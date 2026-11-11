package com.turbodl.engine

import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.turbodl.util.Kit
import com.turbodl.util.M3u
import com.turbodl.util.Mpd
import com.turbodl.util.Mp4
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Sondeo de medios detectados en el navegador: obtiene en segundo plano el
 * tamano real (o estimado), la duracion, la resolucion y la lista de
 * calidades (variantes HLS / representaciones DASH), ademas de detectar
 * pistas de audio separadas para poder combinarlas con el video.
 * Cuatro sondas concurrentes; las variantes HLS se resuelven en paralelo
 * en un pool auxiliar para que un stream no bloquee a los demas.
 */
object MediaProbe {

    class Variant(
        val label: String,       // "720p", "320 kbps", "Audio ES"
        val url: String,         // url de la variante/pista
        val size: Long,          // bytes (-1 desconocido)
        val dur: Double,         // segundos (-1)
        val isAudio: Boolean,
        val extraJson: String    // {"variantUrl":..,"audioUrl":..,"merge":..} o {"repVideo":..}
    )

    class Info {
        var size = -1L
        var sizeApprox = false
        var durationSec = -1.0
        var width = 0
        var height = 0
        var mimeType = ""
        var variants: List<Variant> = emptyList()
        var audioUrl: String? = null
        var audioLabel: String? = null
        var note = ""

        fun qualityLabel(): String = when {
            height > 0 -> "${height}p"
            else -> ""
        }

        /** Linea descriptiva para la lista: calidad · duracion · tamano */
        fun line(): String {
            val parts = ArrayList<String>()
            val q = qualityLabel()
            if (q.isNotEmpty()) parts.add(q)
            val d = Kit.fmtDur(durationSec)
            if (d.isNotEmpty()) parts.add(d)
            if (size > 0) parts.add(Kit.fmtBytes(size) + if (sizeApprox) " ≈" else "")
            else if (sizeApprox) parts.add("tamaño ≈")
            if (parts.isEmpty()) parts.add(note.ifEmpty { "consultando…" })
            return parts.joinToString(" · ")
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newFixedThreadPool(4)

    /** Pool auxiliar: variantes HLS en paralelo dentro de una sola sonda. */
    private val subPool: ExecutorService = Executors.newFixedThreadPool(6)
    private val cache = object : LinkedHashMap<String, Info>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Info>): Boolean =
            size > 300
    }

