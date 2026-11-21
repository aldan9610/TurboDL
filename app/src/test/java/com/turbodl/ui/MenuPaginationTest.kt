package com.turbodl.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios de la paginación del menú inferior (4.2.2).
 *
 * El menú inferior reparte sus celdas en sub-páginas dinámicas de
 * `MENU_COLUMNS × MENU_ROWS` = 10 huecos. El número total de páginas es
 * `ceil(totalCeldas / MENU_PER_PAGE)`. Estos tests blindan las constantes
 * y la fórmula de página activa y de nº de páginas, para que un cambio
 * accidental no vuelva a dejar una página con 11 celdas (crash de la
 * 4.2.1: `IllegalArgumentException: row indices (start + span) mustn't
 * exceed the row count`).
 */
class MenuPaginationTest {

    /** Fórmula de página activa del menú (`round(scrollX / pageWidth)`). */
    private fun pageFromScroll(scrollX: Int, pageWidth: Int, pageCount: Int): Int {
        val w = pageWidth.coerceAtLeast(1)
        return Math.round(scrollX / w.toFloat()).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    }

    /** Nº de páginas del menú a partir del nº total de celdas. */
    private fun pagesFor(nCells: Int): Int =
        if (nCells <= 0) 1
        else (nCells + BrowserActivity.MENU_PER_PAGE - 1) / BrowserActivity.MENU_PER_PAGE

    @Test
    fun `MENU_COLUMNS es 5 y MENU_ROWS es 2`() {
        assertEquals(5, BrowserActivity.MENU_COLUMNS)
        assertEquals(2, BrowserActivity.MENU_ROWS)
    }

    @Test
    fun `MENU_PER_PAGE es 10 (5 columnas x 2 filas)`() {
        assertEquals(10, BrowserActivity.MENU_PER_PAGE)
        assertEquals(
            BrowserActivity.MENU_COLUMNS * BrowserActivity.MENU_ROWS,
            BrowserActivity.MENU_PER_PAGE
        )
    }

    @Test
    fun `10 celdas = 1 pagina (sin crash)`() {
        assertEquals(1, pagesFor(10))
    }

    @Test
    fun `11 celdas = 2 paginas (regresion del crash de la 4_2_1)`() {
        // En 4.2.1 la página 2 tenía 11 celdas y rompía el GridLayout.
        // Ahora, con 11 celdas se crea automáticamente una 2ª página de
        // 1 celda — nunca se excede el rowCount.
        assertEquals(2, pagesFor(11))
        assertEquals(2, pagesFor(20))
        assertEquals(3, pagesFor(21))
        assertEquals(3, pagesFor(30))
        assertEquals(4, pagesFor(31))
    }

    @Test
    fun `scroll 0 corresponde a pagina 0`() {
        val total = pagesFor(31)   // 4 páginas
        assertEquals(0, pageFromScroll(0, 1080, total))
    }

    @Test
    fun `scroll igual al ancho corresponde a pagina 1`() {
        val total = pagesFor(31)
        assertEquals(1, pageFromScroll(1080, 1080, total))
    }

    @Test
    fun `scroll igual al doble de ancho corresponde a pagina 2`() {
        val total = pagesFor(31)
        assertEquals(2, pageFromScroll(2160, 1080, total))
    }

    @Test
    fun `scroll mayor que el total se acota a la ultima pagina`() {
        val total = pagesFor(31)
        assertEquals(total - 1, pageFromScroll(99999, 1080, total))
    }

    @Test
    fun `scroll negativo se acota a pagina 0`() {
        val total = pagesFor(31)
        assertEquals(0, pageFromScroll(-200, 1080, total))
    }

    @Test
    fun `pageWidth 0 no divide por cero (coerce a 1)`() {
        val total = pagesFor(31)
        assertEquals(0, pageFromScroll(0, 0, total))
    }

    @Test
    fun `todas las paginas son alcanzables con 4 paginas`() {
        val total = pagesFor(31)   // 4 páginas
        assertTrue(total == 4)
        val reached = HashSet<Int>()
        for (sx in 0..(4 * 1080) step 100) {
            reached.add(pageFromScroll(sx, 1080, total))
        }
        assertEquals(total, reached.size)
        assertTrue("debe alcanzar la página 0", reached.contains(0))
        assertTrue("debe alcanzar la página 3", reached.contains(3))
    }
}
