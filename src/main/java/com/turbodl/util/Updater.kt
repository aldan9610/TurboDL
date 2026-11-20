package com.turbodl.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.turbodl.engine.Engine
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualización automática desde GitHub Releases (la app se distribuye
 * fuera de tiendas, así que aquí vive el ciclo completo: consultar la
 * última release, comparar versiones y descargar+instalar el APK oficial).
 *
 * Todo es opt-out vía ajustes ("autoUpdate") y la consulta manual está
 * disponible en Ajustes > "Buscar actualizaciones".
 */
object Updater {

    private const val RELEASES_API =
        "https://api.github.com/repos/aldan9610/TurboDL/releases/latest"

    class Rel(val tag: String, val htmlUrl: String, val notes: String, val apkUrl: String?) {
        val apkName: String
            get() = "TurboDL-$tag.apk"
    }

    /** Versión instalada (versionName del paquete, p. ej. "3.8.1"). */
    fun installedVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    /** "3.8.1" -> [3, 8, 1]; ignora letras/sufijos. Lista vacía si no hay dígitos. */
    fun versionList(v: String): List<Int> =
        Regex("\\d+").findAll(v.trim()).take(4).map { it.value.toInt() }.toList()

    /** true si remote es una versión estrictamente mayor que local. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = versionList(remote)
        val l = versionList(local)
        if (r.isEmpty()) return false
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Consulta la última release publicada. Null si falla (sin red, API caída). */
    fun latest(): Rel? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TurboDL")
            }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val o = JSONObject(body)
            val tag = o.optString("tag_name", "").trim()
            if (tag.isEmpty()) return null
            var apk: String? = null
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "")
                    val url = a.optString("browser_download_url", "")
                    if (name.endsWith(".apk") && url.isNotEmpty()) {
                        apk = url // el primer APK publicado (hay uno por release)
                        break
                    }
                }
            }
            Rel(tag, o.optString("html_url", ""), o.optString("body", ""), apk)
        } catch (e: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }

    /** Chequeo al abrir la app: silencioso salvo que haya versión nueva. */
    fun checkSilent(act: Activity, eng: Engine) {
        if (!eng.sp.getBoolean("autoUpdate", true)) return
        // como mucho una consulta por día de arranque
        val now = System.currentTimeMillis()
        val last = eng.sp.getLong("updateLastCheck", 0L)
        if (now - last < 24L * 3600 * 1000) return
        eng.sp.edit().putLong("updateLastCheck", now).apply()
        check(act, eng, manual = false)
    }

    /** Chequeo con interfaz (Ajustes): informa aunque no haya nada nuevo. */
    fun check(act: Activity, eng: Engine, manual: Boolean) {
        Thread {
            val rel = latest()
            act.runOnUiThread {
                if (rel == null) {
                    if (manual) toast(act, "No se pudo consultar GitHub (¿sin internet?)")
                    return@runOnUiThread
                }
                val local = installedVersion(act)
                if (!isNewer(rel.tag, local)) {
                    if (manual) toast(act, "Ya tienes la última versión ($local)")
                    return@runOnUiThread
                }
                showUpdateDialog(act, eng, rel, local)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showUpdateDialog(act: Activity, eng: Engine, rel: Rel, local: String) {
        val msg = StringBuilder()
        msg.append("Versión instalada: $local\n")
        msg.append("Versión disponible: ${rel.tag}\n")
        if (rel.notes.isNotBlank()) {
            msg.append('\n').append(rel.notes.take(400))
            if (rel.notes.length > 400) msg.append("…")
        }
        msg.append("\n\nEl APK se descarga de GitHub Releases (fuente oficial del proyecto).")
        android.app.AlertDialog.Builder(act)
            .setTitle("⬆ Actualización disponible")
            .setMessage(msg.toString())
            .setPositiveButton("Descargar e instalar") { _, _ ->
                downloadAndInstall(act, rel)
            }
            .setNeutralButton("Ver en GitHub") { _, _ ->
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.htmlUrl)))
                } catch (e: Exception) { toast(act, "No se pudo abrir el navegador") }
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    /** Descarga el APK a la caché y lanza el instalador del sistema. */
    fun downloadAndInstall(act: Activity, rel: Rel) {
        toast(act, "Descargando ${rel.apkName}…")
        Thread {
            val dir = File(act.cacheDir, "install")
            dir.mkdirs()
            val dst = File(dir, rel.apkName)
            try {
                val conn = (URL(rel.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "TurboDL")
                }
                if (conn.responseCode !in 200..299) {
                    throw java.io.IOException("HTTP ${conn.responseCode}")
                }
                conn.inputStream.use { inp -> inp.copyTo(java.io.FileOutputStream(dst)) }
                conn.disconnect()
                if (dst.length() < 1024 * 1024) throw java.io.IOException("APK incompleto")
                act.runOnUiThread { launchInstaller(act, dst) }
            } catch (e: Exception) {
                act.runOnUiThread {
                    toast(act, "No se pudo descargar: ${e.message}")
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun launchInstaller(act: Activity, apk: File) {
        try {
            // Android 8+: el usuario debe autorizar "instalar apps desconocidas"
            if (android.os.Build.VERSION.SDK_INT >= 26 &&
                !act.packageManager.canRequestPackageInstalls()) {
                toast(act, "Autoriza «Instalar apps desconocidas» para TurboDL y vuelve a intentarlo")
                try {
                    act.startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${act.packageName}")))
                } catch (e: Exception) { }
                return
            }
            val uri = Uri.parse("content://com.turbodl.files/install/${Uri.encode(apk.name)}")
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(i)
        } catch (e: Exception) {
            toast(act, "No se pudo instalar: ${e.message}")
        }
    }

    private fun toast(act: Activity, m: String) =
        Toast.makeText(act, m, Toast.LENGTH_LONG).show()
}
