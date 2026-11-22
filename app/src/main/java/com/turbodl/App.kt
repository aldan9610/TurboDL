package com.turbodl

import android.app.Application
import com.turbodl.engine.Engine

class App : Application() {
    lateinit var engine: Engine

    override fun onCreate() {
        super.onCreate()
        app = this
        // sandbox de usuarios (4.0): el sufijo del directorio de datos del
        // WebView se fija ANTES de crear cualquier WebView; el usuario
        // "main" no usa sufijo (comportamiento clásico sin cambios)
        com.turbodl.util.Users.applyDataDirSuffix(this)
        // 4.5: instalar proxy global ANTES de cualquier conexión:
        //   · Authenticator de la JVM para credenciales del proxy (HTTP
        //     CONNECT y SOCKS5 user/pass). El motor de descargas lo usa.
        //   · ProxyController del WebView para enrutar toda la carga del
        //     navegador (página + subrecursos). Android 9+ con WebView
        //     actualizable; en <9 se ignora y se usa el proxy del sistema.
        com.turbodl.util.Proxy.installAuthenticator(this)
        com.turbodl.util.Proxy.applyToWebView(this)
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
