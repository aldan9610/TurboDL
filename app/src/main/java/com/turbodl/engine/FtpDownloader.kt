package com.turbodl.engine

import com.turbodl.model.Seg
import com.turbodl.model.St
import com.turbodl.model.Task
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import javax.net.ssl.SSLSocketFactory

/**
 * Cliente FTP/FTPS minimo: descarga de un archivo con reanudacion (REST).
 * FTPS explicito (AUTH TLS) para esquema ftps://
 */
class FtpDownloader(task: Task, engine: Engine) : Downloader(task, engine) {

    private lateinit var ctl: Socket
    private lateinit var reader: BufferedReader

    override fun run() {
        try {
            val u = java.net.URI(task.url)
            val host = u.host ?: throw DlException("host FTP no valido")
            val port = if (u.port > 0) u.port else 21
            val user = if (u.userInfo != null) u.userInfo.substringBefore(':') else "anonymous"
            val pass = if (u.userInfo != null && u.userInfo.contains(':')) u.userInfo.substringAfter(':') else "turbodl@"
            var path = u.path ?: "/"
            if (path.isEmpty()) path = "/"

            if (task.fileName.isEmpty()) {
                val n = URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
                task.fileName = com.turbodl.util.Kit.uniqueName(catTmp(), n.ifEmpty { "archivo" })
                task.name = task.fileName
            }

            ctl = Socket()
            ctl.connect(InetSocketAddress(host, port), 20000)
            reader = BufferedReader(InputStreamReader(ctl.getInputStream(), "UTF-8"))

            expect(220)
            send("USER $user"); val r = read()
            if (r.first == 331) { send("PASS $pass"); expect(230) }

            val ftps = task.url.lowercase().startsWith("ftps://")
            if (ftps) {
                send("AUTH TLS"); expect(334, 234)
                val sf = SSLSocketFactory.getDefault() as SSLSocketFactory
                ctl = sf.createSocket(ctl, host, port, true)
                reader = BufferedReader(InputStreamReader(ctl.getInputStream(), "UTF-8"))
            }

            send("TYPE I"); expect(200)
            try { send("OPTS UTF8 ON"); read() } catch (e: Exception) { }

            // tamano
            var total = -1L
            try {
                send("SIZE $path")
                val sr = read()
                if (sr.first == 213) total = sr.second.trim().toLongOrNull() ?: -1
            } catch (e: Exception) { }
            task.total = total

            val sink = engine.openSink(task, task.fileName)
            var pos = sink.size()
            if (total in 1..pos) {
                sink.truncate(0); pos = 0
            }
            task.done = pos
            task.segs.clear()
            task.segs.add(Seg(0, pos, if (total > 0) total else -1))
            task.state = St.RUNNING
            engine.db.update(task)

            try {
                send("PASV")
                val pr = expect(227)
                val dp = parsePasv(pr.second)
                val data = Socket()
                data.connect(InetSocketAddress(dp.first, dp.second), 20000)
                if (ftps) {
                    val sf2 = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val s = sf2.createSocket(data, host, dp.second, true)
                    send("PROT P"); try { expect(200) } catch (e: Exception) { }
                    transfer(s, sink, pos)
                } else {
                    transfer(data, sink, pos)
                }
            } finally {
                sink.close()
            }
            try { read() } catch (e: Exception) { }

            if (stopped) {
                task.state = St.PAUSED
            } else {
                if (total > 0 && task.done < total) throw DlException("incompleto")
                task.state = St.DONE
                task.done = task.done
                task.segs.clear()
                task.segs.add(Seg(0, task.done, task.done))
            }
            engine.db.update(task)
            engine.notifyChanged()
        } catch (e: Exception) {
            if (stopped) task.state = St.PAUSED
            else {
                task.state = St.ERROR
                task.error = e.message ?: "error FTP"
            }
            engine.db.update(task)
            engine.notifyChanged()
        } finally {
            try { send("QUIT") } catch (e: Exception) { }
            try { ctl.close() } catch (e: Exception) { }
        }
    }

    private fun catTmp(): String = File(engine.baseDir, "General").absolutePath

    private fun transfer(data: Socket, sink: Sink, pos: Long) {
        if (pos > 0) {
            send("REST $pos")
            expect(350, 200)
        }
        send("RETR " + java.net.URI(task.url).path)
        val rr = read()
        if (rr.first != 150 && rr.first != 125) throw DlException("RETR rechazado: ${rr.first}")
        val inp = BufferedInputStream(data.getInputStream())
        val buf = ByteArray(32 * 1024)
        var done = pos
        while (true) {
            if (stopped) break
            val n = inp.read(buf)
            if (n < 0) break
            sink.append(buf, n)
            done += n
            task.done = done
            engine.limiter.take(n)
        }
        inp.close()
        data.close()
    }

    private fun send(cmd: String) {
        ctl.getOutputStream().write((cmd + "\r\n").toByteArray(Charsets.UTF_8))
        ctl.getOutputStream().flush()
    }

    private fun read(): Pair<Int, String> {
        var line = reader.readLine() ?: throw DlException("conexion FTP cerrada")
        var code = line.substring(0, 3).toIntOrNull() ?: 0
        while (line.length > 3 && line[3] == '-') {
            line = reader.readLine() ?: break
            val c2 = line.substring(0, 3).toIntOrNull()
            if (c2 != null) code = c2
        }
        return code to line
    }

    private fun expect(vararg codes: Int): Pair<Int, String> {
        val r = read()
        if (r.first !in codes) throw DlException("FTP ${r.first}: ${r.second}")
        return r
    }

    private fun parsePasv(line: String): Pair<String, Int> {
        val m = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(line)
            ?: throw DlException("respuesta PASV no valida")
        val g = m.groupValues
        val ip = "${g[1]}.${g[2]}.${g[3]}.${g[4]}"
        val port = g[5].toInt() * 256 + g[6].toInt()
        return ip to port
    }
}
