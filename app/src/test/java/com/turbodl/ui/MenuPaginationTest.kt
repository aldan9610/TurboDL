package com.turbodl.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios de la paginación del menú inferior (Bug 1).
 *
 * El menú inferior se construye con `MENU_PAGE_COUNT` páginas (submenús),
 * cada una con `MENU_COLUMNS × MENU_ROWS` celdas. Estos tests blindan las
 * constantes y la lógica de cálculo de página a partir del scroll horizontal,
 * para que un cambio accidental (p. ej. `MENU_PAGE_COUNT = 2`) no rompa
 * la navegación entre submenús.
 *
 * No accedemos a `BrowserActivity` directamente (requiere Activity
 * Instrumented); probamos las constantes del `companion object` y la
 * fórmula de página activa = `round(scrollX / pageWidth)`, acotada a
 * `[0, MENU_PAGE_COUNT - 1]`.
 */
class MenuPaginationTest {

    /** Fórmula exacta de `BrowserActivity` para la página activa del menú. */
    private fun pageFromScroll(scrollX: Int, pageWidth: Int, pageCount: Int): Int {
        val w = pageWidth.coerceAtLeast(1)
        return Math.round(scrollX / w.toFloat()).coerceIn(0, pageCount - 1)
    }

    @Test
    fun `MENU_PAGE_COUNT es 3 paginas`() {
        assertEquals(3, BrowserActivity.MENU_PAGE_COUNT)
    }

    @Test
    fun `MENU_COLUMNS es 5 y MENU_ROWS es 2`() {
        assertEquals(5, BrowserActivity.MENU_COLUMNS)
        assertEquals(2, BrowserActivity.MENU_ROWS)
    }

    @Test
    fun `cada pagina tiene 10 celdas (5 columnas x 2 filas)`() {
        assertEquals(10, BrowserActivity.MENU_COLUMNS * BrowserActivity.MENU_ROWS)
    }

    @Test
    fun `scroll 0 corresponde a pagina 0`() {
        val page = pageFromScroll(0, 1080, BrowserActivity.MENU_PAGE_COUNT)
        assertEquals(0, page)
    }

    @Test
    fun `scroll igual al ancho corresponde a pagina 1`() {
        val page = pageFromScroll(1080, 1080, BrowserActivity.MENU_PAGE_COUNT)
        assertEquals(1, page)
    }

    @Test
    fun `scroll igual al doble de ancho corresponde a pagina 2`() {
        val page = pageFromScroll(2160, 1080, BrowserActivity.MENU_PAGE_COUNT)
        assertEquals(2, page)
    }

    @Test
    fun `scroll mayor que el total se acota a la ultima pagina`() {
        val page = pageFromScroll(99999, 1080, BrowserActivity.MENU_PAGE_COUNT)
        assertEquals(BrowserActivity.MENU_PAGE_COUNT - 1, page)
    }

    @Test
    fun `scroll negativo se acota a pagina 0`() {
        val page = pageFromScroll(-200, 1080, BrowserActivity.MENU_PAGE_COUNT)
        assertEquals(0, page)
    }

    @Test
    fun `pageWidth 0 no divide por cero (coerce a 1)`() {
        val page = pageFromScroll(0, 0, BrowserActivity.MENU_PAGE_COUNT)
        assertEquals(0, page)
    }

    @Test
    fun `todas las paginas son alcanzables`() {
        // Recorre cada punto de scroll y comprueba que las 3 páginas se
        // ven en algún momento (Bug 1: antes solo la página 0 era visible).
        val reached = HashSet<Int>()
        for (sx in 0..(3 * 1080) step 100) {
            reached.add(pageFromScroll(sx, 1080, BrowserActivity.MENU_PAGE_COUNT))
        }
        assertEquals(BrowserActivity.MENU_PAGE_COUNT, reached.size)
        assertTrue("debe alcanzar la página 0", reached.contains(0))
        assertTrue("debe alcanzar la página 1", reached.contains(1))
        assertTrue("debe alcanzar la página 2", reached.contains(2))
    }
}
