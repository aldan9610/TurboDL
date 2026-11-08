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
 * Un solo hilo de sondeo para no afectar dispositivos de bajos recursos.
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
    private val pool: ExecutorService = Executors.newFixedThreadPool(2)
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
        return when {
            low.endsWith(".m3u8") -> probeHls(url, headers)
            low.endsWith(".mpd") -> probeMpd(url, headers)
            else -> probeDirect(url, headers)
        }
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
            for (v in p.variants.sortedByDescending { it.bandwidth }.take(8)) {
                val vi = Info()
                try {
                    val vt = httpGetText(v.url, headers)
                    val vp = M3u.parse(v.url, vt)
                    vi.durationSec = vp.durationSec
                    vi.size = if (vp.byterangeTotal > 0) vp.byterangeTotal
                        else if (v.bandwidth > 0 && vp.durationSec > 0)
                            (v.bandwidth * vp.durationSec / 8).toLong().also { vi.sizeApprox = true }
                        else -1
                    info.durationSec = maxOf(info.durationSec, vp.durationSec)
                } catch (e: Exception) { }
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
        info.size = fetchSize(url, headers)
        val ct = try { headType(url, headers) } catch (e: Exception) { "" }
        info.mimeType = ct
        val low = url.substringBefore('?').lowercase(Locale.US)
        val isMp4ish = low.endsWith(".mp4") || low.endsWith(".m4a") || low.endsWith(".mov") ||
                ct.contains("mp4") || ct.contains("quicktime")
        if (isMp4ish && info.size > 0) {
            // cabecera (moov suele ir delante en streams) y, si no, cola
            try {
                val head = httpGetRange(url, 0, 262143, headers)
                val i = Mp4.parse(head)
                info.durationSec = i.durationSec.toDouble()
                info.width = i.width; info.height = i.height
                if (!i.hasMoov && info.durationSec <= 0) {
                    val tail = httpGetRange(url, (info.size - 524288).coerceAtLeast(0), info.size - 1, headers)
                    val t = Mp4.parse(tail)
                    info.durationSec = t.durationSec.toDouble()
                    if (t.width > 0) { info.width = t.width; info.height = t.height }
                }
            } catch (e: Exception) { }
        }
        return info
    }

    // ---------------- utilidades HTTP ----------------

    private fun open(url: String, range: String?, headers: Map<String, String>): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
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

    private fun headType(url: String, headers: Map<String, String>): String {
        val conn = open(url, "bytes=0-0", headers)
        try { return conn.contentType ?: "" } finally { conn.disconnect() }
    }

    /** Host legible para etiquetas. */
    fun hostOf(url: String): String = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
}
