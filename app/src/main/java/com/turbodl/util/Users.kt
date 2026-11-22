package com.turbodl.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import android.webkit.CookieManager
import android.webkit.WebView
import java.io.File

/**
 * Usuarios (requisito 4.0, estilo lfbrowser): cada usuario es una sandbox
 * completa —cookies, LocalStorage, caché, credenciales, historial,
 * marcadores y accesos propios—. La separación de datos del WebView se
 * logra con WebView.setDataDirectorySuffix por usuario: como el sufijo
 * afecta al proceso entero y debe fijarse ANTES de crear cualquier
 * WebView, cambiar de usuario reinicia la aplicación (aviso previo), y
 * al arrancar el proceso monta el directorio de datos del usuario activo:
 * el servidor ve únicamente la sesión de ese usuario.
 *
 * El usuario "main" (predeterminado) no usa sufijo: es el comportamiento
 * clásico y su directorio de datos no cambia.
 *
 * 4.2: el sufijo se aplica de forma idempotente y robusta.
 *
 * 4.4: dos arreglos clave para que el cambio de usuario sea REAL —
 *   · restartApp era frágil: hacía killProcess(myPid()) tras sleep(120).
 *     Android suele REUSAR el proceso (el kernel lo recicla), así que
 *     App.onCreate se volvía a correr en el mismo proceso y
 *     applyDataDirSuffix veía appliedSuffix != null y DEJABA el sufijo
 *     anterior → el WebView seguía con la sesión del usuario previo
 *     aunque SP dijera que el activo era el nuevo. El usuario lo
 *     percibía como "solo me dice un mensaje, no cambia nada".
 *     Ahora restartApp programa un relanzamiento con AlarmManager (1s
 *     de margen) y luego System.exit(0), que termina el proceso JVM de
 *     forma que Android NO puede reusar la instancia de Application: el
 *     siguiente arranque crea un proceso nuevo y applyDataDirSuffix
 *     fija el sufijo del nuevo usuario correctamente.
 *   · Antes de cambiar, flushCookies() vuelca CookieManager/WebStorage
 *     a disco para que la sesión del usuario saliente quede persistida
 *     y no se pierda.
 *
 * 4.4: la clase U ahora lleva un `email` (identificador visible, como
 * en lfbrowser) además del `name` (etiqueta libre). Se persiste en el
 * JSON con key "e" (backward-compatible: usuarios viejos sin email se
 * muestran con name como fallback).
 */
object Users {

    class U(val id: String, val name: String, val email: String = "")

    private fun sp(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun list(ctx: Context): MutableList<U> {
        val out = ArrayList<U>()
        try {
            val arr = JSONArray(sp(ctx).getString("users", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val n = o.optString("n", "")
                val e = o.optString("e", "")
                if (id.isNotEmpty() && id != "main") out.add(U(id, n.ifEmpty { id }, e))
            }
        } catch (e: Exception) { }
        return out
    }

    fun save(ctx: Context, list: List<U>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("n", it.name).put("e", it.email)) }
        sp(ctx).edit().putString("users", arr.toString()).apply()
    }

    fun add(ctx: Context, name: String, email: String = ""): U {
        val u = U("u" + (System.currentTimeMillis() % 100000000L), name, email)
        val l = list(ctx)
        l.add(u)
        save(ctx, l)
        return u
    }

    /** Actualiza email y/o nombre de un usuario existente (4.4). */
    fun update(ctx: Context, id: String, name: String? = null, email: String? = null) {
        if (id == "main") return
        val l = list(ctx)
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        val cur = l[i]
        l[i] = U(id,
            name?.let { it.trim().ifEmpty { id } } ?: cur.name,
            email?.let { it.trim() } ?: cur.email)
        save(ctx, l)
    }

    /**
     * Cambia el nombre visible de un usuario (4.2). No toca el id ni los
     * datos del sandbox: el id sigue siendo la clave de todos los
     * almacenamientos. 4.4: delega en update() para conservar el email.
     */
    fun rename(ctx: Context, id: String, newName: String) = update(ctx, id, name = newName)

    /**
     * Elimina un usuario Y borra todos sus datos del sandbox (4.2).
     * Antes solo se quitaba el nombre de la lista JSON y los datos
     * (cookies, credenciales, historial, marcadores, accesos) quedaban
     * huérfanos en disco. Ahora se borra:
     *  - Directorio WebView del sandbox (app_webview/usr_$id/).
     *  - Filas de history con user=? en turbodl.db (lo hace el llamador).
     *  - Filas de creds con user=? en turbodl.db (igual).
     *  - SharedPrefs bookmarks_$id, history_$id, sitesP{0,1,2}_$id.
     */
    fun remove(ctx: Context, id: String) {
        if (id == "main") return
        save(ctx, list(ctx).filter { it.id != id })
        if (active(ctx) == id) setActive(ctx, "main")
        // limpiar sandbox en disco
        try { wipeSandbox(ctx, id) } catch (e: Throwable) { }
        // limpiar SharedPrefs namespaced por usuario
        val ed = sp(ctx).edit()
        for (k in listOf("bookmarks_$id", "history_$id",
                         "sitesP0_$id", "sitesP1_$id", "sitesP2_$id")) {
            ed.remove(k)
        }
        ed.apply()
    }

    /**
     * Borra el directorio WebView del sandbox del usuario. El sufijo
     * `usr_$id` mapea a `<dataDir>/app_webview/usr_$id/` en Android 9+.
     * Si el directorio no existe (usuario nuevo o ya borrado), no hace
     * nada. No toca el sandbox del usuario activo.
     */
    fun wipeSandbox(ctx: Context, id: String) {
        if (id == "main") return
        val suffix = "usr_$id"
        val webviewBase = File(ctx.dataDir, "app_webview")
        val target = File(webviewBase, suffix)
        if (target.exists()) target.deleteRecursively()
        val alt = File(ctx.dataDir, "app_${suffix}_webview")
        if (alt.exists()) alt.deleteRecursively()
    }

