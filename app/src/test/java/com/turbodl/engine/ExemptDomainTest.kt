package com.turbodl.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Detección de host y dominio base para la exención del bloqueador. */
class ExemptDomainTest {

    @Test
    fun host() {
        assertEquals("www.ejemplo.com", AdBlockEngine.hostOf("https://www.ejemplo.com/video?id=1"))
        assertEquals("cdn.ejemplo.com", AdBlockEngine.hostOf("http://cdn.ejemplo.com/x.js"))
        assertEquals("", AdBlockEngine.hostOf("about:blank"))
        assertEquals("", AdBlockEngine.hostOf("no es una url"))
    }

    @Test
    fun dominioBase() {
        assertEquals("ejemplo.com", AdBlockEngine.baseDomainOf("www.ejemplo.com"))
        assertEquals("ejemplo.com", AdBlockEngine.baseDomainOf("ejemplo.com"))
        assertEquals("", AdBlockEngine.baseDomainOf("localhost"))
        assertEquals("", AdBlockEngine.baseDomainOf("com"))
        assertEquals("ejemplo.co.uk", AdBlockEngine.baseDomainOf("cdn.ejemplo.co.uk"))
    }
}
