package io.github.marsinsanity.mainui

import io.github.marsinsanity.mainui.data.GameListXml
import io.github.marsinsanity.mainui.data.GameRepository
import io.github.marsinsanity.mainui.scraper.Hashes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("Shift+F7", io.github.marsinsanity.mainui.emu.StandaloneController.keyLabel("keys:KEYCODE_SHIFT_LEFT+KEYCODE_F7"))
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

    @Test
    fun picksTheArmsx3BuildForTheAndroidVersionAndCpu() {
        // ARMSX3 1.6.7's release assets.
        val names = listOf("ARMSX3-1.6.7-a11-armv8.2-sdk30.apk", "ARMSX3-1.6.7-a13-armv8.2-sdk33.apk",
            "ARMSX3-1.6.7-a15-armv8.2-sdk35.apk", "ARMSX3-1.6.7-legacy-armv8.1-sdk30.apk")
        fun pick(sdk: Int, v82: Boolean = true) = names.maxByOrNull { es.scoreApk(it, "arm64-v8a", sdk, v82) }
        assertEquals("ARMSX3-1.6.7-a13-armv8.2-sdk33.apk", pick(33))   // Retroid Pocket Mini V2 (Android 13)
        assertEquals("ARMSX3-1.6.7-a13-armv8.2-sdk33.apk", pick(34))
        assertEquals("ARMSX3-1.6.7-a15-armv8.2-sdk35.apk", pick(36))
        assertEquals("ARMSX3-1.6.7-a11-armv8.2-sdk30.apk", pick(31))
        assertEquals("ARMSX3-1.6.7-legacy-armv8.1-sdk30.apk", pick(33, v82 = false))
        // Nothing this Android can install: every candidate is rejected.
        assertTrue(names.all { es.scoreApk(it, "arm64-v8a", 29, true) <= -50 })
        // RPCSX's single APK is still picked.
        assertTrue(es.scoreApk("rpcsx-release.apk", "arm64-v8a", 33, true) > -50)
    }
}

class Ps3GamesTest {
    private val p = io.github.marsinsanity.mainui.emu.Ps3Games

    @Test fun titleIdsInShortcutsAndNames() {
        assertEquals("BLUS12345", p.titleIdIn("BLUS12345"))
        assertEquals("BLUS12345", p.titleIdIn("[title_id] BLUS12345\n"))
        assertEquals("NPEB00874", p.titleIdIn("Some Game [npeb-00874]"))
        assertNull(p.titleIdIn("Killzone 3"))
        assertNull(p.titleIdIn("ABCDE123456"))
    }

