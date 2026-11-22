package com.turbodl.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.turbodl.model.Task
import java.util.ArrayList

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "turbodl.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tasks(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT, kind INTEGER, name TEXT, dir TEXT, file TEXT," +
                "total INTEGER, done INTEGER, state INTEGER, error TEXT," +
                "headers TEXT, segs TEXT, extra TEXT, time INTEGER)"
        )
        db.execSQL(SQL_HISTORY)
        db.execSQL(SQL_CREDS)
        db.execSQL(SQL_VAULT)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        // v2: historial de paginas del navegador
        if (oldV < 2) db.execSQL(SQL_HISTORY)
        // v3: historial por usuario + credenciales cifradas
        if (oldV < 3) {
            try { db.execSQL("ALTER TABLE history ADD COLUMN user TEXT DEFAULT 'main'") } catch (e: Exception) { }
            try { db.execSQL(SQL_CREDS) } catch (e: Exception) { }
        }
        // v4 (4.6): bóveda de credenciales estilo KeePassXC + OTP (TOTP/HOTP).
        // Las contraseñas y los secrets OTP se guardan cifrados con Secrets
        // (AES-256-GCM, clave en AndroidKeyStore), igual que la tabla creds.
        if (oldV < 4) {
            try { db.execSQL(SQL_VAULT) } catch (e: Exception) { }
        }
    }

    companion object {
        private const val SQL_HISTORY =
            "CREATE TABLE history(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "url TEXT, title TEXT, time INTEGER, user TEXT DEFAULT 'main')"

        // credenciales de inicio de sesion (la contraseña via cifrada con
        // Secrets: AES-256-GCM, clave en el AndroidKeyStore)
        private const val SQL_CREDS =
            "CREATE TABLE creds(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "user TEXT, host TEXT, username TEXT, secret TEXT," +
            "never INTEGER DEFAULT 0, time INTEGER)"

        // 4.6: bóveda de credenciales estilo KeePassXC con OTP.
        //   user     — sandbox de usuario (igual que creds/history).
        //   title    — nombre de la entrada ("Gmail", "GitHub"...).
        //   username — usuario/email para autenticarse.
        //   password — contraseña cifrada con Secrets (vacía si no aplica).
        //   url      — sitio web asociado (opcional).
        //   otpcfg   — config OTP serializada por Otp.Cfg.encode() (vacío si
        //              la entrada no tiene 2FA). Ej: "totp|SHA1|6|30|0|0".
        //   otpkey   — secret OTP cifrado con Secrets (Base32/hex/plain antes
        //              de cifrar). Vacío si no hay OTP.
        //   notes    — notas libres (texto plano; para secret sensible
        //              conviene usar el campo password).
        //   time     — timestamp de creación/edición.
        private const val SQL_VAULT =
            "CREATE TABLE vault(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "user TEXT, title TEXT, username TEXT, password TEXT," +
            "url TEXT, otpcfg TEXT, otpkey TEXT, notes TEXT, time INTEGER)"
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
    fun addHistory(url: String, title: String, user: String = "main") {
        if (url.isEmpty() || url.startsWith("data:")) return
        val v = ContentValues()
        v.put("url", url)
        v.put("title", title)
        v.put("user", user)
        v.put("time", System.currentTimeMillis())
        writableDatabase.insert("history", null, v)
        // capamos a 500 entradas
        writableDatabase.execSQL(
            "DELETE FROM history WHERE id NOT IN " +
            "(SELECT id FROM history ORDER BY id DESC LIMIT 500)")
    }

    @Synchronized
    fun history(limit: Int = 200, user: String = "main"): ArrayList<HistRow> {
        val out = ArrayList<HistRow>()
        val c = readableDatabase.rawQuery(
            "SELECT id, url, title, time FROM history WHERE user=? ORDER BY id DESC LIMIT ?",
            arrayOf(user, limit.toString()))
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
    fun clearHistory(user: String = "main") {
        writableDatabase.delete("history", "user=?", arrayOf(user))
    }

    // ---------------- credenciales de inicio de sesion ----------------

    class CredRow(val id: Long, val user: String, val host: String,
                  val username: String, val secret: String, val never: Boolean)

    @Synchronized
    fun saveCred(user: String, host: String, username: String, secretEnc: String) {
        // un par por host: reemplaza si ya existe para el mismo usuario+host
        writableDatabase.delete("creds", "user=? AND host=?", arrayOf(user, host))
        val v = ContentValues()
        v.put("user", user)
        v.put("host", host)
        v.put("username", username)
        v.put("secret", secretEnc)
        v.put("never", 0)
        v.put("time", System.currentTimeMillis())
        writableDatabase.insert("creds", null, v)
    }

    @Synchronized
    fun creds(user: String): ArrayList<CredRow> {
        val out = ArrayList<CredRow>()
        val c = readableDatabase.rawQuery(
            "SELECT id,user,host,username,secret,never FROM creds WHERE user=? ORDER BY host",
            arrayOf(user))
        c.use {
            while (it.moveToNext()) out.add(CredRow(
                it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "",
                it.getString(3) ?: "", it.getString(4) ?: "", it.getInt(5) != 0))
        }
        return out
    }

    @Synchronized
    fun findCred(user: String, host: String): CredRow? {
        val c = readableDatabase.rawQuery(
            "SELECT id,user,host,username,secret,never FROM creds WHERE user=? AND host=? LIMIT 1",
            arrayOf(user, host))
        c.use {
            if (it.moveToFirst()) return CredRow(
                it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "",
                it.getString(3) ?: "", it.getString(4) ?: "", it.getInt(5) != 0)
        }
        return null
    }

    @Synchronized
    fun deleteCred(id: Long) {
        writableDatabase.delete("creds", "id=?", arrayOf(id.toString()))
    }

    /** Borra TODAS las credenciales de un usuario (4.2: usado por
     *  Users.remove para limpiar el sandbox al eliminar usuario). */
    @Synchronized
    fun clearCreds(user: String) {
        writableDatabase.delete("creds", "user=?", arrayOf(user))
    }

    /** Número de entradas de historial de un usuario (4.2: visor de
     *  sandbox; no devuelve las filas, solo el conteo para el resumen). */
    @Synchronized
    fun historyCount(user: String): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM history WHERE user=?", arrayOf(user))
        return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Número de credenciales guardadas de un usuario (4.2: visor). */
    @Synchronized
    fun credsCount(user: String): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM creds WHERE user=? AND never=0", arrayOf(user))
        return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Número de hosts en los que el usuario marcó "nunca preguntar"
     *  (4.2: visor). */
    @Synchronized
    fun neverCount(user: String): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM creds WHERE user=? AND never=1", arrayOf(user))
        return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    @Synchronized
    fun setNeverCred(user: String, host: String) {
        val v = ContentValues()
        v.put("never", 1)
        v.put("username", "")
        v.put("secret", "")
        v.put("time", System.currentTimeMillis())
        val n = writableDatabase.update("creds", v, "user=? AND host=?", arrayOf(user, host))
        if (n == 0) {
            v.put("user", user)
            v.put("host", host)
            writableDatabase.insert("creds", null, v)
        }
    }

    @Synchronized
    fun neverFor(user: String, host: String): Boolean {
        val c = readableDatabase.rawQuery(
            "SELECT never FROM creds WHERE user=? AND host=? LIMIT 1", arrayOf(user, host))
        c.use { if (it.moveToFirst()) return it.getInt(0) != 0 }
        return false
    }

    // ---------------- bóveda (4.6) ----------------

    /** Entrada de la bóveda. password/otpkey vienen CIFRADOS con Secrets
     *  desde la BD; la UI los descifra bajo demanda (nunca en la lista). */
    class VaultRow(
        val id: Long, val user: String, val title: String, val username: String,
        val passwordEnc: String, val url: String,
        val otpCfgEnc: String,  // serialización Otp.Cfg.encode(), SIN cifrar (no es secreto)
        val otpKeyEnc: String,  // secret OTP CIFRADO con Secrets
        val notes: String, val time: Long
    ) {
        /** ¿Tiene OTP configurado (otpcfg y otpkey no vacíos)? */
        fun hasOtp(): Boolean = otpCfgEnc.isNotBlank() && otpKeyEnc.isNotBlank()
    }

    /** Inserta o reemplaza una entrada de la bóveda. Si id==0 inserta;
     *  si id>0 actualiza. Los campos password y otpKey ya deben venir
     *  CIFrados con [com.turbodl.util.Secrets.encrypt]. */
    @Synchronized
    fun saveVault(id: Long, user: String, title: String, username: String,
                  passwordEnc: String, url: String,
                  otpCfgEnc: String, otpKeyEnc: String, notes: String): Long {
        val v = ContentValues()
        if (id > 0) v.put("id", id)
        v.put("user", user); v.put("title", title); v.put("username", username)
        v.put("password", passwordEnc); v.put("url", url)
        v.put("otpcfg", otpCfgEnc); v.put("otpkey", otpKeyEnc)
        v.put("notes", notes); v.put("time", System.currentTimeMillis())
        return if (id > 0) {
            writableDatabase.update("vault", v, "id=?", arrayOf(id.toString())).toLong()
            id
        } else {
            writableDatabase.insert("vault", null, v)
        }
    }

    /** Todas las entradas del usuario, ordenadas por título. */
    @Synchronized
    fun vaultAll(user: String): ArrayList<VaultRow> {
        val out = ArrayList<VaultRow>()
        val c = readableDatabase.rawQuery(
            "SELECT id,user,title,username,password,url,otpcfg,otpkey,notes,time " +
            "FROM vault WHERE user=? ORDER BY title COLLATE NOCASE",
            arrayOf(user))
        c.use {
            while (it.moveToNext()) out.add(VaultRow(
                it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "",
                it.getString(3) ?: "", it.getString(4) ?: "", it.getString(5) ?: "",
                it.getString(6) ?: "", it.getString(7) ?: "", it.getString(8) ?: "",
                it.getLong(9)
            ))
        }
        return out
    }

    /** Una entrada por id. */
    @Synchronized
    fun vaultGet(id: Long): VaultRow? {
        val c = readableDatabase.rawQuery(
            "SELECT id,user,title,username,password,url,otpcfg,otpkey,notes,time " +
            "FROM vault WHERE id=? LIMIT 1", arrayOf(id.toString()))
        c.use {
            if (it.moveToFirst()) return VaultRow(
                it.getLong(0), it.getString(1) ?: "", it.getString(2) ?: "",
                it.getString(3) ?: "", it.getString(4) ?: "", it.getString(5) ?: "",
                it.getString(6) ?: "", it.getString(7) ?: "", it.getString(8) ?: "",
                it.getLong(9)
            )
        }
        return null
    }

    @Synchronized
    fun deleteVault(id: Long) {
        writableDatabase.delete("vault", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun vaultCount(user: String): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM vault WHERE user=?", arrayOf(user))
        c.use { if (it.moveToFirst()) return it.getInt(0) }
        return 0
    }

    /** Incrementa el contador HOTP de una entrada y devuelve el nuevo
     *  valor. Solo aplica a HOTP; el guardado del nuevo contador lo hace
     *  la UI reescribiendo otpcfg con [com.turbodl.util.Otp.Cfg.encode]. */
    @Synchronized
    fun vaultNextCounter(id: Long): Long {
        val row = vaultGet(id) ?: return 0L
        val cfg = com.turbodl.util.Otp.Cfg.parse(row.otpCfgEnc) ?: return 0L
        val nc = cfg.counter + 1
        val ncfg = cfg.copy(counter = nc)
        val v = ContentValues()
        v.put("otpcfg", ncfg.encode())
        writableDatabase.update("vault", v, "id=?", arrayOf(id.toString()))
        return nc
    }
}
