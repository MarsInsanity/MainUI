package io.github.marsinsanity.mainui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    data class ThemeEntry(val dir: File?, val zip: File?, val name: String, val author: String, val description: String) {
        val id get() = dir?.absolutePath ?: zip?.absolutePath ?: "builtin"
    }

    @Volatile var current: Theme = loadTheme(settings.themePath)
        private set
    private val listeners = mutableListOf<(Theme) -> Unit>()

    private var soundPool: SoundPool? = null
    private var changeSound = 0
    private var bgmPlayer: MediaPlayer? = null

    fun onThemeChanged(l: (Theme) -> Unit) { listeners += l }

    private fun loadTheme(path: String): Theme {
        val dir = path.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { File(it, "config.json").isFile || File(it, "skin").isDirectory }
        return Theme.load(ctx, dir, storage.themeOverrideDir.takeIf { it.isDirectory }, listOf(dir ?: storage.fontDir, storage.fontDir))
    }

    fun reload() {
        val old = current
        current = loadTheme(settings.themePath)
        old.release()
        reloadSounds()
        listeners.forEach { it(current) }
    }

    fun apply(entry: ThemeEntry): String? {
        val dir = entry.dir ?: entry.zip?.let { install(it) ?: return "Could not extract ${it.name}" }
        settings.themePath = dir?.absolutePath ?: ""
        reload()
        return null
    }

    /** All themes: built-in first, then folders and .zip files in every Themes directory. */
    fun list(): List<ThemeEntry> {
        val out = mutableListOf(ThemeEntry(null, null, "MainUI Default", "MainUI", "Built-in theme"))
        val seen = HashSet<String>()
        for (root in storage.themeDirs()) {
            val children = root.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
            for (f in children) {
                if (f.isDirectory) {
                    val cfg = File(f, "config.json")
                    if (!cfg.isFile && !File(f, "skin").isDirectory) continue
                    val o = runCatching { JSONObject(cfg.readText()) }.getOrNull()
                    val e = ThemeEntry(f, null, o?.optString("name")?.ifEmpty { null } ?: f.name,
                        o?.optString("author") ?: "", o?.optString("description") ?: "")
                    if (seen.add(f.name.lowercase())) out += e
                } else if (f.extension.equals("zip", true)) {
                    val n = f.nameWithoutExtension
                    if (seen.add(n.lowercase())) out += ThemeEntry(null, f, n, "", "Not installed yet (.zip)")
                }
            }
        }
        return out
    }

    /** Preview for the theme switcher (preview.png, or background as fallback). */
    fun preview(entry: ThemeEntry, maxW: Int = 640): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
        entry.dir?.let { d ->
            for (rel in listOf("preview.png", "skin/background.png")) {
                val f = File(d, rel)
                if (f.isFile) return BitmapFactory.decodeFile(f.absolutePath, opts)
            }
        }
        entry.zip?.let { z ->
            runCatching {
                ZipFile(z).use { zf ->
                    val e = zf.entries().asSequence().firstOrNull { it.name.endsWith("preview.png", true) && it.name.count { c -> c == '/' } <= 1 }
                        ?: return null
                    return zf.getInputStream(e).use { BitmapFactory.decodeStream(it) }
                }
            }
        }
        return null
    }

    /** Extracts an Onion theme .zip next to itself. Handles zips with or without a top folder. */
    fun install(zip: File): File? = runCatching {
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            val tops = entries.map { it.name.substringBefore('/') }.toSet()
            val hasTopFolder = tops.size == 1 && entries.any { it.name.contains('/') }
            val target = if (hasTopFolder) File(zip.parentFile, tops.first()) else File(zip.parentFile, zip.nameWithoutExtension)
            val base = if (hasTopFolder) zip.parentFile!! else target
            for (e in entries) {
                val out = File(base, e.name)
                if (!out.canonicalPath.startsWith(base.canonicalPath)) continue // zip slip
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
            target
        }
    }.getOrNull()?.also { zip.delete() }

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
                    MediaPlayer().apply { setDataSource(f.absolutePath); isLooping = true; setVolume(0.4f, 0.4f); prepare(); start() }
                }.getOrNull()
            }
        }
    }

    fun click() {
        if (changeSound != 0) soundPool?.play(changeSound, 0.6f, 0.6f, 0, 0, 1f)
    }

    fun pauseBgm() { runCatching { bgmPlayer?.takeIf { it.isPlaying }?.pause() } }
    fun resumeBgm() { runCatching { bgmPlayer?.start() } }
}
