package io.github.marsinsanity.mainui.core

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Typed wrapper over SharedPreferences; every user facing option lives here. */
class Settings(ctx: Context) {
    val prefs: SharedPreferences = ctx.getSharedPreferences("mainui", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<() -> Unit>()

    fun onChange(l: () -> Unit) { listeners += l }
    private fun changed() = listeners.forEach { it() }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getBoolean(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply(); changed()
        }
    }

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any, String> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getString(key, def) ?: def
        override fun setValue(thisRef: Any, property: KProperty<*>, value: String) {
            prefs.edit().putString(key, value).apply(); changed()
        }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getInt(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Int) {
            prefs.edit().putInt(key, value).apply(); changed()
        }
    }

    private fun long(key: String, def: Long) = object : ReadWriteProperty<Any, Long> {
        override fun getValue(thisRef: Any, property: KProperty<*>) = prefs.getLong(key, def)
        override fun setValue(thisRef: Any, property: KProperty<*>, value: Long) {
            prefs.edit().putLong(key, value).apply()
        }
    }

    // ---- look & feel ---------------------------------------------------------------
    /** Absolute path of the active theme directory, empty = built-in theme. */
    var themePath by str("theme_path", "")
    /** "menu" = OnionOS 1x4 main menu, "row" = systems in one row of grid cells as home. */
    var homeLayout by str("home_layout", "menu")
    /** "grid" = OnionOS 4x2 games grid, "row" = the same cells in one scrolling row. */
    var systemsLayout by str("systems_layout", "grid")
    /** "cover", "stretch" or "fit" for 640x480 theme backgrounds on non 4:3 screens. */
    var backgroundFit by str("background_fit", "cover")
    var smoothScaling by bool("smooth_scaling", true)
    var showRecents by bool("show_recents", true)
    var showFavorites by bool("show_favorites", true)
    var showExpert by bool("show_expert", true)
    var showEmptySystems by bool("show_empty_systems", false)
    var showUnknownFolders by bool("show_unknown_folders", false)
    /** List games hidden with Hide ROM (and known BIOS sets) anyway, marked "hidden". */
    var showHiddenGames by bool("show_hidden_games", false)
    var showAndroidSystem by bool("show_android_system", true)
    /** The Ports system in Games even empty, before its ports folder exists too (adding a port creates it). Off: only with ports. */
    var showPortsSystem by bool("show_ports_system", true)
    var showSearchTile by bool("show_search_tile", true)
    var showGameArt by bool("show_game_art", true)
    var uiSounds by bool("ui_sounds", true)
    var bgm by bool("bgm", false)
    /** Vibration in MainUI's menus: "off", "light", "medium", "strong" (see Haptics). */
    var haptics by str("haptics", "light")
    var showClock by bool("show_clock", false)
    /** Clock in the top bar: "24" (14:05), "12" (2:05 PM) or "system" (Android's own setting). */
    var clockFormat by str("clock_format", "24")

    // ---- input ---------------------------------------------------------------------
    var swapAB by bool("swap_ab", false)
    /**
     * What a short Home press does while a game launched from MainUI is in front:
     * "menu" (in-game menu), "gs" (Game Switcher), "home" (go to MainUI like Android does), "none".
     */
    var homePress by str("home_press", "menu")
    /** Same choices for holding Home. */
    var homeHold by str("home_hold", "gs")
    /** How long Home (or Back) must be held (ms). */
    var homeHoldMs by int("home_hold_ms", 500)
    /** Holding Back anywhere: "processes" (process manager popup) or "none". Needs the overlay service. */
    var backHold by str("back_hold", "processes")
    /** Extra in-game menu trigger read through root getevent: "off", "select_start", "l3_r3". */
    var comboTrigger by str("combo_trigger", "off")
    /** What HOME does while MainUI is in front: "menu_then_gs", "gs", "menu". */
    var homeInMainUI by str("home_in_mainui", "menu_then_gs")
    /** Holding HOME while MainUI is in front: "gs" (Game Switcher) or "none". Needs the overlay service. */
    var homeHoldInMainUI by str("home_hold_in_mainui", "gs")

    // ---- library -------------------------------------------------------------------
    var autoSortAndroidGames by bool("auto_sort_android_games", true)
    var lastPackageReconcile by long("last_pkg_reconcile", 0L)
    /** Extra rom roots, one absolute path per line. */
    var extraRomRoots by str("extra_rom_roots", "")
    /** Game metadata source: "gamelist" (gamelist.xml), "logiqx" (Logiqx DAT) or "both". */
    var metadataSource by str("metadata_source", "both")
    /** "auto", "sd" or "internal": where MainUI keeps Themes / config / screenshots. */
    var dataLocation by str("data_location", "auto")

    // ---- updates ---------------------------------------------------------------------
    var updateAutoCheck by bool("update_auto_check", true)
    var updateLastCheck by long("update_last_check", 0L)
    /** versionName of a newer build found by the last check ("" = none). */
    var updateAvailable by str("update_available", "")

    // ---- emulation -----------------------------------------------------------------
    var raPackageOverride by str("ra_package", "")
    var autoSaveOnExit by bool("auto_save_on_exit", true)
    var pauseOnMenu by bool("pause_on_menu", true)
    /** Pause standalone emulators by freezing their process (SIGSTOP, needs root). */
    var freezeStandalone by bool("freeze_standalone", true)

    // ---- scraper -------------------------------------------------------------------
    var ssUser by str("ss_user", "")
    var ssPassword by str("ss_password", "")
    var ssDevId by str("ss_dev_id", "")
    var ssDevPassword by str("ss_dev_password", "")
    /** The software name sent to ScreenScraper (softname); use the one your developer ID was registered for. */
    var ssSoftName by str("ss_soft_name", "")
    /** Where box art comes from, in order: "ss" (ScreenScraper), "gametdb", "libretro", "launchbox", "thegamesdb". */
    var artSources by str("art_sources", "ss,gametdb,libretro,launchbox,thegamesdb")
    var ssMedia by str("ss_media", "box-2D")
    var ssRegions by str("ss_regions", "us,wor,eu,ss,jp")
    var ssLanguage by str("ss_language", "en")
    /** "sd" or "internal" */
    var ssImageStorage by str("ss_image_storage", "sd")
    var ssOnlyMissing by bool("ss_only_missing", true)
    /** Where the scraper writes metadata: "gamelist", "logiqx", "both" or "none". */
    var ssMetaFormat by str("ss_meta_format", "gamelist")
    var ssThreadsOverride by int("ss_threads_override", 0)
    var ssMaxImageWidth by int("ss_max_width", 640)

    // ---- misc ----------------------------------------------------------------------
    var setupDone by bool("setup_done", false)
    var mainMenuIndex by int("main_menu_index", 0)
    var systemsIndex by int("systems_index", 0)
}
