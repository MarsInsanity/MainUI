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
}