    @Test fun pathsAreGivenAsRpcsxSeesThem() {
        assertEquals("/storage/emulated/0/Android/data/net.rpcsx/files/config/dev_hdd0/game/BLUS12345",
            p.toRpcsxView("/data/media/0/Android/data/net.rpcsx/files/config/dev_hdd0/game/BLUS12345"))
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

class PlayStoreCoresTest {
    private val f = io.github.marsinsanity.mainui.emu.RetroArchCores::playModuleCore

    @Test fun coreModulesAreRecognized() {
        assertEquals("mgba", f("core_mgba"))
        assertEquals("fbneo", f("core_fbneo"))
        assertEquals("mupen64plus_next_gles3", f("core_mupen64plus_next_gles3"))
        assertEquals("mesen-s", f("core_mesen_s"))
    }

    @Test fun otherSplitsAreIgnored() {
        assertNull(f("config.arm64_v8a"))
        assertNull(f("core_mgba.config.arm64_v8a"))
        assertNull(f("config.xhdpi"))
        assertNull(f("core_"))
    }
}

class UpdaterTest {
    @Test fun picksTheNewestMainUiBuildFromReleases() {
        val json = """[
          {"tag_name":"v0.1.30","draft":false,"published_at":"2026-11-10T10:00:00Z","body":"Fix things",
           "assets":[{"name":"MainUI-0.1.30.apk","size":11000000,"digest":"sha256:abc","browser_download_url":"https://x/30.apk"}]},
          {"tag_name":"v0.1.41","draft":true,"assets":[{"name":"MainUI-0.1.41.apk","browser_download_url":"https://x/41.apk"}]},
          {"tag_name":"v0.1.35-1a2b3c4","draft":false,"published_at":"2026-11-11T10:00:00Z","body":"",
           "assets":[{"name":"MainUI-0.1.35.apk","size":1,"browser_download_url":"https://x/35.apk"},{"name":"notes.txt"}]},
          {"tag_name":"other","draft":false,"assets":[{"name":"something-else.apk"}]}
        ]"""
        val all = io.github.marsinsanity.mainui.store.Updater.parseReleases(json)
        assertEquals(listOf(35, 30), all.map { it.versionCode })          // drafts and other APKs skipped
        assertEquals("0.1.35", all[0].versionName)
        assertEquals("https://x/35.apk", all[0].url)
        assertNull(all[0].sha256)
        assertEquals("abc", all[1].sha256)
        assertEquals("2026-11-10", all[1].published)
    }
}

class BoxArtTest {
    private val db = io.github.marsinsanity.mainui.scraper.LibretroDb
    private val m = io.github.marsinsanity.mainui.scraper.BoxArtMatch
    private val id = io.github.marsinsanity.mainui.scraper.RomIdent

    private val gbaDat = """
        clrmamepro (
        	name "Nintendo - Game Boy Advance"
        )

        game (
        	name "007 - NightFire (USA, Europe) (En,Fr,De)"
        	region "USA"
        	serial "A7OE"
        	rom ( name "007 - NightFire (USA, Europe) (En,Fr,De).gba" size 8388608 crc 56C83C16 md5 71 sha1 F4 serial "A7OE" )
        )
        game (
        	name "Legend of Zelda, The - The Minish Cap (USA)"
        	rom ( name "Legend of Zelda, The - The Minish Cap (USA).gba" size 16777216 crc B4BD50E4 serial "BZME" )
        )
    """.trimIndent()

    private val arcadeDat = """
        game (
        	name "'88 Games"
        	releaseyear "1988"
        	rom ( name 88games.zip size 758474 crc C1544E79 md5 51 sha1 3d )
        )
    """.trimIndent()

    @Test fun datIndexesCrcSerialAndFileName() {
        val d = db.parse(gbaDat)
        assertEquals(listOf("007 - NightFire (USA, Europe) (En,Fr,De)"), d.crc("56c83c16"))
        assertEquals(listOf("Legend of Zelda, The - The Minish Cap (USA)"), d.serial("BZME"))
        assertEquals(listOf("Legend of Zelda, The - The Minish Cap (USA)"), d.file("Legend of Zelda, The - The Minish Cap (USA).gba"))
        assertEquals(2, d.names.size)
        // FBNeo: unquoted zip names map short names to titles.
        assertEquals(listOf("'88 Games"), db.parse(arcadeDat).file("88games"))
    }

    @Test fun serialKeysMatchWhatTheDiscsCarry() {
        assertTrue("SLUS00594" in db.serialKeys("SLUS-00594"))
        assertTrue("SLPM65002" in db.serialKeys("SLPM-65002-0"))
        assertTrue("GW7P" in db.serialKeys("DL-DOL-GW7P-EUR"))
        assertTrue("SP3E" in db.serialKeys("RVL-SP3E-USA-B0"))
        assertTrue("CTRPAREE" in db.serialKeys("CTR-P-AREE"))
        assertTrue("T4305G" in db.serialKeys("T-4305G"))
    }

    @Test fun titlesIgnoreCasePunctuationAndArticles() {
        assertEquals(m.title("Legend of Zelda, The - A Link to the Past (USA)"), m.title("The Legend of Zelda: A Link to the Past"))
        assertEquals(m.title("Pokemon - Fire Red Version (USA, Europe) (Rev 1)"), m.title("pokemon fire red version"))
        assertTrue(m.title("Super Mario Bros. (World)") != m.title("Super Mario Bros. 3 (USA)"))
        assertEquals("advance wars", m.title("Advance Wars [b1] (USA)"))
    }

    @Test fun picksExactlyIdentifiedArtFirst() {
        val avail = setOf("Advance Wars (USA)", "Advance Wars (Europe)", "Advance Wars (USA) (Beta)")
        assertEquals("Advance Wars (Europe)", m.pick(listOf("Advance Wars (Europe)"), "aw", avail, listOf("USA", "World", "Europe")))
    }

    @Test fun titleMatchPrefersOwnRegionThenPreferences() {
        val avail = setOf("Advance Wars (USA)", "Advance Wars (Europe)", "Advance Wars (USA) (Beta)", "Advance Wars 2 - Black Hole Rising (USA)")
        // The rom says Europe: its own region wins over the USA preference.
        assertEquals("Advance Wars (Europe)", m.pick(emptyList(), "Advance Wars (Europe) (Rev 1)", avail, listOf("USA", "World", "Europe")))
        // No region in the name: the preference order decides; betas never win.
        assertEquals("Advance Wars (USA)", m.pick(emptyList(), "advance wars", avail, listOf("USA", "World", "Europe")))
        assertEquals("Advance Wars (Europe)", m.pick(emptyList(), "advance wars", avail, listOf("Europe", "USA")))
    }

    @Test fun refusesToGuess() {
        val avail = setOf("Super Mario Bros. (World)", "Super Mario Bros. 3 (USA)", "Mario Bros. (World)")
        assertNull(m.pick(emptyList(), "Super Mario Bros. 2 (USA)", avail, listOf("USA")))
        assertNull(m.pick(emptyList(), "Mario", avail, listOf("USA")))
        // Only a beta exists: not used for the retail game.
        assertNull(m.pick(emptyList(), "Secret Game (USA)", setOf("Secret Game (USA) (Beta)"), listOf("USA")))
        // ...but a beta dump gets the beta's box.
        assertEquals("Secret Game (USA) (Beta)", m.pick(emptyList(), "Secret Game (USA) (Beta)", setOf("Secret Game (USA) (Beta)"), listOf("USA")))
    }

    @Test fun multiDiscGamesKeepTheirDisc() {
        val avail = setOf("Final Fantasy VII (USA) (Disc 1)", "Final Fantasy VII (USA) (Disc 2)")
        assertEquals("Final Fantasy VII (USA) (Disc 2)", m.pick(emptyList(), "Final Fantasy VII (USA) (Disc 2)", avail, listOf("USA")))
    }

    @Test fun thumbnailNamesReplaceReservedCharacters() {
        assertEquals("Sonic _ Knuckles (World)", m.thumbName("Sonic & Knuckles (World)"))
        assertEquals("Ys I _ II (USA)", m.thumbName("Ys I & II (USA)"))
    }

    @Test fun readsSerialsFromHeaders() {
        // N64 header in all three byte orders.
        val z64 = ByteArray(0x40).also { it[0] = 0x80.toByte(); it[1] = 0x37; it[2] = 0x12; it[3] = 0x40; "NSME".toByteArray().copyInto(it, 0x3B) }
        val v64 = z64.copyOf().also { for (i in 0 until 0x40 step 2) { val t = it[i]; it[i] = it[i + 1]; it[i + 1] = t } }
        val n64 = z64.copyOf().also { for (i in 0 until 0x40 step 4) { val a = it[i]; val b = it[i + 1]; it[i] = it[i + 3]; it[i + 1] = it[i + 2]; it[i + 2] = b; it[i + 3] = a } }
        assertEquals("NSME", id.n64Code(z64)); assertEquals("NSME", id.n64Code(v64)); assertEquals("NSME", id.n64Code(n64))
        assertEquals("SCUS94163", id.bootSerial("BOOT = cdrom:\\SCUS_941.63;1\r\nTCB = 4\r\n"))
        assertEquals("SLUS20312", id.bootSerial("BOOT2 = cdrom0:\\SLUS_203.12;1\nVER = 1.00\n"))
        val saturn = ByteArray(0x100).also { "SEGA SEGASATURN SEGA ENTERPRISES".toByteArray().copyInto(it, 0); "T-4305G   ".toByteArray().copyInto(it, 0x20) }
        assertEquals("T-4305G", id.segaProduct(saturn, "SEGA SEGASATURN", 0x20))
    }

    @Test fun readsGameCubeAndWiiDiscIds() {
        val dir = java.nio.file.Files.createTempDirectory("discs").toFile()
        try {
            val iso = java.io.File(dir, "a.iso").apply { writeBytes("GALE01".toByteArray() + ByteArray(64)) }
            val rvz = java.io.File(dir, "b.rvz").apply { writeBytes("RVZ\u0001".toByteArray() + ByteArray(0x54) + "RMGE01".toByteArray() + ByteArray(64)) }
            val wbfs = java.io.File(dir, "c.wbfs").apply {
                val b = ByteArray(0x200 + 16); "WBFS".toByteArray().copyInto(b, 0); b[8] = 9; "SB4P01".toByteArray().copyInto(b, 0x200); writeBytes(b)
            }
            assertEquals("GALE01", id.discId6(iso)); assertEquals("RMGE01", id.discId6(rvz)); assertEquals("SB4P01", id.discId6(wbfs))
        } finally { dir.deleteRecursively() }
    }

    @Test fun gameTdbRegionsFollowTheDiscId() {
        val r = { id: String, prefs: List<String> -> io.github.marsinsanity.mainui.scraper.FreeArt.GameTdb.regions(id, prefs) }
        assertEquals("US", r("GALE01", emptyList()).first())
        assertEquals("JA", r("GALJ01", emptyList()).first())
        assertEquals("EN", r("RMGP01", emptyList()).first())
        assertEquals("DE", r("RSPD01", emptyList()).first())
    }
}

class LaunchBoxTest {
    private val lb = io.github.marsinsanity.mainui.scraper.LaunchBoxDb

    private val xml = """<?xml version="1.0" standalone="yes"?>
<LaunchBox>
  <Game>
    <Name>Legend of Zelda: The Minish Cap</Name>
    <Overview>A line
    <Name>is escaped in real files, so this can't happen</Name> but even so it is ignored after Name was set
More text &amp; more.</Overview>
    <DatabaseID>10</DatabaseID>
    <Platform>Nintendo Game Boy Advance</Platform>
  </Game>
  <Game>
    <Name>Tetris</Name>
    <DatabaseID>20</DatabaseID>
    <Platform>Nintendo Entertainment System</Platform>
  </Game>
  <Game>
    <Name>Tetris</Name>
    <DatabaseID>21</DatabaseID>
    <Platform>Nintendo Entertainment System</Platform>
  </Game>
  <Game>
    <Name>Test Drive: Off-Road 3</Name>
    <DatabaseID>30</DatabaseID>
    <Platform>Sony Playstation</Platform>
  </Game>
  <Game>
    <Name>Some PC Game</Name>
    <DatabaseID>40</DatabaseID>
    <Platform>Windows</Platform>
  </Game>
  <Platform>
    <Name>Nintendo Game Boy Advance</Name>
  </Platform>
  <GameAlternateName>
    <AlternateName>4x4 World Trophy</AlternateName>
    <DatabaseID>30</DatabaseID>
  </GameAlternateName>
  <GameImage>
    <DatabaseID>10</DatabaseID>
    <FileName>zelda-us.jpg</FileName>
    <Type>Box - Front</Type>
    <Region>North America</Region>
  </GameImage>
  <GameImage>
    <DatabaseID>10</DatabaseID>
    <FileName>zelda-eu.jpg</FileName>
    <Type>Box - Front</Type>
    <Region>Europe</Region>
  </GameImage>
  <GameImage>
    <DatabaseID>10</DatabaseID>
    <FileName>zelda-shot.jpg</FileName>
    <Type>Screenshot - Gameplay</Type>
  </GameImage>
  <GameImage>
    <DatabaseID>20</DatabaseID>
    <FileName>tetris-a.jpg</FileName>
    <Type>Box - Front</Type>
  </GameImage>
  <GameImage>
    <DatabaseID>21</DatabaseID>
    <FileName>tetris-b.jpg</FileName>
    <Type>Box - Front</Type>
  </GameImage>
  <GameImage>
    <DatabaseID>30</DatabaseID>
    <FileName>td-eu.jpg</FileName>
    <Type>Box - Front</Type>
    <Region>Europe</Region>
  </GameImage>
  <GameImage>
    <DatabaseID>30</DatabaseID>
    <FileName>td-us.jpg</FileName>
    <Type>Box - Front</Type>
    <Region>North America</Region>
  </GameImage>
</LaunchBox>
"""

    private fun build(): Map<String, io.github.marsinsanity.mainui.scraper.LaunchBoxDb.Lookup> {
        val dir = java.nio.file.Files.createTempDirectory("lb").toFile()
        try {
            val counts = lb.buildIndex(xml.reader().buffered(), setOf("Nintendo Game Boy Advance", "Nintendo Entertainment System", "Sony Playstation"), dir)
            assertEquals(mapOf("Nintendo Game Boy Advance" to 1, "Nintendo Entertainment System" to 2, "Sony Playstation" to 1), counts)
            return counts.keys.associateWith { p -> io.github.marsinsanity.mainui.scraper.LaunchBoxDb.Lookup(lb.parseIndex(java.io.File(dir, lb.fileName(p)).readLines().asSequence())) }
        } finally { dir.deleteRecursively() }
    }

    @Test fun matchesByTitleAndAlternateName() {
        val l = build()
        val zelda = l.getValue("Nintendo Game Boy Advance").find("Legend of Zelda, The - The Minish Cap (USA)")!!
        assertEquals("10", zelda.id)
        assertEquals(listOf("North America" to "zelda-us.jpg", "Europe" to "zelda-eu.jpg"), zelda.boxes)   // screenshots dropped
        // The European name of the same game is a LaunchBox alternate name.
        assertEquals("30", l.getValue("Sony Playstation").find("4x4 World Trophy (Europe) (En,Fr,De,Es,It)")?.id)
    }

    @Test fun twoGamesWithOneTitleAreNotGuessed() {
        assertNull(build().getValue("Nintendo Entertainment System").find("Tetris (USA)"))
    }

    @Test fun picksTheDumpsRegionThenItsContinentThenPreferences() {
        val g = build().getValue("Sony Playstation").find("4x4 World Trophy")!!
        assertEquals("td-eu.jpg", lb.pickBox(g, "4x4 World Trophy (Europe)", listOf("USA")))
        assertEquals("td-eu.jpg", lb.pickBox(g, "4x4 World Trophy (France)", listOf("USA")))   // France -> Europe before USA
        assertEquals("td-us.jpg", lb.pickBox(g, "4x4 World Trophy", listOf("USA", "Europe")))
    }

    @Test fun decodesEntities() {
        assertEquals("Ratchet & Clank <3 \"Q\" 'A' é", lb.unescape("Ratchet &amp; Clank &lt;3 &quot;Q&quot; &apos;A&apos; &#233;"))
    }

    @Test fun accentsDontMatter() {
        val m = io.github.marsinsanity.mainui.scraper.BoxArtMatch
        assertEquals(m.title("Pokémon Snap"), m.title("Pokemon Snap (USA)"))
    }

    @Test fun gameTdbUrlsPerPlatform() {
        val t = io.github.marsinsanity.mainui.scraper.FreeArt.GameTdb
        val wii = t.urls("B", "wii", "RMGE01", 640, emptyList())
        assertEquals("B/wii/coverfullHQ/US/RMGE01.png" to true, wii.first())
        assertTrue(wii.contains("B/wii/cover/US/RMGE01.png" to false))
        assertEquals("B/ds/coverHQ/US/AMCE.jpg" to false, t.urls("B", "nds", "AMCE", 640, emptyList()).first())
        assertEquals("B/ds/coverM/EN/AMCP.jpg" to false, t.urls("B", "nds", "AMCP", 320, emptyList()).first())
        assertEquals("B/ps3/coverHQ/EN/BLES00687.jpg" to false, t.urls("B", "ps3", "BLES00687", 0, emptyList()).first())
        assertEquals("JA", t.regions("BLJM60001", emptyList(), ps3 = true).first())
    }
}

class HotkeysTest {
    private val h = io.github.marsinsanity.mainui.emu.Hotkeys

    @Test fun labelsReadLikeKeyboards() {
        assertEquals("F1", h.label("keys:KEYCODE_F1"))
        assertEquals("Shift+F7", h.label("keys:KEYCODE_SHIFT_LEFT+KEYCODE_F7"))
        assertEquals("not set", h.label(null))
    }

    @Test fun stepsThroughFKeysAndModifiers() {
        assertEquals(48, h.CHOICES.size)
        assertEquals("keys:KEYCODE_F2", h.step("keys:KEYCODE_F1", true))
        assertEquals("keys:KEYCODE_SHIFT_LEFT+KEYCODE_F1", h.step("keys:KEYCODE_F12", true))
        assertEquals("keys:KEYCODE_ALT_LEFT+KEYCODE_F12", h.step("keys:KEYCODE_F1", false))   // wraps around
        assertEquals("keys:KEYCODE_F1", h.step(null, true))                                  // from "not set"
    }

    @Test fun virtualKeyboardNames() {
        assertEquals("F3", h.virtualKeys("keys:KEYCODE_F3"))
        assertEquals("CTRL+F5", h.virtualKeys("keys:KEYCODE_CTRL_LEFT+KEYCODE_F5"))
        assertNull(h.virtualKeys("keys:KEYCODE_BACK"))   // not on the virtual keyboard: sent with `input` instead
    }
}

class EmulatorUpdatesTest {
    private val u = io.github.marsinsanity.mainui.store.EmulatorUpdates

    @Test fun comparesVersionsNotText() {
        assertEquals(true, u.isNewer("v1.6.7", "1.6.6"))
        assertEquals(false, u.isNewer("1.6.7", "1.6.7"))
        assertEquals(true, u.isNewer("1.22.2", "1.9.9"))
        assertEquals(true, u.isNewer("1.18", "1.17.1"))
        assertEquals(false, u.isNewer("1.17", "1.17.0"))
        assertEquals(true, u.isNewer("2125.0", "2124.1"))
    }

    @Test fun ignoresGitHashesButNotBuildNumbers() {
        assertEquals(listOf(0L, 2, 0, 3600), u.numbers("0.2.0-3600-g1f2e3d4"))
        assertEquals(true, u.isNewer("v0.2.0-3611", "0.2.0-3600-g1f2e3d4"))
        assertEquals(listOf(1L, 0, 20250425), u.numbers("1.0.20250425"))
        assertEquals(true, u.isNewer("1.0.20250501", "1.0.20250425"))
    }

    @Test fun buildNumberTagsAgainstTheInstalledBuild() {
        // Vita3K: release "v12" is versionName "0.2.0-12", versionCode 12.
        assertNull(u.isNewer("v12", "0.2.0-12"))
        assertEquals(false, u.buildNumberNewer("v12", "0.2.0-12", 12))
        assertEquals(true, u.buildNumberNewer("v13", "0.2.0-12", 12))
        assertEquals(false, u.buildNumberNewer("v11", "0.2.0-12", 12))
        assertEquals(false, u.buildNumberNewer("v40", "1.4", 40))         // same as the version code
        assertNull(u.buildNumberNewer("v5", "1.2.3", 10203))               // different counters: by date
        assertNull(u.buildNumberNewer("v1.2.4", "1.2.3", 10203))
    }

    @Test fun refusesToCompareDifferentSchemes() {
        assertNull(u.isNewer("v20250425", "1.0"))        // a date tag against a version: decided by release date instead
        assertNull(u.isNewer("continuous", "1.0"))
    }
}

class RunningAppsTest {
    private val ps = listOf(
        "  PID USER           RSS %CPU NAME",
        "    1 root         10764  0.0 init",
        "  612 system      312000  1.2 system_server",
        " 1201 u0_a120     180000  0.5 com.android.systemui",
        " 2210 u0_a170      90000  0.0 com.google.android.gms.persistent",
        " 3001 u0_a201     500000 45.0 org.ppsspp.ppsspp",
        " 3002 u0_a201      20000  1.5 org.ppsspp.ppsspp:remote",
        " 3100 u0_a215     150000  0.3 com.android.chrome",
        " 3105 u0_i12       60000  0.1 com.android.chrome:sandboxed_process0",
        " 3200 u0_a230     300000  2.0 com.retroarch.aarch64",
        " 3300 u10_a40      40000  0.0 com.example.work",
    )
    private val apps = setOf("org.ppsspp.ppsspp", "com.android.chrome", "com.retroarch.aarch64", "com.example.work")

    @Test fun oneRowPerAppOnlyKillableOnes() {
        val r = io.github.marsinsanity.mainui.data.RunningApps.parse(ps, { it in apps }, setOf("com.retroarch.aarch64"), "org.ppsspp.ppsspp")
        assertEquals(listOf("org.ppsspp.ppsspp", "com.retroarch.aarch64", "com.android.chrome", "com.example.work"), r.map { it.pkg })
        val ppsspp = r.first()
        assertEquals(listOf(3001, 3002), ppsspp.pids)
        assertEquals(520000L, ppsspp.rssKb)
        assertEquals(46.5, ppsspp.cpu, 0.001)
        assertTrue(ppsspp.isSession)
        assertTrue(r[1].fromMainUI)
        assertEquals(listOf(3100), r[2].pids)   // the isolated sandbox process (u0_i12) isn't an app process
    }

    @Test fun recentAppsFromDumpsys() {
        val dump = """
  * Recent #0: Task{4032e34 #117 visible=true type=standard A=10130:io.github.marsinsanity.mainui U=0 StackId=117 sz=1}
    affinity=10130:io.github.marsinsanity.mainui
  * Recent #1: Task{6eb97d0 #114 visible=false type=home I=com.android.launcher3/.uioverrides.QuickstepLauncher U=0 StackId=1 sz=1}
  * Recent #4: Task{3a3efb4 #110 visible=false type=undefined A=10131:org.ppsspp.ppsspp U=0 StackId=110 sz=0}
    realActivity={com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture}
    realActivity=com.google.android.apps.messaging/.ui.ConversationListActivity
"""
        val r = io.github.marsinsanity.mainui.data.RunningApps.recentPackages(dump)
        assertTrue(r.containsAll(listOf("io.github.marsinsanity.mainui", "com.android.launcher3", "org.ppsspp.ppsspp",
            "com.retroarch", "com.google.android.apps.messaging")))
    }

    @Test fun systemPartsAreProtected() {
        val p = io.github.marsinsanity.mainui.data.RunningApps.PROTECTED
        assertTrue("com.android.systemui" in p && "com.google.android.gms" in p && "com.android.phone" in p)
        val r = io.github.marsinsanity.mainui.data.RunningApps.parse(ps, { it !in p }, emptySet(), null)
        assertTrue(r.none { it.pkg in p })
    }
}

class SafGrantsTest {
    private val g = io.github.marsinsanity.mainui.core.SafGrants

    @Test fun readsFolderGrantsOfOneAppFromDumpsys() {
        val dump = """
ACTIVITY MANAGER URI PERMISSIONS (dumpsys activity permissions)
  * UID 10150 holds:
    UriPermission{1a2b3c content://com.android.externalstorage.documents/tree/primary%3Aroms%2Fpsx [user 0] [prefix]}
    UriPermission{4d5e6f content://com.android.externalstorage.documents/tree/1234-ABCD%3ARoms [user 0] [prefix]}
    UriPermission{777777 content://com.android.externalstorage.documents/tree/primary%3Aroms/document/primary%3Aroms%2Fx.bin [user 0]}
  * UID 10160 holds:
    UriPermission{abcdef content://com.android.externalstorage.documents/tree/primary%3A [user 0] [prefix]}
"""
        assertEquals(listOf("primary:roms/psx", "1234-ABCD:Roms", "primary:roms"), g.fromDumpsys(dump, 10150))
        assertEquals(listOf("primary:"), g.fromDumpsys(dump, 10160))
        val xml = """<uri-grants><uri-grant sourceUserId="0" targetUserId="0" sourcePkg="com.android.externalstorage.documents" targetPkg="com.github.stenzek.duckstation" uri="content://com.android.externalstorage.documents/tree/primary%3ARoms" prefix="true" modeFlags="3" /></uri-grants>"""
        assertEquals(listOf("primary:Roms"), g.fromXml(xml, "com.github.stenzek.duckstation"))
        assertEquals(emptyList<String>(), g.fromXml(xml, "other.app"))
    }

    @Test fun linksThroughTheDeepestGrantedFolderHoldingTheGame() {
        val doc = "primary:roms/psx/Final Fantasy VII (Disc 1).chd"
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3Aroms%2Fpsx/document/" +
            "primary%3Aroms%2Fpsx%2FFinal%20Fantasy%20VII%20(Disc%201).chd",
            g.documentUri(doc, listOf("primary:", "primary:roms/psx", "primary:roms/psp"), "primary:roms"))
        // Nothing granted holds it: through the ROMs folder.
        assertTrue(g.documentUri(doc, listOf("primary:roms/ps2", "primary:romsx"), "primary:roms")
            .startsWith("content://com.android.externalstorage.documents/tree/primary%3Aroms/document/"))
        assertTrue(g.contains("primary:", doc) && !g.contains("primary:rom", doc))
        assertEquals("primary:roms/psx/a b", g.percentDecode("primary%3Aroms%2Fpsx%2Fa%20b"))
    }
}

class TasksTest {
    private val dump = """
  * Recent #0: Task{4032e34 #117 visible=true type=standard mode=fullscreen translucent=false A=10130:io.github.marsinsanity.mainui U=0 StackId=117 sz=1}
    affinity=10130:io.github.marsinsanity.mainui
  * Recent #1: Task{6eb97d0 #114 visible=false type=home mode=fullscreen translucent=true I=com.android.launcher3/.uioverrides.QuickstepLauncher U=0 StackId=1 sz=1}
  * Recent #2: Task{3a3efb4 #110 visible=false type=undefined mode=undefined translucent=true A=10131:org.ppsspp.ppsspp U=0 StackId=110 sz=0}
    affinity=10131:org.ppsspp.ppsspp
  * Recent #3: Task{b49da77 #120 visible=false type=standard mode=fullscreen translucent=false A=10140:com.flycast.emulator U=0 StackId=120 sz=2}
    affinity=10140:com.flycast.emulator
    realActivity={com.flycast.emulator/com.flycast.emulator.NativeGLActivity}
  * Recent #4: Task{90e17dd #80 visible=false type=standard A=10150:custom.affinity U=0 StackId=80 sz=1}
    realActivity={org.dolphinemu.dolphinemu/org.dolphinemu.dolphinemu.ui.main.MainActivity}
"""

