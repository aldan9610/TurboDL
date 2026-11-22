package com.turbodl.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Helper de portapapeles (4.6). Antes el uso de ClipboardManager estaba
 * duplicado inline en MainActivity (383, 469, 715-716) y BrowserActivity
 * (2076-2077). La bóveda de credenciales lo centraliza aquí para copiar
 * usuario/contraseña/códigos OTP con un toque y un toast opcional.
 */
object Clipboard {

    /** Copia [text] al portapapeles con la etiqueta [label]. */
    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Devuelve el texto del portapapeles, o null si está vacío/no disponible. */
    fun paste(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(ctx)?.toString()
    }
}
