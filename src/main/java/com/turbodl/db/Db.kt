package com.turbodl.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.turbodl.model.Task
import java.util.ArrayList

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "turbodl.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tasks(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT, kind INTEGER, name TEXT, dir TEXT, file TEXT," +
                "total INTEGER, done INTEGER, state INTEGER, error TEXT," +
                "headers TEXT, segs TEXT, extra TEXT, time INTEGER)"
        )
        db.execSQL(SQL_HISTORY)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // v2: historial de paginas del navegador
        if (oldV < 2) db.execSQL(SQL_HISTORY)
    }

    companion object {
        private const val SQL_HISTORY =
            "CREATE TABLE history(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "url TEXT, title TEXT, time INTEGER)"
    }

    private fun cv(t: Task): ContentValues {
        val v = ContentValues()
        v.put("url", t.url)
        v.put("kind", t.kind)
        v.put("name", t.name)
        v.put("dir", t.dir)
        v.put("file", t.fileName)
        v.put("total", t.total)
        v.put("done", t.done)
        v.put("state", t.state)
        v.put("error", t.error)
        v.put("headers", t.headersJson)
        v.put("segs", t.segsJson)
        v.put("extra", t.extraJson)
        v.put("time", t.time)
        return v
    }

    private fun row(c: android.database.Cursor): Task {
        val t = Task()
        t.id = c.getLong(0)
        t.url = c.getString(1) ?: ""
        t.kind = c.getInt(2)
        t.name = c.getString(3) ?: ""
        t.dir = c.getString(4) ?: ""
        t.fileName = c.getString(5) ?: ""
        t.total = c.getLong(6)
        t.done = c.getLong(7)
        t.state = c.getInt(8)
        t.error = c.getString(9) ?: ""
        t.headersJson = c.getString(10) ?: ""
        t.segsJson = c.getString(11) ?: ""
        t.extraJson = c.getString(12) ?: ""
        t.time = c.getLong(13)
        return t
    }

    @Synchronized
    fun insert(t: Task): Long {
        t.id = writableDatabase.insert("tasks", null, cv(t))
        return t.id
    }

    @Synchronized
    fun update(t: Task) {
        writableDatabase.update("tasks", cv(t), "id=?", arrayOf(t.id.toString()))
    }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.delete("tasks", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun all(): ArrayList<Task> {
        val out = ArrayList<Task>()
        val c = readableDatabase.rawQuery("SELECT * FROM tasks ORDER BY id DESC", null)
        c.use {
            while (it.moveToNext()) out.add(row(it))
        }
        return out
    }

    @Synchronized
    fun get(id: Long): Task? {
        val c = readableDatabase.rawQuery("SELECT * FROM tasks WHERE id=?", arrayOf(id.toString()))
        c.use { return if (it.moveToFirst()) row(it) else null }
    }

    // ---------------- historial de paginas ----------------

    class HistRow(val id: Long, val url: String, val title: String, val time: Long)

    @Synchronized
    fun addHistory(url: String, title: String) {
        if (url.isEmpty() || url.startsWith("data:")) return
        val v = ContentValues()
        v.put("url", url)
        v.put("title", title)
        v.put("time", System.currentTimeMillis())
        writableDatabase.insert("history", null, v)
        // capamos a 500 entradas
        writableDatabase.execSQL(
            "DELETE FROM history WHERE id NOT IN " +
            "(SELECT id FROM history ORDER BY id DESC LIMIT 500)")
    }

    @Synchronized
    fun history(limit: Int = 200): ArrayList<HistRow> {
        val out = ArrayList<HistRow>()
        val c = readableDatabase.rawQuery(
            "SELECT id, url, title, time FROM history ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString()))
        c.use {
            while (it.moveToNext()) {
                out.add(HistRow(it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "", it.getLong(3)))
            }
        }
        return out
    }

    @Synchronized
    fun deleteHistory(id: Long) {
        writableDatabase.delete("history", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }
}
