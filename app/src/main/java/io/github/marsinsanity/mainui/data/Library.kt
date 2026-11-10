package io.github.marsinsanity.mainui.data

import io.github.marsinsanity.mainui.core.Storage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * User state that has to survive reinstalls: recents, favorites, Android game
 * list, hidden apps and per system / per game emulator choices. Stored as JSON
 * in <storage>/MainUI/config so it travels with the SD card, like Onion.
 */
class Library(private val storage: Storage) {

    data class Entry(
        val systemId: String,
        val path: String?,
        val pkg: String?,
        val name: String,
        var emu: String? = null,
        var lastPlayed: Long = 0,
        var playSeconds: Long = 0,
        var screenshot: String? = null,
    ) {
        val key get() = if (pkg != null) "android:$pkg" else "$systemId:$path"
        fun toGame() = Game(systemId, path?.let { File(it) }, name, pkg = pkg)

        fun json() = JSONObject().apply {
            put("system", systemId); put("path", path); put("pkg", pkg); put("name", name)
            put("emu", emu); put("lastPlayed", lastPlayed); put("playSeconds", playSeconds); put("screenshot", screenshot)
        }

        companion object {
            fun from(o: JSONObject) = Entry(
                o.getString("system"), o.optStringOrNull("path"), o.optStringOrNull("pkg"), o.getString("name"),
                o.optStringOrNull("emu"), o.optLong("lastPlayed"), o.optLong("playSeconds"), o.optStringOrNull("screenshot"),
            )

            fun of(g: Game) = Entry(g.systemId, g.file?.absolutePath, g.pkg, g.name)
        }
    }

    private val lock = Any()
    private val recents = mutableListOf<Entry>()
    private val favorites = mutableListOf<Entry>()
    private val androidGames = linkedSetOf<String>()
    private val hiddenApps = linkedSetOf<String>()
    /** Games hidden with Hide ROM, and known BIOS sets shown again with Unhide ROM (game keys). */
    private val hiddenGames = linkedSetOf<String>()
    private val shownBios = linkedSetOf<String>()
    private val coreChoices = HashMap<String, String>()
    private val playTime = HashMap<String, Long>()

    /**
     * The config folder the lists were read from. Everything is written back there, never to a
     * folder the data root moved to since (an SD card mounted after MainUI started): that one is
     * read first ([current]), so its lists are never overwritten with these.
     */
    @Volatile private var loadedDir: File? = null

    private fun f(name: String) = File(loadedDir ?: storage.configDir, name)

    /** Reloads when the data root has moved to another folder since [load]. Call holding [lock]. */
    private fun current() {
        val dir = storage.configDir
        if (dir.absolutePath != loadedDir?.absolutePath) load()
    }

    fun load() = synchronized(lock) {
        loadedDir = storage.configDir
        recents.clear(); favorites.clear(); androidGames.clear(); hiddenApps.clear(); coreChoices.clear(); playTime.clear()
        hiddenGames.clear(); shownBios.clear()
        readObject("hidden_games.json")?.let { o ->
            o.optJSONArray("hidden")?.let { a -> for (i in 0 until a.length()) hiddenGames += a.getString(i) }
            o.optJSONArray("shown_bios")?.let { a -> for (i in 0 until a.length()) shownBios += a.getString(i) }
        }
        readArray("recents.json")?.forEachObj { recents += Entry.from(it) }
        readArray("favorites.json")?.forEachObj { favorites += Entry.from(it) }
        readArray("android_games.json")?.let { a -> for (i in 0 until a.length()) androidGames += a.getString(i) }
        readArray("hidden_apps.json")?.let { a -> for (i in 0 until a.length()) hiddenApps += a.getString(i) }
        readObject("cores.json")?.let { o -> o.keys().forEach { k -> coreChoices[k] = o.getString(k) } }
        readObject("playtime.json")?.let { o -> o.keys().forEach { k -> playTime[k] = o.getLong(k) } }
    }

    private fun readArray(name: String): JSONArray? = runCatching { JSONArray(f(name).readText()) }.getOrNull()
    private fun readObject(name: String): JSONObject? = runCatching { JSONObject(f(name).readText()) }.getOrNull()

    private fun write(name: String, content: String) {
        runCatching {
            val tmp = f("$name.tmp"); tmp.writeText(content)
            if (!tmp.renameTo(f(name))) { f(name).writeText(content); tmp.delete() }
        }
    }

    private fun saveRecents() = write("recents.json", JSONArray(recents.map { it.json() }).toString(1))
    private fun saveFavorites() = write("favorites.json", JSONArray(favorites.map { it.json() }).toString(1))
    private fun saveAndroid() = write("android_games.json", JSONArray(androidGames.toList()).toString(1))
    private fun saveHidden() = write("hidden_apps.json", JSONArray(hiddenApps.toList()).toString(1))
    private fun saveHiddenGames() = write("hidden_games.json",
        JSONObject().put("hidden", JSONArray(hiddenGames.toList())).put("shown_bios", JSONArray(shownBios.toList())).toString(1))
    private fun saveCores() = write("cores.json", JSONObject(coreChoices as Map<*, *>).toString(1))
    private fun savePlayTime() = write("playtime.json", JSONObject(playTime as Map<*, *>).toString(1))

