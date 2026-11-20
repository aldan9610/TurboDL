package com.turbodl.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.turbodl.App
import com.turbodl.R
import com.turbodl.engine.SafStore
import com.turbodl.util.Kit
import com.turbodl.util.Profiles
import com.turbodl.util.Ui

/**
 * Ajustes: carpeta de descarga (app o explorador/SAF incluida la SD),
 * cola y velocidad, navegador (buscador, perfil, privacidad) y apariencia
 * (tamano de fuente, tipografia, tema).
 */
class SettingsActivity : Activity() {

    private val eng get() = App.app.engine
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        buildUi()
        Ui.styleAll(findViewById(android.R.id.content))
    }

    private fun buildUi() {
        root.removeAllViews()
        root.setPadding(0, 0, 0, 40)
        header("Descargas")
        row("📁 Carpeta de descarga", destText()) { pickFolder() }
        row("⏳ Descargas simultáneas", "${eng.maxConcurrent()}") { pickMaxConcurrent() }
        row("🔗 Conexiones por descarga", "${eng.sp.getInt("conns", 4)}") { pickConns() }
        row("🚦 Límite de velocidad", limitText()) { pickLimit() }
        row("📶 Solo Wi-Fi", if (eng.sp.getBoolean("soloWifi", false))
            "activado: espera Wi-Fi para descargar" else "desactivado") { toggleSoloWifi() }
        row("🔁 Reintentos automáticos",
            "${eng.sp.getInt("retries", 1)} reintento(s) con espera progresiva") { pickRetries() }
        row("⏵ Reanudar descargas al abrir",
            if (eng.sp.getBoolean("autoResume", false)) "activado" else "desactivado") { toggleAutoResume() }
        row("🔐 Permisos", permText()) { checkPermissions() }

        header("Navegador")
        row("🔎 Motor de búsqueda", engineText()) { pickEngine() }
        row("👤 Perfil de navegación", Profiles.name(eng.sp)) { pickProfile() }
        row("🛡 Bloqueador de anuncios",
            if (eng.sp.getBoolean("adblock", true)) "activado" else "desactivado") { toggleAdblock() }
        row("🚫 Exclusiones del bloqueador", exentoText()) { exentoManage() }
        row("🔄 Actualizar listas de filtros (EasyList + EasyPrivacy)", adblockText()) { updateFilterLists() }
        row("🖥 Modo escritorio por defecto",
            if (eng.sp.getBoolean("deskDefault", false)) "activado" else "desactivado") { toggleDesk() }
        row("🧹 Limpiar historial de búsqueda", "") { clearHistory() }
        row("🕘 Limpiar historial de páginas", "") { clearPageHistory() }
        row("🍪 Limpiar cookies y caché del navegador", "") { clearWeb() }

        header("Torrents")
        row("🧩 Elegir archivos del torrent",
            if (eng.sp.getBoolean("torrentSel", true))
                "muestra la lista de archivos al añadir un magnet"
            else "descarga todo el contenido sin preguntar") { toggleTorrentSel() }

        header("Mantenimiento")
        row("💾 Exportar descargas (respaldo JSON)", exportText()) { exportTasks() }
        row("📥 Restaurar descargas desde respaldo", "") { importTasks() }
        row("🐞 Compartir registro de fallos", crashText()) { shareCrashLog() }
        row("⬆ Buscar actualizaciones", updateText()) { checkUpdate() }

        header("Apariencia")
        row("🔠 Tamaño de fuente", "${eng.sp.getInt("fontScale", 100)}%") { pickFontScale() }
        row("🅰 Tipografía", Ui.FAMILIES.firstOrNull { it.first == Ui.fontFamily(this) }?.second ?: "Sans") { pickFamily() }
        row("🌓 Tema", themeText()) { pickTheme() }

        header("Seguridad y privacidad")
        row("🔒 Bloqueo con PIN",
            if ((eng.sp.getString("pinHash", "") ?: "").isNotEmpty()) "activado" else "desactivado") { pickPin() }
        row("🙈 Ocultar descargas de la galería",
            if (eng.sp.getBoolean("noMedia", false)) "activado (.nomedia)" else "desactivado") {
            val nv = !eng.sp.getBoolean("noMedia", false)
            eng.sp.edit().putBoolean("noMedia", nv).apply()
            eng.applyNoMedia()
            buildUi()
            toast(if (nv) "La galería dejará de mostrar las descargas" else "Las descargas volverán a verse")
        }

        header("Acerca de")
        val about = TextView(this)
        val vName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        about.text = "TurboDL $vName · gestor de descargas multipropósito\n" +
                "HTTP multihilo · FTP · HLS · DASH · BitTorrent · subtítulos · " +
                "reproductor interno (ExoPlayer) · navegador con pestañas, marcadores " +
                "y bloqueador de anuncios\n" +
                "Requiere Android 8.0 (API 26). Pensado para equipos de " +
                "1 GB de RAM: sin AndroidX de interfaz, pool de hilos acotado y " +
                "recorte de pestañas bajo presión de memoria.\n" +
                "Gratis, sin anuncios ni suscripciones."
        about.textSize = 12f
        about.setPadding(50, 8, 50, 8)
        root.addView(about)
    }

    private fun updateFilterLists() {
        toast("Actualizando listas de filtros…")
        com.turbodl.engine.AdBlockEngine.updateLists(this) { ok, msg ->
            toast(if (ok) "Listas actualizadas: $msg" else "No se pudo actualizar: $msg")
            buildUi()
        }
    }

    private fun adblockText(): String = when {
        com.turbodl.engine.AdBlockEngine.ready() -> "bloqueador Kotlin activo (EasyList + EasyPrivacy)"
        com.turbodl.engine.AdBlockEngine.caido() -> "motor no disponible: lista básica de hosts"
        else -> "cargando motor…"
    }

    private fun header(t: String) {
        val h = TextView(this)
        h.text = t
        h.textSize = 13f
        h.setTypeface(h.typeface, android.graphics.Typeface.BOLD)
        h.setTextColor(if (Ui.isDark(this)) 0xFFFF8F00.toInt() else 0xFFE65100.toInt())
        h.setPadding(40, 30, 40, 6)
        root.addView(h)
    }

    private fun row(title: String, sub: String, f: () -> Unit) {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.setPadding(50, 22, 50, 22)
        v.background = getDrawable(android.R.drawable.list_selector_background)
        val t1 = TextView(this); t1.text = title; t1.textSize = 15f
        v.addView(t1)
        if (sub.isNotEmpty()) {
            val t2 = TextView(this); t2.text = sub; t2.textSize = 12f
            t2.setTextColor(0xFF888888.toInt())
            v.addView(t2)
        }
        v.setOnClickListener { f() }
        root.addView(v)
    }

    // ---------------- valores actuales ----------------

    private fun destText(): String {
        return if (eng.usingSaf()) {
            val s = eng.saf()
            if (s != null) SafStore.treeName(this, s.treeUri) + "/TurboDL (elegida)"
            else "arbol no disponible"
        } else eng.baseDir.absolutePath
    }

    private fun limitText(): String {
        val l = eng.sp.getInt("limit", 0)
        return if (l <= 0) "sin límite" else "$l KB/s"
    }

    private fun engineText(): String {
        val i = eng.sp.getInt("searchEng", 0)
        return if (i == 99) (eng.sp.getString("searchCustom", "") ?: "personalizado")
        else Profiles.ENGINES[i.coerceIn(0, Profiles.ENGINES.size - 1)]
    }

    private fun permText(): String {
        return if (Build.VERSION.SDK_INT >= 30) "no necesarios (SAF se encarga)"
        else if (Build.VERSION.SDK_INT >= 23) {
            val w = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (w) "concedidos" else "sin conceder (toca para solicitar)"
        } else "concedidos al instalar"
    }

    // ---------------- acciones ----------------

    private fun pickFolder() {
        val opts = arrayOf(
            "Carpeta de la app (privada, sin permisos)",
            "Elegir carpeta con el explorador (permite la tarjeta SD)"
        )
        AlertDialog.Builder(this)
            .setTitle("Carpeta de descarga")
            .setItems(opts) { _, which ->
                if (which == 0) {
                    eng.sp.edit().putString("dlMode", "app").apply()
                    buildUi()
                    toast("Destino: carpeta de la app")
                } else {
                    val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    startActivityForResult(i, 51)
                }
            }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 51 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) { }
            eng.sp.edit()
                .putString(SafStore.PREF_TREE, uri.toString())
                .putString("dlMode", "saf").apply()
            buildUi()
            toast("Descargas en: " + SafStore.treeName(this, uri) + "/TurboDL")
        } else if (requestCode == 61 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Thread {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(buildBackup().toByteArray(Charsets.UTF_8))
                    } ?: throw Exception("no se pudo crear el archivo")
                    runOnUiThread { toast("Respaldo exportado") }
                } catch (e: Exception) {
                    runOnUiThread { toast("No se pudo exportar: ${e.message}") }
                }
            }.apply { isDaemon = true }.start()
        } else if (requestCode == 62 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Thread {
                try {
                    val text = contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: throw Exception("no se pudo leer el archivo")
                    val n = importBackup(text)
                    runOnUiThread {
                        if (n > 0) {
                            toast("Restauradas $n tarea(s): revisa la lista")
                            buildUi()
                        } else toast("El respaldo no contiene tareas")
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("No se pudo restaurar: ${e.message}") }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun pickMaxConcurrent() {
        val vals = arrayOf("1 (menos RAM)", "2 (recomendado)", "3", "4")
        val cur = eng.maxConcurrent() - 1
        AlertDialog.Builder(this)
            .setTitle("Descargas simultáneas (la cola arranca el resto)")
            .setSingleChoiceItems(vals, cur) { d, which ->
                eng.sp.edit().putInt("maxConcurrent", which + 1).apply()
                d.dismiss(); buildUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickConns() {
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(50, 30, 50, 0)
        val txt = TextView(this); txt.text = "Conexiones: ${eng.sp.getInt("conns", 4)}"
        val seek = SeekBar(this); seek.max = 15; seek.progress = eng.sp.getInt("conns", 4) - 1
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                txt.text = "Conexiones: ${p + 1}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        lay.addView(txt); lay.addView(seek)
        AlertDialog.Builder(this)
            .setTitle("Conexiones por descarga (multihilo)")
            .setView(lay)
            .setPositiveButton("Guardar") { _, _ ->
                eng.sp.edit().putInt("conns", seek.progress + 1).apply()
                buildUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickLimit() {
        val edit = android.widget.EditText(this)
        edit.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        edit.hint = "0 = sin límite"
        edit.setText(eng.sp.getInt("limit", 0).toString())
        AlertDialog.Builder(this)
            .setTitle("Límite global de velocidad (KB/s)")
            .setView(edit)
            .setPositiveButton("Guardar") { _, _ ->
                val v = edit.text.toString().toIntOrNull() ?: 0
                eng.sp.edit().putInt("limit", v).apply()
                eng.limiter.bytesPerSec = v * 1024L
                buildUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT in 23..28) {
            requestPermissions(arrayOf(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE), 9)
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        } else {
            toast("En esta versión no se necesitan permisos extra")
        }
    }

    private fun pickEngine() {
        val opts = Profiles.ENGINES.toMutableList()
        opts.add("Personalizado (URL con %s)")
        val cur = eng.sp.getInt("searchEng", 0)
        AlertDialog.Builder(this)
            .setTitle("Motor de búsqueda del navegador")
            .setSingleChoiceItems(opts.toTypedArray(), cur) { d, which ->
                eng.sp.edit().putInt("searchEng", which).apply()
                if (which == opts.size - 1) {
                    d.dismiss()
                    val edit = android.widget.EditText(this)
                    edit.hint = "https://buscador.example/search?q=%s"
                    edit.setText(eng.sp.getString("searchCustom", ""))
                    AlertDialog.Builder(this)
                        .setTitle("Buscador personalizado")
                        .setMessage("Usa %s donde deba ir la búsqueda.")
                        .setView(edit)
                        .setPositiveButton("Guardar") { _, _ ->
                            eng.sp.edit().putString("searchCustom", edit.text.toString().trim()).apply()
                            buildUi()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else { d.dismiss(); buildUi() }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickProfile() {
        val names = Profiles.LIST.map { it.name }.toTypedArray() + arrayOf(Profiles.CUSTOM)
        val cur = if (eng.sp.getBoolean("profileCustom", false)) names.size - 1
        else Profiles.index(eng.sp).coerceIn(0, Profiles.LIST.size - 1)
        AlertDialog.Builder(this)
            .setTitle("Perfil de navegación (User-Agent que ve el servidor)")
            .setSingleChoiceItems(names, cur) { d, which ->
                if (which == names.size - 1) {
                    d.dismiss()
                    val edit = android.widget.EditText(this)
                    edit.hint = "User-Agent completo"
                    edit.setText(if (eng.sp.getBoolean("profileCustom", false))
                        eng.sp.getString("uaCustom", "") else Profiles.uaFor(eng.sp))
                    AlertDialog.Builder(this)
                        .setTitle("User-Agent personalizado")
                        .setView(edit)
                        .setPositiveButton("Guardar") { _, _ ->
                            eng.sp.edit()
                                .putString("uaCustom", edit.text.toString().trim())
                                .putBoolean("profileCustom", true).apply()
                            buildUi()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    eng.sp.edit().putInt("profile", which).putBoolean("profileCustom", false).apply()
                    d.dismiss(); buildUi()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toggleDesk() {
        val cur = eng.sp.getBoolean("deskDefault", false)
        eng.sp.edit().putBoolean("deskDefault", !cur).apply()
        buildUi()
    }

    private fun clearHistory() {
        eng.sp.edit().putString("history", "[]").apply()
        toast("Historial de búsqueda borrado")
    }

    private fun clearWeb() {
        try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            toast("Cookies borradas (la caché del navegador se limpia desde el navegador)")
        } catch (e: Exception) { }
    }

    private fun pickFontScale() {
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(50, 30, 50, 0)
        val txt = TextView(this); txt.text = "Tamaño: ${eng.sp.getInt("fontScale", 100)}%"
        val seek = SeekBar(this)
        seek.max = 8 // 80%..160% en pasos de 10
        seek.progress = (eng.sp.getInt("fontScale", 100) - 80) / 10
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                txt.text = "Tamaño: ${80 + p * 10}%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        lay.addView(txt); lay.addView(seek)
        AlertDialog.Builder(this)
            .setTitle("Tamaño de fuente")
            .setView(lay)
            .setPositiveButton("Aplicar") { _, _ ->
                eng.sp.edit().putInt("fontScale", 80 + seek.progress * 10).apply()
                recreate()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickFamily() {
        AlertDialog.Builder(this)
            .setTitle("Tipografía")
            .setItems(Ui.FAMILIES.map { it.second }.toTypedArray()) { d, which ->
                eng.sp.edit().putString("fontFamily", Ui.FAMILIES[which].first).apply()
                d.dismiss()
                recreate()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickTheme() {
        val opts = arrayOf("Claro", "Oscuro", "AMOLED (negro puro, ahorra batería)")
        val cur = Ui.themeMode(this)
        AlertDialog.Builder(this)
            .setTitle("Tema")
            .setSingleChoiceItems(opts, cur.coerceIn(0, 2)) { d, which ->
                eng.sp.edit().putInt("themeMode", which).putBoolean("dark", which >= 1).apply()
                d.dismiss()
                recreate()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun themeText(): String = when (Ui.themeMode(this)) {
        2 -> "AMOLED"
        1 -> "Oscuro"
        else -> "Claro"
    }

    private fun toggleSoloWifi() {
        val nv = !eng.sp.getBoolean("soloWifi", false)
        eng.sp.edit().putBoolean("soloWifi", nv).apply()
        buildUi()
        toast(if (nv) "Las descargas esperarán a tener Wi-Fi" else "Descarga con cualquier red")
    }

    private fun toggleAutoResume() {
        val nv = !eng.sp.getBoolean("autoResume", false)
        eng.sp.edit().putBoolean("autoResume", nv).apply()
        buildUi()
    }

    // ---------------- exclusión, torrents, respaldo, fallos, updates ----------------

    private fun exentoText(): String {
        val n = try { com.turbodl.engine.AdBlockEngine.exemptCount() } catch (e: Exception) { 0 }
        return if (n == 0) "ningún sitio excluido"
        else "$n sitio(s) con el bloqueo desactivado"
    }

    private fun exentoManage() {
        val raw = eng.sp.getString("adblockExempt", "[]") ?: "[]"
        val arr = try { org.json.JSONArray(raw) } catch (e: Exception) { org.json.JSONArray() }
        if (arr.length() == 0) {
            toast("El bloqueo se desactiva por sitio desde el escudo 🛡 del navegador")
            return
        }
        val items = ArrayList<String>()
        for (i in 0 until arr.length()) items.add(arr.optString(i))
        AlertDialog.Builder(this)
            .setTitle("Sitios con el bloqueo desactivado")
            .setItems(items.toTypedArray()) { _, which ->
                val dom = items[which]
                AlertDialog.Builder(this)
                    .setTitle("Reactivar bloqueo")
                    .setMessage("¿Volver a bloquear anuncios en «$dom»?")
                    .setPositiveButton("Reactivar") { _, _ ->
                        try {
                            com.turbodl.engine.AdBlockEngine.setExempt(this,
                                "https://$dom/", false)
                        } catch (e: Exception) { }
                        buildUi()
                        toast("Bloqueo reactivado en $dom")
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .setNeutralButton("Vaciar todo") { _, _ ->
                for (i in 0 until arr.length()) {
                    try {
                        com.turbodl.engine.AdBlockEngine.setExempt(this,
                            "https://${arr.optString(i)}/", false)
                    } catch (e: Exception) { }
                }
                buildUi()
                toast("Exclusiones vaciadas: el bloqueo vuelve a todos los sitios")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun toggleTorrentSel() {
        val nv = !eng.sp.getBoolean("torrentSel", true)
        eng.sp.edit().putBoolean("torrentSel", nv).apply()
        buildUi()
        toast(if (nv) "Al añadir un magnet se mostrarán sus archivos"
        else "Los torrents se descargan completos sin preguntar")
    }

    private fun exportText(): String {
        val n = eng.tasks.size
        return "$n tarea(s) · categorías y ajustes incluidos"
    }

    private fun crashText(): String {
        val f = com.turbodl.util.CrashLog.logFile(this)
        return if (f.exists() && f.length() > 0)
            "${Kit.fmtBytes(f.length())} registrados" else "sin fallos registrados"
    }

    private fun updateText(): String =
        "instalada: ${com.turbodl.util.Updater.installedVersion(this)}"

    private fun checkUpdate() {
        toast("Consultando GitHub…")
        com.turbodl.util.Updater.check(this, eng, manual = true)
    }

    private fun shareCrashLog() {
        val f = com.turbodl.util.CrashLog.logFile(this)
        if (!f.exists() || f.length() == 0L) {
            toast("No hay fallos registrados. ¡Buena señal!")
            return
        }
        try {
            val uri = android.net.Uri.parse(
                "content://com.turbodl.files/logs/${android.net.Uri.encode(f.name)}")
            val i = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Registro de fallos de TurboDL")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Compartir registro"))
        } catch (e: Exception) {
            toast("No se pudo compartir: ${e.message}")
        }
    }

    /** Respaldo: tareas + ajustes clave en un JSON. */
    private fun exportTasks() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "turbodl-respaldo.json")
        startActivityForResult(i, 61)
    }

    private fun importTasks() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        startActivityForResult(i, 62)
    }

    private fun buildBackup(): String {
        val o = org.json.JSONObject()
        o.put("app", "TurboDL")
        o.put("ver", 1)
        o.put("exported", System.currentTimeMillis())
        o.put("appVersion", try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" })
        val arr = org.json.JSONArray()
        for (t in eng.tasks.values) {
            val t2 = org.json.JSONObject()
            t2.put("url", t.url)
            t2.put("kind", t.kind)
            t2.put("name", t.name)
            t2.put("dir", t.dir)
            t2.put("file", t.fileName)
            t2.put("total", t.total)
            t2.put("done", t.done)
            t2.put("state", t.state)
            t2.put("error", t.error)
            t2.put("headers", t.headersJson)
            t2.put("segs", t.segsJson)
            t2.put("extra", t.extraJson)
            t2.put("time", t.time)
            arr.put(t2)
        }
        o.put("tasks", arr)
        return o.toString(2)
    }

    /** Importa respaldos: las tareas activas llegan en cola y los destinos
     *  locales se re-apuntan a la carpeta actual de la categoría. */
    private fun importBackup(text: String): Int {
        val o = org.json.JSONObject(text)
        if (o.optString("app") != "TurboDL") throw Exception("no es un respaldo de TurboDL")
        val arr = o.optJSONArray("tasks") ?: return 0
        var n = 0
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val t = com.turbodl.model.Task()
            t.url = j.optString("url", "")
            if (t.url.isEmpty()) continue
            t.kind = j.optInt("kind", 0)
            t.name = j.optString("name", "tarea")
            t.fileName = j.optString("file", "")
            t.total = j.optLong("total", -1L)
            t.done = j.optLong("done", 0L)
            t.error = j.optString("error", "")
            t.headersJson = j.optString("headers", "")
            t.segsJson = j.optString("segs", "")
            t.extraJson = j.optString("extra", "")
            t.time = if (j.has("time")) j.optLong("time") else System.currentTimeMillis()
            // estado: solo conservan sentido Pausado/Done/En cola
            val st = j.optInt("state", 0)
            t.state = when (st) {
                com.turbodl.model.St.DONE -> com.turbodl.model.St.DONE
                com.turbodl.model.St.PAUSED -> com.turbodl.model.St.PAUSED
                else -> com.turbodl.model.St.QUEUED
            }
            // destino: si la carpeta original no existe, usar la categoría actual
            val ex = t.extra()
            val catIdx = ex.optInt("cat", 0).coerceIn(0, eng.cats.size - 1)
            val isSaf = ex.optString("tree", "").isNotEmpty()
            if (!isSaf && t.dir.isNotEmpty() && !java.io.File(t.dir).exists()) {
                t.dir = eng.catDir(catIdx).absolutePath
                if (t.fileName.isNotEmpty()) {
                    t.fileName = com.turbodl.util.Kit.uniqueName(t.dir, t.fileName)
                }
                t.done = 0
                t.segsJson = ""
                if (t.state == com.turbodl.model.St.DONE) {
                    t.state = com.turbodl.model.St.PAUSED
                    t.putExtra("note", "restaurado: el archivo original no está; revisa y reanuda")
                }
            } else if (isSaf) {
                t.done = 0
                t.segsJson = ""
                if (t.state == com.turbodl.model.St.DONE) {
                    t.state = com.turbodl.model.St.PAUSED
                    t.putExtra("note", "restaurado: comprueba si el archivo sigue en el árbol SAF")
                }
            }
            eng.db.insert(t)
            synchronized(eng.tasks) { eng.tasks[t.id] = t }
            n++
        }
        return n
    }

    private fun toggleAdblock() {
        val nv = !eng.sp.getBoolean("adblock", true)
        eng.sp.edit().putBoolean("adblock", nv).apply()
        buildUi()
    }

    private fun pickRetries() {
        val opts = arrayOf("0 (sin reintentos)", "1 reintento", "2 reintentos", "3 reintentos")
        AlertDialog.Builder(this)
            .setTitle("Reintentos si una descarga falla\n(espera 5 s, 15 s, 60 s entre intentos)")
            .setSingleChoiceItems(opts, eng.sp.getInt("retries", 1).coerceIn(0, 3)) { d, which ->
                eng.sp.edit().putInt("retries", which).apply()
                d.dismiss(); buildUi()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun clearPageHistory() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar todo el historial de páginas?")
            .setPositiveButton("Borrar") { _, _ ->
                eng.db.clearHistory()
                toast("Historial de páginas borrado")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickPin() {
        val cur = (eng.sp.getString("pinHash", "") ?: "").isNotEmpty()
        if (cur) {
            AlertDialog.Builder(this)
                .setTitle("Bloqueo con PIN")
                .setMessage("El PIN está activado. ¿Quieres quitarlo?")
                .setPositiveButton("Quitar PIN") { _, _ ->
                    eng.sp.edit().remove("pinHash").apply()
                    buildUi()
                    toast("Bloqueo desactivado")
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(50, 30, 50, 0)
        val txt = TextView(this)
        txt.text = "PIN de 4-8 dígitos. Se guarda cifrado (SHA-256).\nSi olvidas el PIN, tendrás que borrar los datos de la app."
        txt.textSize = 12f
        val pin = android.widget.EditText(this)
        pin.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        pin.hint = "PIN"
        lay.addView(txt); lay.addView(pin)
        AlertDialog.Builder(this)
            .setTitle("Activar bloqueo con PIN")
            .setView(lay)
            .setPositiveButton("Activar") { _, _ ->
                val v = pin.text.toString()
                if (v.length in 4..8) {
                    eng.sp.edit().putString("pinHash", Kit.sha256(v)).apply()
                    buildUi()
                    toast("Bloqueo activado")
                } else toast("El PIN debe tener 4-8 dígitos")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
