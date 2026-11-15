package com.turbodl.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.turbodl.App
import com.turbodl.R
import com.turbodl.engine.SafStore
import com.turbodl.util.Ui

/**
 * Introducción de primera instalación (estilo 1DM): explica la app y deja
 * listas las configuraciones que importan — notificaciones de progreso,
 * carpeta de descargas (carpeta interna sin permisos o carpeta pública/SD
 * con SAF) y exención de optimización de batería para descargar en segundo
 * plano. Cada paso se puede saltar; el feedback se repite al abrir la app
 * si algo queda pendiente (MainActivity.onResume).
 */
class OnboardingActivity : Activity() {

    private val eng get() = App.app.engine
    private var paso = 0

    private lateinit var raiz: LinearLayout
    private lateinit var cuerpo: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        setContentView(raiz)
        Ui.styleAll(findViewById(android.R.id.content))
        build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun build() {
        raiz.removeAllViews()
        cuerpo = LinearLayout(this)
        cuerpo.orientation = LinearLayout.VERTICAL
        cuerpo.setPadding(dp(24), dp(28), dp(24), dp(16))

        val sc = ScrollView(this)
        sc.addView(cuerpo, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        raiz.addView(sc, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        raiz.addView(botones(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        when (paso) {
            0 -> bienvenida()
            1 -> notificaciones()
            2 -> carpeta()
            3 -> bateria()
            else -> listo()
        }
    }

    private fun titulo(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 22f
        v.setPadding(0, dp(8), 0, dp(10))
        cuerpo.addView(v)
    }

    private fun texto(t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 14f
        v.setPadding(0, dp(6), 0, dp(6))
        cuerpo.addView(v)
    }

    private fun btn(label: String, f: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { f() }
        cuerpo.addView(b, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return b
    }

    private fun botones(): LinearLayout {
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.setPadding(dp(16), dp(8), dp(16), dp(16))
        fila.gravity = Gravity.CENTER_VERTICAL
        if (paso > 0) {
            val atras = Button(this)
            atras.text = "← Atrás"
            atras.setOnClickListener { paso--; build() }
            fila.addView(atras)
        }
        val saltar = Button(this)
        saltar.text = "Saltar"
        saltar.setOnClickListener { terminar() }
        fila.addView(saltar, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val seguir = Button(this)
        seguir.text = if (paso >= 4) "Empezar a descargar ✔" else "Continuar →"
        seguir.setOnClickListener {
            if (paso >= 4) terminar() else { paso++; build() }
        }
        fila.addView(seguir)
        return fila
    }

    private fun terminar() {
        eng.sp.edit().putBoolean("onboardingDone", true).apply()
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ---------------- pasos ----------------

    private fun bienvenida() {
        val logo = TextView(this)
        logo.text = "⚡"
        logo.textSize = 48f
        logo.gravity = Gravity.CENTER
        cuerpo.addView(logo, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(80)))
        titulo("Bienvenido a TurboDL")
        texto("Gestor de descargas multipropósito: HTTP multihilo con reanudación, " +
                "FTP, HLS (m3u8), DASH (mpd), BitTorrent, subtítulos, reproductor " +
                "integrado y navegador con bloqueador de anuncios.")
        texto("Los siguientes pasos preparan lo importante para que las descargas " +
                "funcionen bien desde el primer día. Puedes saltar cualquier paso " +
                "y ajustarlo luego en ⚙ Ajustes.")
        texto("Gratis, sin anuncios ni suscripciones.")
    }

    private fun notificaciones() {
        titulo("Notificaciones de descarga")
        texto("TurboDL muestra una notificación con el progreso mientras descarga " +
                "en segundo plano. Sin el permiso, Android oculta esa notificación " +
                "(y en algunos teléfonos puede frenar las descargas en segundo plano).")
        if (Build.VERSION.SDK_INT >= 33) {
            val concedido = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (concedido) {
                texto("✅ Permiso de notificaciones concedido.")
            } else {
                btn("🔔 Activar notificaciones") {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
                }
                texto("Si pulsa «no permitir», las descargas seguirán funcionando; " +
                        "solo no verás el progreso.")
            }
        } else {
            texto("✅ Tu versión de Android no necesita este permiso.")
        }
    }

    private fun carpeta() {
        titulo("Carpeta de descargas")
        texto("¿Dónde quieres que se guarden los archivos?")
        texto("1) Carpeta interna de TurboDL: sin permisos, privada de la app. " +
                "Puedes ver y compartir los archivos desde el botón 📂 de cada descarga " +
                "y desde cualquier explorador en Android/data/com.turbodl.")
        texto("2) Carpeta pública o tarjeta SD: Descargas, Música, Vídeo o la SD " +
                "externa. Android pedirá elegir la carpeta una vez (SAF) y quedará " +
                "recordada.")
        btn("📁 Carpeta de TurboDL (sin permisos, recomendado)") {
            eng.sp.edit().putString("dlMode", "app").apply()
            texto("✅ Destino: carpeta de TurboDL.")
        }
        btn("🗂 Elegir carpeta pública o SD…") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            startActivityForResult(i, 51)
        }
        texto("Luego puedes cambiarlo en ⚙ Ajustes → Carpeta de destino.")
    }

    private fun bateria() {
        titulo("Descargar en segundo plano")
        texto("Para descargar con la pantalla apagada o con la app en segundo plano, " +
                "Android puede congelar TurboDL. Puedes eximirla de la optimización " +
                "de batería (no consume más: solo evita que el sistema la pare).")
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            texto("✅ TurboDL ya puede descargar en segundo plano.")
        } else {
            btn("🔋 Permitir descargar en segundo plano") {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        AlertDialog.Builder(this)
                            .setTitle("No disponible")
                            .setMessage("Tu versión de Android no abre esta pantalla. " +
                                    "Hazlo en: Ajustes → Aplicaciones → TurboDL → Batería.")
                            .setPositiveButton("OK", null).show()
                    }
                }
            }
        }
        texto("💡 Si tu teléfono es Xiaomi/Redmi, Huawei o similar, activa también " +
                "«Inicio automático» para TurboDL en Ajustes del sistema; si no, el " +
                "sistema puede cerrar las descargas largas.")
    }

    private fun listo() {
        titulo("Todo listo")
        val sp = eng.sp
        val notif = if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED else true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        texto((if (notif) "✅" else "⚠") + " Notificaciones: " +
                (if (notif) "activadas" else "sin conceder (no verás el progreso)"))
        texto("📂 Destino: " + if (sp.getString("dlMode", "app") == "saf" && usingSafOk())
                "carpeta pública elegida" else "carpeta de TurboDL")
        texto((if (pm.isIgnoringBatteryOptimizations(packageName)) "✅" else "⚠") +
                " Segundo plano: " + (if (pm.isIgnoringBatteryOptimizations(packageName))
                "permitido" else "sin eximir de batería"))
        texto("Consejo: al abrir un vídeo en el navegador integrado, el botón " +
                "«⬇ Detectados» muestra los medios con su calidad y duración.")
        texto("¡Disfruta TurboDL!")
    }

    private fun usingSafOk(): Boolean {
        return try { eng.usingSaf() } catch (e: Exception) { false }
    }

    // ---------------- resultados ----------------

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 51 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) { }
            eng.sp.edit()
                .putString(SafStore.PREF_TREE, uri.toString())
                .putString("dlMode", "saf").apply()
            AlertDialog.Builder(this)
                .setTitle("Carpeta elegida")
                .setMessage("Las descargas se guardarán en: " +
                        SafStore.treeName(this, uri) + "/TurboDL")
                .setPositiveButton("OK", null).show()
            build()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) build() // refresca el estado del paso de notificaciones
    }
}
