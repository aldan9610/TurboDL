package com.turbodl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios de la utilidad de normalización de URLs snifeadas.
 *
 * Cubre el comportamiento de [Kit.normalizeSniffUrl], que limpia las URLs
 * detectadas por el WebView antes de meterlas en la lista de medios
 * descargables. La utilidad deduplica segmentos de googlevideo eliminando
 * los parámetros de rango (`range`, `rn`, `rbuf`) para que todos los
 * fragmentos del mismo stream den la misma URL.
 */
class NormalizeSniffUrlTest {

    @Test
    fun `URL sin videoplayback pasa sin cambios`() {
        val original = "https://example.com/video.mp4"
        val normalizada = Kit.normalizeSniffUrl(original)
        assertEquals("una URL que no es de googlevideo no debe modificarse",
            original, normalizada)
    }

    @Test
    fun `URL con query sin parametros de rango pasa igual`() {
        val original = "https://example.com/v.mp4?t=123&sig=abc"
        assertEquals(original, Kit.normalizeSniffUrl(original))
    }

    @Test
    fun `URL con fragmento conserva el fragmento`() {
        val original = "https://example.com/page#sec3"
        assertEquals(original, Kit.normalizeSniffUrl(original))
    }

    @Test
    fun `URL videoplayback conserva parametros utiles`() {
        val original = "https://rr.googlevideo.com/videoplayback?itags=140&mime=audio/mp4"
        val normalizada = Kit.normalizeSniffUrl(original)
        // No se modifica: no hay parámetros de rango que quitar.
        assertEquals(original, normalizada)
    }

    @Test
    fun `URL videoplayback quita range rn rbuf para deduplicar segmentos`() {
        val original = "https://rr.googlevideo.com/videoplayback?itags=140&range=0-1000&rn=2&rbuf=300&mime=audio/mp4"
        val normalizada = Kit.normalizeSniffUrl(original)
        // Deben desaparecer los parámetros de rango; los demás se conservan.
        assertFalse("range= no debe aparecer tras normalizar: $normalizada",
            normalizada.contains("range="))
        assertFalse("rn= no debe aparecer tras normalizar: $normalizada",
            normalizada.contains("rn="))
        assertFalse("rbuf= no debe aparecer tras normalizar: $normalizada",
            normalizada.contains("rbuf="))
        assertTrue("itags= debe conservarse: $normalizada",
            normalizada.contains("itags=140"))
        assertTrue("mime= debe conservarse: $normalizada",
            normalizada.contains("mime=audio/mp4"))
    }

    @Test
    fun `URLs del mismo stream con rangos distintos dan la misma normalizada`() {
        val seg1 = "https://rr.googlevideo.com/videoplayback?itags=140&range=0-1000&rn=1&mime=audio/mp4"
        val seg2 = "https://rr.googlevideo.com/videoplayback?itags=140&range=1000-2000&rn=2&mime=audio/mp4"
        assertEquals("dos segmentos del mismo stream deben normalizarse igual",
            Kit.normalizeSniffUrl(seg1), Kit.normalizeSniffUrl(seg2))
    }
}
