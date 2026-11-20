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

    /**
     * Resuelve la ruta real de un URI. Además de las descargas
     * (getExternalFilesDir/TurboDL) la 3.8.x sirve dos raíces internas:
     *  - "install/<apk>" -> cacheDir/install (APK de la auto-actualización)
     *  - "logs/<archivo>" -> filesDir/logs (registro de fallos compartible)
     */
    private fun resolve(uri: Uri): File {
        val rel = Uri.decode(uri.path ?: "").removePrefix("/")
        return when {
            rel.startsWith("install/") -> {
                val base = File(App.app.cacheDir, "install").canonicalPath
                val f = File(base, rel.removePrefix("install/")).canonicalFile
                if (!f.path.startsWith(base)) throw SecurityException("ruta fuera de alcance")
                f
            }
            rel.startsWith("logs/") -> {
                val base = File(App.app.filesDir, "logs").canonicalPath
                val f = File(base, rel.removePrefix("logs/")).canonicalFile
                if (!f.path.startsWith(base)) throw SecurityException("ruta fuera de alcance")
                f
            }
            else -> {
                val base = root().canonicalPath
                val f = File(base, rel).canonicalFile
                if (!f.path.startsWith(base)) throw SecurityException("ruta fuera de alcance")
                f
            }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = resolve(uri)
        if (!f.exists()) throw java.io.FileNotFoundException(f.path)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String {
        val name = resolve(uri).name
        if (name.endsWith(".apk")) return "application/vnd.android.package-archive"
        if (name.endsWith(".log") || name.endsWith(".txt")) return "text/plain"
        val ext = MimeTypeMap.getFileExtensionFromUrl(name).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor {
        val f = resolve(uri)
        val c = MatrixCursor(arrayOf("_display_name", "_size", "_content_size"))
        c.addRow(arrayOf(f.name, f.length(), f.length()))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
