package com.turbodl.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Base propia del navegador 4.0: notas (para prompts y textos que se
 * copian a los chatbots) y páginas guardadas para acceso offline.
 */
class NotesDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "browser4.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "title TEXT, body TEXT, url TEXT, time INTEGER)")
        db.execSQL("CREATE TABLE saved(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "url TEXT, title TEXT, path TEXT, size INTEGER, time INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) { }

    // ---------------- notas ----------------

    class Note(val id: Long, val title: String, val body: String,
               val url: String, val time: Long) {
        fun preview(): String {
            val t = title.ifEmpty { "(sin título)" }
            return "$t\n" + body.replace('\n', ' ').take(80)
        }
    }

    @Synchronized fun addNote(title: String, body: String, url: String): Long {
        val v = ContentValues()
        v.put("title", title)
        v.put("body", body)
        v.put("url", url)
        v.put("time", System.currentTimeMillis())
        return writableDatabase.insert("notes", null, v)
    }

    @Synchronized fun updateNote(id: Long, title: String, body: String) {
        val v = ContentValues()
        v.put("title", title)
        v.put("body", body)
        v.put("time", System.currentTimeMillis())
        writableDatabase.update("notes", v, "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun deleteNote(id: Long) {
        writableDatabase.delete("notes", "id=?", arrayOf(id.toString()))
    }

    @Synchronized fun notes(query: String = ""): ArrayList<Note> {
        val out = ArrayList<Note>()
        val c = if (query.isBlank())
            readableDatabase.rawQuery(
                "SELECT id,title,body,url,time FROM notes ORDER BY time DESC", null)
        else readableDatabase.rawQuery(
            "SELECT id,title,body,url,time FROM notes WHERE " +
            "title LIKE ? OR body LIKE ? ORDER BY time DESC",
            arrayOf("%$query%", "%$query%"))
        c.use {
            while (it.moveToNext()) out.add(Note(
                it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "",
                it.getString(3) ?: "", it.getLong(4)))
        }
        return out
    }

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

    companion object {
        @Volatile private var inst: NotesDb? = null
        fun get(ctx: Context): NotesDb =
            inst ?: synchronized(this) {
                inst ?: NotesDb(ctx).also { inst = it }
            }
    }
}
