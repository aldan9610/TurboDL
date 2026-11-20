package com.turbodl.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.turbodl.App
import com.turbodl.engine.Engine

/**
 * Servicio en primer plano que mantiene vivas las descargas.
 * La notificacion muestra progreso y tiene botones «Pausar todo» y
 * «Detener servicio»; tambien se apaga solo cuando no queda nada activo.
 */
class DownloadService : Service() {

    private var wl: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val eng = App.app.engine
        eng.service = this
        val n = eng.buildNotif(0, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(eng.notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            startForeground(eng.notifId, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val eng = App.app.engine
        when (intent?.action) {
            Engine.ACTION_PAUSE_ALL -> {
                eng.pauseAll()
                stopGraceful()
                return START_NOT_STICKY
            }
            Engine.ACTION_RESUME_ALL -> {
                eng.resumeAll()
                return START_STICKY
            }
            Engine.ACTION_STOP -> {
                eng.pauseAll()
                stopGraceful()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    fun updateForeground(n: Notification, ongoing: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(App.app.engine.notifId, n)
        setActive(ongoing)
    }

    /** Apaga el servicio y su notificacion (ahorro de bateria). */
    fun stopGraceful() {
        setActive(false)
        try { stopForeground(true) } catch (e: Exception) { }
        try { stopSelf() } catch (e: Exception) { }
        App.app.engine.service = null
    }

    @Synchronized
    private fun setActive(active: Boolean) {
        if (active) {
            if (wl == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "turbodl:descargas").apply {
                    try { acquire(6 * 60 * 60 * 1000L) } catch (e: Exception) { }
                }
            }
        } else {
            try { wl?.release() } catch (e: Exception) { }
            wl = null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // si no hay descargas activas, no conviene quedarse vivo
        val eng = App.app.engine
        if (eng.runningCount() == 0 && eng.queuedCount() == 0) stopGraceful()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        setActive(false)
        App.app.engine.service = null
        try { stopForeground(true) } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
