package io.github.marsinsanity.mainui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import io.github.marsinsanity.mainui.core.Settings
import io.github.marsinsanity.mainui.core.Storage
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Lists, installs and applies OnionOS themes; owns the active [Theme]. */
class ThemeManager(private val ctx: Context, private val storage: Storage, private val settings: Settings) {

    /**
     * [key] is what Settings.themePath stores: "" for the bundled MainUI theme,
     * [CLASSIC] for the flat theme drawn in code, otherwise the theme folder.
     */
    data class ThemeEntry(val dir: File?, val zip: File?, val name: String, val author: String, val description: String,
                          val key: String = dir?.absolutePath ?: zip?.absolutePath ?: CLASSIC) {
        val id get() = key
    }

    /** The bundled default theme (assets/themes/MainUI), unpacked to app storage. */
    val builtinDir: File = File(ctx.filesDir, "themes/MainUI").also { extractBuiltin(it) }

    /** The other themes that come with MainUI (assets/themes/<name>), unpacked next to it. */
    private val bundled: List<String> =
        runCatching { ctx.assets.list("themes")?.filter { it != "MainUI" }?.sorted() }.getOrNull().orEmpty()

    private fun bundledDir(name: String) = File(ctx.filesDir, "themes/$name")

    init {
        // Unpacked (or brought up to date after an update) in the background; one that is needed
        // before that is unpacked there and then (see [ensureBundled]).
        if (bundled.isNotEmpty()) Thread {
            for (n in bundled) {
                val dir = bundledDir(n)
                if (runCatching { ensureBundled(dir) }.getOrDefault(false))
                    android.os.Handler(android.os.Looper.getMainLooper()).post { if (settings.themePath == dir.absolutePath) reload() }
            }
        }.start()
    }

    /** Unpacks bundled theme [dir] if it isn't, or is older than the APK's; true when it did. */
    @Synchronized private fun ensureBundled(dir: File): Boolean {
        val asset = "themes/${dir.name}"
        val bundledCfg = runCatching { ctx.assets.open("$asset/config.json").bufferedReader().use { it.readText() } }.getOrNull() ?: return false
        if (runCatching { File(dir, "config.json").readText() }.getOrNull() == bundledCfg) return false
        copyFresh(dir, asset)
        return true
    }

    private fun isBundled(dir: File?) = dir != null && dir.parentFile == builtinDir.parentFile && dir.name in bundled

    @Volatile var current: Theme = loadTheme(settings.themePath)
        private set
    private val listeners = mutableListOf<(Theme) -> Unit>()

    private var soundPool: SoundPool? = null
    private var changeSound = 0
    private var bgmPlayer: MediaPlayer? = null

    fun onThemeChanged(l: (Theme) -> Unit) { listeners += l }

    private fun loadTheme(path: String): Theme {
        // Themes that lack a system icon borrow the bundled theme's (it has one for every system).
        Theme.iconFallbackDir = File(builtinDir, "icons").takeIf { it.isDirectory }
        Theme.onionIcons = settings.onionIcons
        val dir = when {
            path == CLASSIC -> null
            path.isBlank() -> builtinDir.takeIf { it.isDirectory }
            isBundled(File(path)) -> File(path).also { runCatching { ensureBundled(it) } }.takeIf { File(it, "config.json").isFile }
                ?: builtinDir.takeIf { it.isDirectory }
            else -> File(path).takeIf { File(it, "config.json").isFile || File(it, "skin").isDirectory } ?: builtinDir.takeIf { it.isDirectory }
        }
        return Theme.load(ctx, dir, storage.themeOverrideDir.takeIf { it.isDirectory }, listOf(dir ?: storage.fontDir, storage.fontDir))
    }

