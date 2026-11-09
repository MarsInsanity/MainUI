package io.github.marsinsanity.mainui

import io.github.marsinsanity.mainui.data.GameListXml
import io.github.marsinsanity.mainui.data.GameRepository
import io.github.marsinsanity.mainui.scraper.Hashes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class DataTest {
    @Test fun cleanNameStripsRegionAndDumpTags() {
        assertEquals("Super Mario World", GameRepository.cleanName("Super Mario World (USA) [!]"))
        assertEquals("Sonic", GameRepository.cleanName("Sonic (Rev 1)(Europe)"))
        // Never returns an empty name
        assertEquals("(Proto)", GameRepository.cleanName("(Proto)"))
    }

    @Test fun gamelistPathsNormalise() {
        assertEquals("sub/game.zip", GameListXml.normalize("./Sub/Game.zip"))
        assertEquals("game.zip", GameListXml.normalize(" ././game.zip "))
    }

    @Test fun sanitizeFixesBareAmpersandsOnly() {
        val s = GameListXml.sanitize("<name>Tom & Jerry &amp; Friends &#38; &lt;3</name>")
        assertEquals("<name>Tom &amp; Jerry &amp; Friends &#38; &lt;3</name>", s)
    }

    @Test fun escapeRoundTripsSpecialCharacters() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot;", GameListXml.escape("a & b <c> \"d\""))
    }

    @Test fun hashesMatchKnownValues() {
        val f = File.createTempFile("rom", ".bin")
        f.writeText("hello")
        val h = Hashes.of(f)!!
        assertEquals("3610a686", h.crc)
        assertEquals("5d41402abc4b2a76b9719d911017c592", h.md5)
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", h.sha1)
        f.delete()
        assertNull(Hashes.of(File("/does/not/exist")))
    }
}

class EmulatorConfigTest {
    @Test fun stateSupportParsesEveryMode() {
        assertEquals(io.github.marsinsanity.mainui.emu.StateSupport.HOTKEY, io.github.marsinsanity.mainui.emu.StateSupport.parse("hotkey"))
        assertEquals(io.github.marsinsanity.mainui.emu.StateSupport.NONE, io.github.marsinsanity.mainui.emu.StateSupport.parse("none"))
        assertEquals(io.github.marsinsanity.mainui.emu.StateSupport.API, io.github.marsinsanity.mainui.emu.StateSupport.parse("API"))
        // anything unknown falls back to the emulator's own menu
        assertEquals(io.github.marsinsanity.mainui.emu.StateSupport.MENU, io.github.marsinsanity.mainui.emu.StateSupport.parse(null))
    }

    @Test fun hotkeyLabelsAreReadable() {
        assertEquals("F2", io.github.marsinsanity.mainui.emu.StandaloneController.keyLabel("keys:KEYCODE_F2"))
        assertEquals("SHIFT_LEFT+F7", io.github.marsinsanity.mainui.emu.StandaloneController.keyLabel("keys:KEYCODE_SHIFT_LEFT+KEYCODE_F7"))
    }
}

class CoresTest {
    @Test
    fun readsRetroArchConfigValues() {
        val cfg = """
            libretro_directory = "/data/user/0/com.retroarch.aarch64/cores"
            libretro_info_path = ""
            savefile_directory = "default"
        """.trimIndent()
        assertEquals("/data/user/0/com.retroarch.aarch64/cores",
            io.github.marsinsanity.mainui.emu.RetroArchCores.cfgValue(cfg, "libretro_directory"))
        // Empty and "default" mean RetroArch's built-in location.
        assertEquals(null, io.github.marsinsanity.mainui.emu.RetroArchCores.cfgValue(cfg, "libretro_info_path"))
        assertEquals(null, io.github.marsinsanity.mainui.emu.RetroArchCores.cfgValue(cfg, "savefile_directory"))
        assertEquals(null, io.github.marsinsanity.mainui.emu.RetroArchCores.cfgValue(cfg, "missing_key"))
    }

    @Test
    fun coreNamesFromPaths() {
        val c = io.github.marsinsanity.mainui.emu.RetroArchCores
        assertEquals("mgba", c.coreName("/data/data/com.retroarch/cores/mgba_libretro_android.so"))
        assertEquals("yabadabadoo", c.coreName("lib/arm64-v8a/yabadabadoo_libretro_android.so"))
        assertEquals("https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mgba_libretro_android.so.zip",
            c.downloadUrl("arm64-v8a", "mgba"))
    }

    @Test
    fun formatsSizes() {
        assertEquals("1 KB", io.github.marsinsanity.mainui.store.formatBytes(10))
        assertEquals("20 KB", io.github.marsinsanity.mainui.store.formatBytes(20 * 1024))
        assertEquals("2.5 MB", io.github.marsinsanity.mainui.store.formatBytes(2_621_440))
    }
}

class EmulatorStoreTest {
    private val es = io.github.marsinsanity.mainui.store.EmulatorStore

    @Test
    fun newestVersionComparesNumerically() {
        // retroarch.com lists versions as text: 1.9.9 comes after 1.22.2 alphabetically.
        assertEquals("1.22.2", es.newestVersion(listOf("1.9.9", "1.22.2", "1.10.0", "1.22.1")))
        assertEquals(null, es.newestVersion(emptyList()))
    }

