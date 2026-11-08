package com.turbodl.ui

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.VideoView
import com.turbodl.R
import com.turbodl.util.Ui

/**
 * Reproductor interno minimo (VideoView = MediaPlayer con codecs por
 * hardware): comprueba la descarga sin salir de la app ni gastar RAM.
 * Soporta archivos locales (ruta) y documentos SAF (content://).
 */
class VideoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        val vv = VideoView(this)
        vv.setId(View.generateViewId())
        setContentView(vv)

        val src = intent.getStringExtra("src") ?: ""
        val title = intent.getStringExtra("title") ?: "Reproductor"
        this.title = title
        if (src.isEmpty()) { finish(); return }

        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setVideoURI(Uri.parse(src))
        vv.setOnErrorListener { _, what, extra ->
            AlertDialog.Builder(this)
                .setTitle("No se puede reproducir")
                .setMessage("El reproductor interno no soporta este formato (código $what/$extra). " +
                        "Usa «Abrir archivo» para elegir un reproductor externo como VLC o MX Player.")
                .setPositiveButton("Cerrar") { _, _ -> finish() }
                .setCancelable(false)
                .show()
            true
        }
        vv.setOnCompletionListener { finish() }
        vv.start()
    }
}
