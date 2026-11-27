package com.turbodl.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.turbodl.R
import com.turbodl.db.NotesDb
import com.turbodl.util.Ui
import java.security.MessageDigest

/**
 * Herramientas (4.17): reescrita con la misma elaboración que Ajustes —
 * encabezados de sección en naranja y filas con título + subtítulo
 * descriptivo, sobre fondo del tema (claro/oscuro/AMOLED). Antes eran
 * cuatro botones planos sin descripciones.
 *
 * Herramientas disponibles: combinar archivos, suma de verificación,
 * codificadores (URI/Base64), bóveda de credenciales (4.6) y notas
 * (4.17, módulo completo estilo ColorNote).
 */
class ToolsActivity : Activity() {

    private lateinit var root: LinearLayout

    private var mergeParts: List<Uri>? = null

    companion object {
        const val REQ_MERGE_PICK = 1
        const val REQ_MERGE_OUT = 2
        const val REQ_CHECK = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(0, dp(8), 0, dp(24))
        scroll.addView(root)
        setContentView(scroll)
        buildUi()
        Ui.styleAll(findViewById(android.R.id.content))
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        root.removeAllViews()

        header("Archivos")
        row("🧩 Combinar archivos", "Une las partes divididas en orden " +
            "natural de nombre y guarda el resultado donde elijas") { pickParts() }
        row("#️⃣ Suma de verificación", "Calcula MD5, SHA-1 y SHA-256 de " +
            "un archivo y compara contra un hash esperado") { pickForChecksum() }
        row("🔀 Codificadores", "Codifica y decodifica texto en URI " +
            "(URL encode) y Base64") { showEncoders() }

        header("Navegador y seguridad")
        row("📝 Notas", notesText()) {
            startActivity(Intent(this, NotesActivity::class.java))
        }
        row("🔐 Bóveda de credenciales", "Contraseñas guardadas del " +
            "navegador estilo KeePassXC + códigos OTP (TOTP/HOTP)") {
            startActivity(Intent(this, VaultActivity::class.java))
        }

        header("Acerca de")
        val about = TextView(this)
        val vName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        about.text = "TurboDL $vName — gestor de descargas multipropósito\n\n" +
            "• HTTP/HTTPS con multihilo y reanudación\n" +
            "• FTP/FTPS, HLS (m3u8) con AES-128, DASH (mpd)\n" +
            "• BitTorrent: magnet y .torrent\n" +
            "• thunder://, flashget://, qqdl://\n" +
            "• Navegador con detección de video, pestañas y bloqueador de anuncios\n" +
            "• Cola de descargas y fusión de audio+video (DASH/HLS fMP4)\n" +
            "• Carpeta de descarga a elección (incluida la tarjeta SD)\n" +
            "• Notas, bóveda de credenciales y herramientas de archivo\n" +
            "• Gratis, sin suscripciones ni anuncios\n\n" +
            "Funciona desde Android 8.0 (API 26). Diseñado para dispositivos\n" +
            "de 1 GB de RAM: sin AndroidX de interfaz, buffers pequeños,\n" +
            "pool de hilos acotado y un solo WebView.\n\n" +
            "Código original escrito desde cero en Kotlin.\n" +
            "Sin relación con FileCentipede ni con sus autores;\n" +
            "solo se ofrece una funcionalidad equivalente."
        about.textSize = 12f
        about.setTextColor(Ui.text2(this))
        about.setPadding(dp(20), dp(8), dp(20), dp(8))
        root.addView(about)
    }

    private fun notesText(): String {
        val (n, t) = NotesDb.get(this).counts()
        return buildString {
            append("Notas de texto y listas con colores, fijadas y papelera — ")
            append(if (n == 1) "1 nota" else "$n notas")
            if (t > 0) append(" · $t en papelera")
        }
    }

    // ---- mismo estilo de filas que Ajustes (header + row) ----

    private fun header(t: String) {
        val h = TextView(this)
        h.text = t
        h.textSize = 13f
        h.setTypeface(h.typeface, Typeface.BOLD)
        h.setTextColor(Ui.accent(this))
        h.setPadding(dp(20), dp(24), dp(20), dp(6))
        root.addView(h)
    }

    private fun row(title: String, sub: String, f: () -> Unit) {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.setPadding(dp(20), dp(16), dp(20), dp(16))
        v.background = getDrawable(android.R.drawable.list_selector_background)
        val t1 = TextView(this)
        t1.text = title
        t1.textSize = 15f
        t1.setTextColor(Ui.text(this))
        v.addView(t1)
        if (sub.isNotEmpty()) {
            val t2 = TextView(this)
            t2.text = sub
            t2.textSize = 12f
            t2.setTextColor(Ui.text2(this))
            v.addView(t2)
        }
        v.setOnClickListener { f() }
        root.addView(v)
    }

