package io.github.marsinsanity.mainui.emu.setup

import android.view.MotionEvent
import io.github.marsinsanity.mainui.core.Root
import java.net.URLDecoder

/**
 * PPSSPP's controls: [ControlMapping] in PSP/SYSTEM/controls.ini, "<PSP control> = <device>-<code>"
 * bindings, comma separated, ':' joining a combo. Every gamepad is device 10, a keyboard without
 * letters (MainUI's) device 0, one with letters 1. Key codes are Android's; an axis direction is
 * 4000 + axis * 2 (+ 1 for negative). A section lists every control: the ones it leaves out are
 * unbound. A game with its own <GAME ID>_ppsspp.ini uses that file's [ControlMapping] (its
 * defaults when it has none), so those get the same section.
 */
internal object PpssppMapping {
    private const val PAD = 10

    private fun axis(axis: Int, positive: Boolean) = "$PAD-${4000 + axis * 2 + if (positive) 0 else 1}"
    private fun key(code: Int, device: Int = PAD) = "$device-$code"

    /** The handheld button [r], with its trigger or hat axis. */
    private fun btn(p: ControlsProfile, pad: PadInfo, r: Role): List<String> {
        val code = p.key(r)
        val b = mutableListOf(key(code))
        if (code == r.defaultKey) when (r) {
            Role.L2 -> pad.leftTrigger?.let { b += axis(it, true) }
            Role.R2 -> pad.rightTrigger?.let { b += axis(it, true) }
            Role.UP -> if (pad.hasHat) b += axis(MotionEvent.AXIS_HAT_Y, false)
            Role.DOWN -> if (pad.hasHat) b += axis(MotionEvent.AXIS_HAT_Y, true)
            Role.LEFT -> if (pad.hasHat) b += axis(MotionEvent.AXIS_HAT_X, false)
            Role.RIGHT -> if (pad.hasHat) b += axis(MotionEvent.AXIS_HAT_X, true)
            else -> {}
        }
        return b
    }

    private fun combo(p: ControlsProfile, h: Hotkey) = "${key(p.hotkeyButton)}:${key(p.hotkey(h))}"

    /** MainUI's keyboard keys, as a keyboard without letters and, in case, one with them. */
    private fun keyboard(spec: String?): List<String> {
        val codes = KeySpec.codes(spec) ?: return emptyList()
        return listOf(0, 1).map { dev -> codes.joinToString(":") { key(it, dev) } }
    }

    fun entries(p: ControlsProfile, pad: PadInfo, saveSpec: String?, loadSpec: String?): List<Pair<String, String>> {
        val e = mutableListOf<Pair<String, List<String>>>()
        e += "Up" to btn(p, pad, Role.UP)
        e += "Down" to btn(p, pad, Role.DOWN)
        e += "Left" to btn(p, pad, Role.LEFT)
        e += "Right" to btn(p, pad, Role.RIGHT)
        // By position: Cross at the bottom, Circle on the right.
        e += "Circle" to btn(p, pad, Role.EAST)
        e += "Cross" to btn(p, pad, Role.SOUTH)
        e += "Square" to btn(p, pad, Role.WEST)
        e += "Triangle" to btn(p, pad, Role.NORTH)
        e += "Start" to btn(p, pad, Role.START)
        e += "Select" to btn(p, pad, Role.SELECT)
        e += "L" to btn(p, pad, Role.L1)
        e += "R" to btn(p, pad, Role.R1)
        if (pad.hasLeftStick) {
            e += "An.Up" to listOf(axis(MotionEvent.AXIS_Y, false))
            e += "An.Down" to listOf(axis(MotionEvent.AXIS_Y, true))
            e += "An.Left" to listOf(axis(MotionEvent.AXIS_X, false))
            e += "An.Right" to listOf(axis(MotionEvent.AXIS_X, true))
        }
        pad.rightStick?.let { (x, y) ->
            e += "RightAn.Up" to listOf(axis(y, false))
            e += "RightAn.Down" to listOf(axis(y, true))
            e += "RightAn.Left" to listOf(axis(x, false))
            e += "RightAn.Right" to listOf(axis(x, true))
        }
        // PPSSPP's own: the menu on L2, fast forward (held) on R2; plus the hotkey combos.
        e += "Pause" to btn(p, pad, Role.L2)
        e += "Fast-forward" to btn(p, pad, Role.R2) + combo(p, Hotkey.FAST_FORWARD)
        e += "Screenshot" to listOf(combo(p, Hotkey.SCREENSHOT))
        // Save / load on MainUI's keyboard keys: its in-game menu presses them without the State API.
        keyboard(saveSpec).takeIf { it.isNotEmpty() }?.let { e += "Save State" to it }
        keyboard(loadSpec).takeIf { it.isNotEmpty() }?.let { e += "Load State" to it }
        return e.map { (k, v) -> k to v.distinct().joinToString(",") }
    }

