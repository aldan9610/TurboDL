package com.turbodl.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests del catálogo de listas de bloqueo (4.2). No usa Context (que
 * requeriría Robolectric/AndroidJUnit) — solo valida la estructura
 * estática del catálogo: que los IDs son únicos, que las URLs están
 * bien formadas, que el default está en el catálogo, y que las
 * entradas tipo HOSTS se marcan como tales.
 */
class AdblockCatalogTest {

    @Test
    fun `los ids son unicos`() {
        val ids = AdblockCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `los ids no estan vacios ni tienen espacios`() {
        AdblockCatalog.ALL.forEach {
            assertTrue("id vacío en $it", it.id.isNotEmpty())
            assertFalse("id con espacio: ${it.id}", it.id.contains(' '))
        }
    }

    @Test
    fun `todas las URLs son http o https y no estan vacias`() {
        AdblockCatalog.ALL.forEach {
            assertTrue("URL vacía en ${it.id}", it.url.isNotEmpty())
            assertTrue("URL no http(s) en ${it.id}: ${it.url}",
                it.url.startsWith("http://") || it.url.startsWith("https://"))
        }
    }

    @Test
    fun `BY_ID cubre todas las entradas`() {
        AdblockCatalog.ALL.forEach {
            assertNotNull("Falta $it.id en BY_ID", AdblockCatalog.BY_ID[it.id])
        }
        assertEquals(AdblockCatalog.ALL.size, AdblockCatalog.BY_ID.size)
    }

    @Test
    fun `DEFAULT_IDS existe en el catálogo`() {
        AdblockCatalog.DEFAULT_IDS.forEach { id ->
            assertNotNull("Default id $id no está en catálogo", AdblockCatalog.BY_ID[id])
        }
    }

    @Test
    fun `default incluye easylist y easyprivacy`() {
        assertTrue("easylist" in AdblockCatalog.DEFAULT_IDS)
        assertTrue("easyprivacy" in AdblockCatalog.DEFAULT_IDS)
    }

    @Test
    fun `al menos una entrada tipo HOSTS existe (StevenBlack)`() {
        val hosts = AdblockCatalog.ALL.filter {
            it.format == AdblockCatalog.Format.HOSTS
        }
        assertTrue("Debe haber al menos una lista tipo hosts (StevenBlack)", hosts.isNotEmpty())
        assertTrue("StevenBlack/hosts debe estar en el catálogo",
            hosts.any { it.id == "stevenblack-hosts" })
    }

    @Test
    fun `AdGuard Base y Tracking estan presentes`() {
        assertNotNull(AdblockCatalog.BY_ID["adguard-base"])
        assertNotNull(AdblockCatalog.BY_ID["adguard-tracking"])
        assertNotNull(AdblockCatalog.BY_ID["adguard-annoyances"])
    }

    @Test
    fun `Fanboy Annoyances esta presente`() {
        assertNotNull(AdblockCatalog.BY_ID["fanboy-annoyances"])
    }

    @Test
    fun `EasyList Spanish regional esta presente`() {
        assertNotNull(AdblockCatalog.BY_ID["easylist-es"])
    }

    @Test
    fun `todas las entradas tienen nombre y desc no vacios`() {
        AdblockCatalog.ALL.forEach {
            assertTrue("Nombre vacío en ${it.id}", it.name.isNotEmpty())
            assertTrue("Desc vacía en ${it.id}", it.desc.isNotEmpty())
        }
    }

    @Test
    fun `las URLs de AdGuard vienen de filters adtidy org`() {
        val ag = AdblockCatalog.BY_ID["adguard-base"]!!
        assertTrue(ag.url.startsWith("https://filters.adtidy.org/"))
    }

    @Test
    fun `la URL de StevenBlack viene de github`() {
        val sb = AdblockCatalog.BY_ID["stevenblack-hosts"]!!
        assertTrue(sb.url.contains("githubusercontent.com"))
    }
}
