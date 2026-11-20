package com.turbodl

import android.app.Application
import com.turbodl.engine.Engine

class App : Application() {
    lateinit var engine: Engine

    override fun onCreate() {
        super.onCreate()
        app = this
        // registro local de fallos (sin telemetría): lo primero, para no
        // perder ni el crash que ocurra durante el arranque
        com.turbodl.util.CrashLog.install(this)
        engine = Engine(this)
        engine.init()
        // bloqueador de anuncios: inicializar temprano (hilo de fondo) SOLO con el
        // bloqueador activado; las listas están integradas en el APK, no
        // necesita conexión la primera vez
        if (com.turbodl.engine.AdBlockEngine.activado(this)) {
            com.turbodl.engine.AdBlockEngine.ensure(this)
        }
    }

    companion object {
        lateinit var app: App

        /** false mientras el PIN no haya sido validado en esta ejecucion. */
        @Volatile var unlocked: Boolean = false
    }
}
