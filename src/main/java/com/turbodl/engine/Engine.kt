package com.turbodl.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import com.turbodl.db.Db
import com.turbodl.model.Kind
import com.turbodl.model.St
import com.turbodl.model.Task
import com.turbodl.service.DownloadService
import com.turbodl.ui.MainActivity
import com.turbodl.util.Kit
import com.turbodl.util.Profiles
import com.turbodl.util.Schemes
import com.turbodl.util.SpeedLimiter
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.Executors

class Engine(val ctx: Context) {
    val db = Db(ctx)
    val sp: SharedPreferences = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    val ui = Handler(Looper.getMainLooper())
    val limiter = SpeedLimiter(0)
    val tasks = LinkedHashMap<Long, Task>()
    val cats = arrayOf("General", "Video", "Audio", "Imagenes", "Documentos", "Comprimidos", "Torrents")

    @Volatile var listener: (() -> Unit)? = null
    @Volatile var service: DownloadService? = null
    @Volatile private var tickerOn = true
    @Volatile private var shuttingDown = false

    private val addPool = Executors.newSingleThreadExecutor()
    private val taskPool = Executors.newFixedThreadPool(3)
    private val active = HashMap<Long, Downloader>()
    private val lastDone = HashMap<Long, Long>()
    private var lastSave = 0L
    private var lastSpaceCheck = 0L
    private var lastSpaceTold = 0L
    private var safStore: SafStore? = null
    private var safUriStr: String? = null

