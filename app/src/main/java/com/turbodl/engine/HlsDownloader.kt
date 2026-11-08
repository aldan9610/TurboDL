package com.turbodl.engine

import com.turbodl.model.Seg
import com.turbodl.model.St
import com.turbodl.model.Task
import com.turbodl.util.Kit
import com.turbodl.util.M3u
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Descargador HLS (m3u8): soporta master playlists (con eleccion de calidad),
 * AES-128, BYTERANGE, fMP4 (EXT-X-MAP) y streams en vivo.
 * Si el master declara un grupo de audio separado (EXT-X-MEDIA TYPE=AUDIO)
 * descarga tambien el audio; cuando ambas pistas son fMP4 se fusionan en un
 * unico MP4 indexado (Merge.kt). La salida fMP4 siempre se normaliza para
 * que el reproductor pueda desplazarse.
 */
class HlsDownloader(task: Task, engine: Engine) : Downloader(task, engine) {

    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val parts = TreeMap<Long, File>()
    private val partsLock = Object()
    @Volatile private var feedEnded = false
    @Volatile private var submittedUpTo = -1L
    @Volatile private var feedError: Exception? = null

    override fun run() {
        var partsDir: File? = null
        var audioFile: File? = null
        var sink: Sink? = null
        try {
            // ---- resolver playlist (master -> variante elegida o mejor) ----
            var url = task.url
            var p = M3u.parse(url, httpGetText(url))
            var masterAudio: String? = null
            if (p.master) {
                if (p.audioUri != null && task.extra().optString("audioUrl", "").isEmpty()) {
                    masterAudio = p.audioUri // grupo de audio separado del master
                }
                val chosen = p.variants.firstOrNull { it.url == task.extra().optString("variantUrl", "") }
                    ?: p.variants.maxByOrNull { it.bandwidth }
                    ?: throw DlException("lista master sin variantes")
                url = chosen.url
                task.url = url
                if (chosen.height > 0) task.putExtra("q", "${chosen.height}p")
                p = M3u.parse(url, httpGetText(url))
            }
            if (p.entries.isEmpty() && !p.endList) throw DlException("lista de medios vacia")

            // ---- duracion detectada ----
            if (p.durationSec > 0) task.putExtra("dur", Math.round(p.durationSec))

            // ---- nombre de salida ----
            var fn = task.fileName.ifEmpty { Kit.nameFromUrl(task.url) }
            val isMp4 = p.mapUri != null || fn.endsWith(".mp4", true)
            val dot = fn.lastIndexOf('.')
            if (dot > 0) fn = fn.substring(0, dot)
            fn = if (isMp4) "$fn.mp4" else "$fn.ts"
            task.fileName = fn
            task.name = fn

            // ---- punto de reanudacion ----
            val ex = task.extra()
            val resumeFrom = ex.optLong("next", 0)
            val firstIdx = if (p.entries.isNotEmpty()) p.entries[0].gidx else 0
            val skip = maxOf(resumeFrom, firstIdx)

            val cacheDir = File(engine.ctx.cacheDir, "hls" + task.id)
            partsDir = File(cacheDir, "parts")
            partsDir.mkdirs()

            val out = engine.openSink(task, fn)
            sink = out
            var baseWritten = out.size()
            if (baseWritten == 0L) {
                val mp = p.mapUri
                if (mp != null) {
                    val mb = fetchBytes(mp, p.mapRange)
                    out.append(mb, mb.size)
                    baseWritten += mb.size
                }
            }
            // total SIEMPRE en bytes; si no se puede saber (sin BYTERANGE),
            // queda -1 y el progreso se calcula por segmentos (sdone/segs).
            // (Antes se usaba el numero de segmentos como bytes: porcentaje falso.)
            task.total = if (p.endList && p.byterangeTotal > 0) p.byterangeTotal + baseWritten else -1
            task.done = baseWritten
            task.segs.clear()
            task.segs.add(Seg(0, baseWritten, if (task.total > 0) task.total else -1))
            task.state = St.RUNNING
            engine.db.update(task)

            // ---- pista de audio separada: la declara la tarea o el master ----
            val audioUrl = if (ex.optString("audioUrl", "").isNotEmpty())
                ex.optString("audioUrl", "") else (masterAudio ?: "")
            if (audioUrl.isNotEmpty()) {
                audioFile = downloadAudio(cacheDir, audioUrl)
            }

            // ---- hilo escritor: concatena partes en orden ----
            val wthread = Thread {
                var w = skip
                var bytes = baseWritten
                val buf = ByteArray(64 * 1024)
                try {
                    while (true) {
                        if (stopped && synchronized(partsLock) { parts.isEmpty() }) break
                        val f: File? = synchronized(partsLock) { parts.remove(w) }
                        if (f != null) {
                            val fs = java.io.FileInputStream(f)
                            while (true) {
                                val n = fs.read(buf)
                                if (n < 0) break
                                out.append(buf, n)
                                bytes += n
                            }
                            fs.close()
                            f.delete()
                            w++
                            task.done = bytes
                            try {
                                val o = JSONObject(task.extraJson)
                                o.put("next", w)
                                o.put("sdone", w - firstIdx)
                                task.extraJson = o.toString()
                            } catch (e: Exception) { }
                        } else {
                            val empty = synchronized(partsLock) { parts.isEmpty() }
                            if (feedEnded && empty) {
                                if (w >= submittedUpTo + 1 || feedError != null) break
                            }
                            try { Thread.sleep(80) } catch (e: InterruptedException) { break }
                        }
                    }
                } catch (e: Exception) {
                    feedError = e
                } finally {
                    task.done = bytes
                }
            }
            wthread.start()

            // ---- alimentador de segmentos ----
            val pool = Executors.newFixedThreadPool(4)
            val futs = ArrayList<java.util.concurrent.Future<*>>()
            var curUrl = url
            var first = true
            while (!stopped) {
                val list = if (first) p else M3u.parse(curUrl, httpGetText(curUrl))
                first = false
                list.entries.forEachIndexed { i, e ->
                    if (e.gidx >= skip && e.gidx > submittedUpTo) {
                        submittedUpTo = e.gidx
                        futs.add(pool.submit { segJob(e, partsDir!!) })
                        try {
                            val o = JSONObject(task.extraJson)
                            o.put("segs", if (list.endList) list.entries.size else -1)
                            task.extraJson = o.toString()
                        } catch (er: Exception) { }
                    }
                }
                if (list.endList) break
                if (list.entries.isEmpty()) {
                    try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
                } else {
                    try { Thread.sleep((list.target.coerceAtLeast(2) * 1000).toLong()) }
                    catch (e: InterruptedException) { break }
                }
                curUrl = url
            }
            feedEnded = true
            pool.shutdown()
            for (f in futs) {
                try {
                    f.get()
                } catch (e: Exception) {
                    if (!stopped) feedError = (e.cause as? Exception) ?: e
                }
            }
            wthread.join()
            partsDir?.deleteRecursively()

            if (feedError != null && !stopped) throw feedError!!
            if (stopped) {
                task.state = St.PAUSED
            } else {
                task.done = out.size()
                task.segs.clear()
                task.segs.add(Seg(0, task.done, task.done))
                out.close()
                sink = null
                // ---- fusion con audio separado (solo fMP4 video + m4a) ----
                val audioMuxable = audioFile != null && audioFile!!.exists() &&
                        task.extra().optBoolean("audioIsMp4", false)
                if (audioMuxable && isMp4) {
                    task.state = St.MERGING
                    engine.db.update(task)
                    val merged = Merge.tryMerge(engine, task, out, fn, audioFile!!)
                    if (merged) task.putExtra("note", "audio+video combinados en un MP4")
                    else {
                        Merge.normalize(engine, task, task.fileName)
                        task.state = St.DONE
                    }
                } else if (isMp4) {
                    // fMP4 sin audio separado: reconstruir indice para poder
                    // desplazarse en el reproductor
                    task.state = St.MERGING
                    engine.db.update(task)
                    Merge.normalize(engine, task, task.fileName)
                    task.state = St.DONE
                } else {
                    task.state = St.DONE
                    if (audioFile != null && audioFile!!.exists()) {
                        task.putExtra("note", "video TS con audio en archivo aparte (no combinable)")
                    }
                }
            }
            engine.db.update(task)
            engine.notifyChanged()
        } catch (e: Exception) {
            partsDir?.deleteRecursively()
            try { sink?.close() } catch (e2: Exception) { }
            if (stopped) {
                task.state = St.PAUSED
            } else {
                task.state = St.ERROR
                task.error = e.message ?: "error HLS"
            }
            engine.db.update(task)
            engine.notifyChanged()
        }
    }

