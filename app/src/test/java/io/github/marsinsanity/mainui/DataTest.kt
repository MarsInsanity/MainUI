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
