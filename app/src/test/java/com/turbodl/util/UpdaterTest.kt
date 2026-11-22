package com.turbodl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comparación de versiones para la auto-actualización de GitHub Releases. */
class UpdaterTest {

    @Test
    fun versionList() {
        assertEquals(listOf(3, 8, 1), Updater.versionList("3.8.1"))
        assertEquals(listOf(3, 10, 1), Updater.versionList("v3.10.1"))
        assertEquals(listOf(4, 0), Updater.versionList("4.0"))
        assertEquals(emptyList<Int>(), Updater.versionList("sin-numeros"))
    }

    @Test
    fun newer() {
        assertTrue(Updater.isNewer("3.8.1", "3.8.0"))
        assertTrue(Updater.isNewer("v3.9.0", "3.8.5"))
        assertTrue(Updater.isNewer("4.0", "3.9.9"))
        assertTrue(Updater.isNewer("3.10.0", "3.9.9")) // 10 > 9, comparación numérica
        assertFalse(Updater.isNewer("3.8.1", "3.8.1")) // igual no actualiza
        assertFalse(Updater.isNewer("3.6.9", "3.7.0"))
        assertFalse(Updater.isNewer("", "3.8.1")) // etiqueta sin dígitos
    }
}
