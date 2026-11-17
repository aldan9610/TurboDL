package com.turbodl.engine

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.turbodl.model.Kind
import com.turbodl.model.St
import com.turbodl.model.Task
import com.turbodl.ui.BrowserActivity
import com.turbodl.util.Kit
import java.util.Locale

/**
 * Refresco de enlaces caducados (estilo 1DM): los CDNs firmados (YouTube,
 * Facebook...) devuelven 403/410 cuando la URL vence. Aqui se reabre la
 * página de origen en un WebView OCULTO, se recapturan los medios que
 * olfatea la misma bridge del navegador (__tdlBridge) y se elige el
 * candidato que corresponde a la tarea; luego la descarga reanuda con
 * Range: bytes=<progreso>- sobre el .part intacto.
 */
object LinkRefresher {

    private const val TIMEOUT_MS = 30000L

    /** cb(ok, msg) llega en el hilo principal. */
    fun refresh(engine: Engine, task: Task, origin: String, cb: (Boolean, String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            val found = ArrayList<String>()
            var wv: WebView? = null
            var failed = false
            try {
                wv = WebView(engine.ctx) // WebView oculto sin UI
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.userAgentString = engine.userAgent()
                val bridge = object {
                    @JavascriptInterface
                    fun sniff(u: String, type: String, title: String) {
                        if (u.isNotEmpty()) synchronized(found) { found.add(u) }
                    }
                }
                wv.addJavascriptInterface(bridge, "__tdlBridge")
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView?, url: String?) {
                        try { v?.evaluateJavascript(BrowserActivity.HOOK, null) } catch (e: Exception) { }
                    }
                }
                wv.loadUrl(origin)
            } catch (e: Exception) {
                failed = true
                try { wv?.destroy() } catch (e2: Exception) { }
                main.post { cb(false, "no se pudo abrir la página (${e.message})") }
            }
            if (failed) return@post

            // hilo de espera: intenta emparejar hasta TIMEOUT o exito
            Thread {
                val t0 = System.currentTimeMillis()
                var match: String? = null
                while (match == null && System.currentTimeMillis() - t0 < TIMEOUT_MS) {
                    try { Thread.sleep(500) } catch (e: InterruptedException) { break }
                    match = matchCandidate(synchronized(found) { found.toList() }, task)
                }
                val okUrl = match
                main.post {
                    try { wv?.destroy() } catch (e: Exception) { }
                    if (okUrl == null) {
                        cb(false, "no se detectó el archivo en la página; ábrela y vuelve a descargarlo")
                    } else {
                        // URL y cabeceras frescas (cookies renovadas al recargar)
                        task.url = okUrl
                        val hs = task.headers()
                        val ck = try { CookieManager.getInstance().getCookie(origin) } catch (e: Exception) { null }
                        if (!ck.isNullOrBlank()) hs["Cookie"] = ck
                        hs["Referer"] = origin
                        val o = org.json.JSONObject()
                        for ((k, v) in hs) o.put(k, v)
                        task.headersJson = o.toString()
                        cb(true, okUrl)
                    }
                }
            }.apply { isDaemon = true; name = "link-refresher" }.start()
        }
    }

    /** Elige el candidato que corresponde a la tarea original. */
    private fun matchCandidate(cands: List<String>, task: Task): String? {
        if (cands.isEmpty()) return null
        val oldNorm = Kit.normalizeSniffUrl(task.url)
        val oldItag = qParam(task.url, "itag")
        val oldMime = qParam(task.url, "mime")
        val oldPath = task.url.substringBefore('?').substringBefore('#')
        val oldExt = Kit.extOf(task.fileName.ifEmpty { Kit.nameFromUrl(task.url) })

        // 1) misma URL normalizada (la firma cambia, el recurso no)
        cands.firstOrNull { Kit.normalizeSniffUrl(it) == oldNorm }?.let { return it }

        // 2) googlevideo (YouTube): mismo itag y mime que la original
        if (oldItag.isNotEmpty()) {
            cands.firstOrNull {
                qParam(it, "itag") == oldItag && (oldMime.isEmpty() || qParam(it, "mime") == oldMime)
            }?.let { return it }
        }

        // 3) HLS/DASH: mismo manifiesto (el path no cambia entre visitas)
        if (task.kind == Kind.HLS || task.kind == Kind.DASH) {
            cands.firstOrNull {
                it.substringBefore('?').substringBefore('#') == oldPath
            }?.let { return it }
        }

        // 4) misma extension y mismo host
        if (oldExt.isNotEmpty()) {
            cands.firstOrNull {
                Kit.extOf(Kit.nameFromUrl(it)) == oldExt &&
                    hostOf(it) == hostOf(task.url)
            }?.let { return it }
        }
        return null
    }

    private fun qParam(url: String, key: String): String {
        val q = url.substringAfter('?', "").substringBefore('#')
        for (p in q.split('&')) {
            val i = p.indexOf('=')
            if (i > 0 && p.substring(0, i).lowercase(Locale.US) == key) return p.substring(i + 1)
        }
        return ""
    }

    private fun hostOf(url: String): String =
        try { java.net.URI(url).host ?: "" } catch (e: Exception) { "" }
}
