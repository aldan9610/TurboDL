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
