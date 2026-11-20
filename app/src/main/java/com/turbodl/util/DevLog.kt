package com.turbodl.util

/**
 * Buffers en memoria para las herramientas de desarrollador (requisito 4.0):
 * registro de peticiones de red y consola de JavaScript. Ring buffers con
 * tope para no crecer sin límite en móviles humildes.
 */
object DevLog {

    class NetRow(val method: String, val url: String, val mime: String,
                 val mainFrame: Boolean, val time: Long) {
        fun line(): String =
            "$method ${if (mainFrame) "[doc] " else ""}" +
            (if (mime.isNotEmpty()) "($mime) " else "") + url.take(110)
    }

    class ConRow(val level: String, val msg: String, val time: Long) {
        fun line(): String = "[$level] ${msg.take(400)}"
    }

    private const val NET_CAP = 500
    private const val CON_CAP = 300
    private val net = ArrayDeque<NetRow>()
    private val con = ArrayDeque<ConRow>()

    fun logNet(method: String, url: String, mime: String, mainFrame: Boolean) {
        synchronized(net) {
            net.addLast(NetRow(method, url, mime, mainFrame, System.currentTimeMillis()))
            while (net.size > NET_CAP) net.removeFirst()
        }
    }

    fun logCon(level: String, msg: String) {
        synchronized(con) {
            con.addLast(ConRow(level, msg, System.currentTimeMillis()))
            while (con.size > CON_CAP) con.removeFirst()
        }
    }

    fun netRows(): List<NetRow> = synchronized(net) { net.toList().asReversed() }
    fun conRows(): List<ConRow> = synchronized(con) { con.toList().asReversed() }
    fun clearNet() = synchronized(net) { net.clear() }
    fun clearCon() = synchronized(con) { con.clear() }
}
