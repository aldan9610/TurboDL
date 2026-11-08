package com.turbodl.engine

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.ArrayList

/**
 * Destino de escritura abstracto: archivo local (RandomAccessFile) o documento
 * SAF (tarjeta SD / carpeta elegida con el explorador). Los descargadores
 * escriben igual en ambos casos.
 */
interface Sink : AutoCloseable {
    fun seekWrite(off: Long, buf: ByteArray, len: Int)
    fun append(buf: ByteArray, len: Int) = seekWrite(-1L, buf, len)
    fun truncate(len: Long)
    fun size(): Long
    override fun close()
    /** Uri del documento (SAF) o null si es archivo local */
    fun docUri(): Uri? = null
}

/** Sink sobre archivo local. */
class FileSink(private val f: File) : Sink {
    private val raf = java.io.RandomAccessFile(f, "rw")
    override fun seekWrite(off: Long, buf: ByteArray, len: Int) {
        if (off >= 0) raf.seek(off) else raf.seek(raf.length())
        raf.write(buf, 0, len)
    }
    override fun append(buf: ByteArray, len: Int) {
        raf.seek(raf.length()); raf.write(buf, 0, len)
    }
    override fun truncate(len: Long) = raf.setLength(len)
    override fun size(): Long = raf.length()
    override fun close() = raf.close()
}

/** Sink sobre un documento SAF usando un descriptor con seek ("rw"). */
class SafSink(private val res: ContentResolver, private val uri: Uri) : Sink {
    private val pfd = res.openFileDescriptor(uri, "rw")
        ?: throw java.io.IOException("no se pudo abrir $uri")
    private val out: OutputStream = FileOutputStream(pfd.fileDescriptor)
    private var pos: Long = 0

    override fun seekWrite(off: Long, buf: ByteArray, len: Int) {
        val ch = (out as FileOutputStream).channel
        if (off >= 0) ch.position(off) else ch.position(ch.size())
        var written = 0
        while (written < len) {
            val n = ch.write(java.nio.ByteBuffer.wrap(buf, written, len - written))
            if (n <= 0) break
            written += n
        }
        pos = ch.position()
    }
    override fun truncate(len: Long) {
        val ch = (out as FileOutputStream).channel
        ch.truncate(len)
    }
    override fun size(): Long = (out as FileOutputStream).channel.size()
    override fun close() {
        try { out.close() } catch (e: Exception) { }
        try { pfd.close() } catch (e: Exception) { }
    }
    override fun docUri(): Uri = uri
}

/**
 * Tienda SAF: crea carpetas y archivos dentro de un árbol elegido con
 * ACTION_OPEN_DOCUMENT_TREE (incluye tarjetas SD), con API directa de
 * DocumentsContract (sin dependencias).
 */
class SafStore(private val ctx: Context, val treeUri: Uri) {

    private val res: ContentResolver get() = ctx.contentResolver

    private fun docId(uri: Uri): String = DocumentsContract.getDocumentId(uri)

    fun rootDocUri(): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    /** Lista {nombre: docUri} de hijos del documento dado. */
    fun children(parent: Uri): HashMap<String, Uri> {
        val out = HashMap<String, Uri>()
        try {
            val kidsUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId(parent))
            val c = res.query(kidsUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null)
            c?.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    out[name] = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            }
        } catch (e: Exception) {
            Log.w("TurboDL", "children: ${e.message}")
        }
        return out
    }

    fun hasDir(parent: Uri, name: String): Boolean {
        val kids = children(parent)
        return kids.containsKey(name) // no distingue archivo/carpeta, ok para uso interno
    }

    /** Encuentra o crea una subcarpeta; devuelve su Uri. */
    fun ensureDir(parent: Uri, name: String): Uri {
        val kids = children(parent)
        kids[name]?.let { return it }
        val mime = DocumentsContract.Document.MIME_TYPE_DIR
        val created = DocumentsContract.createDocument(res, parent, mime, name)
            ?: throw java.io.IOException("SAF: no se pudo crear carpeta $name")
        return created
    }

    /** Encuentra o crea un archivo evitando sobrescrituras: nombre (2).ext */
    fun ensureFile(parent: Uri, name: String, mime: String): Uri {
        val kids = children(parent)
        if (!kids.containsKey(name)) {
            val created = DocumentsContract.createDocument(res, parent, mime, name)
            if (created != null) return created
        }
        // existe (o fallo): buscar un nombre libre
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (i < 1000) {
            val n = "$base ($i)$ext"
            if (!kids.containsKey(n)) {
                val created = DocumentsContract.createDocument(res, parent, mime, n)
                if (created != null) return created
            }
            i++
        }
        throw java.io.IOException("SAF: no se pudo crear $name")
    }

    fun delete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(res, uri)
    } catch (e: Exception) { false }

    fun openSink(docUri: Uri): SafSink = SafSink(res, docUri)

    companion object {
        const val PREF_TREE = "safTree"

        /** Tomado del prefs; null si no configurado o el permiso se revoco. */
        fun from(ctx: Context, sp: android.content.SharedPreferences): SafStore? {
            val s = sp.getString(PREF_TREE, null) ?: return null
            return try {
                val uri = Uri.parse(s)
                SafStore(ctx, uri)
            } catch (e: Exception) { null }
        }

        /** Nombre legible del arbol ("Tarjeta SD", "Descargas"...). */
        fun treeName(ctx: Context, treeUri: Uri): String {
            try {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                ctx.contentResolver.query(docUri, arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
                )?.use { if (it.moveToFirst()) return it.getString(0) ?: "?" }
            } catch (e: Exception) { }
            return treeUri.lastPathSegment ?: "carpeta"
        }
    }
}
