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
        row("🔐 Permisos", permText()) { checkPermissions() }

        header("Navegador")
        row("🔎 Motor de búsqueda", engineText()) { pickEngine() }
        row("👤 Perfil de navegación", Profiles.name(eng.sp)) { pickProfile() }
        row("🖥 Modo escritorio por defecto",
            if (eng.sp.getBoolean("deskDefault", false)) "activado" else "desactivado") { toggleDesk() }
        row("🧹 Limpiar historial de búsqueda", "") { clearHistory() }
        row("🍪 Limpiar cookies y caché del navegador", "") { clearWeb() }

        header("Apariencia")
        row("🔠 Tamaño de fuente", "${eng.sp.getInt("fontScale", 100)}%") { pickFontScale() }
        row("🅰 Tipografía", Ui.FAMILIES.firstOrNull { it.first == Ui.fontFamily(this) }?.second ?: "Sans") { pickFamily() }
        row("🌓 Tema", if (Ui.isDark(this)) "Oscuro" else "Claro") { pickTheme() }

        header("Acerca de")
        val about = TextView(this)
        about.text = "TurboDL 2.1.0 · gestor de descargas multipropósito\n" +
                "HTTP · FTP · HLS · DASH · BitTorrent · thunder/flashget/qqdl\n" +
                "Funciona desde Android 5.0 (API 21). Pensado para equipos de " +
                "1 GB de RAM: sin AndroidX, pool de hilos acotado y WebView único.\n" +
                "Gratis, sin anuncios ni suscripciones."
        about.textSize = 12f
        about.setPadding(50, 8, 50, 8)
        root.addView(about)
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
        val opts = arrayOf("Claro", "Oscuro")
        AlertDialog.Builder(this)
            .setTitle("Tema")
            .setSingleChoiceItems(opts, if (Ui.isDark(this)) 1 else 0) { d, which ->
                eng.sp.edit().putBoolean("dark", which == 1).apply()
                d.dismiss()
                recreate()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