    /**
     * Copies the bundled theme out of the APK. Its config.json carries a content
     * stamp, so this only happens when the theme itself changed: on the first
     * start (synchronously, so the first screen has it) or after an update that
     * changed it (in the background; the previous copy is used until it's done).
     */
    private fun extractBuiltin(dir: File) {
        val bundled = runCatching { ctx.assets.open("themes/MainUI/config.json").bufferedReader().use { it.readText() } }.getOrNull() ?: return
        val cfg = File(dir, "config.json")
        val current = runCatching { cfg.readText() }.getOrNull()
        if (current == bundled) return
        if (current == null) { runCatching { copyFresh(dir, "themes/MainUI") }; return }
        Thread {
            if (runCatching { copyFresh(dir, "themes/MainUI") }.isSuccess) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { if (settings.themePath.isBlank()) reload() }
            }
        }.start()
    }

    /** Unpacks [asset] into a temporary folder, then swaps it in as [dir]. */
    private fun copyFresh(dir: File, asset: String) {
        val tmp = File(dir.parentFile, dir.name + ".new")
        tmp.deleteRecursively()
        copyAsset(asset, tmp)
        val old = File(dir.parentFile, dir.name + ".old")
        old.deleteRecursively()
        if (dir.exists() && !dir.renameTo(old)) dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw java.io.IOException("Could not install the bundled theme")
        old.deleteRecursively()
    }

    private fun copyAsset(path: String, dest: File) {
        val children = ctx.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (c in children) copyAsset("$path/$c", File(dest, c))
        }
    }

    fun reload() {
        val old = current
        current = loadTheme(settings.themePath)
        // (Glyphs are drawn in the theme's accent: a card mounted / ejected can change it.)
        if (current.accent != old.accent) io.github.marsinsanity.mainui.ui.Glyphs.clear()
        old.release()
        reloadSounds()
        listeners.forEach { it(current) }
    }

    fun apply(entry: ThemeEntry): String? {
        if (isBundled(entry.dir)) runCatching { ensureBundled(entry.dir!!) }.onFailure { return "Could not unpack ${entry.name}" }
        settings.themePath = if (entry.zip != null) {
            (install(entry.zip) ?: return "Could not extract ${entry.zip.name}").absolutePath
        } else entry.key
        reload()
        return null
    }

    /** Key of the active theme, comparable with [ThemeEntry.key]. */
    val currentKey: String get() = settings.themePath.let { p ->
        if (p.isBlank() || p == CLASSIC || File(p).isDirectory) p else ""
    }

    /** All themes: the built-in ones first, then folders and .zip files in every Themes directory. */
    fun list(): List<ThemeEntry> {
        val out = mutableListOf(
            ThemeEntry(builtinDir, null, "MainUI", "MainUI", "Built-in blue theme with an icon for every system", key = ""),
            ThemeEntry(null, null, "MainUI Classic", "MainUI", "The original flat theme, drawn in code", key = CLASSIC),
        )
        for (n in bundled) {
            val o = runCatching { JSONObject(ctx.assets.open("themes/$n/config.json").bufferedReader().use { it.readText() }) }.getOrNull()
            out += ThemeEntry(bundledDir(n), null, o?.optString("name")?.ifEmpty { null } ?: n, o?.optString("author") ?: "MainUI",
                o?.optString("description") ?: "")
        }
        val seen = HashSet<String>()
        for (root in storage.themeDirs()) {
            val children = root.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
            for (f in children) {
                if (f.isDirectory) {
                    for (d in themeFolders(f)) {
                        val o = runCatching { JSONObject(File(d, "config.json").readText()) }.getOrNull()
                        val e = ThemeEntry(d, null, o?.optString("name")?.ifEmpty { null } ?: d.name,
                            o?.optString("author") ?: "", o?.optString("description") ?: "")
                        if (seen.add((if (d == f) d.name else "${f.name}/${d.name}").lowercase())) out += e
                    }
                } else if (f.extension.equals("zip", true)) {
                    val n = f.nameWithoutExtension
                    if (seen.add(n.lowercase())) out += ThemeEntry(null, f, n, "", "Not installed yet (.zip)")
                }
            }
        }
        return out
    }

    /** Preview for the theme switcher (preview.png, or background as fallback, turned upright). */
    fun preview(entry: ThemeEntry, maxW: Int = 640): Bitmap? {
        // Made smaller when it's big: one bigger than the image cache was decoded again every frame.
        fun scaled(w: Int, h: Int) = BitmapFactory.Options().apply { inSampleSize = sampleSize(w, h, maxW, 16L shl 20) }
        entry.dir?.let { d ->
            for (rel in listOf("preview.png", "skin/background.png")) {
                val f = File(d, rel)
                if (!f.isFile) continue
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, b)
                val bmp = BitmapFactory.decodeFile(f.absolutePath, scaled(b.outWidth, b.outHeight))
                if (rel == "preview.png" || bmp == null) return bmp
                // (themes store background.png upside down, see Gfx.background)
                return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(180f) }, true)
                    .also { if (it !== bmp) bmp.recycle() }
            }
            // A bundled theme not unpacked yet: its picture straight from the APK.
            if (isBundled(d)) runCatching {
                return ctx.assets.open("themes/${d.name}/preview.png").use { BitmapFactory.decodeStream(it) }
            }
        }
        entry.zip?.let { z ->
            runCatching {
                readZip(z) { zf ->
                    val e = zf.entries().asSequence().firstOrNull { it.name.endsWith("preview.png", true) && it.name.count { c -> c == '/' } <= 1 }
                        ?: return null
                    val bytes = zf.getInputStream(e).use { it.readBytes() }
                    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, scaled(b.outWidth, b.outHeight))
                }
            }
        }
        return null
    }

    /**
     * Extracts an Onion theme .zip next to itself: the theme at its root, in a top folder, or each
     * theme of a pack ("CyberOnion (2-pack)", also with the pack's own folder on top).
     */
    fun install(zip: File): File? {
        val themesDir = zip.parentFile ?: return null
        val created = mutableListOf<File>()
        val dirs = runCatching {
            readZip(zip) { zf ->
                val entries = zf.entries().toList().filter { !it.name.startsWith("__MACOSX/") }
                val names = HashSet<String>()
                themeRoots(entries.map { it.name }).mapNotNull { root ->
                    val name = if (root.isEmpty()) zip.nameWithoutExtension else root.substringAfterLast('/')
                    // (two themes of the same name are not merged into one folder: the first one is kept)
                    if (!names.add(name.lowercase())) return@mapNotNull null
                    val target = File(themesDir, name)
                    if (!target.exists()) created += target
                    extract(zf, entries, themesDir, root, name)
                }
            }
        }.getOrNull()
        // Only usable themes count (and free the zip); anything else keeps the zip, fails and leaves nothing behind.
        if (dirs.isNullOrEmpty() || !dirs.all { isTheme(it) }) { created.forEach { it.deleteRecursively() }; return null }
        // The pack's own name has no folder: remembered, so the Theme Downloader knows it is installed.
        if (dirs.size > 1) runCatching { packMarker(themesDir, zip.nameWithoutExtension).writeText(dirs.joinToString("\n") { it.name }) }
        zip.delete()
        return dirs.first()
    }

    /** Opens [zip], with the zip format's old CP437 names when they aren't UTF-8. */
    private inline fun <T> readZip(zip: File, block: (ZipFile) -> T): T =
        try { ZipFile(zip).use(block) } catch (e: IllegalArgumentException) {
            val cp437 = runCatching { java.nio.charset.Charset.forName("IBM437") }.getOrDefault(Charsets.ISO_8859_1)
            ZipFile(zip, cp437).use(block)
        }

    /** Lists the theme folders a pack of several themes ([name]: the pack's) was installed as. */
    fun packMarker(themesDir: File, name: String) = File(themesDir, ".$name.pack")

    /**
     * Unpacks the entries under [root] ("" = all of them) into [dir]/[name]. Null for a [name] that
     * isn't a plain folder name.
     */
    private fun extract(zf: ZipFile, entries: List<java.util.zip.ZipEntry>, dir: File, root: String, name: String): File? {
        // The theme folder must be a plain name: never "..", ".", or a path.
        if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) return null
        val target = File(dir, name)
        val inside = target.canonicalPath + File.separator
        val prefix = if (root.isEmpty()) "" else "$root/"
        for (e in entries) {
            if (!e.name.startsWith(prefix)) continue
            val out = File(target, e.name.substring(prefix.length))
            // Zip slip: everything must land inside the theme's own folder (a prefix test of the
            // bare path would let "Blue/../Blueprint/x" into a sibling theme "Blueprint").
            if (!(out.canonicalPath + if (e.isDirectory) File.separator else "").startsWith(inside)) continue
            if (e.isDirectory) out.mkdirs() else {
                out.parentFile?.mkdirs()
                zf.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        return target
    }

    // ---- sounds ------------------------------------------------------------------------
    fun reloadSounds() {
        soundPool?.release(); soundPool = null; changeSound = 0
        bgmPlayer?.release(); bgmPlayer = null
        if (settings.uiSounds) {
            val sp = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()).build()
            val f = current.soundChange
            changeSound = if (f != null) sp.load(f.absolutePath, 1) else runCatching {
                ctx.assets.openFd("sound/change.wav").use { sp.load(it, 1) }
            }.getOrDefault(0)
            soundPool = sp
        }
        if (settings.bgm) {
            current.soundBgm?.let { f ->
                bgmPlayer = runCatching {
                    // Playing only while MainUI's screen is shown (a reload behind a game stays quiet).
                    MediaPlayer().apply { setDataSource(f.absolutePath); isLooping = true; setVolume(0.4f, 0.4f); prepare(); if (shown) start() }
                }.getOrNull()
            }
        }
    }

    /**
     * True once: the theme just applied has music and it is off (most OnionOS themes ship
     * sound/bgm.mp3), so the caller offers to turn it on.
     */
    fun takeBgmOffer(): Boolean {
        if (settings.bgm || settings.bgmOffered || current.soundBgm == null) return false
        settings.bgmOffered = true
        return true
    }

    fun enableBgm() { settings.bgm = true; reloadSounds() }

    fun click() {
        if (changeSound != 0) soundPool?.play(changeSound, 0.6f, 0.6f, 0, 0, 1f)
    }

    /** MainUI's screen is shown (between [resumeBgm] and [pauseBgm]): only then is there music. */
    @Volatile private var shown = false

    fun pauseBgm() { shown = false; runCatching { bgmPlayer?.takeIf { it.isPlaying }?.pause() } }
    fun resumeBgm() { shown = true; runCatching { bgmPlayer?.start() } }

    companion object {
        const val CLASSIC = "classic"
    }
}

