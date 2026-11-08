package com.turbodl

import android.app.Application
import com.turbodl.engine.Engine

class App : Application() {
    lateinit var engine: Engine

    override fun onCreate() {
        super.onCreate()
        app = this
        engine = Engine(this)
        engine.init()
    }

    companion object {
        lateinit var app: App

        /** false mientras el PIN no haya sido validado en esta ejecucion. */
        @Volatile var unlocked: Boolean = false
    }
}
