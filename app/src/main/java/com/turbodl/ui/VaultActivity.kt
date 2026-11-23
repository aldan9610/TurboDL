package com.turbodl.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.turbodl.App
import com.turbodl.R
import com.turbodl.db.Db
import com.turbodl.util.Clipboard
import com.turbodl.util.Otp
import com.turbodl.util.Secrets
import com.turbodl.util.Ui
import com.turbodl.util.Users

/**
 * Bóveda de credenciales (4.6, rediseño 4.12) — gestor de contraseñas con
 * códigos OTP (TOTP/HOTP/Steam) para 2FA, accesible desde el navegador y
 * desde Herramientas.
 *
 * ### Rediseño 4.12 (inspirado en gestores de escritorio tipo KeePassXC y
 * en apps de 2FA tipo Google Authenticator, con estilo propio de TurboDL)
 *   1. **Carpetas anidadas** (grupos dentro de grupos, profundidad libre):
 *      árbol en `vault_grp` (Db v5) + columna `grp` en `vault`. Navegación
 *      por niveles con botón subir + ruta de migas de pan; entradas y
 *      carpetas se crean, renombran, mueven (con protección anti-ciclos)
 *      y borran desde el menú ⋮ de cada fila.
 *   2. **Lista vertical**: cada credencial es una tarjeta vertical que
 *      muestra TODOS sus campos a la vista (título, usuario, contraseña,
 *      código OTP, web, notas) — se ve todo de un vistazo, como pidió el
 *      usuario.
 *   3. **Copiado rápido con iconos**: un icono de copiar al lado de cada
 *      campo (usuario, contraseña, código, web). La copia de valores
 *      sensibles se limpia del portapapeles a los 60 s.
 *   4. **Mostrar/ocultar**: la contraseña va enmascarada (••••) con un
 *      icono de ojo para revelarla; el bloque de OTP tiene su propio ojo
 *      (el código no se calcula hasta mostrarlo).
 *   5. **Anillo de tiempo TOTP**: cada código visible lleva un anillo
 *      circular que nace lleno y se va difuminando según pasa el periodo
 *      (30 s por defecto), con los segundos restantes dentro; al llegar
 *      a cero se recalcula el código y el anillo se reinicia, en el ritmo
 *      exacto del TOTP ([Otp.nextStepIn]).
 *
 * ### Optimización de recursos (tip del usuario, 4.6)
 *   - La lista NO descifra nada ni calcula OTP: solo título/usuario.
 *   - El código se calcula al mostrar el bloque y se recalcula solo al
 *     cruzar el límite del periodo (cada 30 s), no cada tick.
 *   - Un ÚNICO handler con tick de 250 ms reparte las actualizaciones a
 *     los bloques visibles (anillo + segundos); se detiene en onPause.
 *   - A diferencia de la 4.6 (una sola OTP abierta), ahora varios bloques
 *     pueden estar visibles a la vez (estilo app de 2FA); el coste es
 *     un HMAC por código visible cada 30 s, despreciable.
 *
 * ### Almacenamiento
 *   - Tabla `vault` (Db v5): + `grp` (carpeta contenedora, 0 = raíz).
 *   - Tabla `vault_grp` (Db v5): carpetas anidadas por `parent`.
 *   - Contraseña y secret OTP cifrados con Secrets (AES-256-GCM,
 *     AndroidKeyStore), descifrados solo bajo demanda.
 */
class VaultActivity : Activity() {

    private val eng get() = App.app.engine

    // ---------- paleta según tema (claro/oscuro/AMOLED) ----------
    private val isDark get() = Ui.isDark(this)
    private val cAccent get() = if (isDark) 0xFFFFB300.toInt() else 0xFF6D00.toInt()
    private val cSec get() = if (isDark) 0xFF9AA0A6.toInt() else 0xFF707070.toInt()
    private val cCard get() = when (Ui.themeMode(this)) {
        2 -> 0xFF141414.toInt(); 1 -> 0xFF1E1E1E.toInt(); else -> 0xFFFFFFFF.toInt()
    }
    private val cStroke get() = when (Ui.themeMode(this)) {
        2 -> 0xFF262626.toInt(); 1 -> 0xFF2E2E2E.toInt(); else -> 0xFFE3E3E3.toInt()
    }
    private val cCode get() = if (isDark) 0xFFA5D6A7.toInt() else 0xFF2E7D32.toInt()
    private val cTrack get() = if (isDark) 0xFF3C3C3C.toInt() else 0xFFD9D9D9.toInt()
    private val cDanger get() = 0xFFE53935.toInt()

    // ---------- estado de navegación ----------
    private var curGrp = 0L            // carpeta actual (0 = raíz)
    private var query = ""             // búsqueda activa ("" = navegación)
    private val groups = ArrayList<Db.VaultGroup>()
    private val items = ArrayList<Item>()

    // ---------- vistas ----------
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var pathView: TextView
    private lateinit var btnUp: ImageView
    private lateinit var searchBox: EditText
    private lateinit var adapter: VaultAdapter

