package io.github.marsinsanity.mainui.core

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * Knows about the device's storage volumes and the on-disk layout MainUI uses.
 *
 * Layout per volume (dArkOS compatible):
 *   <volume>/roms/<system>/...            rom folders, gamelist.xml, images/
 *   <volume>/roms2/<system>/...           second rom root (dArkOS dual card naming)
 *   <volume>/Themes/<theme>/              OnionOS themes (also <volume>/MainUI/Themes)
 *   <volume>/MainUI/                      MainUI data: config, screenshots, overrides
 */
class Storage(private val ctx: Context, private val settings: Settings) {

    data class Volume(val dir: File, val removable: Boolean, val uuid: String?, val label: String) {
        val isInternal get() = !removable
    }

    fun volumes(): List<Volume> {
        val out = mutableListOf<Volume>()
        val sm = ctx.getSystemService(StorageManager::class.java)
        runCatching {
            for (v in sm.storageVolumes) {
                val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else volumePath(v)
                if (dir != null && dir.exists()) {
                    out += Volume(dir, v.isRemovable, v.uuid, v.getDescription(ctx) ?: dir.name)
                }
            }
        }
        if (out.none { !it.removable }) {
            out += Volume(Environment.getExternalStorageDirectory(), false, null, "Internal storage")
        }
        // Fallback probe for cards the StorageManager did not report.
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}")) && out.none { it.dir == f }) {
                out += Volume(f, true, f.name, "SD card")
            }
        }
        return out.sortedBy { if (it.removable) 0 else 1 } // SD card first, like a Miyoo
    }

    @Suppress("DiscouragedPrivateApi")
    private fun volumePath(v: android.os.storage.StorageVolume): File? = runCatching {
        val m = v.javaClass.getDeclaredMethod("getPathFile")
        m.invoke(v) as File
    }.getOrNull()

    val internal: Volume get() = volumes().first { !it.removable }
    val sdCard: Volume? get() = volumes().firstOrNull { it.removable }

    /** All rom root folders that exist, SD card first. */
    fun romRoots(): List<File> {
        val roots = mutableListOf<File>()
        // Shared storage is case-insensitive on Android, so probing "roms", "ROMs"...
        // one by one would return the same folder several times. Use the real listing.
        fun add(f: File) {
            val key = f.canonicalPathOrSelf().lowercase()
            if (f.isDirectory && roots.none { it.canonicalPathOrSelf().lowercase() == key }) roots += f
        }
        for (v in volumes()) {
            val children = v.dir.listFiles() ?: continue
            for (name in listOf("roms", "roms2")) children.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let(::add)
        }
        settings.extraRomRoots.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(File(it)) }
        return roots
    }

    /** Root for MainUI's own data. Prefers an existing MainUI folder. */
    fun dataRoot(): File {
        val vols = volumes()
        val choice = when (settings.dataLocation) {
            "sd" -> vols.firstOrNull { it.removable }
            "internal" -> vols.firstOrNull { !it.removable }
            else -> vols.firstOrNull { File(it.dir, "MainUI").isDirectory } ?: vols.firstOrNull { !it.removable }
        } ?: vols.first()
        val root = File(choice.dir, "MainUI")
        if (!root.exists()) root.mkdirs()
        if (!root.canWrite()) {
            // No "All files access" yet: fall back to app specific storage so we never crash.
            return File(ctx.getExternalFilesDir(null), "MainUI").apply { mkdirs() }
        }
        return root
    }

    fun dataDir(sub: String): File = File(dataRoot(), sub).apply { mkdirs() }

    val configDir get() = dataDir("config")
    val screenshotDir get() = dataDir("Screenshots")
    val stateThumbDir get() = dataDir("States")
    val fontDir get() = dataDir("Fonts")
    val themeOverrideDir get() = File(dataRoot(), "theme_overrides")

    /** Theme search folders: <vol>/Themes and <vol>/MainUI/Themes. */
    fun themeDirs(): List<File> {
        val dirs = mutableListOf<File>()
        for (v in volumes()) {
            dirs += File(v.dir, "Themes")
            dirs += File(v.dir, "MainUI/Themes")
        }
        return dirs.filter { it.isDirectory }
    }

    /** Root to write scraped images into for the given rom root. */
    fun imageRootFor(romRoot: File): File {
        val wantInternal = settings.ssImageStorage == "internal"
        val vol = volumes().firstOrNull { romRoot.absolutePath.startsWith(it.dir.absolutePath) }
        if (vol == null) return romRoot
        val target = if (wantInternal) internal else (sdCard ?: internal)
        if (target.dir == vol.dir) return romRoot
        return File(target.dir, romRoot.relativeTo(vol.dir).path)
    }

    /** Every volume-mirrored copy of the same relative path (for image lookups). */
    fun mirrors(file: File): List<File> {
        val vols = volumes()
        val v = vols.firstOrNull { file.absolutePath.startsWith(it.dir.absolutePath + "/") } ?: return listOf(file)
        val rel = file.relativeTo(v.dir).path
        return listOf(file) + vols.filter { it.dir != v.dir }.map { File(it.dir, rel) }
    }

    /**
     * Storage Access Framework document URI for a file, the format standalone
     * emulators expect when they were granted folder access (same scheme ES-DE uses).
     */
    fun safUri(file: File): Uri {
        val vol = volumes().firstOrNull { file.absolutePath.startsWith(it.dir.absolutePath + "/") }
        val volId = if (vol == null || !vol.removable) "primary" else (vol.uuid ?: vol.dir.name)
        val rel = if (vol != null) file.relativeTo(vol.dir).path else file.absolutePath.trimStart('/')
        val treeDoc = Uri.encode("$volId:")
        val doc = Uri.encode("$volId:$rel")
        return Uri.parse("content://com.android.externalstorage.documents/tree/$treeDoc/document/$doc")
    }

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true
}

fun File.canonicalPathOrSelf(): String = runCatching { canonicalPath }.getOrDefault(absolutePath)
