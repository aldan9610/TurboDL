package com.turbodl.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.HashSet
import java.util.LinkedHashMap

/**
 * Iconos reales para accesos directos y pestañas (requisito 4.0: nada de
 * iconos genéricos repetidos). Estrategia en tres niveles:
 *  1. caché de memoria (host -> bitmap),
 *  2. caché de disco (filesDir/icons/<host>.png),
 *  3. red: favicon real del sitio vía el servicio público de favicons de
 *     Google (entrega el icono que el propio sitio declara, en PNG).
 * Si nada de eso funciona: monograma (inicial del host sobre un color
 * derivado del hash), que nunca es idéntico entre dos sitios.
 */
object Favicons {

    private val mem = LinkedHashMap<String, Bitmap>()
    private val inflight = HashSet<String>()

    fun colorOf(host: String): Int = Color.HSVToColor(
        floatArrayOf((Math.abs(host.hashCode()) % 360).toFloat(), 0.55f, 0.62f))

    private fun file(ctx: Context, host: String): File {
        val dir = File(ctx.filesDir, "icons")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, host.replace(Regex("[^a-z0-9.\\-]"), "_") + ".png")
    }

    /** Devuelve el icono si ya está en memoria o disco (sin red). */
    fun cached(ctx: Context, host: String): Bitmap? {
        if (host.isEmpty()) return null
        synchronized(mem) { mem[host]?.let { return it } }
        try {
            val f = file(ctx, host)
            if (f.exists() && f.length() > 0) {
                val b = BitmapFactory.decodeFile(f.absolutePath)
                if (b != null) {
                    synchronized(mem) {
                        mem[host] = b
                        if (mem.size > 80) mem.remove(mem.keys.first())
                    }
                    return b
                }
            }
        } catch (e: Exception) { }
        return null
    }

    /**
     * Carga asíncrona del icono real; entrega en hilo de fondo. El receptor
     * decide publicarlo en UI. Si falla, cb(null) y el receptor pinta el
     * monograma.
     */
    fun fetchAsync(ctx: Context, host: String, cb: (Bitmap?) -> Unit) {
        if (host.isEmpty()) { cb(null); return }
        val hit = cached(ctx, host)
        if (hit != null) { cb(hit); return }
        val launch = synchronized(inflight) { inflight.add(host) }
        if (!launch) { cb(null); return }
        Thread {
            var b: Bitmap? = null
            try {
                val u = URL("https://www.google.com/s2/favicons?sz=128&domain=$host")
                val c = (u.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (c.responseCode == 200) {
                    val bytes = c.inputStream.use { it.readBytes() }
                    b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (b != null) {
                        try { file(ctx, host).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } } catch (e: Exception) { }
                        synchronized(mem) {
                            mem[host] = b
                            if (mem.size > 80) mem.remove(mem.keys.first())
                        }
                    }
                }
            } catch (e: Exception) { }
            synchronized(inflight) { inflight.remove(host) }
            cb(b)
        }.apply { isDaemon = true; name = "favicon-$host" }.start()
    }

    /** Monograma: inicial del host sobre círculo de color derivado del host. */
    fun monogram(host: String, sizePx: Int): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = colorOf(host)
        c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, p)
        p.color = Color.WHITE
        p.textAlign = Paint.Align.CENTER
        p.textSize = sizePx * 0.52f
        val ch = host.ifEmpty { "T" }.take(1).uppercase()
        val y = sizePx / 2f - (p.descent() + p.ascent()) / 2f
        c.drawText(ch, sizePx / 2f, y, p)
        return b
    }
}