    @Test fun findsTheLiveTaskOfAPackage() {
        val t = io.github.marsinsanity.mainui.emu.Tasks
        assertEquals(120, t.idOf(dump, "com.flycast.emulator"))
        assertEquals(114, t.idOf(dump, "com.android.launcher3"))
        assertEquals(80, t.idOf(dump, "org.dolphinemu.dolphinemu"))     // found by its activity, not its affinity
        assertNull(t.idOf(dump, "org.ppsspp.ppsspp"))                  // only remembered in recents (sz=0)
        assertNull(t.idOf(dump, "com.retroarch"))
    }
}

class TheGamesDbTest {
    private val t = io.github.marsinsanity.mainui.scraper.TheGamesDb

    private val dump = """{"code":200,"status":"Success","data":{"count":6,"games":[
 {"id":1,"game_title":"Sonic the Hedgehog","release_date":"1991-06-23","platform":18,"region_id":2,"country_id":50,
  "overview":"Blue \"hedgehog\" \\ runs {fast} [really]","developers":[1,2],"alternates":["Sonic 1"],"uids":null,"hashes":null},
 {"id":2,"game_title":"Sonic the Hedgehog","platform":36,"region_id":6,"country_id":13,"alternates":null,"uids":null},
 {"id":3,"game_title":"Tetris","platform":7,"region_id":2,"country_id":0,"alternates":null},
 {"id":4,"game_title":"Tetris","platform":7,"region_id":2,"country_id":0,"alternates":null},
 {"id":5,"game_title":"Devil May Cry 3: Dante&#039;s Awakening","platform":11,"region_id":2,"country_id":50,
  "alternates":["DMC3"],"uids":[{"uid":"SLUS-20964","games_uids_patterns_id":2},{"uid":"SLES-53038","games_uids_patterns_id":2}]},
 {"id":6,"game_title":"No Box","platform":7,"region_id":0,"country_id":0},
 {"id":7,"game_title":"Halo","platform":1,"region_id":1,"country_id":50}
]},"include":{"boxart":{"base_url":{"original":"https://cdn.thegamesdb.net/images/original/"},"data":{
 "1":[{"id":10,"type":"fanart","side":null,"filename":"fanart/1-1.jpg","resolution":"1920x1080"},{"id":11,"type":"boxart","side":"back","filename":"boxart/back/1-1.jpg"},{"id":12,"type":"boxart","side":"front","filename":"boxart/front/1-1.jpg"}],
 "2":[{"id":13,"type":"boxart","side":"front","filename":"boxart/front/2-1.jpg"},{"id":14,"type":"boxart","side":"front","filename":"boxart/front/2-2.jpg"}],
 "3":[{"id":15,"type":"boxart","side":"front","filename":"boxart/front/3-1.jpg"}],
 "4":[{"id":16,"type":"boxart","side":"front","filename":"boxart/front/4-1.jpg"}],
 "5":[{"id":17,"type":"boxart","side":"front","filename":"boxart/front/5-1.jpg"}],
 "7":[{"id":18,"type":"boxart","side":"front","filename":"boxart/front/7-1.jpg"}]
}},"platform":{"data":{"7":{"id":7,"name":"NES","alias":"nes"}}}},"pages":null}"""

