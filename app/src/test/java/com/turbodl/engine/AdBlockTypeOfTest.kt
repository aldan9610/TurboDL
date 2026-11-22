package com.turbodl.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios de [AdBlockEngine.typeOf], que deduce el tipo de
 * recurso (script, image, stylesheet, media, font, document,
 * xmlhttprequest) a partir de la extensión de la URL. El navegador usa
 * este tipo para consultar al motor de bloqueo con la opción `$tipo`
 * correcta (p. ej. `*$image` solo bloquea imágenes). Sin estos tests,
 * un cambio en las regex podría mandar `image` cuando el recurso era un
 * `script` y hacer que el bloqueador deje pasar anuncios.
 */
class AdBlockTypeOfTest {

    @Test
    fun `script por extension js`() {
        assertEquals("script",
            AdBlockEngine.typeOf("https://ads.example.com/adsbygoogle.js"))
        assertEquals("script",
            AdBlockEngine.typeOf("https://ads.example.com/ads.js?v=1"))
    }

    @Test
    fun `stylesheet por extension css`() {
        assertEquals("stylesheet",
            AdBlockEngine.typeOf("https://example.com/main.css"))
    }

    @Test
    fun `image por extensiones comunes`() {
        assertEquals("image", AdBlockEngine.typeOf("https://ads.example.com/banner.png"))
        assertEquals("image", AdBlockEngine.typeOf("https://ads.example.com/banner.jpg"))
        assertEquals("image", AdBlockEngine.typeOf("https://ads.example.com/banner.jpeg"))
        assertEquals("image", AdBlockEngine.typeOf("https://ads.example.com/banner.gif"))
        assertEquals("image", AdBlockEngine.typeOf("https://ads.example.com/banner.webp"))
        assertEquals("image", AdBlockEngine.typeOf("https://ads.example.com/banner.svg"))
        assertEquals("image", AdBlockEngine.typeOf("https://ads.example.com/banner.ico"))
    }

    @Test
    fun `font por extensiones woff ttf otf eot`() {
        assertEquals("font", AdBlockEngine.typeOf("https://example.com/font.woff"))
        assertEquals("font", AdBlockEngine.typeOf("https://example.com/font.woff2"))
        assertEquals("font", AdBlockEngine.typeOf("https://example.com/font.ttf"))
        assertEquals("font", AdBlockEngine.typeOf("https://example.com/font.otf"))
    }

    @Test
    fun `media por extensiones de video`() {
        assertEquals("media", AdBlockEngine.typeOf("https://example.com/v.mp4"))
        assertEquals("media", AdBlockEngine.typeOf("https://example.com/v.webm"))
        assertEquals("media", AdBlockEngine.typeOf("https://example.com/seg.ts"))
        assertEquals("media", AdBlockEngine.typeOf("https://example.com/seg.m4s"))
        assertEquals("media", AdBlockEngine.typeOf("https://example.com/v.mkv"))
    }

    @Test
    fun `media por extensiones de audio`() {
        assertEquals("media", AdBlockEngine.typeOf("https://example.com/a.mp3"))
        assertEquals("media", AdBlockEngine.typeOf("https://example.com/a.aac"))
        assertEquals("media", AdBlockEngine.typeOf("https://example.com/a.m4a"))
        assertEquals("media", AdBlockEngine.typeOf("https://example.com/a.opus"))
        assertEquals("media", AdBlockEngine.typeOf("https://example.com/a.flac"))
    }

    @Test
    fun `document por extension html htm o slash final`() {
        assertEquals("document", AdBlockEngine.typeOf("https://example.com/page.html"))
        assertEquals("document", AdBlockEngine.typeOf("https://example.com/page.htm"))
        assertEquals("document", AdBlockEngine.typeOf("https://example.com/carpeta/"))
    }

    @Test
    fun `xmlhttprequest para tipos desconocidos o sin extension`() {
        assertEquals("xmlhttprequest",
            AdBlockEngine.typeOf("https://api.example.com/track?id=1"))
        assertEquals("xmlhttprequest",
            AdBlockEngine.typeOf("https://example.com/sinextension"))
    }

    @Test
    fun `query y fragmento no afectan al tipo`() {
        assertEquals("image",
            AdBlockEngine.typeOf("https://ads.example.com/banner.png?token=abc"))
        assertEquals("image",
            AdBlockEngine.typeOf("https://ads.example.com/banner.png#frag"))
        assertEquals("script",
            AdBlockEngine.typeOf("https://ads.example.com/ads.js?cb=20240101#anchor"))
    }
}
