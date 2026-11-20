package com.turbodl.engine

import com.turbodl.model.Task
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

open class DlException(msg: String) : Exception(msg)

/**
 * Base de los descargadores. Aporta utilidades HTTP con las cabeceras
 * personalizadas de la tarea (Cookie, User-Agent, Referer...).
 */
abstract class Downloader(val task: Task, val engine: Engine) {
    @Volatile
    protected var stopped = false

    open fun stop() { stopped = true }
    fun isStopped(): Boolean = stopped

    abstract fun run()

    val defaultUA: String
        get() = engine.userAgent()

    protected fun open(url: String, range: String? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", defaultUA)
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Connection", "keep-alive")
        for ((k, v) in task.headers()) {
            // No permitir que el usuario rompa el rango
            if (k.equals("Range", true)) continue
            conn.setRequestProperty(k, v)
        }
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }

    /** GET completo a memoria (listas m3u8/mpd/claves pequenas) */
    protected fun httpGetBytes(url: String): ByteArray {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw DlException("HTTP $code en $url")
            val raw = conn.inputStream
            val inp: InputStream =
                if ("gzip".equals(conn.contentEncoding, true)) GZIPInputStream(raw) else raw
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            inp.close()
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    protected fun httpGetText(url: String): String = String(httpGetBytes(url), Charsets.UTF_8)

    /** Rango parcial a memoria (para sondeos MP4): bytes=start-end */
    protected fun httpGetRange(url: String, start: Long, end: Long, max: Int = 4 * 1024 * 1024): ByteArray {
        val conn = open(url, "bytes=$start-$end")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw DlException("HTTP $code")
            val raw = conn.inputStream
            val inp: InputStream =
                if ("gzip".equals(conn.contentEncoding, true)) GZIPInputStream(raw) else raw
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var got = 0
            while (got < max) {
                val n = inp.read(buf, 0, buf.size.coerceAtMost(max - got))
                if (n < 0) break
                out.write(buf, 0, n)
                got += n
            }
            inp.close()
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }
}
