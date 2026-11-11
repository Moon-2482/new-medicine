package com.moon.newmedicine.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Minimal read-only ContentProvider serving the downloaded update APK from
 * the app-private external downloads directory. Hand-written because the app
 * deliberately has zero androidx dependencies (no FileProvider available).
 *
 * Only bare file names are accepted — path traversal is impossible.
 */
class ApkFileProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.moon.newmedicine.fileprovider"
        private const val CODE_APK = 1

        fun apkUri(fileName: String): Uri =
            Uri.parse("content://$AUTHORITY/apk/$fileName")
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, "apk/*", CODE_APK)
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File {
        if (matcher.match(uri) != CODE_APK) throw FileNotFoundException("bad uri: $uri")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("bad uri: $uri")
        require(!name.contains("/") && !name.contains("..")) { "bad name: $name" }
        val dir = context!!.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw FileNotFoundException("no downloads dir")
        return File(dir, name)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri)
        if (!f.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val f = fileFor(uri)
        val c = MatrixCursor(arrayOf("_display_name", "_size"))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = throw UnsupportedOperationException()
}