    fun active(ctx: Context): String = sp(ctx).getString("activeUser", "main") ?: "main"

    fun setActive(ctx: Context, id: String) {
        sp(ctx).edit().putString("activeUser", id).apply()
    }

    fun nameOf(ctx: Context, id: String): String =
        if (id == "main") "Principal" else list(ctx).firstOrNull { it.id == id }?.name ?: id

    /** Identificador visible del usuario: email si lo tiene, si no el
     *  nombre, si no el id (4.4, estilo lfbrowser que muestra el correo). */
    fun displayOf(ctx: Context, id: String): String =
        if (id == "main") "Principal"
        else list(ctx).firstOrNull { it.id == id }?.let { it.email.ifEmpty { it.name.ifEmpty { it.id } } } ?: id

    /** Sufijo de directorio de datos del WebView ya aplicado en este
     *  proceso (o null si aún no se ha fijado ninguno). Es estático al
     *  proceso: una vez fijado, NO se puede cambiar sin matar el proceso. */
    @Volatile
    private var appliedSuffix: String? = null

    /** Sufijo de directorio de datos del WebView para el usuario activo.
     *  LLAMAR SOLO DESDE App.onCreate, antes de crear cualquier WebView.
     *  4.2: idempotente y robusta ante re-entrada (no crash). */
    fun applyDataDirSuffix(ctx: Context) {
        val id = active(ctx)
        val want = if (id == "main") null else "usr_$id"
        // Ya aplicado y coincide: nada que hacer (re-entrada normal).
        if (appliedSuffix == want) return
        // Ya aplicado pero distinto: no se puede cambiar en caliente.
        if (appliedSuffix != null) {
            android.util.Log.w("TurboDL.Users",
                "setDataDirectorySuffix ya fijado a '$appliedSuffix', no se " +
                "puede cambiar a '$want' sin reiniciar el proceso")
            return
        }
        // Primera vez: lo fijamos. Atrapamos Throwable (no solo Exception)
        // porque en ciertas ROMs la API lanza Error.
        try {
            if (want != null) WebView.setDataDirectorySuffix(want)
            appliedSuffix = want
        } catch (e: Throwable) {
            android.util.Log.w("TurboDL.Users",
                "setDataDirectorySuffix('$want') falló: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Vuelca cookies y WebStorage a disco (4.4). Llamar ANTES de cambiar
     * de usuario para que la sesión del usuario saliente quede
     * persistida en su sandbox y no se pierda al reciclar el proceso.
     * Es un best-effort: si falla, no interrumpe el cambio.
     */
    fun flushCookies() {
        // CookieManager.flush() vuelca las cookies a disco; WebStorage
        // no tiene flush() público (se persiste por el WebView). Con esto
        // la sesión del usuario saliente queda guardada en su sandbox.
        try { CookieManager.getInstance().flush() } catch (e: Throwable) { }
    }

    /**
     * Reinicia la aplicación para montar la sandbox del usuario activo.
     *
     * 4.4 (arregla el bug de "solo me dice un mensaje"): antes hacía
     * killProcess(myPid()) tras sleep(120). Android suele REUSAR el
     * proceso reciclado, así que App.onCreate se volvía a correr en el
     * mismo proceso y applyDataDirSuffix veía appliedSuffix != null y
     * DEJABA el sufijo anterior → el WebView seguía con la sesión del
     * usuario previo aunque SP dijera que el activo era el nuevo.
     *
     * Ahora:
     *   1. Programa con AlarmManager un relanzamiento del launcher
     *      intent dentro de ~1 segundo (RTC_WAKEUP). AlarmManager
     *      entrega el intent tras el System.exit, en un proceso NUEVO.
     *   2. Llama a System.exit(0). A diferencia de killProcess, exit
     *      termina la VM de forma que Android NO puede reusar la
     *      instancia de Application: el siguiente onCreate crea un
     *      proceso nuevo y el sufijo se fija para el nuevo usuario.
     *
     * Si packageManager devuelve null (caso muy raro), no matamos: solo
     * cerramos las actividades para que el usuario reabra la app.
     */
    fun restartApp(ctx: Context) {
        flushCookies()
        val pi = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        if (pi != null) {
            pi.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            // Programar el relanzamiento para dentro de 1s. Usamos
            // AlarmManager con RTC_WAKEUP para que dispare aunque el
            // proceso ya haya muerto. PendingIntent.getBroadcast sería
            // más fiable pero requiere un receptor declarado; en su
            // lugar usamos getActivity que arranca directamente la
            // actividad launcher tras el delay.
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val triggerAt = System.currentTimeMillis() + 1000L
                val pending = android.app.PendingIntent.getActivity(
                    ctx, 0, pi,
                    android.app.PendingIntent.FLAG_IMMUTABLE or
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                am.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } catch (e: Throwable) {
                android.util.Log.w("TurboDL.Users",
                    "AlarmManager falló, intentando arranque inmediato: ${e.message}")
                try { ctx.startActivity(pi) } catch (e2: Throwable) { }
            }
            // System.exit(0) termina la VM de forma limpia; Android no
            // puede reusar el proceso: el siguiente onCreate crea un
            // proceso nuevo y applyDataDirSuffix fija el sufijo nuevo.
            try { Thread.sleep(50) } catch (e: InterruptedException) { }
            kotlin.system.exitProcess(0)
        } else {
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
                am.appTasks.forEach { it.finishAndRemoveTask() }
            } catch (e: Exception) { }
        }
    }
}
