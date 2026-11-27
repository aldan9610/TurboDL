package com.turbodl.db

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests JVM del módulo de notas avanzado (4.17): papelera, fijadas,
 * color, checklist (JSON de elementos) y conteos para Herramientas.
 * Ejecutan contra SQLite real vía Robolectric. Complementan a
 * NotesDbTest (CRUD básico 4.3), que comparte el mismo singleton.
 */
@RunWith(RobolectricTestRunner::class)
class NotesDbAdvancedTest {

    private lateinit var db: NotesDb

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = NotesDb.get(ctx)
        db.clearAllNotes()
    }

    // -------------------- papelera (borrado suave) --------------------

    @Test
    fun `softDelete mueve a papelera y restore la recupera`() {
        val id = db.addNote("Nota a borrar", "cuerpo", "")
        assertEquals(1, db.visible().size)
        assertTrue(db.trashed().isEmpty())

        db.softDelete(id)
        assertTrue("tras softDelete no debe estar en visibles", db.visible().isEmpty())
        assertEquals(1, db.trashed().size)
        assertEquals("0 visibles, 1 en papelera", 0 to 1, db.counts())

        db.restore(id)
        assertEquals(1, db.visible().size)
        assertTrue(db.trashed().isEmpty())
    }

    @Test
    fun `deleteNote y emptyTrash borran definitivamente`() {
        val a = db.addNote("A", "", "")
        val b = db.addNote("B", "", "")
        db.softDelete(a)
        db.softDelete(b)
        assertEquals(0 to 2, db.counts())

        db.emptyTrash()
        assertEquals(0 to 0, db.counts())
        assertTrue(db.trashed().isEmpty())
        assertNull("borrado definitivo: getNote debe devolver null", db.getNote(a))
        assertNull(db.getNote(b))
        // deleteNote individual (usado por el menú de la papelera)
        val c = db.addNote("C", "", "")
        db.softDelete(c)
        db.deleteNote(c)
        assertTrue(db.trashed().isEmpty())
    }

    @Test
    fun `softDelete desfija la nota`() {
        val id = db.addNoteFull("Fijada", "", color = 2, kind = NotesDb.KIND_TEXT, pinned = true)
        db.softDelete(id)
        val t = db.trashed().first()
        assertFalse("en la papelera nada sale fijado", t.pinned)
    }

    // -------------------- fijadas --------------------

    @Test
    fun `las notas fijadas salen primero`() {
        val v1 = db.addNote("antigua", "", "")
        Thread.sleep(5)
        val v2 = db.addNote("reciente", "", "")
        db.setPinned(v1, true)
        val list = db.visible()
        assertEquals("la fijada va primero aunque sea más antigua", v1, list[0].id)
        assertEquals(v2, list[1].id)
    }

    @Test
    fun `setPinned persiste el estado`() {
        val id = db.addNote("N", "", "")
        assertFalse(db.getNote(id)!!.pinned)
        db.setPinned(id, true)
        assertTrue(db.getNote(id)!!.pinned)
    }

    // -------------------- color y tipo --------------------

    @Test
    fun `addNoteFull guarda color kind y created`() {
        val id = db.addNoteFull("Lista", "", url = "", color = 3,
            kind = NotesDb.KIND_CHECK, pinned = false,
            items = NotesDb.itemsToJson(listOf(NotesDb.CheckItem("leche", false))))
        val n = db.getNote(id)!!
        assertEquals(3, n.color)
        assertEquals(NotesDb.KIND_CHECK, n.kind)
        assertTrue("created debe fijarse al alta", n.created > 0)
        assertTrue(n.pinned.not())
    }

    @Test
    fun `una nota del CRUD viejo (v1) lee con defaults`() {
        val id = db.addNote("Vieja", "cuerpo", "https://x.com")
        val n = db.getNote(id)!!
        assertEquals(0, n.color)
        assertEquals(NotesDb.KIND_TEXT, n.kind)
        assertFalse(n.pinned)
        assertFalse(n.deleted)
        assertEquals("https://x.com", n.url)
        assertTrue(n.checkItems().isEmpty())
    }

    @Test
    fun `updateNoteFull cambia titulo cuerpo color e items sin tocar url`() {
        val id = db.addNoteFull("T", "b", url = "https://origen.com")
        db.updateNoteFull(id, "T2", "b2", color = 5,
            items = NotesDb.itemsToJson(listOf(NotesDb.CheckItem("x", true))))
        val n = db.getNote(id)!!
        assertEquals("T2", n.title)
        assertEquals("b2", n.body)
        assertEquals(5, n.color)
        assertEquals("https://origen.com", n.url)
        assertEquals(1, n.checkItems().size)
        assertTrue(n.checkItems()[0].d)
    }

    // -------------------- checklist: JSON --------------------

    @Test
    fun `itemsToJson e itemsFromJson son inversos`() {
        val items = listOf(
            NotesDb.CheckItem("comprar leche", false),
            NotesDb.CheckItem("llamar al fontanero", true))
        val json = NotesDb.itemsToJson(items)
        val back = NotesDb.itemsFromJson(json)
        assertEquals(2, back.size)
        assertEquals("comprar leche", back[0].t)
        assertFalse(back[0].d)
        assertEquals("llamar al fontanero", back[1].t)
        assertTrue(back[1].d)
    }

    @Test
    fun `itemsToJson descarta filas vacias`() {
        val json = NotesDb.itemsToJson(listOf(
            NotesDb.CheckItem("", false),
            NotesDb.CheckItem("real", true)))
        assertEquals(1, NotesDb.itemsFromJson(json).size)
        // lista vacía serializa como "[]" (JSONArray), no como ""
        assertEquals("[]", NotesDb.itemsToJson(emptyList()))
    }

    @Test
    fun `itemsFromJson tolera corrupcion sin lanzar`() {
        assertTrue(NotesDb.itemsFromJson("").isEmpty())
        assertTrue(NotesDb.itemsFromJson("no es json").isEmpty())
        assertTrue(NotesDb.itemsFromJson("{\"t\":\"objeto suelto\"}").isEmpty())
        val ok = NotesDb.itemsFromJson("""[{"t":"a","d":false},{"d":true}]""")
        assertEquals(2, ok.size) // el elemento sin "t" se conserva con texto vacío
        assertTrue(ok[1].d)
    }

    @Test
    fun `checkProgress cuenta hechas y totales`() {
        val items = listOf(
            NotesDb.CheckItem("1", true), NotesDb.CheckItem("2", true),
            NotesDb.CheckItem("3", false))
        val id = db.addNoteFull("L", "", items = NotesDb.itemsToJson(items), kind = NotesDb.KIND_CHECK)
        assertEquals(2 to 3, db.getNote(id)!!.checkProgress())
    }

    @Test
    fun `la busqueda tambien encuentra en elementos del checklist`() {
        db.addNoteFull("Lista", "", items = NotesDb.itemsToJson(listOf(
            NotesDb.CheckItem("paracetamol", false))))
        assertEquals(1, db.visible("paracetamol").size)
        assertEquals(0, db.visible("ibuprofeno").size)
    }
}