    /** Carpeta por defecto dentro del almacen de la app. */
    val baseDir: File get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "TurboDL")
    val notifId = 42

    companion object {
        const val CH = "downloads"
        // acciones de la notificacion / servicio
        const val ACTION_PAUSE_ALL = "com.turbodl.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.turbodl.RESUME_ALL"
        const val ACTION_STOP = "com.turbodl.STOP"
    }

    fun userAgent(): String = Profiles.uaFor(sp)

    fun init() {
        limiter.bytesPerSec = sp.getInt("limit", 0) * 1024L
        EngineHolder.ua = userAgent()
        baseDir.mkdirs()
        cats.forEach { File(baseDir, it).mkdirs() }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH, "Descargas", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm?.createNotificationChannel(ch)
        }
        for (t in db.all()) tasks[t.id] = t
        // tareas que quedaron a medias del ultimo arranque: pausadas (reanudables
        // desde donde quedaron); con auto-resumen activado se relanzan solas
        val stale = ArrayList<Task>()
        synchronized(tasks) {
            for (t in tasks.values) if (t.state == St.RUNNING || t.state == St.MERGING) {
                t.state = St.PAUSED; t.live = false; db.update(t)
                stale.add(t)
            }
        }
        if (sp.getBoolean("autoResume", false)) {
            for (t in stale) { t.state = St.QUEUED; db.update(t) }
        }
        cleanupTemp()
        applyNoMedia()
        Thread { tickerLoop() }.apply { isDaemon = true }.start()
    }

    /** Borrar temporales de descargas interrumpidas con mas de 48 h. */
    fun cleanupTemp() {
        Thread {
            try {
                val limit = System.currentTimeMillis() - 48L * 3600 * 1000
                val cache = ctx.cacheDir
                cache.listFiles()?.forEach { f ->
                    if (f.lastModified() < limit &&
                        (f.name.startsWith("hls") || f.name.startsWith("dash") ||
                         f.name.startsWith("merge_") || f.name.startsWith("norm_"))) {
                        f.deleteRecursively()
                    }
                }
                // pedazos sueltos de torrent abandonados
                File(baseDir, cats[6]).listFiles()?.forEach { d ->
                    if (d.isDirectory && d.name.startsWith("Torrent_") && d.lastModified() < limit) {
                        val empty = d.listFiles()?.isEmpty() ?: true
                        if (empty) d.delete()
                    }
                }
            } catch (e: Exception) { }
        }.apply { isDaemon = true }.start()
    }

    /** Ocultar la carpeta de descargas de la galeria (.nomedia). */
    fun applyNoMedia() {
        Thread {
            try {
                val f = File(baseDir, ".nomedia")
                if (sp.getBoolean("noMedia", false)) {
                    if (!f.exists()) f.createNewFile()
                    cats.forEach { c -> File(File(baseDir, c), ".nomedia").createNewFile() }
                } else {
                    f.delete()
                    cats.forEach { c -> File(File(baseDir, c), ".nomedia").delete() }
                }
            } catch (e: Exception) { }
        }.apply { isDaemon = true }.start()
    }

    /** true si "solo Wi-Fi" esta activo y la red actual es de datos moviles. */
    fun wifiBlocked(): Boolean {
        if (!sp.getBoolean("soloWifi", false)) return false
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            @Suppress("DEPRECATION")
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) true
            else caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED).not()
        } catch (e: Exception) { false }
    }

    fun catDir(cat: Int): File = File(baseDir, cats[cat.coerceIn(0, cats.size - 1)])

    fun maxConcurrent(): Int = sp.getInt("maxConcurrent", 2).coerceIn(1, 4)

    // ---------------- carpeta de descarga (app o SAF) ----------------

    @Synchronized
    fun saf(): SafStore? {
        val cur = sp.getString(SafStore.PREF_TREE, null) ?: return null
        if (safStore != null && safUriStr == cur) return safStore
        return try {
            val s = SafStore.from(ctx, sp)
            safStore = s; safUriStr = cur
            s
        } catch (e: Exception) { null }
    }

    fun usingSaf(): Boolean = sp.getString("dlMode", "app") == "saf" && saf() != null

    fun destLabel(): String = if (usingSaf()) {
        val t = saf()!!.treeUri
        SafStore.treeName(ctx, t) + "/TurboDL"
    } else baseDir.absolutePath

    /** Crea (o reutiliza) el Sink para escribir el archivo de salida. */
    fun openSink(t: Task, fileName: String): Sink {
        val treeStr = t.extra().optString("tree", "")
        if (treeStr.isNotEmpty()) {
            val store = SafStore(ctx, Uri.parse(treeStr))
            // reanudacion: reusar el documento ya creado
            val prev = t.extra().optString("docUri", "")
            if (prev.isNotEmpty()) {
                try { return store.openSink(Uri.parse(prev)) } catch (e: Exception) { }
                t.putExtra("docUri", "") // documento perdido: recrear
            }
            var doc = store.rootDocUri()
            doc = store.ensureDir(doc, "TurboDL")
            val catName = if (t.kind == Kind.TORRENT) cats[6] else catNameOf(t.dir)
            doc = store.ensureDir(doc, catName)
            val mime = Kit.mimeOf(fileName)
            val fUri = store.ensureFile(doc, fileName, mime)
            t.putExtra("docUri", fUri.toString())
            return store.openSink(fUri)
        }
        File(t.dir).mkdirs()
        return FileSink(File(t.dir, fileName))
    }

    /** Nombre de categoria guardado en t.dir (local: ruta completa; SAF: etiqueta). */
    fun catNameOf(dirLabel: String): String {
        val seg = dirLabel.substringAfterLast('/')
        return cats.firstOrNull { it.equals(seg, true) } ?: cats[0]
    }

    /** Elimina el archivo de salida, local o SAF. */
    fun deleteOutput(t: Task) {
        try {
            val docStr = t.extra().optString("docUri", "")
            if (docStr.isNotEmpty()) {
                SafStore(ctx, Uri.parse(t.extra().optString("tree", ""))).delete(Uri.parse(docStr))
                return
            }
            if (t.kind == Kind.TORRENT) {
                File(t.dir).deleteRecursively()
                return
            }
            val f = File(t.path())
            if (f.exists()) if (f.isDirectory) f.deleteRecursively() else f.delete()
        } catch (e: Exception) { }
    }

    fun outputExists(t: Task): Boolean {
        val docStr = t.extra().optString("docUri", "")
        if (docStr.isNotEmpty()) return true // consultar SAF por tarea es caro; asumir si
        return t.path().isNotEmpty() && File(t.path()).exists()
    }

    // ---------------- anadir tareas ----------------

    /** Anade una tarea. enqueue=true -> queda en cola; false -> arranca ya. */
    fun add(
        url: String, name: String, kindUser: Int, cat: Int, conns: Int,
        headersJson: String, enqueue: Boolean, extraJson: String?, cb: (Task?) -> Unit
    ) {
        addPool.execute {
            try {
                val (real, warn) = Schemes.decode(url)
                if (warn.isNotEmpty()) toast(warn)
                val kind = if (kindUser > 0) kindUser else Schemes.detectKind(real)
                val t = Task()
                t.url = real
                t.kind = kind
                t.headersJson = headersJson
                t.time = System.currentTimeMillis()
                if (extraJson != null && extraJson.isNotEmpty()) t.extraJson = extraJson
                t.putExtra("conns", conns)
                t.putExtra("cat", cat)
                val explicitName = name.isNotEmpty()
                t.name = if (explicitName) name else Kit.nameFromUrl(real)

                // carpeta de destino segun modo (app propia o arbol SAF)
                val wantSaf = usingSaf() && kind != Kind.TORRENT
                if (wantSaf) {
                    t.putExtra("tree", saf()!!.treeUri.toString())
                    t.dir = destLabel() + "/" + cats[cat.coerceIn(0, cats.size - 1)]
                    t.fileName = t.name // openSink/ensureFile evita duplicados
                } else {
                    t.dir = catDir(cat).absolutePath
                    t.fileName = Kit.uniqueName(t.dir, t.name)
                }

                if (kind == Kind.HTTP || kind == Kind.WEBDAV_DIR) {
                    try {
                        val p = HttpDownloader.probe(t, this)
                        t.url = p.finalUrl
                        t.total = p.total
                        if (explicitName) {
                            // nombre descriptivo dado por el sniffer ("Sitio - Titulo"):
                            // se conserva aunque el sondeo encuentre otro en la URL
                            t.fileName = if (wantSaf) t.name else Kit.uniqueName(t.dir, t.name)
                        } else {
                            t.fileName = if (wantSaf) p.fileName else Kit.uniqueName(t.dir, p.fileName)
                            t.name = t.fileName
                        }
                        t.putExtra("ranges", p.ranges)
                    } catch (e: Exception) {
                        t.error = "sondeo: ${e.message}"
                    }
                } else if (kind == Kind.TORRENT) {
                    t.name = if (explicitName) t.name else "Torrent"
                    t.fileName = ""
                    // la sonda de metadatos puede haber preparado ya la carpeta
                    // y el handle pausado en la sesión (selección de archivos):
                    // se respeta "tdir" para que el torrent caiga donde sondeó
                    val tdir = t.extra().optString("tdir", "")
                    t.dir = if (tdir.isNotEmpty()) tdir
                    else File(catDir(cat), "Torrent_" + System.currentTimeMillis()).absolutePath
                } else if (kind == Kind.HLS) {
                    val fn = t.fileName
                    if (fn.endsWith(".m3u8", true)) t.fileName = fn.substring(0, fn.length - 5) + ".ts"
                } else if (kind == Kind.DASH) {
                    val fn = t.fileName
                    if (fn.endsWith(".mpd", true)) t.fileName = fn.substring(0, fn.length - 4) + ".mp4"
                }
                t.name = if (name.isNotEmpty()) name else t.name

                t.state = St.QUEUED // el planificador arranca cuando haya hueco
                db.insert(t)
                synchronized(tasks) { tasks[t.id] = t }
                ui.post { cb(t); notifyChanged() }
                if (!enqueue) startNow(t)
            } catch (e: Exception) {
                ui.post { toast("Error: ${e.message}"); cb(null) }
            }
        }
    }

    // ---------------- control de tareas / cola ----------------

    /** Arranca ya, incluso saltandose el limite de simultaneas. */
    fun startNow(t: Task) {
        synchronized(this) {
            if (active.containsKey(t.id) || t.state == St.RUNNING) return
            if (shuttingDown) return
            if (t.state != St.MERGING && wifiBlocked()) {
                // solo Wi-Fi activo y hay datos moviles: queda esperando
                t.state = St.QUEUED
                t.putExtra("wait", "esperando Wi-Fi (solo Wi-Fi activado)")
                db.update(t)
                notifyChanged()
                return
            }
            t.putExtra("wait", "")
            t.state = St.RUNNING
            t.error = ""
            t.live = true
            db.update(t)
            val dl: Downloader = newDownloader(t)
            t.tag = dl
            active[t.id] = dl
            taskPool.execute {
                runWithRetries(t)
                synchronized(this@Engine) { active.remove(t.id) }
                t.live = false
                notifyChanged()
            }
            notifyChanged()
        }
    }

    private fun newDownloader(t: Task): Downloader = when (t.kind) {
        Kind.FTP -> FtpDownloader(t, this)
        Kind.HLS -> HlsDownloader(t, this)
        Kind.DASH -> DashDownloader(t, this)
        Kind.TORRENT -> TorrentDownloader(t, this)
        else -> HttpDownloader(t, this)
    }

    /**
     * Ejecuta la tarea y, si falla, reintenta con espera progresiva
     * (5s, 15s, 60s). El usuario puede pausar durante la espera.
     */
    private fun runWithRetries(t: Task) {
        val maxRetries = sp.getInt("retries", 1).coerceIn(0, 3)
        var attempt = 0
        while (true) {
            val dl = newDownloader(t)
            t.tag = dl
            synchronized(this) { active[t.id] = dl }
            try {
                dl.run()
            } catch (e: Exception) {
                t.state = St.ERROR
                t.error = e.message ?: "error"
                db.update(t)
            }
            if (t.state != St.ERROR || attempt >= maxRetries || shuttingDown) return
            attempt++
            val delay = longArrayOf(5, 15, 60)[(attempt - 1).coerceAtMost(2)]
            t.state = St.QUEUED
            t.error = ""
            t.putExtra("wait", "reintento $attempt/$maxRetries en ${delay}s…")
            db.update(t)
            notifyChanged()
            var waited = 0L
            while (waited < delay * 1000) {
                Thread.sleep(500)
                waited += 500
                if (shuttingDown || t.state != St.QUEUED) return // el usuario intervino
            }
            t.putExtra("wait", "")
            t.state = St.RUNNING
            t.live = true
            db.update(t)
            notifyChanged()
        }
    }

    fun runningCount(): Int = synchronized(this) { active.size }

    fun queuedCount(): Int = synchronized(tasks) { tasks.values.count { it.state == St.QUEUED } }

    fun pause(t: Task) {
        (t.tag as? Downloader)?.stop()
        if (t.state == St.RUNNING || t.state == St.QUEUED || t.state == St.MERGING) {
            t.state = St.PAUSED
            t.live = false
            db.update(t)
            notifyChanged()
        }
    }

    fun enqueue(t: Task) {
        (t.tag as? Downloader)?.stop()
        t.state = St.QUEUED
        t.live = false
        db.update(t)
        notifyChanged()
    }

    // ---------------- refresco de enlace caducado ----------------

    private val refreshing = HashSet<Long>()

    /**
     * Reabre la página de origen en un WebView oculto, recaptura el enlace
     * fresco y reanuda la descarga desde el byte ya descargado (el .part y
     * los segmentos se conservan). Estilo 1DM; auto y manual.
     */
    fun requestLinkRefresh(t: Task, manual: Boolean = false) {
        val origin = t.extra().optString("origin", "")
        if (origin.isEmpty()) {
            if (manual) toast("Esta tarea no guarda página de origen")
            return
        }
        if (!refreshing.add(t.id)) return
        t.state = St.QUEUED
        t.live = false
        t.error = ""
        t.putExtra("wait", if (manual) "refrescando enlace…" else "enlace caducado: refrescando…")
        db.update(t)
        notifyChanged()
        toast(if (manual) "Refrescando enlace…"
        else "Enlace caducado: reabriendo la página de origen…")
        LinkRefresher.refresh(this, t, origin) { ok, msg ->
            refreshing.remove(t.id)
            if (ok) {
                t.putExtra("wait", "")
                db.update(t)
                toast("Enlace refrescado: reanudando desde ${Kit.fmtBytes(t.done)}")
                if (wifiBlocked()) {
                    t.state = St.QUEUED
                    db.update(t)
                    notifyChanged()
                } else startNow(t)
            } else {
                t.state = St.ERROR
                t.error = "no se pudo refrescar el enlace: $msg"
                t.putExtra("wait", "")
                db.update(t)
                notifyChanged()
                toast("No se pudo refrescar el enlace: $msg")
            }
        }
    }

    fun resumeAll() {
        val list = synchronized(tasks) { tasks.values.toList() }
        val paused = list.filter { it.state == St.PAUSED || it.state == St.ERROR }
        for (t in paused) {
            t.state = St.QUEUED
            db.update(t)
        }
        // arrancar inmediatamente hasta el limite; el resto queda en cola
        paused.take(maxConcurrent()).forEach { startNow(it) }
        notifyChanged()
    }

    fun pauseAll() {
        val list = synchronized(tasks) { tasks.values.toList() }
        for (t in list) if (t.live || t.state == St.RUNNING || t.state == St.QUEUED) {
            (t.tag as? Downloader)?.stop()
            t.state = St.PAUSED
            t.live = false
            db.update(t)
        }
        notifyChanged()
    }

    fun delete(t: Task, delFile: Boolean) {
        (t.tag as? Downloader)?.stop()
        synchronized(this) { active.remove(t.id) }
        synchronized(tasks) { tasks.remove(t.id) }
        lastDone.remove(t.id)
        db.delete(t.id)
        if (t.kind == Kind.TORRENT) {
            try {
                val ih = try { JSONObject(t.extraJson).optString("ih", "") } catch (e: Exception) { "" }
                if (ih.isNotEmpty()) {
                    val sm = session()
                    val th = sm.find(com.frostwire.jlibtorrent.Sha1Hash(ih))
                    if (th != null && th.isValid) sm.remove(th)
                }
            } catch (e: Exception) { }
        }
        if (delFile) deleteOutput(t)
        notifyChanged()
    }

    /** Detiene todo y apaga el servicio: usado por el boton "Salir". */
    fun shutdown() {
        shuttingDown = true
        pauseAll()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        } catch (e: Exception) { }
        val s = service
        ui.post {
            try { s?.stopSelf() } catch (e: Exception) { }
        }
    }

    // ---------------- sesion BitTorrent (una por proceso) ----------------

    @Volatile
    private var sm: com.frostwire.jlibtorrent.SessionManager? = null

    @Synchronized
    fun session(): com.frostwire.jlibtorrent.SessionManager {
        var s = sm
        if (s == null) {
            s = com.frostwire.jlibtorrent.SessionManager()
            try {
                val sp = com.frostwire.jlibtorrent.SessionParams()
                s.start(sp)
            } catch (e: Exception) {
                s.start()
            }
            sm = s
        }
        return s
    }

    fun activeCount(): Int = synchronized(this) { active.size }

    fun notifyChanged() {
        ui.post { listener?.invoke() }
    }

    fun toast(msg: String) {
        ui.post { ToastCompat.show(ctx, msg) }
    }

    // ---------------- planificador / ticker ----------------

    private fun tickerLoop() {
        while (tickerOn) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) { break }
            if (shuttingDown) break
            val now = System.currentTimeMillis()
            var running = 0
            var totalSpeed = 0L
            var queued = 0
            val snap = synchronized(tasks) { tasks.values.toList() }
            for (t in snap) {
                if (t.live) {
                    val ld = lastDone[t.id] ?: 0L
                    t.speed = (t.done - ld).coerceAtLeast(0L)
                    lastDone[t.id] = t.done
                    if (t.state == St.RUNNING) {
                        running++
                        totalSpeed += t.speed
                    }
                } else t.speed = 0
                if (t.state == St.QUEUED) queued++
            }
            // planificador de cola: arranca la siguiente si hay hueco
            if (queued > 0 && running < maxConcurrent()) {
                // saltar las que esperan Wi-Fi
                val next = snap.filter { it.state == St.QUEUED && !wifiBlocked() }
                    .minByOrNull { it.time }
                if (next != null) startNow(next)
            }
            if (now - lastSave > 4000) {
                lastSave = now
                for (t in snap) {
                    if (t.live) {
                        t.saveSegs()
                        db.update(t)
                    }
                }
            }
            // guarda de espacio libre: con el disco casi lleno las descargas
            // terminan en error y dejan archivos truncados; mejor pausar la
            // cola y avisar con tiempo. Se consulta cada 8 s y el aviso se
            // repite como mucho una vez por minuto.
            if (now - lastSpaceCheck > 8000) {
                lastSpaceCheck = now
                if (running > 0 || queued > 0) {
                    val free = try {
                        android.os.StatFs(baseDir.absolutePath).availableBytes
                    } catch (e: Exception) { -1L }
                    val minFree = sp.getInt("minFreeMb", 150) * 1024L * 1024L
                    if (free >= 0 && free < minFree && now - lastSpaceTold > 60000) {
                        lastSpaceTold = now
                        pauseAll()
                        toast("Espacio insuficiente (${Kit.fmtBytes(free)} libres): " +
                                "descargas pausadas. Libera espacio y pulsa «Todo».")
                    }
                }
            }
            val r = running
            val sp2 = totalSpeed
            val q = queued
            ui.post {
                listener?.invoke()
                val svc = service
                if (svc != null) {
                    // sin actividad: apagar el servicio para no gastar bateria
                    if (r == 0 && q == 0 && !shuttingDown) {
                        svc.stopGraceful()
                    } else {
                        svc.updateForeground(buildNotif(r, sp2), r > 0)
                    }
                }
            }
        }
    }

    fun buildNotif(running: Int, speed: Long): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            piFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
        val pausePi = PendingIntent.getService(
            ctx, 1, Intent(ctx, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            piFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
        val stopPi = PendingIntent.getService(
            ctx, 2, Intent(ctx, DownloadService::class.java).setAction(ACTION_STOP),
            piFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )
        val resumePi = PendingIntent.getService(
            ctx, 3, Intent(ctx, DownloadService::class.java).setAction(ACTION_RESUME_ALL),
            piFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        // tarea principal para un texto descriptivo
        val snap = synchronized(tasks) { tasks.values.toList() }
        val pausedCount = snap.count { it.state == St.PAUSED }
        val top = snap.filter { it.state == St.RUNNING || it.state == St.MERGING }
            .maxByOrNull { it.done }
        val title = when {
            running > 0 -> "TurboDL · $running descarga${if (running > 1) "s" else ""} activa${if (running > 1) "s" else ""}"
            else -> "TurboDL · sin descargas activas"
        }
        val text = StringBuilder()
        if (top != null) {
            text.append(top.name)
            val pct = top.pct()
            if (pct >= 0) text.append(" · ").append(pct).append("%")
            if (top.total > 0) {
                text.append(" · ").append(Kit.fmtBytes(top.done)).append("/").append(Kit.fmtBytes(top.total))
            } else {
                text.append(" · ").append(Kit.fmtBytes(top.done))
            }
            val ex = top.extra()
            if (ex.optLong("segs", -1) > 0) {
                text.append(" · seg ").append(ex.optLong("sdone", 0)).append("/").append(ex.optLong("segs"))
            }
        }
        if (speed > 0) text.append(" · ").append(Kit.fmtSpeed(speed))
        if (top == null && speed <= 0) text.append("Mantengo la app viva en segundo plano. Usa «Detener» para cerrar.")

        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CH)
        else @Suppress("DEPRECATION") Notification.Builder(ctx)
        b.setContentTitle(title)
            .setContentText(text.toString())
            .setStyle(Notification.BigTextStyle().bigText(text.toString()))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(running > 0)
            .setOnlyAlertOnce(true)
        if (top != null) {
            val pct = top.pct()
            if (pct >= 0) b.setProgress(100, pct, false)
        }
        if (running > 0) b.addAction(0, "⏸ Pausar todo", pausePi)
        else if (pausedCount > 0) b.addAction(0, "⏵ Reanudar todo", resumePi)
        b.addAction(0, "✕ Detener servicio", stopPi)
        return b.build()
    }

    fun piFlags(base: Int): Int =
        if (Build.VERSION.SDK_INT >= 23) base or PendingIntent.FLAG_IMMUTABLE else base

    fun persistNow(t: Task) {
        t.saveSegs()
        db.update(t)
    }
}

/** Toast seguro desde cualquier hilo (ya se invoca en el principal). */
private object ToastCompat {
    fun show(ctx: Context, msg: String) {
        try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show() }
        catch (e: Exception) { }
    }
}