    /** Descarga la playlist de audio a un archivo local temporal (m4a o aac). */
    private fun downloadAudio(cacheDir: File, audioUrl: String): File? {
        return try {
            var aurl = audioUrl
            var ap = M3u.parse(aurl, httpGetText(aurl))
            if (ap.master && ap.variants.isNotEmpty()) {
                aurl = ap.variants.maxByOrNull { it.bandwidth }!!.url
                ap = M3u.parse(aurl, httpGetText(aurl))
            }
            if (ap.entries.isEmpty()) return null
            val isMp4 = ap.mapUri != null
            val ext = if (isMp4) "m4a" else "aac"
            val out = File(cacheDir, "audio_$ext." + ext)
            val fos = java.io.FileOutputStream(out)
            if (isMp4 && ap.mapUri != null) {
                val mb = fetchBytes(ap.mapUri!!, ap.mapRange)
                fos.write(mb)
            }
            for (e in ap.entries) {
                if (stopped) break
                val b = fetchBytes(e.uri, e.byterange)
                fos.write(b)
                task.putExtra("audioDone", task.extra().optLong("audioDone", 0) + b.size)
            }
            fos.close()
            task.putExtra("audioIsMp4", isMp4)
            out
        } catch (e: Exception) {
            if (!stopped) {
                task.putExtra("note", "audio separado no disponible: ${e.message}")
            }
            null
        }
    }

