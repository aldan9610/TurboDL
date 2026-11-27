package com.turbodl.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests del selector de motor de bloqueo (4.16, bug reportado: "al
 * seleccionar el motor de bloqueo de Brave se queda seleccionado pero la
 * notificación y el texto de Ajustes no lo reflejan").
 *
 * Causa raíz: usaBrave() exigía además RAM >= 1,5 GB, así que en equipos
 * humildes la preferencia "brave" se servía EN SILENCIO con FilterEngine
 * (Kotlin): el diálogo marcaba Brave pero engineName() devolvía
 * "FilterEngine (Kotlin)" — el toast y la fila de Ajustes "no reflejaban"
 * la elección, y además el motor nativo Brave (implementado y funcional:
 * AARs ad-filter + adblock-client con libadblock-client.so) no se usaba.
 *
 * Contrato que fija esta suite (4.16):
 *  - la elección se persiste y engineName() la refleja SIEMPRE, sin
 *    degradación silenciosa por RAM baja;
 *  - usaBrave() depende SOLO de la preferencia elegida;
 *  - esLowRam() es informativo (el selector lo usa para AVISAR al elegir
 *    Brave), nunca cambia el motor servido.
 *
 * Nota: con el bloqueador apagado ("adblock"=false), setEngine() solo
 * persiste la preferencia sin lanzar la inicialización del motor, así que
 * estos tests no tocan los motores reales.
 */
@RunWith(RobolectricTestRunner::class)
class EngineChoiceTest {

    private lateinit var ctx: Application

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // bloqueador apagado → setEngine no lanza hilos de inicialización
        ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
            .edit().clear().putBoolean("adblock", false).commit()
    }

    @Test
    fun `el motor por defecto es brave`() {
        assertEquals("brave", AdBlockEngine.selectedEngine(ctx))
    }

    @Test
    fun `elegir kotlin y hosts se refleja en engineName`() {
        AdBlockEngine.setEngine(ctx, "kotlin")
        assertEquals("kotlin", AdBlockEngine.selectedEngine(ctx))
        assertEquals("FilterEngine (Kotlin)", AdBlockEngine.engineName(ctx))
        assertFalse(AdBlockEngine.usaBrave(ctx))

        AdBlockEngine.setEngine(ctx, "hosts")
        assertEquals("hosts", AdBlockEngine.selectedEngine(ctx))
        assertEquals("Solo hosts", AdBlockEngine.engineName(ctx))
        assertFalse(AdBlockEngine.usaBrave(ctx))
    }

    @Test
    fun `elegir brave se refleja SIEMPRE, tambien en RAM baja (regresion 4_15)`() {
        // Premisa del caso reportado: Robolectric reporta MemoryInfo a
        // cero (RAM total baja), el mismo perfil de equipo donde la 4.15
        // servía FilterEngine en silencio aunque marcases Brave. En 4.15,
        // con esta RAM, engineName devolvía "FilterEngine (Kotlin)" y
        // usaBrave era false: exactamente el bug. Si esta premisa deja de
        // cumplirse (un futuro Robolectric reporta RAM real), forzarla
        // desde ShadowActivityManager antes de quitarla.
        assertTrue("premisa: el entorno debe simular RAM baja; si falla, forzarla con shadowOf(am)",
            AdBlockEngine.esLowRam(ctx))

        AdBlockEngine.setEngine(ctx, "brave")
        assertEquals("brave", AdBlockEngine.selectedEngine(ctx))
        assertEquals("Brave (nativo)", AdBlockEngine.engineName(ctx))
        assertTrue(AdBlockEngine.usaBrave(ctx))
    }

    @Test
    fun `un id de motor desconocido cae a brave`() {
        AdBlockEngine.setEngine(ctx, "no-existe")
        assertEquals("brave", AdBlockEngine.selectedEngine(ctx))
        assertEquals("Brave (nativo)", AdBlockEngine.engineName(ctx))
        assertTrue(AdBlockEngine.usaBrave(ctx))
    }
}
