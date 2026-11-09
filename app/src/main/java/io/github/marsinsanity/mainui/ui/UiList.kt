package io.github.marsinsanity.mainui.ui

import android.graphics.Bitmap

enum class ItemType { ACTION, TOGGLE, MULTI, INFO }

/** One row in an Onion style list. */
class UiItem(
    var label: String,
    var type: ItemType = ItemType.ACTION,
    var icon: (() -> Bitmap?)? = null,
    var value: Int = 0,
    var values: List<String> = emptyList(),
    /** Right aligned secondary text (core name, memory usage...). */
    var sub: String? = null,
    var disabled: Boolean = false,
    var preview: (() -> Bitmap?)? = null,
    var data: Any? = null,
    var onSelect: ((UiItem) -> Unit)? = null,
    var onChange: ((UiItem) -> Unit)? = null,
) {
    val checked get() = value == 1
}

/** Selection / scrolling state for a list, mirroring Onion's List component. */
class UiList(val large: Boolean = false) {
    val items = ArrayList<UiItem>()
    var active = 0
    var scroll = 0
    var visible = 6

    val current: UiItem? get() = items.getOrNull(active)
    val size get() = items.size

    fun set(newItems: List<UiItem>, keepIndex: Boolean = true) {
        val old = active
        items.clear(); items.addAll(newItems)
        active = if (keepIndex) old.coerceIn(0, maxOf(0, items.size - 1)) else 0
        ensureVisible()
    }

    fun ensureVisible() {
        if (items.isEmpty()) { active = 0; scroll = 0; return }
        active = active.coerceIn(0, items.size - 1)
        if (active < scroll) scroll = active
        if (active >= scroll + visible) scroll = active - visible + 1
        scroll = scroll.coerceIn(0, maxOf(0, items.size - visible))
    }

    /** Onion wraps around at the ends unless the key is auto-repeating. */
    fun down(repeat: Boolean): Boolean {
        if (items.isEmpty()) return false
        if (active < items.size - 1) active++ else if (!repeat) active = 0 else return false
        ensureVisible(); return true
    }

    fun up(repeat: Boolean): Boolean {
        if (items.isEmpty()) return false
        if (active > 0) active-- else if (!repeat) active = items.size - 1 else return false
        ensureVisible(); return true
    }

    fun pageDown(): Boolean {
        if (items.isEmpty() || active == items.size - 1) return false
        active = minOf(items.size - 1, active + visible); scroll = minOf(maxOf(0, items.size - visible), scroll + visible)
        ensureVisible(); return true
    }

    fun pageUp(): Boolean {
        if (items.isEmpty() || active == 0) return false
        active = maxOf(0, active - visible); scroll = maxOf(0, scroll - visible)
        ensureVisible(); return true
    }

    /** Jump to the next/previous first letter (L2/R2 like many frontends). */
    fun letter(forward: Boolean): Boolean {
        if (items.isEmpty()) return false
        val cur = items[active].label.firstOrNull()?.uppercaseChar() ?: return false
        if (forward) {
            for (i in active + 1 until items.size) if (items[i].label.firstOrNull()?.uppercaseChar() != cur) { active = i; ensureVisible(); return true }
        } else {
            var i = active - 1
            while (i >= 0 && items[i].label.firstOrNull()?.uppercaseChar() == cur) i--
            if (i < 0) return false
            val target = items[i].label.firstOrNull()?.uppercaseChar()
            while (i > 0 && items[i - 1].label.firstOrNull()?.uppercaseChar() == target) i--
            active = i; ensureVisible(); return true
        }
        return false
    }

    /** Handles left/right on MULTI and TOGGLE items. */
    fun change(delta: Int): Boolean {
        val it = current ?: return false
        if (it.disabled) return false
        when (it.type) {
            ItemType.MULTI -> {
                if (it.values.isEmpty()) return false
                it.value = (it.value + delta + it.values.size) % it.values.size
            }
            ItemType.TOGGLE -> it.value = if (it.value == 1) 0 else 1
            else -> return false
        }
        it.onChange?.invoke(it)
        return true
    }
}
