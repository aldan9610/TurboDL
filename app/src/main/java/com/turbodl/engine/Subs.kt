package com.turbodl.engine

import com.turbodl.model.Task
import com.turbodl.util.Kit
import com.turbodl.util.M3u
import com.turbodl.util.Mpd
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Descarga de SUBTITULOS cuando el stream los declara:
 *  - HLS: playlist del grupo EXT-X-MEDIA TYPE=SUBTITLES (segmentos WebVTT)
 *  - DASH: representacion de texto (mimeType text/vtt; los wvtt empaquetados
 *    en MP4 no son concatenable y se avisa)
 * Une los segmentos en un unico .vtt junto al video.
 */
object Subs {

    /**
     * Intenta bajar los subtitulos y guardarlos como "<base>.vtt" junto al
     * video (carpeta local o arbol SAF de la tarea). Devuelve una nota para
     * el extra "note" (cadena vacia si no hay nada que contar).
     */
    fun tryDownload(engine: Engine, task: Task, hlsSubsUrl: String, dashRep: Mpd.Rep?, mpdUrl: String): String {
        return try {
            val text: String? = when {
                hlsSubsUrl.isNotEmpty() -> fromHls(task, hlsSubsUrl)
                dashRep != null -> fromDash(task, dashRep, mpdUrl)
                else -> return ""
            }
            if (text == null) return "subtítulos: formato no concatenable (wvtt en MP4)"
            if (text.length < 32) return "subtítulos: playlist vacía"

            val base = task.fileName.substringBeforeLast('.')
            val outName = "$base.vtt"
            val data = text.toByteArray(Charsets.UTF_8)
            val docStr = task.extra().optString("docUri", "")
            if (docStr.isNotEmpty()) {
                val store = SafStore(engine.ctx, android.net.Uri.parse(task.extra().optString("tree", "")))
                var doc = store.rootDocUri()
                doc = store.ensureDir(doc, "TurboDL")
                doc = store.ensureDir(doc, engine.catNameOf(task.dir))
                val fUri = store.ensureFile(doc, outName, "text/vtt")
                store.openSink(fUri).use { it.append(data, data.size) }
                // gemelo .srt: VLC y casi todos los reproductores externos
                // cargan automáticamente el subtítulo que se llama igual
                val srt = vttToSrt(text)
                if (srt.isNotBlank()) {
                    val sData = srt.toByteArray(Charsets.UTF_8)
                    val sUri = store.ensureFile(doc, "$base.srt", "application/x-subrip")
                    store.openSink(sUri).use { it.append(sData, sData.size) }
                }
            } else {
                val f = File(File(task.dir), outName)
                if (f.exists()) f.delete()
                f.writeBytes(data)
                // gemelo .srt junto al vídeo (mismo nombre base)
                val srt = vttToSrt(text)
                if (srt.isNotBlank()) {
                    val fs = File(File(task.dir), "$base.srt")
                    if (fs.exists()) fs.delete()
                    fs.writeText(srt, Charsets.UTF_8)
                }
            }
            "subtítulos guardados: $outName (+.srt)"
        } catch (e: Exception) {
            "subtítulos no disponibles: ${e.message}"
        }
    }

