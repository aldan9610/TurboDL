package com.turbodl.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios de los paneles de accesos directos (4.2.1).
 *
 * El home del navegador muestra 3 categorías (Comunes / IA / Mis sitios)
 * repartidas en uno o varios sub-paneles paginados de 12 accesos (4×3).
 * Estos tests blindan las constantes `SITES_CATEGORY_COUNT`,
 * `SITES_COLUMNS`, `SITES_ROWS`, `SITES_PER_PAGE` y la fórmula del
 * panel activo y del reparto en sub-paneles.
 *
 * No testeamos BrowserActivity directamente (requeriría Activity
 * Instrumented); probamos las constantes del companion object y la
 * fórmula extraída del snap por página completa (4.2.1).
 */
class SitesPanelsTest {

    /** Fórmula exacta del snap de `scheduleSitesSnap`: página destino =
     *  round(scrollX / panelWidth), acotada al rango de sub-paneles. */
    private fun panelFromScroll(scrollX: Int, panelWidth: Int, panelCount: Int): Int {
        val w = panelWidth.coerceAtLeast(1)
        return Math.round(scrollX / w.toFloat()).coerceIn(0, (panelCount - 1).coerceAtLeast(0))
    }

    /** Número de sub-paneles que ocupa una categoría con `n` accesos. */
    private fun subPanelsFor(n: Int): Int =
        if (n <= 0) 1 else (n + BrowserActivity.SITES_PER_PAGE - 1) / BrowserActivity.SITES_PER_PAGE

    @Test
    fun `SITES_CATEGORY_COUNT es 3 categorias`() {
        assertEquals(3, BrowserActivity.SITES_CATEGORY_COUNT)
    }

    @Test
    fun `SITES_COLUMNS es 4 columnas por sub-panel`() {
        assertEquals(4, BrowserActivity.SITES_COLUMNS)
    }

    @Test
    fun `SITES_ROWS es 3 filas por sub-panel`() {
        assertEquals(3, BrowserActivity.SITES_ROWS)
    }

    @Test
    fun `SITES_PER_PAGE es 12 (4 columnas x 3 filas)`() {
        assertEquals(12, BrowserActivity.SITES_PER_PAGE)
        assertEquals(
            BrowserActivity.SITES_COLUMNS * BrowserActivity.SITES_ROWS,
            BrowserActivity.SITES_PER_PAGE
        )
    }

    @Test
    fun `scroll 0 corresponde al sub-panel 0`() {
        assertEquals(0, panelFromScroll(0, 1080, 4))
    }

    @Test
    fun `scroll igual al ancho corresponde al sub-panel 1`() {
        assertEquals(1, panelFromScroll(1080, 1080, 4))
    }

    @Test
    fun `scroll doble corresponde al sub-panel 2`() {
        assertEquals(2, panelFromScroll(2160, 1080, 4))
    }

    @Test
    fun `scroll mayor que el total se acota al ultimo sub-panel`() {
        assertEquals(3, panelFromScroll(99999, 1080, 4))
    }

    @Test
    fun `scroll negativo se acota al sub-panel 0`() {
        assertEquals(0, panelFromScroll(-200, 1080, 4))
    }

    @Test
    fun `panelWidth 0 no divide por cero (coerce a 1)`() {
        assertEquals(0, panelFromScroll(0, 0, 4))
    }

    @Test
    fun `12 accesos = 1 sub-panel (sin desbordamiento)`() {
        assertEquals(1, subPanelsFor(12))
    }

    @Test
    fun `13 accesos = 2 sub-paneles (reparto automatico 4_2_1)`() {
        // Lo que pediste: si no cabe en un panel, se añade otro panel en
        // lugar de permitir scroll vertical interno.
        assertEquals(2, subPanelsFor(13))
        assertEquals(2, subPanelsFor(24))
        assertEquals(3, subPanelsFor(25))
        assertEquals(3, subPanelsFor(36))
        assertEquals(4, subPanelsFor(37))
    }

    @Test
    fun `0 accesos = 1 sub-panel (panel vacio visible)`() {
        assertEquals(1, subPanelsFor(0))
    }

    @Test
    fun `swipe entre sub-paneles en pasos pequenos no salta de mas`() {
        for (sx in 0..539) {
            assertEquals(0, panelFromScroll(sx, 1080, 4))
        }
        for (sx in 540..1619) {
            assertEquals(1, panelFromScroll(sx, 1080, 4))
        }
        for (sx in 1620..2699) {
            assertEquals(2, panelFromScroll(sx, 1080, 4))
        }
    }

    @Test
    fun `MENU_PER_PAGE es 10 celdas (snap del menu 4_2_2)`() {
        // 4.2.2: el menú ya no tiene un nº fijo de páginas; las celdas se
        // reparten dinámicamente en páginas de MENU_PER_PAGE = 10.
        assertEquals(5, BrowserActivity.MENU_COLUMNS)
        assertEquals(2, BrowserActivity.MENU_ROWS)
        assertEquals(10, BrowserActivity.MENU_PER_PAGE)
        assertEquals(
            BrowserActivity.MENU_COLUMNS * BrowserActivity.MENU_ROWS,
            BrowserActivity.MENU_PER_PAGE
        )
    }
}
