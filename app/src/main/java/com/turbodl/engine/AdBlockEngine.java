package com.turbodl.engine;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.mlm.adblock.AdblockEngine;
import org.mlm.adblock.CosmeticResources;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Motor de bloqueo de anuncios basado en adblock-rust (el mismo motor del
 * navegador Brave), vendido como libs/adblock-0.1.3.aar con JNI nativo.
 *
 * - Filtrado de red completo: EasyList (+ EasyPrivacy si hay RAM), reglas
 *   $script/$image/$third-party/$redirect y excepciones @@.
 * - Filtrado cosmético: CSS para ocultar huecos de anuncios por página.
 * - Scriptlets y recursos $redirect de Brave (assets/adblock/resources.json).
 *
 * ROBUSTEZ (v3.5.0, tras el crash en telefono sin conexión):
 * - TODOS los accesos al motor usan catch(Throwable): un UnsatisfiedLinkError
 *   o NoClassDefFoundError (que son Error, no Exception) degrada el motor a
 *   la lista compacta de hosts en vez de matar el proceso.
 * - Nunca se llama al nativo con URLs que no sean http/https (about:blank,
 *   chrome-error://chromewebdata/ de las páginas de error, data:, file:):
 *   el JNI las rechaza con una RuntimeException, pero se evitan del todo.
 * - El CSS cosmético se calcula FUERA del hilo principal y se LIMITA a
 *   256 KB: los selectores genéricos de EasyList pueden ser megas y
 *   construirlos en el hilo UI provocaba OutOfMemoryError (Error sin
 *   capturar -> crash), sobre todo en páginas de error sin conexión.
 * - Un self-test con URLs limpias valida el motor antes de marcarlo READY.
 * - Equipos con menos de 1,5 GB de RAM cargan solo EasyList (modo lite) y
 *   guardan un cache distinto (engine-lite.bin); EasyPrivacy es de
 *   rastreadores, EasyList sigue bloqueando los anuncios.
 * - Un NATIVE_LOCK serializa llamadas nativas y sustituciones del motor
 *   (updateLists) para evitar use-after-free al cerrar el motor en uso.
 * - ensure() solo se llama con el bloqueador ACTIVADO; la inicialización
 *   ocurre en el arranque de la app (App.onCreate), lejos de la creación
 *   del WebView del navegador.
 *
 * Las listas van INTEGRADAS en el APK (assets/adblock): no hace falta
 * conexión la primera vez; Ajustes permite actualizarlas de easylist.to.
 */
public final class AdBlockEngine {

    /** Resultado asíncrono de updateLists (SAM para lambdas de Kotlin). */
    public interface Cb { void on(boolean ok, String msg); }

    private static final int STATE_INIT = 0;
    private static final int STATE_READY = 1;
    private static final int STATE_FALLIDO = 2;

    private static final int CSS_MAX = 256 * 1024; // cap del CSS cosmético

    private static volatile int state = STATE_INIT;
    private static volatile AdblockEngine engine = null;
    private static final Object LOCK = new Object();
    private static final Object NATIVE_LOCK = new Object();

    /** false = modo lite (< 1,5 GB RAM): solo EasyList. */
    private static volatile boolean fullLists = true;

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

    /** Equipos humildes: solo EasyList para reducir RAM y tiempo de carga. */
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
        // modo persistido para invalidar el cache si cambia lite<->full
        String modo = fullLists ? "full" : "lite";
        try {
            File mf = new File(d, "mode.txt");
            String prev = mf.exists()
                ? new String(java.nio.file.Files.readAllBytes(mf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim()
                : "";
            if (!prev.equals(modo)) {
                new File(d, cacheName()).delete();
                FileOutputStream fo = new FileOutputStream(mf);
                fo.write(modo.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fo.close();
            }
        } catch (Throwable e) { }
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
        AdblockEngine eng;
        try {
            eng = AdblockEngine.Companion.create();
        } catch (Throwable e) {
            fallo(e); // UnsatisfiedLinkError etc.
            return;
        }
        File bin = new File(d, cacheName());
        boolean ok = false;
        if (bin.exists() && bin.length() > 100000) {
            // arranque rápido: estado serializado del motor
            try {
                synchronized (NATIVE_LOCK) { ok = eng.deserializeFrom(bin); }
            } catch (Throwable e) { ok = false; }
        }
        if (!ok) {
            StringBuilder sb = new StringBuilder();
            int n = fullLists ? LISTAS.length : 1; // lite: solo EasyList
            for (int i = 0; i < n; i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(new String(java.nio.file.Files.readAllBytes(
                    new File(d, LISTAS[i]).toPath()), java.nio.charset.StandardCharsets.UTF_8));
            }
            try {
                synchronized (NATIVE_LOCK) { ok = eng.loadFilterList(sb.toString()); }
            } catch (Throwable e) {
                fallo(e);
                return;
            }
            if (ok) {
                try {
                    synchronized (NATIVE_LOCK) { eng.serializeTo(bin); }
                } catch (Throwable e) { /* cache opcional */ }
            }
        }
        if (ok) {
            // scriptlets y $redirect (recursos tipo Brave incluidos en el AAR)
            try {
                InputStream is = ctx.getAssets().open("adblock/resources.json");
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[32 * 1024];
                int r;
                while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
                is.close();
                synchronized (NATIVE_LOCK) { eng.loadResources(bo.toString("UTF-8")); }
            } catch (Throwable e) { /* opcional */ }
            // self-test con URLs limpias: si el motor no responde bien,
            // no se marca READY y el navegador usa la lista de hosts
            boolean test;
            try {
                synchronized (NATIVE_LOCK) {
                    // orden del wrapper: (url=RECURSO, sourceUrl=PÁGINA, tipo)
                    test = eng.shouldBlock(
                        "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                        "https://www.ejemplo.com/",
                        "script")
                        && !eng.shouldBlock(
                        "https://www.ejemplo.com/css/main.css",
                        "https://www.ejemplo.com/",
                        "stylesheet");
                }
            } catch (Throwable e) {
                test = false;
            }
            if (!test) {
                try { eng.close(); } catch (Throwable e) { }
                state = STATE_FALLIDO;
                Log.w("AdBlockEngine", "self-test falló: usando lista de hosts");
                return;
            }
            synchronized (LOCK) {
                engine = eng;
                state = STATE_READY;
            }
        } else {
            fallo(new Exception("loadFilterList devolvió false"));
        }
    }

    private static String cacheName() {
        return fullLists ? "engine-full.bin" : "engine-lite.bin";
    }

    private static void fallo(Throwable e) {
        synchronized (LOCK) {
            state = STATE_FALLIDO;
            AdblockEngine eng = engine;
            engine = null;           // primero fuera de alcance...
            if (eng != null) {
                try { eng.close(); } catch (Throwable e2) { } // ...después close
            }
        }
        Log.w("AdBlockEngine", "motor nativo no disponible: " + e);
    }

    /** true si el motor Rust está listo; false = usar el fallback de hosts. */
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

    private static boolean httpLimpia(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    public static boolean shouldBlock(String pageUrl, String reqUrl, String type) {
        AdblockEngine eng = engine;
        if (eng == null || !httpLimpia(pageUrl) || !httpLimpia(reqUrl)) return false;
        try {
            synchronized (NATIVE_LOCK) {
                // ORDEN DEL WRAPPER (verificado en el fuente Kotlin y probado
                // con las bindings Python del mismo crate): shouldBlock(url,
                // sourceUrl, type) donde url = RECURSO pedido y sourceUrl =
                // PÁGINA. En 3.5.0 se enviaron invertidos y el motor quedó
                // inerte (0 bloqueos, comprobado empíricamente).
                return eng.shouldBlock(reqUrl, pageUrl, type);
            }
        } catch (Throwable e) {
            return false;
        }
    }

    /** CSS cosmético (ocultar elementos) para la página; vacío si no hay.
     *  Llamar desde un hilo de fondo: puede tardar y devolver cientos de KB. */
    public static String cssFor(String pageUrl) {
        AdblockEngine eng = engine;
        if (eng == null || !httpLimpia(pageUrl)) return "";
        try {
            CosmeticResources cr;
            synchronized (NATIVE_LOCK) {
                cr = eng.cosmeticResources(pageUrl);
            }
            if (cr == null) return "";
            String css = cr.css();
            // cap: selectores genéricos de EasyList pueden ser megas; si
            // desborda, se recorta por líneas para no reventar el heap
            if (css == null || css.isEmpty()) return "";
            if (css.length() > CSS_MAX) {
                int cut = css.lastIndexOf('\n', CSS_MAX);
                css = cut > 1000 ? css.substring(0, cut) : "";
            }
            return css;
        } catch (Throwable e) {
            return "";
        }
    }

    /** Tipo de recurso adblock-rust deducido de la URL. */
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
     * Descarga listas frescas de easylist.to, borra el estado serializado y
     * reconstruye el motor. cb(ok, msg) llega en el hilo principal.
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
                    AdblockEngine eng = engine;
                    engine = null;
                    if (eng != null) {
                        try {
                            synchronized (NATIVE_LOCK) { eng.close(); }
                        } catch (Throwable e) { }
                    }
                }
                new File(dir(ctx), cacheName()).delete();
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
            conn.setRequestProperty("User-Agent", "TurboDL/3.5");
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
