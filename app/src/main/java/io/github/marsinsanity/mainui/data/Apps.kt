package io.github.marsinsanity.mainui.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import io.github.marsinsanity.mainui.core.Settings
import io.github.marsinsanity.mainui.emu.EmulatorManager
import java.util.concurrent.ConcurrentHashMap

/** Android app list backed by PackageManager (the "Apps" menu and the Android system). */
class Apps(private val ctx: Context, private val library: Library, private val settings: Settings) {

    data class AppInfo(val pkg: String, val label: String, val isGame: Boolean, val installTime: Long)

    private val pm: PackageManager = ctx.packageManager
    private val iconCache = ConcurrentHashMap<String, Bitmap>()
    @Volatile private var cache: List<AppInfo>? = null

    fun invalidate() { cache = null }

    fun all(): List<AppInfo> {
        cache?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }
            .map { ai ->
                val install = runCatching { pm.getPackageInfo(ai.packageName, 0).firstInstallTime }.getOrDefault(0L)
                AppInfo(ai.packageName, pm.getApplicationLabel(ai).toString(), isGame(ai), install)
            }
            .sortedBy { it.label.lowercase() }
        cache = list
        return list
    }

    fun label(pkg: String): String = all().firstOrNull { it.pkg == pkg }?.label
        ?: runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    fun isInstalled(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun icon(pkg: String, sizePx: Int = 96): Bitmap? {
        iconCache[pkg]?.let { return it }
        val d = runCatching { pm.getApplicationIcon(pkg) }.getOrNull() ?: return null
        val b = d.toBitmap(sizePx)
        iconCache[pkg] = b
        return b
    }

    /** Android TV style banner if the app ships one (nice in the Android games list). */
    fun banner(pkg: String): Bitmap? = runCatching {
        val ai = pm.getApplicationInfo(pkg, 0)
        (ai.loadBanner(pm) ?: ai.loadLogo(pm))?.toBitmap(320)
    }.getOrNull()

    fun launchIntent(pkg: String): Intent? = pm.getLaunchIntentForPackage(pkg)
        ?: pm.getLeanbackLaunchIntentForPackage(pkg)

    /** Apps for the Apps menu: not hidden, not sorted into the Android system. */
    fun visibleApps(): List<AppInfo> {
        val hidden = library.hiddenApps()
        val games = library.androidGames()
        return all().filter { it.pkg !in hidden && it.pkg !in games }
    }

    fun hiddenApps(): List<AppInfo> {
        val hidden = library.hiddenApps()
        return all().filter { it.pkg in hidden }
    }

    fun androidGames(): List<AppInfo> {
        val games = library.androidGames()
        return all().filter { it.pkg in games }
    }

    /**
     * Auto sort newly installed games into the Android system. Runs on start
     * and on PACKAGE_ADDED. Emulators are never treated as games.
     */
    fun reconcileNewInstalls(emulators: EmulatorManager) {
        if (!settings.autoSortAndroidGames) { settings.lastPackageReconcile = System.currentTimeMillis(); return }
        val since = settings.lastPackageReconcile
        invalidate()
        // Emulators sorted in as games before MainUI knew them (another build's id): take them out.
        for (pkg in library.androidGames()) {
            val label = all().firstOrNull { it.pkg == pkg }?.label
            if (emulators.isEmulatorPackage(pkg, label)) library.setAndroidGame(pkg, false)
        }
        for (a in all()) {
            if (since != 0L && a.installTime <= since) continue
            if (since == 0L) {
                // First run: sort everything that declares itself a game.
                if (a.isGame && !emulators.isEmulatorPackage(a.pkg, a.label)) library.setAndroidGame(a.pkg, true)
            } else if (a.isGame && !emulators.isEmulatorPackage(a.pkg, a.label)) {
                library.setAndroidGame(a.pkg, true)
            }
        }
        settings.lastPackageReconcile = System.currentTimeMillis()
    }

    fun onPackageAdded(pkg: String, emulators: EmulatorManager) {
        invalidate()
        if (!settings.autoSortAndroidGames || emulators.isEmulatorPackage(pkg)) return
        val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return
        if (isGame(ai)) library.setAndroidGame(pkg, true)
    }

    @Suppress("DEPRECATION")
    private fun isGame(ai: ApplicationInfo): Boolean =
        ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
}

fun Drawable.toBitmap(size: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null && bitmap.width >= size) return bitmap
    val w = if (intrinsicWidth > 0) intrinsicWidth else size
    val h = if (intrinsicHeight > 0) intrinsicHeight else size
    val scale = size.toFloat() / maxOf(w, h)
    val bw = maxOf(1, (w * scale).toInt())
    val bh = maxOf(1, (h * scale).toInt())
    val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    setBounds(0, 0, bw, bh)
    draw(c)
    return b
}
