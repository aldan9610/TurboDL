package com.turbodl.util

import android.content.SharedPreferences

/**
 * Perfiles de navegacion: el servidor ve distintos dispositivos segun el
 * perfil elegido (User-Agent) tanto en el navegador integrado como en las
 * descargas. "Android Google" es el movil con Chrome; "Escritorio Windows"
 * activa la vista de escritorio, etc.
 */
object Profiles {

    class P(val name: String, val ua: String, val desktop: Boolean)

    val LIST = arrayOf(
        P("Android (Google Chrome móvil)",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            false),
        P("Escritorio Windows (Chrome)",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            true),
        P("Escritorio Windows (Firefox)",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
            true),
        P("Escritorio Linux (Firefox)",
            "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0",
            true),
        P("Escritorio macOS (Safari)",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
            true),
        P("iPhone (Safari móvil)",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
            false),
        P("iPad (Safari tablet)",
            "Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/604.1",
            false),
        P("Bot de Google (Googlebot)",
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            false),
        P("Wget (sin JavaScript)",
            "Wget/1.21.4",
            false)
    )

    const val CUSTOM = "Personalizado (escribir User-Agent)"

    fun index(sp: SharedPreferences): Int = sp.getInt("profile", 0)

    fun uaFor(sp: SharedPreferences): String {
        val i = index(sp)
        if (sp.getBoolean("profileCustom", false)) {
            val c = sp.getString("uaCustom", null)
            if (!c.isNullOrBlank()) return c
        }
        return LIST[i.coerceIn(0, LIST.size - 1)].ua
    }

    fun isDesktop(sp: SharedPreferences): Boolean {
        if (sp.getBoolean("profileCustom", false)) {
            return sp.getBoolean("deskDefault", false)
        }
        return LIST[index(sp).coerceIn(0, LIST.size - 1)].desktop
    }

    fun name(sp: SharedPreferences): String =
        if (sp.getBoolean("profileCustom", false)) CUSTOM
        else LIST[index(sp).coerceIn(0, LIST.size - 1)].name

    /** Buscadores disponibles para el navegador integrado. */
    val ENGINES = arrayOf(
        "DuckDuckGo (HTML)",
        "Google",
        "Bing",
        "Brave",
        "Startpage",
        "Ecosia",
        "Yandex",
        "Wikipedia (es)",
        "Mojeek"
    )

    fun engineUrl(sp: SharedPreferences, query: String): String {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val tpl = when (sp.getInt("searchEng", 0)) {
            1 -> "https://www.google.com/search?q=%s"
            2 -> "https://www.bing.com/search?q=%s"
            3 -> "https://search.brave.com/search?q=%s"
            4 -> "https://www.startpage.com/sp/search?query=%s"
            5 -> "https://www.ecosia.org/search?q=%s"
            6 -> "https://yandex.com/search/?text=%s"
            7 -> "https://es.wikipedia.org/w/index.php?search=%s"
            8 -> "https://www.mojeek.com/search?q=%s"
            else -> "https://duckduckgo.com/html/?q=%s"
        }
        return if (sp.getInt("searchEng", 0) == 99) {
            val custom = sp.getString("searchCustom", "") ?: ""
            if (custom.contains("%s")) String.format(custom, enc) else custom + enc
        } else String.format(tpl, enc)
    }
}
