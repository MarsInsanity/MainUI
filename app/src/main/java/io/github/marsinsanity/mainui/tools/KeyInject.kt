@file:JvmName("KeyInject")

package io.github.marsinsanity.mainui.tools

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent

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
 * Only up to Android 11: since Android 12 every injected event arrives from device -1 whatever
 * device it names. MainUI itself uses the [daemon]'s uinput keyboard.
 */
/** Linux key codes of the keys the virtual keyboard has: F keys and modifiers. */
val LINUX_KEYS = mapOf("F1" to 59, "F2" to 60, "F3" to 61, "F4" to 62, "F5" to 63, "F6" to 64,
    "F7" to 65, "F8" to 66, "F9" to 67, "F10" to 68, "F11" to 87, "F12" to 88,
    // Modifiers for combinations ("SHIFT+F7"); still no letter keys, so not a typing keyboard.
    "SHIFT" to 42, "CTRL" to 29, "ALT" to 56)

external fun nativeOpen(name: String, keys: IntArray): Int
external fun nativePress(fd: Int, keys: IntArray, holdMs: Int): Boolean
external fun nativeClose(fd: Int)
external fun nativeNudge(name: String, vendor: Int, product: Int): Int

/**
 * `daemon <path of libmainuiinput.so>`: creates one virtual keyboard ("MainUI Keyboard", F keys
 * only) and keeps it while stdin is open, pressing keys on request: lines like "F1 250"
 * (key[+key] holdMs), answered "ok" / "error ...". One lasting device matters: RetroArch
 * registers at most three keyboards per run.
 */
private fun daemon(lib: String) {
    System.load(lib)
    val fd = nativeOpen("MainUI Keyboard", LINUX_KEYS.values.toIntArray())
    if (fd < 0) { println("error uinput $fd"); return }
    println("ready")
    try {
        while (true) {
            val line = readLine() ?: break
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.firstOrNull().isNullOrEmpty()) continue
            val codes = parts[0].split('+').map { LINUX_KEYS[it.uppercase().removePrefix("KEYCODE_")] }
            if (codes.any { it == null }) { println("error unknown key ${parts[0]}"); continue }
            nativePress(fd, codes.filterNotNull().toIntArray(), parts.getOrNull(1)?.toIntOrNull() ?: 200)
            println("ok")
        }
    } finally {
        nativeClose(fd)
    }
}

/**
 * `inject <path of libmainuiinput.so>`: input as a real controller, without any device of its
 * own. "nudge <vendor> <product> <name>" moves one stick axis of that controller a hair and
 * back through its /dev/input node ([nativeNudge]), so the app in front hears from the real
 * controller where its sticks and triggers are now. Answers "ok <axis+1>", "none" (no axis it
 * can nudge unnoticed) or "error ...".
 *
 * Events injected through Android itself can't do this: since Android 12 they all arrive from
 * device -1 ("Virtual") whatever device they name, and RetroArch takes that for a new
 * controller and gives it a port (port 1 when it comes first).
 */
private fun injectDaemon(lib: String?) {
    val loaded = lib != null && runCatching { System.load(lib) }.isSuccess
    println("ready")
    while (true) {
        // (Only the line end goes: a controller's name may end in spaces, as sysfs has it.)
        val line = (readLine() ?: break).trimEnd('\r', '\n').trimStart()
        val parts = line.split(Regex(" +"), limit = 4)
        when (parts.firstOrNull()) {
            "nudge" -> {
                val vendor = parts.getOrNull(1)?.toIntOrNull()
                val product = parts.getOrNull(2)?.toIntOrNull()
                val name = parts.getOrNull(3)
                println(when {
                    !loaded -> "error no native library"
                    vendor == null || product == null || name.isNullOrEmpty() -> "error usage"
                    else -> runCatching { nativeNudge(name, vendor, product) }.fold(
                        { r -> if (r > 0) "ok $r" else if (r == 0) "none" else "error $r" },
                        { "error ${it.message}" })
                })
            }
            null, "" -> {}
            else -> println("error unknown command")
        }
    }
}

fun main(args: Array<String>) {
    if (args.firstOrNull() == "daemon") { daemon(args[1]); return }
    if (args.firstOrNull() == "inject") { injectDaemon(args.getOrNull(1)); return }
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
