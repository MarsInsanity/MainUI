package io.github.marsinsanity.mainui.data

import android.content.Context
import io.github.marsinsanity.mainui.core.Storage
import org.json.JSONObject
import java.io.File

/** A known console/computer definition loaded from assets/systems.json. */
data class SystemDef(
    val id: String,
    val name: String,
    val folders: List<String>,
    val extensions: Set<String>,
    /** Emulator refs in preference order: "ra:<core>" or "sa:<standalone id>". */
    val emulators: List<String>,
    val screenscraperId: Int?,
    /** Theme icon basenames to try, in order (dArkOS name first, Onion alias second). */
    val icons: List<String>,
    val short: String,
) {
    fun accepts(file: File): Boolean {
        if (extensions.isEmpty()) return true
        return file.extension.lowercase() in extensions
    }

    companion object {
        /** Virtual system for Android-native games. */
        val ANDROID = SystemDef("android", "Android", emptyList(), emptySet(), emptyList(), null,
            listOf("android", "app"), "APK")
        /** Virtual "Search" tile shown first in the games grid (OnionOS does the same). */
        val SEARCH = SystemDef("search", "Search", emptyList(), emptySet(), emptyList(), null, listOf("search"), "?")

        fun unknown(folder: String) = SystemDef(folder, folder.replaceFirstChar { it.uppercase() }, listOf(folder),
            emptySet(), emptyList(), null, listOf(folder), folder.take(4).uppercase())
    }
}

/** A system that has a folder on at least one rom root. */
data class GameSystem(val def: SystemDef, val dirs: List<File>) {
    val id get() = def.id
    val name get() = def.name
    @Volatile var gameCount: Int = -1
    /** Files in its folders MainUI can't play here (lowercase extension -> count), from the last count. */
    @Volatile var otherFiles: Map<String, Int> = emptyMap()

    /** "3 .7z, 1 .zip" for a system that has files but none MainUI plays; null otherwise. */
    val unplayableSummary: String?
        get() = if (gameCount != 0 || otherFiles.isEmpty()) null
        else otherFiles.entries.sortedByDescending { it.value }.take(3).joinToString(", ") { "${it.value} .${it.key}" }
}

class SystemRegistry(ctx: Context, private val storage: Storage) {
    val defs: List<SystemDef>
    private val byFolder = HashMap<String, SystemDef>()
    private val byId = HashMap<String, SystemDef>()

    init {
        val txt = ctx.assets.open("systems.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(txt).getJSONArray("systems")
        val list = mutableListOf<SystemDef>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list += SystemDef(
                id = o.getString("id"),
                name = o.getString("name"),
                folders = o.getJSONArray("folders").strings(),
                extensions = o.getJSONArray("extensions").strings().map { it.lowercase() }.toSet(),
                emulators = o.getJSONArray("emulators").strings(),
                screenscraperId = if (o.isNull("screenscraper")) null else o.optInt("screenscraper"),
                icons = o.getJSONArray("icons").strings(),
                short = o.optString("short", o.getString("id").uppercase()),
            )
        }
        defs = list
        for (d in defs) {
            byId[d.id] = d
            for (f in d.folders) byFolder[f.lowercase()] = d
        }
        byId[SystemDef.ANDROID.id] = SystemDef.ANDROID
    }

    fun byId(id: String): SystemDef? = byId[id]

    /** Scans every rom root and merges same-named system folders across volumes. */
    fun discover(includeUnknown: Boolean): List<GameSystem> {
        val found = LinkedHashMap<String, MutableList<File>>()
        val defsFound = HashMap<String, SystemDef>()
        for (root in storage.romRoots()) {
            val children = root.listFiles() ?: continue
            for (dir in children) {
                if (!dir.isDirectory || dir.name.startsWith(".")) continue
                val lname = dir.name.lowercase()
                if (lname in IGNORED_FOLDERS) continue
                var def = byFolder[lname]
                var target = dir
                if (def == null) {
                    // nested folders such as pico-8/carts
                    val nested = byFolder.entries.firstOrNull { it.key.startsWith("$lname/") }
                    if (nested != null) {
                        def = nested.value
                        target = File(root, nested.key.let { k -> dir.name + k.substring(lname.length) })
                        if (!target.isDirectory) continue
                    }
                }
                if (def == null) {
                    if (!includeUnknown) continue
                    def = SystemDef.unknown(dir.name)
                }
                defsFound[def.id] = def
                val dirs = found.getOrPut(def.id) { mutableListOf() }
                if (dirs.none { it.absolutePath.equals(target.absolutePath, ignoreCase = true) }) dirs += target
            }
        }
        return found.map { (id, dirs) -> GameSystem(defsFound.getValue(id), dirs) }
            .sortedBy { it.name.lowercase() }
    }

    companion object {
        val IGNORED_FOLDERS = setOf(
            "bios", "ports", "themes", "images", "media", "videos", "manuals", "screenshots",
            "savestates", "saves", "backup", "backups", "tools", "opt", "lost+found", "mainui",
        )
    }
}

fun org.json.JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
