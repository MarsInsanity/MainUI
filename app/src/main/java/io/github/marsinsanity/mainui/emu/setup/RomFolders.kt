package io.github.marsinsanity.mainui.emu.setup

import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.FolderGrants
import io.github.marsinsanity.mainui.core.Root
import org.json.JSONArray
import java.io.File

/**
 * MainUI's game folders in each emulator's own settings, so it lists them and can open the games
 * MainUI starts: its systems' folders (on every storage), granted to it for good with root
 * ([FolderGrants]) and added to the folders it already has. Folders are tree links
 * ("content://com.android.externalstorage.documents/tree/primary%3ARoms%2Fgc") unless said.
 */
internal object RomFolders {
    /** The folders of the systems the emulators [recipeIds] play ("sa:dolphin": gc, wii...). */
    fun dirs(recipeIds: List<String>): List<File> {
        val refs = recipeIds.map { "sa:$it" }
        return MainUI.systems.discover(false).filter { s -> s.def.emulators.any { it in refs } }.flatMap { it.dirs }.distinct()
    }

    /** Grants [pkg] [dirs] (root): their folder ids ("primary:Roms/gc") it holds now. */
    fun grant(pkg: String, dirs: List<File>): List<String> = FolderGrants.giveTo(pkg, dirs.map { MainUI.storage.documentId(it) })

    /** "gc, wii" for a setup's message. */
    fun names(docIds: List<String>) = docIds.map { it.substringAfterLast('/').substringAfterLast(':') }.distinct().joinToString(", ")

    /** The end of a setup's message: which game folders it got (or that it couldn't get [wanted] ones). */
    fun message(docIds: List<String>, wanted: List<File>) = when {
        docIds.isNotEmpty() -> ", games from ${names(docIds)}"
        wanted.isNotEmpty() -> ", but MainUI couldn't give it the game folders"
        else -> ""
    }

    /** Lets [pkg] open files by path (All files access; storage permissions before Android 11). */
    fun allowFilePaths(pkg: String) {
        val q = Root::q
        // (Per uid as Settings sets it, and per package; nothing changes for an app that doesn't ask for it.)
        Root.exec("appops set --uid ${q(pkg)} MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; appops set ${q(pkg)} MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; " +
            "pm grant ${q(pkg)} android.permission.READ_EXTERNAL_STORAGE 2>/dev/null; pm grant ${q(pkg)} android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null; true")
    }

    private fun merged(old: List<String>, add: List<String>) = (old + add).filter { it.isNotBlank() }.distinct()

    /** Dolphin.ini [General]: ISOPaths (the count) and ISOPath0..., searched in subfolders too. */
    fun dolphin(ini: IniText, uris: List<String>) {
        val n = ini.get("General", "ISOPaths")?.toIntOrNull() ?: 0
        val old = (0 until n).mapNotNull { ini.get("General", "ISOPath$it") }
        val all = merged(old, uris)
        ini.set("General", "ISOPaths", all.size.toString())
        all.forEachIndexed { i, u -> ini.set("General", "ISOPath$i", u) }
        ini.set("General", "RecursiveISOPaths", "True")
    }

    /**
     * ppsspp.ini: its game browser starts in the first folder ([General] CurrentDirectory), the
     * others pinned ([PinnedPaths] Path0...). PPSSPP keeps folders as documents of their own tree.
     */
    fun ppsspp(ini: IniText, folderDocUris: List<String>) {
        folderDocUris.firstOrNull()?.let { ini.set("General", "CurrentDirectory", it) }
        val old = generateSequence(0) { it + 1 }.map { ini.get("PinnedPaths", "Path$it") }.takeWhile { it != null }.filterNotNull().toList()
        ini.replaceSection("PinnedPaths", merged(old, folderDocUris).mapIndexed { i, u -> "Path$i" to u })
    }

    /** ARMSX2.xml romsDirs: a JSON array of tree links, each exactly as granted. */
    fun armsx2(prefs: PrefsXml, uris: List<String>) {
        val old = prefs.string("romsDirs")?.let { s -> runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull() }
            ?: listOfNotNull(prefs.string("roms"))
        prefs.putString("romsDirs", JSONArray(merged(old, uris)).toString())
        prefs.remove("roms")
    }

    /** melonDS's rom_search_dirs, searched at every start. */
    fun melon(prefs: PrefsXml, uris: List<String>) = prefs.putSet("rom_search_dirs", merged(prefs.stringSet("rom_search_dirs").orEmpty(), uris))

    /** SeedlessDS rom_library.xml folders (only their own files are listed, not subfolders). */
    fun seedless(prefs: PrefsXml, uris: List<String>) = prefs.putSet("folders", merged(prefs.stringSet("folders").orEmpty(), uris))

    /** Flycast emu.cfg [config] Dreamcast.ContentPath, ';' separated. */
    fun flycast(ini: IniText, uris: List<String>) {
        val old = ini.get("config", "Dreamcast.ContentPath")?.split(';')?.map { it.trim().trim('"') }.orEmpty()
        ini.set("config", "Dreamcast.ContentPath", merged(old, uris).joinToString(";"))
    }

    /**
     * MAME4droid: one folder, as a tree link and as its path ([path] "/storage/emulated/0/Roms/arcade");
     * its installation folder when it has none yet (else it asks on start), and its folder cache
     * read again. [installDir] ends in "/".
     */
    fun mame(prefs: PrefsXml, uri: String, path: String, installDir: String) {
        prefs.putString("PREF_SAF_URI", uri)
        prefs.putString("PREF_ROMsDIR_2", path.trimEnd('/'))
        if (prefs.string("PREF_INSTALLATION_DIR").isNullOrEmpty()) prefs.putString("PREF_INSTALLATION_DIR", installDir)
        prefs.putBoolean("PREF_SAF_FORCE_RESCAN", true)
    }
}
