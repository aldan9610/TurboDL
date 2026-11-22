package com.turbodl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URISyntaxException

/**
 * Tests unitarios de la lógica de resolución de URL que usa la barra de
 * dirección del navegador (Bug 4): dado lo que el usuario escribe, decidir
 * si es una URL directa (con esquema), un dominio (añadir https://) o una
 * búsqueda en el motor activo.
 *
 * La lógica replicada es la de `BrowserActivity.go()`:
 *   - Si tiene "://" → URL literal.
 *   - Si tiene "." pero no "://" → se le añade "https://".
 *   - Si no tiene ni "://" ni "." → búsqueda en el motor activo.
 *
 * No testeamos BrowserActivity directamente (requeriría un Activity
 * Instrumentado con Robolectric o un emulador, fuera del alcance de los
 * tests JVM puros); en su lugar extraemos la lógica de decisión a una
 * función pura aquí para que el test sea reproducible.
 */
class UrlBarResolutionTest {

    /**
     * Replica exacta de la lógica de `BrowserActivity.go()`. Si en una
     * futura refactorización se extrae esta lógica a una utilidad
     * compartida, este test se podrá reescribir para llamarla
     * directamente; mientras tanto, blinda el contrato.
     *
     * @param input texto tecleado por el usuario en la barra de dirección.
     * @param engineUrl plantilla del motor activo (con %s para el query).
     * @return URL final que se cargaría en el WebView.
     */
    private fun resolve(input: String, engineUrl: (String) -> String): String {
        val query = input.trim()
        if (query.isEmpty()) return ""
        return when {
            query.contains("://") -> query
            query.contains(".") -> "https://$query"
            else -> engineUrl(query)
        }
    }

    @Test
    fun `URL con esquema http se usa literal`() {
        assertEquals("http://example.com",
            resolve("http://example.com") { "https://search.com/?q=${it}" })
    }

    @Test
    fun `URL con esquema https se usa literal`() {
        assertEquals("https://example.com/path?q=1",
            resolve("https://example.com/path?q=1") { "https://search.com/?q=${it}" })
    }

    @Test
    fun `texto con punto pero sin esquema se le anade https`() {
        assertEquals("https://example.com",
            resolve("example.com") { "https://search.com/?q=${it}" })
        assertEquals("https://sub.example.com/path",
            resolve("sub.example.com/path") { "https://search.com/?q=${it}" })
    }

    @Test
    fun `texto sin punto ni esquema se busca en el motor activo`() {
        val result = resolve("hello world") { q -> "https://search.com/?q=${q.replace(" ", "+")}" }
        assertEquals("https://search.com/?q=hello+world", result)
    }

    @Test
    fun `cadena vacia no resuelve a nada`() {
        assertEquals("", resolve("") { "https://search.com/?q=$it" })
        assertEquals("", resolve("   ") { "https://search.com/?q=$it" })
    }

    @Test
    fun `URL magnet se pasa como query al motor (no es URL literal sin esquema http)`() {
        // La lógica de `BrowserActivity.go()` no trata los magnet como
        // URLs: al no tener "://" ni ".", cae al caso de búsqueda.
        // (En la práctica los magnet llegan vía ACTION_VIEW, no por la
        //  barra de dirección, así que el comportamiento es correcto.)
        val result = resolve("magnet:?xt=urn:btih:abc") { q ->
            "https://search.com/?q=${q}"
        }
        assertEquals("https://search.com/?q=magnet:?xt=urn:btih:abc", result)
    }

    // ------- Bug 4: extras de long-press (pegar y abrir) -------

    /**
     * Dado un texto del portapapeles, decide si es candidato a URL
     * (mismo criterio que `BrowserActivity.showUrlContextMenu`).
     */
    private fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return t.startsWith("http") || t.contains("://") || t.contains(".")
    }

    @Test
    fun `looksLikeUrl reconoce URLs validas`() {
        assertTrue(looksLikeUrl("https://example.com"))
        assertTrue(looksLikeUrl("http://example.com/path"))
        assertTrue(looksLikeUrl("example.com"))
        assertTrue(looksLikeUrl("ftp://files.example.com"))
    }

    @Test
    fun `looksLikeUrl no reconoce magnet sin http o punto como URL candidata`() {
        // El menú contextual de pulsación larga solo reconoce como URL
        // candidata a "pegar y abrir" lo que empieza por http, contiene
        // "://" o contiene ".". Un magnet simple (sin ".") no cumple y
        // se descarta; en la práctica los magnet llegan por ACTION_VIEW
        // del sistema, no por el portapapeles.
        assertFalse(looksLikeUrl("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun `looksLikeUrl descarta texto plano`() {
        assertFalse(looksLikeUrl("hello world"))
        assertFalse(looksLikeUrl(""))
        assertFalse(looksLikeUrl("   "))
    }

    /**
     * El host limpio (sin www, sin esquema) que muestra el menú contextual
     * de pulsación larga. Replica `MediaProbe.hostOf`.
     */
    private fun hostOf(url: String): String {
        return try {
            val h = URI(url).host ?: ""
            if (h.isEmpty()) url else h
        } catch (e: URISyntaxException) {
            ""
        }
    }

    @Test
    fun `hostOf extrae el host de URLs validas`() {
        assertEquals("example.com", hostOf("https://example.com/path"))
        assertEquals("www.example.com", hostOf("http://www.example.com/x"))
        assertEquals("cdn.example.com", hostOf("https://cdn.example.com/y.js?x=1"))
    }

    @Test
    fun `hostOf devuelve cadena vacia para URLs invalidas`() {
        assertEquals("", hostOf("no es una url"))
        assertEquals("", hostOf("https://"))
    }
}
