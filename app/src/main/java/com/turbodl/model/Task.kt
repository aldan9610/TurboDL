package com.turbodl.model

import org.json.JSONObject

/** Estado de una tarea */
object St {
    const val QUEUED = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val DONE = 3
    const val ERROR = 4
    const val MERGING = 5
    const val SEEDING = 6 // torrent completado, sembrando
}

/** Tipo de descarga */
object Kind {
    const val HTTP = 0
    const val FTP = 1
    const val HLS = 2
    const val DASH = 3
    const val TORRENT = 4
    const val WEBDAV_DIR = 5 // directorio remoto (http/dav)
}

/** Rango de bytes de un segmento [start, end) */
class Seg(val start: Long, var cur: Long, val end: Long) {
    val len: Long get() = end - start
    fun done(): Boolean = cur >= end
}

class Task {
    var id: Long = 0
    var url: String = ""
    var kind: Int = Kind.HTTP
    var name: String = ""        // nombre mostrado
    var dir: String = ""         // carpeta de destino (absoluta) o etiqueta SAF
    var fileName: String = ""    // nombre de archivo destino
    var total: Long = -1
    var done: Long = 0
    var state: Int = St.QUEUED
    var error: String = ""
    var headersJson: String = "" // cabeceras extra {"Cookie":"...","Referer":"..."}
    var segsJson: String = ""    // progreso por segmentos
    var extraJson: String = ""   // info especifica (conns, calidades, fusion, SAF...)
    var time: Long = 0

    // ---- solo en memoria ----
    @Transient var segs: ArrayList<Seg> = ArrayList()
    @Transient var speed: Long = 0
    @Transient var live: Boolean = false // descarga en curso (para el ticker)
    @Transient var tag: Any? = null      // referencia al downloader

    fun path(): String = if (dir.isEmpty() || fileName.isEmpty()) "" else "$dir/$fileName"

    fun extra(): JSONObject = try { JSONObject(if (extraJson.isEmpty()) "{}" else extraJson) }
        catch (e: Exception) { JSONObject() }

    fun putExtra(k: String, v: Any) {
        val o = extra(); o.put(k, v); extraJson = o.toString()
    }

    /** Etiqueta de calidad detectada ("720p", "HLS 1080p"...) */
    fun quality(): String = extra().optString("q", "")

    /** Duracion detectada en segundos; -1 si se desconoce */
    fun durationSec(): Double = extra().optDouble("dur", -1.0)

    /**
     * Progreso 0..100 o -1 si es desconocido. Usa bytes cuando hay total
     * real; si no, segmentos completados ("segs"/"sdone") para HLS/DASH,
     * evitando porcentajes falsos por mezclar unidades.
     */
    fun pct(): Int {
        if (total > 0) return ((done * 100) / total).toInt().coerceIn(0, 100)
        val ex = extra()
        val st = ex.optLong("segs", -1L)
        if (st > 0) {
            val sd = ex.optLong("sdone", 0L)
            return ((sd * 100) / st).toInt().coerceIn(0, 100)
        }
        return -1
    }

    fun loadSegs() {
        segs.clear()
        if (segsJson.isEmpty()) return
        try {
            for (p in segsJson.split(";")) {
                val f = p.split(":")
                if (f.size == 3) segs.add(Seg(f[0].toLong(), f[1].toLong(), f[2].toLong()))
            }
        } catch (e: Exception) { /* ignora progreso corrupto */ }
    }

    fun saveSegs() {
        val sb = StringBuilder()
        for (s in segs) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(s.start).append(':').append(s.cur).append(':').append(s.end)
        }
        segsJson = sb.toString()
    }

    fun calcDone() {
        var d = 0L
        for (s in segs) d += (s.cur - s.start).coerceAtLeast(0)
        done = d
    }

    fun headers(): HashMap<String, String> {
        val m = HashMap<String, String>()
        if (headersJson.isNotEmpty()) try {
            val o = JSONObject(headersJson)
            val it = o.keys()
            while (it.hasNext()) { val k = it.next(); m[k] = o.getString(k) }
        } catch (e: Exception) { }
        return m
    }
}
