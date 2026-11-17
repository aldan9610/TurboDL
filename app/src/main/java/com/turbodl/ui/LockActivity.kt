package com.turbodl.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.turbodl.App
import com.turbodl.R
import com.turbodl.util.Kit
import com.turbodl.util.Ui

/**
 * Puerta de bloqueo: si el usuario activo el PIN, MainActivity arranca esta
 * actividad antes de mostrar nada. El PIN se guarda solo como SHA-256.
 */
class LockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)

        val eng = App.app.engine
        val saved = eng.sp.getString("pinHash", "") ?: ""
        val lay = LinearLayout(this)
        lay.orientation = LinearLayout.VERTICAL
        lay.setPadding(80, 120, 80, 40)

        val txt = TextView(this)
        txt.text = "🔒 TurboDL bloqueado\nIntroduce tu PIN"
        txt.textSize = 16f
        txt.setPadding(0, 0, 0, 40)
        lay.addView(txt)

        val pin = EditText(this)
        pin.hint = "PIN"
        pin.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        lay.addView(pin)

        val btn = Button(this)
        btn.text = "Desbloquear"
        lay.addView(btn)

        setContentView(lay)
        Ui.styleAll(findViewById(android.R.id.content))

        fun tryUnlock() {
            val h = Kit.sha256(pin.text.toString())
            if (h == saved) {
                App.unlocked = true
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                Toast.makeText(this, "PIN incorrecto", Toast.LENGTH_SHORT).show()
                pin.setText("")
            }
        }
        btn.setOnClickListener { tryUnlock() }
        pin.setOnEditorActionListener { _, _, _ -> tryUnlock(); true }
    }
}
