package io.github.marsinsanity.mainui

import io.github.marsinsanity.mainui.theme.ThemeArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeArtTest {
    private val clear = 0
    private val orange = 0xFFFF9B3A.toInt()
    private val dark = 0xFF2A2A2E.toInt()
    private val blue = 0xFF3965E1.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val grey = 0xFFA0A0A0.toInt()

    private fun image(w: Int, h: Int, at: (Int, Int) -> Int) = IntArray(w * h) { at(it % w, it / w) }

    @Test fun cardKeepsItsColourAndLosesThePicture() {
        // An orange card filling the tile with a blue picture in its upper half.
        val w = 156; val h = 278
        val px = image(w, h) { x, y -> if (x in 40..110 && y in 50..130) blue else orange }
        val t = ThemeArt.tile(px, w, h)!!
        val card = t.card!!
        assertTrue(card.all { it == orange })
        assertEquals(blue, t.ink)
        assertEquals(40f / w, t.left, 0.01f); assertEquals(111f / w, t.right, 0.01f)
        assertEquals(50f / h, t.top, 0.01f); assertEquals(131f / h, t.bottom, 0.01f)
    }

    @Test fun pictureReachingOneEdgeTakesTheNeighbouringRows() {
        val w = 156; val h = 278
        // (the picture runs into the card's left edge on rows 100..120)
        val px = image(w, h) { x, y -> if (y in 100..120 && x < 60) blue else dark }
        val card = ThemeArt.tile(px, w, h)!!.card!!
        val inset = w / 16
        for (y in 100..120) for (x in inset until w - inset) assertEquals(dark, card[y * w + x])
    }

    @Test fun pictureWithoutCardHasNoCard() {
        val w = 156; val h = 278
        val px = image(w, h) { x, y -> if (x in 30..120 && y in 60..140) white else clear }
        val t = ThemeArt.tile(px, w, h)!!
        assertNull(t.card)
        assertEquals(30f / w, t.left, 0.01f); assertEquals(141f / h, t.bottom, 0.01f)
        assertNull(ThemeArt.tile(IntArray(w * h), w, h))
    }

    @Test fun filledButtonLosesItsLetter() {
        // A grey disc with a dark "I" letter.
        val n = 54
        val px = image(n, n) { x, y ->
            val dx = x - 27; val dy = y - 27
            when {
                dx * dx + dy * dy > 26 * 26 -> clear
                x in 24..30 && y in 18..36 -> dark
                else -> grey
            }
        }
        val b = ThemeArt.button(px, n, n)!!
        assertFalse(b.ring)
        assertEquals(grey, b.fill); assertEquals(dark, b.ink)
        assertTrue(b.blank.none { it == dark })
        assertEquals(0.5f, b.cx, 0.03f); assertEquals(0.5f, b.cy, 0.03f)
        assertEquals(19f / n, b.inkH, 0.02f)
    }

    @Test fun cutOutLetterIsFilled() {
        val n = 54
        val px = image(n, n) { x, y ->
            val dx = x - 27; val dy = y - 27
            if (dx * dx + dy * dy > 26 * 26 || (x in 24..30 && y in 18..36)) clear else grey
        }
        val b = ThemeArt.button(px, n, n)!!
        assertFalse(b.ring)
        for (y in 18..36) for (x in 24..30) assertEquals(grey, b.blank[y * n + x])
        assertEquals(0xFF202020.toInt(), b.ink) // (dark on a light button)
    }

    @Test fun outlineButtonKeepsItsRing() {
        // A white ring with a free-standing white letter inside.
        val n = 54
        val px = image(n, n) { x, y ->
            val dx = x - 27; val dy = y - 27; val d = dx * dx + dy * dy
            when {
                d in 22 * 22..26 * 26 -> white
                x in 24..30 && y in 18..36 -> white
                else -> clear
            }
        }
        val b = ThemeArt.button(px, n, n)!!
        assertTrue(b.ring)
        assertEquals(white, b.ink)
        for (y in 18..36) for (x in 24..30) assertEquals(clear, b.blank[y * n + x])
        assertEquals(white, b.blank[27 * n + 2])
    }

    @Test fun dominantColour() {
        val px = IntArray(1000) { if (it < 700) dark else orange }
        assertEquals(dark, ThemeArt.dominant(px))
        assertEquals(orange, ThemeArt.dominant(px, colourful = true))
        assertEquals(orange, ThemeArt.dominant(px, away = dark))
        // (a speck of colour is not the theme's colour)
        assertNull(ThemeArt.dominant(IntArray(1000) { if (it < 990) dark else orange }, colourful = true))
        assertNull(ThemeArt.dominant(IntArray(10)))
        assertNotNull(ThemeArt.button(IntArray(4) { white }, 2, 2))
    }

    @Test fun accentReadsOnScreen() {
        assertTrue(ThemeArt.luma(ThemeArt.usableAccent(white)) <= 186)
        assertTrue(ThemeArt.luma(ThemeArt.usableAccent(0xFF020A5D.toInt())) >= 55)
        assertEquals(orange, ThemeArt.usableAccent(orange))
    }
}
