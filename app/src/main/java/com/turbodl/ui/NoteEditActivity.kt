package com.turbodl.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.turbodl.db.NotesDb
import com.turbodl.util.Ui

/**
 * Editor de notas a pantalla completa (4.17), estilo ColorNote:
 *  · Nota de texto: título + cuerpo, guarda al salir (flecha ‹).
 *  · Lista de comprobación: título + filas ☑/☐ con añadir y quitar
 *    elementos; lo hecho queda tachado.
 *  · 🎨 color (paleta propia, 8 tonos), 📌 fijar arriba.
 *
 * Extras: id (-1 = nueva) y kind (NotesDb.KIND_TEXT / KIND_CHECK).
 * El guardado es automático al volver; una nota totalmente vacía se
 * descarta como en ColorNote.
 */
class NoteEditActivity : Activity() {

    private lateinit var ndb: NotesDb
    private lateinit var root: LinearLayout
    private lateinit var titleEdit: EditText
    private lateinit var bodyEdit: EditText      // solo KIND_TEXT
    private lateinit var checkHost: LinearLayout // solo KIND_CHECK
    private lateinit var pinBtn: Button

    private var id = -1L
    private var kind = NotesDb.KIND_TEXT
    private var color = 0
    private var pinned = false
    private var origUrl = ""
    private var origCreated = 0L

    /** Fila de checklist en edición (texto + estado hecho). */
    private class CheckRow(val text: EditText, val done: CheckBox)

    private val checkRows = ArrayList<CheckRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        ndb = NotesDb.get(this)

