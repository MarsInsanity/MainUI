package io.github.marsinsanity.mainui.core

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.util.Log
import io.github.marsinsanity.mainui.MainUI
import java.util.Collections
import java.util.UUID

/**
 * Lasting folder access (Storage Access Framework tree grants) for emulators, given with root
 * instead of each emulator's folder picker: the [SafGrants] an emulator needs to open the games
 * MainUI starts, and to list them itself.
 *
 * Android only lets an app that holds a folder (or the picker, which holds MANAGE_DOCUMENTS)
 * grant it. So the picker's app, played by root ([io.github.marsinsanity.mainui.tools.FolderGrant]),
 * first offers each folder to MainUI ([FolderGrantService]), MainUI keeps it, grants it on to the
 * emulator, and root makes the emulator's grant lasting.
 */
object FolderGrants {
    private const val TAG = "MainUI.Folders"
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** The tree link of folder [docId] ("primary:Roms/gc"), exactly as the picker's result reads. */
    fun treeUri(docId: String) = "content://$AUTHORITY/tree/${SafGrants.percentEncode(docId)}"

    /** The folder itself as a document of its own tree (PPSSPP keeps folders this way). */
    fun folderDocUri(docId: String) = SafGrants.documentUri(docId, listOf(docId), docId)

    /** Set while folders are being offered: the service keeps only offers carrying it. */
    @Volatile internal var nonce: String? = null
    private val kept: MutableSet<String> = Collections.synchronizedSet(HashSet())

    internal fun kept(uri: Uri) { kept += uri.toString() }

    private val ctx get() = MainUI.app

    /** The folders MainUI holds for good (read and write), as tree links. */
    private fun held(): Set<String> = ctx.contentResolver.persistedUriPermissions
        .filter { it.isReadPermission && it.isWritePermission }.map { it.uri.toString() }.toSet()

    /** The folder picker's app (it may grant any folder): package and uid. */
    private fun picker(): Pair<String, Int>? {
        val pm = ctx.packageManager
        val candidates = listOfNotNull(pm.resolveActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 0)?.activityInfo?.packageName) +
            listOf("com.google.android.documentsui", "com.android.documentsui")
        return candidates.distinct().firstNotNullOfOrNull { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                ?.takeIf { pm.checkPermission(android.Manifest.permission.MANAGE_DOCUMENTS, pkg) == PackageManager.PERMISSION_GRANTED }
                ?.let { pkg to it.uid }
        }
    }

    private fun helper(args: String) = Root.exec("env CLASSPATH=${Root.q(ctx.applicationInfo.sourceDir)} app_process /system/bin " +
        "io.github.marsinsanity.mainui.tools.FolderGrant $args", 60)

    /** Makes MainUI hold the tree links [uris]; those it holds afterwards. Root, off the UI thread. */
    @Synchronized
    private fun ensureHeld(uris: List<String>): Set<String> {
        val missing = uris - held()
        if (missing.isNotEmpty()) {
            val (pkg, uid) = picker() ?: return held().intersect(uris.toSet()).also { Log.w(TAG, "no folder picker app found") }
            kept.clear()
            nonce = UUID.randomUUID().toString()
            try {
                val component = "${ctx.packageName}/${FolderGrantService::class.java.name}"
                val r = helper("offer $uid ${Root.q(pkg)} ${Root.q(component)} $nonce ${missing.joinToString(" ") { Root.q(it) }}")
                if (r.lines.any { it.startsWith("error") }) Log.w(TAG, "offer: ${r.out} ${r.err}")
                // (The service keeps each one on MainUI's main thread, after the helper is done.)
                val until = System.currentTimeMillis() + 10_000
                while (!kept.containsAll(missing) && System.currentTimeMillis() < until) Thread.sleep(100)
            } finally {
                nonce = null
            }
        }
        return held().intersect(uris.toSet())
    }

    /**
     * Gives emulator [pkg] lasting read and write access to the folders [docIds] it doesn't have
     * yet. The folders it has afterwards. Root, off the UI thread.
     */
    fun giveTo(pkg: String, docIds: List<String>): List<String> {
        if (docIds.isEmpty()) return emptyList()
        val uid = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: return emptyList()
        val have = SafGrants.known(pkg, uid, fresh = true).orEmpty()
        val missing = docIds.filter { it !in have }
        if (missing.isNotEmpty()) {
            val uris = ensureHeld(missing.map(::treeUri))
            for (u in uris) runCatching {
                ctx.grantUriPermission(pkg, Uri.parse(u), READ_WRITE or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }.onFailure { Log.w(TAG, "grant $u to $pkg", it) }
            if (uris.isNotEmpty()) {
                val r = helper("persist ${Root.q(pkg)} ${uris.joinToString(" ") { Root.q(it) }}")
                if (r.lines.any { it.startsWith("error") }) Log.w(TAG, "persist: ${r.out} ${r.err}")
            }
        }
        val now = SafGrants.known(pkg, uid, fresh = true).orEmpty()
        return docIds.filter { it in now }
    }
}

/**
 * Keeps a folder the picker's app (root, see [FolderGrants]) offers MainUI. Only that app can
 * start it (MANAGE_DOCUMENTS), and only while MainUI is waiting for the offer.
 */
class FolderGrantService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.data
        if (uri != null && FolderGrants.nonce != null && intent.getStringExtra("nonce") == FolderGrants.nonce) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                FolderGrants.kept(uri)
            }.onFailure { Log.w("MainUI.Folders", "keeping $uri", it) }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** Does nothing: its intent filter only makes MainUI visible to the folder picker's app (Android 11+). */
class PickerVisibilityActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
