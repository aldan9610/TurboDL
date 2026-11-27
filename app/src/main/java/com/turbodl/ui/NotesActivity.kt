package com.turbodl.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.turbodl.R
import com.turbodl.db.NotesDb
import com.turbodl.util.Ui
import java.text.DateFormat
import java.util.Date

/**
 * Notas (4.17): módulo completo estilo ColorNote a pantalla completa,
 * accesible desde Herramientas y desde el menú del navegador.
 *
 *   · Cuadrícula de 2 columnas con tarjetas de color (paleta propia de
 *     la app: pastel en claro, oscuros armonizados en oscuro/AMOLED).
 *   · Tipos: nota de texto y lista de comprobación (checklist).
 *   · Fijadas arriba (📌), búsqueda en vivo, orden por modificación /
 *     creación / título, papelera con restaurar y vaciar.
 *   · Editor a pantalla completa (NoteEditActivity) que guarda al salir.
 *
 * Los datos viven en NotesDb (browser4.db), que está en el sandbox del
 * usuario activo: cada perfil tiene sus propias notas.
 */
class NotesActivity : Activity() {

    private lateinit var ndb: NotesDb
    private lateinit var screen: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var titleTv: TextView
    private lateinit var subTv: TextView
    private lateinit var searchRow: LinearLayout
    private lateinit var searchEdit: EditText
    private lateinit var grid: GridView
    private lateinit var emptyTv: TextView
    private lateinit var trashBar: LinearLayout
    private lateinit var fab: Button

    private var trashMode = false
    private var query = ""
    private var sortMode = 0 // 0 = modificación, 1 = creación, 2 = título
    private var rows = ArrayList<NotesDb.Note>()

    companion object {
        /** Paleta de notas (índice estable en BD). Pastel en claro,
         *  oscuros armonizados en oscuro/AMOLED. Índice 0 = naranja app. */
        val PALETTE_LIGHT = intArrayOf(
            0xFFFFF3E0.toInt(), 0xFFE3F2FD.toInt(), 0xFFE8F5E9.toInt(),
            0xFFFFEBEE.toInt(), 0xFFF3E5F5.toInt(), 0xFFFFF9C4.toInt(),
            0xFFE0F2F1.toInt(), 0xFFF5F5F5.toInt())
        val PALETTE_DARK = intArrayOf(
            0xFF43301E.toInt(), 0xFF26333F.toInt(), 0xFF283B2D.toInt(),
            0xFF432A2A.toInt(), 0xFF382B44.toInt(), 0xFF403C24.toInt(),
            0xFF24403C.toInt(), 0xFF2E2E2E.toInt())

        fun palette(ctx: Context, color: Int): Int {
            val p = if (Ui.isDark(ctx)) PALETTE_DARK else PALETTE_LIGHT
            return p[color.coerceIn(0, p.size - 1)]
        }

        const val EXTRA_ID = "id"
        const val EXTRA_KIND = "kind"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        ndb = NotesDb.get(this)

        // FrameLayout raíz para anclar el FAB sobre el contenido
        screen = FrameLayout(this)
        screen.setBackgroundColor(Ui.bg(this))
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        screen.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildUi()
        setContentView(screen)
        refresh()
        Ui.styleAll(findViewById(android.R.id.content))
    }

    override fun onResume() {
        super.onResume()
        // el editor pudo crear/borrar notas: refrescar al volver
        refresh()
    }

    // -------------------- construcción de la interfaz --------------------