    /** Solicita la informacion; cb se invoca en el hilo principal. */
    fun probe(url: String, headers: Map<String, String>, cb: (Info) -> Unit) {
        val hit = synchronized(cache) { cache[url] }
        if (hit != null) { cb(hit); return }
        pool.execute {
            val info = try { doProbe(url, headers) } catch (e: Exception) {
                Info().apply { note = "sin datos" }
            }
            synchronized(cache) { cache[url] = info }
            ui.post { cb(info) }
        }
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    // ---------------- sondeos por tipo ----------------

    private fun doProbe(url: String, headers: Map<String, String>): Info {
        val low = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        // YouTube (googlevideo): duracion y tamano vienen en la propia URL
        if (low.contains("googlevideo.com") && low.contains("videoplayback")) {
            val g = probeGoogleParams(url)
            if (g != null) return g
            return probeDirect(url, headers)
        }
        // manifiesto DASH de YouTube (/api/manifest/dash/...) o .mpd
        return when {
            low.endsWith(".m3u8") -> probeHls(url, headers)
            low.endsWith(".mpd") -> probeMpd(url, headers)
            low.contains("/api/manifest/dash/") -> probeMpd(url, headers)
            else -> probeDirect(url, headers)
        }
    }

    /**
     * Vía rápida para googlevideo (YouTube): las URLs de videoplayback ya
     * traen dur= (segundos), clen= (bytes) y mime= (video/mp4, video/webm,
     * audio/mp4...). Cero peticiones de red: duracion y tamano exactos.
     */
    private fun probeGoogleParams(url: String): Info? {
        try {
            val q = url.substringAfter('?', "").substringBefore('#')
            if (q.isEmpty()) return null
            var dur = -1.0
            var clen = -1L
            var mime = ""
            var itag = ""
            for (p in q.split('&')) {
                val i = p.indexOf('=')
                if (i <= 0) continue
                val k = p.substring(0, i).lowercase(Locale.US)
                val v = p.substring(i + 1)
                when (k) {
                    "dur" -> dur = v.toDoubleOrNull() ?: -1.0
                    "clen" -> clen = v.toLongOrNull() ?: -1L
                    "mime" -> mime = try { java.net.URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
                    "itag" -> itag = v
                }
            }
            if (dur <= 0 && clen <= 0) return null
            val info = Info()
            info.durationSec = dur
            info.size = clen
            info.mimeType = mime
            if (mime.contains("audio")) info.note = "solo audio (YouTube)"
            else if (mime.contains("video")) info.note = "sin audio (YouTube)"
            info.height = itagHeight(itag)
            return info
        } catch (e: Exception) { return null }
    }

    /** Alturas aproximadas de los itag mas comunes de YouTube. */
    private fun itagHeight(itag: String): Int = when (itag) {
        "160", "278" -> 144
        "133", "242", "395" -> 240
        "134", "243", "396", "18" -> 360
        "135", "244", "397", "22" -> 480
        "136", "247", "398" -> 720
        "137", "248", "399", "308" -> 1080
        "271", "400" -> 1440
        "313", "401" -> 2160
        else -> -1
    }

    private fun probeHls(url: String, headers: Map<String, String>): Info {
        val info = Info()
        var text = httpGetText(url, headers)
        var p = M3u.parse(url, text)
        if (p.master) {
            if (p.audioUri != null) {
                info.audioUrl = p.audioUri
                info.audioLabel = if (p.audioName.isNotEmpty()) "audio ${p.audioName}" else "audio separado"
            }
            val vars = ArrayList<Variant>()
            // resolver todas las variantes EN PARALELO (subPool) con tope de tiempo
            val list = p.variants.sortedByDescending { it.bandwidth }.take(8)
            val results = arrayOfNulls<Info>(list.size)
            val latch = java.util.concurrent.CountDownLatch(if (list.isEmpty()) 1 else list.size)
            for ((idx, v) in list.withIndex()) {
                subPool.execute {
                    try {
                        val vt = httpGetText(v.url, headers)
                        val vp = M3u.parse(v.url, vt)
                        val vi = Info()
                        vi.durationSec = vp.durationSec
                        vi.size = if (vp.byterangeTotal > 0) vp.byterangeTotal
                            else if (v.bandwidth > 0 && vp.durationSec > 0)
                                (v.bandwidth * vp.durationSec / 8).toLong().also { vi.sizeApprox = true }
                            else -1
                        results[idx] = vi
                        synchronized(info) { info.durationSec = maxOf(info.durationSec, vp.durationSec) }
                    } catch (e: Exception) { }
                    latch.countDown()
                }
            }
            latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
            for ((idx, v) in list.withIndex()) {
                val vi = results[idx] ?: Info()
                val ex = JSONObject()
                ex.put("variantUrl", v.url)
                if (info.audioUrl != null) {
                    ex.put("audioUrl", info.audioUrl)
                    ex.put("merge", true)
                }
                vars.add(Variant(v.label(), v.url, vi.size, vi.durationSec, false, ex.toString()))
            }
            info.variants = vars
            if (vars.isNotEmpty()) {
                info.size = vars.maxOfOrNull { it.size } ?: -1
                info.sizeApprox = vars.any { it.size < 0 } || info.size < 0
                info.height = p.height
            }
            if (info.size < 0) info.sizeApprox = true
        } else {
            info.durationSec = p.durationSec
            info.size = p.byterangeTotal
            if (info.size < 0 && p.entries.isNotEmpty() && p.durationSec > 0) {
                // estimar con el primer segmento (1 peticion de rango)
                try {
                    val first = p.entries[0].uri
                    val sz = fetchSize(first, headers)
                    if (sz > 0) {
                        val dur = if (p.entries[0].dur > 0) p.entries[0].dur else 6.0
                        info.size = (sz / dur * p.durationSec).toLong()
                        info.sizeApprox = true
                    }
                } catch (e: Exception) { info.sizeApprox = true }
            } else if (info.size < 0) info.sizeApprox = true
        }
        return info
    }

    private fun probeMpd(url: String, headers: Map<String, String>): Info {
        val info = Info()
        val xml = httpGetText(url, headers)
        val m = Mpd.parse(xml)
        if (m.drm) { info.note = "protegido con DRM"; return info }
        info.durationSec = m.durationSec
        val vars = ArrayList<Variant>()
        val dur = m.durationSec
        for (r in m.video.sortedByDescending { it.bandwidth }.take(10)) {
            val ex = JSONObject()
            ex.put("repVideo", r.id)
            if (m.audio.isNotEmpty()) {
                ex.put("repAudio", m.audio.maxByOrNull { it.bandwidth }!!.id)
                ex.put("merge", true)
                info.audioLabel = "audio separado (DASH)"
            }
            val sz = if (r.bandwidth > 0 && dur > 0) (r.bandwidth * dur / 8).toLong() else -1
            vars.add(Variant(r.label(), url, sz, dur, false, ex.toString()))
            info.height = maxOf(info.height, r.height)
        }
        val audioOnly = m.video.isEmpty() && m.audio.isNotEmpty()
        for (r in m.audio.sortedByDescending { it.bandwidth }.take(4)) {
            val ex = JSONObject()
            ex.put("repVideo", r.id)
            val sz = if (r.bandwidth > 0 && dur > 0) (r.bandwidth * dur / 8).toLong() else -1
            vars.add(Variant("audio ${r.label()}", url, sz, dur, true, ex.toString()))
        }
        info.variants = vars
        info.size = vars.maxOfOrNull { it.size } ?: -1
        info.sizeApprox = true // DASH: tamaños estimados por bitrate
        if (audioOnly) info.note = "solo audio (DASH)"
        return info
    }

    private fun probeDirect(url: String, headers: Map<String, String>): Info {
        val info = Info()
        try {
            // UNA sola peticion de rango: da tamano (Content-Range), mime y
            // los primeros 256 KB para leer el moov de un MP4
            val h = fetchHead(url, headers, 256)
            info.mimeType = h.contentType
            info.size = h.total
            val low = url.substringBefore('?').lowercase(Locale.US)
            val ct = h.contentType.lowercase(Locale.US)
            val isMp4ish = low.endsWith(".mp4") || low.endsWith(".m4a") || low.endsWith(".mov") ||
                    ct.contains("mp4") || ct.contains("quicktime")
            if (isMp4ish && h.bytes.isNotEmpty()) {
                val i = Mp4.parse(h.bytes)
                info.durationSec = i.durationSec.toDouble()
                info.width = i.width; info.height = i.height
                if (!i.hasMoov && info.durationSec <= 0 && info.size > 0) {
                    // moov al final: segunda (y ultima) peticion de rango
                    val tail = httpGetRange(url, (info.size - 524288).coerceAtLeast(0), info.size - 1, headers)
                    val t = Mp4.parse(tail)
                    info.durationSec = t.durationSec.toDouble()
                    if (t.width > 0) { info.width = t.width; info.height = t.height }
                }
            }
            if (info.size <= 0 && info.durationSec <= 0) {
                // sin rango ni longitud: ultimo intento, solo tamano
                val sz = fetchSize(url, headers)
                if (sz > 0) info.size = sz
            }
        } catch (e: Exception) {
            try {
                val sz = fetchSize(url, headers)
                if (sz > 0) info.size = sz else info.note = "sin datos"
            } catch (e2: Exception) { info.note = "sin datos" }
        }
        return info
    }

    class Head(val bytes: ByteArray, val total: Long, val contentType: String)

    /**
     * Rango inicial en una sola conexion: devuelve los primeros maxKB bytes,
     * el tamano TOTAL del recurso (Content-Range o Content-Length) y el mime.
     */
    private fun fetchHead(url: String, headers: Map<String, String>, maxKB: Int): Head {
        val cap = maxKB * 1024
        val conn = open(url, "bytes=0-${cap - 1}", headers)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            val raw = conn.inputStream
            val inp: InputStream = if ("gzip".equals(conn.contentEncoding, true))
                java.util.zip.GZIPInputStream(raw) else raw
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var got = 0
            while (got < cap) {
                val n = inp.read(buf, 0, buf.size.coerceAtMost(cap - got))
                if (n < 0) break
                out.write(buf, 0, n)
                got += n
            }
            inp.close()
            var total = -1L
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val cr = conn.getHeaderField("Content-Range") ?: ""
                val m = Regex("(\\d+)\\s*$").find(cr.substringAfterLast('/'))
                total = m?.groupValues?.get(1)?.toLongOrNull() ?: -1
            } else {
                total = conn.contentLengthLong
            }
            return Head(out.toByteArray(), total, conn.contentType ?: "")
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- utilidades HTTP ----------------

    private fun open(url: String, range: String?, headers: Map<String, String>): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("Accept", "*/*")
        headers.forEach { (k, v) ->
            if (!k.equals("Range", true)) conn.setRequestProperty(k, v)
        }
        if (range != null) conn.setRequestProperty("Range", range)
        return conn
    }

    private fun httpGetText(url: String, headers: Map<String, String>): String {
        val conn = open(url, null, headers)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            val raw = conn.inputStream
            val inp: InputStream = if ("gzip".equals(conn.contentEncoding, true))
                java.util.zip.GZIPInputStream(raw) else raw
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            inp.close()
            return out.toString("UTF-8")
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetRange(url: String, start: Long, end: Long, headers: Map<String, String>): ByteArray {
        val conn = open(url, "bytes=$start-$end", headers)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            val raw = conn.inputStream
            val inp: InputStream = if ("gzip".equals(conn.contentEncoding, true))
                java.util.zip.GZIPInputStream(raw) else raw
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var got = 0
            while (got < 4 * 1024 * 1024) {
                val n = inp.read(buf, 0, buf.size.coerceAtMost(4 * 1024 * 1024 - got))
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

    /** Tamano via rango de 1 byte (funciona incluso sin HEAD). */
    private fun fetchSize(url: String, headers: Map<String, String>): Long {
        val conn = open(url, "bytes=0-0", headers)
        try {
            val code = conn.responseCode
            if (code !in 200..299) return -1
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val cr = conn.getHeaderField("Content-Range") ?: return -1
                val m = Regex("(\\d+)\\s*$").find(cr.substringAfterLast('/'))
                return m?.groupValues?.get(1)?.toLongOrNull() ?: -1
            }
            val len = conn.contentLengthLong
            return if (len >= 0) len else -1
        } catch (e: Exception) {
            return -1
        } finally {
            conn.disconnect()
        }
    }

    /** Host legible para etiquetas. */
    fun hostOf(url: String): String = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
}
