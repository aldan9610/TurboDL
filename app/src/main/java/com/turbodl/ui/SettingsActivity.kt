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
        row("🖥 Modo escritorio por defecto",
            if (eng.sp.getBoolean("deskDefault", false)) "activado" else "desactivado") { toggleDesk() }
        row("🧹 Limpiar historial de búsqueda", "") { clearHistory() }
        row("🕘 Limpiar historial de páginas", "") { clearPageHistory() }
        row("🍪 Limpiar cookies y caché del navegador", "") { clearWeb() }

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
        about.text = "TurboDL 3.0.0 · gestor de descargas multipropósito\n" +
                "HTTP multihilo · FTP · HLS · DASH · BitTorrent · subtítulos · " +
                "reproductor interno · navegador con pestañas, marcadores y adblock\n" +
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