    /**
     * Convierte WebVTT a SubRip: los reproductores externos cargan .srt de
     * forma casi universal y el .vtt no siempre. Normaliza las marcas
     * HH:MM:SS.mmm -> HH:MM:SS,mmm (o MM:SS.mmm -> 00:MM:SS,mmm) y numera
     * los cues. Función pura, cubierta por pruebas JVM.
     */
    fun vttToSrt(vtt: String): String {
        val tsRe = Regex("((?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3})\\s*-->\\s*((?:\\d{2}:)?\\d{2}:\\d{2}\\.\\d{3}).*")
        fun normTs(t: String): String {
            var s = t.trim()
            if (s.split(":").size == 2) s = "00:$s"
            return s.replace('.', ',')
        }
        val out = StringBuilder()
        val lines = vtt.lines()
        var i = 0
        var n = 0
        while (i < lines.size) {
            val m = tsRe.find(lines[i])
            if (m == null) { i++; continue }
            n++
            out.append(n).append('\n')
            out.append(normTs(m.groupValues[1])).append(" --> ").append(normTs(m.groupValues[2])).append('\n')
            i++
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                out.append(lines[i]).append('\n')
                i++
            }
            out.append('\n')
        }
        val res = out.toString().trimEnd('\n')
        return if (res.isEmpty()) "" else "$res\n"
    }

    /** GET de texto con las cabeceras de la tarea (UA, Referer, Cookie...). */
    private fun getText(task: Task, url: String): String {
        // 4.5: proxy opcional (HTTP/HTTPS/SOCKS). Usamos App.app (singleton
        // global) porque getText no recibe engine en su firma.
        val proxy = com.turbodl.util.Proxy.javaProxy(com.turbodl.App.app)
        val conn = (if (proxy != null) URL(url).openConnection(proxy)
                    else URL(url).openConnection()) as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", EngineHolder.ua)
        conn.setRequestProperty("Accept", "*/*")
        for ((k, v) in task.headers()) conn.setRequestProperty(k, v)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw DlException("HTTP $code en $url")
            val raw: InputStream = conn.inputStream
            val inp = if ("gzip".equals(conn.contentEncoding, true)) GZIPInputStream(raw) else raw
            val out = StringBuilder()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.append(String(buf, 0, n, Charsets.UTF_8))
            }
            inp.close()
            return out.toString()
        } finally {
            conn.disconnect()
        }
    }

    /** Descarga y concatena una playlist HLS de subtitulos WebVTT. */
    private fun fromHls(task: Task, url: String): String? {
        val p = M3u.parse(url, getText(task, url))
        val mediaUrl = if (p.master && p.variants.isNotEmpty()) p.variants[0].url else url
        val list = if (p.master) M3u.parse(mediaUrl, getText(task, mediaUrl)) else p
        if (list.entries.isEmpty()) return ""
        val sb = StringBuilder()
        var headerWritten = false
        for (e in list.entries) {
            val seg = getText(task, e.uri)
            if (!headerWritten && seg.trimStart().startsWith("WEBVTT")) {
                sb.append(seg).append("\n\n")
                headerWritten = true
            } else {
                val part = cleanVtt(seg)
                if (part.isNotEmpty()) sb.append(part).append("\n\n")
            }
        }
        val out = sb.toString()
        return if (out.startsWith("WEBVTT") || out.isEmpty()) out else "WEBVTT\n\n$out"
    }

    /** Descarga y concatena segmentos .vtt de una representacion DASH. */
    private fun fromDash(task: Task, rep: Mpd.Rep, mpdUrl: String): String? {
        val mime = rep.mimeType.lowercase()
        if (mime.contains("mp4") || mime.contains("wvtt")) return null // no concatenable
        val sb = StringBuilder()
        var headerWritten = false
        fun abs(u: String): String =
            if (rep.baseUrl != null) Kit.resolveUrl(rep.baseUrl, u) else Kit.resolveUrl(mpdUrl, u)
        fun addSegment(text: String) {
            if (!headerWritten && text.trimStart().startsWith("WEBVTT")) {
                sb.append(text).append("\n\n")
                headerWritten = true
            } else {
                val part = cleanVtt(text)
                if (part.isNotEmpty()) sb.append(part).append("\n\n")
            }
        }
        if (rep.initTpl != null && rep.initTpl.isNotEmpty()) {
            try { addSegment(getText(task, abs(fill(rep.initTpl, rep)))) } catch (e: Exception) { }
        }
        if (rep.segUrls.isNotEmpty()) {
            for (u in rep.segUrls) {
                try { addSegment(getText(task, abs(u))) } catch (e: Exception) { }
            }
        } else if (rep.mediaTpl != null) {
            val times = rep.times
            if (times.isNotEmpty()) {
                var t = 0L
                for (d in times) {
                    try { addSegment(getText(task, abs(fill(rep.mediaTpl!!, rep)
                        .replace("\$Time\$", t.toString())))) } catch (e: Exception) { }
                    t += d
                }
            } else {
                var n = rep.startNumber
                var misses = 0
                while (misses < 3) {
                    try {
                        addSegment(getText(task, abs(fill(rep.mediaTpl!!, rep)
                            .replace("\$Number\$", n.toString()))))
                        misses = 0
                    } catch (e: Exception) { misses++ }
                    n++
                }
            }
        }
        val out = sb.toString()
        return if (out.startsWith("WEBVTT") || out.isEmpty()) out else null
    }

    private fun fill(tpl: String, rep: Mpd.Rep): String =
        tpl.replace("\$RepresentationID\$", rep.id)

    /** Quita la cabecera WEBVTT y las lineas X-TIMESTAMP-MAP de un segmento. */
    private fun cleanVtt(seg: String): String {
        val lines = seg.lines().toMutableList()
        while (lines.isNotEmpty()) {
            val l = lines[0].trim()
            if (l.isEmpty() || l == "WEBVTT" || l.startsWith("WEBVTT ") ||
                l.startsWith("X-TIMESTAMP-MAP") || l.startsWith("NOTE")) lines.removeAt(0)
            else break
        }
        return lines.joinToString("\n").trim('\n')
    }
}

/** Puente minimo para User-Agent del perfil activo. */
object EngineHolder {
    @Volatile var ua: String = "Mozilla/5.0 (Linux; Android) TurboDL"
}
