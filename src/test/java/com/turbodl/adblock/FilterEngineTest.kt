package com.turbodl.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM del motor de bloqueo 100% Kotlin: primero la sintaxis con una
 * lista sintética, luego una prueba de humo contra EasyList/EasyPrivacy
 * REALES (las integradas en el APK) comprobando que bloquean anuncios y NO
 * bloquean subrecursos legítimos — el fallo que motivó rehacer el motor.
 */
class FilterEngineTest {

    private fun engine(text: String): FilterEngine {
        val e = FilterEngine()
        e.parse(text)
        return e
    }

    // ------------------------------------------------ sintaxis sintética
    @Test
    fun `regla host basica con separador`() {
        val e = engine("||ads.example.com^\n")
        assertTrue(e.shouldBlock("https://ads.example.com/x.js", "https://www.otro.com/", "script"))
        assertTrue(e.shouldBlock("https://www.ads.example.com/x.js", "https://www.otro.com/", "script"))
        // NO debe bloquear example.com (subdominios sí, el dominio base no es ads)
        assertFalse(e.shouldBlock("https://example.com/x.js", "https://www.otro.com/", "script"))
        // ni un host que solo CONTIENE el nombre en medio sin frontera de dominio
        assertFalse(e.shouldBlock("https://ads.example.com.evil.net/x", "https://www.otro.com/", "script"))
    }

    @Test
    fun `separador y comodin`() {
        val e = engine("/banner*.js\n||cdn.example^\n")
        assertTrue("comodin simple", e.shouldBlock("https://a.com/banner-X.js", "https://b.com/", "script"))
        assertTrue("comodin con path", e.shouldBlock("https://a.com/img/banner/big.js", "https://b.com/", "script"))
        assertFalse("no debe casar bannerette.css", e.shouldBlock("https://a.com/bannerette.css", "https://b.com/", "script"))
        assertTrue("host con separador", e.shouldBlock("https://cdn.example/a/b", "https://b.com/", "script"))
    }

    @Test
    fun `anclas absolutas`() {
        val e = engine("|https://especifico.com/track|")
        assertTrue(e.shouldBlock("https://especifico.com/track", "https://a.com/", "xmlhttprequest"))
        assertFalse(e.shouldBlock("https://especifico.com/track/extra", "https://a.com/", "xmlhttprequest"))
    }

    @Test
    fun `tipos de recurso`() {
        val e = engine("||server.com^\$image\n||s2.com^\$script,stylesheet\n")
        assertTrue(e.shouldBlock("https://server.com/x", "https://a.com/", "image"))
        assertFalse(e.shouldBlock("https://server.com/x", "https://a.com/", "script"))
        assertTrue(e.shouldBlock("https://s2.com/x", "https://a.com/", "script"))
        assertTrue(e.shouldBlock("https://s2.com/x", "https://a.com/", "stylesheet"))
        assertFalse(e.shouldBlock("https://s2.com/x", "https://a.com/", "image"))
    }

    @Test
    fun `third party y first party`() {
        val e = engine("||tracker.com^\$third-party\n||self.com^\$first-party\n")
        // third-party: la página NO es de tracker.com
        assertTrue("third-party debe bloquear", e.shouldBlock("https://tracker.com/pix", "https://a.com/", "image"))
        // primera parte: la página SÍ es de tracker.com -> la regla no aplica
        assertFalse(e.shouldBlock("https://tracker.com/pix", "https://tracker.com/", "image"))
        assertFalse(e.shouldBlock("https://cdn.tracker.com/pix", "https://tracker.com/", "image"))
        // first-party solo si la página es del mismo dominio
        assertFalse(e.shouldBlock("https://self.com/x", "https://otro.com/", "image"))
        assertTrue("first-party mismo host", e.shouldBlock("https://self.com/x", "https://self.com/", "image"))
        assertTrue("first-party subdominios hermanos", e.shouldBlock("https://cdn.self.com/x", "https://www.self.com/", "image"))
    }