        id = intent.getLongExtra(NotesActivity.EXTRA_ID, -1L)
        kind = intent.getIntExtra(NotesActivity.EXTRA_KIND, NotesDb.KIND_TEXT)
        var body = ""
        var items = ""
        if (id > 0) {
            ndb.getNote(id)?.let { n ->
                kind = n.kind
                color = n.color
                pinned = n.pinned
                body = n.body
                items = n.items
                origUrl = n.url
                origCreated = n.created
            }
        }

        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.bg(this))
        setContentView(root)
        buildUi(body, items)
        retint()
        Ui.styleAll(findViewById(android.R.id.content))
    }

    // -------------------- interfaz --------------------

    private fun buildUi(body: String, items: String) {
        // ---- barra superior ----
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(6), dp(6), dp(6), dp(6))
        root.addView(top, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val back = topBtn("‹", "Guardar y volver")
        back.setOnClickListener { onBackPressed() }
        top.addView(back)

        val kindTv = TextView(this)
        kindTv.text = if (kind == NotesDb.KIND_CHECK) "Lista" else "Nota"
        kindTv.textSize = 17f
        kindTv.setTypeface(kindTv.typeface, Typeface.BOLD)
        kindTv.setTextColor(Ui.text(this))
        top.addView(kindTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val colorBtn = topBtn("🎨", "Cambiar el color de la nota")
        colorBtn.setOnClickListener { pickColor() }
        top.addView(colorBtn)

        pinBtn = topBtn("📌", "Fijar esta nota arriba en la lista")
        pinBtn.setOnClickListener {
            pinned = !pinned
            pinBtn.alpha = if (pinned) 1f else 0.45f
            toast(if (pinned) "Nota fijada" else "Nota desfijada")
        }
        pinBtn.alpha = if (pinned) 1f else 0.45f
        top.addView(pinBtn)

        // ---- título ----
        titleEdit = EditText(this)
        titleEdit.hint = "Título"
        titleEdit.textSize = 19f
        titleEdit.setTypeface(titleEdit.typeface, Typeface.BOLD)
        titleEdit.setSingleLine(false)
        titleEdit.maxLines = 2
        titleEdit.background = null
        titleEdit.setPadding(dp(16), dp(14), dp(16), dp(4))
        titleEdit.setTextColor(Ui.text(this))
        titleEdit.setHintTextColor(Ui.text3(this))
        root.addView(titleEdit)

        // ---- cuerpo o checklist ----
        if (kind == NotesDb.KIND_TEXT) {
            bodyEdit = EditText(this)
            bodyEdit.hint = "Escribe aquí…"
            bodyEdit.textSize = 15f
            bodyEdit.gravity = Gravity.TOP
            bodyEdit.background = null
            bodyEdit.setPadding(dp(16), dp(8), dp(16), dp(16))
            bodyEdit.setTextColor(Ui.text(this))
            bodyEdit.setHintTextColor(Ui.text3(this))
            root.addView(bodyEdit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            bodyEdit.setText(body)
        } else {
            val scroll = ScrollView(this)
            checkHost = LinearLayout(this)
            checkHost.orientation = LinearLayout.VERTICAL
            scroll.addView(checkHost)
            root.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            val parsed = NotesDb.itemsFromJson(items)
            if (parsed.isEmpty()) addCheckRow("", false)
            else parsed.forEach { addCheckRow(it.t, it.d) }

            val addBtn = Button(this)
            addBtn.text = "＋ Añadir elemento"
            addBtn.textSize = 14f
            addBtn.setTextColor(Ui.accent(this))
            addBtn.background = Ui.borderless(this)
            addBtn.setPadding(dp(16), dp(12), dp(16), dp(16))
            addBtn.setOnClickListener { addCheckRow("", false, focus = true) }
            root.addView(addBtn)
        }
    }

    /** Añade una fila de checklist; focus=true sitúa el cursor al final. */
    private fun addCheckRow(text: String, done: Boolean, focus: Boolean = false) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(8), 0, dp(8), 0)

        val chk = CheckBox(this)
        chk.isChecked = done
        chk.contentDescription = "Elemento completado"
        row.addView(chk)

        val edit = EditText(this)
        edit.hint = "Elemento"
        edit.textSize = 15f
        edit.background = null
        edit.setPadding(dp(8), dp(10), dp(8), dp(10))
        edit.setTextColor(Ui.text(this))
        edit.setHintTextColor(Ui.text3(this))
        edit.setText(text)
        row.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val del = Button(this)
        del.text = "✕"
        del.textSize = 14f
        del.setTextColor(Ui.text3(this))
        del.background = Ui.borderless(this)
        del.minWidth = dp(40)
        del.minimumWidth = dp(40)
        del.contentDescription = "Quitar este elemento"
        row.addView(del)

        // lo hecho se tacha
        fun applyStrike() {
            edit.paintFlags = if (chk.isChecked)
                edit.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            else edit.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        applyStrike()
        chk.setOnClickListener { applyStrike() }

        val r = CheckRow(edit, chk)
        del.setOnClickListener {
            checkRows.remove(r)
            checkHost.removeView(row)
            // nunca dejar la lista sin fila para escribir
            if (checkRows.isEmpty()) addCheckRow("", false, focus = true)
        }
        checkRows.add(r)
        checkHost.addView(row)
        if (focus) {
            edit.requestFocus()
            edit.setSelection(edit.text.length)
        }
    }

    private fun topBtn(label: String, cd: String): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 17f
        b.setTextColor(Ui.text(this))
        b.background = Ui.borderless(this)
        b.minWidth = dp(40)
        b.minimumWidth = dp(40)
        b.contentDescription = cd
        return b
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** Tinta la pantalla con el color de la nota (como ColorNote). */
    private fun retint() {
        val bg = GradientDrawable()
        bg.setColor(NotesActivity.palette(this, color))
        bg.cornerRadius = 0f
        root.background = bg
    }

    // -------------------- color y guardado --------------------

    private fun pickColor() {
        val pal = if (Ui.isDark(this)) NotesActivity.PALETTE_DARK else NotesActivity.PALETTE_LIGHT
        val host = LinearLayout(this)
        host.orientation = LinearLayout.VERTICAL
        host.setPadding(dp(24), dp(24), dp(24), dp(8))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val dlgRef = arrayOfNulls<AlertDialog>(1)
        pal.forEachIndexed { idx, col ->
            val sw = TextView(this)
            sw.text = if (idx == color) "✓" else ""
            sw.gravity = Gravity.CENTER
            sw.setTextColor(if (Ui.isDark(this)) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
            sw.textSize = 16f
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(col)
            bg.setStroke(dp(2), if (idx == color) Ui.accent(this) else android.graphics.Color.TRANSPARENT)
            sw.background = bg
            val lp = LinearLayout.LayoutParams(dp(38), dp(38))
            lp.setMargins(dp(6), 0, dp(6), 0)
            sw.layoutParams = lp
            sw.setOnClickListener {
                color = idx
                retint()
                dlgRef[0]?.dismiss()
            }
            row.addView(sw)
        }
        host.addView(row)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Color de la nota")
            .setView(host)
            .setNegativeButton("Cerrar", null)
            .show()
        dlgRef[0] = dlg
    }

    /** Guarda al salir (como ColorNote). Nota totalmente vacía: descartar. */
    override fun onBackPressed() {
        val title = titleEdit.text.toString().trim()
        if (kind == NotesDb.KIND_TEXT) {
            val body = bodyEdit.text.toString()
            if (title.isEmpty() && body.isBlank()) {
                toast("Nota vacía descartada")
                super.onBackPressed(); return
            }
            if (id > 0) ndb.updateNoteFull(id, title, body, color, "")
            else id = ndb.addNoteFull(title, body, origUrl, color, NotesDb.KIND_TEXT, pinned, "")
            // la fijación se aplica aparte (updateNoteFull no la toca)
            ndb.setPinned(id, pinned)
            toast("Guardada")
        } else {
            val items = NotesDb.itemsToJson(checkRows.map { NotesDb.CheckItem(it.text.text.toString(), it.done.isChecked) })
            val hasContent = title.isNotEmpty() || checkRows.any { it.text.text.isNotBlank() }
            if (!hasContent) {
                toast("Lista vacía descartada")
                super.onBackPressed(); return
            }
            if (id > 0) ndb.updateNoteFull(id, title, "", color, items)
            else id = ndb.addNoteFull(title, "", origUrl, color, NotesDb.KIND_CHECK, pinned, items)
            ndb.setPinned(id, pinned)
            toast("Guardada")
        }
        super.onBackPressed()
    }
}