    // ---- recents -------------------------------------------------------------------
    fun recents(): List<Entry> = synchronized(lock) { current(); recents.toList() }

    fun recentFor(key: String): Entry? = synchronized(lock) { current(); recents.firstOrNull { it.key == key } }

    fun addRecent(g: Game, emu: String?) = synchronized(lock) { current();
        val old = recents.firstOrNull { it.key == g.key }
        recents.removeAll { it.key == g.key }
        val e = Entry.of(g).also {
            it.emu = emu; it.lastPlayed = System.currentTimeMillis()
            it.playSeconds = old?.playSeconds ?: 0; it.screenshot = old?.screenshot
        }
        recents.add(0, e)
        while (recents.size > MAX_RECENTS) {
            // Off the list: its Game Switcher screenshot goes too.
            recents.removeAt(recents.size - 1).screenshot?.let { p -> if (p.startsWith(storage.screenshotDir.absolutePath)) File(p).delete() }
        }
        saveRecents()
    }

    fun updateRecent(key: String, block: (Entry) -> Unit) = synchronized(lock) { current();
        recents.firstOrNull { it.key == key }?.let { block(it); saveRecents() }
    }

    fun removeRecent(key: String) = synchronized(lock) { current();
        val e = recents.firstOrNull { it.key == key }
        recents.removeAll { it.key == key }
        e?.screenshot?.let { p -> if (p.startsWith(storage.screenshotDir.absolutePath)) File(p).delete() }
        saveRecents()
    }

    fun addPlayTime(key: String, seconds: Long) = synchronized(lock) { current();
        if (seconds <= 0) return@synchronized
        playTime[key] = (playTime[key] ?: 0) + seconds
        savePlayTime()
    }

    fun playTime(key: String): Long = synchronized(lock) { current(); playTime[key] ?: 0 }
    fun totalPlayTime(): Long = synchronized(lock) { current(); playTime.values.sum() }

    // ---- favorites -----------------------------------------------------------------
    fun favorites(): List<Entry> = synchronized(lock) { current(); favorites.sortedBy { it.name.lowercase() } }
    fun isFavorite(key: String) = synchronized(lock) { current(); favorites.any { it.key == key } }

    /** Returns the new favorite state. */
    fun toggleFavorite(g: Game): Boolean = synchronized(lock) { current();
        val had = favorites.removeAll { it.key == g.key }
        if (!had) favorites += Entry.of(g)
        saveFavorites()
        !had
    }

    // ---- Android games & hidden apps ---------------------------------------------
    fun androidGames(): Set<String> = synchronized(lock) { current(); androidGames.toSet() }
    fun isAndroidGame(pkg: String) = synchronized(lock) { current(); pkg in androidGames }
    fun setAndroidGame(pkg: String, value: Boolean) = synchronized(lock) { current();
        if (value) androidGames += pkg else androidGames -= pkg
        saveAndroid()
    }

    fun hiddenApps(): Set<String> = synchronized(lock) { current(); hiddenApps.toSet() }
    fun isHidden(pkg: String) = synchronized(lock) { current(); pkg in hiddenApps }
    fun setHidden(pkg: String, value: Boolean) = synchronized(lock) { current();
        if (value) hiddenApps += pkg else hiddenApps -= pkg
        saveHidden()
    }

    // ---- hidden games -----------------------------------------------------------------
    /** Hidden with Hide ROM, or [bios] (a known BIOS set) and not shown again. */
    fun isGameHidden(key: String, bios: Boolean): Boolean = synchronized(lock) { current();
        key in hiddenGames || (bios && key !in shownBios)
    }

    fun setGameHidden(key: String, hide: Boolean, bios: Boolean) = synchronized(lock) { current();
        if (hide) { hiddenGames += key; shownBios -= key }
        else { hiddenGames -= key; if (bios) shownBios += key }
        saveHiddenGames()
    }

    // ---- emulator / core choice --------------------------------------------------
    fun coreForSystem(systemId: String): String? = synchronized(lock) { current(); coreChoices["system:$systemId"] }
    fun coreForGame(gameKey: String): String? = synchronized(lock) { current(); coreChoices["game:$gameKey"] }

    fun setCoreForSystem(systemId: String, ref: String?) = synchronized(lock) { current();
        if (ref == null) coreChoices.remove("system:$systemId") else coreChoices["system:$systemId"] = ref
        saveCores()
    }

    fun setCoreForGame(gameKey: String, ref: String?) = synchronized(lock) { current();
        if (ref == null) coreChoices.remove("game:$gameKey") else coreChoices["game:$gameKey"] = ref
        saveCores()
    }

    companion object {
        const val MAX_RECENTS = 50
    }
}

fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)

inline fun JSONArray.forEachObj(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) optJSONObject(i)?.let(block)
}
