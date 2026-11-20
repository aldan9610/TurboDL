package com.turbodl.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro local de fallos: las excepciones no capturadas se anexan a
 * filesDir/logs/crash.log (capado a ~300 KB) con versión y modelo del
 * dispositivo. Sin telemetría remota: el usuario decide si lo comparte
 * desde Ajustes > "Compartir registro de fallos".
 */
object CrashLog {

    /** Instala el manejador global (llamar una vez, en App.onCreate). */
    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { append(ctx, t, e) } catch (ignored: Throwable) { }
            try { prev?.uncaughtException(t, e) } catch (ignored: Throwable) { }
        }
    }

    fun logFile(ctx: Context): File {
        val d = File(ctx.filesDir, "logs")
        d.mkdirs()
        return File(d, "crash.log")
    }

    /** Texto del último fallo registrado ("" si no hay). */
    fun lastCrash(ctx: Context): String {
        try {
            val f = logFile(ctx)
            if (!f.exists()) return ""
            val lines = f.readLines(Charsets.UTF_8)
            // cada bloque empieza con la marca de fecha "== "
            val idx = lines.indexOfLast { it.startsWith("== ") }
            if (idx < 0) return ""
            return lines.subList(idx, lines.size).joinToString("\n").take(1200)
        } catch (e: Exception) { return "" }
    }

    fun clear(ctx: Context) {
        try { logFile(ctx).delete() } catch (e: Exception) { }
    }

    /** Anexa un fallo; también usable para registrar eventos propios. */
    fun append(ctx: Context, thread: Thread?, e: Throwable) {
        val f = logFile(ctx)
        // cap: si se pasa de 300 KB, se conserva solo la mitad final
        if (f.exists() && f.length() > 300L * 1024) {
            try {
                val all = f.readText(Charsets.UTF_8)
                f.writeText(all.substring(all.length / 2))
            } catch (ignored: Exception) { }
        }
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val v = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (ignored: Exception) { "?" }
        f.appendText(
            "== $stamp · TurboDL $v · Android ${android.os.Build.VERSION.RELEASE} · " +
                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                "hilo: ${thread?.name ?: "?"}\n$sw\n", Charsets.UTF_8
        )
    }
}