    private fun buildUi() {
        // ---- barra superior ----
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(Ui.bar(this))
        top.setPadding(dp(6), dp(6), dp(6), dp(6))
        root.addView(top, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val back = barBtn("‹", "Volver a Herramientas")
        back.setOnClickListener { finish() }
        top.addView(back)

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titleTv = TextView(this)
        titleTv.text = "Notas"
        titleTv.textSize = 18f
        titleTv.setTypeface(titleTv.typeface, Typeface.BOLD)
        titleTv.setTextColor(Ui.text(this))
        titles.addView(titleTv)
        subTv = TextView(this)
        subTv.textSize = 11f
        subTv.setTextColor(Ui.text3(this))
        titles.addView(subTv)
        titles.setOnClickListener { toggleSearch() } // tocar el título abre buscar
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val searchBtn = barBtn("🔍", "Buscar en las notas")
        searchBtn.setOnClickListener { toggleSearch() }
        top.addView(searchBtn)

        val sortBtn = barBtn("↕", "Ordenar las notas")
        sortBtn.setOnClickListener { pickSort() }
        top.addView(sortBtn)

        val trashBtn = barBtn("🗑", "Papelera")
        trashBtn.setOnClickListener {
            trashMode = !trashMode
            if (trashMode) { searchRow.visibility = View.GONE; searchEdit.setText(""); query = "" }
            refresh()
        }
        top.addView(trashBtn)

        // ---- fila de búsqueda (oculta hasta pulsar 🔍) ----
        searchRow = LinearLayout(this)
        searchRow.orientation = LinearLayout.HORIZONTAL
        searchRow.gravity = Gravity.CENTER_VERTICAL
        searchRow.setBackgroundColor(Ui.bar(this))
        searchRow.setPadding(dp(16), 0, dp(8), dp(6))
        searchRow.visibility = View.GONE
        searchEdit = EditText(this)
        searchEdit.hint = "Buscar por título, texto o elementos…"
        searchEdit.textSize = 13f
        searchEdit.setSingleLine(true)
        searchEdit.background = null
        searchEdit.setTextColor(Ui.text(this))
        searchEdit.setHintTextColor(Ui.text3(this))
        searchRow.addView(searchEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clearBtn = barBtn("✕", "Vaciar la búsqueda")
        clearBtn.setOnClickListener {
            searchEdit.setText("")
            toggleSearch()
        }
        searchRow.addView(clearBtn)
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { }
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                if (trashMode) trashMode = false // escribir saca de la papelera
                refresh()
            }
        })
        root.addView(searchRow)

