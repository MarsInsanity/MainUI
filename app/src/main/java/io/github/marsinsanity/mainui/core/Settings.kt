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
    /** "menu" = OnionOS 1x4 main menu, "carousel" = P60 style system carousel as home. */
    var homeLayout by str("home_layout", "menu")
    /** "grid" = OnionOS games grid, "carousel" = P60 style carousel. */
    var systemsLayout by str("systems_layout", "grid")
    /** "cover", "stretch" or "fit" for 640x480 theme backgrounds on non 4:3 screens. */
    var backgroundFit by str("background_fit", "cover")
    var smoothScaling by bool("smooth_scaling", true)
    var showRecents by bool("show_recents", true)
    var showFavorites by bool("show_favorites", true)
    var showExpert by bool("show_expert", true)
    var showEmptySystems by bool("show_empty_systems", false)
    var showUnknownFolders by bool("show_unknown_folders", false)
    var showAndroidSystem by bool("show_android_system", true)
    var showSearchTile by bool("show_search_tile", true)
    var showGameArt by bool("show_game_art", true)
    var uiSounds by bool("ui_sounds", true)
    var bgm by bool("bgm", false)
    var showClock by bool("show_clock", false)

    // ---- input ---------------------------------------------------------------------
    var swapAB by bool("swap_ab", false)
    /** "home" (accessibility HOME intercept), "select_start", "l3_r3" (root input listener) or "off". */
    var ingameTrigger by str("ingame_trigger", "home")
    /** What HOME does while MainUI is in front: "menu_then_gs", "gs", "menu". */
    var homeInMainUI by str("home_in_mainui", "menu_then_gs")
    /** Long press HOME in-game opens Game Switcher instead of the in-game menu. */
    var longHomeGameSwitcher by bool("long_home_gs", true)

    // ---- library -------------------------------------------------------------------
    var autoSortAndroidGames by bool("auto_sort_android_games", true)
    var lastPackageReconcile by long("last_pkg_reconcile", 0L)
    /** Extra rom roots, one absolute path per line. */
    var extraRomRoots by str("extra_rom_roots", "")
    /** "auto", "sd" or "internal": where MainUI keeps Themes / config / screenshots. */
    var dataLocation by str("data_location", "auto")

    // ---- emulation -----------------------------------------------------------------
    var raPackageOverride by str("ra_package", "")
    var autoSaveOnExit by bool("auto_save_on_exit", true)
    var pauseOnMenu by bool("pause_on_menu", true)

    // ---- scraper -------------------------------------------------------------------
    var ssUser by str("ss_user", "")
    var ssPassword by str("ss_password", "")
    var ssDevId by str("ss_dev_id", "")
    var ssDevPassword by str("ss_dev_password", "")
    var ssMedia by str("ss_media", "box-2D")
    var ssRegions by str("ss_regions", "us,wor,eu,ss,jp")
    var ssLanguage by str("ss_language", "en")
    /** "sd" or "internal" */
    var ssImageStorage by str("ss_image_storage", "sd")
    var ssOnlyMissing by bool("ss_only_missing", true)
    var ssWriteGamelist by bool("ss_write_gamelist", true)
    var ssThreadsOverride by int("ss_threads_override", 0)
    var ssMaxImageWidth by int("ss_max_width", 640)

    // ---- misc ----------------------------------------------------------------------
    var setupDone by bool("setup_done", false)
    var mainMenuIndex by int("main_menu_index", 0)
    var systemsIndex by int("systems_index", 0)
}
