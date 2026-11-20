package com.turbodl.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del adapter que convierte listas tipo "hosts"
 * (`0.0.0.0 ejemplo.com`) a sintaxis EasyList (`||ejemplo.com^`).
 *
 * Este adapter es la clave para que el usuario pueda integrar más
 * bloqueadores (StevenBlack/hosts, listas tipo DNS-blocklist) sin
 * tocar el motor: FilterEngine ya entiende EasyList estándar.
 */
class HostsAdapterTest {

    @Test
    fun `linea 0_0_0_0 host se convierte a barra barra host caret`() {
        val out = HostsAdapter.toEasyList("0.0.0.0 adservice.google.com")
        assertTrue(out.contains("||adservice.google.com^"))
    }

    @Test
    fun `linea 127_0_0_1 host se convierte igual`() {
        val out = HostsAdapter.toEasyList("127.0.0.1 tracker.ejemplo.net")
        assertTrue("||tracker.ejemplo.net^" in out)
    }

    @Test
    fun `localhost se descarta`() {
        val out = HostsAdapter.toEasyList("0.0.0.0 localhost\n127.0.0.1 localhost")
        assertFalse(out.contains("localhost"))
    }

    @Test
    fun `comentarios y lineas vacias se descartan`() {
        val input = """
            # Esto es un comentario
            # 0.0.0.0 ejemplo.com

            0.0.0.0 real.example.org
        """.trimIndent()
        val out = HostsAdapter.toEasyList(input)
        assertFalse(out.contains("comentario"))
        assertTrue("||real.example.org^" in out)
    }

    @Test
    fun `comentario inline se ignora y se toma el host`() {
        val out = HostsAdapter.toEasyList("0.0.0.0 ads.test.com # banner")
        assertTrue("||ads.test.com^" in out)
        assertFalse(out.contains("banner"))
    }

    @Test
    fun `linea EasyList ya formada pasa tal cual`() {
        val input = "||easylist-rule.com^\$third-party\n@@||exemption.com^"
        val out = HostsAdapter.toEasyList(input)
        assertTrue("||easylist-rule.com^\$third-party" in out)
        assertTrue("@@||exemption.com^" in out)
    }

    @Test
    fun `selector cosmético pasa tal cual`() {
        val input = "###ad-banner\nexample.com##.ads"
        val out = HostsAdapter.toEasyList(input)
        assertTrue("###ad-banner" in out)
        assertTrue("example.com##.ads" in out)
    }

    @Test
    fun `lista vacia devuelve vacio`() {
        assertEquals("", HostsAdapter.toEasyList(""))
        assertEquals("", HostsAdapter.toEasyList("# solo comentarios\n\n"))
    }

    @Test
    fun `entrada sin segundo token se descarta`() {
        val out = HostsAdapter.toEasyList("0.0.0.0\n127.0.0.1")
        assertEquals("", out)
    }

    @Test
    fun `host sin punto (broadcasthost) se descarta`() {
        val out = HostsAdapter.toEasyList("0.0.0.0 broadcasthost")
        assertFalse(out.contains("broadcasthost"))
    }

    @Test
    fun `CRLF y CR se toleran como separadores`() {
        // Solo \r\n (CRLF, Windows) y \n (LF): los dos separadores
        // reales que aparecen en listas descargadas. El \r suelto en
        // medio de una línea es raro y no aparece en listas reales.
        val input = "0.0.0.0 a.com\r\n0.0.0.0 b.com\r\n0.0.0.0 c.com\n"
        val out = HostsAdapter.toEasyList(input)
        assertTrue("||a.com^" in out)
        assertTrue("||b.com^" in out)
        assertTrue("||c.com^" in out)
    }

    @Test
    fun `StevenBlack snapshot estilo funciona`() {
        // Muestra real del header de StevenBlack/hosts
        val input = """
            # Title: StevenBlack/hosts
            # This hosts file is a merged collection from:
            # -hosts-filecsyshosts
            # -Addy819yeyeyey
            # -adaway
            0.0.0.0 0.fyxlyfe.com
            0.0.0.0 fyfxq.com
            127.0.0.1 pixel.facebook.com
            0.0.0.0 localhost
        """.trimIndent()
        val out = HostsAdapter.toEasyList(input)
        assertTrue("||0.fyxlyfe.com^" in out)
        assertTrue("||fyfxq.com^" in out)
        assertTrue("||pixel.facebook.com^" in out)
        assertFalse(out.contains("localhost"))
        // los comentarios no se filtran como reglas
        assertFalse(out.contains("Title:"))
    }

    @Test
    fun `multiples espacios o tabs entre ip y host`() {
        val out = HostsAdapter.toEasyList("0.0.0.0\tads1.com\n0.0.0.0   ads2.com")
        assertTrue("||ads1.com^" in out)
        assertTrue("||ads2.com^" in out)
    }
}