    // ---------- portapapeles con autolimpieza ----------
    private val uiHandler = Handler(Looper.getMainLooper())
    private var clipClearRun: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        title = "🔐 Bóveda de credenciales"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // ---- barra superior: título + añadir entrada + añadir carpeta ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(10), dp(8), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = "🔐 Bóveda"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(iconBtn(R.drawable.ic_key, "Añadir credencial") { editEntry(null) })
        top.addView(iconBtn(R.drawable.ic_new_folder, "Nueva carpeta") { promptFolder(null) })
        root.addView(top, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- buscador (filtra en TODAS las carpetas) ----
        val searchWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(2), dp(14), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpf(24f).toFloat()
                setColor(cCard)
                setStroke(dp(1), cStroke)
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        searchPill.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(cSec)
            val p = dp(6); setPadding(0, 0, p, 0)
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        searchBox = EditText(this).apply {
            hint = "Buscar en toda la bóveda…"
            textSize = 14f
            background = null
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        searchPill.addView(searchBox, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchWrap.addView(searchPill, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(searchWrap)
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                load()
            }
        })

        // ---- migas de pan: subir + ruta actual ----
        val crumbs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(14), dp(6))
            gravity = Gravity.CENTER_VERTICAL
        }
        btnUp = iconBtn(R.drawable.ic_up, "Subir un nivel") {
            if (curGrp != 0L) enterGrp(groupOf(curGrp)?.parent ?: 0L)
        }
        crumbs.addView(btnUp, LinearLayout.LayoutParams(dp(34), dp(34)))
        pathView = TextView(this).apply {
            textSize = 14f
            setTextColor(cAccent)
            setPadding(dp(6), 0, 0, 0)
            // tocar la ruta abre el salto directo a cualquier carpeta superior
            setOnClickListener { showJumpDialog() }
        }
        crumbs.addView(pathView, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(crumbs)

        // ---- lista vertical ----
        list = ListView(this).apply {
            dividerHeight = 0
            setPadding(dp(10), dp(4), dp(10), dp(16))
            clipToPadding = false
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- estado vacío ----
        empty = TextView(this).apply {
            text = "Bóveda vacía.\n\nUsa los iconos 🔑 y 📁 de la barra superior " +
                   "para crear carpetas y credenciales. Puedes pegar el secret OTP " +
                   "(Base32) o un otpauth:// URI para activar el código 2FA."
            textSize = 14f
            setTextColor(cSec)
            setPadding(dp(32), dp(48), dp(32), dp(32))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(empty, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        Ui.styleAll(findViewById(android.R.id.content))

        adapter = VaultAdapter()
        list.adapter = adapter
        load()
    }

    // ================= carga y navegación =================

    private fun load() {
        val user = Users.active(this)
        groups.clear()
        groups.addAll(eng.db.vaultGroups(user))
        items.clear()
        if (query.isNotEmpty()) {
            // búsqueda global: resultados planos con su ruta
            eng.db.vaultSearch(user, query).forEach {
                items.add(Item(entry = it, path = pathOf(it.grp), searching = true))
            }
        } else {
            // carpetas de este nivel primero (estilo árbol), luego entradas
            groups.filter { it.parent == curGrp }.forEach { items.add(Item(folder = it)) }
            eng.db.vaultAll(user, curGrp).forEach {
                items.add(Item(entry = it, path = pathOf(it.grp), searching = false))
            }
        }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        btnUp.isEnabled = curGrp != 0L && query.isEmpty()
        btnUp.alpha = if (btnUp.isEnabled) 1f else 0.35f
        pathView.text = if (query.isNotEmpty()) "Búsqueda: «$query»" else pathOf(curGrp)
        adapter.notifyDataSetChanged()
    }

    private fun enterGrp(g: Long) {
        curGrp = g
        if (searchBox.text.isNotEmpty()) {
            searchBox.setText("")   // dispara load() via TextWatcher
        } else load()
    }

    private fun groupOf(id: Long): Db.VaultGroup? = groups.firstOrNull { it.id == id }

    /** Ruta legible "Raíz / Trabajo / Bancos" (para migas y etiquetas). */
    private fun pathOf(grp: Long): String {
        if (grp == 0L) return "Raíz"
        val parts = ArrayList<String>()
        var cur = groupOf(grp)
        var guard = 0
        while (cur != null && guard++ < 16) {
            parts.add(cur.name)
            cur = groupOf(cur.parent)
        }
        parts.reverse()
        return "Raíz / " + parts.joinToString(" / ")
    }

    /** Salto directo a cualquier carpeta superior (toque en la ruta). */
    private fun showJumpDialog() {
        if (query.isNotEmpty()) return
        // ancestros de la carpeta actual, de arriba a abajo
        val chain = ArrayList<Db.VaultGroup>()
        var cur = groupOf(curGrp)
        var guard = 0
        while (cur != null && guard++ < 16) { chain.add(cur); cur = groupOf(cur.parent) }
        chain.reverse()
        val names = ArrayList<String>()
        names.add("Raíz")
        chain.forEach { names.add(it.name) }
        AlertDialog.Builder(this)
            .setTitle("Ir a…")
            .setItems(names.toTypedArray()) { _, which ->
                enterGrp(if (which == 0) 0L else chain[which - 1].id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ================= acciones de carpetas =================

    /** Crear (null) o renombrar una carpeta. parent solo aplica al crear. */
    private fun promptFolder(existing: Db.VaultGroup?) {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "Nombre de la carpeta"
            setText(existing?.name ?: "")
            setSelection(text.length)
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        wrap.addView(input)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nueva carpeta" else "Renombrar carpeta")
            .setView(wrap)
            .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast("El nombre es obligatorio"); return@setPositiveButton }
                eng.db.saveVaultGroup(existing?.id ?: 0, Users.active(this), name,
                    existing?.parent ?: curGrp)
                load()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** ¿[target] es descendiente (o igual) de [root]? Evita ciclos al mover. */
    private fun isDescendant(root: Long, target: Long): Boolean {
        if (root == target) return true
        var cur = groupOf(target)
        var guard = 0
        while (cur != null && guard++ < 16) {
            if (cur.id == root) return true
            cur = groupOf(cur.parent)
        }
        return false
    }

    /** Lista plana del árbol con sangría, en orden DFS: para los selectores
     *  de destino ("mover a carpeta"). [exclude] se omite (con sus hijos). */
    private fun flatTree(exclude: Long?): Pair<Array<String>, Array<Long>> {
        val names = ArrayList<String>()
        val ids = ArrayList<Long>()
        names.add("Raíz"); ids.add(0L)
        fun walk(parent: Long, depth: Int) {
            groups.filter { it.parent == parent }.sortedBy { it.name.lowercase() }
                .forEach { g ->
                    if (exclude == null || !isDescendant(exclude!!, g.id)) {
                        names.add("   ".repeat(depth) + "📁 " + g.name)
                        ids.add(g.id)
                        walk(g.id, depth + 1)
                    }
                }
        }
        walk(0L, 0)
        return names.toTypedArray() to ids.toTypedArray()
    }

    private fun moveGroupDialog(g: Db.VaultGroup) {
        val (names, ids) = flatTree(g.id)
        AlertDialog.Builder(this)
            .setTitle("Mover «${g.name}» a…")
            .setItems(names) { _, which ->
                val dest = ids[which]
                if (dest == g.parent) { toast("Ya está ahí"); return@setItems }
                eng.db.saveVaultGroup(g.id, g.user, g.name, dest)
                load()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteGroupDialog(g: Db.VaultGroup) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar carpeta")
            .setMessage("«${g.name}» se eliminará. Sus subcarpetas y credenciales " +
                "pasarán a la carpeta superior (no se borra nada).")
            .setPositiveButton("Eliminar") { _, _ ->
                if (curGrp == g.id) enterGrp(g.parent)
                eng.db.deleteVaultGroup(g.id)
                load()
                toast("Carpeta eliminada")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun folderMenu(g: Db.VaultGroup) {
        val opts = arrayOf("📂 Abrir", "✏️ Renombrar", "➕ Subcarpeta", "📁 Mover a…", "🗑️ Eliminar")
        AlertDialog.Builder(this)
            .setTitle("📁 ${g.name}")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> enterGrp(g.id)
                    1 -> promptFolder(g)
                    2 -> promptSubFolder(g)
                    3 -> moveGroupDialog(g)
                    4 -> deleteGroupDialog(g)
                }
            }
            .show()
    }

    private fun promptSubFolder(parent: Db.VaultGroup) {
        val input = EditText(this).apply {
            setSingleLine(true); hint = "Nombre de la subcarpeta"
        }
        val wrap = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        wrap.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Subcarpeta de «${parent.name}»")
            .setView(wrap)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { toast("El nombre es obligatorio"); return@setPositiveButton }
                eng.db.saveVaultGroup(0, Users.active(this), name, parent.id)
                load()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ================= acciones de entradas =================

    private fun entryMenu(row: Db.VaultRow, path: String) {
        val opts = arrayOf("✏️ Editar", "📁 Mover a…", "🗑️ Eliminar")
        AlertDialog.Builder(this)
            .setTitle("🔑 ${row.title}")
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> editEntry(row)
                    1 -> moveEntryDialog(row)
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Eliminar credencial")
                        .setMessage("¿Eliminar «${row.title}»? No se puede deshacer.")
                        .setPositiveButton("Eliminar") { _, _ ->
                            eng.db.deleteVault(row.id)
                            load()
                            toast("Credencial eliminada")
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            .show()
    }

    private fun moveEntryDialog(row: Db.VaultRow) {
        val (names, ids) = flatTree(null)
        AlertDialog.Builder(this)
            .setTitle("Mover «${row.title}» a…")
            .setItems(names) { _, which ->
                if (ids[which] == row.grp) { toast("Ya está ahí"); return@setItems }
                eng.db.vaultMove(row.id, ids[which])
                load()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /**
     * Diálogo de alta/edición de una entrada (igual que en 4.6, con la
     * carpeta destino mostrada). El secret OTP y la contraseña se guardan
     * cifrados con Secrets.
     */
    private fun editEntry(existing: Db.VaultRow?) {
        val ctx = this
        val scroll = ScrollView(this)
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        fun field(label: String, hint: String, value: String,
                  password: Boolean = false): EditText {
            lay.addView(TextView(ctx).apply {
                text = label; textSize = 13f; setTextColor(cSec)
                setPadding(0, dp(8), 0, dp(2))
            })
            return EditText(ctx).apply {
                setSingleLine(true); this.hint = hint
                setText(value)
                if (password)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                lay.addView(this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        // carpeta destino (nueva entrada: la carpeta actual; edición: la suya)
        val destGrp = existing?.grp ?: curGrp
        lay.addView(TextView(ctx).apply {
            text = "Se guardará en: ${pathOf(destGrp)}  (muévela luego con ⋮ → Mover)"
            textSize = 12f; setTextColor(cAccent)
            setPadding(0, dp(2), 0, dp(2))
        })

        val etTitle = field("Título *", "p. ej. Gmail", existing?.title ?: "")
        val etUser = field("Usuario / email", "tu@correo", existing?.username ?: "")
        val etPass = field("Contraseña", "•••••• (dejar vacío si no aplica)",
            existing?.let { Secrets.decrypt(it.passwordEnc) ?: "" } ?: "", password = true)
        val etUrl = field("Sitio web (opcional)", "https://...", existing?.url ?: "")

        // --- OTP ---
        lay.addView(TextView(ctx).apply {
            text = "Doble factor (OTP) — opcional"
            textSize = 14f; setPadding(0, dp(16), 0, dp(4))
            setTypeface(typeface, Typeface.BOLD)
        })
        lay.addView(TextView(ctx).apply {
            text = "Tipo de código"; textSize = 13f; setTextColor(cSec)
        })
        val optKinds = arrayOf("Ninguno", "TOTP (tiempo, 30s)", "HOTP (contador)", "Steam Guard")
        val spinKind = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx,
                android.R.layout.simple_spinner_item, optKinds)
            val cur = existing?.otpCfgEnc?.let {
                val c = Otp.Cfg.parse(it)
                when {
                    c == null -> 0
                    c.steam -> 3
                    c.kind == Otp.Kind.HOTP -> 2
                    else -> 1
                }
            } ?: 0
            setSelection(cur)
        }
        lay.addView(spinKind, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        lay.addView(TextView(ctx).apply {
            text = "Algoritmo HMAC"; textSize = 13f; setTextColor(cSec)
            setPadding(0, dp(8), 0, dp(2))
        })
        val optAlgo = arrayOf("SHA1 (estándar)", "SHA256", "SHA512")
        val spinAlgo = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx,
                android.R.layout.simple_spinner_item, optAlgo)
            val cur = existing?.otpCfgEnc?.let {
                Otp.Cfg.parse(it)?.algo?.ordinal ?: 0
            } ?: 0
            setSelection(cur)
        }
        lay.addView(spinAlgo, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        lay.addView(TextView(ctx).apply {
            text = "Dígitos (6 por defecto; 8 para algunos servicios)"; textSize = 13f
            setTextColor(cSec); setPadding(0, dp(8), 0, dp(2))
        })
        val optDigits = arrayOf("6", "7", "8")
        val spinDigits = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx,
                android.R.layout.simple_spinner_item, optDigits)
            val cur = existing?.otpCfgEnc?.let {
                val d = Otp.Cfg.parse(it)?.digits ?: 6
                when (d) { 7 -> 1; 8 -> 2; else -> 0 }
            } ?: 0
            setSelection(cur)
        }
        lay.addView(spinDigits, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val etOtp = field("Secret OTP (Base32, o pega otpauth://...)",
            "JBSWY3DPEHPK3PXP  /  otpauth://totp/...",
            existing?.let {
                Secrets.decrypt(it.otpKeyEnc) ?: ""
            } ?: "")
        etOtp.minLines = 2; etOtp.setSingleLine(false)

        lay.addView(TextView(ctx).apply {
            text = "Notas (texto plano — no guardes secretos aquí)"
            textSize = 13f; setTextColor(cSec); setPadding(0, dp(8), 0, dp(2))
        })
        val etNotes = EditText(ctx).apply {
            hint = "notas libres"
            setText(existing?.notes ?: "")
            minLines = 2; setSingleLine(false)
        }
        lay.addView(etNotes, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scroll.addView(lay)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nueva credencial" else "Editar credencial")
            .setView(scroll)
            .setPositiveButton("Guardar") { _, _ ->
                val t = etTitle.text.toString().trim()
                if (t.isEmpty()) { toast("El título es obligatorio"); return@setPositiveButton }
                val u = etUser.text.toString().trim()
                val pw = etPass.text.toString()
                val url = etUrl.text.toString().trim()
                val notes = etNotes.text.toString()

                // Resolver OTP: otpauth:// URI → cfg+secret; si no, spinners.
                var cfg: Otp.Cfg? = null
                var secret: String = ""
                val rawOtp = etOtp.text.toString().trim()
                if (rawOtp.isNotEmpty()) {
                    val parsed = Otp.parseOtpAuthUri(rawOtp)
                    if (parsed != null) {
                        cfg = parsed.first; secret = parsed.second
                        if (spinKind.selectedItemPosition == 3) cfg = cfg!!.copy(steam = true)
                        if (cfg!!.kind != Otp.Kind.HOTP && spinKind.selectedItemPosition == 2)
                            cfg = cfg!!.copy(kind = Otp.Kind.HOTP)
                    } else {
                        val kindSel = spinKind.selectedItemPosition
                        cfg = when (kindSel) {
                            1 -> Otp.Cfg(kind = Otp.Kind.TOTP,
                                algo = Otp.Algo.values()[spinAlgo.selectedItemPosition],
                                digits = optDigits[spinDigits.selectedItemPosition].toInt())
                            2 -> Otp.Cfg(kind = Otp.Kind.HOTP,
                                algo = Otp.Algo.values()[spinAlgo.selectedItemPosition],
                                digits = optDigits[spinDigits.selectedItemPosition].toInt())
                            3 -> Otp.Cfg(kind = Otp.Kind.TOTP,
                                algo = Otp.Algo.values()[spinAlgo.selectedItemPosition],
                                digits = 5, steam = true)
                            else -> null
                        }
                        secret = rawOtp
                        if (cfg == null) toast("Tipo = Ninguno: el secret no se usará")
                    }
                    if (cfg != null && !Otp.isValidSecret(secret)) {
                        toast("Secret OTP inválido (debe ser Base32 o tener prefijo hex:/plain:)")
                        return@setPositiveButton
                    }
                }

                val uid = Users.active(this)
                val pwEnc = if (pw.isNotEmpty()) Secrets.encrypt(pw) else ""
                val otpCfgEnc = cfg?.encode() ?: ""
                val otpKeyEnc = if (cfg != null && secret.isNotEmpty())
                    Secrets.encrypt(secret) else ""
                eng.db.saveVault(existing?.id ?: 0, uid, t, u, pwEnc, url,
                    otpCfgEnc, otpKeyEnc, notes, destGrp)
                load()
                toast(if (existing == null) "Credencial guardada" else "Cambios guardados")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ================= portapapeles con autolimpieza =================

    /**
     * Copia un valor al portapapeles y programa su limpieza a los 60 s
     * (como hacen los gestores de contraseñas serios): si a los 60 s el
     * portapapeles sigue conteniendo una copia nuestra (etiqueta
     * "TurboDL·…"), se sustituye por una vacía.
     */
    private fun copySecret(label: String, value: String) {
        Clipboard.copy(this, "TurboDL·$label", value)
        toast("$label copiada — se limpia en 60 s")
        clipClearRun?.let { uiHandler.removeCallbacks(it) }
        clipClearRun = Runnable {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = cm.primaryClip
                val lbl = clip?.description?.label?.toString() ?: ""
                if (lbl.startsWith("TurboDL·")) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                }
            } catch (e: Exception) { }
        }
        uiHandler.postDelayed(clipClearRun!!, 60_000L)
    }

    // ================= utilidades =================

    override fun onPause() {
        super.onPause()
        adapter.stopTick()          // sin timers zombis al salir
    }

    override fun onResume() {
        super.onResume()
        adapter.refreshVisibleOtps()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    /** Botón-icono cuadrado con tinte del tema. */
    private fun iconBtn(drawable: Int, desc: String, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(drawable)
            setColorFilter(cSec)
            contentDescription = desc
            background = getDrawable(android.R.drawable.list_selector_background)
            val p = dp(6); setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }

    // ================= adapter: carpetas + tarjetas de credencial =================

    /**
     * Fila del adaptador: o carpeta o entrada (dos viewTypes). Las
     * tarjetas muestran todos los campos en vertical con iconos de acción.
     */
    private class Item(val folder: Db.VaultGroup? = null,
                       val entry: Db.VaultRow? = null,
                       val path: String = "",
                       val searching: Boolean = false)

    private inner class VaultAdapter : BaseAdapter() {

        private val handler = Handler(Looper.getMainLooper())
        private val visibleOtps = ArrayList<EntryHolder>()
        private var ticking = false

        private val tickRun = object : Runnable {
            override fun run() {
                // un tick reparte el avance del anillo a todos los bloques
                // OTP visibles; los códigos se recalculan solo al cruzar
                // el límite del periodo (no cada tick).
                val snapshot = visibleOtps.toList()
                for (h in snapshot) h.onTick()
                if (visibleOtps.isNotEmpty()) {
                    handler.postDelayed(this, 250L)
                } else ticking = false
            }
        }

        private fun ensureTick() {
            if (!ticking && visibleOtps.isNotEmpty()) {
                ticking = true
                handler.postDelayed(tickRun, 250L)
            }
        }

        /** Reanuda el tick y refresca los códigos (onResume tras pausa). */
        fun refreshVisibleOtps() {
            val snapshot = visibleOtps.toList()
            for (h in snapshot) h.refreshCode()
            ensureTick()
        }

        /** Detiene el tick (onPause) SIN cerrar los bloques visibles. */
        fun stopTick() {
            handler.removeCallbacks(tickRun)
            ticking = false
        }

        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].folder?.id ?: items[p].entry?.id ?: -1
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (items[p].folder != null) 0 else 1

        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val it = items[pos]
            return if (it.folder != null) {
                val v = (cv?.tag as? FolderHolder)?.root ?: buildFolderRow()
                (v.tag as FolderHolder).bind(it.folder)
                v
            } else {
                val v = (cv?.tag as? EntryHolder)?.root ?: buildEntryRow()
                (v.tag as EntryHolder).bind(it.entry!!, it.path, it.searching)
                v
            }
        }

        // ---------------- fila de carpeta ----------------

        private fun buildFolderRow(): View {
            val ctx = this@VaultActivity
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpf(12f), dpf(8f), dpf(8f), dpf(8f))
                background = GradientDrawable().apply {
                    cornerRadius = dpf(14f).toFloat()
                    setColor(cCard)
                    setStroke(dp(1), cStroke)
                }
            }
            val icon = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_folder)
                setColorFilter(cAccent)
                val p = dp(4); setPadding(0, 0, p, 0)
            }
            root.addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(ctx).apply {
                textSize = 15f; setTypeface(typeface, Typeface.BOLD)
            }
            val sub = TextView(ctx).apply { textSize = 12f; setTextColor(cSec) }
            texts.addView(name)
            texts.addView(sub)
            root.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val more = iconBtn(R.drawable.ic_more, "Opciones de carpeta") {
                (root.tag as FolderHolder).folder?.let { folderMenu(it) }
            }
            root.addView(more, LinearLayout.LayoutParams(dp(34), dp(34)))
            root.setOnClickListener { (root.tag as FolderHolder).folder?.let { enterGrp(it.id) } }
            val h = FolderHolder(root, name, sub)
            root.tag = h
            return root
        }

        private inner class FolderHolder(
            val root: View, val name: TextView, val sub: TextView
        ) {
            var folder: Db.VaultGroup? = null
            fun bind(g: Db.VaultGroup) {
                folder = g
                name.text = g.name
                val n = eng.db.vaultCountByGroup(Users.active(this@VaultActivity))[g.id] ?: 0
                val s = eng.db.vaultSubCount(Users.active(this@VaultActivity))[g.id] ?: 0
                sub.text = when {
                    n == 0 && s == 0 -> "vacía"
                    s == 0 -> "$n ${if (n == 1) "credencial" else "credenciales"}"
                    else -> "$n ${if (n == 1) "credencial" else "credenciales"} · $s subcarpeta${if (s == 1) "" else "s"}"
                }
            }
        }

        // ---------------- tarjeta de credencial ----------------

        private fun buildEntryRow(): View {
            val ctx = this@VaultActivity
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpf(12f), dpf(10f), dpf(12f), dpf(10f))
                background = GradientDrawable().apply {
                    cornerRadius = dpf(14f).toFloat()
                    setColor(cCard)
                    setStroke(dp(1), cStroke)
                }
            }
            fun row(): LinearLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            fun lineIcon(res: Int, tint: Int): ImageView = ImageView(ctx).apply {
                setImageResource(res); setColorFilter(tint)
                val p = dp(5); setPadding(0, 0, p, 0)
            }
            fun actionIcon(res: Int, desc: String, tint: Int = cSec): ImageView =
                ImageView(ctx).apply {
                    setImageResource(res); setColorFilter(tint)
                    contentDescription = desc
                    background = getDrawable(android.R.drawable.list_selector_background)
                    val p = dp(4); setPadding(p, p, p, p)
                }

            // --- r1: avatar + título + etiqueta de carpeta + ⋮ ---
            val r1 = row()
            val avatar = TextView(ctx).apply {
                gravity = Gravity.CENTER
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpf(10f).toFloat()
                    setColor(cAccent)
                }
            }
            r1.addView(avatar, LinearLayout.LayoutParams(dp(34), dp(34)))
            val titleCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val title = TextView(ctx).apply {
                textSize = 15f; setTypeface(typeface, Typeface.BOLD)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val grpTag = TextView(ctx).apply {
                textSize = 11f; setTextColor(cSec); visibility = View.GONE
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            titleCol.addView(title)
            titleCol.addView(grpTag)
            r1.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val more = actionIcon(R.drawable.ic_more, "Opciones")
            r1.addView(more, LinearLayout.LayoutParams(dp(30), dp(30)))
            card.addView(r1)

            // --- r2: usuario + copiar ---
            val r2 = row()
            r2.addView(lineIcon(R.drawable.ic_person, cSec), LinearLayout.LayoutParams(dp(20), dp(20)))
            val user = TextView(ctx).apply {
                textSize = 13f; setTextColor(cSec)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            r2.addView(user, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val userCopy = actionIcon(R.drawable.ic_copy, "Copiar usuario")
            r2.addView(userCopy, LinearLayout.LayoutParams(dp(30), dp(30)))
            card.addView(r2)

            // --- r3: contraseña (enmascarada) + ojo + copiar ---
            val r3 = row()
            r3.addView(lineIcon(R.drawable.ic_key, cSec), LinearLayout.LayoutParams(dp(20), dp(20)))
            val pass = TextView(ctx).apply {
                textSize = 14f
                typeface = Typeface.MONOSPACE
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            r3.addView(pass, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val passEye = actionIcon(R.drawable.ic_eye_off, "Mostrar contraseña")
            r3.addView(passEye, LinearLayout.LayoutParams(dp(30), dp(30)))
            val passCopy = actionIcon(R.drawable.ic_copy, "Copiar contraseña")
            r3.addView(passCopy, LinearLayout.LayoutParams(dp(30), dp(30)))
            card.addView(r3)

            // --- r4: cabecera OTP ("Código 2FA" + ojo) + bloque desplegable ---
            val r4head = row()
            r4head.addView(lineIcon(R.drawable.ic_eye, cAccent), LinearLayout.LayoutParams(dp(20), dp(20)))
            val otpLabel = TextView(ctx).apply {
                text = "Código 2FA"
                textSize = 12f; setTextColor(cSec)
            }
            r4head.addView(otpLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val otpEye = actionIcon(R.drawable.ic_eye, "Mostrar código 2FA")
            r4head.addView(otpEye, LinearLayout.LayoutParams(dp(30), dp(30)))
            card.addView(r4head)

            val otpBlock = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(26), dp(2), 0, dp(2))
                visibility = View.GONE
            }
            val code = TextView(ctx).apply {
                textSize = 24f
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cCode)
                letterSpacing = 0.08f
            }
            otpBlock.addView(code, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val ring = TimerRingView(ctx)
            otpBlock.addView(ring, LinearLayout.LayoutParams(dp(38), dp(38)))
            val otpCopy = actionIcon(R.drawable.ic_copy, "Copiar código")
            otpBlock.addView(otpCopy, LinearLayout.LayoutParams(dp(30), dp(30)))
            card.addView(otpBlock)

            val hotpNote = TextView(ctx).apply {
                textSize = 11f; setTextColor(cSec)
                setPadding(dp(26), 0, 0, dp(2))
                visibility = View.GONE
            }
            card.addView(hotpNote)

            // --- r5: web + copiar ---
            val r5 = row()
            r5.addView(lineIcon(R.drawable.ic_globe, cSec), LinearLayout.LayoutParams(dp(20), dp(20)))
            val url = TextView(ctx).apply {
                textSize = 13f; setTextColor(cSec)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            r5.addView(url, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val urlCopy = actionIcon(R.drawable.ic_copy, "Copiar web")
            r5.addView(urlCopy, LinearLayout.LayoutParams(dp(30), dp(30)))
            card.addView(r5)

            // --- r6: notas ---
            val r6 = row()
            r6.addView(lineIcon(R.drawable.ic_note, cSec), LinearLayout.LayoutParams(dp(20), dp(20)))
            val notes = TextView(ctx).apply {
                textSize = 12f; setTextColor(cSec)
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            r6.addView(notes, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(r6)

            val h = EntryHolder(card, avatar, title, grpTag, user, userCopy,
                pass, passEye, passCopy, otpEye, otpBlock, code, ring, otpCopy,
                hotpNote, url, urlCopy, notes)
            card.tag = h

            // listeners una sola vez (el holder se re-vincula al reciclar)
            more.setOnClickListener { h.row?.let { entryMenu(it, h.path) } }
            userCopy.setOnClickListener {
                val u = h.row?.username ?: ""
                if (u.isNotEmpty()) copySecret("usuario", u) else toast("Sin usuario")
            }
            passEye.setOnClickListener { h.togglePass() }
            passCopy.setOnClickListener {
                val pw = Secrets.decrypt(h.row?.passwordEnc ?: "")
                if (pw.isNullOrEmpty()) toast("Sin contraseña guardada")
                else copySecret("contraseña", pw)
            }
            otpEye.setOnClickListener { h.toggleOtp() }
            otpCopy.setOnClickListener {
                val c = h.code
                if (c.isNotEmpty()) copySecret("código", c)
            }
            urlCopy.setOnClickListener {
                val u = h.row?.url ?: ""
                if (u.isNotEmpty()) copySecret("web", u) else toast("Sin web guardada")
            }
            card.setOnClickListener { h.row?.let { editEntry(it) } }
            return card
        }

        private inner class EntryHolder(
            val root: View,
            val avatar: TextView, val title: TextView, val grpTag: TextView,
            val user: TextView, val userCopy: ImageView,
            val pass: TextView, val passEye: ImageView, val passCopy: ImageView,
            val otpEye: ImageView, val otpBlock: LinearLayout,
            val codeView: TextView, val ring: TimerRingView, val otpCopy: ImageView,
            val hotpNote: TextView,
            val url: TextView, val urlCopy: ImageView, val notes: TextView
        ) {
            var row: Db.VaultRow? = null
            var path: String = ""
            private var boundId = -1L
            // estado efímero por tarjeta (se limpia al re-vincular)
            private var passShown = false
            private var passCache: String? = null
            private var otpShown = false
            private var otpCounter = -1L
            private var hotpCounter = -1L
            var code: String = ""
                private set

            fun bind(r: Db.VaultRow, pathTag: String, searching: Boolean) {
                if (boundId != r.id) unbindEphemeral()
                boundId = r.id
                row = r
                path = pathTag
                title.text = r.title
                avatar.text = r.title.trim().take(1).uppercase().ifEmpty { "·" }
                grpTag.visibility = if (searching && pathTag.isNotEmpty()) View.VISIBLE else View.GONE
                grpTag.text = pathTag
                if (r.username.isNotEmpty()) {
                    user.text = r.username; userCopy.visibility = View.VISIBLE
                } else {
                    user.text = "sin usuario"; userCopy.visibility = View.GONE
                }
                val hasPw = r.passwordEnc.isNotEmpty()
                pass.visibility = if (hasPw) View.VISIBLE else View.GONE
                passEye.visibility = if (hasPw) View.VISIBLE else View.GONE
                passCopy.visibility = if (hasPw) View.VISIBLE else View.GONE
                if (hasPw) paintPass()
                // cabecera OTP siempre visible si hay OTP configurado
                otpEye.visibility = if (r.hasOtp()) View.VISIBLE else View.GONE
                otpLabelVisibility()
                if (r.hasOtp() && otpShown) {
                    otpBlock.visibility = View.VISIBLE
                    paintOtpHead(true)
                    refreshCode()
                    if (!visibleOtps.contains(this)) visibleOtps.add(this)
                    ensureTick()
                } else {
                    otpBlock.visibility = View.GONE
                    paintOtpHead(false)
                }
                if (r.url.isNotEmpty()) {
                    url.text = r.url; urlCopy.visibility = View.VISIBLE
                } else {
                    url.text = ""; urlCopy.visibility = View.GONE
                }
                if (r.notes.isNotBlank()) {
                    notes.text = r.notes; notes.visibility = View.VISIBLE
                } else notes.visibility = View.GONE
            }

            private fun otpLabelVisibility() {
                val has = row?.hasOtp() == true
                (otpEye.parent as View).visibility = if (has) View.VISIBLE else View.GONE
            }

            private fun unbindEphemeral() {
                // la tarjeta se recicla para OTRA credencial: borrar estado
                passShown = false; passCache = null
                if (otpShown) {
                    otpShown = false
                    visibleOtps.remove(this)
                }
                otpCounter = -1; hotpCounter = -1; code = ""
            }

            private fun paintPass() {
                if (!passShown) {
                    pass.text = "••••••••"
                    passEye.setImageResource(R.drawable.ic_eye_off)
                } else {
                    val pv = passCache ?: Secrets.decrypt(row?.passwordEnc ?: "") ?: ""
                    passCache = pv
                    pass.text = pv
                    passEye.setImageResource(R.drawable.ic_eye)
                }
            }

            fun togglePass() {
                passShown = !passShown
                paintPass()
            }

            private fun paintOtpHead(shown: Boolean) {
                otpEye.setImageResource(if (shown) R.drawable.ic_eye_off else R.drawable.ic_eye)
                otpEye.contentDescription =
                    if (shown) "Ocultar código 2FA" else "Mostrar código 2FA"
            }

            fun toggleOtp() {
                val r = row ?: return
                if (!r.hasOtp()) { toast("Esta entrada no tiene OTP"); return }
                otpShown = !otpShown
                if (otpShown) {
                    otpBlock.visibility = View.VISIBLE
                    if (!visibleOtps.contains(this)) visibleOtps.add(this)
                    refreshCode()
                    ensureTick()
                } else {
                    otpBlock.visibility = View.GONE
                    visibleOtps.remove(this)
                }
                paintOtpHead(otpShown)
            }

            /** (Re)calcula el código ahora: al mostrar, al reanudar tras
             *  pausa y al cruzar el límite del periodo. */
            fun refreshCode() {
                val r = row ?: return
                if (!otpShown || !r.hasOtp()) return
                val secret = Secrets.decrypt(r.otpKeyEnc)
                val cfg = Otp.Cfg.parse(r.otpCfgEnc)
                if (secret.isNullOrEmpty() || cfg == null) {
                    codeView.text = "—"; code = ""; return
                }
                if (cfg.kind == Otp.Kind.HOTP) {
                    // HOTP: cada REVELACIÓN consume un código (contador+1),
                    // igual que en la 4.6. Sin anillo ni timer.
                    if (hotpCounter < 0) {
                        hotpCounter = eng.db.vaultNextCounter(r.id).coerceAtLeast(0)
                    }
                    code = Otp.computeOtp(secret, cfg.copy(counter = hotpCounter)) ?: "—"
                    codeView.text = code
                    ring.visibility = View.GONE
                    hotpNote.visibility = View.VISIBLE
                    hotpNote.text = "HOTP (contador): siguiente código al volver a mostrar"
                } else {
                    ring.visibility = View.VISIBLE
                    hotpNote.visibility = View.GONE
                    val now = System.currentTimeMillis() / 1000L
                    val period = cfg.period.coerceAtLeast(1)
                    val counter = now / period
                    if (counter != otpCounter) {
                        otpCounter = counter
                        code = Otp.computeOtp(secret, cfg) ?: "—"
                        codeView.text = code
                    }
                }
            }

            /** Tick de 250 ms: solo avanza el anillo; el código se recalcula
             *  aquí si el tick detectó el cruce del límite del periodo. */
            fun onTick() {
                val r = row ?: return
                if (!otpShown || !r.hasOtp()) return
                val cfg = Otp.Cfg.parse(r.otpCfgEnc) ?: return
                if (cfg.kind != Otp.Kind.TOTP) return
                val period = cfg.period.coerceAtLeast(1)
                val now = System.currentTimeMillis() / 1000L
                val counter = now / period
                if (counter != otpCounter) {
                    // límite cruzado: nuevo código
                    refreshCode()
                }
                val secsLeft = (period - (now % period)).toInt()
                ring.setProgress(secsLeft, period)
            }
        }
    }

    /** Anillo de tiempo del código 2FA: nace lleno y se difumina conforme
     *  pasa el periodo; los segundos restantes van dentro. Al llegar a 0
     *  se reinicia (el código ya se recalculó en ese cruce). */
    private class TimerRingView(ctx: Context) : View(ctx) {

        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        private val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        private val isDark = Ui.isDark(ctx)
        private val cAccent = if (isDark) 0xFFFFB300.toInt() else 0xFF6D00.toInt()
        private val cTrack = if (isDark) 0xFF3C3C3C.toInt() else 0xFFD9D9D9.toInt()
        private val cTxt = if (isDark) 0xFFE0E0E0.toInt() else 0xFF444444.toInt()
        private val cWarn = 0xFFE53935.toInt()

        var seconds = 30; private set
        private var period = 30

        init {
            track.color = cTrack
            track.strokeWidth = 4f
            prog.color = cAccent
            prog.strokeWidth = 4f
            num.color = cTxt
            num.textSize = 12f
        }

        /** @param secs segundos restantes; @param total periodo completo. */
        fun setProgress(secs: Int, total: Int) {
            seconds = secs.coerceIn(0, total)
            period = total.coerceAtLeast(1)
            // urgencia: rojo en los últimos 5 s (como los apps de 2FA)
            prog.color = if (seconds <= 5) cWarn else cAccent
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val stroke = w * 0.12f
            track.strokeWidth = stroke
            prog.strokeWidth = stroke
            num.textSize = w * 0.40f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val stroke = track.strokeWidth
            val inset = stroke / 2f + 1f
            val rect = android.graphics.RectF(inset, inset, width - inset, height - inset)
            // pista completa
            canvas.drawOval(rect, track)
            // arco restante: nace lleno (360°) y se difumina al vaciarse;
            // también baja ligeramente la opacidad para el efecto de desvanecer
            val fraction = seconds / period.toFloat()
            val sweep = 360f * fraction
            prog.alpha = ((130 + 125 * fraction)).toInt().coerceIn(0, 255)
            if (sweep > 0.5f) {
                canvas.drawArc(rect, -90f, sweep, false, prog)
            }
            // segundos restantes dentro del anillo
            val y = height / 2f - (num.descent() + num.ascent()) / 2f
            canvas.drawText(seconds.toString(), width / 2f, y, num)
        }
    }
}
