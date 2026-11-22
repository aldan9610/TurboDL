package com.turbodl.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.webkit.WebView
import com.turbodl.util.Kit
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Exportación de la página actual (requisito 4.0): PDF (dos vías),
 * Markdown para alimentar LLMs, texto plano, HTML completo, captura PNG
 * y guardado offline en MHT (CSS+JS incluidos).
 */
object Exporter {

    /** Altura máxima de captura (memoria en móviles humildes). */
    private const val MAX_CAPTURE_PX = 9000

    // ---------------- guardado genérico ----------------

    /** Guarda bytes en Download/TurboDL (API 29+) o en la carpeta de la app.
     *  Devuelve la ruta visible o null si falló. */
    fun saveFile(ctx: Context, name: String, mime: String, data: ByteArray): String? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues()
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name)
                cv.put(MediaStore.Downloads.MIME_TYPE, mime)
                cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download/TurboDL")
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                "Download/TurboDL/$name"
            } else {
                val dir = File(ctx.getExternalFilesDir(null), "TurboDL")
                dir.mkdirs()
                val f = File(dir, name)
                f.writeBytes(data)
                f.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- PDF ----------------

    /** Captura el render completo del WebView (software, hasta MAX_CAPTURE_PX). */
    private fun captureFull(web: WebView): Bitmap? {
        val wv = web
        val w = wv.width.coerceAtLeast(1)
        val hContent = try { (wv.contentHeight * wv.scale).toInt() } catch (e: Exception) { 0 }
        if (hContent <= 0) return null
        val h = hContent.coerceIn(1, MAX_CAPTURE_PX)
        // renderizado software: captura también el contenido fuera de pantalla
        val oldLayer = wv.layerType
        try { wv.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null) } catch (e: Exception) { }
        val bmp = try {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val c = Canvas(b)
            c.drawColor(0xFFFFFFFF.toInt())
            wv.draw(c)
            b
        } catch (e: Exception) { null }
        try { wv.setLayerType(oldLayer, null) } catch (e: Exception) { }
        return bmp
    }

    /** PNG de la página completa (captura del render). */
    fun exportPng(act: Activity, web: WebView, title: String, cb: (String) -> Unit) {
        val bitmap = captureFull(web)
        if (bitmap == null) { cb(""); return }
        Thread {
            val out = try {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 92, baos)
                baos.toByteArray()
            } catch (e: Exception) { null }
            val bytes = out
            act.runOnUiThread {
                if (bytes == null) cb("")
                else cb(saveFile(act, sanitize(title) + "_" + ts() + ".png",
                    "image/png", bytes) ?: "")
            }
        }.apply { isDaemon = true; name = "png-export" }.start()
    }

    /** PDF por captura del render (rápido, sin diálogo del sistema). */
    fun exportPdfCapture(act: Activity, web: WebView, title: String, cb: (String) -> Unit) {
        val bitmap = captureFull(web)
        if (bitmap == null) { cb(""); return }
        Thread {
            val out = try {
                val doc = PdfDocument()
                val pw = 595f // A4 en puntos
                val ph = 842f
                val scale = pw / bitmap.width
                val pageHpx = (ph / scale).toInt().coerceAtLeast(1)
                var y = 0
                var n = 1
                while (y < bitmap.height && n <= 60) {
                    val slice = (pageHpx).coerceAtMost(bitmap.height - y)
                    val page = doc.startPage(
                        PdfDocument.PageInfo.Builder(595, 842, n).create())
                    val m = android.graphics.Matrix()
                    m.setScale(scale, scale)
                    m.postTranslate(0f, -y.toFloat() * scale)
                    val pc = page.canvas
                    pc.drawColor(0xFFFFFFFF.toInt())
                    pc.drawBitmap(bitmap, m, null)
                    doc.finishPage(page)
                    y += pageHpx
                    n++
                }
                val baos = ByteArrayOutputStream()
                doc.writeTo(baos)
                doc.close()
                baos.toByteArray()
            } catch (e: Exception) { null }
            val bytes = out
            act.runOnUiThread {
                if (bytes == null) cb("")
                else {
                    val name = sanitize(title) + "_" + ts() + ".pdf"
                    cb(saveFile(act, name, "application/pdf", bytes) ?: "")
                }
            }
        }.apply { isDaemon = true; name = "pdf-export" }.start()
    }

    /** PDF vía impresión del sistema (documento vectorial completo). */
    fun exportPdfSystem(act: Activity, web: WebView, title: String) {
        try {
            val pm = act.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val job = "TurboDL - ${title.take(30).ifEmpty { "página" }}"
            pm.print(job, web.createPrintDocumentAdapter(job), PrintAttributes.Builder().build())
        } catch (e: Exception) { }
    }

    // ---------------- Markdown / texto / HTML ----------------

    /** Convierte el DOM vivo a Markdown limpio (listo para LLMs):
     *  quita navegación/scripts y conserva estructura, enlaces, imágenes,
     *  tablas y bloques de código con URLs absolutas. */
    fun exportMarkdown(html: String, pageUrl: String, title: String, cb: (String) -> Unit) {
        Thread {
            val md = try { htmlToMarkdown(html, pageUrl, title) } catch (e: Exception) { "" }
            cb(md)
        }.apply { isDaemon = true; name = "md-export" }.start()
    }

    fun htmlToMarkdown(html: String, pageUrl: String, title: String): String {
        val doc = Jsoup.parse(html, pageUrl)
        doc.select("script,style,noscript,iframe,svg,nav,header,footer,aside,form,button,select,textarea,ins,[role=banner],[role=navigation],[aria-hidden=true]").remove()
        val root = doc.selectFirst("article, main, [role=main], #content, .content, .post, .entry-content, #mw-content-text")
            ?: doc.body() ?: return ""
        val sb = StringBuilder()
        sb.append("# ").append(title.ifEmpty { doc.title().ifEmpty { "Página" } }).append("\n\n")
        if (pageUrl.startsWith("http")) sb.append("Fuente: ").append(pageUrl).append("\n\n")
        walkBlocks(root, sb, 0)
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim() + "\n"
    }

    private fun walkBlocks(el: Element, sb: StringBuilder, depth: Int) {
        for (child in el.children()) {
            when (child.tagName().lowercase()) {
                "h1" -> { sb.append("\n# ").append(inline(child)).append("\n\n") }
                "h2" -> { sb.append("\n## ").append(inline(child)).append("\n\n") }
                "h3" -> { sb.append("\n### ").append(inline(child)).append("\n\n") }
                "h4", "h5", "h6" -> { sb.append("\n#### ").append(inline(child)).append("\n\n") }
                "p" -> {
                    val t = inline(child)
                    if (t.isNotBlank()) sb.append(t).append("\n\n")
                }
                "br" -> sb.append("\n")
                "ul", "ol" -> listToMd(child, sb, 0, child.tagName() == "ol")
                "pre" -> {
                    val code = child.wholeText().trimEnd()
                    val lang = child.selectFirst("[class*=language-]")?.attr("class")
                        ?.substringAfter("language-")?.substringBefore(' ') ?: ""
                    sb.append("\n```").append(lang).append('\n').append(code).append("\n```\n\n")
                }
                "blockquote" -> {
                    val inner = StringBuilder()
                    walkBlocks(child, inner, depth + 1)
                    inner.toString().trimEnd('\n').split('\n').forEach {
                        sb.append("> ").append(it).append('\n')
                    }
                    sb.append('\n')
                }
                "table" -> tableToMd(child, sb)
                "hr" -> sb.append("\n---\n\n")
                "img" -> {
                    val src = child.absUrl("src")
                    if (src.isNotEmpty()) sb.append("\n![")
                        .append(child.attr("alt").ifEmpty { "imagen" })
                        .append("](").append(src).append(")\n\n")
                }
                "figure" -> walkBlocks(child, sb, depth)
                "div", "section", "article", "span", "center", "font", "body", "td", "tr", "tbody", "thead",
                "dl", "dt", "dd", "label", "main", "a", "video", "audio", "picture", "figcaption" ->
                    if (child.children().isNotEmpty()) walkBlocks(child, sb, depth)
                    else {
                        val t = inline(child)
                        if (t.isNotBlank()) sb.append(t).append("\n\n")
                    }
                else -> {
                    if (child.children().isNotEmpty()) walkBlocks(child, sb, depth)
                    else {
                        val t = inline(child)
                        if (t.isNotBlank()) sb.append(t).append("\n\n")
                    }
                }
            }
        }
    }

    private fun listToMd(list: Element, sb: StringBuilder, level: Int, ordered: Boolean) {
        var i = 1
        for (li in list.children()) {
            if (li.tagName() != "li") continue
            val indent = "  ".repeat(level)
            val mark = if (ordered) "${i++}." else "-"
            val own = StringBuilder()
            li.childNodes().forEach { n ->
                if (n is TextNode) own.append(n.text())
                else if (n is Element && n.children().isEmpty()) own.append(inline(n))
            }
            sb.append(indent).append(mark).append(' ').append(own.toString().trim()).append('\n')
            li.select("> ul, > ol").forEach { sub ->
                listToMd(sub, sb, level + 1, sub.tagName() == "ol")
            }
        }
        sb.append('\n')
    }

    private fun tableToMd(table: Element, sb: StringBuilder) {
        val rows = table.select("tr")
        if (rows.isEmpty()) return
        sb.append('\n')
        rows.forEachIndexed { ri, tr ->
            val cells = tr.select("th, td").map { inline(it).replace('\n', ' ').take(60) }
            sb.append("| ").append(cells.joinToString(" | ")).append(" |\n")
            if (ri == 0) {
                sb.append("|")
                cells.forEach { sb.append(" --- |") }
                sb.append('\n')
            }
        }
        sb.append('\n')
    }

    /** Texto inline con enlaces, negritas, cursivas y código. */
    private fun inline(el: Element): String {
        val sb = StringBuilder()
        inlineNodes(el, sb)
        return sb.toString().replace(Regex("\n{2,}"), "\n").trim()
    }

    private fun inlineNodes(e: Element, sb: StringBuilder) {
        e.childNodes().forEach { inlineNode(it, sb) }
    }

    private fun inlineNode(n: Node, sb: StringBuilder) {
        when (n) {
            is TextNode -> sb.append(n.text())
            is Element -> when (n.tagName().lowercase()) {
                "a" -> {
                    val txt = n.text().trim()
                    val href = n.absUrl("href")
                    if (href.startsWith("http") && txt.isNotEmpty() && txt != href)
                        sb.append('[').append(txt).append("](").append(href).append(')')
                    else sb.append(txt)
                }
                "strong", "b" -> { val t = n.text().trim(); if (t.isNotEmpty()) sb.append("**").append(t).append("**") else inlineNodes(n, sb) }
                "em", "i" -> { val t = n.text().trim(); if (t.isNotEmpty()) sb.append('*').append(t).append('*') else inlineNodes(n, sb) }
                "code" -> sb.append('`').append(n.text()).append('`')
                "br" -> sb.append('\n')
                "img" -> {
                    val src = n.absUrl("src")
                    if (src.isNotEmpty()) sb.append("\n![").append(n.attr("alt").ifEmpty { "imagen" })
                        .append("](").append(src).append(")\n")
                }
                else -> inlineNodes(n, sb)
            }
            else -> { }
        }
    }

    // ---------------- offline (MHT) ----------------

    /** Guardado offline en MHT: HTML+CSS+imágenes+JS en un solo archivo. */
    fun saveOffline(web: WebView, url: String, title: String, cb: (String) -> Unit) {
        try {
            val ctx = web.context
            val dir = File(ctx.getExternalFilesDir(null), "offline").apply { mkdirs() }
            val f = File(dir, "p_" + ts() + ".mht")
            web.saveWebArchive(f.absolutePath, false) { path ->
                val p = path ?: f.absolutePath
                val size = try { File(p).length() } catch (e: Exception) { 0L }
                if (size > 0) {
                    try {
                        com.turbodl.db.NotesDb.get(ctx)
                            .addSaved(url, title, p, size)
                    } catch (e: Exception) { }
                    cb(p)
                } else cb("")
            }
        } catch (e: Exception) {
            cb("")
        }
    }

    private fun sanitize(t: String): String =
        t.replace(Regex("[^A-Za-z0-9ÁÉÍÓÚáéíóúñÑ _-]"), "").trim()
            .take(60).ifEmpty { "pagina" }

    private fun ts(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
}
