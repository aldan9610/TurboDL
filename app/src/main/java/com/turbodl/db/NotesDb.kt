package com.turbodl.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Base propia del navegador 4.0: notas (para prompts y textos que se
 * copian a los chatbots) y páginas guardadas para acceso offline.
 *
 * 4.17: las notas se convierten en el módulo completo estilo ColorNote
 * (Herramientas › Notas): color, tipo (texto o lista de comprobación),
 * fijadas arriba, papelera con restauración y elementos de checklist.
 * Esquema v2 — onUpgrade añade columnas; los campos nuevos tienen
 * defaults para que las notas antiguas sigan funcionando sin cambios.
 */
class NotesDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "browser4.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "title TEXT, body TEXT, url TEXT, time INTEGER," +
            "color INTEGER DEFAULT 0, kind INTEGER DEFAULT 0," +
            "pinned INTEGER DEFAULT 0, deleted INTEGER DEFAULT 0," +
            "created INTEGER DEFAULT 0, items TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE saved(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "url TEXT, title TEXT, path TEXT, size INTEGER, time INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // v1 → v2: columnas del módulo de notas avanzado (4.17). ALTER
        // TABLE con defaults: las filas existentes quedan color=0
        // (naranja), kind=0 (texto), no fijadas, no borradas.
        if (oldV < 2) {
            db.execSQL("ALTER TABLE notes ADD COLUMN color INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN kind INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN pinned INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN deleted INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN created INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE notes ADD COLUMN items TEXT DEFAULT ''")
        }
    }

    // ---------------- notas ----------------

    /** Elemento de una lista de comprobación (4.17). */
    class CheckItem(val t: String, val d: Boolean)

    class Note(val id: Long, val title: String, val body: String,
               val url: String, val time: Long,
               val color: Int = 0, val kind: Int = KIND_TEXT,
               val pinned: Boolean = false, val deleted: Boolean = false,
               val created: Long = 0, val items: String = "") {
        fun preview(): String {
            val t = title.ifEmpty { "(sin título)" }
            return "$t\n" + body.replace('\n', ' ').take(80)
        }
        /** Elementos del checklist (vacío si es nota de texto). */
        fun checkItems(): List<CheckItem> = itemsFromJson(items)
        /** Progreso del checklist "hechas/total" (0/0 en texto). */
        fun checkProgress(): Pair<Int, Int> {
            val its = checkItems()
            return its.count { it.d } to its.size
        }
    }

    @Synchronized fun addNote(title: String, body: String, url: String): Long =
        addNoteFull(title, body, url, 0, KIND_TEXT, false, "")

    /** 4.17: alta completa (color, tipo, fijada, checklist). */
    @Synchronized fun addNoteFull(
        title: String, body: String, url: String = "",
        color: Int = 0, kind: Int = KIND_TEXT,
        pinned: Boolean = false, items: String = ""
    ): Long {
        val now = System.currentTimeMillis()
        val v = ContentValues()
        v.put("title", title)
        v.put("body", body)
        v.put("url", url)
        v.put("time", now)
        v.put("color", color)
        v.put("kind", kind)
        v.put("pinned", if (pinned) 1 else 0)
        v.put("deleted", 0)
        v.put("created", now)
        v.put("items", items)
        return writableDatabase.insert("notes", null, v)
    }

    @Synchronized fun updateNote(id: Long, title: String, body: String) {
        val v = ContentValues()
        v.put("title", title)
        v.put("body", body)
        v.put("time", System.currentTimeMillis())
        writableDatabase.update("notes", v, "id=?", arrayOf(id.toString()))
    }

    /** 4.17: edición completa desde el editor (título, cuerpo, color,
     *  checklist). Conserva url y created; actualiza time. */
    @Synchronized fun updateNoteFull(
        id: Long, title: String, body: String,
        color: Int, items: String
    ) {
        val v = ContentValues()
        v.put("title", title)
        v.put("body", body)
        v.put("color", color)
        v.put("items", items)
        v.put("time", System.currentTimeMillis())
        writableDatabase.update("notes", v, "id=?", arrayOf(id.toString()))
    }

    /** 4.17: fijar/desfijar (las fijadas salen primero en la lista). */
    @Synchronized fun setPinned(id: Long, b: Boolean) {
        val v = ContentValues()
        v.put("pinned", if (b) 1 else 0)
        v.put("time", System.currentTimeMillis())
        writableDatabase.update("notes", v, "id=?", arrayOf(id.toString()))
    }

    /** 4.17: color de la nota (índice de la paleta, 0..7). */
    @Synchronized fun setColor(id: Long, color: Int) {
        val v = ContentValues()
        v.put("color", color)
        v.put("time", System.currentTimeMillis())
        writableDatabase.update("notes", v, "id=?", arrayOf(id.toString()))
    }

    /** 4.17: papelera — borrado suave (recuperable). */
    @Synchronized fun softDelete(id: Long) {
        val v = ContentValues()
        v.put("deleted", 1)
        v.put("pinned", 0)
        writableDatabase.update("notes", v, "id=?", arrayOf(id.toString()))
    }

    /** 4.17: restaurar desde la papelera. */
    @Synchronized fun restore(id: Long) {
        val v = ContentValues()
        v.put("deleted", 0)
        v.put("time", System.currentTimeMillis())
        writableDatabase.update("notes", v, "id=?", arrayOf(id.toString()))
    }

    /** 4.17: borrado definitivo de UNA nota de la papelera. */
    @Synchronized fun deleteNote(id: Long) {
        writableDatabase.delete("notes", "id=?", arrayOf(id.toString()))
    }

    /** 4.17: vaciar la papelera por completo. */
    @Synchronized fun emptyTrash() {
        writableDatabase.delete("notes", "deleted=1", null)
    }

    /** 4.3: borra todas las notas. Se usa en los tests JVM de CRUD para
     *  garantizar aislamiento entre tests (NotesDb es singleton, así no
     *  se puede recrear la instancia). No se llama desde la app. */
    @Synchronized fun clearAllNotes() {
        writableDatabase.execSQL("DELETE FROM notes")
    }

    /** Lee una sola nota por id (4.2: antes editNote releía toda la
     *  lista con notes().firstOrNull; ahora va directo a la fila). */
    @Synchronized fun getNote(id: Long): Note? {
        val c = readableDatabase.rawQuery(
            "SELECT id,title,body,url,time,color,kind,pinned,deleted,created,items " +
            "FROM notes WHERE id=?", arrayOf(id.toString()))
        return c.use {
            if (it.moveToFirst()) readNote(it) else null
        }
    }

    /** Notas visibles (no borradas): fijadas primero, luego por fecha.
     *  Con query filtra por título/cuerpo/elementos del checklist. */
    @Synchronized fun visible(query: String = ""): ArrayList<Note> =
        queryNotes(query, includeDeleted = false)

    /** 4.17: papelera (borradas suaves, la más reciente primero). */
    @Synchronized fun trashed(): ArrayList<Note> =
        queryNotes("", includeDeleted = true)

    /** 4.17: conteo rápido para la fila de Herramientas. */
    @Synchronized fun counts(): Pair<Int, Int> {
        val c = readableDatabase.rawQuery(
            "SELECT (SELECT COUNT(*) FROM notes WHERE deleted=0), " +
            "(SELECT COUNT(*) FROM notes WHERE deleted=1)", null)
        return c.use {
            if (it.moveToFirst()) Pair(it.getInt(0), it.getInt(1)) else Pair(0, 0)
        }
    }

    @Synchronized fun notes(query: String = ""): ArrayList<Note> =
        queryNotes(query, includeDeleted = false)

    private fun queryNotes(query: String, includeDeleted: Boolean): ArrayList<Note> {
        val out = ArrayList<Note>()
        val where = if (includeDeleted) "deleted=1" else "deleted=0"
        val order = if (includeDeleted) "time DESC"
            else "pinned DESC, time DESC"
        val c = if (query.isBlank())
            readableDatabase.rawQuery(
                "SELECT id,title,body,url,time,color,kind,pinned,deleted,created,items " +
                "FROM notes WHERE $where ORDER BY $order", null)
        else readableDatabase.rawQuery(
            "SELECT id,title,body,url,time,color,kind,pinned,deleted,created,items " +
            "FROM notes WHERE $where AND " +
            "(title LIKE ? OR body LIKE ? OR items LIKE ?) ORDER BY $order",
            arrayOf("%$query%", "%$query%", "%$query%"))
        c.use {
            while (it.moveToNext()) out.add(readNote(it))
        }
        return out
    }

    private fun readNote(c: android.database.Cursor): Note = Note(
        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "",
        c.getString(3) ?: "", c.getLong(4),
        c.getInt(5), c.getInt(6), c.getInt(7) == 1, c.getInt(8) == 1,
        c.getLong(9), c.getString(10) ?: "")

    // ---------------- páginas guardadas offline ----------------

    class SavedPage(val id: Long, val url: String, val title: String,
                    val path: String, val size: Long, val time: Long)

    @Synchronized fun addSaved(url: String, title: String, path: String, size: Long): Long {
        val v = ContentValues()
        v.put("url", url)
        v.put("title", title)
        v.put("path", path)
        v.put("size", size)
        v.put("time", System.currentTimeMillis())
        return writableDatabase.insert("saved", null, v)
    }

    @Synchronized fun saved(): ArrayList<SavedPage> {
        val out = ArrayList<SavedPage>()
        val c = readableDatabase.rawQuery(
            "SELECT id,url,title,path,size,time FROM saved ORDER BY time DESC", null)
        c.use {
            while (it.moveToNext()) out.add(SavedPage(
                it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "",
                it.getString(3) ?: "", it.getLong(4), it.getLong(5)))
        }
        return out
    }

    @Synchronized fun deleteSaved(id: Long) {
        writableDatabase.delete("saved", "id=?", arrayOf(id.toString()))
    }

    // ---------------- checklist: JSON de elementos (4.17) ----------------
    // Pure functions: los tests JVM las ejercitan sin tocar la BD.

    companion object {
        const val KIND_TEXT = 0
        const val KIND_CHECK = 1

        /** Serializa los elementos a JSON ([{"t":..,"d":true},..]). */
        fun itemsToJson(items: List<CheckItem>): String {
            val arr = JSONArray()
            for (it in items) {
                if (it.t.isBlank()) continue // filas vacías fuera
                arr.put(JSONObject().put("t", it.t).put("d", it.d))
            }
            return arr.toString()
        }

        /** Parsea el JSON de elementos; tolera corrupción (devuelve lo
         *  que se pueda, nunca lanza). */
        fun itemsFromJson(s: String): List<CheckItem> {
            if (s.isBlank()) return emptyList()
            return try {
                val arr = JSONArray(s)
                val out = ArrayList<CheckItem>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(CheckItem(o.optString("t"), o.optBoolean("d")))
                }
                out
            } catch (e: Exception) {
                emptyList()
            }
        }

        @Volatile private var inst: NotesDb? = null
        fun get(ctx: Context): NotesDb =
            inst ?: synchronized(this) {
                inst ?: NotesDb(ctx).also { inst = it }
            }
    }
}
