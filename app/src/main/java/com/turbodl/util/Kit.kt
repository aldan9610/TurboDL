package com.turbodl.util

import android.webkit.MimeTypeMap
import java.io.File
import java.net.URLDecoder
import java.util.Locale

object Kit {
    fun fmtBytes(b: Long): String {
        if (b < 0) return "?"
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024.0; i++ }
        return if (i == 0) "${b} ${u[0]}" else String.format(Locale.US, "%.1f %s", v, u[i])
    }

    fun fmtSpeed(bps: Long): String = fmtBytes(bps) + "/s"

    fun fmtEta(seconds: Long): String {
        if (seconds < 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /** Duracion compacta: 1h 02:03 / 12:05 / 45 s (estilo del ejemplo del usuario) */
    fun fmtDur(sec: Double): String {
        if (sec <= 0 || sec.isNaN()) return ""
        val total = sec.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            h > 0 -> String.format(Locale.US, "%dh %02d:%02d", h, m, s)
            m > 0 -> String.format(Locale.US, "%d:%02d min", m, s)
            else -> "$total s"
        }
    }

    /** Nombre de archivo razonable a partir de una URL */
    fun nameFromUrl(url: String): String {
        try {
            var p = url
            val q = p.indexOf('?')
            if (q >= 0) p = p.substring(0, q)
            val h = p.indexOf('#')
            if (h >= 0) p = p.substring(0, h)
            val idx = p.lastIndexOf('/')
            if (idx >= 0 && idx < p.length - 1) {
                var n = URLDecoder.decode(p.substring(idx + 1), "UTF-8")
                n = n.replace("[\\\\/:*?\"<>|]".toRegex(), "_").trim()
                if (n.isNotEmpty() && n.length < 150) return n
            }
        } catch (e: Exception) { }
        return "archivo"
    }

    /** filename= o filename*= de Content-Disposition */
    fun nameFromDisposition(cd: String?): String? {
        if (cd == null) return null
        try {
            val star = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(cd)
            if (star != null) return URLDecoder.decode(star.groupValues[1].trim('"'), "UTF-8")
            val fn = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)
            if (fn != null) return fn.groupValues[1].trim()
        } catch (e: Exception) { }
        return null
    }

    fun extOf(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i in 1 until name.length - 1) name.substring(i + 1).lowercase(Locale.US) else ""
    }

    fun mimeOf(name: String): String {
        val ext = extOf(name)
        val m = if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null
        return m ?: when (ext) {
            "ts" -> "video/mp2t"
            "m4s" -> "video/iso.segment"
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "m4a" -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    /** evita sobrescribir: archivo (2).ext (modo local) */
    fun uniqueName(dir: String, name: String): String {
        val f = File(dir, name)
        if (!f.exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (i < 1000) {
            val n = "$base ($i)$ext"
            if (!File(dir, n).exists()) return n
            i++
        }
        return "$base.${System.currentTimeMillis()}$ext"
    }

    fun resolveUrl(base: String, rel: String): String {
        return try { java.net.URL(java.net.URL(base), rel).toString() } catch (e: Exception) { rel }
    }

    fun isVideoOrAudio(url: String): Boolean {
        val u = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        return Regex("\\.(m3u8|mpd|mp4|webm|mov|mkv|m4s|ts|mp3|aac|m4a|ogg|opus|flac|wav|avi|flv)$").containsMatchIn(u)
    }

    fun fmtOf(url: String): String {
        val u = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        return when {
            u.endsWith(".m3u8") -> "HLS"
            u.endsWith(".mpd") -> "DASH"
            u.endsWith(".mp4") -> "MP4"
            u.endsWith(".webm") -> "WEBM"
            u.endsWith(".mkv") -> "MKV"
            u.endsWith(".mp3") -> "MP3"
            u.endsWith(".m4a") -> "M4A"
            u.endsWith(".aac") -> "AAC"
            u.endsWith(".flac") -> "FLAC"
            u.endsWith(".opus") -> "OPUS"
            u.endsWith(".ts") -> "TS"
            u.endsWith(".m4s") -> "fMP4"
            else -> extOf(u).uppercase(Locale.US).ifEmpty { "?" }
        }
    }

    /** Clasifica un medio: video / audio / lista / otro */
    fun mediaClass(url: String): String {
        val u = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        return when {
            u.endsWith(".m3u8") || u.endsWith(".mpd") -> "stream"
            Regex("\\.(mp4|webm|mov|mkv|avi|flv|ts|m4s)$").containsMatchIn(u) -> "video"
            Regex("\\.(mp3|aac|m4a|ogg|opus|flac|wav)$").containsMatchIn(u) -> "audio"
            else -> "otro"
        }
    }
}

/**
 * Limitador global de velocidad (token bucket).
 * bytesPerSec <= 0 significa sin limite.
 */
class SpeedLimiter(@Volatile var bytesPerSec: Long = 0) {
    private var tokens: Long = 0
    private var last: Long = System.nanoTime()

    @Synchronized
    fun take(n: Int) {
        val limit = bytesPerSec
        if (limit <= 0) return
        refill()
        while (tokens < n) {
            val deficit = n - tokens
            val waitNs = deficit * 1_000_000_000L / limit
            try {
                Thread.sleep(waitNs / 1_000_000, (waitNs % 1_000_000).toInt())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            refill()
        }
        tokens -= n
    }

    @Synchronized
    private fun refill() {
        val now = System.nanoTime()
        val elapsed = now - last
        if (elapsed > 0) {
            tokens = (tokens + elapsed * bytesPerSec / 1_000_000_000L).coerceAtMost(bytesPerSec)
            last = now
        }
    }
}