        // ---- barra de acciones de la papelera ----
        trashBar = LinearLayout(this)
        trashBar.orientation = LinearLayout.HORIZONTAL
        trashBar.setPadding(dp(12), dp(4), dp(12), dp(4))
        trashBar.visibility = View.GONE
        val emptyBtn = Button(this)
        emptyBtn.text = "Vaciar papelera"
        emptyBtn.textSize = 12f
        emptyBtn.setTextColor(0xFFEF5350.toInt())
        emptyBtn.background = Ui.borderless(this)
        emptyBtn.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Vaciar papelera")
                .setMessage("Se borrará definitivamente todo lo que hay en la papelera.")
                .setPositiveButton("Vaciar") { _, _ ->
                    ndb.emptyTrash()
                    toast("Papelera vaciada")
                    refresh()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        trashBar.addView(emptyBtn)
        root.addView(trashBar)

        // ---- cuadrícula de tarjetas (estilo ColorNote) ----
        grid = GridView(this)
        grid.numColumns = 2
        grid.horizontalSpacing = dp(8)
        grid.verticalSpacing = dp(8)
        grid.setPadding(dp(10), dp(10), dp(10), dp(96))
        grid.clipToPadding = false
        grid.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        root.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- estado vacío ----
        emptyTv = TextView(this)
        emptyTv.gravity = Gravity.CENTER
        emptyTv.textSize = 14f
        emptyTv.setTextColor(Ui.text3(this))
        emptyTv.setPadding(dp(30), dp(60), dp(30), dp(60))
        root.addView(emptyTv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- FAB de nueva nota (anclado al FrameLayout raíz) ----
        fab = Button(this)
        fab.text = "+"
        fab.textSize = 26f
        fab.setTextColor(Color.WHITE)
        fab.background = getDrawable(R.drawable.fab_bg)
        fab.stateListAnimator = null
        fab.elevation = dp(6).toFloat()
        fab.contentDescription = "Nueva nota"
        val flp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        flp.gravity = Gravity.BOTTOM or Gravity.END
        flp.setMargins(dp(16), 0, dp(16), dp(16))
        screen.addView(fab, flp)
        fab.setOnClickListener { newNoteDialog() }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun barBtn(label: String, cd: String): Button {
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

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // -------------------- datos y refresco --------------------

    private fun toggleSearch() {
        val show = searchRow.visibility != View.VISIBLE
        searchRow.visibility = if (show) View.VISIBLE else View.GONE
        if (show) searchEdit.requestFocus()
        else { searchEdit.setText(""); query = "" }
        refresh()
    }

    private fun pickSort() {
        val opts = arrayOf("Fecha de modificación", "Fecha de creación", "Título (A-Z)")
        AlertDialog.Builder(this)
            .setTitle("Ordenar notas")
            .setSingleChoiceItems(opts, sortMode) { d, which ->
                sortMode = which
                d.dismiss()
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun refresh() {
        val counts = ndb.counts()
        if (trashMode) {
            titleTv.text = "Papelera"
            subTv.text = "${counts.second} eliminada(s) — pulsa una tarjeta para recuperarla"
            trashBar.visibility = if (counts.second > 0) View.VISIBLE else View.GONE
        } else {
            titleTv.text = "Notas"
            subTv.text = when {
                query.isNotBlank() -> "buscando «$query»"
                counts.first == 1 -> "1 nota"
                else -> "${counts.first} notas"
            }
            trashBar.visibility = View.GONE
        }

        rows = if (trashMode) ndb.trashed() else ndb.visible(query)
        if (!trashMode && query.isBlank()) sortRows(rows)
        grid.adapter = CardsAdapter()

        val empty = rows.isEmpty()
        grid.visibility = if (empty) View.GONE else View.VISIBLE
        emptyTv.visibility = if (empty) View.VISIBLE else View.GONE
        emptyTv.text = when {
            trashMode -> "🗑\n\nLa papelera está vacía"
            query.isNotBlank() -> "🔍\n\nSin resultados para «$query»"
            else -> "📝\n\nSin notas todavía\nPulsa + para crear la primera\n" +
                "(texto o lista de comprobación)"
        }
    }

    /** Reordena en memoria según el criterio elegido; las fijadas SIEMPRE
     *  salen primero (la BD ya las trae primero con orden por defecto:
     *  esta función conserva esa invariante al reordenar). */
    private fun sortRows(list: ArrayList<NotesDb.Note>) {
        val sorted = when (sortMode) {
            1 -> list.sortedWith(compareByDescending<NotesDb.Note> { it.created }.thenByDescending { it.time })
            2 -> list.sortedWith(compareBy<NotesDb.Note> { it.title.lowercase() }.thenByDescending { it.time })
            else -> list.sortedWith(compareByDescending<NotesDb.Note> { it.time })
        }
        list.clear()
        list.addAll(sorted.filter { it.pinned } + sorted.filter { !it.pinned })
    }

    // -------------------- adaptador de tarjetas --------------------

    private inner class CardsAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(p: Int): NotesDb.Note = rows[p]
        override fun getItemId(p: Int): Long = rows[p].id
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val n = getItem(p)
            val card = LinearLayout(this@NotesActivity)
            card.orientation = LinearLayout.VERTICAL
            card.setPadding(dp(12), dp(10), dp(12), dp(10))
            card.minimumHeight = dp(110)
            val bg = GradientDrawable()
            bg.setColor(palette(this@NotesActivity, n.color))
            bg.cornerRadius = dp(10).toFloat()
            bg.setStroke(1, Ui.divider(this@NotesActivity))
            card.background = bg

            // título (+ 📌 si está fijada)
            val tv = TextView(this@NotesActivity)
            tv.text = (if (n.pinned) "📌 " else "") + n.title.ifEmpty { "(sin título)" }
            tv.textSize = 15f
            tv.setTypeface(tv.typeface, Typeface.BOLD)
            tv.setTextColor(Ui.text(this@NotesActivity))
            tv.maxLines = 2
            tv.ellipsize = android.text.TextUtils.TruncateAt.END
            card.addView(tv)

            // vista previa: checklist (progreso + pendientes) o texto
            if (n.kind == NotesDb.KIND_CHECK) {
                val (done, total) = n.checkProgress()
                val pv = TextView(this@NotesActivity)
                pv.text = buildString {
                    append("☑ $done/$total completado")
                    for (it2 in n.checkItems().filter { !it.d }.take(2))
                        append("\n☐ ").append(it2.t.take(30))
                }
                pv.textSize = 12f
                pv.setTextColor(Ui.text2(this@NotesActivity))
                pv.maxLines = 4
                card.addView(pv)
            } else {
                val pv = TextView(this@NotesActivity)
                pv.text = n.body.replace('\n', ' ').take(160)
                pv.textSize = 12f
                pv.setTextColor(Ui.text2(this@NotesActivity))
                pv.maxLines = 4
                pv.ellipsize = android.text.TextUtils.TruncateAt.END
                card.addView(pv)
            }

            val date = TextView(this@NotesActivity)
            val which = if (sortMode == 1 && n.created > 0) n.created else n.time
            date.text = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(Date(which))
            date.textSize = 10f
            date.setTextColor(Ui.text3(this@NotesActivity))
            date.setPadding(0, dp(6), 0, 0)
            card.addView(date)

            card.setOnClickListener {
                if (trashMode) trashCardMenu(n) else openEditor(n)
            }
            card.setOnLongClickListener {
                if (trashMode) trashCardMenu(n) else cardMenu(n)
                true
            }
            return card
        }
    }

    private fun openEditor(n: NotesDb.Note) {
        val i = Intent(this, NoteEditActivity::class.java)
            .putExtra(EXTRA_ID, n.id)
            .putExtra(EXTRA_KIND, n.kind)
        startActivity(i)
    }

    /** Menú de tarjeta (pulsación larga), estilo ColorNote. */
    private fun cardMenu(n: NotesDb.Note) {
        val opts = arrayOf(
            if (n.pinned) "📌 Desfijar" else "📌 Fijar arriba",
            "🎨 Cambiar color",
            "📤 Compartir",
            "📋 Copiar",
            "🗑 Enviar a la papelera")
        AlertDialog.Builder(this)
            .setTitle(n.title.ifEmpty { "(sin título)" })
            .setItems(opts) { d, which ->
                d.dismiss()
                when (which) {
                    0 -> { ndb.setPinned(n.id, !n.pinned); refresh() }
                    1 -> pickColor(n)
                    2 -> shareNote(n)
                    3 -> {
                        val c = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        c.setPrimaryClip(ClipData.newPlainText("nota", notePlainText(n)))
                        toast("Nota copiada")
                    }
                    4 -> {
                        ndb.softDelete(n.id)
                        toast("Enviada a la papelera")
                        refresh()
                    }
                }
            }
            .show()
    }

    /** Menú de tarjeta en la papelera: restaurar o borrar definitivo. */
    private fun trashCardMenu(n: NotesDb.Note) {
        val opts = arrayOf("♻ Restaurar", "🗑 Borrar definitivamente")
        AlertDialog.Builder(this)
            .setTitle(n.title.ifEmpty { "(sin título)" })
            .setItems(opts) { d, which ->
                d.dismiss()
                if (which == 0) {
                    ndb.restore(n.id)
                    toast("Nota restaurada")
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("¿Borrar para siempre «${n.title.ifEmpty { "(sin título)" }}»?")
                        .setPositiveButton("Borrar") { _, _ ->
                            ndb.deleteNote(n.id)
                            refresh()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
                refresh()
            }
            .show()
    }

    /** Selector de color: 8 círculos, el activo con ✓ y borde acento. */
    private fun pickColor(n: NotesDb.Note) {
        val pal = if (Ui.isDark(this)) PALETTE_DARK else PALETTE_LIGHT
        val host = LinearLayout(this)
        host.orientation = LinearLayout.VERTICAL
        host.setPadding(dp(24), dp(24), dp(24), dp(8))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        val dlgRef = arrayOfNulls<AlertDialog>(1)
        pal.forEachIndexed { idx, col ->
            val sw = TextView(this)
            sw.text = if (idx == n.color) "✓" else ""
            sw.gravity = Gravity.CENTER
            sw.setTextColor(if (Ui.isDark(this)) Color.WHITE else Color.BLACK)
            sw.textSize = 16f
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(col)
            bg.setStroke(dp(2), if (idx == n.color) Ui.accent(this) else Color.TRANSPARENT)
            sw.background = bg
            val lp = LinearLayout.LayoutParams(dp(38), dp(38))
            lp.setMargins(dp(6), 0, dp(6), 0)
            sw.layoutParams = lp
            sw.setOnClickListener {
                ndb.setColor(n.id, idx)
                dlgRef[0]?.dismiss()
                refresh()
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

    private fun notePlainText(n: NotesDb.Note): String =
        if (n.kind == NotesDb.KIND_CHECK) {
            buildString {
                if (n.title.isNotEmpty()) append(n.title).append('\n')
                for (it in n.checkItems()) append(if (it.d) "☑ " else "☐ ").append(it.t).append('\n')
            }.trimEnd('\n')
        } else n.body

    private fun shareNote(n: NotesDb.Note) {
        try {
            val i = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, n.title.ifEmpty { "Nota" })
                .putExtra(Intent.EXTRA_TEXT, notePlainText(n))
            startActivity(Intent.createChooser(i, "Compartir nota"))
        } catch (e: Exception) {
            toast("No se pudo compartir: ${e.message}")
        }
    }

    /** FAB: crear nota nueva (texto o checklist). */
    private fun newNoteDialog() {
        val opts = arrayOf("📝 Nota de texto", "☑ Lista de comprobación")
        AlertDialog.Builder(this)
            .setTitle("Nueva nota")
            .setItems(opts) { d, which ->
                d.dismiss()
                val i = Intent(this, NoteEditActivity::class.java)
                    .putExtra(EXTRA_ID, -1L)
                    .putExtra(EXTRA_KIND, if (which == 0) NotesDb.KIND_TEXT else NotesDb.KIND_CHECK)
                startActivity(i)
            }
            .show()
    }
}
