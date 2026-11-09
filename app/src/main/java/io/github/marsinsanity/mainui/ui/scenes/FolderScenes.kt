package io.github.marsinsanity.mainui.ui.scenes

import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.Glyphs
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem
import java.io.File

/**
 * Lets the user manage rom folders without typing paths: shows the folders
 * MainUI found by itself (roms/ and roms2/ on every volume) and the ones added
 * by hand, which can be removed with Y.
 */
class RomFoldersScene(host: SceneHost) : ListScene(host, "Rom folders") {
    override val showPreview = false
    override var aLabel: String? = "OPEN"
    override val extraHints get() = listOf("Y" to "REMOVE")

    override fun onEnter() = build()
    override fun onResume() = build()

    private fun extras(): List<String> =
        MainUI.settings.extraRomRoots.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun build() {
        val extra = extras()
        val found = MainUI.storage.romRoots().filter { r -> extra.none { File(it).absolutePath == r.absolutePath } }
        val items = mutableListOf<UiItem>()
        items += UiItem("Add folder...", icon = { Glyphs.get("folder", 40) }, onSelect = {
            host.push(FolderPickerScene(host, "Choose a rom folder") { dir -> add(dir) })
        })
        for (r in found) items += UiItem(pretty(r), sub = "found automatically", data = null)
        for (p in extra) items += UiItem(pretty(File(p)), sub = "added", data = p)
        list.set(items)
    }

    private fun add(dir: File) {
        val cur = extras()
        if (cur.any { File(it).absolutePath == dir.absolutePath } || MainUI.storage.romRoots().any { it.absolutePath == dir.absolutePath }) {
            host.toast("Already a rom folder"); return
        }
        MainUI.settings.extraRomRoots = (cur + dir.absolutePath).joinToString("\n")
        MainUI.games.invalidate()
        host.toast("Added ${dir.name}")
        build()
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val p = list.current?.data as? String
        if (b == Btn.Y && !repeat && p != null) {
            host.push(DialogScene(host, "Remove rom folder", "Stop scanning\n${pretty(File(p))}?\nNo files are deleted.") {
                MainUI.settings.extraRomRoots = extras().filter { it != p }.joinToString("\n")
                MainUI.games.invalidate()
                build()
            })
            return true
        }
        return super.onKey(b, repeat)
    }

    companion object {
        /** "/storage/1234-ABCD/Games/roms" -> "SD card/Games/roms". */
        fun pretty(f: File): String {
            val v = MainUI.storage.volumes().firstOrNull { f.absolutePath == it.dir.absolutePath || f.absolutePath.startsWith(it.dir.absolutePath + "/") }
                ?: return f.absolutePath
            val name = if (v.removable) "SD card" else "Internal storage"
            val rel = f.absolutePath.removePrefix(v.dir.absolutePath).trimStart('/')
            return if (rel.isEmpty()) name else "$name/$rel"
        }
    }
}

/**
 * Gamepad and touch folder browser. Starts at the storage volumes (internal
 * storage, SD card); A opens a folder, B goes up, X or START picks the folder
 * you are in. The first row always picks the current folder too, so touch users
 * never need the buttons.
 */
class FolderPickerScene(
    host: SceneHost,
    private val purpose: String,
    private val onPick: (File) -> Unit,
) : ListScene(host, purpose) {
    override val showPreview = false
    override var aLabel: String? = "OPEN"
    override val extraHints get() = if (dir != null) listOf("X" to "USE FOLDER") else emptyList()

    /** null = volume list */
    private var dir: File? = null
    private val positions = HashMap<String, Int>()

    override fun onEnter() = show(null)

    private fun show(d: File?, select: String? = null) {
        dir = d
        title = d?.let { RomFoldersScene.pretty(it) } ?: purpose
        val items = mutableListOf<UiItem>()
        if (d == null) {
            for (v in MainUI.storage.volumes()) {
                val label = if (v.removable) "SD card (${v.dir.name})" else "Internal storage"
                items += UiItem(label, icon = { Glyphs.get("folder", 40) }, data = v.dir)
            }
        } else {
            val count = d.listFiles()?.count { it.isFile && !it.name.startsWith(".") } ?: 0
            items += UiItem("Use this folder", sub = if (count > 0) "$count files" else null, data = USE)
            val subs = d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
            if (subs.isEmpty()) items += UiItem("(no sub folders)", disabled = true)
            for (s in subs) {
                // Hint when a folder looks like a dArkOS rom root (contains system folders)
                val systems = s.listFiles()?.count { c -> c.isDirectory && MainUI.systems.defs.any { def -> def.folders.any { it.equals(c.name, true) } } } ?: 0
                items += UiItem(s.name, icon = { Glyphs.get("folder", 40) }, data = s,
                    sub = if (systems >= 2) "$systems systems" else null)
            }
        }
        list.set(items, keepIndex = false)
        if (select != null) list.active = list.items.indexOfFirst { (it.data as? File)?.name == select }.coerceAtLeast(0)
        else d?.let { positions[it.absolutePath] }?.let { list.active = it.coerceIn(0, maxOf(0, list.size - 1)) }
        list.ensureVisible()
    }

    override fun activate() {
        val item = list.current ?: return
        if (item.disabled) return
        when (val data = item.data) {
            USE -> pick()
            is File -> {
                dir?.let { positions[it.absolutePath] = list.active }
                if (data.canRead()) show(data) else host.toast("Can't open ${data.name} (grant All files access)")
            }
        }
    }

    private fun pick() {
        val d = dir ?: return
        close()
        onPick(d)
    }

    override fun back() {
        val d = dir ?: return close()
        val volume = MainUI.storage.volumes().firstOrNull { it.dir.absolutePath == d.absolutePath }
        if (volume != null) show(null) else show(d.parentFile, select = d.name)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if ((b == Btn.X || b == Btn.START) && !repeat && dir != null) { pick(); return true }
        return super.onKey(b, repeat)
    }

    private companion object {
        val USE = Any()
    }
}