/** A folder MainUI can use as a theme: OnionOS's config.json, or at least skin/. */
internal fun isTheme(dir: File) = File(dir, "config.json").isFile || File(dir, "skin").isDirectory

/**
 * The themes in folder [f] of a Themes folder: [f] itself, or for a pack folder copied as it is
 * ("Material (6-pack)"), each theme in it with a config.json.
 */
internal fun themeFolders(f: File): List<File> =
    if (isTheme(f)) listOf(f)
    else f.listFiles()?.filter { it.isDirectory && File(it, "config.json").isFile }?.sortedBy { it.name.lowercase() }.orEmpty()

/**
 * The theme folders in a zip with entries [names], as paths without the trailing '/' ("" = the zip's
 * root): each folder with a config.json or a skin/, but not one inside another (Milk's v1/ and v2/
 * are part of Milk).
 */
internal fun themeRoots(names: List<String>): List<String> {
    val roots = LinkedHashSet<String>()
    for (n in names) {
        val parts = n.split('/')
        if (parts.last() == "config.json") roots += parts.dropLast(1).joinToString("/")
        val skin = parts.indexOf("skin")
        if (skin >= 0 && skin < parts.size - 1) roots += parts.take(skin).joinToString("/")
    }
    return roots.filter { r -> roots.none { o -> o != r && (o.isEmpty() || r.startsWith("$o/")) } }
}