    private fun lookup(system: String): io.github.marsinsanity.mainui.scraper.TheGamesDb.Lookup {
        val dir = java.nio.file.Files.createTempDirectory("tgdb").toFile()
        t.buildIndex(java.io.StringReader(dump), t.PLATFORMS.values.flatten().toSet(), dir)
        val games = t.PLATFORMS.getValue(system).flatMap { p -> File(dir, "$p.tsv").takeIf { it.isFile }?.readLines()?.let { t.parseIndex(it.asSequence()) } ?: emptyList() }
        dir.deleteRecursively()
        return io.github.marsinsanity.mainui.scraper.TheGamesDb.Lookup(games)
    }

    @Test fun indexKeepsFrontBoxesOfKnownPlatforms() {
        val dir = java.nio.file.Files.createTempDirectory("tgdb").toFile()
        val counts = t.buildIndex(java.io.StringReader(dump), t.PLATFORMS.values.flatten().toSet(), dir)
        assertEquals(mapOf(18 to 1, 36 to 1, 7 to 2, 11 to 1), counts)       // no box (6) and PC (7: not a system here) are left out
        val ps2 = t.parseIndex(File(dir, "11.tsv").readLines().asSequence()).single()
        assertEquals(listOf("Devil May Cry 3: Dante's Awakening", "DMC3"), ps2.names)
        assertTrue("SLUS20964" in ps2.serials)
        assertEquals("boxart/front/5-1.jpg", ps2.file)
        assertEquals("boxart/front/2-1.jpg", t.parseIndex(File(dir, "36.tsv").readLines().asSequence()).single().file)
        dir.deleteRecursively()
    }

