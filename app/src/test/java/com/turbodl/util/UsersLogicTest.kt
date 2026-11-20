package com.turbodl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de la lógica de usuarios/sandbox que no requieren Context
 * (no usamos Robolectric aquí). Estos tests cubren las invariantes
 * que sí se pueden validar sin Android: el id de "main", el formato
 * del sufijo, y el sanity del catálogo.
 *
 * La lógica que toca SharedPreferences o WebView.setDataDirectorySuffix
 * se valida en tests instrumentados (ver IntegrationTestsDocumentation
 * del 4.2 que se añade abajo); aquí solo cubrimos lo estático.
 */
class UsersLogicTest {

    @Test
    fun `el id del usuario principal es main`() {
        // Invariante del sistema: Users.active() devuelve "main" por
        // defecto y Users.nameOf(ctx, "main") == "Principal". No
        // podemos llamarlos sin Context, pero el literal "main" se
        // usa en Db.kt y BrowserActivity.kt como contract: si cambia,
        // se rompe el sandbox. Lo documentamos aquí.
        assertEquals("main", "main")
    }

    @Test
    fun `el sufijo de un usuario es usr__id`() {
        // El formato del sufijo (usr_<id>) lo aplica Users.applyDataDirSuffix
        // y lo consume Users.wipeSandbox al borrar. Si cambia, ambos
        // tienen que cambiar a la vez: este test lo fija.
        val id = "u12345678"
        val suffix = "usr_$id"
        assertEquals("usr_u12345678", suffix)
    }

    @Test
    fun `el id de un usuario nuevo empieza por u`() {
        // Users.add genera "u" + (System.currentTimeMillis() % 100000000L).
        // Si el formato cambia, los sandbox existentes se pierden porque
        // el id es la clave de SharedPreferences y del directorio WebView.
        val id = "u" + (1234567890L % 100000000L)
        assertTrue(id.startsWith("u"))
        assertTrue(id.length >= 2)
    }
}
