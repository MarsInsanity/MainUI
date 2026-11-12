package io.github.marsinsanity.mainui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads an OnionOS theme's own images for what MainUI draws that OnionOS has no image for (its
 * colours, the Emulators tile, X / Y button hints), so those look like the rest of the theme.
 * Pixels are ARGB ints, row by row.
 */
object ThemeArt {
    private fun a(c: Int) = c ushr 24
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    fun dist(x: Int, y: Int) = abs(r(x) - r(y)) + abs(g(x) - g(y)) + abs(b(x) - b(y))
    fun chroma(c: Int) = max(r(c), max(g(c), b(c))) - min(r(c), min(g(c), b(c)))
    fun luma(c: Int) = (r(c) * 299 + g(c) * 587 + b(c) * 114) / 1000

    /**
     * The most common colour of the clearly visible pixels (opaque, averaged within its bin of
     * similar colours), or null. [colourful]: only pixels with a clear hue count, and they must be
     * at least 2% of the visible ones. [away]: pixels within [minDist] of it are left out.
     */
    fun dominant(px: IntArray, colourful: Boolean = false, away: Int? = null, minDist: Int = 120): Int? {
        val n = IntArray(512); val sr = IntArray(512); val sg = IntArray(512); val sb = IntArray(512)
        var visible = 0
        for (c in px) {
            if (a(c) < 160) continue
            visible++
            if (colourful && chroma(c) < 48) continue
            if (away != null && dist(c, away) < minDist) continue
            val k = (r(c) shr 5 shl 6) or (g(c) shr 5 shl 3) or (b(c) shr 5)
            n[k]++; sr[k] += r(c); sg[k] += g(c); sb[k] += b(c)
        }
        var best = -1
        for (k in 0 until 512) if (n[k] > 0 && (best < 0 || n[k] > n[best])) best = k
        if (best < 0 || (colourful && n[best] * 50 < visible)) return null
        return rgb(sr[best] / n[best], sg[best] / n[best], sb[best] / n[best])
    }

    /** [c] darkened or lightened until white text on it and it on a dark screen both read. */
    fun usableAccent(c: Int): Int {
        val l = luma(c)
        if (l > 185) { val f = 185f / l; return rgb((r(c) * f).toInt(), (g(c) * f).toInt(), (b(c) * f).toInt()) }
        if (l < 60) {
            // (towards white: scaling a dark colour up would clip it)
            val f = (60f - l) / (255f - l)
            return rgb((r(c) + (255 - r(c)) * f + 0.5f).toInt(), (g(c) + (255 - g(c)) * f + 0.5f).toInt(), (b(c) + (255 - b(c)) * f + 0.5f).toInt())
        }
        return c or (0xFF shl 24)
    }

    /**
     * A main menu tile with its picture taken out: [card] (null when the tile is a picture
     * without a card behind it), where the picture was (fractions of the tile) and its main
     * colour, [ink].
     */
    class Tile(val card: IntArray?, val w: Int, val h: Int,
               val left: Float, val top: Float, val right: Float, val bottom: Float, val ink: Int)

    fun tile(px: IntArray, w: Int, h: Int): Tile? {
        var gl = w; var gt = h; var gr = -1; var gb = -1
        for (y in 0 until h) for (x in 0 until w) if (a(px[y * w + x]) >= 128) {
            gl = min(gl, x); gr = max(gr, x); gt = min(gt, y); gb = max(gb, y)
        }
        if (gr < 0) return null
        // A card (or a frame) fills the tile; a picture alone does not.
        if ((gr - gl + 1) * 10 < w * 7 || (gb - gt + 1) * 10 < h * 7) {
            return Tile(null, w, h, gl / w.toFloat(), gt / h.toFloat(), (gr + 1) / w.toFloat(), (gb + 1) / h.toFloat(),
                dominant(px, colourful = true) ?: dominant(px) ?: 0xFFFFFFFF.toInt())
        }
        // The card's colour on each row, from just inside its left and right edges where the two
        // agree; rows where the picture reaches an edge take the nearest row that agreed.
        val inset = max(3, w / 16)
        val rowL = IntArray(h) { -1 }; val rowR = IntArray(h) { -1 }
        val rowC = IntArray(h); val known = BooleanArray(h)
        for (y in 0 until h) {
            val o = y * w
            var l = -1; var rr = -1
            for (x in 0 until w) if (a(px[o + x]) >= 128) { if (l < 0) l = x; rr = x }
            if (l < 0 || rr - l <= 2 * inset + 2 || (rr - l + 1) * 10 < (gr - gl + 1) * 6) continue
            rowL[y] = l; rowR[y] = rr
            val cl = px[o + l + inset]; val cr = px[o + rr - inset]
            if (dist(cl, cr) <= 36 && abs(a(cl) - a(cr)) <= 24) {
                rowC[y] = ((a(cl) + a(cr)) / 2 shl 24) or (((r(cl) + r(cr)) / 2) shl 16) or (((g(cl) + g(cr)) / 2) shl 8) or ((b(cl) + b(cr)) / 2)
                known[y] = true
            }
        }
        if (known.none { it }) return null
        for (y in 0 until h) if (rowL[y] >= 0 && !known[y]) {
            var d = 1
            while (true) {
                if (y - d >= 0 && known[y - d]) { rowC[y] = rowC[y - d]; break }
                if (y + d < h && known[y + d]) { rowC[y] = rowC[y + d]; break }
                d++
            }
        }
        val card = px.copyOf()
        val ink = ArrayList<Int>()
        var l = w; var t = h; var rr = -1; var bb = -1
        for (y in 0 until h) {
            if (rowL[y] < 0) continue
            val o = y * w
            for (x in rowL[y] + inset..rowR[y] - inset) {
                val c = px[o + x]
                if (a(c) >= 128 && dist(c, rowC[y]) + abs(a(c) - a(rowC[y])) > 60) {
                    ink += c; l = min(l, x); rr = max(rr, x); t = min(t, y); bb = max(bb, y)
                }
                card[o + x] = rowC[y]
            }
        }
        val inkColour = (if (ink.isEmpty()) null else dominant(ink.toIntArray(), colourful = true) ?: dominant(ink.toIntArray()))
            ?: if (luma(rowC[h / 2]) > 140) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
        return if (rr < 0) Tile(card, w, h, 0.22f, 0.12f, 0.78f, 0.55f, inkColour)
        else Tile(card, w, h, l / w.toFloat(), t / h.toFloat(), (rr + 1) / w.toFloat(), (bb + 1) / h.toFloat(), inkColour)
    }