    @Test fun regionPicksAmongSameTitleEntriesAndRefusesRealAmbiguity() {
        val md = lookup("megadrive")
        assertEquals("1", md.find("Sonic the Hedgehog (USA, Europe)", listOf("USA"))?.id)
        assertEquals("2", md.find("Sonic the Hedgehog (Europe)", listOf("USA"))?.id)     // Germany counts as Europe
        assertEquals("1", md.find("Sonic the Hedgehog", listOf("USA", "Europe"))?.id)
        assertEquals("1", md.find("Sonic 1", listOf("USA"))?.id)                         // alternate name
        assertNull(md.find("Sonic the Hedgehog 2", listOf("USA")))                       // never a neighbour
        assertNull(lookup("nes").find("Tetris (USA)", listOf("USA")))                    // two different USA "Tetris"
    }

    @Test fun serialIdentifiesDiscs() {
        val ps2 = lookup("ps2")
        assertEquals("5", ps2.bySerial(listOf("SLES-53038"))?.id)
        assertEquals("5", ps2.find("Devil May Cry 3 - Dante's Awakening (USA)", listOf("USA"))?.id)
        assertNull(ps2.bySerial(listOf("SLUS-99999")))
    }

    /**
     * Against the real dump and DATs (opt in: TGDB_DUMP=<database-latest.json> TGDB_DATS=<dir with
     * libretro-database DATs>): how many dumps find a box, and whether serial matches agree with the
     * dump's own title.
     */
    @Test fun realData() {
        val dumpPath = System.getenv("TGDB_DUMP") ?: return
        val dats = File(System.getenv("TGDB_DATS") ?: return)
        val dir = java.nio.file.Files.createTempDirectory("tgdb-real").toFile()
        val counts = File(dumpPath).bufferedReader().use { t.buildIndex(it, t.PLATFORMS.values.flatten().toSet(), dir) }
        println("TGDB index: ${counts.values.sum()} games")
        fun lk(system: String) = io.github.marsinsanity.mainui.scraper.TheGamesDb.Lookup(t.PLATFORMS.getValue(system).flatMap { p ->
            File(dir, "$p.tsv").takeIf { it.isFile }?.readLines()?.let { t.parseIndex(it.asSequence()) } ?: emptyList() })
        val gameRe = Regex("""game \(\s*\n\s*name "([^"]+)"(.*?)\n\)""", RegexOption.DOT_MATCHES_ALL)
        val serialRe = Regex("""^\s*serial "([^"]+)"""", RegexOption.MULTILINE)
        val unwanted = Regex("""\((Beta|Proto|Demo|Sample|Kiosk|Promo|Pirate|Hack|Unl|Aftermarket|Program|Test|Debug|Alt)[^)]*\)""")
        for ((system, file) in listOf("gba" to "Nintendo - Game Boy Advance.dat", "snes" to "Nintendo - Super Nintendo Entertainment System.dat",
                "psx" to "Sony - PlayStation.dat", "ps2" to "Sony - PlayStation 2.dat")) {
            val f = File(dats, file); if (!f.isFile) continue
            val lookup = lk(system)
            var total = 0; var byTitle = 0; var bySerial = 0; var serialAgree = 0; var either = 0
            for (m in gameRe.findAll(f.readText())) {
                val name = m.groupValues[1]
                if (unwanted.containsMatchIn(name) || name.contains("(Disc ") && !name.contains("(Disc 1)")) continue
                total++
                val tg = lookup.find(name, listOf("USA", "Europe", "Japan"))
                val sg = lookup.bySerial(serialRe.findAll(m.groupValues[2]).map { it.groupValues[1] }.toList())
                if (tg != null) byTitle++
                if (sg != null) {
                    bySerial++
                    val dumpTitle = io.github.marsinsanity.mainui.scraper.BoxArtMatch.title(name.replace(Regex(""" \(Disc \d\)"""), ""))
                    if (sg.names.any { io.github.marsinsanity.mainui.scraper.BoxArtMatch.title(it) == dumpTitle ||
                            io.github.marsinsanity.mainui.scraper.BoxArtMatch.title(it).replace(" ", "") == dumpTitle.replace(" ", "") }) serialAgree++
                    else if (System.getenv("TGDB_SHOW") != null && serialAgree % 7 == 0) println("  serial: $name -> ${sg.names}")
                }
                if (sg != null || tg != null) either++
            }
            println("$system: $total dumps, title $byTitle (${byTitle * 100 / maxOf(total, 1)}%), serial $bySerial " +
                "(title agrees on $serialAgree), any ${either * 100 / maxOf(total, 1)}%")
        }
        dir.deleteRecursively()
    }
}

class ArcadeSourcesTest {
    private val db = io.github.marsinsanity.mainui.scraper.LibretroDb

    @Test fun arcadeFoldersUseFbneoAndMame() {
        assertEquals(listOf("FBNeo - Arcade Games", "MAME"), db.sources("arcade").map { it.thumbs })
        assertEquals(listOf("FBNeo - Arcade Games", "MAME"), db.sources("neogeo").map { it.thumbs })
        assertEquals(listOf("MAME", "FBNeo - Arcade Games"), db.sources("mame").map { it.thumbs })
        assertEquals(listOf("MAME", "FBNeo - Arcade Games"), db.sources("atomiswave").map { it.thumbs })
        assertEquals(listOf("Sega - Naomi", "MAME"), db.sources("naomi").map { it.thumbs })
        assertEquals(listOf("Nintendo - Game Boy Advance"), db.sources("gba").map { it.thumbs })
        assertTrue(db.supports("atomiswave"))
    }

    @Test fun mameDatNamesTheZip() {
        val dat = db.parse("""
clrmamepro (
	name "MAME - Consolidated ROM Sets"
)

game (
	name "Cruis'n USA (rev L4.1)"
	year "1994"
	rom ( name crusnusa.zip size 1 crc D123FE67 )
)
""")
        assertEquals(listOf("Cruis'n USA (rev L4.1)"), dat.file("crusnusa.zip"))
        assertEquals(listOf("Cruis'n USA (rev L4.1)"), dat.file("crusnusa"))
    }
}

class ExtraCoreUrlTest {
    @Test fun matchesTheCoresReleaseAsset() {
        // The name .github/workflows/cores.yml uploads (an earlier build asked for "..._android.so-arm64-v8a.zip": 404).
        assertEquals("yabadabadoo_libretro_android-arm64-v8a.zip",
            io.github.marsinsanity.mainui.emu.RetroArchCores.extraZip("yabadabadoo", "arm64-v8a"))
    }
}

class ZipImagesTest {
    private val z = io.github.marsinsanity.mainui.emu.ZipImages
    private val types = setOf("iso", "xiso", "cso", "cci")

