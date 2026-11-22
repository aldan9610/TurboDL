package com.turbodl.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversión WebVTT -> SubRip del gemelo .srt de los subtítulos. */
class SubsSrtTest {

    @Test
    fun basic() {
        val vtt = """
            WEBVTT

            00:01.000 --> 00:03.500
            Hola mundo

            00:04.250 --> 00:06.000 line:-1
            Segunda línea de diálogo
        """.trimIndent()
        val srt = Subs.vttToSrt(vtt)
        val lines = srt.trim().lines()
        assertEquals("1", lines[0])
        assertEquals("00:00:01,000 --> 00:00:03,500", lines[1])
        assertEquals("Hola mundo", lines[2])
        assertTrue(srt.contains("2\n00:00:04,250 --> 00:00:06,000"))
        assertTrue(srt.contains("Segunda línea de diálogo"))
        assertFalse(srt.contains("WEBVTT"))
        assertFalse(srt.contains(".250")) // marcas cortas VTT convertidas
        assertFalse(srt.contains(".000"))
    }

    @Test
    fun horasCompletas() {
        val srt = Subs.vttToSrt("01:02:03.500 --> 02:03:04.999\nlinea")
        assertTrue(srt.contains("01:02:03,500"))
        assertTrue(srt.contains("02:03:04,999"))
    }

    @Test
    fun vacio() {
        assertEquals("", Subs.vttToSrt("WEBVTT\n\n"))
        assertEquals("", Subs.vttToSrt(""))
    }
}
