package io.github.marsinsanity.mainui

import io.github.marsinsanity.mainui.ui.ItemType
import io.github.marsinsanity.mainui.ui.UiItem
import io.github.marsinsanity.mainui.ui.UiList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiListTest {
    private fun list(n: Int, visible: Int = 6) = UiList().apply {
        this.visible = visible
        set((0 until n).map { UiItem("Item $it") }, false)
    }

    @Test fun wrapsAroundOnSinglePressButNotOnRepeat() {
        val l = list(3)
        assertTrue(l.up(false)); assertEquals(2, l.active)
        assertTrue(l.down(false)); assertEquals(0, l.active)
        l.active = 2
        assertFalse(l.down(true)); assertEquals(2, l.active)
    }

    @Test fun scrollFollowsSelection() {
        val l = list(20, visible = 6)
        repeat(7) { l.down(false) }
        assertEquals(7, l.active)
        assertEquals(2, l.scroll)
        l.pageDown(); assertEquals(13, l.active)
        l.pageUp(); assertEquals(7, l.active)
    }

    @Test fun letterJumpSkipsToNextInitial() {
        val l = UiList().apply { visible = 6; set(listOf("Alpha", "Apple", "Banana", "Berry", "Cherry").map { UiItem(it) }, false) }
        assertTrue(l.letter(true)); assertEquals(2, l.active)
        assertTrue(l.letter(true)); assertEquals(4, l.active)
        assertTrue(l.letter(false)); assertEquals(2, l.active)
        assertTrue(l.letter(false)); assertEquals(0, l.active)
    }

    @Test fun multiValueCyclesAndNotifies() {
        var seen = -1
        val item = UiItem("Mode", ItemType.MULTI, values = listOf("a", "b", "c"), onChange = { seen = it.value })
        val l = UiList().apply { set(listOf(item), false) }
        l.change(-1); assertEquals(2, seen)
        l.change(1); assertEquals(0, seen)
    }

    @Test fun keepsIndexWithinBoundsWhenItemsShrink() {
        val l = list(10)
        l.active = 9
        l.set(listOf(UiItem("only")))
        assertEquals(0, l.active)
    }
}