    private fun zip(dir: File, name: String, entries: Map<String, Int>): File {
        val f = File(dir, name)
        java.util.zip.ZipOutputStream(f.outputStream()).use { out ->
            for ((n, size) in entries) { out.putNextEntry(java.util.zip.ZipEntry(n)); out.write(ByteArray(size) { (it % 251).toByte() }); out.closeEntry() }
        }
        return f
    }

    @Test fun unpacksTheImageBesideTheZipOnce() {
        val dir = java.nio.file.Files.createTempDirectory("zi").toFile()
        val f = zip(dir, "Halo.zip", mapOf("readme.txt" to 10, "Halo - Combat Evolved (USA).iso" to 300_000))
        assertNull(z.unpacked(f, types))
        assertEquals("Halo - Combat Evolved (USA).iso" to 300_000L, z.entry(f, types))
        val img = z.unpack(f, types, { _, _ -> }, { false })
        assertEquals("Halo.iso", img.name)
        assertEquals(300_000L, img.length())
        assertEquals(img, z.unpacked(f, types))
        assertTrue(dir.list()!!.none { it.endsWith(".part") })
        dir.deleteRecursively()
    }

    @Test fun stoppedOrWrongZipsLeaveNothingBehind() {
        val dir = java.nio.file.Files.createTempDirectory("zi").toFile()
        val f = zip(dir, "Fable.zip", mapOf("Fable.xiso" to 3_000_000))
        val stopped = runCatching { z.unpack(f, types, { _, _ -> }, { true }) }
        assertTrue(stopped.isFailure)
        assertEquals(listOf("Fable.zip"), dir.list()!!.toList())
        val none = zip(dir, "Docs.zip", mapOf("manual.pdf" to 100))
        assertNull(z.entry(none, types))
        assertTrue(runCatching { z.unpack(none, types, { _, _ -> }, { false }) }.exceptionOrNull()!!.message!!.contains("holds no"))
        dir.deleteRecursively()
    }
}

class UnzipSystemsTest {
    @Test fun xboxAndGamecubeUnpackZippedImages() {
        val arr = org.json.JSONObject(File("src/main/assets/systems.json").readText()).getJSONArray("systems")
        val byId = (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.getString("id") }
        for ((id, image) in listOf("xbox" to "iso", "gc" to "rvz")) {
            val o = byId.getValue(id)
            val exts = (0 until o.getJSONArray("extensions").length()).map { o.getJSONArray("extensions").getString(it) }
            val unzip = (0 until o.getJSONArray("unzip").length()).map { o.getJSONArray("unzip").getString(it) }
            assertTrue("$id lists .zip", "zip" in exts)
            assertTrue("$id unpacks .$image", image in unzip)
            assertTrue("$id: every unpacked type is listed too", exts.containsAll(unzip))
        }
    }
}

class DialogSceneTest {
    private class FakeHost : io.github.marsinsanity.mainui.ui.SceneHost {
        val popped = ArrayList<io.github.marsinsanity.mainui.ui.Scene?>()
        override val hostContext: android.content.Context get() = throw UnsupportedOperationException()
        override val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        override val logicalWidth = 640f
        override val logicalHeight = 480f
        override fun push(scene: io.github.marsinsanity.mainui.ui.Scene) {}
        override fun pop(scene: io.github.marsinsanity.mainui.ui.Scene?) { popped += scene }
        override fun replace(scene: io.github.marsinsanity.mainui.ui.Scene) {}
        override fun popToRoot() {}
        override fun top(): io.github.marsinsanity.mainui.ui.Scene? = null
        override fun invalidate() {}
        override fun toast(message: String, millis: Long) {}
        override fun <T> io(work: () -> T, done: (T) -> Unit): kotlinx.coroutines.Job { done(work()); return kotlinx.coroutines.Job() }
        override fun finishHost() {}
    }

    @Test fun trailingLambdaIsTheConfirmAction() {
        // Every caller writes DialogScene(host, title, text, "DOWNLOAD", "CANCEL") { download() }:
        // A must run it and B must not (0.1.35-0.1.37 had them the other way round).
        var confirmed = 0
        val host = FakeHost()
        val d = io.github.marsinsanity.mainui.ui.DialogScene(host, "Core not installed", "Download it?", "DOWNLOAD", "CANCEL") { confirmed++ }
        d.onKey(io.github.marsinsanity.mainui.ui.Btn.B, false)
        assertEquals(0, confirmed)
        d.onKey(io.github.marsinsanity.mainui.ui.Btn.A, false)
        assertEquals(1, confirmed)
        var cancelled = 0
        io.github.marsinsanity.mainui.ui.DialogScene(host, "t", "m", "YES", "NO", onCancel = { cancelled++ }) { confirmed++ }
            .onKey(io.github.marsinsanity.mainui.ui.Btn.B, false)
        assertEquals(1, cancelled)
        assertEquals(1, confirmed)
    }
}

class EmulatorVariantTest {
    private fun v(pkgs: List<String>, name: String, id: String, pkg: String, label: String?) =
        io.github.marsinsanity.mainui.emu.EmulatorManager.isVariant(pkgs, name, id, pkg, label)

    @Test fun otherBuildsOfKnownEmulators() {
        assertTrue(v(listOf("com.rfandango.haku_x"), "hakuX", "hakux", "com.rfandango.haku_x.debug", "hakuX Debug"))
        assertTrue(v(listOf("org.vita3k.emulator"), "Vita3K", "vita3k", "org.vita3k.emulator.debug", null))
        assertTrue(v(listOf("com.armsx2"), "ARMSX2", "armsx2", "io.someone.ps2fork", "ARMSX2 Nightly"))
        assertTrue(v(listOf("org.vita3k.emulator"), "Vita3K", "vita3k", "com.other.vita", "Vita3K"))
        assertTrue(v(listOf("com.armsx2"), "ARMSX2", "armsx2", "x.y", "ARMSX2 2.6.1"))
    }

    @Test fun neverAnUnrelatedApp() {
        assertFalse(v(listOf("org.dolphinemu.dolphinemu"), "Dolphin", "dolphin", "mobi.mgeek.TunnyBrowser", "Dolphin Browser"))
        assertFalse(v(listOf("com.rfandango.haku_x"), "hakuX", "hakux", "com.rfandango.haku_xtra", "Haku Xtra"))
        assertFalse(v(listOf("org.ppsspp.ppsspp"), "PPSSPP", "ppsspp", "com.example.notes", "Notes"))
    }
}

/** Fixes from the bug review: parsers that must not hang or abort, and lookups that must not lose matches. */
class ReviewFixesTest {
    @Test fun truncatedJsonThrowsInsteadOfLoopingForever() {
        val p = io.github.marsinsanity.mainui.scraper.JsonPull(java.io.StringReader("""{"data":{"games":[{"id":1},{"id":"""))
        val t = Thread.currentThread()
        val watchdog = Thread { runCatching { Thread.sleep(5000); t.interrupt() } }.apply { isDaemon = true; start() }
        try {
            var threw = false
            try {
                p.next(); p.next(); p.next(); p.next(); p.next() // { data { games [
                while (true) { val tok = p.next(); if (tok == io.github.marsinsanity.mainui.scraper.JsonPull.T.BEGIN_OBJECT) p.skipRest(tok) }
            } catch (e: java.io.IOException) { threw = true }
            assertTrue(threw)
        } finally { watchdog.interrupt() }
    }

    @Test fun completeJsonEndsNormally() {
        val p = io.github.marsinsanity.mainui.scraper.JsonPull(java.io.StringReader("""{"a":[1,2]}"""))
        val seen = generateSequence { p.next().takeIf { it != io.github.marsinsanity.mainui.scraper.JsonPull.T.END } }.toList()
        assertEquals(io.github.marsinsanity.mainui.scraper.JsonPull.T.END_OBJECT, seen.last())
    }

    @Test fun outOfRangeEntityIsKeptAsWritten() {
        val lb = io.github.marsinsanity.mainui.scraper.LaunchBoxDb
        assertEquals("A &#1114112; B &#x7FFFFFFF; é", lb.unescape("A &#1114112; B &#x7FFFFFFF; &#233;"))
    }