    // -------------------- combinar archivos --------------------

    private fun pickParts() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(i, REQ_MERGE_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_MERGE_PICK -> {
                val uris = ArrayList<Uri>()
                if (data.clipData != null) {
                    for (i in 0 until data.clipData!!.itemCount) uris.add(data.clipData!!.getItemAt(i).uri)
                } else data.data?.let { uris.add(it) }
                if (uris.size < 2) {
                    Toast.makeText(this, "Selecciona al menos 2 archivos", Toast.LENGTH_SHORT).show()
                    return
                }
                mergeParts = uris.sortedBy { uriName(it) } // orden natural por nombre
                val c = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/octet-stream"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, "combinado.bin")
                }
                startActivityForResult(c, REQ_MERGE_OUT)
            }
            REQ_MERGE_OUT -> {
                val out = data.data ?: return
                val parts = mergeParts ?: return
                merge(parts, out)
            }
            REQ_CHECK -> {
                data.data?.let { checksum(it) }
            }
        }
    }

    private fun uriName(u: Uri): String {
        var name = u.lastPathSegment ?: ""
        contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex("_display_name")
                if (i >= 0) name = c.getString(i) ?: name
            }
        }
        return name
    }

    private fun merge(parts: List<Uri>, out: Uri) {
        val dlg = AlertDialog.Builder(this).setMessage("Combinando ${parts.size} archivos...")
            .setCancelable(false).create()
        dlg.show()
        Thread {
            var ok = false
            try {
                val o = contentResolver.openOutputStream(out)!!
                val buf = ByteArray(64 * 1024)
                for (u in parts) {
                    val inp = contentResolver.openInputStream(u)!!
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                    }
                    inp.close()
                }
                o.close()
                ok = true
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            runOnUiThread {
                dlg.dismiss()
                if (ok) Toast.makeText(this, "Archivos combinados correctamente", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // -------------------- suma de verificación --------------------

    private fun pickForChecksum() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(i, REQ_CHECK)
    }

    private fun checksum(u: Uri) {
        val dlg = AlertDialog.Builder(this).setMessage("Calculando...").setCancelable(false).create()
        dlg.show()
        Thread {
            val res = LinkedHashMap<String, String>()
            try {
                for (algo in arrayOf("MD5", "SHA-1", "SHA-256")) {
                    val md = MessageDigest.getInstance(algo)
                    val inp = contentResolver.openInputStream(u)!!
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                    inp.close()
                    res[algo] = md.digest().joinToString("") { "%02x".format(it) }
                }
            } catch (e: Exception) {
                runOnUiThread { dlg.dismiss(); Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show() }
                return@Thread
            }
            runOnUiThread {
                dlg.dismiss()
                val lay = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(50, 30, 50, 0)
                }
                val txt = TextView(this)
                txt.text = res.entries.joinToString("\n\n") { "${it.key}:\n${it.value}" }
                txt.textSize = 11f
                txt.setTextColor(Ui.text(this))
                val edit = EditText(this).apply { hint = "Pega un hash para comparar" }
                lay.addView(txt)
                lay.addView(edit)
                AlertDialog.Builder(this)
                    .setTitle(uriName(u))
                    .setView(lay)
                    .setPositiveButton("Comparar") { _, _ ->
                        val want = edit.text.toString().trim().lowercase()
                        val found = res.values.any { it == want }
                        Toast.makeText(
                            this,
                            if (want.isEmpty()) "Escribe un hash" else if (found) "✔ Coinciden" else "✘ No coincide",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
        }.start()
    }

    // -------------------- codificadores --------------------

    private fun showEncoders() {
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 0)
        }
        val input = EditText(this).apply { hint = "Texto de entrada" }
        val output = EditText(this).apply {
            hint = "Resultado"
            keyListener = null
            textSize = 12f
        }
        val row1 = LinearLayout(this)
        val row2 = LinearLayout(this)
        fun btn(t: String, f: (String) -> String): android.widget.Button {
            val b = android.widget.Button(this)
            b.text = t
            b.setOnClickListener {
                val r = try { f(input.text.toString()) } catch (e: Exception) { "Error: ${e.message}" }
                output.setText(r)
            }
            return b
        }
        row1.addView(btn("URI codificar") { s -> java.net.URLEncoder.encode(s, "UTF-8") })
        row1.addView(btn("URI decodificar") { s -> java.net.URLDecoder.decode(s, "UTF-8") })
        row2.addView(btn("Base64 codificar") { s -> android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.DEFAULT) })
        row2.addView(btn("Base64 decodificar") {
            s -> String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
        })
        lay.addView(input)
        lay.addView(row1)
        lay.addView(row2)
        lay.addView(output)
        AlertDialog.Builder(this).setTitle("Codificadores").setView(lay)
            .setPositiveButton("Cerrar", null).show()
    }
}
