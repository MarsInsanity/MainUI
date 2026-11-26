package io.github.marsinsanity.mainui.emu.setup

import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.emu.Hotkeys

/** Root file editing shared by the [EmulatorSetup]s. */
internal object SetupFiles {
    /**
     * Edits [path] (empty text when it doesn't exist yet) and writes it back, made like its
     * folder's files when new, only when [change] changed it. The first change keeps the original
     * as <file>.mainui.bak. True when it was written.
     */
    fun editText(path: String, change: (String) -> String): Boolean {
        val name = path.substringAfterLast('/')
        val exists = Root.fileExists(path) ?: throw SetupProblem("root couldn't check $name")
        val old = if (exists) Root.readFile(path) ?: throw SetupProblem("couldn't read $name") else ""
        val text = change(old)
        if (text == old) return false
        val w = Root.writeOwnedFile(path, text, backup = old.isNotEmpty())
        if (!w.ok) throw SetupProblem("couldn't write $name: ${w.error}")
        return true
    }

    /** (Unchanged entries leave the file exactly as it was, line ends and all.) */
    fun editIni(path: String, change: (IniText) -> Unit): Boolean = editText(path) { old ->
        val ini = IniText(old)
        val before = ini.toString()
        change(ini)
        ini.toString().takeIf { it != before } ?: old
    }

    /**
     * An app's SharedPreferences file; the app must be stopped first. A leftover <file>.bak (a
     * write the app didn't finish) is what Android loads instead, over the file: it is edited
     * then, written as the file, and removed.
     */
    fun editPrefs(path: String, change: (PrefsXml) -> Unit): Boolean {
        val bak = "$path.bak"
        if (Root.fileExists(bak) == true) {
            val prefs = PrefsXml(Root.readFile(bak) ?: throw SetupProblem("couldn't read ${bak.substringAfterLast('/')}"))
            change(prefs)
            val w = Root.writeOwnedFile(path, prefs.toString(), backup = Root.fileExists(path) == true)
            if (!w.ok) throw SetupProblem("couldn't write ${path.substringAfterLast('/')}: ${w.error}")
            Root.exec("rm -f ${Root.q(bak)}")
            return true
        }
        return editText(path) { old ->
            val prefs = PrefsXml(old)
            val before = prefs.toString()
            change(prefs)
            prefs.toString().takeIf { it != before } ?: old
        }
    }

    /** The [packages] (package to name shown) that are installed. */
    fun installed(packages: List<Pair<String, String>>): List<Pair<String, String>> {
        val pm = MainUI.app.packageManager
        return packages.filter { (pkg, _) -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }
    }

    /** The first of [dirs] that exists (root); null when none does yet. */
    fun firstDir(dirs: List<String>): String? = dirs.firstOrNull { Root.dirExists(it) }

    fun androidData(pkg: String) = "/storage/emulated/0/Android/data/$pkg/files"
    fun appData(pkg: String) = "/data/data/$pkg"

    /** MainUI's save / load keys for emulator [id] (the user's, else its recipe's), as key specs. */
    fun saveLoadKeys(id: String): Pair<String?, String?> {
        val def = MainUI.emulators.standaloneDefs().firstOrNull { it.id == id }
        val (save, load) = Hotkeys.get(id)
        return (save ?: def?.save) to (load ?: def?.load)
    }
}

/** MainUI's key specs ("keys:KEYCODE_F1", "keys:KEYCODE_SHIFT_LEFT+KEYCODE_F4") as Android key codes. */
internal object KeySpec {
    fun codes(spec: String?): List<Int>? {
        val names = spec?.removePrefix("keys:")?.split('+')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        if (names.isEmpty()) return null
        return names.map { n ->
            Regex("KEYCODE_F(\\d+)").matchEntire(n)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..12 }?.let { 130 + it } ?: when (n) {
                "KEYCODE_SHIFT_LEFT" -> 59
                "KEYCODE_ALT_LEFT" -> 57
                "KEYCODE_CTRL_LEFT" -> 113
                else -> return null
            }
        }
    }
}
