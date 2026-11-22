package com.turbodl.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
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
 * Bóveda de credenciales (4.6) — gestor de contraseñas estilo KeePassXC
 * con generación de códigos OTP (TOTP/HOTP) para 2FA, accesible desde
 * el navegador y desde Herramientas.
 *
 * ### Optimización de recursos (tip del usuario)
 * El usuario pidió explícitamente: *"no generar estos códigos siempre
 * sino cuando se entre a la interfaz que los muestra, no vaya a ser
 * que consuman muchos recursos computacionales en caso de haber muchas
 * credenciales"*.
 *
 * Por eso:
 *   1. La **lista** de entradas **no** descifra nada ni calcula OTP. Solo
 *      muestra título/usuario (texto plano en BD). El descifrado y el
 *      HMAC solo ocurren al pulsar **"Ver código"** en una entrada.
 *   2. Cuando se muestra un código, se calcula **una vez** y se
 *      reprograma **un único** [Handler.postDelayed] en el límite del
 *      step siguiente (cada 30s por defecto), no cada tick. Entre
 *      recalculadas no hay CPU gastada.
 *   3. Al [onPause], reciclar el [ViewHolder] o cerrar la vista de OTP,
 *      el callback se cancela ([OtpHolder.stop]). No quedan timers
 *      zombis recorriendo la bóveda.
 *   4. Solo una entrada muestra su OTP a la vez (la última abierta).
 *
 * ### Almacenamiento
 *   - Tabla `vault` (Db v4): título, usuario, contraseña (cifrada con
 *     Secrets), url, config OTP (serializada, no secreto), secret OTP
 *     (cifrado), notas. Todo bajo el usuario activo del sandbox.
 *   - La contraseña y el secret OTP se descifran **solo bajo demanda**.
 */
class VaultActivity : Activity() {

    private val eng get() = App.app.engine
    private lateinit var root: LinearLayout
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var adapter: VaultAdapter
    private val rows = ArrayList<Db.VaultRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        title = "🔐 Bóveda de credenciales"
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setPadding(0, dp(8), 0, 0)