    @Test
    fun `excepciones y important`() {
        val e = engine(
            "||ads.com^\n" +
            "@@||ads.com^\$image\n" +
            "||vip.com^\n" +
            "@@||vip.com^\n" +
            "||hot.com^\n" +
            "||hot.com^\$important\n")
        // bloquea...
        assertTrue(e.shouldBlock("https://ads.com/a.js", "https://x.com/", "script"))
        // ...salvo imagen (excepción @@ con tipo)
        assertFalse(e.shouldBlock("https://ads.com/a.jpg", "https://x.com/", "image"))
        // excepción general
        assertFalse(e.shouldBlock("https://vip.com/a.js", "https://x.com/", "script"))
        // \$important gana a la excepción @@ (semántica EasyList)
        assertTrue(e.shouldBlock("https://hot.com/a.js", "https://x.com/", "script"))
    }

    @Test
    fun `option domain`() {
        val e = engine("/ads/1x1\$domain=foo.com|bar.com\n")
        assertTrue(e.shouldBlock("https://cualquier-cdn.com/ads/1x1.gif", "https://foo.com/p", "image"))
        assertTrue(e.shouldBlock("https://cualquier-cdn.com/ads/1x1.gif", "https://sub.bar.com/p", "image"))
        assertFalse(e.shouldBlock("https://cualquier-cdn.com/ads/1x1.gif", "https://baz.com/p", "image"))
    }

    @Test
    fun `ocultacion de elementos`() {
        val e = engine(
            "##.banner-generico\n" +
            "noticias.com##.ads-slot\n" +
            "noticias.com#@#.ads-slot-avisado\n" +
            "otros.com#?#div:has(.emulado)\n")
        val cssNoticias = e.cssFor("https://www.noticias.com/pagina", 256 * 1024)
        assertTrue(cssNoticias.contains(".ads-slot{display:none!important}"))
        assertTrue(cssNoticias.contains(".banner-generico{display:none!important}"))
        assertFalse(cssNoticias.contains("ads-slot-avisado"))
        // #?# con :has( se descarta (CSS no estándar)
        assertFalse(e.cssFor("https://otros.com/", 256 * 1024).contains(":has("))
    }

    @Test
    fun `elemhide y generichide en excepciones`() {
        val e = engine(
            "##.banner-generico\n" +
            "misitio.com##.slot\n" +
            "@@||misitio.com^\$generichide\n")
        val css = e.cssFor("https://misitio.com/", 256 * 1024)
        // generichide: sin genéricos, pero el cosmético del dominio se conserva
        assertTrue(css.contains(".slot{display:none!important}"))
        assertFalse(css.contains(".banner-generico"))
    }

    @Test
    fun `url sin motor o rara nunca bloquea`() {
        val e = engine("||ads.com^\n")
        assertFalse(e.shouldBlock("about:blank", "https://a.com/", "script"))
        assertFalse(e.shouldBlock("https://ads.com/x", "", "script")) // página vacía
        assertFalse(e.shouldBlock("https://ads.com/x", "chrome-error://chromewebdata/", "script"))
    }

    // -------------------------------------------- humo con listas REALES
    private fun realEngine(): FilterEngine {
        val e = FilterEngine()
        var n = e.parse(java.io.File("src/main/assets/adblock/easylist.txt").readText())
        assertTrue("easylist debe producir reglas", n > 10000)
        n += e.parse(java.io.File("src/main/assets/adblock/easyprivacy.txt").readText())
        assertTrue("easyprivacy debe producir reglas", n > 10000)
        return e
    }

    // páginas "de origen" realistas
    private val pags = listOf(
        "https://www.youtube.com/", "https://www.google.com/search?q=fotos",
        "https://m.facebook.com/", "https://x.com/home",
        "https://www.reddit.com/r/Android/", "https://es.wikipedia.org/wiki/Android",
        "https://www.instagram.com/", "https://www.tiktok.com/foryou"
    )

