package io.github.marsinsanity.mainui.emu

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.canonicalPathOrSelf
import java.io.File
import java.io.FileNotFoundException

/**
 * A rom as a single document, content://<app>.roms/document/<encoded path> (%ROMDOC%), the form a
 * file manager hands an emulator. MainUI grants the emulator read access to that one link when it
 * launches the game, so the emulator needs no access of its own to the ROMs folder (PPSSPP only
 * opens storage links it was given, and takes no FileProvider links: it wants a "document" link).
 * Read only, and only for files in a ROMs folder.
 */
class RomDocumentProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val seg = uri.pathSegments
        if (seg.size != 2 || seg[0] != "document") return null
        val f = File(seg[1])
        if (!f.isFile) return null
        val path = f.canonicalPathOrSelf()
        val roots = runCatching { MainUI.storage.romRoots() }.getOrDefault(emptyList())
        return f.takeIf { roots.any { r -> path.startsWith(r.canonicalPathOrSelf() + "/") } }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED)
        val row = cols.map {
            when (it) {
                Document.COLUMN_DOCUMENT_ID -> uri.pathSegments[1]
                Document.COLUMN_DISPLAY_NAME, OpenableColumns.DISPLAY_NAME -> f.name
                Document.COLUMN_SIZE, OpenableColumns.SIZE -> f.length()
                Document.COLUMN_FLAGS -> 0
                Document.COLUMN_MIME_TYPE -> MIME
                Document.COLUMN_LAST_MODIFIED -> f.lastModified()
                else -> null
            }
        }
        return MatrixCursor(cols).apply { addRow(row) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode.contains('w') || mode.contains('t')) throw SecurityException("Roms are read only")
        val f = file(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = file(uri)?.let { MIME }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val MIME = "application/octet-stream"

        /** [f]'s link; the path is one encoded segment, so the link has exactly provider/document/id. */
        fun uri(authority: String, f: File): Uri =
            Uri.parse("content://$authority/document/" + Uri.encode(f.absolutePath))
    }
}