    private fun segJob(e: M3u.Entry, partsDir: File) {
        if (stopped) return
        try {
            var data = fetchBytes(e.uri, e.byterange)
            val k = e.keyUri
            if (k != null) {
                val key = getKey(k)
                val iv: ByteArray = if (e.iv != null) {
                    hex(e.iv.removePrefix("0x").removePrefix("0X"))
                } else {
                    ByteBuffer.allocate(16).putLong(0).putLong(e.gidx).array()
                }
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                data = c.doFinal(data)
            }
            val f = File(partsDir, "seg_${e.gidx}.part")
            val fos = java.io.FileOutputStream(f)
            fos.write(data)
            fos.close()
            synchronized(partsLock) { parts[e.gidx] = f }
        } catch (ex: Exception) {
            if (!stopped) feedError = ex
        }
    }

    private fun getKey(uri: String): ByteArray {
        return keys.getOrPut(uri) { fetchBytes(uri, null) }
    }

    private fun fetchBytes(url: String, range: String?): ByteArray {
        val conn = open(url, range?.let { r ->
            val f = r.substringBefore('@').toLong()
            val s = r.substringAfter('@', "0").toLong()
            "bytes=$s-${s + f - 1}"
        })
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw DlException("HTTP $code en segmento")
            val out = ByteArrayOutputStream()
            val inp = conn.inputStream
            val buf = ByteArray(32 * 1024)
            while (true) {
                if (stopped) throw DlException("detenido")
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                engine.limiter.take(n)
            }
            inp.close()
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i + 1 < s.length + 1 && i / 2 < out.size) {
            out[i / 2] = s.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }
}
