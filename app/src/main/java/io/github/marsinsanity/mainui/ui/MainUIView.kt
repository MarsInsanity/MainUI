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
    fun scenes(): List<Scene> = stack.toList()

    override fun toast(message: String, millis: Long) {
        toastText = message
        toastUntil = System.currentTimeMillis() + millis
        invalidate()
        handler.postDelayed({ invalidate() }, millis + 20)
    }

    override fun <T> io(work: () -> T, done: (T) -> Unit): Job = scope.launch {
        val r = withContext(Dispatchers.IO) { work() }
        done(r)
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
        handler.removeCallbacksAndMessages(null)
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

    /** Feeds a logical button press (used by the overlay service and tests). */
    fun dispatch(b: Btn, repeat: Boolean) {
        val top = stack.lastOrNull() ?: return
        if (top.onKey(b, repeat)) {
            if (!repeat || b.isDirection) MainUI.themes.click()
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val b = mapKey(event.keyCode) ?: return super.dispatchKeyEvent(event)
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

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
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

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val s = width / logicalWidth
            val top = stack.lastOrNull() ?: return false
            if (top.onTap(e.x / s, e.y / s)) { MainUI.themes.click(); invalidate() }
            return true
        }
        override fun onLongPress(e: MotionEvent) = dispatch(Btn.SELECT, false)
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) > abs(vy)) dispatch(if (vx < 0) Btn.RIGHT else Btn.LEFT, false)
            else dispatch(if (vy < 0) Btn.DOWN else Btn.UP, false)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    companion object {
        const val REPEAT_DELAY = 300L
        const val REPEAT_INTERVAL = 70L
    }
}
