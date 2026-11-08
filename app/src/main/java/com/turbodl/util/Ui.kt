package com.turbodl.util

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.turbodl.R

/**
 * Ajustes de apariencia: tema claro/oscuro, tamano de fuente y tipografia.
 * Todo via framework (sin AndroidX) y aplicado recorriendo la jerarquia.
 */
object Ui {

    val FAMILIES = arrayOf(
        "sans-serif" to "Sans (por defecto)",
        "sans-serif-condensed" to "Sans condensada",
        "sans-serif-light" to "Sans ligera",
        "sans-serif-medium" to "Sans media",
        "serif" to "Serif",
        "monospace" to "Monoespaciada"
    )

    fun isDark(ctx: Context): Boolean =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("dark", false)

    /** Llamar ANTES de setContentView. */
    fun theme(act: Activity) {
        act.setTheme(if (isDark(act)) R.style.AppThemeDark else R.style.AppTheme)
    }

    fun fontScale(ctx: Context): Float {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        return p.getInt("fontScale", 100) / 100f
    }

    fun fontFamily(ctx: Context): String {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        return p.getString("fontFamily", "sans-serif") ?: "sans-serif"
    }

    /** Aplica escala y tipografia a toda la jerarquia (llamar tras setContentView). */
    fun styleAll(root: View) {
        val scale = fontScale(root.context)
        val family = fontFamily(root.context)
        walk(root) { v ->
            if (v is TextView) {
                if (scale != 1f) {
                    v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, v.textSize * scale)
                }
                try {
                    val style = v.typeface?.style ?: Typeface.NORMAL
                    v.typeface = Typeface.create(family, style)
                } catch (e: Exception) { }
            }
        }
    }

    private fun walk(v: View, f: (View) -> Unit) {
        f(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), f)
        }
    }

    /** Modo escritorio on/off para un WebView segun el perfil activo. */
    fun applyUa(web: android.webkit.WebView, sp: android.content.SharedPreferences, desktop: Boolean) {
        val ws = web.settings
        ws.userAgentString = if (desktop) desktopUa() else uaFor(sp, desktop)
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.textZoom = 100
    }

    private fun uaFor(sp: android.content.SharedPreferences, desktop: Boolean): String {
        return if (desktop) desktopUa() else Profiles.uaFor(sp)
    }

    fun desktopUa(): String = Profiles.LIST[1].ua // Windows Chrome
}
