package com.turbodl.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import android.webkit.WebView

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

    fun remove(ctx: Context, id: String) {
        save(ctx, list(ctx).filter { it.id != id })
        if (active(ctx) == id) setActive(ctx, "main")
    }

    fun active(ctx: Context): String = sp(ctx).getString("activeUser", "main") ?: "main"

    fun setActive(ctx: Context, id: String) {
        sp(ctx).edit().putString("activeUser", id).apply()
    }

    fun nameOf(ctx: Context, id: String): String =
        if (id == "main") "Principal" else list(ctx).firstOrNull { it.id == id }?.name ?: id

    /** Sufijo de directorio de datos del WebView para el usuario activo.
     *  LLAMAR SOLO DESDE App.onCreate, antes de crear cualquier WebView. */
    fun applyDataDirSuffix(ctx: Context) {
        try {
            val id = active(ctx)
            if (id != "main") WebView.setDataDirectorySuffix("usr_$id")
        } catch (e: Exception) { }
    }

    /**
     * Reinicia la aplicación para montar la sandbox del usuario activo.
     * Termina todas las actividades y relanza la actividad principal;
     * el proceso se recicla y App.onCreate vuelve a aplicar el sufijo.
     */
    fun restartApp(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        if (i != null) {
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ctx.startActivity(i)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
