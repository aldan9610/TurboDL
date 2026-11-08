package com.turbodl.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import com.turbodl.App
import java.io.File

/**
 * Proveedor minimalista para abrir/compartir los archivos descargados
 * sin depender de librerias de soporte.
 * URIs: content://com.turbodl.files/<ruta relativa codificada>
 */
class MiniFileProvider : ContentProvider() {

    private fun root(): File = File(App.app.getExternalFilesDir(null), "TurboDL")

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val rel = Uri.decode(uri.path ?: "").removePrefix("/")
        val base = root().canonicalPath
        val f = File(base, rel).canonicalFile
        if (!f.path.startsWith(base)) throw SecurityException("ruta fuera de alcance")
        if (!f.exists()) throw java.io.FileNotFoundException(f.path)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor {
        val rel = Uri.decode(uri.path ?: "").removePrefix("/")
        val f = File(root(), rel)
        val c = MatrixCursor(arrayOf("_display_name", "_size", "_content_size"))
        c.addRow(arrayOf(f.name, f.length(), f.length()))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
