package io.github.marsinsanity.mainui

import io.github.marsinsanity.mainui.data.GameListXml
import io.github.marsinsanity.mainui.data.GameRepository
import io.github.marsinsanity.mainui.scraper.Hashes
import org.junit.Assert.assertEquals
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
