package com.turbodl.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.turbodl.util.Ui
import java.io.File

/**
 * Reproductor interno sobre ExoPlayer (androidx.media3, Java puro, sin
 * binarios). Sustituye al VideoView básico de 3.7.0 para ganar: HLS y DASH,
 * pistas de subtítulos laterales (.srt/.vtt con el mismo nombre que el
 * vídeo), Picture-in-Picture y control de errores claro.
 * Archivos locales (content:// de MiniFileProvider) y documentos SAF.
 */
class VideoActivity : Activity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.theme(this)
        super.onCreate(savedInstanceState)
        val src = intent.getStringExtra("src") ?: ""
        val title = intent.getStringExtra("title") ?: "Reproductor"
        if (src.isEmpty()) { finish(); return }

        val pv = PlayerView(this)
        pv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(pv)

        val p = ExoPlayer.Builder(this).build()
        player = p
        pv.player = p
        this.title = title

        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AlertDialog.Builder(this@VideoActivity)
                    .setTitle("No se puede reproducir")
                    .setMessage("El reproductor interno no soporta este contenido " +
                            "(${error.errorCodeName}). Usa «Abrir archivo» para elegir " +
                            "un reproductor externo como VLC o MX Player.")
                    .setPositiveButton("Cerrar") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            }
        })

        p.setMediaItem(buildItem(src, title))
        p.prepare()
        p.play()
    }

    /** MediaItem con subtítulo lateral si existe <base>.srt o <base>.vtt
     *  junto al vídeo (lo que genera la descarga de subtítulos). */
    private fun buildItem(src: String, title: String): MediaItem {
        val b = MediaItem.Builder()
            .setUri(Uri.parse(src))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        val sub = findSubtitle(src)
        if (sub != null) {
            val mime = if (sub.first.endsWith(".vtt", true))
                MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.second))
                    .setMimeType(mime)
                    .setLabel("Subtítulos")
                    .build()))
        }
        return b.build()
    }

    /** Busca <base>.srt / <base>.vtt al lado del vídeo (solo carpeta de la
     *  app; en SAF los hermanos del documento no se consultan baratos). */
    private fun findSubtitle(src: String): Pair<String, String>? {
        val prefix = "content://com.turbodl.files/"
        if (!src.startsWith(prefix)) return null
        val rel = Uri.decode(src.removePrefix(prefix))
        val base = rel.substringBeforeLast('.')
        if (base.isEmpty()) return null
        val root = File(getExternalFilesDir(null), "TurboDL")
        for (ext in arrayOf("srt", "vtt")) {
            val f = File(root, "$base.$ext")
            if (f.exists()) {
                return "$ext" to "$prefix${Uri.encode("$base.$ext")}"
            }
        }
        return null
    }

    /** Al irse de la app con vídeo en marcha: ventana flotante. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val p = player ?: return
        if (p.isPlaying) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9)).build())
            } catch (e: Exception) { }
        }
    }

    override fun onPause() {
        super.onPause()
        // en PiP se sigue viendo; en fondo se pausa para no gastar batería
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onResume() {
        super.onResume()
        if (!isInPictureInPictureMode) player?.play()
    }

    override fun onDestroy() {
        try { player?.release() } catch (e: Exception) { }
        player = null
        super.onDestroy()
    }
}
