package com.turbodl.util

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 4.8: caché HTTP del navegador con validación ETag / Last-Modified.
 *
 * Pediste: *"navegador con caché con etag u otra alternativa para
 * disminuir el consumo de datos y que al recargar la página no se
 * descargue todo de nuevo"*. El caché nativo del WebView ya respeta
 * ETag con `LOAD_DEFAULT`, pero es opaco, pequeño y el usuario no
 * tiene control. Este interceptor lo reemplaza para GET http(s):
 *
 *  - Cada entrada guarda cuerpo + ETag + Last-Modified + max-age +
 *    expires + headers de respuesta. Clave = SHA-1 de la URL.
 *  - Al recargar, si la entrada es fresca por `max-age`, se sirve
 *    sin ir a la red (ahorro total). Si está vencida, se revalida
 *    con `If-None-Match` / `If-Modified-Since`: si el servidor
 *    responde **304 Not Modified**, se entrega el caché sin bajar
 *    el cuerpo (solo ~300 bytes de headers vs. MB de página).
 *  - `Cache-Control: no-store` / `no-cache` y `Pragma: no-cache`
 *    se respetan: no se cachea.
 *  - LRU por fecha de acceso, tamaño máximo 80 MB por usuario.
 *  - Sin red (excepción): sirve el caché stale como paliativo.
 *  - Cookies: se reenvían las del `CookieManager` del WebView.
 *  - Range (vídeo) y métodos no GET se ignoran (los hace el WebView).
 *  - Cifrado de transferencia: forzamos `Accept-Encoding: identity`
 *    para guardar el cuerpo final (sin gzip); el ahorro real viene
 *    del 304, no de la compresión.
 *
 * Se invoca desde `shouldInterceptRequest` (hilo de red de Chromium);
 * es thread-safe: cada llamada usa archivos propios y `HttpURLConnection`
 * es independiente por hilo. El contador de ahorro va a SharedPreferences
 * "cfg" clave `httpCacheSaved` (bytes no transferidos gracias al caché).
 */
object HttpCache {

    private const val MAX_BYTES = 80L * 1024 * 1024