    // subrecursos LEGÍTIMOS típicos (con y sin extensión; los sin extensión
    // los clasifica la app como xmlhttprequest)
    private val legit = listOf(
        "https://yt3.ggpht.com/ytc/AL5GRJXabc123=w48-h48",
        "https://lh3.googleusercontent.com/a/ACg8ocKabc=s40-c",
        "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRabc",
        "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
        "https://www.google.com/xjs/_/js/k=xjs.s.en.lbKVabc.js",
        "https://www.gstatic.com/gm3/images/abc.png",
        "https://fonts.gstatic.com/s/i/materialicons/abc/v6/24px.svg",
        "https://scontent.xx.fbcdn.net/v/t39.30808-6/444123_99_n.jpg?_nc_cat=1",
        "https://static.xx.fbcdn.net/rsrc.php/v3/yx/r/abc.js",
        "https://static.xx.fbcdn.net/rsrc.php/v3/yN/l/0,cross/abc.css",
        "https://abs-0.twimg.com/hash/abc.png",
        "https://pbs.twimg.com/media/Gabc?format=jpg&name=small",
        "https://www.redditstatic.com/desktop2x/abc.js",
        "https://external-preview.redd.it/abc.jpg?width=640",
        "https://preview.redd.it/abc.png",
        "https://media.tenor.com/abcAAAAC.gif",
        "https://www.bing.com/th?id=OHR.abc&w=200",
        "https://es.wikipedia.org/static/images/icons/wikipedia.png",
        "https://es.wikipedia.org/w/load.php?modules=startup&only=scripts",
        "https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg",
        "https://www.whatsapp.com/img/whatsapp.png"
    )

    // ANUNCIOS / RASTREADORES (control positivo)
    private val ads = listOf(
        "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
        "https://www.googletagservices.com/tag/js/gpt.js",
        "https://googleads.g.doubleclick.net/pagead/id",
        "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
        "https://cdn.taboola.com/libtrc/abc/loader.js",
        "https://www.google-analytics.com/analytics.js",
        "https://www.googletagmanager.com/gtag/js?id=G-1",
        "https://analytics.tiktok.com/i18n/pixel/events.js",
        "https://static.ads-twitter.com/uwt.js",
        "https://connect.facebook.net/en_US/fbevents.js",
        "https://s.amazon-adsystem.com/v3/abc",
        "https://adservice.google.com/ddm/activity"
    )

    private fun typeOf(url: String): String {
        val u = url.substringBefore("?").substringBefore("#").lowercase()
        return when {
            u.endsWith(".js") -> "script"
            u.endsWith(".css") -> "stylesheet"
            u.matches(Regex(".*\\.(png|jpe?g|gif|webp|svg|ico|bmp)\$")) -> "image"
            u.matches(Regex(".*\\.(m3u8|mpd|mp4|webm|ts|m4s|mov|mkv)\$")) -> "media"
            u.endsWith(".html") || u.endsWith("/") -> "document"
            else -> "xmlhttprequest"
        }
    }

    @Test
    fun `humo EasyList real - cero legitimos bloqueados`() {
        val e = realEngine()
        val bloqueados = mutableListOf<String>()
        for (pag in pags) {
            for (u in legit) {
                if (e.shouldBlock(u, pag, typeOf(u))) bloqueados.add("$pag :: $u")
            }
        }
        assertTrue(
            "subrecursos legítimos bloqueados: $bloqueados",
            bloqueados.isEmpty())
    }

    @Test
    fun `humo EasyList real - bloquea anuncios`() {
        val e = realEngine()
        var b = 0
        val sinBloquear = mutableListOf<String>()
        for (pag in pags) {
            for (u in ads) {
                if (e.shouldBlock(u, pag, typeOf(u))) b++ else sinBloquear.add(u)
            }
        }
        // EasyList debe atrapar la gran mayoría (>= 70% de las combinaciones)
        assertTrue("solo $b de ${ads.size * pags.size} anuncios bloqueados; faltaron: $sinBloquear",
            b >= ads.size * pags.size * 7 / 10)
    }

    @Test
    fun `rendimiento razonable por peticion`() {
        val e = realEngine()
        val urls = legit + ads
        val t0 = System.currentTimeMillis()
        var hits = 0
        repeat(5) {
            for (pag in pags) for (u in urls) {
                if (e.shouldBlock(u, pag, typeOf(u))) hits++
            }
        }
        val ms = System.currentTimeMillis() - t0
        // (urls * pags * 5) comprobaciones; cada una < ~2 ms de media con margen
        val total = urls.size * pags.size * 5
        assertTrue("rendimiento: $ms ms para $total checks (${"%.3f".format(ms * 1000.0 / total)} us/check)",
            ms < total * 2)
        assertTrue(hits > 0)
    }
}
