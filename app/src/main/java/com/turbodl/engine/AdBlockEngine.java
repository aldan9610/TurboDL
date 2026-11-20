package com.turbodl.engine;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.turbodl.adblock.AdblockPlusEngine;
import com.turbodl.adblock.FilterEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Fachada del bloqueador de anuncios (4.1).
 *
 * <p>La versión 4.1 introduce un <b>cambio de prioridad</b>: el motor
 * primario es ahora la librería madura de Adblock Plus
 * (<code>libadblockplus-android</code>, el binding JNI del C++ que
 * usan las extensiones oficiales ABP de escritorio desde 2013). El
 * motor 100% Kotlin <code>FilterEngine</code> (añadido en 3.6.0)
 * sigue en el APK y se usa como <b>fallback automático</b> cuando la
 * AAR no está presente o no se pudo inicializar (lo que ocurre si
 * jcenter no resuelve el artefacto en el entorno de build). En
 * runtime la elección es transparente para el navegador:
 * <code>shouldBlock</code> consulta primero al motor oficial; si
 * responde, su respuesta es la buena; si no está listo, cae al
 * motor Kotlin; si tampoco, a la lista compacta de hosts.</p>
 *
 * <p>Robustez heredada de 3.5.x:</p>
 * <ul>
 *   <li>Todos los accesos al motor van con <code>catch(Throwable)</code>:
 *       ningún <code>Error</code> detiene la navegación.</li>
 *   <li>Nunca se piden URLs que no sean http/https (about:blank,
 *       chrome-error://, data:, file:).</li>
 *   <li>El CSS cosmético se calcula fuera del hilo principal y se LIMITA
 *       a 256 KB.</li>
 *   <li>Self-test con URLs limpias antes de marcar READY; si falla, el
 *       navegador usa la lista compacta de hosts.</li>
 *   <li>MODO POR RAM: < 1,5 GB solo EasyList (lite, ~10 MB); en adelante
 *       EasyList + EasyPrivacy (~20 MB).</li>
 *   <li>Un fusible del navegador pausa el motor si bloqueara una
 *       proporción anómala de peticiones.</li>
 *   <li><code>ensure()</code> solo se llama con el bloqueador ACTIVADO;
 *       la inicialización ocurre en el arranque de la app
 *       (<code>App.onCreate</code>), lejos de la creación del WebView.</li>
 * </ul>
 *
 * <p>Las listas van INTEGRADAS en el APK: no hace falta conexión la
 * primera vez; Ajustes permite actualizarlas de easylist.to.</p>
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

    /**
     * Lanza la inicialización una sola vez (en segundo plano).
     *
     * 4.1: primero intenta cargar el motor oficial de Adblock Plus
     * (libadblockplus-android). Si la AAR no está presente o falla al
     * inicializarse, continúa con el motor 100% Kotlin (FilterEngine)
     * que ya estaba integrado. La transición es invisible para el
     * navegador.
     */
    public static void ensure(Context ctx) {
        // Intento 1: motor oficial Adblock Plus (con timeout interno).
        try {
            AdblockPlusEngine.ensure(ctx);
        } catch (Throwable e) {
            Log.w("AdBlockEngine", "Adblock Plus no disponible: " + e);
        }
        // Intento 2: motor 100% Kotlin (siempre que el oficial no esté
        // ya listo o siga cargando). Si el oficial acaba de llegar, lo
        // dejamos como primario y no cargamos también EasyList en RAM.
        if (!AdblockPlusEngine.ready()) {
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

    /**
     * true si HAY un motor listo para responder (4.1): primero el
     * oficial de Adblock Plus; si no, el 100% Kotlin. El navegador usa
     * este método para decidir entre la API del motor y la lista
     * compacta de hosts.
     */
    public static boolean ready() {
        return AdblockPlusEngine.ready() || (state == STATE_READY && engine != null);
    }

    /**
     * true si TODOS los motores fallaron y hay que usar la lista
     * compacta de hosts. (4.1: la lista de hosts es ahora el tercer
     * y último nivel de fallback.)
     */
    public static boolean caido() {
        return AdblockPlusEngine.unavailable() && state == STATE_FALLIDO;
    }

    /** Fusible anti-sobre-bloqueo: pausa el motor por esta sesión y deja solo
     *  la lista de hosts. Lo llama el navegador si el motor bloquea una
     *  proporción anómala de peticiones (p. ej. todas las imágenes). */
    public static void deshabilitarSesion() {
        // 4.1: el fusible desactiva AMBOS motores; al volver a activar
        // desde Ajustes se reintenta la inicialización.
        try { AdblockPlusEngine.shutdown(); } catch (Throwable e) { }
        fallo(new Exception("fusible: proporción de bloqueo anómala"));
    }

    /**
     * Resumen legible del estado para el panel de diagnóstico del
     * navegador (4.1): muestra primero el motor oficial Adblock Plus,
     * después el 100% Kotlin y por último la lista de hosts.
     */
    public static String diagnostico(Context ctx) {
        StringBuilder sb = new StringBuilder();
        boolean on = activado(ctx);
        sb.append("- Bloqueador: ").append(on ? "activado" : "desactivado en ajustes")
            .append('\n');
        if (!on) {
            sb.append("- Motor: no se carga (bloqueador apagado)");
            return sb.toString();
        }
        sb.append("- Sitios con bloqueo desactivado: ").append(exemptCount()).append('\n');
        // 4.1: motor oficial Adblock Plus (libadblockplus-android).
        sb.append("- Motor oficial: ").append(AdblockPlusEngine.statusLine()).append('\n');
        // Motor 100% Kotlin (FilterEngine) — fallback.
        if (state == STATE_READY) {
            FilterEngine eng = engine;
            sb.append("- Motor Kotlin (fallback): LISTO (")
                .append(fullLists ? "EasyList + EasyPrivacy" : "solo EasyList");
            if (eng != null) sb.append(", ").append(eng.getNetworkRules()).append(" reglas");
            sb.append(")\n");
        } else if (state == STATE_FALLIDO) {
            sb.append("- Motor Kotlin (fallback): NO EN USO (")
                .append(motivo == null || motivo.isEmpty() ? "no disponible" : motivo)
                .append(")\n- Filtrando con la lista básica de hosts (≈0 MB de RAM)\n");
        } else {
            sb.append("- Motor Kotlin (fallback): cargando…\n");
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

    // ---------------- exención por sitio ("desactivar en este dominio") ----------------

    /** Hosts/dominios con el bloqueo desactivado por decisión del usuario
     *  (persistidos en prefs "adblockExempt" como array JSON). Cache en
     *  memoria: shouldBlock corre en el hilo de red y no debe tocar prefs. */
    private static volatile java.util.Set<String> exentasCache = null;

    private static java.util.Set<String> exentas() {
        java.util.Set<String> s = exentasCache;
        if (s != null) return s;
        synchronized (LOCK) {
            if (exentasCache != null) return exentasCache;
            java.util.Set<String> out = new java.util.HashSet<String>();
            try {
                String raw = com.turbodl.App.app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                        .getString("adblockExempt", "[]");
                org.json.JSONArray arr = new org.json.JSONArray(raw == null ? "[]" : raw);
                for (int i = 0; i < arr.length(); i++) {
                    out.add(arr.getString(i).toLowerCase(Locale.US));
                }
            } catch (Throwable e) { }
            exentasCache = out;
            return out;
        }
    }

    /** Activa o desactiva el bloqueo para el sitio de pageUrl. Guarda el host
     *  completo y su dominio base (ej.: www.ejemplo.com + ejemplo.com). */
    public static void setExempt(Context ctx, String pageUrl, boolean on) {
        String h = hostOf(pageUrl);
        if (h.isEmpty()) return;
        String base = baseDomainOf(h);
        java.util.Set<String> s = new java.util.HashSet<String>(exentas());
        if (on) {
            s.add(h);
            if (!base.isEmpty()) s.add(base);
        } else {
            s.remove(h);
            s.remove(base);
        }
        try {
            java.util.List<String> l = new java.util.ArrayList<String>(s);
            java.util.Collections.sort(l);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String x : l) arr.put(x);
            ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
                    .edit().putString("adblockExempt", arr.toString()).apply();
        } catch (Throwable e) { }
        exentasCache = s;
    }

    /** true si el bloqueo está desactivado para la página indicada. */
    public static boolean exempted(String pageUrl) {
        String h = hostOf(pageUrl);
        if (h.isEmpty()) return false;
        java.util.Set<String> s = exentas();
        if (s.contains(h)) return true;
        String b = baseDomainOf(h);
        return !b.isEmpty() && s.contains(b);
    }

    /** Cuántos sitios tienen el bloqueo desactivado (para diagnóstico). */
    public static int exemptCount() {
        return exentas().size();
    }

    /** Host en minúsculas de una URL ("" si no se puede parsear). */
    static String hostOf(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.US);
        } catch (Throwable e) {
            return "";
        }
    }

    /** Dominio base aproximado: quita la primera etiqueta cuando hay
     *  subdominio (www.ejemplo.com -> ejemplo.com) y devuelve el propio
     *  host si ya es base (ejemplo.com -> ejemplo.com). "" si no hay
     *  dominio (localhost, TLD suelto). */
    static String baseDomainOf(String host) {
        if (host == null || host.isEmpty()) return "";
        int f = host.indexOf('.');
        if (f < 0) return "";
        int g = host.indexOf('.', f + 1);
        if (g < 0) return host;
        return host.substring(f + 1);
    }

    /**
     * true si el subrecurso debe bloquearse. Sin motor (no listo o caído)
     * devuelve false SIEMPRE: fallar es dejar pasar, no bloquear de más.
     *
     * <p>4.1: consulta primero al motor oficial Adblock Plus. Si está
     * listo y responde, su decisión es la definitiva. Si no está listo
     * (cargando) o no está presente, cae al motor 100% Kotlin. Si
     * tampoco, devuelve false (el navegador usará su lista compacta de
     * hosts en el caller).</p>
     */
    public static boolean shouldBlock(String pageUrl, String reqUrl, String type) {
        if (!httpLimpia(pageUrl) || !httpLimpia(reqUrl)) return false;
        try {
            // sitio con el bloqueo desactivado por el usuario: dejar pasar todo
            if (exempted(pageUrl)) return false;
            // Intento 1: motor oficial Adblock Plus (libadblockplus-android).
            if (AdblockPlusEngine.ready()) {
                boolean block = AdblockPlusEngine.shouldBlock(pageUrl, reqUrl, type);
                // El wrapper devuelve false si no está seguro (placeholder
                // conservador). En ese caso, caemos al fallback Kotlin.
                if (block) return true;
            }
            // Intento 2: motor 100% Kotlin (FilterEngine).
            FilterEngine eng = engine;
            if (eng == null) return false;
            // orden del motor: (recurso pedido, página, tipo). El frame
            // principal nunca llega aquí (lo excluye BrowserActivity).
            return eng.shouldBlock(reqUrl, pageUrl, type);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Patrón de la regla que bloquearía reqUrl (para el escudo del
     *  navegador). 4.1: primero el motor oficial; si no, el Kotlin.
     *  Vacío si ningún motor está listo o la URL no se bloquea. */
    public static String ruleFor(String pageUrl, String reqUrl, String type) {
        if (!httpLimpia(pageUrl) || !httpLimpia(reqUrl)) return "";
        try {
            if (exempted(pageUrl)) return "";
            // Intento 1: motor oficial.
            if (AdblockPlusEngine.ready()) {
                String rule = AdblockPlusEngine.ruleFor(pageUrl, reqUrl, type);
                if (rule != null && !rule.isEmpty()) return rule;
            }
            // Intento 2: motor Kotlin.
            FilterEngine eng = engine;
            if (eng == null) return "";
            String p = eng.debugMatch(reqUrl, pageUrl, type);
            return (p == null || p.equals("-")) ? "" : p;
        } catch (Throwable e) {
            return "";
        }
    }

    /** CSS cosmético (ocultar elementos) para la página; vacío si no hay.
     *  4.1: primero el motor oficial; si no, el Kotlin. Llamar desde un
     *  hilo de fondo; el resultado se limita a 256 KB. */
    public static String cssFor(String pageUrl) {
        if (!httpLimpia(pageUrl)) return "";
        try {
            if (exempted(pageUrl)) return "";
            // Intento 1: motor oficial.
            if (AdblockPlusEngine.ready()) {
                String css = AdblockPlusEngine.cssFor(pageUrl);
                if (css != null && !css.isEmpty()) return css;
            }
            // Intento 2: motor Kotlin.
            FilterEngine eng = engine;
            if (eng == null) return "";
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