    /** Directorio del caché del usuario activo (aislado por perfil). */
    fun dir(ctx: Context): File {
        val u = Users.active(ctx)
        val base = File(ctx.dataDir, "httpcache")
        val d = if (u == "main") base else File(base, "usr_$u")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Borra todo el caché del usuario activo. Devuelve bytes liberados. */
    fun clear(ctx: Context): Long {
        val d = dir(ctx)
        var n = 0L
        d.listFiles()?.forEach { n += it.length(); it.delete() }
        return n
    }

    /** Tamaño actual en bytes del caché del usuario activo. */
    fun size(ctx: Context): Long =
        dir(ctx).listFiles()?.sumOf { it.length() } ?: 0L

    /** LRU: borra las entradas más antiguas hasta bajar de MAX_BYTES. */
    private fun trim(d: File) {
        val metas = d.listFiles { f -> f.name.endsWith(".meta") } ?: return
        data class P(val meta: File, val body: File, val size: Long, val at: Long)
        val items = metas.mapNotNull { m ->
            val b = File(d, m.name.removeSuffix(".meta") + ".body")
            if (!b.exists()) { m.delete(); null }
            else P(m, b, m.length() + b.length(), m.lastModified())
        }.sortedBy { it.at }
        var total = items.sumOf { it.size }
        for (it in items) {
            if (total <= MAX_BYTES) break
            it.meta.delete(); it.body.delete(); total -= it.size
        }
    }

    private fun readMeta(f: File): org.json.JSONObject? =
        if (!f.exists()) null
        else try { org.json.JSONObject(f.readText()) } catch (e: Throwable) { null }

    private fun writeMeta(f: File, o: org.json.JSONObject) {
        try { f.writeText(o.toString()) } catch (e: Throwable) { }
    }

    private fun touch(f: File) {
        try { f.setLastModified(System.currentTimeMillis()) } catch (e: Throwable) { }
    }

    /** Suma `bytes` al contador de ahorro del usuario activo. */
    private fun addSaved(ctx: Context, bytes: Int) {
        try {
            val sp = ctx.applicationContext
                .getSharedPreferences("cfg", Context.MODE_PRIVATE)
            sp.edit().putLong("httpCacheSaved",
                sp.getLong("httpCacheSaved", 0) + bytes.toLong()).apply()
        } catch (e: Throwable) { }
    }

    /** Bytes ahorrados desde que se instaló (todos los usuarios). */
    fun savedTotal(ctx: Context): Long =
        ctx.applicationContext
            .getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .getLong("httpCacheSaved", 0)

    /**
     * Intercepta una petición del WebView. Devuelve una respuesta
     * cacheada (si aplica) o `null` para que el WebView la haga normal.
     */
    fun intercept(ctx: Context, request: WebResourceRequest): WebResourceResponse? {
        val u = request.url?.toString() ?: return null
        if (!u.startsWith("http")) return null
        val method = request.method?.uppercase(Locale.US) ?: "GET"
        if (method != "GET") return null
        // Range (vídeo/streaming): no cacheable, lo hace el WebView.
        val rh = request.requestHeaders
        if (rh?.any { it.key.equals("Range", true) } == true) return null

        val d = dir(ctx)
        val k = key(u)
        val metaF = File(d, "$k.meta")
        val bodyF = File(d, "$k.body")
        val meta = readMeta(metaF)
        val now = System.currentTimeMillis()

        // ¿Fresco por max-age? Servir sin ir a la red.
        if (meta != null) {
            val noStore = meta.optBoolean("noStore", false)
            val maxAge = meta.optLong("maxAge", -1)
            val captured = meta.optLong("captured", 0)
            if (!noStore && maxAge >= 0 && now < captured + maxAge * 1000 && bodyF.exists()) {
                touch(metaF)
                val r = cachedResponse(meta, bodyF, "OK (cache fresca)")
                addSaved(ctx, bodyF.length().toInt())
                return r
            }
        }

        // Revalidar con If-None-Match / If-Modified-Since.
        val conn = try {
            (URL(u).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                useCaches = false
                if (meta != null) {
                    meta.optString("etag").takeIf { it.isNotEmpty() }
                        ?.let { setRequestProperty("If-None-Match", it) }
                    meta.optString("lastModified").takeIf { it.isNotEmpty() }
                        ?.let { setRequestProperty("If-Modified-Since", it) }
                }
                // Cookies del WebView (login, sesiones).
                val cookies = android.webkit.CookieManager.getInstance().getCookie(u)
                if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
                // Reenviar headers del navegador, salvo los que gestionamos
                // nosotros (auth de caché, cookie, host, connection, range,
                // accept-encoding que forzamos a identity).
                rh?.forEach { (key, value) ->
                    val kk = key.lowercase(Locale.US)
                    if (kk !in setOf("if-none-match", "if-modified-since",
                            "cookie", "host", "connection",
                            "accept-encoding", "range")) {
                        try { setRequestProperty(key, value) } catch (e: Throwable) { }
                    }
                }
                setRequestProperty("Accept-Encoding", "identity")
            }
        } catch (e: Throwable) { return null }

        try {
            conn.connect()
            val status = conn.responseCode
            // 304: el caché sigue siendo válido; no se bajó el cuerpo.
            if (status == 304 && meta != null && bodyF.exists()) {
                touch(metaF)
                val r = cachedResponse(meta, bodyF, "OK (cache, sin cambios)")
                addSaved(ctx, bodyF.length().toInt())
                return r
            }
            if (status in 200..299) {
                val bytes = conn.inputStream.readBytes()
                val cc = conn.getHeaderField("Cache-Control") ?: ""
                val noStore = cc.contains("no-store") || cc.contains("no-cache") ||
                    (conn.getHeaderField("Pragma") ?: "").contains("no-cache")
                val etag = conn.getHeaderField("ETag")
                val lm = conn.getHeaderField("Last-Modified")
                val maxAge = Regex("max-age=(\\d+)")
                    .find(cc)?.groupValues?.get(1)?.toLong() ?: -1L
                val expiresMs = parseHttpDate(conn.getHeaderField("Expires"))
                val mime = (conn.contentType ?: "application/octet-stream")
                    .substringBefore(';').trim()
                val charset = Regex("charset=([^;\\s]+)")
                    .find(conn.contentType ?: "")?.groupValues?.get(1)

                val headers = LinkedHashMap<String, String>()
                var i = 0
                while (true) {
                    val hk = conn.getHeaderFieldKey(i) ?: break
                    val hv = conn.getHeaderField(i)
                    if (hk.isNotBlank() && hv != null) headers[hk] = hv
                    i++
                }
                if (!noStore && bytes.size <= MAX_BYTES) {
                    try {
                        bodyF.writeBytes(bytes)
                        val m = org.json.JSONObject()
                        m.put("url", u); m.put("status", status)
                        m.put("mime", mime); m.put("charset", charset ?: "")
                        m.put("etag", etag ?: ""); m.put("lastModified", lm ?: "")
                        m.put("maxAge", maxAge); m.put("expires", expiresMs)
                        m.put("captured", now); m.put("noStore", false)
                        val hj = org.json.JSONObject()
                        headers.forEach { hj.put(it.key, it.value) }
                        m.put("headers", hj)
                        writeMeta(metaF, m)
                        trim(d)
                    } catch (e: Throwable) { }
                }
                // Constructor de 6 args (API 21+): mimeType, encoding,
                // statusCode, reasonPhrase, responseHeaders, inputStream.
                return WebResourceResponse(
                    mime, charset, status, "OK", headers, ByteArrayInputStream(bytes))
            }
            return null
        } catch (e: Throwable) {
            // Sin red: servir el caché stale como paliativo (modo offline).
            if (meta != null && bodyF.exists())
                return cachedResponse(meta, bodyF, "OK (cache offline)")
            return null
        } finally {
            try { conn.disconnect() } catch (e: Throwable) { }
        }
    }

    private fun cachedResponse(meta: org.json.JSONObject, bodyF: File,
                                reason: String): WebResourceResponse {
        val bytes = bodyF.readBytes()
        val mime = meta.optString("mime", "application/octet-stream")
        val charset = meta.optString("charset").ifEmpty { null }
        val headers = LinkedHashMap<String, String>()
        val h = meta.optJSONObject("headers")
        if (h != null) h.keys().forEach { headers[it] = h.getString(it) }
        // Constructor de 5 args (API 21+): fija statusCode y reasonPhrase
        // en la construcción; mimeType y encoding van como setters existentes.
        return WebResourceResponse(
            mime, charset, 200, reason, headers, ByteArrayInputStream(bytes))
    }

    private fun parseHttpDate(s: String?): Long {
        if (s.isNullOrEmpty()) return 0L
        val fmts = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy"
        )
        for (p in fmts) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                f.timeZone = TimeZone.getTimeZone("GMT")
                return f.parse(s).time
            } catch (e: Throwable) { }
        }
        return 0L
    }
}
