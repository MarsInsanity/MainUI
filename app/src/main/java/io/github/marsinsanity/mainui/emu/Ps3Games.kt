package io.github.marsinsanity.mainui.emu

import io.github.marsinsanity.mainui.core.Root
import java.io.File

/**
 * PS3 title ids, and where RPCSX keeps the game with one.
 *
 * RPCSX can't read the rom folders (it has no storage permission): it boots games
 * installed into its own storage, `Android/data/net.rpcsx/files/config/dev_hdd0/game/<id>`
 * (from a .pkg) and `.../config/games/<folder>` (discs). A game in the rom folder names
 * the one to start by its title id: a `.ps3` shortcut holding it (what ARMSX3's frontend
 * export writes, one id like BLUS12345 per file), or the id in a file or folder name.
 */
object Ps3Games {
    private val idInText = Regex("""\b([A-Z]{4})-?([0-9]{5})\b""")
    const val RPCSX_PKG = "net.rpcsx"
    /** RPCSX's own view of its storage (what its game list stores and boots). */
    const val RPCSX_ROOT = "/storage/emulated/0/Android/data/$RPCSX_PKG/files"

    /** First title id in [text] ("BLUS12345", "BLUS-12345", "[title_id] BLUS12345"), normalized. */
    fun titleIdIn(text: String): String? = idInText.find(text.uppercase())?.let { it.groupValues[1] + it.groupValues[2] }

    /** The title id a rom names: shortcut content, PARAM.SFO of a game folder, or its name. */
    fun titleId(rom: File): String? {
        if (rom.isFile && rom.length() <= 4096 && rom.extension.equals("ps3", true)) {
            runCatching { titleIdIn(rom.readText()) }.getOrNull()?.let { return it }
        }
        if (rom.isDirectory) {
            for (sfo in listOf("PS3_GAME/PARAM.SFO", "PARAM.SFO")) {
                val f = File(rom, sfo)
                if (f.isFile) runCatching { titleIdIn(String(f.readBytes(), Charsets.ISO_8859_1)) }.getOrNull()?.let { return it }
            }
        }
        return titleIdIn(rom.name)
    }

    /**
     * The path RPCSX boots for [rom]'s game. Throws [IllegalStateException] with a
     * readable reason when the rom names no title id or RPCSX doesn't have that game.
     */
    fun rpcsxPath(rom: File): String {
        val id = titleId(rom) ?: throw IllegalStateException(
            "RPCSX only starts games installed in RPCSX. Install the game there, then put a .ps3 file holding " +
                "its title id (like BLUS12345) in the rom folder.")
        if (!Root.available()) throw IllegalStateException("MainUI needs root to find games installed in RPCSX")
        val found = Root.exec(findScript(id), 20).out.lines().map { it.trim() }.firstOrNull { it.startsWith("/") }
            ?: throw IllegalStateException("$id is not installed in RPCSX. Install it in RPCSX first.")
        return toRpcsxView(found)
    }

    /** Shell script that prints the folder holding title [id] (lower path, readable by root). */
    fun findScript(id: String): String {
        val q = Root::q
        val bases = listOf("/data/media/0/Android/data/$RPCSX_PKG/files/config", "$RPCSX_ROOT/config")
        return bases.joinToString("; ") { b ->
            "[ -d ${q("$b/dev_hdd0/game/$id")} ] && echo ${q("$b/dev_hdd0/game/$id")}; " +
                "for d in ${q("$b/games")}/*; do " +
                "grep -qs ${q(id)} \"\$d/PS3_GAME/PARAM.SFO\" \"\$d/PARAM.SFO\" && echo \"\$d\"; done"
        } + "; true"
    }

    /** /data/media/0/... -> /storage/emulated/0/..., the path RPCSX itself uses. */
    fun toRpcsxView(path: String): String =
        if (path.startsWith("/data/media/0/")) "/storage/emulated/0/" + path.removePrefix("/data/media/0/") else path
}
