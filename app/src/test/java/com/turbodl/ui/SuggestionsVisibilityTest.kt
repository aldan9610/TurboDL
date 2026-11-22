package com.turbodl.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests unitarios del comportamiento de las sugerencias de búsqueda (Bug 3).
 *
 * El navegador debe OCULTAR el panel de sugerencias cuando:
 *   - El campo de URL está vacío (no hay nada que sugerir).
 *   - El campo coincide con la URL de la pestaña activa (ya se cargó o
 *     está cargando; no se sugiere navegar a donde ya estamos).
 *   - Se acaba de lanzar una búsqueda (suppressSuggest=true durante
 *     el setText, y hideSuggestions() se llama explícitamente).
 *
 * Aquí replicamos la lógica de `BrowserActivity.showSuggestions()` sin
 * Activity para que el test sea reproducible en JVM puro.
 */
class SuggestionsVisibilityTest {

    /**
     * Decide si el panel de sugerencias debe estar visible dado el
     * estado actual del navegador. Replica el guard inicial de
     * `BrowserActivity.showSuggestions`.
     */
    private fun shouldShowSuggestions(
        fieldText: String,
        suppressSuggest: Boolean,
        activeTabUrl: String
    ): Boolean {
        // Guard 1: si el flag de supresión está activo o el campo está
        // vacío, no se muestra nada.
        if (suppressSuggest || fieldText.isBlank()) return false
        // Guard 2 (Bug 3): si el texto coincide con la URL de la pestaña
        // activa, no se muestran sugerencias (ya se está cargando o ya
        // se cargó esa página).
        if (activeTabUrl.isNotEmpty() && fieldText.trim() == activeTabUrl) return false
        // Resto: hay texto y no coincide con la página activa → se muestran.
        return true
    }

    @Test
    fun `campo vacio oculta sugerencias`() {
        assertFalse(shouldShowSuggestions("", false, ""))
        assertFalse(shouldShowSuggestions("   ", false, "https://example.com"))
    }

    @Test
    fun `flag suppressSuggest oculta sugerencias aunque haya texto`() {
        assertFalse(shouldShowSuggestions("hello", true, ""))
        assertFalse(shouldShowSuggestions("example.com", true, "https://other.com"))
    }

    @Test
    fun `texto distinto a la URL activa muestra sugerencias`() {
        assertTrue(shouldShowSuggestions("face", false, "https://example.com"))
        assertTrue(shouldShowSuggestions("face", false, ""))
        assertTrue(shouldShowSuggestions("https://example.com", false, "https://other.com"))
    }

    @Test
    fun `Bug 3 - texto identico a URL activa oculta sugerencias`() {
        // Caso clave: tras la búsqueda, el campo se reescribe con la URL
        // final. El TextWatcher reabría sugerencias si no hay guard; lo
        // evitamos comparando con la URL de la pestaña activa.
        assertFalse(shouldShowSuggestions("https://example.com/search?q=hello", false,
            "https://example.com/search?q=hello"))
        assertFalse(shouldShowSuggestions("https://example.com", false,
            "https://example.com"))
    }

    @Test
    fun `Bug 3 - texto identico pero con espacios no casa (trim previo)`() {
        // El campo conserva la URL sin espacios, así que tras trim()
        // deben coincidir y ocultarse las sugerencias.
        assertFalse(shouldShowSuggestions("  https://example.com  ", false,
            "https://example.com"))
    }

    @Test
    fun `URL activa vacia nunca bloquea sugerencias`() {
        // Si no hay pestaña activa (o está en blanco), cualquier texto
        // puede sugerir (típicamente al escribir en la página de inicio).
        assertTrue(shouldShowSuggestions("youtube", false, ""))
        assertTrue(shouldShowSuggestions("https://other.com", false, ""))
    }

    @Test
    fun `URL casi igual pero con fragmento distinto SI muestra sugerencias`() {
        // La comparación es exacta (no normaliza fragmentos): si el
        // usuario está en /page y escribe /page#sec3, le damos sugerencias
        // (es legítimo que quiera ir al ancla).
        assertTrue(shouldShowSuggestions("https://example.com/page#sec3", false,
            "https://example.com/page"))
    }
}
