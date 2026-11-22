package com.turbodl.db

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests JVM del CRUD de NotesDb (4.3). Valida las cuatro operaciones
 * fundamentales (Create, Read, Update, Delete) y la búsqueda por
 * título/cuerpo, ejecutándose contra SQLite real vía Robolectric (sin
 * dispositivo físico).
 *
 * El bug de la 4.2.3 que este test previene: el GestureDetector del swipe
 * del menú bloqueaba los clics en las celdas → el usuario no podía abrir
 * Notas. La UI dependía de NotesDb, pero NotesDb en sí nunca dejó de
 * funcionar; estos tests fijan el contract del CRUD para que futuros
 * cambios no rompan las notas "sin que nadie se dé cuenta".
 */
@RunWith(RobolectricTestRunner::class)
class NotesDbTest {

    private lateinit var db: NotesDb

    @Before
    fun setUp() {
        // NotesDb es un singleton cacheado en `inst`. Como los tests
        // comparten proceso, en vez de recrear la instancia (que el
        // singleton no permite), limpiamos la tabla `notes` antes de
        // cada test para garantizar aislamiento.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = NotesDb.get(ctx)
        // Limpieza directa: trunca la tabla de notas para que cada test
        // empiece desde cero (NotesDb es singleton, no se puede recrear).
        db.clearAllNotes()
    }

    @Test
    fun `create añade una nota y read la recupera por id`() {
        val id = db.addNote("Título 1", "Cuerpo de la nota", "https://ejemplo.com")
        assertTrue("addNote debe devolver un id > 0", id > 0)

        val n = db.getNote(id)
        assertNotNull("getNote debe encontrar la nota recién creada", n)
        assertEquals("Título 1", n!!.title)
        assertEquals("Cuerpo de la nota", n.body)
        assertEquals("https://ejemplo.com", n.url)
        assertTrue("time debe ser > 0", n.time > 0)
    }

    @Test
    fun `read lista las notas ordenadas por tiempo descendente`() {
        val a = db.addNote("A", "aa", "")
        Thread.sleep(5)   // distinto timestamp
        val b = db.addNote("B", "bb", "")

        val all = db.notes()
        assertEquals(2, all.size)
        // La más reciente (B) va primero.
        assertEquals(b, all[0].id)
        assertEquals("B", all[0].title)
        assertEquals(a, all[1].id)
    }

    @Test
    fun `update modifica el título y el cuerpo y refresca el timestamp`() {
        val id = db.addNote("Viejo", "viejo cuerpo", "https://a.com")
        val antes = db.getNote(id)!!
        Thread.sleep(5)
        db.updateNote(id, "Nuevo", "nuevo cuerpo")

        val despues = db.getNote(id)
        assertNotNull(despues)
        assertEquals("Nuevo", despues!!.title)
        assertEquals("nuevo cuerpo", despues.body)
        // url NO cambia al actualizar (solo título y cuerpo).
        assertEquals("https://a.com", despues.url)
        assertTrue("el timestamp debe avanzar", despues.time >= antes.time)
    }

    @Test
    fun `delete elimina la nota y getNote devuelve null`() {
        val id = db.addNote("Para borrar", "x", "")
        assertNotNull(db.getNote(id))

        db.deleteNote(id)
        assertNull("getNote debe devolver null tras delete", db.getNote(id))
        assertEquals(0, db.notes().size)
    }

    @Test
    fun `search filtra por titulo o cuerpo en tiempo real`() {
        db.addNote("Receta flan", "huevos y leche", "")
        db.addNote("Lista compra", "pan y flan", "")
        db.addNote("Trabajo", "reunión a las 10", "")

        // Coincidencia por título.
        assertEquals(1, db.notes("Receta").size)
        // Coincidencia por cuerpo (dos notas contienen "flan").
        assertEquals(2, db.notes("flan").size)
        // Sin coincidencias.
        assertEquals(0, db.notes("zzz").size)
        // Query vacía devuelve todo.
        assertEquals(3, db.notes("").size)
    }

    @Test
    fun `preview muestra el titulo y el inicio del cuerpo`() {
        val n = NotesDb.Note(1L, "Hola", "Esto es el cuerpo largo", "", 0L)
        val p = n.preview()
        assertTrue("preview debe empezar por el título", p.startsWith("Hola"))
        assertTrue("preview debe contener parte del cuerpo", p.contains("Esto es el cuerpo"))
    }

    @Test
    fun `nota sin titulo muestra sin titulo en el preview`() {
        val n = NotesDb.Note(1L, "", "cuerpo", "", 0L)
        assertTrue(n.preview().startsWith("(sin título)"))
    }
}

