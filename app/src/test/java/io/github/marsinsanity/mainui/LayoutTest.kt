package io.github.marsinsanity.mainui

import io.github.marsinsanity.mainui.ui.Gfx
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutTest {
    @Test fun miyooScreenIsExact() {
        val (w, h) = Gfx.logicalSize(640, 480)
        assertEquals(640f, w, 0.01f); assertEquals(480f, h, 0.01f)
    }

    @Test fun retroidPocketMiniV2KeepsThemeWidth() {
        // 1240x1080: narrower than 4:3, so themes keep their 640px width and gain height
        val (w, h) = Gfx.logicalSize(1240, 1080)
        assertEquals(640f, w, 0.01f)
        assertEquals(557.4f, h, 0.1f)
    }

    @Test fun widescreenKeepsThemeHeight() {
        val (w, h) = Gfx.logicalSize(1920, 1080)
        assertEquals(480f, h, 0.01f)
        assertEquals(853.3f, w, 0.1f)
    }

    @Test fun pageDotsGroupTilesOnlyWhenTheyDontFit() {
        // Dots 14 px + 10 px gap on a 600 px wide row: 25 fit.
        assertEquals(1, Gfx.dotsPerGroup(6, 24f, 610f))
        assertEquals(1, Gfx.dotsPerGroup(25, 24f, 610f))
        assertEquals(2, Gfx.dotsPerGroup(26, 24f, 610f))
        assertEquals(2, Gfx.dotsPerGroup(50, 24f, 610f))
        assertEquals(3, Gfx.dotsPerGroup(51, 24f, 610f))
        assertEquals(1, Gfx.dotsPerGroup(0, 24f, 610f))
    }

    @Test fun longSystemNamesShrinkThenWrap() {
        val fit = io.github.marsinsanity.mainui.ui.scenes.TileStrip.Companion::fitLabel
        // A pretend font: 10 px per character at size 20, a tile room of 140 px.
        val fits = { s: String, size: Float -> s.length * size / 2f <= 140f }
        assertEquals(listOf("Games") to 28f, fit("Games", 28f, 20f, fits))
        // 15 characters fit at 18 px only: too small for one line, so two lines.
        val (lines, size) = fit("Nintendo Switch", 28f, 20f, fits)
        assertEquals(listOf("Nintendo", "Switch"), lines)
        assertEquals(23f, size, 0.01f)
        // 13 characters still fit on one line once a little smaller.
        assertEquals(listOf("PlayStation 2") to 21f, fit("PlayStation 2", 28f, 20f, fits))
        // Split after a slash when there is no space.
        assertEquals(listOf("Genesis/", "Megadrive"), fit("Genesis/Megadrive", 28f, 20f, fits).first)
        // Nowhere to split: one line at the smallest size (scrolled when selected).
        assertEquals(listOf("Supercalifragilistic") to 20f, fit("Supercalifragilistic", 28f, 20f, fits))
    }

    @Test fun contentOffsetsAreWholePixels() {
        assertEquals(107f, Gfx.centred(853.33f, 640f), 0f)
        assertEquals(39f, Gfx.centred(557.4f, 480f), 0f)
        assertEquals(0f, Gfx.centred(640f, 640f), 0f)
    }

    @Test fun batteryBoxIsOnionsComposite() {
        // 48x48 icon, "100%" 50 px wide, text on the right: 2*(50+5)+48 = 158, the icon at 0, the text after it.
        val left = Gfx.batteryBox(48, 48, 50, 30, visible = true, fixed = false, align = "left", offsetX = 0)
        assertEquals(158, left.w); assertEquals(48, left.h)
        assertEquals(0, left.iconX); assertEquals(53, left.textX); assertEquals(0, left.iconY)
        // onleft: the text first, the icon still centred in the box.
        val right = Gfx.batteryBox(48, 48, 50, 30, visible = true, fixed = false, align = "right", offsetX = 0)
        assertEquals(55, right.iconX); assertEquals(0, right.textX)
        assertEquals(158 / 2, right.iconX + 48 / 2 + 0)
        // Silky: 88x48 fixed with offsetX 26: as wide as the icon.
        val silky = Gfx.batteryBox(88, 48, 40, 30, visible = true, fixed = true, align = "left", offsetX = 26)
        assertEquals(88, silky.w); assertEquals(26, silky.textX)
        // Small icons sit left in a 48 px box, odd sizes are made even.
        val small = Gfx.batteryBox(32, 32, 50, 30, visible = false, fixed = false, align = "left", offsetX = 0)
        assertEquals(48, small.w); assertEquals(48, small.h); assertEquals(0, small.iconX); assertEquals(8, small.iconY)
        assertEquals(94, Gfx.batteryBox(93, 47, 0, 0, visible = false, fixed = false, align = "left", offsetX = 0).w)
        // Text taller than the icon: its height; else (OnionOS's slip) the icon's width.
        assertEquals(58, Gfx.batteryBox(129, 30, 40, 57, visible = true, fixed = true, align = "left", offsetX = 0).h)
        assertEquals(130, Gfx.batteryBox(129, 64, 40, 30, visible = true, fixed = true, align = "left", offsetX = 0).h)
        // Centred text with a negative odd difference truncates towards zero, as in C.
        assertEquals(-1, Gfx.batteryBox(48, 48, 51, 30, visible = true, fixed = true, align = "center", offsetX = 0).textX)
    }

    @Test fun screenSizedBatteryGoesWithItsBand() {
        // win98's taskbar battery (rows 444..470 of 480) stays at the bottom of a 557 px canvas.
        assertEquals(107f, Gfx.batteryAnchorY(444, 470, 557f), 0.01f)
        assertEquals(30f, Gfx.batteryAnchorY(444, 470, 480f), 0.01f)
        assertEquals(30f, Gfx.batteryAnchorY(6, 54, 557f), 0f)
        // System 9's spans the screen: it goes with the content.
        assertEquals(69f, Gfx.batteryAnchorY(0, 480, 557.4f), 0f)
    }

    @Test fun onionMainMenuGeometry() {
        // 24x14 dots, 4 items: x 262, 29 apart (Silky, Blueprint); 10x10 and 6 items: 275, 15 apart.
        assertEquals(262, Gfx.menuDotX(0, 4, 29)); assertEquals(291, Gfx.menuDotX(1, 4, 29))
        assertEquals(275, Gfx.menuDotX(0, 6, 15))
        // 156x278 in slot 0 at (10, 91); 156x328 at (10, 66); 120 wide centred (27); 794 wide at -309.
        assertEquals(10f, Gfx.slotIconX(10f, 156), 0f); assertEquals(91, Gfx.slotIconY(278))
        assertEquals(66, Gfx.slotIconY(328)); assertEquals(27f, Gfx.slotIconX(10f, 120), 0f)
        assertEquals(10f - 319f, Gfx.slotIconX(10f, 794), 0f); assertEquals(-20, Gfx.slotIconY(500))
    }

    @Test fun wifiSignalBars() {
        assertEquals(0, Gfx.wifiBars(-127))
        assertEquals(1, Gfx.wifiBars(-100))
        assertEquals(1, Gfx.wifiBars(-90))
        assertEquals(2, Gfx.wifiBars(-80))
        assertEquals(3, Gfx.wifiBars(-65))
        assertEquals(4, Gfx.wifiBars(-55))
        assertEquals(4, Gfx.wifiBars(-30))
    }

    @Test fun drawnTilesUseTheThemeGeneratorsColours() {
        // MainUI's accent #3A96FF gives the generator's tile colours (within rounding).
        val a = 0xFF3A96FF.toInt()
        fun rgb(c: Int) = Triple((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
        assertEquals(Triple(63, 150, 255), rgb(io.github.marsinsanity.mainui.ui.tileTop(a)))
        assertEquals(Triple(20, 62, 150), rgb(io.github.marsinsanity.mainui.ui.tileBottom(a)))
        assertEquals(Triple(85, 220, 255), rgb(io.github.marsinsanity.mainui.ui.tileHighlight(a)))
    }

    @Test fun selectionBarsKeepTheirEnds() {
        assertEquals(24, Gfx.selectionCap(640))  // MainUI's and Onion's 640 px bars
        assertEquals(48, Gfx.selectionCap(1280)) // drawn for a 2x screen: the same 24 design px
        assertEquals(6, Gfx.selectionCap(160))   // proportional for other sizes
        assertEquals(1, Gfx.selectionCap(8))     // tiny ones: at least a pixel
        assertEquals(1, Gfx.selectionCap(2))
    }
}