    @Test
    fun picksTheRightApkForTheCpu() {
        val names = listOf("app-armeabi-v7a-release.apk", "app-arm64-v8a-release.apk", "app-x86_64-release.apk", "app-universal-release.apk")
        assertEquals("app-arm64-v8a-release.apk", names.maxByOrNull { es.scoreApk(it, "arm64-v8a") })
        assertEquals("app-x86_64-release.apk", names.maxByOrNull { es.scoreApk(it, "x86_64") })
        // Store flavours and debug builds lose to the plain build.
        val flavours = listOf("azahar-2125.0-android-googleplay.apk", "azahar-2125.0-android-vanilla.apk", "app-debug.apk")
        assertEquals("azahar-2125.0-android-vanilla.apk", flavours.maxByOrNull { es.scoreApk(it, "arm64-v8a") })
    }
}

class RootPathsTest {
    @Test
    fun lowerPathsReachTheBackingStorage() {
        val r = io.github.marsinsanity.mainui.core.Root
        assertEquals(listOf("/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg",
            "/data/media/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg"),
            r.lowerPaths("/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg"))
        assertEquals(listOf("/storage/1A2B-3C4D/roms/gba", "/mnt/media_rw/1A2B-3C4D/roms/gba"), r.lowerPaths("/storage/1A2B-3C4D/roms/gba"))
        assertEquals(listOf("/data/data/com.retroarch/cores/x.so"), r.lowerPaths("/data/data/com.retroarch/cores/x.so"))
    }
}

class RetroArchConfigTest {
    private val c = io.github.marsinsanity.mainui.emu.RetroArchCores

    private fun cfg(n: Int) = (0 until n).joinToString("\n") { "setting_$it = \"$it\"" } + "\npause_nonactive = \"false\"\n"

    @Test
    fun replacesExistingAndAppendsMissingKeys() {
        val out = c.mergeConfig(cfg(300), mapOf("pause_nonactive" to "\"true\"", "autosave_interval" to "\"10\""))!!
        assertEquals(302, out.lines().count { it.isNotBlank() })
        assertEquals(true, out.contains("pause_nonactive = \"true\"\n"))
        assertEquals(false, out.contains("pause_nonactive = \"false\""))
        assertEquals(true, out.endsWith("autosave_interval = \"10\"\n"))
        assertEquals(true, out.contains("setting_299 = \"299\""))
    }

    @Test
    fun refusesSomethingThatIsNotAWholeConfig() {
        assertNull(c.mergeConfig("", mapOf("a" to "b")))
        assertNull(c.mergeConfig(cfg(20), mapOf("a" to "b")))
    }
}

class BuildbotListTest {
    private val c = io.github.marsinsanity.mainui.emu.RetroArchCores

    @Test
    fun readsCoresAndSizesFromTheFolderPage() {
        val html = """<table><tr><td class="fb-i"><img src="/_h5ai/public/images/fallback/folder-parent.png"/></td><td class="fb-n"><a href="..">Parent Directory</a></td><td class="fb-d"></td><td class="fb-s"></td></tr>""" +
            """<tr><td class="fb-i"><img src="/_h5ai/public/images/fallback/file.png" alt="file"/></td><td class="fb-n"><a href="/nightly/android/latest/arm64-v8a/mgba_libretro_android.so.zip">mgba_libretro_android.so.zip</a></td><td class="fb-d">2026-11-07 07:32</td><td class="fb-s">419 KB</td></tr>""" +
            """<tr><td class="fb-i"><img src="/_h5ai/public/images/fallback/file.png" alt="file"/></td><td class="fb-n"><a href="/nightly/android/latest/arm64-v8a/mame2016_libretro_android.so.zip">mame2016_libretro_android.so.zip</a></td><td class="fb-d">2026-11-07 09:32</td><td class="fb-s">40.3 MB</td></tr></table>"""
        val rows = c.parseListing(html)
        assertEquals(listOf("mgba", "mame2016"), rows.map { it.core })
        assertEquals(419L * 1024, rows[0].bytes)
        assertEquals((40.3 * 1048576).toLong(), rows[1].bytes)
        assertEquals("2026-11-07", rows[0].date)
    }

    @Test
    fun readsThePlainIndexToo() {
        val rows = c.parseIndexExtended("2026-10-31 7f42945f squirreljme_libretro_android.so.zip\n2026-11-08 657995de mednafen_psx_hw_libretro_android.so.zip\n\n")
        assertEquals(listOf("squirreljme", "mednafen_psx_hw"), rows.map { it.core })
        assertEquals(0L, rows[0].bytes)
    }
}

class CoreFailureSummaryTest {
    private val sum = io.github.marsinsanity.mainui.ui.scenes.CoreInstall::failureSummary

    @Test fun sameReasonIsShownOnce() {
        val r = sum(listOf("pokemini", "fbneo", "mgba", "snes9x", "a5200").map {
            it to "Could not copy ${it}_libretro_android.so: stat: '/data/data/com.retroarch': No such file or directory"
        })
        assertEquals("5 failed (pokemini, fbneo, mgba, snes9x, ...):\n" +
            "Could not copy pokemini_libretro_android.so: stat: '/data/data/com.retroarch': No such file or directory", r)
    }

    @Test fun differentReasonsAreListed() {
        val r = sum(listOf("mgba" to "HTTP 404", "fbneo" to "No space left on device"))
        assertEquals("2 failed:\nmgba: HTTP 404\nfbneo: No space left on device", r)
    }
}