        // Barra superior: botón añadir + info
        val bar = LinearLayout(this).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            orientation = LinearLayout.HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "Bóveda (KeePassXC + OTP)"
            textSize = 18f
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
        })
        val add = Button(this).apply {
            text = "+ Añadir"
            setOnClickListener { editEntry(null) }
        }
        bar.addView(add)
        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        list = ListView(this).apply {
            dividerHeight = 1
        }
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        empty = TextView(this).apply {
            text = "La bóveda está vacía.\n\nPulsa «Añadir» para guardar tu primera " +
                   "credencial. Puedes pegar el secret OTP (Base32) o un otpauth:// URI " +
                   "completo para que la app genere los códigos 2FA."
            textSize = 14f
            setTextColor(0xFF888888.toInt())
            setPadding(dp(40), dp(40), dp(40), dp(40))
            visibility = View.GONE
        }
        root.addView(empty, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(root) })
        Ui.styleAll(findViewById(android.R.id.content))

        adapter = VaultAdapter(this, rows) { editEntry(it) }
        list.adapter = adapter
        load()
    }

    private fun load() {
        rows.clear()
        rows.addAll(eng.db.vaultAll(Users.active(this)))
        adapter.notifyDataSetChanged()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        super.onPause()
        // Optimización: detener cualquier recálculo de OTP en curso al
        // salir de la actividad (no dejamos timers zombis).
        adapter.stopAllOtp()
    }

    // ---------------- diálogo añadir/editar ----------------

    /**
     * Diálogo de alta/edición de una entrada. Si [existing] != null es
     * edición; si es null es alta. El secret OTP y la contraseña se
     * guardan cifrados con Secrets.
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
                text = label; textSize = 13f; setTextColor(0xFF888888.toInt())
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

        val etTitle = field("Título *", "p. ej. Gmail", existing?.title ?: "")
        val etUser = field("Usuario / email", "tu@correo", existing?.username ?: "")
        val etPass = field("Contraseña", "•••••• (dejar vacío si no aplica)",
            existing?.let { Secrets.decrypt(it.passwordEnc) ?: "" } ?: "", password = true)
        val etUrl = field("Sitio web (opcional)", "https://...", existing?.url ?: "")

        // --- OTP ---
        lay.addView(TextView(ctx).apply {
            text = "Doble factor (OTP) — opcional"
            textSize = 14f; setPadding(0, dp(16), 0, dp(4))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        // Selector tipo OTP
        lay.addView(TextView(ctx).apply {
            text = "Tipo de código"; textSize = 13f; setTextColor(0xFF888888.toInt())
        })
        val optKinds = arrayOf("Ninguno", "TOTP (tiempo, 30s)", "HOTP (contador)", "Steam Guard")
        val spinKind = Spinner(ctx).apply {
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

        // Selector algoritmo
        lay.addView(TextView(ctx).apply {
            text = "Algoritmo HMAC"; textSize = 13f; setTextColor(0xFF888888.toInt())
            setPadding(0, dp(8), 0, dp(2))
        })
        val optAlgo = arrayOf("SHA1 (estándar)", "SHA256", "SHA512")
        val spinAlgo = Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx,
                android.R.layout.simple_spinner_item, optAlgo)
            val cur = existing?.otpCfgEnc?.let {
                Otp.Cfg.parse(it)?.algo?.ordinal ?: 0
            } ?: 0
            setSelection(cur)
        }
        lay.addView(spinAlgo, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Dígitos
        lay.addView(TextView(ctx).apply {
            text = "Dígitos (6 por defecto; 8 para algunos servicios)"; textSize = 13f
            setTextColor(0xFF888888.toInt()); setPadding(0, dp(8), 0, dp(2))
        })
        val optDigits = arrayOf("6", "7", "8")
        val spinDigits = Spinner(ctx).apply {
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

        // Secret OTP
        val etOtp = field("Secret OTP (Base32, o pega otpauth://...)",
            "JBSWY3DPEHPK3PXP  /  otpauth://totp/...",
            existing?.let {
                Secrets.decrypt(it.otpKeyEnc) ?: ""  // mostrar el secret original
            } ?: "")
        etOtp.minLines = 2; etOtp.setSingleLine(false)

        // Notas
        lay.addView(TextView(ctx).apply {
            text = "Notas (texto plano — no guardes secretos aquí)"
            textSize = 13f; setTextColor(0xFF888888.toInt()); setPadding(0, dp(8), 0, dp(2))
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

                // Resolver OTP: si el usuario pegó un otpauth:// URI, lo
                // parseamos y rellenamos cfg+secret; si no, usamos los
                // spinners + el campo secret.
                var cfg: Otp.Cfg? = null
                var secret: String = ""
                val rawOtp = etOtp.text.toString().trim()
                if (rawOtp.isNotEmpty()) {
                    val parsed = Otp.parseOtpAuthUri(rawOtp)
                    if (parsed != null) {
                        cfg = parsed.first; secret = parsed.second
                        // Si el usuario eligió Steam en el spinner, forzamos steam
                        if (spinKind.selectedItemPosition == 3) cfg = cfg!!.copy(steam = true)
                        // Si pegó hotp URI respeta el contador; si no, respeta spinners
                        if (cfg!!.kind != Otp.Kind.HOTP && spinKind.selectedItemPosition == 2)
                            cfg = cfg!!.copy(kind = Otp.Kind.HOTP)
                    } else {
                        // Secret suelto (Base32/hex/plain): usar spinners
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
                        // Si tipo es "Ninguno" pero hay secret, avisar
                        if (cfg == null) toast("Tipo = Ninguno: el secret no se usará")
                    }
                    // Validar secret decodificable
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
                    otpCfgEnc, otpKeyEnc, notes)
                load()
                toast(if (existing == null) "Credencial guardada" else "Cambios guardados")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- adapter con OTP bajo demanda ----------------

    /**
     * Adapter de la bóveda. Cada fila tiene título/usuario y, si tiene
     * OTP, un botón **"Ver código"**. El código NO se calcula hasta que
     * se pulsa el botón (optimización del usuario). Cuando se calcula,
     * se reprograma UN solo [Handler.postDelayed] en el límite del
     * step siguiente (cada 30s por defecto), no cada tick.
     *
     * Solo una fila mantiene OTP activo a la vez (la última abierta);
     * al abrir otra, la anterior se oculta y cancela su timer. Esto
     * evita N timers simultáneos cuando hay muchas credenciales.
     */
    private class VaultAdapter(
        private val ctx: VaultActivity,
        private val rows: List<Db.VaultRow>,
        private val onEdit: (Db.VaultRow) -> Unit
    ) : BaseAdapter() {

        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var active: OtpHolder? = null

        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = rows[p].id

        /** Detiene cualquier recálculo de OTP en curso (onPause). */
        fun stopAllOtp() {
            active?.stop(); active = null
        }

        override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
            val row = rows[pos]
            val v = cv ?: buildRow()
            val h = v.tag as OtpHolder
            h.bind(row)
            return v
        }

        /** Construye una fila una sola vez (reciclado por BaseAdapter). */
        private fun buildRow(): View {
            val d = dp2(ctx, 16)
            val v = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(d, dp2(ctx, 10), d, dp2(ctx, 10))
                background = ctx.getDrawable(android.R.drawable.list_selector_background)
            }
            // --- fila 1: título + botones ---
            val r1 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val title = TextView(ctx).apply {
                textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            r1.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
            val btnOtp = Button(ctx).apply { text = "Ver código" }
            val btnUser = Button(ctx).apply { text = "Usu." }
            val btnPass = Button(ctx).apply { text = "Pass" }
            val btnUrl = Button(ctx).apply { text = "URL"; visibility = View.GONE }
            r1.addView(btnOtp); r1.addView(btnUser); r1.addView(btnPass); r1.addView(btnUrl)
            v.addView(r1, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // --- usuario ---
            val user = TextView(ctx).apply {
                textSize = 13f; setTextColor(0xFF888888.toInt())
            }
            v.addView(user)
            // --- bloque OTP (oculto hasta pulsar "Ver código") ---
            val otpBlock = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp2(ctx, 6), 0, 0)
                visibility = View.GONE
            }
            val otpCode = TextView(ctx).apply {
                textSize = 28f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF1B5E20.toInt())
            }
            otpBlock.addView(otpCode, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            })
            val otpCopy = Button(ctx).apply { text = "Copiar" }
            otpBlock.addView(otpCopy)
            v.addView(otpBlock, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // --- notas ---
            val notes = TextView(ctx).apply {
                textSize = 12f; setTextColor(0xFF666666.toInt()); visibility = View.GONE
            }
            v.addView(notes)

            val h = OtpHolder(this, title, user, btnOtp, btnUser, btnPass, btnUrl,
                otpBlock, otpCode, otpCopy, notes)
            v.tag = h

            // Conectar listeners una sola vez (la fila se recicla, el
            // holder se re-bind a la nueva row en cada getView).
            v.setOnClickListener { onEdit(h.row) }
            btnUser.setOnClickListener {
                if (h.row.username.isNotEmpty()) {
                    Clipboard.copy(ctx, "usuario", h.row.username); ctx.toast("Usuario copiado")
                } else ctx.toast("Sin usuario")
            }
            btnPass.setOnClickListener {
                val pw = Secrets.decrypt(h.row.passwordEnc)
                if (pw.isNullOrEmpty()) ctx.toast("Sin contraseña guardada")
                else { Clipboard.copy(ctx, "contraseña", pw); ctx.toast("Contraseña copiada") }
            }
            btnUrl.setOnClickListener {
                Clipboard.copy(ctx, "url", h.row.url); ctx.toast("URL copiada")
            }
            btnOtp.setOnClickListener { h.toggle() }
            otpCopy.setOnClickListener {
                val c = otpCode.text.toString()
                if (c.isNotEmpty() && c != "—") {
                    Clipboard.copy(ctx, "otp", c); ctx.toast("Código copiado")
                }
            }
            return v
        }

        /**
         * Estado OTP de una fila. [showOtp] descifra el secret (una sola
         * vez por apertura) y calcula el código; reprograma UN solo
         * [Handler.postDelayed] en el límite del step siguiente para
         * TOTP. HOTP solo se recalcula al pulsar (no tiene timer), e
         * incrementa el contador al pulsar (no cada 30s).
         */
        private inner class OtpHolder(
            outer: VaultAdapter,
            private val title: TextView,
            private val user: TextView,
            private val btnOtp: Button,
            private val btnUser: Button,
            private val btnPass: Button,
            private val btnUrl: Button,
            private val otpBlock: LinearLayout,
            private val codeView: TextView,
            private val otpCopy: Button,
            private val notes: TextView
        ) {
            lateinit var row: Db.VaultRow
            private var shown = false
            private val updater = Runnable { refreshOtp(auto = true) }

            fun bind(r: Db.VaultRow) {
                row = r
                title.text = r.title
                user.text = if (r.username.isNotEmpty()) "👤 ${r.username}" else "—"
                btnUrl.visibility = if (r.url.isNotEmpty()) View.VISIBLE else View.GONE
                if (r.notes.isNotBlank()) {
                    notes.text = "📝 ${r.notes}"; notes.visibility = View.VISIBLE
                } else notes.visibility = View.GONE
                // Si esta fila reciclada tenía OTP abierto pero ya no es la
                // activa (o no tiene OTP), ocultarlo.
                if (active != this) {
                    stop(); otpBlock.visibility = View.GONE
                }
                btnOtp.isEnabled = r.hasOtp()
                if (!r.hasOtp() && !shown) btnOtp.text = "—"
            }

            fun toggle() {
                if (shown) { stop(); otpBlock.visibility = View.GONE }
                else showOtp()
            }

            fun showOtp() {
                if (!row.hasOtp()) { ctx.toast("Esta entrada no tiene OTP"); return }
                // Solo una fila a la vez: detener la anterior.
                active?.takeIf { it != this }?.let { it.stop(); it.otpBlock.visibility = View.GONE }
                active = this
                shown = true
                otpBlock.visibility = View.VISIBLE
                btnOtp.text = "Ocultar"
                // HOTP: incrementar contador al ABRIR (un uso del código).
                val cfg = Otp.Cfg.parse(row.otpCfgEnc)
                if (cfg?.kind == Otp.Kind.HOTP)
                    ctx.eng.db.vaultNextCounter(row.id)
                refreshOtp(auto = false)
            }

            /**
             * @param auto true si la llamada viene del timer (no
             *  incrementa HOTP); false si viene del pulsar "Ver código".
             */
            private fun refreshOtp(auto: Boolean) {
                val secret = Secrets.decrypt(row.otpKeyEnc)
                val cfg = Otp.Cfg.parse(row.otpCfgEnc)
                if (secret.isNullOrEmpty() || cfg == null) {
                    codeView.text = "—"; return
                }
                codeView.text = Otp.computeOtp(secret, cfg) ?: "—"
                // TOTP: recalcular solo en el límite del step siguiente.
                if (cfg.kind == Otp.Kind.TOTP) {
                    val secs = Otp.nextStepIn(cfg)
                    handler.removeCallbacks(updater)
                    handler.postDelayed(updater, secs * 1000L)
                }
                // HOTP: no hay timer; el siguiente código se obtiene al
                // pulsar de nuevo (e incrementa el contador).
            }

            fun stop() {
                handler.removeCallbacks(updater)
                shown = false
                btnOtp.text = if (row.hasOtp()) "Ver código" else "—"
            }
        }
    }
}

/** dp helper estático (usado dentro del adapter). */
private fun dp2(ctx: Context, v: Int): Int =
    (v * ctx.resources.displayMetrics.density).toInt()
