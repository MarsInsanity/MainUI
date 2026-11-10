@file:JvmName("KeyInject")

package io.github.marsinsanity.mainui.tools

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Presses a key as if a real keyboard did, for apps that ignore keys from Android's virtual
 * keyboard device (id -1, what `input keyevent` and accessibility actions use). RetroArch is
 * one: it registers keyboards by their device and drops keys from -1.
 *
 * Run as root through app_process, like scrcpy's server:
 *   CLASSPATH=<MainUI apk> app_process /system/bin io.github.marsinsanity.mainui.tools.KeyInject KEYCODE_F1 [holdMs] [deviceId]
 *
 * The key is held for holdMs (default 150) so it is seen on at least one frame, and stamped
 * with a keyboard-only input device that exists (e.g. "gpio-keys"), unless one is given.
 */
/** Linux key codes of the keys the virtual keyboard has: F keys and modifiers. */
val LINUX_KEYS = mapOf("F1" to 59, "F2" to 60, "F3" to 61, "F4" to 62, "F5" to 63, "F6" to 64,
    "F7" to 65, "F8" to 66, "F9" to 67, "F10" to 68, "F11" to 87, "F12" to 88,
    // Modifiers for combinations ("SHIFT+F7"); still no letter keys, so not a typing keyboard.
    "SHIFT" to 42, "CTRL" to 29, "ALT" to 56)

external fun nativeOpen(name: String, keys: IntArray): Int
external fun nativePress(fd: Int, keys: IntArray, holdMs: Int): Boolean
external fun nativeClose(fd: Int)

/** The controller axes [axes] carries, in this order (sticks, d-pad hat, triggers). */
val CONTROLLER_AXES = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
    MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
    MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS)

/**
 * One controller motion event from [deviceId] with [values] for [CONTROLLER_AXES], to the window
 * in front. A game only learns a trigger or stick moved from such events: one released while
 * MainUI's menu had focus went to MainUI, and the game still thinks it is held.
 */
private fun axes(inject: (InputEvent) -> Boolean, deviceId: Int, values: List<Float>): Boolean {
    val now = SystemClock.uptimeMillis()
    val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
    val coords = arrayOf(MotionEvent.PointerCoords().apply {
        CONTROLLER_AXES.forEachIndexed { i, a -> setAxisValue(a, values.getOrElse(i) { 0f }) }
    })
    val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1f, 1f, deviceId, 0,
        InputDevice.SOURCE_JOYSTICK, 0)
    return try { inject(e) } finally { e.recycle() }
}

/**
 * `daemon <path of libmainuiinput.so>`: creates one virtual keyboard ("MainUI Keyboard", F keys
 * only) and keeps it while stdin is open, pressing keys on request: lines like "F1 250"
 * (key[+key] holdMs), answered "ok" / "error ...". One lasting device matters: RetroArch
 * registers at most three keyboards per run. "axes <deviceId> <v>..." sends a controller's
 * axis values (see [axes]).
 */
private fun daemon(lib: String) {
    System.load(lib)
    val fd = nativeOpen("MainUI Keyboard", LINUX_KEYS.values.toIntArray())
    if (fd < 0) { println("error uinput $fd"); return }
    val inject by lazy { injector() }
    println("ready")
    try {
        while (true) {
            val line = readLine() ?: break
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.firstOrNull().isNullOrEmpty()) continue
            if (parts[0] == "axes") {
                val id = parts.getOrNull(1)?.toIntOrNull()
                val ok = id != null && runCatching { axes(inject, id, parts.drop(2).map { it.toFloatOrNull() ?: 0f }) }.getOrDefault(false)
                println(if (ok) "ok" else "error axes")
                continue
            }
            val codes = parts[0].split('+').map { LINUX_KEYS[it.uppercase().removePrefix("KEYCODE_")] }
            if (codes.any { it == null }) { println("error unknown key ${parts[0]}"); continue }
            nativePress(fd, codes.filterNotNull().toIntArray(), parts.getOrNull(1)?.toIntOrNull() ?: 200)
            println("ok")
        }
    } finally {
        nativeClose(fd)
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "daemon") { daemon(args[1]); return }
    // `axes <deviceId> <v>...`: one controller event (see [axes]), without making a virtual keyboard.
    if (args.firstOrNull() == "axes") {
        val id = args.getOrNull(1)?.toIntOrNull()
        val ok = id != null && runCatching { axes(injector(), id, args.drop(2).map { it.toFloatOrNull() ?: 0f }) }.getOrDefault(false)
        println(if (ok) "ok" else "error axes")
        return
    }
    if (args.firstOrNull() == "list") {
        for (id in InputDevice.getDeviceIds()) InputDevice.getDevice(id)?.let { d ->
            println("$id\t${d.name}\tsources=0x${Integer.toHexString(d.sources)}\tkeyboardType=${d.keyboardType}\tvirtual=${d.isVirtual}")
        }
        println("chosen: ${keyboardDevice()}")
        return
    }
    val keys = args.getOrNull(0)?.split('+')?.map { k ->
        k.toIntOrNull() ?: KeyEvent.keyCodeFromString(if (k.startsWith("KEYCODE_")) k else "KEYCODE_$k")
    }?.filter { it > 0 }
    if (keys.isNullOrEmpty()) { System.err.println("usage: KeyInject KEYCODE_X[+KEYCODE_Y] [holdMs] [deviceId]"); System.exit(2); return }
    val hold = args.getOrNull(1)?.toLongOrNull() ?: 150L
    val device = args.getOrNull(2)?.toIntOrNull() ?: keyboardDevice()
    val inject = injector()
    val down = SystemClock.uptimeMillis()
    fun send(action: Int, code: Int) {
        val e = KeyEvent(down, SystemClock.uptimeMillis(), action, code, 0, 0, device, 0,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
        if (!inject(e)) System.err.println("injection refused for $code")
    }
    for (k in keys) send(KeyEvent.ACTION_DOWN, k)
    Thread.sleep(hold)
    for (k in keys.reversed()) send(KeyEvent.ACTION_UP, k)
    println("sent ${args[0]} on device $device")
}

/** A real keyboard-only device: what apps see as a hardware keyboard (gpio-keys, a USB keyboard...). */
fun keyboardDevice(): Int {
    val devices: List<InputDevice> = InputDevice.getDeviceIds().toList()
        .mapNotNull { id: Int -> InputDevice.getDevice(id) }.filter { d -> !d.isVirtual && d.id >= 0 }
    return (devices.firstOrNull { it.sources == InputDevice.SOURCE_KEYBOARD && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC }
        ?: devices.firstOrNull { it.sources == InputDevice.SOURCE_KEYBOARD }
        ?: devices.firstOrNull { it.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD })?.id ?: -1
}

/** InputManager(Global).injectInputEvent through reflection (hidden API; allowed for app_process). */
private fun injector(): (InputEvent) -> Boolean {
    val cls = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
        ?: Class.forName("android.hardware.input.InputManager")
    val im = cls.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
    val m = cls.getDeclaredMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
    return { e -> m.invoke(im, e, 0) as Boolean }   // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
}
