package com.turbodl.engine

import android.util.Base64
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.Sha1Hash
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.turbodl.model.Seg
import com.turbodl.model.St
import com.turbodl.model.Task
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Descargador BitTorrent (magnet / .torrent) sobre jlibtorrent.
 * Sigue sembrando tras completar mientras la app este viva.
 * Los torrents requieren carpeta real: si el destino es SAF se usa la
 * carpeta interna de la app y se avisa.
 */
class TorrentDownloader(task: Task, engine: Engine) : Downloader(task, engine) {

    companion object {
        /** Extrae el info-hash (hex) de un magnet:?xt=urn:btih:... */
        fun magnetHash(magnet: String): String? {
            try {
                val xt = magnet.substringAfter("xt=urn:btih:").substringBefore('&').trim()
                if (xt.length == 40 && xt.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return xt.lowercase(
                    Locale.US
                )
                if (xt.length == 32) {
                    val bytes = base32decode(xt.uppercase(Locale.US))
                    if (bytes != null && bytes.size == 20) {
                        val sb = StringBuilder()
                        for (b in bytes) sb.append(String.format(Locale.US, "%02x", b))
                        return sb.toString()
                    }
                }
            } catch (e: Exception) { }
            return null
        }

        private fun base32decode(s: String): ByteArray? {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            var bits = 0
            var value = 0
            val out = java.io.ByteArrayOutputStream()
            for (c in s) {
                val idx = alphabet.indexOf(c)
                if (idx < 0) return null
                value = (value shl 5) or idx
                bits += 5
                if (bits >= 8) {
                    out.write((value ushr (bits - 8)) and 0xFF)
                    bits -= 8
                }
            }
            return out.toByteArray()
        }
    }

    @Volatile
    private var handle: TorrentHandle? = null

    override fun stop() {
        stopped = true
        try { handle?.pause() } catch (e: Exception) { }
    }

    fun removeFromSession() {
        try {
            val th = handle
            val sm = engine.session()
            if (th != null && th.isValid) sm.remove(th)
        } catch (e: Exception) { }
    }

    // ---------------- metadatos previos (selección de archivos) ----------------

    /** Archivo dentro del torrent (ruta relativa y tamaño). */
    class TFile(val path: String, val size: Long)

    /** Metadatos leidos de un magnet/.torrent sin empezar a descargar. */
    class Meta(val hash: String, val name: String, val total: Long, val files: List<TFile>)

    /**
     * Lee la lista de archivos de un torrent ANTES de añadir la tarea:
     *  - .torrent (http/content/archivo local): se parsea localmente, sin sesión.
     *  - magnet: se añade a la sesión, se espera la metadata (DHT/peers) y se
     *    pausa el handle para que no descargue mientras el usuario elige.
     * Devuelve null si no se pudo (timeout sin peers, cancelado...). El handle
     * queda PAUSADO en la sesión; la tarea que usa "ih"+"tdir" lo reutiliza y
     * si el usuario cancela el diálogo hay que llamar a removeProbe.
     */
    fun probeMetadata(engine: Engine, url: String, saveDir: File,
                      timeoutSec: Int = 45, cancelled: () -> Boolean = { false }): Meta? {
        val sm = engine.session()
        try {
            var hash: Sha1Hash? = null
            var ti: TorrentInfo? = null
            if (url.startsWith("magnet:", true)) {
                val hex = magnetHash(url) ?: return null
                hash = Sha1Hash(hex)
                if (sm.find(hash) == null) sm.download(url, saveDir)
                val dl = System.currentTimeMillis() + timeoutSec * 1000L
                while (System.currentTimeMillis() < dl) {
                    if (cancelled()) return null
                    val th = sm.find(hash)
                    if (th != null && th.isValid && th.status().hasMetadata()) {
                        try { th.pause() } catch (e: Exception) { }
                        ti = th.torrentFile()
                        break
                    }
                    Thread.sleep(1000)
                }
                if (ti == null) return null
            } else {
                val f = obtainTorrentFileStatic(engine, url)
                ti = TorrentInfo(f)
                if (!ti.isValid) return null
            }
            val t = ti!!
            val fs = t.files()
            val files = ArrayList<TFile>(fs.numFiles())
            for (i in 0 until fs.numFiles()) {
                files.add(TFile(fs.filePath(i), fs.fileSize(i)))
            }
            val h = hash ?: t.infoHash()
            return Meta(h.toHex(), t.name(), t.totalSize(), files)
        } catch (e: Exception) {
            return null
        }
    }

    /** Quita de la sesión el handle pausado que dejó probeMetadata. */
    fun removeProbe(engine: Engine, hashHex: String) {
        try {
            val sm = engine.session()
            val th = sm.find(Sha1Hash(hashHex))
            if (th != null && th.isValid) sm.remove(th)
        } catch (e: Exception) { }
    }

    /** Versión estática de obtainTorrentFile para la sonda (sin tarea). */
    private fun obtainTorrentFileStatic(engine: Engine, u: String): File {
        if (u.startsWith("content://") || u.startsWith("file://")) {
            val src = android.net.Uri.parse(u)
            val dst = File(engine.ctx.cacheDir, "torrent_" + System.currentTimeMillis() + ".torrent")
            val inp = engine.ctx.contentResolver.openInputStream(src)
                ?: throw DlException("no se pudo abrir $u")
            val out = java.io.FileOutputStream(dst)
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            inp.close(); out.close()
            return dst
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            val dst = File(engine.ctx.cacheDir, "torrent_" + System.currentTimeMillis() + ".torrent")
            val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", EngineHolder.ua)
            if (conn.responseCode !in 200..299) throw DlException("HTTP ${conn.responseCode}")
            val out = java.io.FileOutputStream(dst)
            conn.inputStream.use { inp -> inp.copyTo(out) }
            out.close()
            conn.disconnect()
            return dst
        }
        val f = File(u)
        if (f.exists()) return f
        throw DlException("origen de .torrent no soportado: $u")
    }

    override fun run() {
        var ti: TorrentInfo? = null
        try {
            // los torrents necesitan carpeta real (no SAF)
            if (task.extra().optString("tree", "").isNotEmpty()) {
                task.putExtra("tree", "")
                task.putExtra("docUri", "")
                task.dir = File(engine.catDir(6), "Torrent_" + System.currentTimeMillis()).absolutePath
                task.putExtra("note", "torrent guardado en la carpeta interna (requiere carpeta real)")
            }
            val sm = engine.session()
            File(task.dir).mkdirs()

            var hash: Sha1Hash? = null
            if (task.url.startsWith("magnet:", true)) {
                val hex = magnetHash(task.url)
                if (hex == null) throw DlException("magnet sin info-hash valido")
                hash = Sha1Hash(hex)
                if (sm.find(hash) == null) {
                    sm.download(task.url, File(task.dir))
                }
            } else {
                // .torrent: http(s) o content:// o archivo local
                val f = obtainTorrentFile()
                ti = TorrentInfo(f)
                if (!ti.isValid) throw DlException("archivo .torrent invalido")
                hash = ti.infoHash()
                task.name = ti.name()
                task.total = ti.totalSize()
                // FIX 3.8.x: el .torrent ahora SI se añade a la sesión (antes
                // solo se parseaba y la espera de metadata acababa en timeout)
                if (sm.find(hash) == null) sm.download(ti, File(task.dir))
            }
            val h = hash ?: throw DlException("hash no disponible")
            task.putExtra("ih", h.toHex())
            task.state = St.RUNNING
            task.segs.clear()
            task.segs.add(Seg(0, 0, 1))
            engine.db.update(task)

            // esperar a que aparezca el handle y la metadata
            var th: TorrentHandle? = null
            var waited = 0
            while (!stopped) {
                th = sm.find(h)
                if (th != null && th.isValid) {
                    handle = th
                    val st = th.status()
                    if (st.hasMetadata() || ti != null) break
                }
                Thread.sleep(1000)
                waited++
                if (waited > 90) throw DlException("sin metadata (peers: ${if (th == null) 0 else th.status().numPeers()})")
            }
            if (stopped) { pauseState(); return }
            th = handle
            if (th == null) throw DlException("handle no disponible")
            if (ti == null) {
                val t2 = th.torrentFile()
                if (t2 != null && t2.isValid) {
                    task.name = t2.name()
                    task.total = t2.totalSize()
                    engine.db.update(task)
                }
            }

            // selección de archivos (extra "skip": índices a NO descargar) y
            // descarga secuencial (extra "seq"): decididos en el diálogo previo
            val skip = task.extra().optString("skip", "")
            if (skip.isNotEmpty()) {
                val set = skip.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
                val tf = th.torrentFile()
                if (tf != null && tf.isValid) {
                    for (i in 0 until tf.files().numFiles()) {
                        th.filePriority(i, if (i in set) Priority.IGNORE else Priority.NORMAL)
                    }
                }
            }
            if (task.extra().optBoolean("seq", false)) {
                try { th.setFlags(com.frostwire.jlibtorrent.swig.libtorrent.getSequential_download()) }
                catch (e: Exception) { }
            }
            th.resume()

            // bucle de progreso
            while (!stopped) {
                val st = th.status()
                val ec = st.errorCode()
                if (ec != null && ec.isError) throw DlException(ec.message())
                task.total = st.totalWanted()
                task.done = st.totalWantedDone()
                task.speed = st.downloadRate().toLong()
                if (st.isFinished || st.progress() >= 1f) {
                    task.state = St.DONE
                    task.done = task.total
                    task.segs.clear()
                    task.segs.add(Seg(0, task.done, task.done))
                    engine.db.update(task)
                    engine.notifyChanged()
                    // seguir sembrando en silencio: salimos del bucle pero el
                    // handle queda vivo en la sesion
                    return
                }
                Thread.sleep(1000)
            }
            pauseState()
        } catch (e: Exception) {
            if (stopped || e is InterruptedException) pauseState()
            else {
                task.state = St.ERROR
                task.error = e.message ?: "error torrent"
                engine.db.update(task)
                engine.notifyChanged()
            }
        }
    }

    private fun pauseState() {
        try { handle?.pause() } catch (e: Exception) { }
        task.state = St.PAUSED
        engine.db.update(task)
        engine.notifyChanged()
    }

    /** Obtiene un .torrent como archivo local descargandolo si es necesario */
    private fun obtainTorrentFile(): File {
        val u = task.url
        if (u.startsWith("content://") || u.startsWith("file://")) {
            val src = android.net.Uri.parse(u)
            val dst = File(engine.ctx.cacheDir, "torrent_" + System.currentTimeMillis() + ".torrent")
            val inp = engine.ctx.contentResolver.openInputStream(src)
                ?: throw DlException("no se pudo abrir $u")
            val out = java.io.FileOutputStream(dst)
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            inp.close(); out.close()
            return dst
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            val dst = File(engine.ctx.cacheDir, "torrent_" + System.currentTimeMillis() + ".torrent")
            val bytes = httpGetBytes(u)
            val out = java.io.FileOutputStream(dst)
            out.write(bytes); out.close()
            return dst
        }
        val f = File(u)
        if (f.exists()) return f
        throw DlException("origen de .torrent no soportado: $u")
    }
}
