package com.turbodl.engine

import com.turbodl.model.Seg
import com.turbodl.model.St
import com.turbodl.model.Task
import com.turbodl.util.Kit
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

class Probe {
    var total: Long = -1
    var ranges: Boolean = false
    var fileName: String = ""
    var finalUrl: String = ""
}

/** Enlace firmado que vencio (401/403/410): no se reintenta, se refresca. */
class LinkExpired(msg: String) : DlException(msg)

class HttpDownloader(task: Task, engine: Engine) : Downloader(task, engine) {

    companion object {
        /** Sondeo inicial: tamano, soporte de rangos y nombre real del archivo */
        fun probe(task: Task, engine: Engine): Probe {
            val p = Probe()
            val dl = HttpDownloader(task, engine)
            val conn = dl.open(task.url, "bytes=0-0")
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw DlException("HTTP $code")
                p.finalUrl = conn.url.toString()
                p.fileName = Kit.nameFromDisposition(conn.getHeaderField("Content-Disposition"))
                    ?: Kit.nameFromUrl(p.finalUrl)
                p.ranges = code == HttpURLConnection.HTTP_PARTIAL ||
                        "bytes".equals(conn.getHeaderField("Accept-Ranges"), true)
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    val cr = conn.getHeaderField("Content-Range") ?: ""
                    val m = Regex("(\\d+)\\s*$").find(cr.substringAfterLast('/'))
                    if (m != null) p.total = m.groupValues[1].toLong()
                } else {
                    val len = conn.contentLengthLong
                    p.total = if (len >= 0) len else -1
                    p.ranges = false
                }
            } finally {
                conn.disconnect()
            }
            return p
        }
    }

    override fun run() {
        var sink: Sink? = null
        try {
            task.loadSegs()
            val fname = task.fileName.ifEmpty { Kit.nameFromUrl(task.url) }
            if (task.total < 0 || task.segs.isEmpty()) {
                // sondeo pendiente o reanudacion sin total: sondear si falta
                try {
                    val p = probe(task, engine)
                    if (task.url != p.finalUrl) {
                        task.url = p.finalUrl
                        task.putExtra("redirect", 1)
                    }
                    if (task.total < 0) task.total = p.total
                } catch (e: Exception) {
                    if (task.total < 0) task.error = "sondeo: ${e.message}"
                }
            }
            task.fileName = fname
            // fase "creando archivo": comprobar espacio y pre-asignar el
            // fichero con su tamaño final antes de gastar datos descargando
            if (task.total > 0 && task.extra().optString("tree", "").isEmpty()) {
                File(task.dir).mkdirs()
                val free = File(task.dir).usableSpace
                val need = task.total + 16L * 1024 * 1024 // margen de seguridad
                if (free in 0 until need) {
                    throw DlException("espacio insuficiente: libres ${Kit.fmtBytes(free)}, hacen falta ${Kit.fmtBytes(task.total)}")
                }
            }
            task.putExtra("wait", "creando archivo…")
            engine.db.update(task)
            val sink0 = engine.openSink(task, fname)
            sink = sink0
            if (task.total > 0) sink0.truncate(task.total) // pre-asignacion (setLength)
            if (task.segs.isEmpty()) plan()
            task.putExtra("wait", "")
            task.state = St.RUNNING
            engine.db.update(task)

            var pool: java.util.concurrent.ExecutorService? = null
            try {
                pool = Executors.newFixedThreadPool(task.segs.size.coerceIn(1, 16))
                val futs = ArrayList<java.util.concurrent.Future<*>>()
                for (s in task.segs) futs.add(pool.submit { segLoop(s, sink0) })
                for (f in futs) f.get()
            } finally {
                pool?.shutdown()
            }

            if (stopped) { savePaused(sink0); return }
            task.calcDone()
            if (task.total >= 0 && task.done < task.total) {
                throw DlException("descarga incompleta (${Kit.fmtBytes(task.done)} de ${Kit.fmtBytes(task.total)})")
            }
            task.state = St.DONE
            task.done = if (task.total >= 0) task.total else sink0.size()
            task.saveSegs()
            engine.db.update(task)
            sink0.close()
            // verificacion rapida del MP4 terminado (indice moov)
            if (fname.endsWith(".mp4", true) || fname.endsWith(".m4a", true)) {
                Merge.validate(engine, task)
            }
            engine.notifyChanged()
        } catch (e: Exception) {
            try { sink?.close() } catch (e2: Exception) { }
            if (e is LinkExpired && task.extra().optString("origin", "").isNotEmpty()) {
                // enlace firmado vencido: reabrir la página de origen y
                // recapturar el enlace; la descarga reanuda desde el .part
                task.calcDone()
                task.saveSegs()
                if (!task.extra().optBoolean("refrescoAuto", false)) {
                    task.putExtra("refrescoAuto", true)
                    task.state = St.PAUSED
                    task.error = ""
                    engine.db.update(task)
                    engine.notifyChanged()
                    engine.requestLinkRefresh(task, manual = false)
                } else {
                    task.state = St.ERROR
                    task.error = "enlace caducado (${e.message}); usa «Refrescar enlace» en el menú de la tarea"
                    engine.db.update(task)
                    engine.notifyChanged()
                }
            } else onError(e)
        }
    }

    private fun plan() {
        val ex = try { JSONObject(task.extraJson) } catch (e: Exception) { JSONObject() }
        val conns = ex.optInt("conns", 4).coerceIn(1, 16)
        val ranges = ex.optBoolean("ranges", true)
        task.segs.clear()
        if (task.total > 0 && ranges && task.total > 1024 * 1024 && conns > 1) {
            val n = conns.toLong()
            val chunk = task.total / n
            for (i in 0 until n) {
                val start = i * chunk
                val end = if (i == n - 1) task.total else start + chunk
                task.segs.add(Seg(start, start, end))
            }
        } else if (task.total > 0) {
            task.segs.add(Seg(0, 0, task.total))
        } else {
            // longitud desconocida: flujo unico sin reanudacion
            task.segs.add(Seg(0, 0, -1))
        }
        task.saveSegs()
        engine.db.update(task)
    }

    private fun segLoop(s: Seg, sink: Sink) {
        var retry = 0
        val buf = ByteArray(32 * 1024)
        while (!s.done() && !stopped) {
            try {
                val range = if (s.end < 0) null else "bytes=${s.cur}-${s.end - 1}"
                val conn = open(task.url, range)
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_PARTIAL || code == HttpURLConnection.HTTP_OK) {
                    if (code == HttpURLConnection.HTTP_OK && s.end >= 0 && s.cur > s.start) {
                        // el servidor ignoro el rango: error para reintentar
                        conn.disconnect()
                        throw DlException("el servidor no acepta rangos")
                    }
                    val raw = conn.inputStream
                    val inp: InputStream =
                        if ("gzip".equals(conn.contentEncoding, true)) GZIPInputStream(raw) else raw
                    while (true) {
                        if (stopped) break
                        val n = inp.read(buf)
                        if (n < 0) break
                        synchronized(sink) {
                            sink.seekWrite(if (s.end >= 0) s.cur else -1L, buf, n)
                        }
                        s.cur += n
                        task.done += n
                        engine.limiter.take(n)
                    }
                    inp.close()
                    conn.disconnect()
                    if (s.end < 0) s.cur = s.end // marca terminado
                    retry = 0
                } else if (code == 416 && s.cur >= (if (s.end >= 0) s.end else Long.MAX_VALUE)) {
                    // rango ya completado
                    break
                } else {
                    if (code == 401 || code == 403 || code == 410) {
                        // enlace firmado vencido: sin reintentos inutiles
                        throw LinkExpired("HTTP $code")
                    }
                    throw DlException("HTTP $code")
                }
            } catch (e: Exception) {
                if (stopped) return
                if (e is LinkExpired) throw e
                retry++
                if (retry > 6) throw DlException("fallo tras $retry intentos: ${e.message}")
                try { Thread.sleep(800L * retry) } catch (ie: InterruptedException) { return }
            }
        }
    }

    private fun savePaused(sink: Sink) {
        try { sink.close() } catch (e: Exception) { }
        task.calcDone()
        task.saveSegs()
        task.state = St.PAUSED
        engine.db.update(task)
        engine.notifyChanged()
    }

    private fun onError(e: Exception) {
        task.calcDone()
        task.saveSegs()
        if (stopped) {
            task.state = St.PAUSED
        } else {
            task.state = St.ERROR
            task.error = e.message ?: e.javaClass.simpleName
        }
        engine.db.update(task)
        engine.notifyChanged()
    }
}
