package io.github.marsinsanity.mainui.core

import android.util.Log

/**
 * The folders an emulator was given access to through Android's folder picker (Storage Access
 * Framework), e.g. "primary:roms/psx" when DuckStation was pointed at roms/psx. A game's
 * %ROMSAF% link has to go through one of them: a document link under any other folder (the whole
 * storage, or the ROMs root when only roms/psx was granted) is refused by Android, and the
 * emulator then can't open the game.
 *
 * Read with root from Android's URI grant table (`dumpsys activity permissions`, and
 * /data/system/urigrants.xml where it is plain XML). Without root nothing is known and
 * [Storage.safUri] falls back to the ROMs folder, like ES-DE.
 */
object SafGrants {
    private const val TAG = "MainUI.Saf"
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private val cache = HashMap<String, Pair<Long, List<String>>>()

    /** Tree document ids ("primary:roms/psx", "1234-ABCD:Roms") granted to [pkg] (user id [uid]). */
    @Synchronized
    fun trees(pkg: String, uid: Int): List<String> {
        val now = System.currentTimeMillis()
        cache[pkg]?.let { (at, v) -> if (now - at < 60_000) return v }
        if (!Root.knownAvailable() && !Root.available()) return emptyList()
        val dump = Root.exec("dumpsys activity permissions", 15).out
        val xml = Root.exec("f=/data/system/urigrants.xml; head -c 5 \$f | grep -q '<' && cat \$f", 15).out
        val found = (fromDumpsys(dump, uid) + fromXml(xml, pkg)).distinct()
        Log.d(TAG, "$pkg folders: $found")
        cache[pkg] = now to found
        return found
    }

    /** The tree id of a folder grant; encoded ids have no raw '/', so a document grant's "/document/..." is left out. */
    private val treeRe = Regex("""content://$AUTHORITY/tree/([^\s}\]"/]+)""")

    /**
     * `dumpsys activity permissions`: "* UID 10123 holds:" then one
     * "UriPermission{1a2b3c content://com.android.externalstorage.documents/tree/primary%3Aroms [user 0] [prefix]}" per line.
     */
    fun fromDumpsys(dump: String, uid: Int): List<String> {
        val out = ArrayList<String>()
        var mine = false
        for (line in dump.lineSequence()) {
            Regex("""\*\s*UID (\d+) holds""").find(line)?.let { mine = it.groupValues[1].toIntOrNull() == uid; return@let }
            if (!mine) continue
            treeRe.findAll(line).forEach { out += decode(it.groupValues[1]) }
        }
        return out
    }

    /** urigrants.xml (text form): <uri-grant ... targetPkg="pkg" uri="content://.../tree/primary%3Aroms" .../>. */
    fun fromXml(xml: String, pkg: String): List<String> =
        Regex("""<uri-grant\b[^>]*>""").findAll(xml).mapNotNull { m ->
            val tag = m.value
            if (!tag.contains("targetPkg=\"$pkg\"")) null
            else treeRe.find(tag)?.groupValues?.get(1)?.let(::decode)
        }.toList()

    private fun decode(s: String) = percentDecode(s).trimEnd('/')

    /**
     * The document link for [docId] ("primary:roms/psx/Game.chd") through the deepest granted
     * folder that holds it, or through [fallbackTree] when none does.
     */
    fun documentUri(docId: String, granted: List<String>, fallbackTree: String): String {
        val tree = granted.filter { t -> contains(t, docId) }.maxByOrNull { it.length } ?: fallbackTree
        return "content://$AUTHORITY/tree/${percentEncode(tree)}/document/${percentEncode(docId)}"
    }

    /** Like android.net.Uri.encode: everything but letters, digits and _-!.~'()* as %XX (UTF-8). */
    fun percentEncode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "_-!.~'()*") append(c)
            else append('%').append("%02X".format(b.toInt() and 0xff))
        }
    }

    fun percentDecode(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && s[i + 1].isHex() && s[i + 2].isHex()) {
                out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
            } else { out.write(c.toString().toByteArray(Charsets.UTF_8)); i++ }
        }
        return out.toString("UTF-8")
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** True when folder [tree] ("primary:roms", or a whole volume "primary:") holds [docId]. */
    fun contains(tree: String, docId: String): Boolean =
        docId == tree || docId.startsWith(if (tree.endsWith(":")) tree else "$tree/")
}