    /**
     * The folder memstick_dir.txt names: a path, or a storage access framework tree / document URI
     * on the device's own storage ("primary:Games/PSP" is /storage/emulated/0/Games/PSP). Null for
     * a URI that isn't on local storage.
     */
    fun memstickPath(setting: String): String? {
        val s = setting.trim()
        if (!s.startsWith("content://")) return s.trimEnd('/').ifEmpty { null }
        if (!s.startsWith("content://com.android.externalstorage.documents/")) return null
        val rest = s.substringAfter("/tree/", "").ifEmpty { s.substringAfter("/document/", "") }
        val id = URLDecoder.decode((rest.substringAfter("/document/", "").ifEmpty { rest.substringBefore('/') }), "UTF-8")
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "").trim('/')
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (path.isEmpty()) root else "$root/$path"
    }

    /** PPSSPP's PSP folder: <memstick>/PSP, or the memstick itself when it is named PSP. */
    fun pspFolder(memstick: String) = if (memstick.substringAfterLast('/').equals("PSP", ignoreCase = true)) memstick else "$memstick/PSP"
}

object PpssppSetup : EmulatorSetup {
    override val name = "PPSSPP"
    private val PACKAGES = listOf(
        "org.ppsspp.ppsspp.mainui" to "PPSSPP (MainUI build)", "org.ppsspp.ppsspp" to "PPSSPP", "org.ppsspp.ppssppgold" to "PPSSPP Gold",
    )

    override fun installed(): List<Pair<String, String>> = SetupFiles.installed(PACKAGES)

    override fun apply(pkg: String, label: String, p: ControlsProfile, pad: PadInfo, wiiGames: () -> List<String>): String {
        Root.forceStop(pkg)
        val setting = Root.readText("${SetupFiles.appData(pkg)}/files/memstick_dir.txt")?.trim().orEmpty()
        val memstick = if (setting.isNotEmpty()) PpssppMapping.memstickPath(setting)
            ?: throw SetupProblem("$label's storage folder isn't on this device's storage; MainUI can't reach it.")
        else "/storage/emulated/0" // older installs without scoped storage: the storage root
        val system = "${PpssppMapping.pspFolder(memstick)}/SYSTEM"
        if (!Root.dirExists(system)) throw SetupProblem("Start $label once and pick its storage folder, then set up again.")
        val (save, load) = SetupFiles.saveLoadKeys("ppsspp")
        val entries = PpssppMapping.entries(p, pad, save, load)
        SetupFiles.editIni("$system/controls.ini") { it.replaceSection("ControlMapping", entries) }
        var games = 0
        for (f in Root.listDir(system).filter { it.endsWith("_ppsspp.ini", ignoreCase = true) }) {
            SetupFiles.editIni("$system/$f") { it.replaceSection("ControlMapping", entries) }
            games++
        }
        return "controller, menu on L2, fast forward on R2 and the hotkey, save / load keys" +
            if (games > 0) " (also in $games game setting${if (games > 1) "s" else ""})" else ""
    }
}