    @Test fun launchBoxTitleOnTwoPlatformsTakesTheFirstPlatform() {
        fun game(id: String, name: String) = io.github.marsinsanity.mainui.scraper.LaunchBoxDb.Game(id, listOf(name), listOf("" to "$id.jpg"))
        fun lookup(vararg platforms: List<io.github.marsinsanity.mainui.scraper.LaunchBoxDb.Game>) =
            io.github.marsinsanity.mainui.scraper.LaunchBoxDb.Lookup(platforms.toList())
        val aes = listOf(game("1", "Metal Slug"))
        val mvs = listOf(game("2", "Metal Slug"), game("3", "Blazing Star"))
        val l = lookup(aes, mvs)
        assertEquals("1", l.find("Metal Slug (World)")?.id)
        assertEquals("3", l.find("Blazing Star")?.id)
        // Two games with one title on the same platform still can't be told apart.
        assertNull(lookup(aes + game("4", "Metal Slug")).find("Metal Slug"))
    }

    @Test fun titleIndexPicksTheSameAsAFullScan() {
        val m = io.github.marsinsanity.mainui.scraper.BoxArtMatch
        val names = setOf("Legend of Zelda, The - A Link to the Past (USA)", "Legend of Zelda, The - A Link to the Past (Europe)",
            "Super Mario Bros. 3 (USA)", "Super Mario Bros. (World)", "Pokemon - Red Version (USA, Europe)")
        val idx = m.titleIndex(names)
        for (file in listOf("The Legend of Zelda - A Link to the Past (Europe)", "Super Mario Bros (USA)", "Pokémon - Red Version (USA)", "Nothing"))
            assertEquals(m.pick(emptyList(), file, names, listOf("USA")), m.pick(emptyList(), file, names, listOf("USA"), idx))
    }

    @Test fun heldDirectionDoesNotWrapOrFlip() {
        var flips = 0
        val multi = io.github.marsinsanity.mainui.ui.UiItem("Brightness", io.github.marsinsanity.mainui.ui.ItemType.MULTI, values = listOf("1", "2", "3"))
        multi.value = 2
        val l = io.github.marsinsanity.mainui.ui.UiList().apply { set(listOf(multi), false) }
        assertFalse(l.change(1, repeat = true)); assertEquals(2, multi.value)   // held: stops at the top
        assertTrue(l.change(1)); assertEquals(0, multi.value)                    // a single press still wraps
        assertFalse(l.change(-1, repeat = true)); assertEquals(0, multi.value)
        val toggle = io.github.marsinsanity.mainui.ui.UiItem("Wi-Fi", io.github.marsinsanity.mainui.ui.ItemType.TOGGLE, onChange = { flips++ })
        val t = io.github.marsinsanity.mainui.ui.UiList().apply { set(listOf(toggle), false) }
        assertTrue(t.change(1)); assertFalse(t.change(1, repeat = true)); assertFalse(t.change(1, repeat = true))
        assertEquals(1, flips)
    }

    @Test fun packageNamesThatArePathsAreRefused() {
        val re = io.github.marsinsanity.mainui.store.AppStore.PACKAGE_NAME
        assertTrue(re.matches("org.ppsspp.ppsspp")); assertTrue(re.matches("com.retroarch.aarch64")); assertTrue(re.matches("a.b_c.D9"))
        for (bad in listOf("../../../roms/snes/images/Game", "com.foo/../x", "single", ".com.foo", "com..foo", "com.foo.", "com.foo bar"))
            assertFalse(bad, re.matches(bad))
    }

    @Test fun localizedTextIsChosenPerField() {
        val pick = io.github.marsinsanity.mainui.store.AppStore::pickLocalized
        val loc = linkedMapOf(
            "de" to mapOf("name" to "Spiel", "description" to "Beschreibung", "summary" to "Kurz"),
            "en-US" to mapOf("summary" to "Short"),
        )
        val top = mapOf("name" to "Game", "summary" to null, "description" to null, "icon" to "icon.png")
        // English device: en-US where it has the field, else the index's own text, never German.
        val en = pick(loc, top, java.util.Locale.US)
        assertEquals("Short", en["summary"]?.second)
        assertEquals("Game", en["name"]?.second); assertNull(en["name"]?.first)
        assertEquals("Beschreibung", en["description"]?.second) // nothing else has one: any language
        assertEquals(null to "icon.png", en["icon"])
        // German device: German first.
        assertEquals("Spiel", pick(loc, top, java.util.Locale.GERMANY)["name"]?.second)
    }
}

/** gamelist.xml / Logiqx DAT rewriting keeps what MainUI doesn't use, and never overwrites a file it couldn't read. */
class MetadataFilesTest {
    private lateinit var dir: File

    @org.junit.Before fun setUp() {
        GameListXml.newParser = { org.kxml2.io.KXmlParser() }
        dir = java.nio.file.Files.createTempDirectory("snes").toFile()
    }

    @org.junit.After fun tearDown() { dir.deleteRecursively() }

    @Test fun gamelistKeepsOtherToolsData() {
        val f = File(dir, "gamelist.xml")
        f.writeText("""<?xml version="1.0"?>
<gameList>
	<provider><System>SNES</System><software>Skraper</software></provider>
	<alternativeEmulator><label>Snes9x</label></alternativeEmulator>
	<game id="5" source="ScreenScraper.fr">
		<path>./Game (USA).sfc</path>
		<name>Game</name>
		<desc>Fun &amp; games</desc>
		<scrap name="ScreenScraper" date="20240101T000000"/>
		<scrap name="TheGamesDB" date="20240102T000000"/>
		<genre>Action</genre>
	</game>
</gameList>
""")
        val gl = GameListXml(dir).load()
        assertNull(gl.parseError)
        val rom = File(dir, "Game (USA).sfc")
        assertEquals("Game", gl.get(rom)?.name)
        assertEquals("Fun & games", gl.get(rom)?.desc)
        gl.put(rom) { it.fields["image"] = "./images/Game (USA).png" }
        gl.save()
        val text = f.readText()
        for (kept in listOf("<provider><System>SNES</System><software>Skraper</software></provider>",
            "<alternativeEmulator><label>Snes9x</label></alternativeEmulator>",
            "<scrap name=\"ScreenScraper\" date=\"20240101T000000\"/>", "<scrap name=\"TheGamesDB\" date=\"20240102T000000\"/>",
            "<genre>Action</genre>", "<desc>Fun &amp; games</desc>"))
            assertTrue("lost $kept in\n$text", text.contains(kept))
        val again = GameListXml(dir).load()
        assertNull(again.parseError)
        assertEquals("./images/Game (USA).png", again.get(rom)?.image)
        assertEquals(1, File(dir, ".").listFiles()!!.count { it.name.endsWith(".tmp") }.let { if (it == 0) 1 else 0 })
    }

    @Test fun brokenGamelistIsNeverOverwritten() {
        val f = File(dir, "gamelist.xml")
        val broken = "<gameList><game><path>./a.sfc</path><name>A</name></game><game><path>./b.sfc</path><desc>a < b</desc></game><game><path>./c.sfc</path></game></gameList>"
        f.writeText(broken)
        val gl = GameListXml(dir).load()
        assertTrue(gl.parseError != null)
        assertEquals("A", gl.get(File(dir, "a.sfc"))?.name)   // what was read is still used for display
        gl.put(File(dir, "d.sfc")) { it.fields["name"] = "D" }
        assertTrue(runCatching { gl.save() }.isFailure)
        assertEquals(broken, f.readText())
    }

    @Test fun emptyGamelistIsJustEmpty() {
        File(dir, "gamelist.xml").writeText("<?xml version=\"1.0\"?>\n")
        val gl = GameListXml(dir).load()
        assertNull(gl.parseError)
        gl.put(File(dir, "a.sfc")) { it.fields["name"] = "A" }
        gl.save()
        assertEquals("A", GameListXml(dir).load().get(File(dir, "a.sfc"))?.name)
    }

    @Test fun logiqxDatKeepsDisksAttributesAndRepeats() {
        val f = File(dir, "${dir.name}.dat")
        f.writeText("""<?xml version="1.0"?>
<datafile>
	<header>
		<name>Nintendo - SNES</name>
		<clrmamepro forcenodump="required"/>
	</header>
	<game name="Game (USA)">
		<description>Game (USA)</description>
		<release name="Game (USA)" region="USA"/>
		<release name="Game (Canada)" region="CAN"/>
		<rom name="Game (USA).sfc" size="1024" crc="abcd1234" status="verified"/>
		<disk name="game-disc" sha1="0123456789abcdef0123456789abcdef01234567"/>
	</game>
</datafile>
""")
        val d = io.github.marsinsanity.mainui.data.LogiqxDat.forWriting(dir)
        assertEquals(f.name, d.file.name)
        assertNull(d.parseError)
        d.put(File(dir, "Game (USA).sfc"), "Game", "A game", "1994", "Maker", null)
        d.save()
        val text = f.readText()
        for (kept in listOf("<clrmamepro forcenodump=\"required\"/>", "<release name=\"Game (USA)\" region=\"USA\"/>",
            "<release name=\"Game (Canada)\" region=\"CAN\"/>", "status=\"verified\"", "<disk name=\"game-disc\"", "<manufacturer>Maker</manufacturer>"))
            assertTrue("lost $kept in\n$text", text.contains(kept))
    }

