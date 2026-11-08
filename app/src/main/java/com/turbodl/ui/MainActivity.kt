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
        pm.menu.add(0, 4, 3, "🚪 Salir (cerrar del todo)")
        pm.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> { eng.pauseAll(); toast("Todo pausado") }
                2 -> { eng.resumeAll(); maybeStartService() }
                3 -> showSortMenu()
                4 -> confirmExit()
            }
            true
        }
        pm.show()
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
        if (t.state == St.DONE && eng.outputExists(t)) labels.add("📂 Abrir archivo")
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
                    "📂 Abrir archivo" -> openFile(t)
                    "🔗 Copiar enlace" -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("url", t.url))
                        toast("Enlace copiado")
                    }
                    "ℹ Detalles" -> showDetails(t)
                    "🗑 Eliminar" -> confirmDelete(t)
                }
            }
            .show()
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
                St.QUEUED -> "En cola · ${if (t.total > 0) Kit.fmtBytes(t.total) else "tamaño desconocido"}$qInfo$dInfo"
                else -> "En cola$segInfo"
            }
            return v
        }
    }
}
