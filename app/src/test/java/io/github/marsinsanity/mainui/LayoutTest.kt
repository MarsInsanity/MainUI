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
}
