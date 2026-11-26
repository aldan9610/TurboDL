package com.turbodl.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests del contrato de cambio de usuario (4.15, bug reportado: "cambiar
 * usuario dentro del navegador no funciona — la app se reinicia dos
 * veces y el usuario activo sigue siendo el mismo").
 *
 * Contrato que fijan estos tests (lo que fallaba en 4.14):
 *  - setActive escribe en disco DE FORMA SÍNCRONA: cuando devuelve, el
 *    valor ya está persistido — un reinicio inmediato del proceso
 *    (Users.restartApp hace System.exit ~ms después) no puede perderlo.
 *    Antes usaba apply() (hilo de fondo) y el proceso moría antes de
 *    que el hilo escribiera: al arrancar seguía el usuario anterior.
 *    Se verifica releyendo desde una instancia NUEVA de los
 *    SharedPreferences (equivale al siguiente arranque del proceso).
 *  - remove() del usuario ACTIVO reactiva "main" y también de forma
 *    síncrona (ese camino también llama a restartApp).
 *  - add/list/update/displayOf redondos (id, nombre, email).
 */
@RunWith(RobolectricTestRunner::class)
class UsersSandboxTest {

    private lateinit var ctx: android.content.Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // estado limpio por test
        ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    /** Instancia "nueva" de los prefs: lo que leerá el siguiente
     *  proceso tras el System.exit de restartApp. */
    private fun prefsReleidas(): android.content.SharedPreferences =
        ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)

    @Test
    fun `activo por defecto es main`() {
        assertEquals("main", Users.active(ctx))
    }

    @Test
    fun `setActive persiste de inmediato y sobrevive al releido`() {
        val u = Users.add(ctx, "Trabajo", "al2dan9610@gmail.com")
        Users.setActive(ctx, u.id)
        // SÍNCRONO: al devolver setActive, el valor ya está en disco.
        // El "siguiente proceso" releería exactamente esto.
        assertEquals(u.id, prefsReleidas().getString("activeUser", "main"))
        assertEquals(u.id, Users.active(ctx))
    }

    @Test
    fun `setActive dos veces seguidas deja el ultimo valor`() {
        val a = Users.add(ctx, "Personal")
        val b = Users.add(ctx, "Trabajo", "jefe@empresa.com")
        Users.setActive(ctx, a.id)
        Users.setActive(ctx, b.id)
        assertEquals(b.id, prefsReleidas().getString("activeUser", "main"))
        assertEquals(b.id, Users.active(ctx))
    }

    @Test
    fun `remove del usuario activo reactiva main de inmediato`() {
        val u = Users.add(ctx, "Borrar", "chao@correo.com")
        Users.setActive(ctx, u.id)
        Users.remove(ctx, u.id)
        // remove() resetea el activo a "main" y ese camino también
        // reinicia la app: debe quedar en disco ya.
        assertEquals("main", prefsReleidas().getString("activeUser", "main"))
        assertEquals("main", Users.active(ctx))
        // y desaparece del catálogo
        assertTrue(Users.list(ctx).none { it.id == u.id })
    }

    @Test
    fun `add y list conservan nombre y email`() {
        val u = Users.add(ctx, "Trabajo", "al2dan9610@gmail.com")
        val l = Users.list(ctx)
        assertEquals(1, l.size)
        assertEquals(u.id, l[0].id)
        assertEquals("Trabajo", l[0].name)
        assertEquals("al2dan9610@gmail.com", l[0].email)
    }

    @Test
    fun `update conserva el campo que no se cambia`() {
        val u = Users.add(ctx, "Personal", "yo@correo.com")
        Users.update(ctx, u.id, name = "Casa")
        val l = Users.list(ctx)
        assertEquals("Casa", l[0].name)
        assertEquals("yo@correo.com", l[0].email)
    }

    @Test
    fun `displayOf prefiere email y luego nombre`() {
        val conEmail = Users.add(ctx, "Trabajo", "jefe@empresa.com")
        val sinEmail = Users.add(ctx, "Casa")
        assertEquals("jefe@empresa.com", Users.displayOf(ctx, conEmail.id))
        assertEquals("Casa", Users.displayOf(ctx, sinEmail.id))
        assertEquals("Principal", Users.displayOf(ctx, "main"))
    }

    @Test
    fun `users list se persiste de inmediato`() {
        // save() también debe ser durable: Users.remove lo usa y luego
        // reinicia el proceso.
        val a = Users.add(ctx, "A")
        val b = Users.add(ctx, "B")
        Users.save(ctx, listOf(Users.U(a.id, "A"), Users.U(b.id, "B")))
        val releido = prefsReleidas().getString("users", "[]")
        assertTrue(releido!!.contains("\"" + a.id + "\""))
        assertTrue(releido.contains("\"" + b.id + "\""))
    }
}
