package com.turbodl.engine

import com.turbodl.model.Seg
import com.turbodl.model.St
import com.turbodl.model.Task
import com.turbodl.util.Kit
import com.turbodl.util.Mpd
import java.io.File

/**
 * Descargador DASH (MPD): elige la representacion pedida (extra "repVideo")
 * o la de mayor bitrate, descarga init+segmentos a un archivo por pista y,
 * si hay audio separado, los combina por defecto en un unico MP4 (extra
 * "merge"=false lo desactiva). Tras unir los segmentos se NORMALIZA el
 * archivo (remux con indice clasico) para que se pueda buscar posiciones
 * y la duracion sea correcta en el reproductor.
 * Contenido con DRM se rechaza con mensaje claro.
 */
class DashDownloader(task: Task, engine: Engine) : Downloader(task, engine) {

    override fun run() {
        var sink: Sink? = null
        var audioFile: File? = null
        try {
            val info = Mpd.parse(httpGetText(task.url))
            if (info.drm) throw DlException("contenido protegido con DRM: no descargable")
            if (info.video.isEmpty() && info.audio.isEmpty()) {
                throw DlException("MPD sin representaciones")
            }
            if (info.durationSec > 0) task.putExtra("dur", Math.round(info.durationSec))

            val ex = task.extra()
            val repV = info.video.firstOrNull { it.id == ex.optString("repVideo", "") }
                ?: info.video.maxByOrNull { it.bandwidth }
                ?: info.audio.maxByOrNull { it.bandwidth }
                ?: throw DlException("MPD sin representaciones")
            val repA = info.audio.firstOrNull { it.id == ex.optString("repAudio", "") }
                ?: info.audio.maxByOrNull { it.bandwidth }
            if (repV.height > 0) task.putExtra("q", "${repV.height}p")

            var fn = task.fileName.ifEmpty { Kit.nameFromUrl(task.url) }
            val dot = fn.lastIndexOf('.')
            if (dot > 0) fn = fn.substring(0, dot)
            // fusion automatica cuando existe pista de audio separada
            // (extra "merge"=false la desactiva)
            val wantMerge = repA != null && ex.optBoolean("merge", true)
            val outName = if (wantMerge) "$fn.video.mp4" else "$fn.mp4"
            task.fileName = outName

            task.state = St.RUNNING
            task.total = -1
            task.done = 0
            task.segs.clear()
            task.segs.add(Seg(0, 0, -1))
            // progreso por segmentos (unidades correctas cuando no hay bytes totales)
            val segsV = repSegCount(repV)
            val segsA = if (wantMerge && repA != null) repSegCount(repA) else 0
            val segsTotal = if (segsV > 0) segsV + (if (segsA > 0) segsA else 0) else -1L
            task.putExtra("segs", segsTotal)
            task.putExtra("sdone", 0L)
            engine.db.update(task)

            val cacheDir = File(engine.ctx.cacheDir, "dash" + task.id)
            cacheDir.mkdirs()

            // ---- pista principal (video o la unica disponible) ----
            task.putExtra("fase", "video")
            val out = engine.openSink(task, outName)
            sink = out
            out.truncate(0)
            var written = 0L
            written = downloadRep(out, repV, task.url, written)

            // ---- audio separado si hay fusion ----
            task.putExtra("fase", "audio")
            if (wantMerge && repA != null) {
                val af = File(cacheDir, "audio.m4a")
                val afos = java.io.FileOutputStream(af)
                val aw = downloadRepTo(afos, repA, task.url, 0L)
                afos.close()
                audioFile = af
                task.putExtra("audioBytes", aw)
            }

            if (stopped) {
                task.state = St.PAUSED
            } else {
                task.done = written
                task.segs.clear()
                task.segs.add(Seg(0, written, written))
                if (audioFile != null) {
                    // fusion audio+video: tryMerge ya produce un MP4 indexado
                    task.state = St.MERGING
                    engine.db.update(task)
                    out.close()
                    sink = null
                    val ok = Merge.tryMerge(engine, task, out, task.fileName, audioFile!!)
                    if (!ok) {
                        // sin fusion: normalizar al menos el video suelto
                        task.state = St.MERGING
                        engine.db.update(task)
                        Merge.normalize(engine, task, task.fileName)
                        task.putExtra("note", "audio separado no combinado: quedan dos archivos")
                        task.state = St.DONE
                    }
                } else {
                    // pista unica: normalizar para poder buscar posiciones
                    task.state = St.MERGING
                    engine.db.update(task)
                    out.close()
                    sink = null
                    Merge.normalize(engine, task, task.fileName)
                    task.state = St.DONE
                }
                // subtítulos de texto del manifiesto + verificación del MP4
                val subsRep = info.other.firstOrNull {
                    it.mimeType.contains("text") && !it.mimeType.contains("mp4")
                }
                if (subsRep != null) {
                    val note = Subs.tryDownload(engine, task, "", subsRep, task.url)
                    if (note.isNotEmpty()) task.putExtra("note2", note)
                }
                Merge.validate(engine, task)
            }
            engine.db.update(task)
            engine.notifyChanged()
        } catch (e: Exception) {
            try { sink?.close() } catch (e2: Exception) { }
            if (stopped) task.state = St.PAUSED
            else {
                task.state = St.ERROR
                task.error = e.message ?: "error DASH"
            }
            engine.db.update(task)
            engine.notifyChanged()
        } finally {
            File(engine.ctx.cacheDir, "dash" + task.id).deleteRecursively()
        }
    }

