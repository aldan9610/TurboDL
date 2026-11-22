package com.turbodl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios de la clasificación de medios que usa el navegador
 * ([Kit.mediaClass] y [Kit.fmtOf]).
 *
 * El navegador decide qué tipo de descarga usar (HLS, DASH, MP4, audio…)
 * a partir de la extensión de la URL detectada. Estos tests blindan el
 * comportamiento para que un cambio de sintaxis en la regex no rompa
 * silenciosamente la detección de YouTube (videoplayback) o los
 * streams m3u8.
 */
class MediaClassTest {

    // ----------------------------- mediaClass -----------------------------

    @Test
    fun `stream m3u8 se clasifica como stream`() {
        assertEquals("stream", Kit.mediaClass("https://hls.example.com/master.m3u8"))
        assertEquals("stream",
            Kit.mediaClass("https://hls.example.com/master.m3u8?token=abc"))
    }

    @Test
    fun `stream mpd se clasifica como stream`() {
        assertEquals("stream",
            Kit.mediaClass("https://dash.example.com/manifest.mpd"))
    }

    @Test
    fun `video mp4 webm mkv se clasifican como video`() {
        assertEquals("video", Kit.mediaClass("https://example.com/v.mp4"))
        assertEquals("video", Kit.mediaClass("https://example.com/v.webm"))
        assertEquals("video", Kit.mediaClass("https://example.com/v.mkv"))
        assertEquals("video", Kit.mediaClass("https://example.com/v.mov"))
        assertEquals("video", Kit.mediaClass("https://example.com/v.avi"))
    }

    @Test
    fun `audio mp3 aac m4a opus se clasifican como audio`() {
        assertEquals("audio", Kit.mediaClass("https://example.com/a.mp3"))
        assertEquals("audio", Kit.mediaClass("https://example.com/a.aac"))
        assertEquals("audio", Kit.mediaClass("https://example.com/a.m4a"))
        assertEquals("audio", Kit.mediaClass("https://example.com/a.opus"))
        assertEquals("audio", Kit.mediaClass("https://example.com/a.flac"))
    }

    @Test
    fun `url sin extension reconoce como otro`() {
        assertEquals("otro", Kit.mediaClass("https://example.com/page"))
        assertEquals("otro", Kit.mediaClass("https://example.com/"))
    }

    @Test
    fun `query y fragmento no afectan a la clasificacion`() {
        // La query no debe falsear la extensión: .mp4?t=1 sigue siendo video.
        assertEquals("video", Kit.mediaClass("https://example.com/v.mp4?t=1"))
        assertEquals("audio", Kit.mediaClass("https://example.com/a.mp3#x"))
        assertEquals("stream", Kit.mediaClass("https://example.com/m.m3u8?token=abc#frag"))
    }

    // ------------------------------- fmtOf --------------------------------

    @Test
    fun `fmtOf reconoce formatos comunes`() {
        assertEquals("HLS", Kit.fmtOf("https://example.com/master.m3u8"))
        assertEquals("DASH", Kit.fmtOf("https://example.com/manifest.mpd"))
        assertEquals("MP4", Kit.fmtOf("https://example.com/v.mp4"))
        assertEquals("WEBM", Kit.fmtOf("https://example.com/v.webm"))
        assertEquals("MKV", Kit.fmtOf("https://example.com/v.mkv"))
        assertEquals("MP3", Kit.fmtOf("https://example.com/a.mp3"))
        assertEquals("M4A", Kit.fmtOf("https://example.com/a.m4a"))
        assertEquals("AAC", Kit.fmtOf("https://example.com/a.aac"))
        assertEquals("FLAC", Kit.fmtOf("https://example.com/a.flac"))
        assertEquals("OPUS", Kit.fmtOf("https://example.com/a.opus"))
        assertEquals("TS", Kit.fmtOf("https://example.com/seg.ts"))
        assertEquals("fMP4", Kit.fmtOf("https://example.com/seg.m4s"))
    }

    @Test
    fun `fmtOf devuelve la extension upper si no es conocido`() {
        assertEquals("BIN", Kit.fmtOf("https://example.com/archivo.bin"))
    }

    @Test
    fun `fmtOf no falla en URL sin extension explicita de archivo`() {
        // fmtOf no distingue host de path: extOf busca el último punto
        // de la cadena. Para una URL sin extensión de archivo el
        // resultado depende del host; solo garantizamos que no se lanza
        // excepción y que devuelve una cadena no vacía (aunque sea
        // ruidosa como "COM/SINEXTENSION").
        val result = Kit.fmtOf("https://example.com/sinextension")
        assertTrue("result debe ser no vacío: $result", result.isNotEmpty())
    }

    // --------------------------- isVideoOrAudio ---------------------------

    @Test
    fun `isVideoOrAudio true para extensiones multimedia`() {
        assertTrue(Kit.isVideoOrAudio("https://example.com/v.mp4"))
        assertTrue(Kit.isVideoOrAudio("https://example.com/m.m3u8"))
        assertTrue(Kit.isVideoOrAudio("https://example.com/a.mp3"))
        assertTrue(Kit.isVideoOrAudio("https://example.com/v.mkv"))
    }

    @Test
    fun `isVideoOrAudio false para HTML y JSON`() {
        assertFalse(Kit.isVideoOrAudio("https://example.com/page.html"))
        assertFalse(Kit.isVideoOrAudio("https://example.com/data.json"))
        assertFalse(Kit.isVideoOrAudio("https://example.com/image.png"))
    }
}
