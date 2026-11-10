package io.github.marsinsanity.mainui.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import io.github.marsinsanity.mainui.MainUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The single View that renders MainUI. Owns the scene stack and translates
 * gamepad / keyboard / touch input into [Btn] presses with Onion-like
 * auto-repeat. Used both by the launcher activity and by the in-game overlay.
 */
@SuppressLint("ViewConstructor")
class MainUIView(context: Context, private val onEmpty: () -> Unit = {}) : View(context), SceneHost {

    private val stack = ArrayList<Scene>()
    private val gfx = Gfx()
    override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    private var toastText: String? = null
    private var toastUntil = 0L

    override val hostContext: Context get() = context
    override val logicalWidth get() = Gfx.logicalSize(maxOf(1, width), maxOf(1, height)).first
    override val logicalHeight get() = Gfx.logicalSize(maxOf(1, width), maxOf(1, height)).second

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // MainUI draws its own selection. Without this, Android lays its translucent
        // "focused view" highlight over the whole screen whenever a controller is used,
        // which washes every color out (strongly on some OEM themes).
        defaultFocusHighlightEnabled = false
        setBackgroundColor(Color.BLACK)
    }

    // ---- scene stack ---------------------------------------------------------------------

    override fun push(scene: Scene) {
        stack += scene
        scene.onEnter()
        invalidate()
    }

    override fun pop(scene: Scene?) {
        if (stack.isEmpty()) return
        val s = scene ?: stack.last()
        val wasTop = stack.lastOrNull() === s
        if (!stack.remove(s)) return
        s.onExit()
        if (stack.isEmpty()) { onEmpty(); return }
        if (wasTop) stack.last().onResume()
        invalidate()
    }

    override fun replace(scene: Scene) {
        stack.removeLastOrNull()?.onExit()
        push(scene)
    }

    override fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.size - 1).onExit()
        stack.lastOrNull()?.onResume()
        invalidate()
    }

    fun setRoot(scene: Scene) {
        while (stack.isNotEmpty()) stack.removeAt(stack.size - 1).onExit()
        push(scene)
    }

    override fun top(): Scene? = stack.lastOrNull()

    /**
     * Redraw. Scenes also call this from background work (download / unpack / scrape progress).
     * View.invalidate() is only safe on the UI thread: from another thread it can schedule a frame
     * while the UI thread does too and leave a sync barrier in the main queue, after which the app
     * no longer handles input ("MainUI isn't responding" while downloading cores). Elsewhere the
     * redraw is posted to the UI thread.
     */
    override fun invalidate() {
        if (Looper.myLooper() == Looper.getMainLooper()) super.invalidate() else postInvalidate()
    }
    fun scenes(): List<Scene> = stack.toList()

    override fun toast(message: String, millis: Long) {
        toastText = message
        toastUntil = System.currentTimeMillis() + millis
        invalidate()
        handler.postDelayed({ invalidate() }, millis + 20)
    }

    override fun <T> io(work: () -> T, done: (T) -> Unit): Job = scope.launch {
        // A failing background task (I/O error, bad file...) must never take MainUI down.
        val r = try {
            withContext(Dispatchers.IO) { work() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e("MainUI", "background task failed", e)
            toast("Error: ${e.message ?: e.javaClass.simpleName}".take(120), 3000)
            return@launch
        }
        try { done(r) } catch (e: Throwable) {
            android.util.Log.e("MainUI", "task callback failed", e)
            toast("Error: ${e.message ?: e.javaClass.simpleName}".take(120), 3000)
        }
        invalidate()
    }

    override fun finishHost() = onEmpty()

    // ---- drawing --------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        gfx.begin(canvas, width, height)
        try {
            // Draw from the last opaque scene upwards so overlays show what's below.
            var start = stack.size - 1
            while (start > 0 && stack[start].isOverlay) start--
            for (i in maxOf(0, start) until stack.size) stack[i].draw(gfx)
            val t = toastText
            if (t != null) {
                if (System.currentTimeMillis() < toastUntil) gfx.infoPanel(t) else toastText = null
            }
        } finally {
            gfx.end()
        }
        val now = System.currentTimeMillis()
        if (gfx.wantsFrame || stack.any { it.animating(now) }) postInvalidateOnAnimation()
        else if (gfx.redrawIn > 0) postInvalidateDelayed(gfx.redrawIn)
    }

    // ---- battery --------------------------------------------------------------------------

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0) BatteryState.level = level * 100 / maxOf(1, scale)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            BatteryState.charging = status == BatteryManager.BATTERY_STATUS_CHARGING
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        requestFocus()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { context.unregisterReceiver(batteryReceiver) }
        releaseAll()
        handler.removeCallbacksAndMessages(null)
    }

    /** Window focus callback (the overlay uses it to know when the game lost focus). */
    var onWindowFocus: ((Boolean) -> Unit)? = null

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Releases never arrive once the window lost focus: don't leave a button "held" (it would
        // keep auto-repeating, or swallow its next press).
        if (!hasWindowFocus) releaseAll()
        onWindowFocus?.invoke(hasWindowFocus)
    }

    fun destroy() {
        while (stack.isNotEmpty()) stack.removeAt(stack.size - 1).onExit()
        scope.cancel()
    }

    // ---- input ------------------------------------------------------------------------------

    private val held = HashSet<Btn>()
    private var repeatBtn: Btn? = null
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val b = repeatBtn ?: return
            dispatch(b, true)
            handler.postDelayed(this, REPEAT_INTERVAL)
        }
    }

    /**
     * [timerRepeat]: synthesize auto-repeat ourselves. Only needed for axis input
     * (hat / stick); key events already get Android's own key repeat.
     */
    private fun press(b: Btn, timerRepeat: Boolean) {
        if (!held.add(b)) return
        dispatch(b, false)
        if (timerRepeat && (b.isDirection || b == Btn.L1 || b == Btn.R1)) {
            repeatBtn = b
            handler.removeCallbacks(repeatRunnable)
            handler.postDelayed(repeatRunnable, REPEAT_DELAY)
        }
    }

    private fun release(b: Btn) {
        if (!held.remove(b)) return
        if (repeatBtn == b) { repeatBtn = null; handler.removeCallbacks(repeatRunnable) }
        stack.lastOrNull()?.onKeyUp(b)
    }

    /** Lets go of everything held (focus lost, detached, input cancelled). */
    private fun releaseAll() {
        for (b in held.toList()) release(b)
        downBtn.clear()
        axisState.clear()
        repeatBtn = null
        handler.removeCallbacks(repeatRunnable)
    }

    /** Feeds a logical button press (used by the overlay service and tests). */
    fun dispatch(b: Btn, repeat: Boolean) {
        val top = stack.lastOrNull() ?: return
        if (top.onKey(b, repeat)) {
            if (!repeat || b.isDirection) MainUI.themes.click()
            if (b.isDirection || b == Btn.L1 || b == Btn.R1 || b == Btn.L2 || b == Btn.R2) io.github.marsinsanity.mainui.core.Haptics.move()
            else if (!repeat) io.github.marsinsanity.mainui.core.Haptics.press()
            invalidate()
        }
    }

    fun mapKey(code: Int): Btn? {
        val swap = MainUI.settings.swapAB
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> Btn.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Btn.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Btn.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Btn.RIGHT
            KeyEvent.KEYCODE_BUTTON_A -> if (swap) Btn.B else Btn.A
            KeyEvent.KEYCODE_BUTTON_B -> if (swap) Btn.A else Btn.B
            KeyEvent.KEYCODE_BUTTON_X -> if (swap) Btn.Y else Btn.X
            KeyEvent.KEYCODE_BUTTON_Y -> if (swap) Btn.X else Btn.Y
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Btn.A
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL -> Btn.B
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_PAGE_UP -> Btn.L1
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_PAGE_DOWN -> Btn.R1
            KeyEvent.KEYCODE_BUTTON_L2 -> Btn.L2
            KeyEvent.KEYCODE_BUTTON_R2 -> Btn.R2
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_TAB -> Btn.SELECT
            KeyEvent.KEYCODE_BUTTON_START -> Btn.START
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU -> Btn.MENU
            KeyEvent.KEYCODE_BUTTON_THUMBL -> Btn.L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> Btn.R3
            else -> null
        }
    }

    /** The button each key's DOWN was, so its UP releases the same one (Swap A/B toggled meanwhile). */
    private val downBtn = HashMap<Int, Btn>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val b = (if (event.action == KeyEvent.ACTION_UP) downBtn.remove(event.keyCode) else null)
            ?: mapKey(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) downBtn[event.keyCode] = b
        when (event.action) {
            KeyEvent.ACTION_DOWN -> when {
                event.repeatCount == 0 -> press(b, timerRepeat = false)
                b.isDirection || b == Btn.L1 || b == Btn.R1 -> dispatch(b, true)
            }
            KeyEvent.ACTION_UP -> release(b)
        }
        return true
    }

    // Analog stick, hat (d-pad reported as axes) and analog triggers.
    private val axisState = HashMap<Btn, Boolean>()

    /**
     * The controllers (device ids) whose triggers / sticks / hat moved here. A game behind MainUI
     * misses these changes: an R2 let go while MainUI had focus stays "held" in the game, so the
     * controller is made to report its axes again when the game gets focus back (see
     * [takeMovedControllers] and OverlayService.handBackControllers).
     */
    private val movedControllers = HashSet<Int>()

    /** The controllers whose axes moved here since the last call. */
    fun takeMovedControllers(): Set<Int> = HashSet(movedControllers).also { movedControllers.clear() }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE && event.deviceId >= 0) movedControllers += event.deviceId
            // The stick / hat stream was cut off (focus moved to a game): nothing stays held.
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) { releaseAll(); return true }
            if (event.action == MotionEvent.ACTION_MOVE) {
                val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                val sx = event.getAxisValue(MotionEvent.AXIS_X)
                val sy = event.getAxisValue(MotionEvent.AXIS_Y)
                val x = if (abs(hx) > 0.5f) hx else if (abs(sx) > 0.6f) sx else 0f
                val y = if (abs(hy) > 0.5f) hy else if (abs(sy) > 0.6f) sy else 0f
                axis(Btn.LEFT, x < -0.5f); axis(Btn.RIGHT, x > 0.5f)
                axis(Btn.UP, y < -0.5f); axis(Btn.DOWN, y > 0.5f)
                val lt = maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE))
                val rt = maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS))
                axis(Btn.L2, lt > 0.5f); axis(Btn.R2, rt > 0.5f)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun axis(b: Btn, down: Boolean) {
        if (axisState[b] == down) return
        axisState[b] = down
        if (down) press(b, timerRepeat = true) else release(b)
    }

    // ---- touch ----------------------------------------------------------------------------
    // Taps: footer hints first (tap "B BACK" to go back), then the header's left
    // corner (back), then the scene. Drags scroll lists; flings either keep a list
    // scrolling or turn into a page / item change on grids, switchers and rows.

    private var dragging = false
    private var dragHandled = false
    private var flung = false
    private var dragX = 0f
    private var dragY = 0f

    private fun toLogical(v: Float) = v / (width / logicalWidth)
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        /** This touch was a long press (it already did its thing: no tap when it lifts). */
        private var longPressed = false

        override fun onDown(e: MotionEvent): Boolean {
            dragging = false; dragHandled = false; flung = false; dragX = 0f; dragY = 0f
            longPressed = false
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean = tap(e)

        // A quick second tap is reported as a double tap instead of a tap; treat it as one
        // (first tap selects a row, second opens it). onDoubleTap gets the FIRST tap's position,
        // so the second tap is handled from its own events: at its own place, when it lifts.
        override fun onDoubleTap(e: MotionEvent): Boolean = true

        private var secondX = 0f
        private var secondY = 0f
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { secondX = e.x; secondY = e.y }
                MotionEvent.ACTION_UP -> {
                    // Moved away (a quick swipe after a tap): not a tap.
                    // Held (a long press opened options under the finger): not a tap on them.
                    if (!longPressed && abs(e.x - secondX) <= touchSlop && abs(e.y - secondY) <= touchSlop) tap(e)
                }
            }
            return true
        }

        private fun tap(e: MotionEvent): Boolean {
            val x = toLogical(e.x); val y = toLogical(e.y)
            val top = stack.lastOrNull() ?: return false
            gfx.hintAt(x, y)?.let { b -> dispatch(b, false); return true }
            if (top.onTap(x, y)) { MainUI.themes.click(); io.github.marsinsanity.mainui.core.Haptics.press(); invalidate(); return true }
            if (y < Gfx.HEADER && x < logicalWidth * 0.25f && stack.size > 1 && !top.isOverlay) {
                dispatch(Btn.B, false); return true
            }
            return false
        }

        override fun onLongPress(e: MotionEvent) {
            if (dragging) return
            longPressed = true
            // The scene first (a held key on the touch keyboard types it), else SELECT (options).
            val top = stack.lastOrNull()
            if (top?.onLongPress(toLogical(e.x), toLogical(e.y)) == true) invalidate() else dispatch(Btn.SELECT, false)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            dragging = true
            dragX -= toLogical(dx); dragY -= toLogical(dy)
            val top = stack.lastOrNull() ?: return false
            if (top.onDrag(-toLogical(dx), -toLogical(dy))) { dragHandled = true; invalidate() }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val top = stack.lastOrNull() ?: return false
            flung = true
            val lvx = toLogical(vx); val lvy = toLogical(vy)
            if (dragHandled) { if (top.onFling(lvx, lvy)) invalidate(); return true }
            val b = if (abs(vx) > abs(vy)) (if (vx < 0) Btn.RIGHT else Btn.LEFT) else (if (vy < 0) Btn.DOWN else Btn.UP)
            if (top.onSwipe(b)) { MainUI.themes.click(); io.github.marsinsanity.mainui.core.Haptics.move(); invalidate() }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val top = stack.lastOrNull()
            if (dragging) top?.onDragEnd()
            // A slow swipe never becomes a fling: still treat it as a swipe.
            if (dragging && !flung && !dragHandled && event.actionMasked == MotionEvent.ACTION_UP && top != null &&
                maxOf(abs(dragX), abs(dragY)) > SWIPE_MIN) {
                val b = if (abs(dragX) > abs(dragY)) (if (dragX < 0) Btn.RIGHT else Btn.LEFT) else (if (dragY < 0) Btn.DOWN else Btn.UP)
                if (top.onSwipe(b)) { MainUI.themes.click(); io.github.marsinsanity.mainui.core.Haptics.move(); invalidate() }
            }
            dragging = false
        }
        return handled
    }

    companion object {
        const val REPEAT_DELAY = 300L
        const val REPEAT_INTERVAL = 70L
        /** Logical px a drag must travel to count as a swipe. */
        const val SWIPE_MIN = 60f
    }
}
