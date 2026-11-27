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

    fun isDark(ctx: Context): Boolean = themeMode(ctx) >= 1

    /** 0 = claro, 1 = oscuro, 2 = AMOLED (negro puro). */
    fun themeMode(ctx: Context): Int =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("themeMode",
            if (ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("dark", false)) 1 else 0)

    /** Llamar ANTES de setContentView. */
    fun theme(act: Activity) {
        act.setTheme(when (themeMode(act)) {
            2 -> R.style.AppThemeAmoled
            1 -> R.style.AppThemeDark
            else -> R.style.AppTheme
        })
        if (themeMode(act) == 2) {
            // barra de estado negra en AMOLED
            try { act.window.statusBarColor = 0xFF000000.toInt() } catch (e: Exception) { }
        }
    }

    // -------------------- paleta por tema (4.17) --------------------
    // Resuelven los atributos td* del tema activo (claro/oscuro/AMOLED).
    // Todo el color de la interfaz debe salir de aquí: así el modo
    // oscuro es consistente en TODAS las pantallas y no hay blancos
    // fijos (era el bug de las barras del navegador y las hojas).

    /** Resuelve un atributo de color del tema activo. */
    fun c(ctx: Context, attr: Int): Int {
        val ta = ctx.obtainStyledAttributes(intArrayOf(attr))
        val v = try { ta.getColor(0, 0xFF00FF00.toInt()) } finally { ta.recycle() }
        return v
    }

    /** Fondo general de la pantalla. */
    fun bg(ctx: Context): Int = c(ctx, R.attr.tdBg)
    /** Superficie de tarjetas, hojas inferiores y diálogos. */
    fun card(ctx: Context): Int = c(ctx, R.attr.tdCard)
    /** Barras (navegador arriba/abajo, paneles elevados). */
    fun bar(ctx: Context): Int = c(ctx, R.attr.tdBar)
    /** Texto principal. */
    fun text(ctx: Context): Int = c(ctx, R.attr.tdText)
    /** Texto secundario (subtítulos, fechas, descripciones). */
    fun text2(ctx: Context): Int = c(ctx, R.attr.tdText2)
    /** Texto terciario y hints. */
    fun text3(ctx: Context): Int = c(ctx, R.attr.tdText3)
    /** Divisores y bordes. */
    fun divider(ctx: Context): Int = c(ctx, R.attr.tdDivider)
    /** Fondo suave para avatares y resaltes ligero. */
    fun soft(ctx: Context): Int = c(ctx, R.attr.tdSoft)
    /** Naranja de acento según el tema (más claro en oscuro). */
    fun accent(ctx: Context): Int = c(ctx, android.R.attr.colorAccent)

    /** Drawable de botón plano (sin fondo, con ripple) del tema activo.
     *  Es un ATRIBUTO de tema, no un recurso directo: hay que resolverlo. */
    fun borderless(act: Activity): android.graphics.drawable.Drawable? {
        val v = android.util.TypedValue()
        act.theme.resolveAttribute(android.R.attr.borderlessButtonStyle, v, true)
        return act.getDrawable(v.resourceId)
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