    /** Numero de segmentos de una representacion; -1 si es desconocido. */
    private fun repSegCount(rep: Mpd.Rep): Long = when {
        rep.segUrls.isNotEmpty() -> rep.segUrls.size.toLong()
        rep.times.isNotEmpty() -> rep.times.size.toLong()
        else -> -1L
    }

    private fun bumpSeg() {
        if (stopped) return
        try {
            val o = task.extra()
            o.put("sdone", o.optLong("sdone", 0L) + 1)
            task.extraJson = o.toString()
        } catch (e: Exception) { }
    }

    private fun downloadRep(out: Sink, rep: Mpd.Rep, mpdUrl: String, base: Long): Long {
        var written = base
        if (rep.initTpl != null && rep.initTpl.isNotEmpty()) {
            val b = httpGetBytes(abs(rep, fill(rep.initTpl, rep), mpdUrl))
            out.append(b, b.size); written += b.size
            task.done = written
        }
        if (rep.segUrls.isNotEmpty()) {
            for (u in rep.segUrls) {
                if (stopped) break
                val b = httpGetBytes(abs(rep, u, mpdUrl))
                out.append(b, b.size); written += b.size
                task.done = written
                bumpSeg()
            }
            return written
        }
        if (rep.mediaTpl != null) {
            val times = rep.times
            if (times.isNotEmpty()) {
                var t = 0L
                for (d in times) {
                    if (stopped) break
                    val u = fill(rep.mediaTpl, rep).replace("$" + "Time" + "$", t.toString())
                    val b = httpGetBytes(abs(rep, u, mpdUrl))
                    out.append(b, b.size); written += b.size
                    task.done = written
                    bumpSeg()
                    t += d
                }
            } else {
                var n = rep.startNumber
                while (!stopped) {
                    try {
                        val u = fill(rep.mediaTpl, rep).replace("$" + "Number" + "$", n.toString())
                        val b = httpGetBytes(abs(rep, u, mpdUrl))
                        out.append(b, b.size); written += b.size
                        task.done = written
                        bumpSeg()
                    } catch (e: DlException) {
                        if (e.message?.contains("404") == true || e.message?.contains("410") == true) break
                        throw e
                    }
                    n++
                }
            }
            return written
        }
        return written
    }

    private fun downloadRepTo(fos: java.io.FileOutputStream, rep: Mpd.Rep, mpdUrl: String, base: Long): Long {
        var written = base
        var lastAudioReport = 0L
        val bufOut = object : Sink {
            override fun seekWrite(off: Long, buf: ByteArray, len: Int) {
                fos.write(buf, 0, len); written += len
                // progreso vivo del audio para la UI (cada 512 KB)
                if (written - lastAudioReport > 512 * 1024) {
                    lastAudioReport = written
                    task.putExtra("audioDone", written)
                }
            }
            override fun truncate(len: Long) { }
            override fun size(): Long = written
            override fun close() { }
        }
        return downloadRep(bufOut, rep, mpdUrl, base)
    }

    private fun fill(tpl: String, rep: Mpd.Rep): String =
        tpl.replace("$" + "RepresentationID" + "$", rep.id)

    private fun abs(rep: Mpd.Rep, u: String, mpdUrl: String): String =
        if (rep.baseUrl != null) Kit.resolveUrl(rep.baseUrl, u) else Kit.resolveUrl(mpdUrl, u)
}
