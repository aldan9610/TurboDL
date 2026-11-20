package com.turbodl.util

import android.util.Base64
import java.util.Locale

/**
 * Esquemas especiales tipo FileCentipede:
 *  thunder://  -> Base64(URL)
 *  flashget:// -> Base64(URL)&nombre
 *  qqdl://     -> Base64(URL)
 *  ed2k://     -> se pasa tal cual (no soportado, mensaje claro)
 */
object Schemes {
    fun decode(url: String): Pair<String, String> { // (url real, aviso o vacio)
        val u = url.trim()
        val low = u.lowercase(Locale.US)
        return try {
            when {
                low.startsWith("thunder://") -> {
                    val raw = u.substring(10)
                    val dec = String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
                    dec.trim() to ""
                }
                low.startsWith("qqdl://") -> {
                    val raw = u.substring(7)
                    String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim() to ""
                }
                low.startsWith("flashget://") -> {
                    val raw = u.substring(11).substringBefore('&')
                    String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim() to ""
                }
                low.startsWith("ed2k://") -> u to "ed2k no soportado todavia en esta version"
                else -> u to ""
            }
        } catch (e: Exception) {
            u to "No se pudo decodificar el enlace"
        }
    }

    /** Detecta tipo de descarga por el enlace */
    fun detectKind(url: String): Int {
        val low = url.lowercase(Locale.US).substringBefore('?').substringBefore('#')
        return when {
            low.startsWith("magnet:") -> com.turbodl.model.Kind.TORRENT
            low.startsWith("ftp://") -> com.turbodl.model.Kind.FTP
            low.startsWith("ftps://") -> com.turbodl.model.Kind.FTP
            low.endsWith(".m3u8") -> com.turbodl.model.Kind.HLS
            low.endsWith(".mpd") -> com.turbodl.model.Kind.DASH
            low.endsWith(".torrent") -> com.turbodl.model.Kind.TORRENT
            else -> com.turbodl.model.Kind.HTTP
        }
    }
}
