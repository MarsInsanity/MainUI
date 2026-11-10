package io.github.marsinsanity.mainui.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Logical buttons, named after the Miyoo Mini's. */
enum class Btn { UP, DOWN, LEFT, RIGHT, A, B, X, Y, L1, R1, L2, R2, SELECT, START, MENU, L3, R3;
    val isDirection get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}

interface SceneHost {
    val hostContext: Context
    val scope: CoroutineScope
    /** Logical canvas size (640 wide on screens narrower than 4:3, 480 tall otherwise). */
    val logicalWidth: Float
    val logicalHeight: Float
    fun push(scene: Scene)
    fun pop(scene: Scene? = null)
    fun replace(scene: Scene)
    /** Pops everything above the root scene. */
    fun popToRoot()
    fun top(): Scene?
    fun invalidate()
    fun toast(message: String, millis: Long = 1800)
    /** Runs [work] on a background thread and [done] back on the UI thread. */
    fun <T> io(work: () -> T, done: (T) -> Unit = {}): Job
    /** Called when the last scene is popped (used by the in-game overlay). */
    fun finishHost()
}

/**
 * A full screen state (main menu, game list, game switcher...). Scenes draw in
 * Onion's 640x480 based logical coordinates; see [Gfx].
 */
abstract class Scene(val host: SceneHost) {
    /** Overlay scenes (dialogs, pop menus, keyboard) draw on top of the scene below. */
    open val isOverlay: Boolean = false
    /** False hides the header battery etc. for full screen scenes like the game switcher. */
    open fun onEnter() {}
    /** Called when this scene becomes the top scene again. */
    open fun onResume() {}
    open fun onExit() {}
    abstract fun draw(g: Gfx)
    /** Return true if handled. [repeat] is true for auto-repeat while held. */
    open fun onKey(b: Btn, repeat: Boolean): Boolean = false
    open fun onKeyUp(b: Btn) {}
    /** Tap in logical coordinates. */
    open fun onTap(x: Float, y: Float): Boolean = false
    /** A touch held at [x], [y]; false: it is SELECT (options). */
    open fun onLongPress(x: Float, y: Float): Boolean = false
    /** Finger drag in logical px (positive dy = finger moving down). Return true if it scrolled something. */
    open fun onDrag(dx: Float, dy: Float): Boolean = false
    open fun onDragEnd() {}
    /** Fling after a handled drag, logical px per second. */
    open fun onFling(vx: Float, vy: Float): Boolean = false
    /**
     * Swipe that no drag handled. [direction] is the D-pad direction that
     * matches it (swipe left = RIGHT = next). Defaults to pressing that direction.
     */
    open fun onSwipe(direction: Btn): Boolean = onKey(direction, false)
    /** Return true while an animation is running so the host keeps redrawing. */
    open fun animating(now: Long): Boolean = false

    protected val ctx: Context get() = host.hostContext
    fun invalidate() = host.invalidate()
    fun close() = host.pop(this)
}