    @Test fun textDatIsLeftAloneAndMainUiWritesItsOwn() {
        val theirs = File(dir, "${dir.name}.dat")
        val text = "clrmamepro (\n\tname \"Nintendo - SNES\"\n)\n\ngame (\n\tname \"Game (USA)\"\n\trom ( name \"Game (USA).sfc\" size 1024 crc ABCD1234 )\n)\n"
        theirs.writeText(text)
        val d = io.github.marsinsanity.mainui.data.LogiqxDat.forWriting(dir)
        assertEquals("${dir.name}.mainui.dat", d.file.name)
        d.put(File(dir, "Game (USA).sfc"), "Game", null, null, null, null)
        d.save()
        assertEquals(text, theirs.readText())
        assertEquals("${dir.name}.mainui.dat", io.github.marsinsanity.mainui.data.LogiqxDat.locate(dir)?.name)
    }
}

/** Follow-ups from verifying the review fixes. */
class ReviewFollowUpTest {
    @org.junit.Before fun parser() { GameListXml.newParser = { org.kxml2.io.KXmlParser() } }

    @Test fun byteOrderMarkIsNotAParseError() {
        val dir = java.nio.file.Files.createTempDirectory("bom").toFile()
        try {
            File(dir, "gamelist.xml").writeText("﻿<?xml version=\"1.0\"?>\n<gameList><game><path>./a.sfc</path><name>A</name></game></gameList>")
            val gl = GameListXml(dir).load()
            assertNull(gl.parseError)
            assertEquals("A", gl.get(File(dir, "a.sfc"))?.name)
            File(dir, "${dir.name}.dat").writeText("﻿<?xml version=\"1.0\"?>\n<datafile><game name=\"A\"><rom name=\"a.sfc\" size=\"1\"/></game></datafile>")
            val d = io.github.marsinsanity.mainui.data.LogiqxDat.forWriting(dir)
            assertEquals("${dir.name}.dat", d.file.name)
            assertNull(d.parseError)
        } finally { dir.deleteRecursively() }
    }

    @Test fun emptyXmlCheck() {
        assertTrue(GameListXml.isEmptyXml(""))
        assertTrue(GameListXml.isEmptyXml("  \n<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\n"))
        assertFalse(GameListXml.isEmptyXml("<?xml version=\"1.0\"?><gameList/>"))
        assertFalse(GameListXml.isEmptyXml("clrmamepro ("))
    }

    @Test fun readOnlyDatCannotBeSaved() {
        val dir = java.nio.file.Files.createTempDirectory("ro").toFile()
        try {
            val f = File(dir, "x.dat").apply { writeText("<datafile><game name=\"A\"><rom name=\"a.sfc\" size=\"1\" status=\"verified\"/></game></datafile>") }
            val d = io.github.marsinsanity.mainui.data.LogiqxDat(f).load()
            assertEquals("A", d.find(File(dir, "a.sfc"))?.name)
            assertTrue(runCatching { d.save() }.isFailure)
        } finally { dir.deleteRecursively() }
    }

    @Test fun scaleStopsAtItsEnds() {
        val item = io.github.marsinsanity.mainui.ui.UiItem("Volume", io.github.marsinsanity.mainui.ui.ItemType.MULTI,
            values = listOf("Muted", "50%", "100%"), wrap = false)
        val l = io.github.marsinsanity.mainui.ui.UiList().apply { set(listOf(item), false) }
        assertFalse(l.change(-1)); assertEquals(0, item.value)
        item.value = 2
        assertFalse(l.change(1)); assertEquals(2, item.value)
    }

    @Test fun screenScraperBreakerRestsThenTriesAgain() {
        var now = 0L
        val logs = ArrayList<String>()
        val b = io.github.marsinsanity.mainui.scraper.Scraper.SsBreaker({ logs += it }, { now })
        repeat(4) { b.failure() }
        assertTrue(b.allow())
        b.failure()
        assertFalse(b.allow()); assertEquals(1, logs.size)
        now += 61_000
        assertTrue(b.allow())                     // tries again after a minute
        b.failure(); assertFalse(b.allow())       // still down: rests again, logged once
        assertEquals(1, logs.size)
        now += 61_000
        b.success(); assertTrue(b.allow())
    }
}

/** Which files "Delete ROM" removes with a game. */
class RomFilesTest {
    private lateinit var dir: File
    @org.junit.Before fun setUp() { dir = java.nio.file.Files.createTempDirectory("roms").toFile() }
    @org.junit.After fun tearDown() { dir.deleteRecursively() }
    private fun f(name: String, text: String = "x") = File(dir, name).apply { parentFile?.mkdirs(); writeText(text) }
    private fun names(files: List<File>) = files.map { it.relativeTo(dir).path }.toSet()
    private val rf = io.github.marsinsanity.mainui.data.RomFiles

    @Test fun cueTakesItsTracks() {
        val cue = f("Game (USA).cue", "FILE \"Game (USA) (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\nFILE \"Game (USA) (Track 2).bin\" BINARY\n")
        f("Game (USA) (Track 1).bin"); f("Game (USA) (Track 2).bin"); f("Other.bin")
        assertEquals(setOf("Game (USA).cue", "Game (USA) (Track 1).bin", "Game (USA) (Track 2).bin"), names(rf.related(cue)))
    }

    @Test fun m3uTakesDiscsAndTheirTracks() {
        val m3u = f("FF7.m3u", "FF7 (Disc 1).cue\n# comment\n.hidden/FF7 (Disc 2).chd\n")
        f("FF7 (Disc 1).cue", "FILE \"FF7 (Disc 1).bin\" BINARY\n"); f("FF7 (Disc 1).bin"); f(".hidden/FF7 (Disc 2).chd")
        assertEquals(setOf("FF7.m3u", "FF7 (Disc 1).cue", "FF7 (Disc 1).bin", ".hidden/FF7 (Disc 2).chd"), names(rf.related(m3u)))
    }

    @Test fun gdiTakesItsTracks() {
        val gdi = f("Crazy Taxi.gdi", "3\n1 0 4 2352 track01.bin 0\n2 756 0 2352 \"track 02.raw\" 0\n3 45000 4 2352 track03.bin 0\n")
        f("track01.bin"); f("track 02.raw"); f("track03.bin")
        assertEquals(setOf("Crazy Taxi.gdi", "track01.bin", "track 02.raw", "track03.bin"), names(rf.related(gdi)))
    }

    @Test fun companionsByName() {
        f("Game.img"); f("Game.sub"); f("Game2.mdf")
        assertEquals(setOf("Game.ccd", "Game.img", "Game.sub"), names(rf.related(f("Game.ccd"))))
        assertEquals(setOf("Game2.mds", "Game2.mdf"), names(rf.related(f("Game2.mds"))))
        f("Halo.zip")
        assertEquals(setOf("Halo.iso", "Halo.zip"), names(rf.related(f("Halo.iso"), setOf("iso"))))
        assertEquals(setOf("Halo.zip", "Halo.iso"), names(rf.related(File(dir, "Halo.zip"), setOf("iso"))))
    }

    @Test fun neverATrackAnotherGameUsesOrAFileOutsideTheFolder() {
        val a = f("A.cue", "FILE \"shared.bin\" BINARY\nFILE \"a.bin\" BINARY\nFILE \"../outside.bin\" BINARY\n")
        f("B.cue", "FILE \"shared.bin\" BINARY\n"); f("shared.bin"); f("a.bin")
        File(dir.parentFile, "outside.bin").writeText("keep")
        try {
            assertEquals(setOf("A.cue", "a.bin"), names(rf.related(a)))
        } finally { File(dir.parentFile, "outside.bin").delete() }
    }

    @Test fun deletesAndReportsFailures() {
        val cue = f("G.cue", "FILE \"G.bin\" BINARY\n"); f("G.bin")
        val failed = rf.delete(rf.related(cue))
        assertTrue(failed.isEmpty())
        assertFalse(File(dir, "G.cue").exists()); assertFalse(File(dir, "G.bin").exists())
    }
}