    /**
     * The A button picture with its letter taken out ([blank]), the letter's colour ([ink]),
     * where it sat and how tall it was (fractions of the picture), and the colour behind it
     * ([fill]). [ring]: the button is an outline, the letter standing free inside it.
     */
    class Button(val blank: IntArray, val w: Int, val h: Int, val fill: Int, val ink: Int,
                 val cx: Float, val cy: Float, val inkH: Float, val ring: Boolean)

    fun button(px: IntArray, w: Int, h: Int): Button? {
        val fill = dominant(px) ?: return null
        var l = w; var t = h; var rr = -1; var bb = -1
        for (y in 0 until h) for (x in 0 until w) if (a(px[y * w + x]) >= 128) {
            l = min(l, x); rr = max(rr, x); t = min(t, y); bb = max(bb, y)
        }
        // The letter is in the middle of the button; its edge (an outline, a shadow) is not.
        val mx = (rr - l + 1) * 18 / 100; val my = (bb - t + 1) * 18 / 100
        fun inner(x: Int, y: Int) = x >= l + mx && x <= rr - mx && y >= t + my && y <= bb - my
        val letter = BooleanArray(w * h)
        // An outline button: opaque parts lying wholly in the middle, not joined to the outline.
        val comp = IntArray(w * h) { -1 }
        val stack = IntArray(w * h)
        var ring = false
        for (start in 0 until w * h) {
            if (comp[start] >= 0 || a(px[start]) < 128) continue
            var sp = 0; stack[sp++] = start; comp[start] = start
            val members = ArrayList<Int>()
            var allInner = true
            while (sp > 0) {
                val i = stack[--sp]; members += i
                val x = i % w; val y = i / w
                if (!inner(x, y)) allInner = false
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1))
                    if (j >= 0 && comp[j] < 0 && a(px[j]) >= 128) { comp[j] = start; stack[sp++] = j }
            }
            if (allInner && members.size < w * h / 2) { ring = true; for (i in members) letter[i] = true }
        }
        var ink: Int
        if (ring) {
            ink = dominant(px.filterIndexed { i, _ -> letter[i] }.toIntArray()) ?: fill
        } else {
            // A filled button: the letter is drawn in another colour, or cut out of it.
            ink = dominant(px, away = fill) ?: (if (luma(fill) > 140) 0xFF202020.toInt() else 0xFFFFFFFF.toInt())
            val outside = BooleanArray(w * h)
            var sp = 0
            for (x in 0 until w) for (y in intArrayOf(0, h - 1)) { val i = y * w + x; if (a(px[i]) < 128 && !outside[i]) { outside[i] = true; stack[sp++] = i } }
            for (y in 0 until h) for (x in intArrayOf(0, w - 1)) { val i = y * w + x; if (a(px[i]) < 128 && !outside[i]) { outside[i] = true; stack[sp++] = i } }
            while (sp > 0) {
                val i = stack[--sp]; val x = i % w; val y = i / w
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1))
                    if (j >= 0 && !outside[j] && a(px[j]) < 128) { outside[j] = true; stack[sp++] = j }
            }
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (!inner(x, y)) continue
                val c = px[i]
                if (if (a(c) < 128) !outside[i] else dist(c, ink) < dist(c, fill)) letter[i] = true
            }
        }
        var ll = w; var lt = h; var lr = -1; var lb = -1
        for (i in 0 until w * h) if (letter[i]) { val x = i % w; val y = i / w; ll = min(ll, x); lr = max(lr, x); lt = min(lt, y); lb = max(lb, y) }
        val blank = px.copyOf()
        if (lr >= 0) {
            // (one pixel more all round: the letter's soft edge)
            for (y in max(0, lt - 1)..min(h - 1, lb + 1)) for (x in max(0, ll - 1)..min(w - 1, lr + 1)) {
                val i = y * w + x
                val near = letter[i] || (x > 0 && letter[i - 1]) || (x < w - 1 && letter[i + 1]) || (y > 0 && letter[i - w]) || (y < h - 1 && letter[i + w])
                if (near && (ring || inner(x, y))) blank[i] = if (ring) 0 else fill
            }
        }
        if (lr < 0) return Button(blank, w, h, fill, ink, (l + rr + 1) / 2f / w, (t + bb + 1) / 2f / h, (bb - t + 1) * 0.5f / h, ring)
        return Button(blank, w, h, fill, ink, (ll + lr + 1) / 2f / w, (lt + lb + 1) / 2f / h, (lb - lt + 1) / h.toFloat(), ring)
    }
}
