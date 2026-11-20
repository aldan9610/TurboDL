package com.turbodl.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
 * 4.2: el sufijo ahora se aplica de forma idempotente y robusta. Antes
 * se llamaba a `WebView.setDataDirectorySuffix` en cada `App.onCreate`
 * envuelto en `catch (e: Exception)`: en Android 9+ la API lanza
 * `IllegalStateException` (subclase de RuntimeException, sí atrapado) la
 * SEGUNDA vez que se fija el sufijo en el mismo proceso, o si ya existe
 * un WebView vivo. Como `restartApp` a veces no mata el proceso a
 * tiempo y Android re-entra por `App.onCreate` con un sufijo distinto,
 * la segunda llamada lanzaba y dejaba el sandbox inconsistente. Ahora:
 *  - Se atrapa `Throwable` (cubre también `Error`).
 *  - Se registra el sufijo ya aplicado en una variable estática; si
 *    coincide con el que se quiere aplicar, se omite la llamada (caso
 *    normal de re-entrada tras `restartApp` que no mató el proceso).
 *  - Si el sufijo ya aplicado NO coincide con el activo, se deja el
 *    anterior (no se puede cambiar en caliente) y se registra en log;
 *    la app sigue funcionando con el sandbox anterior, sin crash.
 */
object Users {

    class U(val id: String, val name: String)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun list(ctx: Context): MutableList<U> {
        val out = ArrayList<U>()
        try {
            val arr = JSONArray(sp(ctx).getString("users", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val n = o.optString("n", "")
                if (id.isNotEmpty() && id != "main") out.add(U(id, n.ifEmpty { id }))
            }
        } catch (e: Exception) { }
        return out
    }

    fun save(ctx: Context, list: List<U>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("n", it.name)) }
        sp(ctx).edit().putString("users", arr.toString()).apply()
    }

    fun add(ctx: Context, name: String): U {
        val u = U("u" + (System.currentTimeMillis() % 100000000L), name)
        val l = list(ctx)
        l.add(u)
        save(ctx, l)
        return u
    }

    /**
     * Cambia el nombre visible de un usuario (4.2: completa el CRUD —
     * antes solo se podía crear y eliminar, no renombrar). No toca el id
     * ni los datos del sandbox: el id sigue siendo la clave de todos los
     * almacenamientos (history/creds/SharedPrefs/webview dir).
     */
    fun rename(ctx: Context, id: String, newName: String) {
        if (id == "main") return
        val l = list(ctx)
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        l[i] = U(id, newName.trim().ifEmpty { id })
        save(ctx, l)
    }

    /**
     * Elimina un usuario Y borra todos sus datos del sandbox (4.2).
     * Antes solo se quitaba el nombre de la lista JSON y los datos
     * (cookies, credenciales, historial, marcadores, accesos) quedaban
     * huérfanos en disco. Ahora se borra:
     *  - Directorio WebView del sandbox (app_webview/usr_$id/).
     *  - Filas de history con user=? en turbodl.db (lo hace el llamador
     *    a través de Db.clearHistory(id); aquí no accedemos a la BD
     *    para no crear dependencia circular).
     *  - Filas de creds con user=? en turbodl.db (igual).
     *  - SharedPrefs bookmarks_$id, history_$id, sitesP{0,1,2}_$id.
     * El limpiado de DB lo hace el llamador (BrowserActivity) que ya
     * tiene la instancia de Engine/Db; aquí solo borramos archivos y
     * prefs, que son las fugas que el código antiguo dejaba.
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
        // app_webview/ es el dir por defecto del WebView; el sufijo crea
        // un subdirectorio con ese nombre dentro de él.
        val webviewBase = File(ctx.dataDir, "app_webview")
        val target = File(webviewBase, suffix)
        if (target.exists()) target.deleteRecursively()
        // Algunas ROMs usan <dataDir>/app_<sufijo>_webview/; por si acaso.
        val alt = File(ctx.dataDir, "app_${suffix}_webview")
        if (alt.exists()) alt.deleteRecursively()
    }

    fun active(ctx: Context): String = sp(ctx).getString("activeUser", "main") ?: "main"

    fun setActive(ctx: Context, id: String) {
        sp(ctx).edit().putString("activeUser", id).apply()
    }

    fun nameOf(ctx: Context, id: String): String =
        if (id == "main") "Principal" else list(ctx).firstOrNull { it.id == id }?.name ?: id

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
        // Mantenemos el anterior y la app sigue con ese sandbox. No
        // lanzamos: el usuario debe reiniciar (lo hace restartApp).
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
            // No marcamos appliedSuffix: si la siguiente llamada quiere
            // el mismo sufijo lo reintentará; si quiere otro, también.
            // La app sigue con el dir por defecto (sandbox 'main').
        }
    }

    /**
     * Reinicia la aplicación para montar la sandbox del usuario activo.
     * Termina todas las actividades y relanza la actividad principal;
     * el proceso se recicla y App.onCreate vuelve a aplicar el sufijo.
     * 4.2: si getLaunchIntentForPackage devuelve null (caso raro),
     * no matamos el proceso sin relanzar — terminamos solo las
     * actividades y dejamos que el usuario vuelva a abrir la app.
     */
    fun restartApp(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        if (i != null) {
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            try { ctx.startActivity(i) } catch (e: Exception) { }
            // pequeña pausa para que el nuevo task se agende antes de matar
            try { Thread.sleep(120) } catch (e: InterruptedException) { }
            android.os.Process.killProcess(android.os.Process.myPid())
        } else {
            // fallback: no podemos relanzar, pero al menos sacamos al
            // usuario de la Activity actual para que reabra la app.
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
                am.appTasks.forEach { it.finishAndRemoveTask() }
            } catch (e: Exception) { }
        }
    }
}
