package com.turbodl.db

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests JVM del esquema v5 de la bóveda (4.12): carpetas anidadas
 * (vault_grp con parent), columna grp en vault, mover entradas/carpetas y
 * el re-parent seguro al eliminar una carpeta. Contra SQLite real vía
 * Robolectric.
 *
 * Contrato que fijan estos tests (rediseño de la UI de la bóveda):
 *  - una carpeta puede contener subcarpetas (profundidad libre);
 *  - deleteVaultGroup NO pierde datos: subcarpetas y entradas pasan al
 *    padre de la carpeta eliminada;
 *  - vaultAll(user, grp) solo devuelve las entradas de esa carpeta;
 *  - vaultSearch(user, q) busca en TODAS las carpetas.
 */
@RunWith(RobolectricTestRunner::class)
class VaultGroupsTest {

    private lateinit var db: Db

    @Before
    fun setUp() {
        db = Db(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun carpetasAnidadas_crudBasico() {
        val raiz = db.saveVaultGroup(0, "main", "Trabajo", 0)
        assertTrue(raiz > 0)
        val sub = db.saveVaultGroup(0, "main", "Bancos", raiz)
        val subsub = db.saveVaultGroup(0, "main", "Ahorro", sub)

        val all = db.vaultGroups("main")
        assertEquals(3, all.size)
        // jerarquía: Ahorro → Bancos → Trabajo → raíz
        assertEquals(raiz, db.vaultGroupGet(sub)!!.parent)
        assertEquals(sub, db.vaultGroupGet(subsub)!!.parent)
        assertEquals(0L, db.vaultGroupGet(raiz)!!.parent)
        assertNull(db.vaultGroupGet(99999))
    }

    @Test
    fun entradasEnCarpetas_filtrosPorNivel() {
        val g = db.saveVaultGroup(0, "main", "Personal", 0)
        val raizId = db.saveVault(0, "main", "Raíz", "u", "", "", "", "", "", 0)
        val dentroId = db.saveVault(0, "main", "Dentro", "u", "", "", "", "", "", g)

        assertEquals(1, db.vaultAll("main", 0).size)
        assertEquals(1, db.vaultAll("main", g).size)
        assertEquals("Raíz", db.vaultAll("main", 0)[0].title)
        assertEquals("Dentro", db.vaultAll("main", g)[0].title)
        assertEquals(g, db.vaultGet(dentroId)!!.grp)
        assertEquals(0L, db.vaultGet(raizId)!!.grp)

        // mover la de raíz a la carpeta
        db.vaultMove(raizId, g)
        assertEquals(0, db.vaultAll("main", 0).size)
        assertEquals(2, db.vaultAll("main", g).size)
    }

    @Test
    fun busquedaCruzaCarpetas() {
        val g = db.saveVaultGroup(0, "main", "Personal", 0)
        db.saveVault(0, "main", "GitHub", "ana@mail", "", "https://github.com", "", "", "", 0)
        db.saveVault(0, "main", "Correo", "pepe@mail", "", "", "", "", "nota banco", g)

        // por título en raíz
        assertEquals(1, db.vaultSearch("main", "github").size)
        // por notas de una entrada en carpeta
        assertEquals(1, db.vaultSearch("main", "banco").size)
        // por usuario
        assertEquals(1, db.vaultSearch("main", "pepe@").size)
        assertEquals(0, db.vaultSearch("main", "inexistente").size)
    }

    @Test
    fun eliminarCarpeta_reparentaHijosYEntradas() {
        val raiz = db.saveVaultGroup(0, "main", "Trabajo", 0)
        val sub = db.saveVaultGroup(0, "main", "Bancos", raiz)
        val subsub = db.saveVaultGroup(0, "main", "Ahorro", sub)
        db.saveVault(0, "main", "EnSub", "u", "", "", "", "", "", sub)
        db.saveVault(0, "main", "EnSubSub", "u", "", "", "", "", "", subsub)

        // borrar "Bancos": sus HIJOS DIRECTOS suben un nivel (Ahorro pasa a
        // Trabajo, la entrada EnSub pasa a Trabajo); la entrada que estaba
        // DENTRO de Ahorro sigue en Ahorro (que ahora cuelga de Trabajo).
        // Nada se pierde, igual que en un gestor tipo KeePassXC.
        db.deleteVaultGroup(sub)
        assertNull(db.vaultGroupGet(sub))
        assertEquals(raiz, db.vaultGroupGet(subsub)!!.parent)
        val enTrabajo = db.vaultAll("main", raiz)
        assertEquals(1, enTrabajo.size)
        assertEquals("EnSub", enTrabajo[0].title)
        val enAhorro = db.vaultAll("main", subsub)
        assertEquals(1, enAhorro.size)
        assertEquals("EnSubSub", enAhorro[0].title)
    }

    @Test
    fun clearVault_borraEntradasYCarpetasDelUsuario() {
        db.saveVaultGroup(0, "u1", "Carpeta", 0)
        db.saveVault(0, "u1", "Entrada", "u", "", "", "", "", "", 0)
        db.saveVault(0, "u2", "Ajena", "u", "", "", "", "", "", 0)
        db.clearVault("u1")
        assertEquals(0, db.vaultGroups("u1").size)
        assertEquals(0, db.vaultAll("u1", 0).size)
        // el otro usuario no se toca
        assertEquals(1, db.vaultAll("u2", 0).size)
    }

    @Test
    fun migracionV5_entradasAntiguasQuedanEnRaiz() {
        // Crea a mano una BD con el esquema v4 (vault SIN columna grp, sin
        // vault_grp) y una entrada dentro; user_version=4. Al abrirla con
        // la clase Db real (v5) corre onUpgrade 4→5: la entrada debe quedar
        // accesible en la RAÍZ (grp default 0) y las carpetas funcionar.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migr4test.db"
        ctx.deleteDatabase(name)
        val f = ctx.getDatabasePath(name)
        f.parentFile?.mkdirs()
        val raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(f, null)
        raw.execSQL("CREATE TABLE vault(id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "user TEXT, title TEXT, username TEXT, password TEXT," +
            "url TEXT, otpcfg TEXT, otpkey TEXT, notes TEXT, time INTEGER)")
        val v = android.content.ContentValues()
        v.put("user", "olduser"); v.put("title", "Antigua")
        v.put("time", System.currentTimeMillis())
        raw.insert("vault", null, v)
        raw.version = 4   // marca esquema v4 para disparar onUpgrade(4→5)
        raw.close()

        val db = Db(ctx, name)
        val entradas = db.vaultAll("olduser", 0)
        assertEquals(1, entradas.size)
        assertEquals("Antigua", entradas[0].title)
        assertEquals(0L, entradas[0].grp)
        // y la bóveda migrada acepta carpetas anidadas al momento
        val g = db.saveVaultGroup(0, "olduser", "NuevaCarpeta", 0)
        db.vaultMove(entradas[0].id, g)
        assertEquals(0, db.vaultAll("olduser", 0).size)
        assertEquals(1, db.vaultAll("olduser", g).size)
    }
}
