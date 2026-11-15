package com.turbodl.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.turbodl.App
import com.turbodl.R
import com.turbodl.model.Kind
import com.turbodl.model.St
import com.turbodl.model.Task
import com.turbodl.service.DownloadService
import com.turbodl.util.Kit
import com.turbodl.util.Ui
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var eng: com.turbodl.engine.Engine
    private lateinit var adapter: TaskAdapter
    private lateinit var list: ListView
    private lateinit var txtTotal: TextView
    private lateinit var dim: View
    private lateinit var drawer: View
    private val sorted = ArrayList<Task>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        // puerta de bloqueo con PIN (si esta activo y aun no se valido)
        val eng0 = App.app.engine
        if ((eng0.sp.getString("pinHash", "") ?: "").isNotEmpty() && !App.unlocked) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.main)
        eng = App.app.engine
        list = findViewById(R.id.list)
        txtTotal = findViewById(R.id.txtTotal)
        dim = findViewById(R.id.dim)
        drawer = findViewById(R.id.drawer)
        adapter = TaskAdapter()
        list.adapter = adapter
        Ui.styleAll(findViewById(android.R.id.content))

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showAddDialog("") }
        findViewById<Button>(R.id.btnBrowser).setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        findViewById<Button>(R.id.btnAll).setOnClickListener { toggleAll() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { openDrawer() }
        findViewById<Button>(R.id.btnMore).setOnClickListener { showMoreMenu(it) }

        setupDrawer()
        list.setOnItemClickListener { _, _, pos, _ -> showTaskActions(sorted[pos]) }
        dim.setOnClickListener { closeDrawer() }

        requestNeededPermissions()
        maybeStartService()

        // introducción de primera instalación (estilo 1DM): notificaciones,
        // carpeta de descargas, batería/segundo plano
        if (!eng.sp.getBoolean("onboardingDone", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // URL compartida desde otra app
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val url = text.split(Regex("\\s+")).firstOrNull {
                it.startsWith("http") || it.startsWith("magnet") || it.contains("://")
            } ?: text
            if (url.isNotEmpty()) showAddDialog(url)
        } else if (intent?.action == Intent.ACTION_VIEW) {
            val u = intent.dataString ?: ""
            if (u.isNotEmpty()) showAddDialog(u)
        }
    }

    // ---------------- permisos ----------------

    private fun requestNeededPermissions() {
        val sp = eng.sp
        val wantsStorage = sp.getString("dlMode", "app") == "saf" || Build.VERSION.SDK_INT <= 28
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED && !sp.getBoolean("notifAsked", false)) {
                sp.edit().putBoolean("notifAsked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            }
        }
        // Almacenamiento: solo necesario en Android 6..9 (permiso runtime) cuando
        // se guarda fuera de la carpeta de la app; en 5.x se concede al instalar.
        if (Build.VERSION.SDK_INT in 23..28 && wantsStorage) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED && !sp.getBoolean("storeAsked", false)) {
                AlertDialog.Builder(this)
                    .setTitle("Permiso de almacenamiento")
                    .setMessage("TurboDL lo necesita para guardar las descargas donde elijas " +
                            "(incluida la tarjeta SD). Sin el permiso se usará la carpeta interna de la app.")
                    .setPositiveButton("Conceder") { _, _ ->
                        sp.edit().putBoolean("storeAsked", true).apply()
                        requestPermissions(arrayOf(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE), 9)
                    }
                    .setNegativeButton("Ahora no", null)
                    .show()
            }
        }
    }

    // ---------------- cajon lateral y menus ----------------

    private fun openDrawer() {
        dim.visibility = View.VISIBLE
        drawer.visibility = View.VISIBLE
        drawer.translationX = -drawer.width.toFloat()
        drawer.animate().translationX(0f).setDuration(180).start()
    }

    private fun closeDrawer() {
        drawer.animate().translationX(-drawer.width.toFloat()).setDuration(150)
            .withEndAction {
                drawer.visibility = View.GONE
                dim.visibility = View.GONE
            }.start()
    }

    private fun setupDrawer() {
        fun item(id: Int, f: () -> Unit) {
            findViewById<TextView>(id).setOnClickListener {
                closeDrawer()
                f()
            }
        }
        item(R.id.dNav) { startActivity(Intent(this, BrowserActivity::class.java)) }
        item(R.id.dAdd) { showAddDialog("") }
        item(R.id.dResume) { eng.resumeAll(); maybeStartService(); toast("Reanudando todo") }
        item(R.id.dPause) { eng.pauseAll(); toast("Todo pausado") }
        item(R.id.dSort) { showSortMenu() }
        item(R.id.dTools) { startActivity(Intent(this, ToolsActivity::class.java)) }
        item(R.id.dSettings) { startActivity(Intent(this, SettingsActivity::class.java)) }
        item(R.id.dExit) { confirmExit() }
    }

    private fun showMoreMenu(v: View) {
        val pm = PopupMenu(this, v)
        pm.menu.add(0, 1, 0, "⏸ Pausar todo")
        pm.menu.add(0, 2, 1, "▶ Reanudar todo")
        pm.menu.add(0, 3, 2, "↕ Ordenar lista…")
        pm.menu.add(0, 5, 3, if (eng.sp.getBoolean("compactView", false))
            "📋 Vista normal" else "📋 Vista compacta")
        pm.menu.add(0, 6, 4, "💾 Uso de almacenamiento")
        pm.menu.add(0, 4, 5, "🚪 Salir (cerrar del todo)")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> { eng.pauseAll(); toast("Todo pausado") }
                2 -> { eng.resumeAll(); maybeStartService() }
                3 -> showSortMenu()
                5 -> {
                    val nv = !eng.sp.getBoolean("compactView", false)
                    eng.sp.edit().putBoolean("compactView", nv).apply()
                    refresh()
                }
                6 -> showStoragePanel()
                4 -> confirmExit()
            }
            true
        }
        pm.show()
    }

    /** Panel: cuanto ocupa cada descarga, total y espacio libre. */
    private fun showStoragePanel() {
        Thread {
            val rows = ArrayList<Pair<String, Long>>()
            var total = 0L
            val snap = synchronized(eng.tasks) { eng.tasks.values.toList() }
            for (t in snap) {
                val size = try {
                    val docUri = t.extra().optString("docUri", "")
                    if (t.kind == Kind.TORRENT) {
                        File(t.dir).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                    } else if (docUri.isNotEmpty()) {
                        val c = contentResolver.query(Uri.parse(docUri),
                            arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
                        c?.use {
                            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
                        } ?: 0L
                    } else File(t.path()).takeIf { it.exists() }?.length() ?: 0L
                } catch (e: Exception) { 0L }
                if (size > 0) { rows.add(t.name to size); total += size }
            }
            rows.sortByDescending { it.second }
            val free = try {
                val st = android.os.StatFs(eng.baseDir.absolutePath)
                st.availableBytes
            } catch (e: Exception) { -1L }
            runOnUiThread {
                val sb = StringBuilder()
                sb.append("Total de descargas: ${Kit.fmtBytes(total)}\n")
                if (free >= 0) sb.append("Espacio libre: ${Kit.fmtBytes(free)}\n\n")
                if (rows.isEmpty()) sb.append("Sin archivos todavía.")
                else {
                    sb.append("Mayores archivos:\n")
                    for ((name, size) in rows.take(15)) {
                        sb.append("• ${Kit.fmtBytes(size)} · ${name.take(40)}\n")
                    }
                    if (rows.size > 15) sb.append("… y ${rows.size - 15} más")
                }
                AlertDialog.Builder(this)
                    .setTitle("💾 Almacenamiento")
                    .setMessage(sb.toString())
                    .setPositiveButton("Cerrar", null)
                    .setNeutralButton("🧹 Limpiar temporales") { _, _ ->
                        eng.cleanupTemp()
                        toast("Temporales de hace +48 h eliminados")
                    }
                    .show()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showSortMenu() {
        val modes = arrayOf(
            "Fecha (recientes primero)", "Nombre (A-Z)", "Nombre (Z-A)",
            "Tamaño (mayor a menor)", "Tamaño (menor a mayor)", "Estado"
        )
        val cur = eng.sp.getInt("sortTasks", 0)
        AlertDialog.Builder(this)
            .setTitle("Ordenar descargas")
            .setSingleChoiceItems(modes, cur) { d, which ->
                eng.sp.edit().putInt("sortTasks", which).apply()
                refresh()
                d.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun toggleAll() {
        val anyActive = eng.tasks.values.any {
            it.state == St.RUNNING || it.state == St.QUEUED || it.state == St.MERGING
        }
        if (anyActive) {
            eng.pauseAll()
            toast("Todo pausado")
        } else {
            eng.resumeAll()
            maybeStartService()
            toast("Reanudando todo")
        }
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Salir de TurboDL")
            .setMessage("Se detendrán las descargas activas (quedarán pausadas), se cerrará " +
                    "el servicio en segundo plano y la app se cerrará por completo.")
            .setPositiveButton("Salir") { _, _ ->
                eng.shutdown()
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun maybeStartService() {
        val needed = eng.tasks.values.any {
            it.state == St.RUNNING || it.state == St.QUEUED || it.state == St.MERGING
        }
        if (!needed) return
        val i = Intent(this, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    override fun onResume() {
        super.onResume()
        eng.listener = { refresh() }
        refresh()
        checkClipboard()
        feedbackConfig()
    }

    /** Feedback al abrir: si falta alguna configuración importante, se dice
     *  al usuario qué hacer (una vez por sesión, sin insistir). */
    @Volatile private var feedbackShown = false
    private fun feedbackConfig() {
        if (!eng.sp.getBoolean("onboardingDone", false)) return // la intro lo cubre
        if (feedbackShown) return
        feedbackShown = true
        val pendientes = ArrayList<String>()
        val acciones = ArrayList<() -> Unit>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendientes.add("• Notificaciones desactivadas: no verás el progreso de las descargas.")
            acciones.add {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            }
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            pendientes.add("• Segundo plano: Android puede frenar descargas largas por la batería.")
            acciones.add {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (e: Exception) { }
            }
        }
        if (eng.sp.getString("dlMode", "app") == "saf" && !eng.usingSaf()) {
            pendientes.add("• La carpeta pública elegida ya no es accesible: se usa la carpeta interna hasta volver a elegirla.")
            acciones.add { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        if (pendientes.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Configuración recomendada")
            .setMessage("Para que todo funcione bien:\n\n" + pendientes.joinToString("\n"))
            .setPositiveButton("Configurar") { _, _ -> acciones.firstOrNull()?.invoke() }
            .setNeutralButton("Ver la introducción") { _, _ ->
                startActivity(Intent(this, OnboardingActivity::class.java))
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    /** Enlaces compartidos/recibidos mientras la app ya está abierta
     *  (singleTask: los VIEW externos llegan aquí, no a onCreate). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val url = text.split(Regex("\\s+")).firstOrNull {
                it.startsWith("http") || it.startsWith("magnet") || it.contains("://")
            } ?: text
            if (url.isNotEmpty()) showAddDialog(url)
        } else if (intent.action == Intent.ACTION_VIEW) {
            val u = intent.dataString ?: ""
            if (u.isNotEmpty()) showAddDialog(u)
        }
    }

    /** Enlace copiado detectado: ofrecer descargarlo o abrirlo. */
    private fun checkClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).coerceToText(this).toString().trim()
            if (!text.startsWith("http://") && !text.startsWith("https://")) return
            if (text.length > 600) return
            val last = eng.sp.getString("lastClip", "") ?: ""
            if (text == last) return
            eng.sp.edit().putString("lastClip", text).apply()
            AlertDialog.Builder(this)
                .setTitle("Enlace copiado detectado")
                .setMessage(text)
                .setPositiveButton("⬇ Descargar") { _, _ -> showAddDialog(text) }
                .setNeutralButton("🌐 Abrir en navegador") { _, _ ->
                    startActivity(Intent(this, BrowserActivity::class.java)
                        .putExtra("openUrl", text))
                }
                .setNegativeButton("Ignorar", null)
                .show()
        } catch (e: Exception) { }
    }

    override fun onPause() {
        eng.listener = null
        super.onPause()
    }

    private fun refresh() {
        runOnUiThread {
            adapter.refill()
            adapter.notifyDataSetChanged()
            updateFooter()
        }
    }

    private fun updateFooter() {
        var running = 0
        var queued = 0
        var speed = 0L
        for (t in eng.tasks.values) {
            when (t.state) {
                St.RUNNING, St.MERGING -> { running++; speed += t.speed }
                St.QUEUED -> queued++
            }
        }
        txtTotal.text = when {
            running > 0 -> "$running activa(s) · ${Kit.fmtSpeed(speed)}" +
                    if (queued > 0) " · $queued en cola" else ""
            queued > 0 -> "$queued en cola (descargas simultáneas: ${eng.maxConcurrent()})"
            else -> "Listo · ${eng.tasks.size} descarga(s)"
        }
        findViewById<Button>(R.id.btnAll).text =
            if (running > 0 || queued > 0) "⏸ Pausar" else "▶ Todo"
    }

    // ---------------- dialogo añadir ----------------

    private fun showAddDialog(prefill: String) {
        val v = layoutInflater.inflate(R.layout.dialog_add, null)
        val editUrl = v.findViewById<EditText>(R.id.editUrl)
        val editName = v.findViewById<EditText>(R.id.editName)
        val spinKind = v.findViewById<Spinner>(R.id.spinKind)
        val spinCat = v.findViewById<Spinner>(R.id.spinCat)
        val seek = v.findViewById<SeekBar>(R.id.seekConns)
        val txtConns = v.findViewById<TextView>(R.id.txtConns)
        val editHeaders = v.findViewById<EditText>(R.id.editHeaders)
        val chkQueue = v.findViewById<CheckBox>(R.id.chkQueue)
        editUrl.setText(prefill)

        spinKind.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Auto", "HTTP(S)", "FTP/FTPS", "HLS (m3u8)", "DASH (mpd)", "Torrent"))
        spinCat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, eng.cats)
        spinCat.setSelection(0)

        val defConns = eng.sp.getInt("conns", 4)
        seek.progress = defConns - 1
        txtConns.text = "Conexiones: $defConns"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                txtConns.text = "Conexiones: ${p + 1}"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        v.findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                editUrl.setText(clip.getItemAt(0).coerceToText(this).toString())
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Añadir descarga")
            .setView(v)
            .setPositiveButton("Añadir") { _, _ ->
                val url = editUrl.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                val kindUser = spinKind.selectedItemPosition
                val headers = editHeaders.text.toString()
                var headersJson = ""
                if (headers.isNotEmpty()) {
                    val o = JSONObject()
                    try {
                        for (line in headers.lines()) {
                            val idx = line.indexOf(':')
                            if (idx > 0) o.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
                        }
                        headersJson = o.toString()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Cabeceras ignoradas: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                eng.add(url, editName.text.toString().trim(), kindUser, spinCat.selectedItemPosition,
                    seek.progress + 1, headersJson, chkQueue.isChecked, null) { t ->
                    if (t != null) {
                        toast(if (chkQueue.isChecked)
                            "Añadido a la cola: ${t.name}" else "Descargando: ${t.name}")
                        maybeStartService()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- acciones de tarea ----------------

    private fun showTaskActions(t: Task) {
        val opts = ArrayList<String>()
        val labels = ArrayList<String>()
        if (t.state == St.RUNNING || t.state == St.MERGING) labels.add("⏸ Pausar")
        else if (t.state == St.QUEUED) labels.add("⏏ Sacar de la cola e iniciar ahora")
        else labels.add("▶ Iniciar ahora")
        if (t.state != St.QUEUED && t.state != St.RUNNING && t.state != St.MERGING) labels.add("⏳ Añadir a la cola")
        if (t.state == St.DONE && eng.outputExists(t)) {
            val ext = Kit.extOf(t.fileName)
            if (ext in MEDIA_EXTS) labels.add("▶ Reproducir")
            if (ext == "mp4" && t.kind != Kind.TORRENT) labels.add("🎵 Extraer audio (M4A)")
            labels.add("📂 Abrir archivo")
            labels.add("📤 Mover a otra carpeta")
        }
        if (t.extra().optString("origin", "").isNotEmpty() &&
            (t.state == St.ERROR || t.state == St.PAUSED)) labels.add("🔄 Refrescar enlace (reabrir la página y reanudar)")
        labels.add("🔗 Copiar enlace")
        labels.add("ℹ Detalles")
        labels.add("🗑 Eliminar")

        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setItems(labels.toTypedArray()) { _, which ->
                when (labels[which]) {
                    "⏸ Pausar" -> eng.pause(t)
                    "⏏ Sacar de la cola e iniciar ahora" -> eng.startNow(t)
                    "▶ Iniciar ahora" -> { eng.startNow(t); maybeStartService() }
                    "⏳ Añadir a la cola" -> eng.enqueue(t)
                    "▶ Reproducir" -> playInternally(t)
                    "🎵 Extraer audio (M4A)" -> extractAudio(t)
                    "📂 Abrir archivo" -> openFile(t)
                    "📤 Mover a otra carpeta" -> moveTaskPick(t)
                    "🔗 Copiar enlace" -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("url", t.url))
                        toast("Enlace copiado")
                    }
                    "🔄 Refrescar enlace (reabrir la página y reanudar)" -> {
                        // manual: permite reintentar aunque el auto ya falló
                        t.putExtra("refrescoAuto", false)
                        eng.requestLinkRefresh(t, manual = true)
                        maybeStartService()
                    }
                    "ℹ Detalles" -> showDetails(t)
                    "🗑 Eliminar" -> confirmDelete(t)
                }
            }
            .show()
    }

    private val MEDIA_EXTS = setOf("mp4", "webm", "mkv", "ts", "mov", "m4a", "mp3", "aac", "flac", "ogg", "opus", "wav", "3gp")

    private fun playInternally(t: Task) {
        try {
            val docUri = t.extra().optString("docUri", "")
            val src = if (docUri.isNotEmpty()) docUri
            else "content://com.turbodl.files/" + Uri.encode(
                File(t.path()).canonicalFile.absolutePath
                    .removePrefix(eng.baseDir.canonicalPath + "/"))
            val i = Intent(this, VideoActivity::class.java)
                .putExtra("src", src)
                .putExtra("title", t.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            toast("No se pudo reproducir: ${e.message}")
        }
    }

    private fun extractAudio(t: Task) {
        toast("Extrayendo audio…")
        Thread {
            val name = com.turbodl.engine.Merge.extractAudio(eng, t)
            runOnUiThread {
                if (name != null) toast("Audio guardado: $name")
                else toast("No se pudo extraer el audio (¿sin pista de sonido?)")
            }
        }.apply { isDaemon = true }.start()
    }

    /** "Mover a otra carpeta": elige arbol SAF, copia y borra el original. */
    private var moveTarget: Task? = null
    private fun moveTaskPick(t: Task) {
        moveTarget = t
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, 77)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val t = moveTarget ?: return
            val tree = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) { }
            toast("Moviendo…")
            Thread { moveTask(t, tree) }.apply { isDaemon = true }.start()
        }
    }

    private fun moveTask(t: Task, tree: Uri) {
        try {
            val docUri = t.extra().optString("docUri", "")
            val store = com.turbodl.engine.SafStore(this, tree)
            var doc = store.rootDocUri()
            doc = store.ensureDir(doc, "TurboDL")
            doc = store.ensureDir(doc, eng.catNameOf(t.dir))
            val mime = Kit.mimeOf(t.fileName)
            val dest = store.ensureFile(doc, t.fileName, mime)
            val buf = ByteArray(128 * 1024)
            var copied = 0L
            // 1) copiar a destino contando bytes
            store.openSink(dest).use { out ->
                if (docUri.isNotEmpty()) {
                    contentResolver.openInputStream(Uri.parse(docUri))?.use { inp ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.append(buf, n)
                            copied += n
                        }
                    } ?: throw Exception("origen ilegible")
                } else {
                    java.io.FileInputStream(File(t.path())).use { inp ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.append(buf, n)
                            copied += n
                        }
                    }
                }
            }
            // 2) borrar origen
            if (docUri.isNotEmpty()) {
                com.turbodl.engine.SafStore(this, Uri.parse(t.extra().optString("tree", "")))
                    .delete(Uri.parse(docUri))
            } else File(t.path()).delete()
            // 3) reconfigurar la tarea al nuevo arbol
            t.putExtra("tree", tree.toString())
            t.putExtra("docUri", dest.toString())
            t.dir = com.turbodl.engine.SafStore.treeName(this, tree) + "/TurboDL/" + eng.catNameOf(t.dir)
            t.total = copied
            t.done = copied
            eng.db.update(t)
            runOnUiThread {
                refresh()
                toast("Movido a ${t.dir}")
            }
        } catch (e: Exception) {
            runOnUiThread { toast("No se pudo mover: ${e.message}") }
        }
    }

    private fun showDetails(t: Task) {
        val msg = StringBuilder()
        msg.append("URL: ${t.url}\n")
        val docUri = t.extra().optString("docUri", "")
        msg.append("Archivo: ${if (docUri.isNotEmpty()) docUri else t.path()}\n")
        if (t.quality().isNotEmpty()) msg.append("Calidad: ${t.quality()}\n")
        val dur = t.durationSec()
        if (dur > 0) msg.append("Duración: ${Kit.fmtDur(dur)}\n")
        msg.append("Tamaño: ${if (t.total >= 0) Kit.fmtBytes(t.total) else "desconocido"}\n")
        msg.append("Descargado: ${Kit.fmtBytes(t.done)}\n")
        msg.append("Destino: ${t.dir}\n")
        val note = t.extra().optString("note", "")
        if (note.isNotEmpty()) msg.append("Nota: $note\n")
        val note2 = t.extra().optString("note2", "")
        if (note2.isNotEmpty()) msg.append("$note2\n")
        val ok = t.extra().optString("ok", "")
        if (ok == "1") msg.append("Verificación: ✔ correcto\n")
        else if (ok == "0") msg.append("Verificación: ⚠ revisa la nota\n")
        msg.append("Estado: ${stateName(t.state)}${if (t.error.isNotEmpty()) " — ${t.error}" else ""}")
        AlertDialog.Builder(this).setTitle(t.name).setMessage(msg.toString())
            .setPositiveButton("Cerrar", null).show()
    }

    private fun confirmDelete(t: Task) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar «${t.name}»")
            .setMessage("¿Borrar también el archivo descargado?")
            .setPositiveButton("Sí, todo") { _, _ -> eng.delete(t, true) }
            .setNegativeButton("Solo tarea") { _, _ -> eng.delete(t, false) }
            .setNeutralButton("Cancelar", null)
            .show()
    }

    private fun openFile(t: Task) {
        try {
            val docUri = t.extra().optString("docUri", "")
            if (docUri.isNotEmpty()) {
                val ext = Kit.extOf(t.fileName)
                var mime = Kit.mimeOf(t.fileName)
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(docUri), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(i, "Abrir con"))
                return
            }
            val root = eng.baseDir.canonicalPath
            val f = File(t.path()).canonicalFile
            val rel = f.absolutePath.removePrefix(root + "/")
            val uri = Uri.parse("content://com.turbodl.files/" + Uri.encode(rel))
            val ext = MimeTypeMap.getFileExtensionFromUrl(f.name).lowercase()
            var mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (mime == null) mime = when (ext) {
                "ts" -> "video/mp2t"
                "m3u8" -> "application/x-mpegURL"
                else -> "application/octet-stream"
            }
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Abrir con"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stateName(s: Int): String = when (s) {
        St.RUNNING -> "Descargando"
        St.PAUSED -> "Pausado"
        St.DONE -> "Completado"
        St.ERROR -> "Error"
        St.MERGING -> "Combinando audio+video"
        St.SEEDING -> "Completado (sembrando)"
        else -> "En cola"
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------------- adaptador ----------------

    inner class TaskAdapter : BaseAdapter() {

        fun refill() {
            sorted.clear()
            val all = eng.tasks.values.toList()
            when (eng.sp.getInt("sortTasks", 0)) {
                1 -> sorted.addAll(all.sortedBy { it.name.lowercase() })
                2 -> sorted.addAll(all.sortedByDescending { it.name.lowercase() })
                3 -> sorted.addAll(all.sortedByDescending { if (it.total > 0) it.total else -1 })
                4 -> sorted.addAll(all.sortedBy { if (it.total > 0) it.total else Long.MAX_VALUE })
                5 -> sorted.addAll(all.sortedByDescending { it.state })
                else -> sorted.addAll(all.sortedByDescending { it.time })
            }
        }

        override fun getCount(): Int = sorted.size
        override fun getItem(pos: Int): Task = sorted[pos]
        override fun getItemId(pos: Int): Long = getItem(pos).id

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.item_task, parent, false)
            val t = getItem(pos)
            val badge = v.findViewById<TextView>(R.id.txtBadge)
            val name = v.findViewById<TextView>(R.id.txtName)
            val state = v.findViewById<TextView>(R.id.txtState)
            val prog = v.findViewById<ProgressBar>(R.id.prog)
            val info = v.findViewById<TextView>(R.id.txtInfo)

            badge.text = when (t.kind) {
                Kind.FTP -> "FTP"
                Kind.HLS -> "HLS"
                Kind.DASH -> "DASH"
                Kind.TORRENT -> "BT"
                else -> "HTTP"
            }
            val q = t.quality()
            name.text = t.name
            state.text = when (t.state) {
                St.QUEUED -> "⏳"
                St.RUNNING -> "⬇"
                St.PAUSED -> "⏸"
                St.DONE -> "✔"
                St.ERROR -> "⚠"
                St.MERGING -> "🔀"
                else -> ""
            }

            // progreso: bytes si hay total real; si no, segmentos (HLS/DASH)
            val compactView = eng.sp.getBoolean("compactView", false)
            val pct = t.pct()
            if (pct >= 0) {
                prog.isIndeterminate = false
                prog.progress = (pct * 10).coerceIn(0, 1000)
            } else {
                prog.progress = 0
                prog.isIndeterminate = t.state == St.RUNNING || t.state == St.MERGING
            }

            val extra = t.extra()
            val segsT = extra.optLong("segs", -1)
            val segsD = extra.optLong("sdone", 0)
            val segInfo = if (segsT > 0) " · seg $segsD/$segsT" else ""
            val qInfo = if (q.isNotEmpty()) " · $q" else ""
            val dInfo = t.durationSec().takeIf { it > 0 }?.let { " · ${Kit.fmtDur(it)}" } ?: ""
            val pctInfo = if (pct >= 0) " · $pct%" else ""

            info.text = when (t.state) {
                St.RUNNING -> {
                    val eta = if (t.total > 0 && t.speed > 0)
                        " · resta ${Kit.fmtEta((t.total - t.done) / t.speed)}" else ""
                    "${Kit.fmtSpeed(t.speed)} · ${Kit.fmtBytes(t.done)}" +
                            (if (t.total > 0) " / " + Kit.fmtBytes(t.total) else "") +
                            pctInfo + eta + segInfo + qInfo + dInfo
                }
                St.MERGING -> "Reconstruyendo índice del MP4…$pctInfo$segInfo$qInfo$dInfo"
                St.PAUSED -> "Pausado · ${Kit.fmtBytes(t.done)}$pctInfo$segInfo$qInfo$dInfo"
                St.DONE -> "Completado · ${Kit.fmtBytes(if (t.total > 0) t.total else t.done)}$qInfo$dInfo"
                St.ERROR -> "⚠ ${t.error}"
                St.QUEUED -> {
                    val wait = extra.optString("wait", "")
                    if (wait.isNotEmpty()) "⏳ $wait"
                    else "En cola · ${if (t.total > 0) Kit.fmtBytes(t.total) else "tamaño desconocido"}$qInfo$dInfo"
                }
                else -> "En cola$segInfo"
            }
            info.visibility = if (compactView && t.state == St.DONE) View.GONE else View.VISIBLE
            return v
        }
    }
}
