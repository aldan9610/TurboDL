package com.turbodl.engine;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.turbodl.adblock.FilterEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Fachada del bloqueador de anuncios. Desde la 3.6.0 el motor es 100%
 * Kotlin (com.turbodl.adblock.FilterEngine, sin JNI ni código nativo) y las
 * listas son EasyList / EasyPrivacy (assets/adblock), la parte madura y
 * mantenida desde hace más de 15 años por easylist.to. El AAR adblock-rust
 * que se usó en 3.4.0-3.5.2 se ELIMINÓ por completo: en móviles de poca RAM
 * su memoria nativa (90-165 MB) estrangulaba al renderizador del WebView.
 *
 * - Filtrado de red: ||host^, comodines, ^, anclas, $script/$image/...,
 *   $third-party, $domain=, @@ y $important (semántica EasyList).
 * - Filtrado cosmético: selectores ## por dominio + genéricos con límite
 *   de tamaño, respetando #@# y las excepciones $elemhide/$generichide.
 * - El filtro aplica SOLO a subrecursos (el frame principal nunca se
 *   bloquea; lo garantiza BrowserActivity).
 *
 * ROBUSTEZ (se conserva de las 3.5.x):
 * - Todos los accesos al motor van con catch(Throwable): ningún Error
 *   detiene la navegación.
 * - Nunca se piden URLs que no sean http/https (about:blank,
 *   chrome-error://, data:, file:).
 * - El CSS cosmético se calcula fuera del hilo principal y se LIMITA a
 *   256 KB.
 * - Self-test con URLs limpias antes de marcar READY; si falla, el
 *   navegador usa la lista compacta de hosts (AD_HOSTS).
 * - MODO POR RAM: < 1,5 GB solo EasyList (lite, ~10 MB); en adelante
 *   EasyList + EasyPrivacy (~20 MB). Nada comparable a los 90-165 MB del
 *   motor nativo.
 * - Un fusible del navegador pausa el motor si bloqueara una proporción
 *   anómala de peticiones.
 * - ensure() solo se llama con el bloqueador ACTIVADO; la inicialización
 *   ocurre en el arranque de la app (App.onCreate), lejos de la creación
 *   del WebView.
 *
 * Las listas van INTEGRADAS en el APK: no hace falta conexión la primera
 * vez; Ajustes permite actualizarlas de easylist.to.
 */
public final class AdBlockEngine {

    /** Resultado asíncrono de updateLists (SAM para lambdas de Kotlin). */
    public interface Cb { void on(boolean ok, String msg); }

    private static final int STATE_INIT = 0;
    private static final int STATE_READY = 1;
    private static final int STATE_FALLIDO = 2;

    private static final int CSS_MAX = 256 * 1024; // cap del CSS cosmético

    private static volatile int state = STATE_INIT;
    private static volatile FilterEngine engine = null;
    private static final Object LOCK = new Object();

    /** false = modo lite (< 1,5 GB RAM): solo EasyList. */
    private static volatile boolean fullLists = true;

    /** Motivo por el que el motor no está disponible (para diagnóstico). */
    private static volatile String motivo = "";

    /** Nombres de archivo de listas en assets/adblock y filesDir/adblock. */
    private static final String[] LISTAS = {"easylist.txt", "easyprivacy.txt"};
    private static final String[] URL_LISTAS = {
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt"};

    private AdBlockEngine() { }

    public static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "adblock");
    }

    /** true si el bloqueador esta activado en ajustes. */
    public static boolean activado(Context ctx) {
        return ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getBoolean("adblock", true);
    }

    /** Lanza la inicialización una sola vez (en segundo plano). */
    public static void ensure(Context ctx) {
        if (state != STATE_INIT) return;
        synchronized (LOCK) {
            if (state != STATE_INIT) return;
            Thread t = new Thread(() -> {
                try { init(ctx); } catch (Throwable e) { fallo(e); }
            });
            t.setDaemon(true);
            t.setName("adblock-init");
            t.start();
        }
    }

    /** Equipos humildes: solo EasyList (el motor Kotlin es ligero, pero
     *  EasyPrivacy añade ~10 MB de reglas que en 1 GB no valen la pena). */
    private static boolean esLowRam(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem < 1500L * 1024 * 1024;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void init(Context ctx) throws Exception {
        fullLists = !esLowRam(ctx);
        File d = dir(ctx);
        d.mkdirs();
        // limpieza del legado del motor Rust: cache serializado ya sin uso
        for (String legado : new String[]{"engine-full.bin", "engine-lite.bin", "mode.txt"}) {
            try { new File(d, legado).delete(); } catch (Throwable e) { }
        }
        for (String n : LISTAS) {
            File f = new File(d, n);
            if (!f.exists() || f.length() < 1000) {
                try (InputStream inp = ctx.getAssets().open("adblock/" + n);
                     OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = inp.read(buf)) > 0) out.write(buf, 0, r);
                }
            }
        }
        FilterEngine eng = new FilterEngine();
        StringBuilder sb = new StringBuilder();
        int n = fullLists ? LISTAS.length : 1; // lite: solo EasyList
        for (int i = 0; i < n; i++) {
            sb.append(new String(java.nio.file.Files.readAllBytes(
                new File(d, LISTAS[i]).toPath()), java.nio.charset.StandardCharsets.UTF_8));
            sb.append('\n');
        }
        boolean ok;
        try {
            ok = eng.parse(sb.toString()) > 0;
        } catch (Throwable e) {
            fallo(e);
            return;
        }
        if (ok) {
            // self-test con URLs limpias: si el motor no responde bien,
            // no se marca READY y el navegador usa la lista de hosts
            boolean test;
            try {
                test = eng.shouldBlock(
                    "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                    "https://www.ejemplo.com/", "script")
                    && !eng.shouldBlock(
                    "https://www.ejemplo.com/css/main.css",
                    "https://www.ejemplo.com/", "stylesheet");
            } catch (Throwable e) {
                test = false;
            }
            if (!test) {
                state = STATE_FALLIDO;
                motivo = "self-test falló: usando lista de hosts";
                Log.w("AdBlockEngine", motivo);
                return;
            }
            synchronized (LOCK) {
                engine = eng;
                state = STATE_READY;
            }
        } else {
            fallo(new Exception("las listas no produjeron reglas"));
        }
    }

    private static void fallo(Throwable e) {
        synchronized (LOCK) {
            state = STATE_FALLIDO;
            motivo = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            engine = null;
        }
        Log.w("AdBlockEngine", "motor no disponible: " + e);
    }

    /** true si el motor está listo; false = usar el fallback de hosts. */
    public static boolean ready() {
        return state == STATE_READY && engine != null;
    }

    /** true si el motor falló y hay que usar la lista compacta de hosts. */
    public static boolean caido() {
        return state == STATE_FALLIDO;
    }

    /** Fusible anti-sobre-bloqueo: pausa el motor por esta sesión y deja solo
     *  la lista de hosts. Lo llama el navegador si el motor bloquea una
     *  proporción anómala de peticiones (p. ej. todas las imágenes). */
    public static void deshabilitarSesion() {
        fallo(new Exception("fusible: proporción de bloqueo anómala"));
    }

    /** Resumen legible del estado para el panel de diagnóstico del navegador. */
    public static String diagnostico(Context ctx) {
        StringBuilder sb = new StringBuilder();
        boolean on = activado(ctx);
        sb.append("- Bloqueador: ").append(on ? "activado" : "desactivado en ajustes")
            .append('\n');
        if (!on) {
            sb.append("- Motor: no se carga (bloqueador apagado)");
            return sb.toString();
        }
        if (state == STATE_READY) {
            FilterEngine eng = engine;
            sb.append("- Motor: KOTLIN LISTO (")
                .append(fullLists ? "EasyList + EasyPrivacy" : "solo EasyList");
            if (eng != null) sb.append(", ").append(eng.getNetworkRules()).append(" reglas");
            sb.append(")\n");
        } else if (state == STATE_FALLIDO) {
            sb.append("- Motor: NO EN USO (")
                .append(motivo == null || motivo.isEmpty() ? "no disponible" : motivo)
                .append(")\n- Filtrando con la lista básica de hosts (≈0 MB de RAM)\n");
        } else {
            sb.append("- Motor: cargando…\n");
        }
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                sb.append("- RAM: ").append(mi.availMem / (1024L * 1024L))
                    .append(" MB libres de ").append(mi.totalMem / (1024L * 1024L))
                    .append(" MB\n");
            }
        } catch (Throwable e) { }
        return sb.toString();
    }

    private static boolean httpLimpia(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * true si el subrecurso debe bloquearse. Sin motor (no listo o caído)
     * devuelve false SIEMPRE: fallar es dejar pasar, no bloquear de más.
     */
    public static boolean shouldBlock(String pageUrl, String reqUrl, String type) {
        FilterEngine eng = engine;
        if (eng == null || !httpLimpia(pageUrl) || !httpLimpia(reqUrl)) return false;
        try {
            // orden del motor: (recurso pedido, página, tipo). El frame
            // principal nunca llega aquí (lo excluye BrowserActivity).
            return eng.shouldBlock(reqUrl, pageUrl, type);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Patrón de la regla que bloquearía reqUrl (para el escudo del navegador).
     *  Vacío si el motor no está listo o la URL no se bloquea. */
    public static String ruleFor(String pageUrl, String reqUrl, String type) {
        FilterEngine eng = engine;
        if (eng == null || !httpLimpia(pageUrl) || !httpLimpia(reqUrl)) return "";
        try {
            String p = eng.debugMatch(reqUrl, pageUrl, type);
            return (p == null || p.equals("-")) ? "" : p;
        } catch (Throwable e) {
            return "";
        }
    }

    /** CSS cosmético (ocultar elementos) para la página; vacío si no hay.
     *  Llamar desde un hilo de fondo; se limita a 256 KB. */
    public static String cssFor(String pageUrl) {
        FilterEngine eng = engine;
        if (eng == null || !httpLimpia(pageUrl)) return "";
        try {
            String css = eng.cssFor(pageUrl, CSS_MAX);
            if (css == null) return "";
            return css;
        } catch (Throwable e) {
            return "";
        }
    }

    /** Tipo de recurso para el motor, deducido de la URL. */
    public static String typeOf(String url) {
        String u = url.split("\\?")[0].split("#")[0].toLowerCase(Locale.US);
        if (u.endsWith(".js")) return "script";
        if (u.endsWith(".css")) return "stylesheet";
        if (u.matches(".*\\.(png|jpe?g|gif|webp|svg|ico|bmp)$")) return "image";
        if (u.matches(".*\\.(woff2?|ttf|otf|eot)$")) return "font";
        if (u.matches(".*\\.(m3u8|mpd|mp4|webm|ts|m4s|mov|mkv|flv|avi)$")) return "media";
        if (u.matches(".*\\.(mp3|aac|m4a|ogg|opus|flac|wav)$")) return "media";
        if (u.endsWith(".html") || u.endsWith(".htm") || u.endsWith("/")) return "document";
        return "xmlhttprequest";
    }

    /**
     * Descarga listas frescas de easylist.to y reconstruye el motor.
     * cb(ok, msg) llega en el hilo principal.
     */
    public static void updateLists(Context ctx, Cb cb) {
        Thread t = new Thread(() -> {
            int okN = 0;
            try {
                File d = dir(ctx);
                d.mkdirs();
                for (int i = 0; i < LISTAS.length; i++) {
                    if (descarga(URL_LISTAS[i], new File(d, LISTAS[i]))) okN++;
                }
            } catch (Throwable e) { }
            final int okF = okN;
            if (okF == 0) {
                uiPost(() -> cb.on(false, "sin conexión con easylist.to"));
                return;
            }
            try {
                synchronized (LOCK) {
                    state = STATE_INIT;
                    engine = null;
                }
                init(ctx);
                final boolean listo = ready();
                uiPost(() -> cb.on(listo, listo
                    ? "listas actualizadas (" + okF + " de 2)"
                    : "el motor no pudo recargar"));
            } catch (Throwable e) {
                final String msg = e.getMessage() == null ? "error" : e.getMessage();
                uiPost(() -> cb.on(false, msg));
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void uiPost(Runnable f) {
        new Handler(Looper.getMainLooper()).post(f);
    }

    private static boolean descarga(String url, File dest) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "TurboDL/3.6");
            if (conn.getResponseCode() < 200 || conn.getResponseCode() > 299) return false;
            File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
            if (tmp.length() < 10000) { tmp.delete(); return false; }
            // validar cabecera de lista ABP
            InputStream in = tmp.toURI().toURL().openStream();
            byte[] head = new byte[200];
            int n = in.read(head);
            in.close();
            String h = new String(head, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8);
            if (!h.contains("[Adblock")) { tmp.delete(); return false; }
            if (dest.exists()) dest.delete();
            return tmp.renameTo(dest);
        } catch (Throwable e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
