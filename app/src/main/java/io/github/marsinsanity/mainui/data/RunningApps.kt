package io.github.marsinsanity.mainui.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The running apps the process manager offers: one row per app (all of its processes together),
 * and only apps you open yourself. Android's own parts (System UI, the phone and Bluetooth stacks,
 * Google Play services, the keyboard, the launcher, accessibility services...) are left out:
 * stopping them breaks things until they restart.
 */
object RunningApps {
    data class App(val pkg: String, val pids: List<Int>, val rssKb: Long, val cpu: Double, val fromMainUI: Boolean, val isSession: Boolean)

    /** Never offered, launcher icon or not. */
    val PROTECTED = setOf(
        "android", "com.android.systemui", "com.android.phone", "com.android.bluetooth", "com.android.nfc",
        "com.android.shell", "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.providers.media.module", "com.google.android.providers.media.module",
        "com.android.launcher3", "com.google.android.apps.nexuslauncher", "com.android.networkstack",
        "com.google.android.networkstack", "com.android.se", "com.android.server.telecom",
    )

    /**
     * Groups `ps -A -o PID,USER,RSS,%CPU,NAME` output (header included) by app.
     * Only app users count (u0_a123, u10_a45: not root, system or isolated u0_i processes);
     * [keep] decides which packages are listed. Sorted: the game being played, then apps MainUI
     * started, then by memory.
     */
    fun parse(ps: List<String>, keep: (String) -> Boolean, launched: Set<String>, sessionPkg: String?): List<App> {
        class Acc(val pids: MutableList<Int> = ArrayList(), var rss: Long = 0, var cpu: Double = 0.0)
        val byPkg = LinkedHashMap<String, Acc>()
        for (line in ps) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) continue
            val user = parts[1]
            if (!Regex("u\\d+_a\\d+").matches(user)) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val pkg = parts[4].substringBefore(':')
            if (!pkg.contains('.') || !keep(pkg)) continue
            val a = byPkg.getOrPut(pkg) { Acc() }
            a.pids += pid
            a.rss += parts[2].toLongOrNull() ?: 0
            a.cpu += parts[3].toDoubleOrNull() ?: 0.0
        }
        return byPkg.map { (pkg, a) -> App(pkg, a.pids, a.rss, a.cpu, pkg in launched, pkg == sessionPkg) }
            .sortedWith(compareBy<App>({ !it.isSession }, { !it.fromMainUI }, { -it.rssKb }))
    }

    private val recentRe = Regex("""(?:\bA=\d+:|\bI=|realActivity=\{?)([A-Za-z0-9_.]+)""")

    /** Packages with a task in Android's recent apps, from `dumpsys activity recents`. */
    fun recentPackages(dump: String): Set<String> =
        recentRe.findAll(dump).map { it.groupValues[1].trimEnd('.') }.filter { it.contains('.') }.toSet()

    /**
     * Which packages are apps you can safely stop: they have a launcher icon, and are not MainUI,
     * a persistent or protected system package, the current keyboard, an enabled accessibility
     * service, or a launcher that came with the device (frontends you installed, like ES-DE, stay).
     * Apps that came with the device (Messages, Phone, Search...) also run in the background on
     * their own, so they are only listed once you opened them: when they are in [opened] (recent
     * apps, or started from MainUI). Apps you installed are always listed.
     */
    fun killable(ctx: Context, opened: Set<String>): (String) -> Boolean {
        val pm = ctx.packageManager
        val cr = ctx.contentResolver
        val skip = HashSet(PROTECTED)
        skip += ctx.packageName
        runCatching {
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS)?.split(':')?.forEach { skip += it.substringBefore('/') }
            Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)?.let { skip += it.substringBefore('/') }
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.forEach { skip += it.substringBefore('/') }
        }
        runCatching {
            val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            for (r in homes) {
                val ai = r.activityInfo?.applicationInfo ?: continue
                if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) skip += ai.packageName
            }
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let { skip += it }
        }
        val cache = HashMap<String, Boolean>()
        return { pkg ->
            cache.getOrPut(pkg) {
                if (pkg in skip) false
                else {
                    val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
                    ai != null && ai.flags and ApplicationInfo.FLAG_PERSISTENT == 0 &&
                        (ai.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pkg in opened) &&
                        (pm.getLaunchIntentForPackage(pkg) != null || pm.getLeanbackLaunchIntentForPackage(pkg) != null)
                }
            }
        }
    }
}
