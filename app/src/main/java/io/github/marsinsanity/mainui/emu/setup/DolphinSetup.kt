package io.github.marsinsanity.mainui.emu.setup

import io.github.marsinsanity.mainui.core.Root

/**
 * Dolphin (official and MainUI builds; MMJR's old input settings differ and are left alone).
 * Writes, in its user folder:
 *  - Config/GCPadNew.ini [GCPad1]: GameCube controller 1 on the handheld;
 *  - Config/WiimoteNew.ini [Wiimote1]: emulated Wii Remote 1 as the default Wii controller;
 *  - Config/Profiles/Wiimote/MainUI *.ini: one profile per [WiiController];
 *  - GameSettings/<disc id>.ini [Controls] WiimoteProfile1: the profile of each Wii game played
 *    another way than the default (Dolphin loads it when the game starts);
 *  - Config/Hotkeys.ini [Hotkeys]: fast forward, screenshot, and save / load on MainUI's keys.
 * Those sections are replaced whole; everything else in the files stays. Each file's first
 * change keeps the original as <file>.mainui.bak.
 */
object DolphinSetup : EmulatorSetup {
    override val name = "Dolphin"
    private const val OFFICIAL = "org.dolphinemu.dolphinemu"
    private val PACKAGES = listOf("$OFFICIAL.mainui" to "Dolphin (MainUI build)", OFFICIAL to "Dolphin")
    private const val PROFILE_KEY = "WiimoteProfile1"

    override fun installed(): List<Pair<String, String>> = SetupFiles.installed(PACKAGES)

    /**
     * Dolphin's user folder: its Android/data files folder, or the old shared dolphin-emu folder an
     * official build on Android 10 or older may still use. Null before Dolphin's first start.
     */
    private fun userDir(pkg: String): String? {
        val dirs = listOf("/storage/emulated/0/Android/data/$pkg/files") +
            if (pkg == OFFICIAL) listOf("/storage/emulated/0/dolphin-emu") else emptyList()
        return dirs.firstOrNull { Root.dirExists("$it/Config") }
    }

    /** Edits [rel] under [dir] (made when missing); written only when [change] changed it. */
    private fun edit(dir: String, rel: String, change: (IniText) -> Unit): Boolean = SetupFiles.editIni("$dir/$rel", change)

    private fun gameRel(discId: String) = "GameSettings/$discId.ini"

    private fun setGame(ini: IniText, c: WiiController, p: ControlsProfile) {
        val cur = ini.get("Controls", PROFILE_KEY)
        when {
            c != p.wiiDefault -> ini.set("Controls", PROFILE_KEY, c.profile)
            // Back to the default: only MainUI's own choice goes, never one the user made in Dolphin.
            cur != null && cur.startsWith("MainUI ") -> ini.set("Controls", PROFILE_KEY, null)
        }
    }

    override fun apply(pkg: String, label: String, p: ControlsProfile, pad: PadInfo, wiiGames: () -> List<String>): String {
        val dir = userDir(pkg) ?: throw SetupProblem("Start $label once (it makes its settings folder), then set up again.")
        // Dolphin writes its controller settings back when it closes.
        Root.forceStop(pkg)
        edit(dir, "Config/GCPadNew.ini") { it.replaceSection("GCPad1", DolphinMapping.gcPad(p, pad)) }
        edit(dir, "Config/WiimoteNew.ini") {
            it.replaceSection("Wiimote1", listOf("Source" to "1") + DolphinMapping.wiimote(p.wiiDefault, p, pad))
        }
        for (t in WiiController.entries) {
            edit(dir, "Config/Profiles/Wiimote/${t.profile}.ini") { it.replaceSection("Profile", DolphinMapping.wiimote(t, p, pad)) }
        }
        val (save, load) = SetupFiles.saveLoadKeys("dolphin")
        edit(dir, "Config/Hotkeys.ini") { it.replaceSection("Hotkeys", DolphinMapping.hotkeys(p, pad, save, load)) }
        var games = 0
        for (id in wiiGames()) {
            val c = Controls.wiiFor(id, p)
            if (edit(dir, gameRel(id)) { setGame(it, c, p) } && c != p.wiiDefault) games++
        }
        val motion = when {
            !pad.hasMotion -> "no motion sensors"
            p.wiiPointer == WiiPointer.MOTION -> "motion controls and pointer"
            p.wiiMotion -> "motion controls"
            else -> "no motion controls"
        }
        return "GameCube controller, Wii Remote (${p.wiiDefault.label}, $motion)" +
            (if (games > 0) ", ${if (games > 1) "$games Wii games" else "1 Wii game"} with a controller of ${if (games > 1) "their" else "its"} own" else "") + ", hotkeys"
    }

    /** One Wii game's controller (its options in MainUI). */
    fun applyGame(pkg: String, discId: String, c: WiiController, p: ControlsProfile) {
        val dir = userDir(pkg) ?: return
        Root.forceStop(pkg)
        edit(dir, gameRel(discId)) { setGame(it, c, p) }
    }
}
