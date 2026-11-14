package com.turbodl.engine;

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
 * - Filtrado de red completo: EasyList + EasyPrivacy (reglas $script,
 *   $image, $third-party, $redirect, excepciones @@...).
 * - Filtrado cosmético: CSS para ocultar huecos de anuncios por página.
 * - Scriptlets y recursos $redirect de Brave (assets/adblock/resources.json
 *   que trae el propio AAR).
 *
 * Rendimiento: cada comprobación es nativa (microsegundos). El motor se
 * serializa a engine.bin tras la primera carga: los arranques siguientes
 * deserializan en milisegundos en vez de reparsear ~3,5 MB de reglas.
 *
 * Escrito en Java (no en Kotlin): el AAR trae metadatos de Kotlin 2.4 que el
 * compilador 1.9 del proyecto no puede leer; a nivel Java las clases del
 * wrapper son totalmente usables.
 *
 * Si el motor nativo falla al iniciar (p. ej. ABI inesperada), el estado
 * pasa a FALLIDO y el navegador cae de vuelta a la lista compacta de hosts.
 */
public final class AdBlockEngine {

    /** Resultado asíncrono de updateLists (SAM para lambdas de Kotlin). */
    public interface Cb { void on(boolean ok, String msg); }

    private static final int STATE_INIT = 0;
    private static final int STATE_READY = 1;
    private static final int STATE_FALLIDO = 2;

    private static volatile int state = STATE_INIT;
    private static volatile AdblockEngine engine = null;
    private static final Object LOCK = new Object();

    /** Nombres de archivo de listas en assets/adblock y filesDir/adblock. */
    private static final String[] LISTAS = {"easylist.txt", "easyprivacy.txt"};
    private static final String[] URL_LISTAS = {
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt"};

    private AdBlockEngine() { }

    public static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "adblock");
    }

    /** Lanza la inicialización una sola vez (en segundo plano). */
    public static void ensure(Context ctx) {
        if (state != STATE_INIT) return;
        synchronized (LOCK) {
            if (state != STATE_INIT) return;
            Thread t = new Thread(() -> {
                try { init(ctx); } catch (Exception e) { fallo(e); }
            });
            t.setDaemon(true);
            t.setName("adblock-init");
            t.start();
        }
    }

    private static void init(Context ctx) throws Exception {
        File d = dir(ctx);
        d.mkdirs();
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
        AdblockEngine eng = AdblockEngine.Companion.create();
        File bin = new File(d, "engine.bin");
        boolean ok = false;
        if (bin.exists() && bin.length() > 100000) {
            // arranque rápido: estado serializado del motor
            try { ok = eng.deserializeFrom(bin); } catch (Exception e) { ok = false; }
        }
        if (!ok) {
            StringBuilder sb = new StringBuilder();
            for (String n : LISTAS) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(new String(java.nio.file.Files.readAllBytes(
                    new File(d, n).toPath()), java.nio.charset.StandardCharsets.UTF_8));
            }
            ok = eng.loadFilterList(sb.toString());
            if (ok) {
                try { eng.serializeTo(bin); } catch (Exception e) { /* cache opcional */ }
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
                eng.loadResources(bo.toString("UTF-8"));
            } catch (Exception e) { /* opcional */ }
            engine = eng;
            state = STATE_READY;
        } else {
            fallo(new Exception("loadFilterList devolvió false"));
        }
    }

    private static void fallo(Exception e) {
        state = STATE_FALLIDO;
        try { if (engine != null) engine.close(); } catch (Exception e2) { }
        engine = null;
        Log.w("AdBlockEngine", "motor nativo no disponible: " + e.getMessage());
    }

    /** true si el motor Rust está listo; false = usar el fallback de hosts. */
    public static boolean ready() {
        return state == STATE_READY && engine != null;
    }

    /** true si el motor falló y hay que usar la lista compacta de hosts. */
    public static boolean caido() {
        return state == STATE_FALLIDO;
    }

    public static boolean shouldBlock(String pageUrl, String reqUrl, String type) {
        AdblockEngine eng = engine;
        if (eng == null) return false;
        try { return eng.shouldBlock(pageUrl, reqUrl, type); } catch (Exception e) { return false; }
    }

    /** CSS cosmético (ocultar elementos) para la página; vacío si no hay. */
    public static String cssFor(String pageUrl) {
        AdblockEngine eng = engine;
        if (eng == null) return "";
        try {
            CosmeticResources cr = eng.cosmeticResources(pageUrl);
            return cr == null ? "" : cr.css();
        } catch (Exception e) { return ""; }
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
            } catch (Exception e) { }
            final int okF = okN;
            if (okF == 0) {
                uiPost(() -> cb.on(false, "sin conexión con easylist.to"));
                return;
            }
            try {
                synchronized (LOCK) {
                    state = STATE_INIT;
                    try { if (engine != null) engine.close(); } catch (Exception e) { }
                    engine = null;
                }
                new File(dir(ctx), "engine.bin").delete();
                init(ctx);
                final boolean listo = ready();
                uiPost(() -> cb.on(listo, listo
                    ? "listas actualizadas (" + okF + " de 2)"
                    : "el motor no pudo recargar"));
            } catch (Exception e) {
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
            conn.setRequestProperty("User-Agent", "TurboDL/3.4");
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
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
