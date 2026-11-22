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
 * Fachada del bloqueador de anuncios (4.9).
 *
 * <p>El motor es 100% Kotlin ({@link FilterEngine} + {@code HostsAdapter}).
 * La 4.9 elimina el wrapper muerto de la librería nativa Adblock Plus
 * (libadblockplus-android, V8, ~30 MB) cuya AAR dejó de resolverse al
 * retirarse jcenter en 2021: siempre devolvía "no instalado" sin aportar
 * nada, y confundía al usuario con el mensaje "Adblock Plus no
 * instalado" cuando el bloqueo sí funcionaba (lo hacía FilterEngine).</p>
 *
 * <p>4.9: el usuario ELIGE el motor en Ajustes → Navegador → Motor de
 * bloqueo (preferencia {@code adblockEngine} en SharedPreferences "cfg"):</p>
 * <ul>
 *   <li><b>"kotlin"</b> (default): {@link FilterEngine} carga TODAS las
 *       listas seleccionadas (EasyList, EasyPrivacy, AdGuard, Fanboy,
 *       EasyList Spanish…) con reglas de red + cosméticos, y las tipo
 *       hosts (StevenBlack) vía {@code HostsAdapter}. Bloqueo completo.</li>
 *   <li><b>"hosts"</b>: solo se cargan las listas {@code Format.HOSTS}
 *       del catálogo, convertidas a EasyList. Modo mínimo: ligero, sin
 *       cosméticos, sin falsos positivos, pero solo bloquea dominios
 *       enteros (sin reglas finas de red ni ocultamiento de elementos).</li>
 * </ul>
 *
 * <p>Robustez heredada de 3.5.x–4.1:</p>
 * <ul>
 *   <li>Todos los accesos al motor van con <code>catch(Throwable)</code>:
 *       ningún <code>Error</code> detiene la navegación.</li>
 *   <li>Nunca se piden URLs que no sean http/https (about:blank,
 *       chrome-error://, data:, file:).</li>
 *   <li>El CSS cosmético se calcula fuera del hilo principal y se LIMITA
 *       a 256 KB.</li>
 *   <li>Self-test con URLs limpias antes de marcar READY; si falla, el
 *       navegador usa la lista compacta de hosts.</li>
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

    /** 4.9: motor elegido por el usuario en Ajustes.
     *  "kotlin" = FilterEngine con todas las listas (default);
     *  "hosts"  = solo listas Format.HOSTS vía HostsAdapter. */
    public static final String ENGINE_KOTLIN = "kotlin";
    public static final String ENGINE_HOSTS = "hosts";

    /** Devuelve el motor elegido ("kotlin" o "hosts"); nunca null. */
    public static String selectedEngine(Context ctx) {
        String s = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getString("adblockEngine", ENGINE_KOTLIN);
        return ENGINE_HOSTS.equals(s) ? ENGINE_HOSTS : ENGINE_KOTLIN;
    }

    /** 4.9: cambia el motor y fuerza la recarga en segundo plano.
     *  Llamar desde el hilo UI tras el cambio en Ajustes. Si el
     *  bloqueador está apagado, solo persiste la preferencia (init()
     *  correrá cuando se active). */
    public static void setEngine(Context ctx, String engineId) {
        String val = ENGINE_HOSTS.equals(engineId) ? ENGINE_HOSTS : ENGINE_KOTLIN;
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .edit().putString("adblockEngine", val).apply();
        if (activado(ctx)) {
            synchronized (LOCK) {
                state = STATE_INIT;
                engine = null;
            }
            ensure(ctx);
        }
    }

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
     * 4.9: solo queda FilterEngine; el motor se elige en Ajustes
     * (kotlin/hosts) y {@link #init} filtra las listas según el modo.
     */
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
        // 4.2: cargar las listas seleccionadas por el usuario desde el
        // catálogo (AdblockCatalog). Si una lista no está en disco (primera
        // vez o descarga fallida) y existe como asset embebido (easylist,
        // easyprivacy), se copia desde assets; si no, se salta. Las listas
        // tipo hosts pasan por HostsAdapter que las convierte a sintaxis
        // EasyList (||host^) para que FilterEngine las entienda.
        // 4.9: en modo "hosts" SOLO se cargan las listas Format.HOSTS del
        // catálogo (ignorando las EasyList que el usuario tenga marcadas),
        // para un bloqueo mínimo por dominio. En modo "kotlin" se cargan
        // todas las seleccionadas (EasyList + hosts).
        boolean modoHosts = ENGINE_HOSTS.equals(selectedEngine(ctx));
        java.util.List<com.turbodl.adblock.AdblockCatalog.Entry> sel =
            com.turbodl.adblock.AdblockCatalog.selected(ctx);
        StringBuilder sb = new StringBuilder();
        int cargadas = 0;
        for (com.turbodl.adblock.AdblockCatalog.Entry e : sel) {
            // 4.9: en modo hosts, saltar las listas que no son HOSTS.
            if (modoHosts && e.format != com.turbodl.adblock.AdblockCatalog.Format.HOSTS)
                continue;
            File f = new File(d, e.id + ".txt");
            // si la lista no existe en disco, intentamos copiarla desde
            // assets (easylist y easyprivacy vienen embebidos en el APK).
            if (!f.exists() || f.length() < 1000) {
                try (InputStream inp = ctx.getAssets().open("adblock/" + e.id + ".txt");
                     OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = inp.read(buf)) > 0) out.write(buf, 0, r);
                } catch (Throwable ex) {
                    // no está embebida: se saltará si tampoco está descargada
                }
            }
            if (!f.exists() || f.length() < 1000) continue;
            String content = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
            // 4.2: si es formato hosts, convertir a EasyList
            if (e.format == com.turbodl.adblock.AdblockCatalog.Format.HOSTS) {
                content = com.turbodl.adblock.HostsAdapter.toEasyList(content);
            }
            sb.append(content);
            sb.append('\n');
            cargadas++;
        }
        // Si por la razón que sea ninguna lista se cargó (todas fallaron),
        // caer a los assets clásicos easylist+easyprivacy si existen,
        // para no dejar el motor vacío y romper el self-test.
        // 4.9: en modo "hosts" este fallback no aplica (no tendría sentido
        // cargar EasyList cuando el usuario eligió solo hosts); en ese
        // caso se reporta fallo y el navegador usará su lista compacta.
        if (cargadas == 0 && !modoHosts) {
            for (String n : LISTAS) {
                File f = new File(d, n);
                if (!f.exists() || f.length() < 1000) {
                    try (InputStream inp = ctx.getAssets().open("adblock/" + n);
                         OutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[64 * 1024];
                        int r;
                        while ((r = inp.read(buf)) > 0) out.write(buf, 0, r);
                    } catch (Throwable ex) { }
                }
                if (!f.exists() || f.length() < 1000) continue;
                sb.append(new String(java.nio.file.Files.readAllBytes(
                    f.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                sb.append('\n');
                cargadas++;
            }
        }
        if (cargadas == 0) {
            fallo(new Exception("no se pudo cargar ninguna lista de filtros"));
            return;
        }
        FilterEngine eng = new FilterEngine();
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
     * true si HAY un motor listo para responder (4.9): solo FilterEngine
     * (modo kotlin o hosts, ambos corren sobre el mismo FilterEngine con
     * listas distintas). El navegador usa este método para decidir entre
     * la API del motor y la lista compacta de hosts.
     */
    public static boolean ready() {
        return state == STATE_READY && engine != null;
    }

    /**
     * true si el motor falló y hay que usar la lista compacta de hosts
     * del navegador (tercer y último nivel de fallback).
     */
    public static boolean caido() {
        return state == STATE_FALLIDO;
    }

    /** Fusible anti-sobre-bloqueo: pausa el motor por esta sesión y deja solo
     *  la lista de hosts. Lo llama el navegador si el motor bloquea una
     *  proporción anómala de peticiones (p. ej. todas las imágenes). */
    public static void deshabilitarSesion() {
        fallo(new Exception("fusible: proporción de bloqueo anómala"));
    }

    /** 4.9: nombre legible del motor elegido, para UI y diagnóstico. */
    public static String engineName(Context ctx) {
        return ENGINE_HOSTS.equals(selectedEngine(ctx))
            ? "Solo hosts" : "FilterEngine (Kotlin)";
    }

    /**
     * Resumen legible del estado para el panel de diagnóstico del
     * navegador (4.9): muestra el motor elegido y su estado, sin el
     * mensaje confuso "Adblock Plus no instalado" de la 4.1-4.8.
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
        sb.append("- Motor: ").append(engineName(ctx)).append('\n');
        sb.append("- Sitios con bloqueo desactivado: ").append(exemptCount()).append('\n');
        // 4.2: listas seleccionadas por el usuario
        java.util.List<com.turbodl.adblock.AdblockCatalog.Entry> sel =
            com.turbodl.adblock.AdblockCatalog.selected(ctx);
        if (sel.isEmpty()) {
            sb.append("- Listas: ninguna seleccionada\n");
        } else {
            sb.append("- Listas (").append(sel.size()).append("): ");
            for (int i = 0; i < sel.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(sel.get(i).name);
            }
            sb.append('\n');
        }
        // 4.9: estado del motor (sin mención a Adblock Plus).
        if (state == STATE_READY) {
            FilterEngine eng = engine;
            sb.append("- Estado: LISTO");
            if (eng != null) sb.append(" (").append(eng.getNetworkRules()).append(" reglas)");
            sb.append('\n');
        } else if (state == STATE_FALLIDO) {
            sb.append("- Estado: NO DISPONIBLE (")
                .append(motivo == null || motivo.isEmpty() ? "error de carga" : motivo)
                .append(")\n- Filtrando con la lista básica de hosts (≈0 MB de RAM)\n");
        } else {
            sb.append("- Estado: cargando…\n");
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
     * 4.9: solo FilterEngine (modo kotlin o hosts); el motor oficial ABP
     * se eliminó.
     */
    public static boolean shouldBlock(String pageUrl, String reqUrl, String type) {
        if (!httpLimpia(pageUrl) || !httpLimpia(reqUrl)) return false;
        try {
            // sitio con el bloqueo desactivado por el usuario: dejar pasar todo
            if (exempted(pageUrl)) return false;
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
     *  navegador). Vacío si el motor no está listo o la URL no se bloquea. */
    public static String ruleFor(String pageUrl, String reqUrl, String type) {
        if (!httpLimpia(pageUrl) || !httpLimpia(reqUrl)) return "";
        try {
            if (exempted(pageUrl)) return "";
            FilterEngine eng = engine;
            if (eng == null) return "";
            String p = eng.debugMatch(reqUrl, pageUrl, type);
            return (p == null || p.equals("-")) ? "" : p;
        } catch (Throwable e) {
            return "";
        }
    }

    /** CSS cosmético (ocultar elementos) para la página; vacío si no hay.
     *  Llamar desde un hilo de fondo; el resultado se limita a 256 KB.
     *  4.9: en modo "hosts" el motor no tiene cosméticos (solo reglas de
     *  red por dominio), así que esto devuelve "" naturalmente — el
     *  motor se cargó solo con listas HOSTS y FilterEngine.cssFor no
     *  encuentra selectores. */
    public static String cssFor(String pageUrl) {
        if (!httpLimpia(pageUrl)) return "";
        try {
            if (exempted(pageUrl)) return "";
            FilterEngine eng = engine;
            if (eng == null) return "";
            String css = eng.cssFor(pageUrl, CSS_MAX);
            return css == null ? "" : css;
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
     * Descarga las listas seleccionadas por el usuario (4.2: antes
     * solo EasyList+EasyPrivacy) y reconstruye el motor. cb(ok, msg)
     * llega en el hilo principal. Las listas tipo hosts también pasan
     * por descarga + HostsAdapter al cargar (no al descargar).
     */
    public static void updateLists(Context ctx, Cb cb) {
        Thread t = new Thread(() -> {
            int okN = 0;
            int totalN = 0;
            try {
                File d = dir(ctx);
                d.mkdirs();
                // 4.9: en modo "hosts" solo se descargan las listas
                // Format.HOSTS del catálogo (las EasyList se ignoran para
                // no gastar datos en listas que init() no va a cargar).
                boolean modoHosts = ENGINE_HOSTS.equals(selectedEngine(ctx));
                java.util.List<com.turbodl.adblock.AdblockCatalog.Entry> sel =
                    com.turbodl.adblock.AdblockCatalog.selected(ctx);
                totalN = sel.size();
                for (com.turbodl.adblock.AdblockCatalog.Entry e : sel) {
                    if (modoHosts && e.format != com.turbodl.adblock.AdblockCatalog.Format.HOSTS) {
                        totalN--;
                        continue;
                    }
                    File dest = new File(d, e.id + ".txt");
                    // las listas tipo hosts no tienen cabecera [Adblock;
                    // descarga las admite igual porque StevenBlack trae un
                    // encabezado con [Adblock], pero si una futura no lo
                    // trajera, la conversión de HostsAdapter añade la suya.
                    if (descargaLista(e, dest)) okN++;
                }
            } catch (Throwable e) { }
            final int okF = okN;
            final int totalF = totalN;
            if (okF == 0) {
                uiPost(() -> cb.on(false, "sin conexión o listas no accesibles"));
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
                    ? "listas actualizadas (" + okF + " de " + totalF + ")"
                    : "el motor no pudo recargar (las listas se guardaron)"));
            } catch (Throwable e) {
                final String msg = e.getMessage() == null ? "error" : e.getMessage();
                uiPost(() -> cb.on(false, msg));
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** Descarga una entrada del catálogo a su archivo. Para las listas
     *  tipo hosts, valida solo tamaño mínimo (la cabecera [Adblock no
     *  aparece en todas ellas). Para EasyList, exige la cabecera. */
    private static boolean descargaLista(com.turbodl.adblock.AdblockCatalog.Entry e, File dest) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(e.url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "TurboDL/4.2");
            if (conn.getResponseCode() < 200 || conn.getResponseCode() > 299) return false;
            File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            }
            if (tmp.length() < 5000) { tmp.delete(); return false; }
            // validación: las EasyList deben tener [Adblock; las hosts no.
            if (e.format == com.turbodl.adblock.AdblockCatalog.Format.EASYLIST) {
                InputStream in = tmp.toURI().toURL().openStream();
                byte[] head = new byte[200];
                int n = in.read(head);
                in.close();
                String h = new String(head, 0, Math.max(0, n),
                    java.nio.charset.StandardCharsets.UTF_8);
                if (!h.contains("[Adblock")) { tmp.delete(); return false; }
            }
            if (dest.exists()) dest.delete();
            return tmp.renameTo(dest);
        } catch (Throwable ex) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
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
