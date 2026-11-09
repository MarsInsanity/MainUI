# MainUI for Android

An OnionOS / Miyoo MainUI style frontend and home screen for Android handhelds,
built for the **Retroid Pocket Mini V2** (rooted with Magisk). It keeps what
Android does well (fast standalone emulators, RetroArch, Android games and apps)
and puts a Miyoo Mini style interface in front of it, so you rarely need the
stock launcher.

> Status: first public version. It builds and the unit tests pass, but it has
> not been tested on real Retroid hardware yet. The emulator launch recipes in
> particular need checking on a device; see [Known limitations](#known-limitations).

## Features

| # | Feature | How it works |
|---|---------|--------------|
| 1 | **OnionOS theme support** | Reads Onion `config.json` (title / hint / list / grid / currentpage / total / batteryPercentage / hideLabels / frame), with the same defaults and fallback rules as Onion's `theme_loadFromPath()`. Uses `skin/*.png`, `skin/extra/*`, `icons/*.png`, `icons/sel/*.png`, `icons/app/*.png`, `sound/change.wav`, `sound/bgm.mp3` and `preview.png`. Anything a theme lacks is drawn by MainUI so partial themes still look complete. |
| 2 | **OnionOS layout** | Header and footer bars, 1x4 main menu with page dots, 4x2 games grid, 60 px small / 90 px large lists anchored like Onion's, `A SELECT  B BACK` hints, `n/total` status. Optional **P60 style carousel** for the systems view and/or the home screen. |
| 3 | **dArkOS folder structure** | `roms/<system>/` with dArkOS folder names for 120+ systems (generated from the dArkOS wiki). Artwork from `gamelist.xml`, `images/`, `media/images/`, `Imgs/` (Onion) and more, on either storage volume. Full `gamelist.xml` parser and writer (names, hidden games, images, metadata). |
| 4 | **ScreenScraper scraper** | Multi-threaded: uses the `maxthreads` your ScreenScraper account allows (more threads for donors), honours per-minute limits, CRC/MD5/SHA1 matching, region and language priority, 8 media types. Saves images to the **SD card or internal storage** and updates `gamelist.xml`. Runs as a foreground service. |
| 5 | **Emulator / core detection** | Detects RetroArch (aarch64 / 32-bit / Play Store builds) and its installed cores (root), plus 19 standalone emulators. Per-system and per-game core selection like Onion (`SELECT` on a game > Core). |
| 6 | **Rom search** | Search tile in the games grid (or `Y` on most screens): on-screen keyboard, results across every system and Android games. |
| 7 | **Game Switcher** | Onion's GameSwitcher: full screen screenshots of recent games, `LEFT/RIGHT` browse, `A` resume, `X` remove, `Y` view mode, `SELECT` play time, `UP/DOWN` brightness, `gs-top-bar` / `gs-bottom-bar` / `gs-legend` / `lum0-10` theme art. |
| 8 | **Apps menu** | Every launchable Android app with its icon. `Y` hides an app; hidden apps live in their own **Hidden apps** menu. `SELECT` for app info, uninstall, move to Android games. |
| 9 | **Theme Switcher** | Full screen previews, applies instantly, installs `.zip` themes dropped into a `Themes` folder. |
| 10 | **Android system** | An "Android" system for native games. Add games by hand or let MainUI sort apps that declare themselves games into it automatically on install. |
| 11 | **Process manager** | Lists apps MainUI launched first (current game marked), with memory and CPU. Switch to, force stop one, or stop everything MainUI launched (root). |
| 12 | **In-game menu** | Press **Home** in a game: a Miyoo style menu over the paused game with **Continue, Save, Load, Native Menu, Game Switcher, Exit Game**. Save/Load support slots 0-9 with thumbnails for RetroArch. Hold Home for the Game Switcher. |

## Install

1. Build (see below) or download the APK and install it.
2. Open MainUI. The setup screen walks you through:
   - **All files access** (required, to read roms and themes).
   - **Set MainUI as home app** so the Home button returns to it.
   - **In-game menu overlay**: Android Settings > Accessibility > *MainUI in-game menu*.
   - **Root**: grant MainUI superuser in Magisk (core detection, process manager, key injection, force stop).
3. In **Settings > Emulators & cores**, run **Configure RetroArch for MainUI** once. It turns on
   RetroArch's network command interface, which the in-game menu uses for pause / save / load / exit.

## Storage layout

```
<SD card or internal storage>/
├── roms/                    dArkOS layout (also roms2/)
│   ├── gba/
│   │   ├── Game.gba
│   │   ├── gamelist.xml     names, descriptions, image paths (ES / Skraper / MainUI scraper)
│   │   └── images/Game.png  artwork (also media/images, Imgs, downloaded_images, boxart)
│   ├── psx/ snes/ nds/ ...
│   └── bios/
├── Themes/                  OnionOS themes (folders or .zip)
└── MainUI/
    ├── config/              recents, favorites, Android games, hidden apps, core choices, play time
    ├── Screenshots/         Game Switcher screenshots
    ├── States/              save slot thumbnails
    ├── Fonts/               extra fonts themes may reference
    ├── theme_overrides/     like Onion's Saves/CurrentProfile/theme (config.json + skin/ overrides)
    └── emulators.json       optional: your own emulator launch recipes
```

Settings > Library & storage > **Create dArkOS folders** creates every rom folder for you.
Folder names come from the [dArkOS emulator list](https://github.com/christianhaitian/dArkOS/wiki/dArkOS-Emulators-and-Ports-information);
`tools/gen_systems.py` regenerates `app/src/main/assets/systems.json` from that wiki page.
Android-only systems were added with the same naming: `ps2`, `wii`, `switch`.

When a game exists on both the SD card and internal storage the lists merge them.
Artwork is looked up at the same relative path on every volume, so images scraped to
internal storage work for roms on the SD card and the other way round.

## Themes

Copy an OnionOS theme folder (or its `.zip`) into `Themes/` and pick it in **Apps > Theme Switcher**.
Most themes from the [OnionUI theme repository](https://github.com/OnionUI/Themes) should work.

Differences from a Miyoo Mini, due to the screen:

- The Retroid Pocket Mini V2 screen (1240x1080) is narrower than 4:3. MainUI keeps the theme's
  640 px width and gains about 77 px of height (a 640x557 canvas). Header and footer stay at the
  top and bottom, content is centred, and lists show one extra row.
- Backgrounds are cropped to fill the screen by default (Settings > User interface > Background fit:
  Crop / Stretch / Fit).
- Fonts that Onion ships (`/mnt/SDCARD/miyoo/app/...`) are matched by file name. BPreplay, Exo 2 and
  Adobe Blank are bundled. Helvetica and Microsoft YaHei are not, because of their licences, so the
  system font is used instead. You can drop font files into `MainUI/Fonts/`.
- Theme authors can add an optional `"mainui": { "accent": "#RRGGBB", "panel": "#AARRGGBB", "background": "#RRGGBB" }`
  block to colour the elements MainUI draws itself.

## Controls

| Where | Button | Action |
|-------|--------|--------|
| Everywhere | D-pad / left stick | Move (held = repeat) |
| | A / B | Select / Back (swap in Settings > Controls) |
| | L1 / R1 | Page up / down |
| | L2 / R2 | Jump to previous / next letter |
| | Home | Main menu, then Game Switcher (configurable) |
| Main menu / grid | SELECT | Game Switcher (grid: system options) |
| | Y | Search |
| | START (grid) | Rescan roms |
| Game list | Y | Toggle favorite |
| | SELECT | Game options: favorite, core for this game, system core, scrape, info |
| Game Switcher | A / B / X / Y | Resume / back / remove / view mode |
| | SELECT / UP / DOWN | Play time / brightness |
| In game | Home | In-game menu (hold: Game Switcher) |
| Touch | tap / swipe / long press | Select / move / SELECT |

## Emulators

RetroArch cores come from the dArkOS list (default core first), followed by any other
installed core whose `.info` file lists a matching extension. Standalone emulators with
built-in launch recipes:

DraStic, melonDS, PPSSPP, DuckStation, NetherSX2/AetherSX2, Dolphin, Dolphin MMJR2,
Azahar, Citra, Lime3DS, Redream, Flycast, Yaba Sanshiro 2, Mupen64Plus FZ,
MAME4droid, Eden, Citron, Sudachi, yuzu.

Recipes live in `app/src/main/assets/emulators.json`. To fix or add one without rebuilding,
copy that file to `MainUI/emulators.json` and edit it (Settings > Emulators & cores, `Y` rescans).
Placeholders: `%ROM%`, `%ROMURI%`, `%ROMPROVIDER%` (content:// uri), `%ROMSAF%`
(Storage Access Framework uri, needs the emulator to have access to the folder),
`%BASENAME%`, `%CORE%`, `%CORE_PATH%`, `%RA_CONFIG%`.

### In-game menu: how it works

- An accessibility service watches which app is in front. Only while a game MainUI launched
  is in front does it consume the Home key. Everywhere else Home behaves normally.
- It screenshots the game, pauses it, and draws the menu in an overlay on top of the game.
- **RetroArch**: pause, Save/Load slot 0-9, Native Menu (RetroArch's quick menu) and Exit
  (with auto save) go through RetroArch's UDP command interface on `127.0.0.1:55355`.
- **Standalone emulators**: *Native Menu* sends Back, which opens the pause menu in nearly every
  Android emulator. *Exit* force stops the emulator (root) or kills it once it is in the background
  (no root). Save/Load only appear when you give the emulator hotkeys in `emulators.json`
  (`"ingame": {"save": "keys:KEYCODE_F1", "load": "keys:KEYCODE_F4"}`) that you also mapped
  inside the emulator. They are injected with root.
- Without the accessibility service, pressing Home still opens the same menu inside MainUI,
  without the live screenshot.
- Alternative triggers: Select+Start or L3+R3 (Settings > Controls). These read input through
  root `getevent`, so they add no latency, but the game also receives the buttons.

Accessibility key filtering sends every key press through the service before it reaches the game.
The handler returns straight away for anything but Home, but if you notice input lag, switch the
trigger to Select+Start or L3+R3 and turn the overlay service off.

## Scraper

ScreenScraper requires a **developer ID** for every application that uses its API. Developer
IDs are free; ask the ScreenScraper team for one through the forum on [screenscraper.fr](https://www.screenscraper.fr).
Enter it under Apps > Scraper > Developer ID, or build it into the APK:

```properties
# local.properties (not committed)
screenscraper.devid=yourDevId
screenscraper.devpassword=yourDevPassword
```

Then add your normal ScreenScraper username and password. The scraper reads your account's
`maxthreads` and runs that many workers (anonymous: 1). Images are written to
`roms/<system>/images/<rom>.png` on the volume chosen under **Save images to**, and `gamelist.xml`
is updated (a `gamelist.xml.bak` is kept the first time). Scraping keeps running in the background
with a notification. `SELECT` on a system or game scrapes just that.

## Building

Requirements: JDK 17+, Android SDK with platform 35.

```bash
./gradlew assembleRelease
```

```bash
./gradlew testDebugUnitTest
```

The release APK is signed with the debug key so it can be sideloaded directly:
`app/build/outputs/apk/release/app-release.apk`.

## Known limitations

- **Not yet tested on hardware.** In particular the standalone emulator recipes (activity names and
  extras) are based on what those emulators publicly document and on other frontends' configs. Some
  will need adjusting; use `MainUI/emulators.json`.
- ScreenScraper system IDs in `systems.json` were mapped by hand; a few rare systems may be wrong
  or missing (`null` = not scraped).
- RetroArch core detection needs root (cores live in RetroArch's private storage). Without root every
  recommended core is offered and marked `?`.
- `SAVE_STATE_SLOT` / `LOAD_STATE_SLOT` need RetroArch 1.17 or newer; older versions fall back to
  stepping slots.
- Ports (PortMaster) are Linux-only and are not shown.
- No Wi-Fi / Bluetooth / volume settings yet. Settings > Android settings opens the stock ones. Replacing
  more of the stock UI is the next step.

## Licence

GPL-3.0 (see `LICENSE`). Bundled fonts are under the SIL Open Font License (`app/src/main/assets/fonts/OFL-*.txt`).
No Miyoo or OnionOS theme art is bundled; the default look is drawn in code.
