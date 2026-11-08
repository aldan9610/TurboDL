package com.turbodl.engine

import android.util.Log
import com.turbodl.model.St
import com.turbodl.model.Task
import com.turbodl.util.Kit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Fusion de pistas audio+video en un unico MP4 usando org.mp4parser
 * (isoparser + muxer, JARs vendidos en app/libs). Se usa cuando un stream
 * DASH o HLS entrega video y audio por separado (fMP4) o cuando el usuario
 * tiene un MP4 + M4A. Si falla, los archivos se conservan por separado.
 */
object Merge {

    private const val TAG = "TurboDL"

    /**
     * Combina el video ya descargado (task) con el archivo de audio y
     * reescribe la salida como MP4. `sink` debe estar cerrado.
     * @return true si la fusion tuvo exito y la salida fue reemplazada.
     */
    fun tryMerge(engine: Engine, task: Task, sink: Sink, videoName: String, audioFile: File): Boolean {
        var tmpVideo: File? = null
        var tmpOut: File? = null
        var oldDocUri: String? = null
        try {
            // 1. obtener el video como archivo local (SAF -> copia a cache)
            val docStr = task.extra().optString("docUri", "")
            val v: File = if (docStr.isNotEmpty()) {
                val f = File(engine.ctx.cacheDir, "merge_v_${task.id}.mp4")
                engine.ctx.contentResolver.openInputStream(android.net.Uri.parse(docStr))?.use { inp ->
                    java.io.FileOutputStream(f).use { out -> inp.copyTo(out, 128 * 1024) }
                } ?: return false
                f
            } else {
                val f = File(task.path())
                if (!f.exists()) return false else f
            }
            tmpVideo = v

            // 2. leer pistas
            val mv = org.mp4parser.muxer.container.mp4.MovieCreator.build(v.absolutePath)
            val ma = org.mp4parser.muxer.container.mp4.MovieCreator.build(audioFile.absolutePath)

            val combined = org.mp4parser.muxer.Movie()
            var addedVideo = 0
            for (t in mv.tracks) if (t.handler == "vide") { combined.addTrack(t); addedVideo++ }
            var addedAudio = 0
            for (t in ma.tracks) if (t.handler == "soun") { combined.addTrack(t); addedAudio++ }
            if (addedVideo == 0) {
                // el "video" no tenia pista de video: usar todas sus pistas
                if (mv.tracks.isEmpty()) return false
                combined.addTrack(mv.tracks[0])
            }
            if (addedAudio == 0 && ma.tracks.isNotEmpty() && combined.tracks.none { it.handler == "soun" }) {
                combined.addTrack(ma.tracks[0])
            }
            if (combined.tracks.none { it.handler == "soun" }) return false

            // 3. reconstruir
            tmpOut = File(engine.ctx.cacheDir, "merge_out_${task.id}.mp4")
            val container = org.mp4parser.muxer.builder.DefaultMp4Builder().build(combined)
            java.io.FileOutputStream(tmpOut).use { fos ->
                container.writeContainer(fos.channel)
            }

            // 4. reescribir la salida final (local o SAF) con el nombre limpio
            oldDocUri = docStr.ifEmpty { null }
            val finalName = cleanName(videoName)
            if (finalName != videoName) {
                // destino nuevo: forzar la creacion de un documento con ese nombre
                task.putExtra("docUri", "")
            }
            val out = engine.openSink(task, finalName)
            out.truncate(0)
            java.io.FileInputStream(tmpOut).use { fis ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = fis.read(buf)
                    if (n < 0) break
                    out.append(buf, n)
                }
            }
            out.close()
            // 5. borrar el archivo de video original (quedo duplicado)
            try {
                if (oldDocUri != null) {
                    SafStore(engine.ctx, android.net.Uri.parse(task.extra().optString("tree", "")))
                        .delete(android.net.Uri.parse(oldDocUri))
                } else if (finalName != videoName) {
                    v.parentFile?.let { File(it, videoName).delete() }
                }
            } catch (e: Exception) { }
            task.fileName = finalName
            task.name = finalName
            task.total = tmpOut.length()
            task.done = task.total
            task.state = St.DONE
            return true
        } catch (e: Exception) {
            Log.w(TAG, "merge fallo: ${e.message}")
            task.putExtra("note", "no se pudo combinar: ${e.message}; pistas guardadas por separado")
            return false
        } finally {
            if (task.extra().optString("docUri", "").isNotEmpty()) tmpVideo?.delete()
            tmpOut?.delete()
        }
    }

    /** "nombre.video.mp4" / "nombre.audio.m4a" -> "nombre.mp4"; "x.ts" -> "x.mp4" */
    fun cleanName(n: String): String {
        var base = n
        for (suf in arrayOf(".video.mp4", ".audio.m4a", ".video.ts", ".audio.aac")) {
            if (base.endsWith(suf, true)) {
                base = base.substring(0, base.length - suf.length)
                return "$base.mp4"
            }
        }
        val dot = base.lastIndexOf('.')
        if (dot > 0) base = base.substring(0, dot)
        return "$base.mp4"
    }

    /**
     * Normaliza un MP4 fragmentado (init + segmentos DASH/HLS concatenados)
     * reconstruyendolo con un moov clasico e indexado. Sin este paso, el
     * archivo une los bytes correctamente pero muchos reproductores no pueden
     * desplazarse por el (vuelven al inicio) y muestran duracion 0.
     * No recodifica: solo reorganiza cajas, costo de CPU/RAM minimo.
     */
    fun normalize(engine: Engine, task: Task, videoName: String): Boolean {
        var tmpOut: File? = null
        var tmpVideo: File? = null
        try {
            val docStr = task.extra().optString("docUri", "")
            val v: File = if (docStr.isNotEmpty()) {
                val f = File(engine.ctx.cacheDir, "norm_${task.id}.mp4")
                engine.ctx.contentResolver.openInputStream(android.net.Uri.parse(docStr))?.use { inp ->
                    java.io.FileOutputStream(f).use { out -> inp.copyTo(out, 128 * 1024) }
                } ?: return false
                f
            } else {
                val f = File(task.path())
                if (!f.exists()) return false else f
            }
            tmpVideo = v

            val movie = org.mp4parser.muxer.container.mp4.MovieCreator.build(v.absolutePath)
            if (movie.tracks.isEmpty()) return false
            tmpOut = File(engine.ctx.cacheDir, "norm_out_${task.id}.mp4")
            val container = org.mp4parser.muxer.builder.DefaultMp4Builder().build(movie)
            java.io.FileOutputStream(tmpOut).use { fos ->
                container.writeContainer(fos.channel)
            }

            val out = engine.openSink(task, videoName)
            out.truncate(0)
            java.io.FileInputStream(tmpOut).use { fis ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = fis.read(buf)
                    if (n < 0) break
                    out.append(buf, n)
                }
            }
            out.close()
            task.total = tmpOut.length()
            task.done = task.total
            task.putExtra("note", "optimizado para reproducción (índice de búsqueda reconstruido)")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "normalize fallo: ${e.message}")
            task.putExtra("note", "no se pudo reconstruir el índice: ${e.message}")
            return false
        } finally {
            // el temporal de video solo se borra si era copia (SAF); el local
            // es el propio archivo de salida ya reescrito
            if (task.extra().optString("docUri", "").isNotEmpty()) tmpVideo?.delete()
            tmpOut?.delete()
        }
    }

    /**
     * Comprueba que un MP4 terminado tiene indice clasico (ftyp+moov al
     * inicio) y un tamano razonable; deja la verificacion en "ok"/"note".
     * Lee solo la cabecera: costo nulo incluso en equipos modestos.
     */
    fun validate(engine: Engine, task: Task) {
        try {
            val docStr = task.extra().optString("docUri", "")
            var head = ByteArray(64 * 1024)
            if (docStr.isNotEmpty()) {
                engine.ctx.contentResolver.openInputStream(android.net.Uri.parse(docStr))?.use {
                    val n = it.read(head)
                    if (n >= 0) head = head.copyOf(n)
                } ?: return
            } else {
                val f = File(task.path())
                if (!f.exists() || f.length() < 2048) {
                    task.putExtra("ok", "0")
                    task.putExtra("note", "archivo sospechosamente pequeño")
                    return
                }
                java.io.FileInputStream(f).use { inp ->
                    val n = inp.read(head)
                    if (n >= 0) head = head.copyOf(n)
                }
            }
            val s = String(head, Charsets.ISO_8859_1)
            val hasFtyp = s.contains("ftyp")
            val hasMoov = s.contains("moov")
            if (hasFtyp && hasMoov) {
                task.putExtra("ok", "1")
                if (task.extra().optString("note", "").isEmpty()) {
                    task.putExtra("note", "MP4 verificado: índice correcto, se puede buscar posiciones")
                }
            } else if (hasFtyp) {
                task.putExtra("ok", "0")
                task.putExtra("note", "MP4 sin índice al inicio: el reproductor podría no desplazarse")
            } else {
                // TS u otro formato: sin verificacion de indice
                task.putExtra("ok", "1")
            }
        } catch (e: Exception) { }
    }

    /**
     * Extrae la pista de audio de un MP4 ya descargado a un M4A junto al
     * original; la nueva tarea aparece como completada en la lista.
     * @return nombre del archivo creado o null.
     */
    fun extractAudio(engine: Engine, task: Task): String? {
        var tmpOut: File? = null
        var tmpSrc: File? = null
        try {
            val docStr = task.extra().optString("docUri", "")
            val src: File = if (docStr.isNotEmpty()) {
                val f = File(engine.ctx.cacheDir, "aud_${task.id}.mp4")
                engine.ctx.contentResolver.openInputStream(android.net.Uri.parse(docStr))?.use { inp ->
                    FileOutputStream(f).use { out -> inp.copyTo(out, 128 * 1024) }
                } ?: return null
                f
            } else {
                val f = File(task.path())
                if (!f.exists()) return null else f
            }
            tmpSrc = src

            val mv = org.mp4parser.muxer.container.mp4.MovieCreator.build(src.absolutePath)
            val audio = org.mp4parser.muxer.Movie()
            for (t in mv.tracks) if (t.handler == "soun") audio.addTrack(t)
            if (audio.tracks.isEmpty()) return null

            tmpOut = File(engine.ctx.cacheDir, "aud_out_${task.id}.m4a")
            val container = org.mp4parser.muxer.builder.DefaultMp4Builder().build(audio)
            FileOutputStream(tmpOut).use { fos -> container.writeContainer(fos.channel) }

            // destino: misma carpeta que el video, nombre "<base> (audio).m4a"
            val base = task.fileName.removeSuffix(".mp4").removeSuffix(".MP4")
            val outName = "$base (audio).m4a"
            val mime = Kit.mimeOf(outName)
            var docUriOut = ""
            if (docStr.isNotEmpty()) {
                val store = SafStore(engine.ctx, android.net.Uri.parse(task.extra().optString("tree", "")))
                var doc = store.rootDocUri()
                doc = store.ensureDir(doc, "TurboDL")
                doc = store.ensureDir(doc, "Audio")
                val fUri = store.ensureFile(doc, outName, mime)
                store.openSink(fUri).use { out ->
                    FileInputStream(tmpOut).use { fis ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = fis.read(buf)
                            if (n < 0) break
                            out.append(buf, n)
                        }
                    }
                }
                docUriOut = fUri.toString()
            } else {
                val outF = File(File(task.dir), outName)
                if (outF.exists()) outF.delete()
                tmpOut.copyTo(outF, true, 128 * 1024)
            }

            // registrar como tarea completada propia
            val nt = Task()
            nt.url = task.url
            nt.kind = task.kind
            nt.name = outName
            nt.dir = if (docUriOut.isNotEmpty()) task.dir + "/Audio" else task.dir
            nt.fileName = outName
            nt.total = tmpOut.length()
            nt.done = nt.total
            nt.state = St.DONE
            nt.time = System.currentTimeMillis()
            if (docUriOut.isNotEmpty()) {
                nt.putExtra("tree", task.extra().optString("tree", ""))
                nt.putExtra("docUri", docUriOut)
            }
            nt.putExtra("q", "audio")
            engine.db.insert(nt)
            synchronized(engine.tasks) { engine.tasks[nt.id] = nt }
            engine.notifyChanged()
            return outName
        } catch (e: Exception) {
            Log.w(TAG, "extractAudio fallo: ${e.message}")
            return null
        } finally {
            if (task.extra().optString("docUri", "").isNotEmpty()) tmpSrc?.delete()
            tmpOut?.delete()
        }
    }
}
