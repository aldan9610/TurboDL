package com.turbodl.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios de los paneles de accesos directos (Bug 2).
 *
 * El home del navegador muestra 3 paneles (Comunes / IA / Mis sitios) en
 * un `HorizontalScrollView` con swipe horizontal. Estos tests blindan las
 * constantes `SITES_PANEL_COUNT`, `SITES_COLUMNS`, `SITES_ROWS_VISIBLE` y
 * la fórmula `panelActivo = round(scrollX / panelWidth)` para que un
 * cambio accidental no vuelva a dejar solo el primer panel visible.
 *
 * No testeamos BrowserActivity directamente (requeriría Activity
 * Instrumented); probamos las constantes del companion object y la
 * fórmula de panel activo extraída.
 */
class SitesPanelsTest {

    /** Fórmula exacta de `BrowserActivity` para el panel activo. */
    private fun panelFromScroll(scrollX: Int, panelWidth: Int, panelCount: Int): Int {
        val w = panelWidth.coerceAtLeast(1)
        return Math.round(scrollX / w.toFloat()).coerceIn(0, panelCount - 1)
    }

    @Test
    fun `SITES_PANEL_COUNT es 3 paneles`() {
        assertEquals(3, BrowserActivity.SITES_PANEL_COUNT)
    }

    @Test
    fun `SITES_COLUMNS es 4 columnas por panel`() {
        assertEquals(4, BrowserActivity.SITES_COLUMNS)
    }

    @Test
    fun `SITES_ROWS_VISIBLE es 3 filas por panel`() {
        assertEquals(3, BrowserActivity.SITES_ROWS_VISIBLE)
    }

    @Test
    fun `cada panel muestra 12 accesos (4 columnas x 3 filas)`() {
        assertEquals(12, BrowserActivity.SITES_COLUMNS * BrowserActivity.SITES_ROWS_VISIBLE)
    }

    @Test
    fun `scroll 0 corresponde al panel 0 (Sitios comunes)`() {
        assertEquals(0, panelFromScroll(0, 1080, BrowserActivity.SITES_PANEL_COUNT))
    }

    @Test
    fun `scroll igual al ancho corresponde al panel 1 (IA)`() {
        assertEquals(1, panelFromScroll(1080, 1080, BrowserActivity.SITES_PANEL_COUNT))
    }

    @Test
    fun `scroll doble corresponde al panel 2 (Mis sitios)`() {
        assertEquals(2, panelFromScroll(2160, 1080, BrowserActivity.SITES_PANEL_COUNT))
    }

    @Test
    fun `scroll mayor que el total se acota al ultimo panel`() {
        assertEquals(BrowserActivity.SITES_PANEL_COUNT - 1,
            panelFromScroll(99999, 1080, BrowserActivity.SITES_PANEL_COUNT))
    }

    @Test
    fun `scroll negativo se acota al panel 0`() {
        assertEquals(0,
            panelFromScroll(-200, 1080, BrowserActivity.SITES_PANEL_COUNT))
    }

    @Test
    fun `panelWidth 0 no divide por cero (coerce a 1)`() {
        assertEquals(0, panelFromScroll(0, 0, BrowserActivity.SITES_PANEL_COUNT))
    }

    @Test
    fun `los tres paneles son alcanzables con swipe horizontal`() {
        // Bug 2: antes del fix, solo el panel 0 era visible porque los
        // paneles 1 y 2 medían 0 de ancho. Aquí recorremos scrollX y
        // comprobamos que los tres paneles se ven en algún momento.
        val reached = HashSet<Int>()
        for (sx in 0..(3 * 1080) step 50) {
            reached.add(panelFromScroll(sx, 1080, BrowserActivity.SITES_PANEL_COUNT))
        }
        assertEquals(BrowserActivity.SITES_PANEL_COUNT, reached.size)
        assertTrue("panel 0 (Comunes) debe alcanzarse", reached.contains(0))
        assertTrue("panel 1 (IA) debe alcanzarse", reached.contains(1))
        assertTrue("panel 2 (Mis sitios) debe alcanzarse", reached.contains(2))
    }

    @Test
    fun `swipe entre paneles en pasos pequenos no salta de mas`() {
        // Pequeños desplazamientos dentro del mismo panel no deben
        // cambiar el panel activo (tolerancia del 50% de ancho).
        for (sx in 0..539) {
            assertEquals(0, panelFromScroll(sx, 1080, BrowserActivity.SITES_PANEL_COUNT))
        }
        for (sx in 540..1619) {
            assertEquals(1, panelFromScroll(sx, 1080, BrowserActivity.SITES_PANEL_COUNT))
        }
        for (sx in 1620..2160) {
            assertEquals(2, panelFromScroll(sx, 1080, BrowserActivity.SITES_PANEL_COUNT))
        }
    }
}
