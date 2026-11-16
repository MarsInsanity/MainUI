# Moving MainUI development to your PC

This file lists what your PC needs: what to install, what to download and where from, how to build
and test, and how to connect an Android device over ADB so Claude Code can test on it. It also
covers how the CI, releases and emulator forks work, and the rules this project follows. Give it to
the Claude Code session on your PC as its first read.

Last updated 2026-11-15 (MainUI 0.1.123).

**Two devices, two keys:**
- **Retroid Pocket 2+**: the test device on the PC's USB. It runs builds from the PC, signed with
  the PC's debug key. Claude installs and tests on it.
- **Retroid Pocket Mini V2**: your own device. It keeps running the GitHub Actions releases, signed
  with the release key, and updates through Settings > Updates.

**All development happens on `main`.** No other branches.

**What to test on the Pocket 2+:** [TEST_PLAN.md](TEST_PLAN.md), bug areas in order of importance.

---

## 1. What to install on the PC

| Tool | Why | Where to get it |
|---|---|---|
| **Git** | cloning and committing | https://git-scm.com/downloads (on Windows this includes Git Bash) |
| **JDK 17** (up to 24, not 25 or newer) | Gradle / Android build | https://adoptium.net (Temurin 17, what CI uses), or the JDK bundled with Android Studio. See the JDK note below the table |
| **Android SDK**: platform 35, build-tools 35, NDK 27.2.12479018, platform-tools (adb) | build and install the app | Easiest: **Android Studio** (https://developer.android.com/studio), then SDK Manager. Without Studio: "Command line tools only" from the same page. See the SDK note below the table |
| **Claude Code** | your agent on the PC | https://code.claude.com/docs (macOS / Linux / WSL: `curl -fsSL https://claude.ai/install.sh \| bash`; Windows PowerShell: `irm https://claude.ai/install.ps1 \| iex`) |
| **GitHub CLI `gh`** (recommended) | watch CI runs, read releases, start workflows | https://cli.github.com, then `gh auth login` |
| **Python 3.13** + Pillow 12.3.0, numpy 2.5.3 | only for the themes repo (its kit draws every theme) | https://www.python.org/downloads, then `pip install -r kit/requirements.txt` in the themes repo |
| **scrcpy** (optional) | see and control the test device's screen live | https://github.com/Genymobile/scrcpy |

**JDK:** Gradle 8.14.3 runs on Java 17 to 24. On Java 25 or newer every build fails at once, with
only the Java version (for example `25.0.5`) under "What went wrong". If your default `java` is
25+, keep it and point Gradle at a JDK 17 just for MainUI. The Temurin 17 `.zip`
(not the `.msi`) unpacks anywhere without changing PATH or JAVA_HOME. Then run Gradle with
`JAVA_HOME` set to that folder, for example in Git Bash
`JAVA_HOME=~/.jdks/jdk-17.0.21+9 ./gradlew assembleDebug`.

**SDK:** MainUI needs the **NDK 27.2.12479018**: the app builds one small C library with
ndk-build, `libmainuiinput.so` (`app/src/main/jni`), the virtual keyboard MainUI presses RetroArch's
hotkeys with. The emulator forks need the NDK too, but GitHub Actions builds them (see section 7).
Install the packages with the command line tools:
- In command line tools 23 or newer, `sdkmanager` is only a deprecated wrapper that can't parse
  names with `;`. Use the new `android` CLI next to it, which separates package names with `/`:
  `android sdk install platform-tools platforms/android-35 build-tools/35.0.0 ndk/27.2.12479018`
- Older command line tools (with a working `sdkmanager`) use `;`:
  `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;27.2.12479018"`
- If the downloads keep failing ("closed"), download the archives from
  `https://dl.google.com/android/repository/` with `curl -L -C - --retry 5 -O <url>` (it resumes)
  and unpack them by hand: `android-ndk-r27c-<os>.zip` into `<sdk>/ndk/27.2.12479018`, and
  `build-tools_r35_<os>.zip` into `<sdk>/build-tools/35.0.0` (each archive has one top folder;
  its contents go there).

**Windows:** run adb natively on Windows, not inside WSL, where USB devices aren't visible without
extra setup. If `adb devices` lists nothing, install the Google USB Driver (Android Studio > SDK
Manager > SDK Tools) or your device maker's driver. Linux or macOS work as they are.

---

## 2. Repositories to clone

Clone everything side by side, for example into `~/mainui-dev/`. **Clone MainUI with full history,
not a shallow clone.** The app's version number is the commit count (`git rev-list --count HEAD`).
A shallow clone gets a low version number, and Android refuses a lower version as an update.

### MainUI (the app)

```bash
git clone https://github.com/MarsInsanity/MainUI.git
```

Work on `main` only: commit there and push there. All earlier work was merged into it. The old
`claude/tender-tesla-u8ze4z` branch is finished and isn't used any more.

### Themes

```bash
git clone https://github.com/MainUI-Android/themes.git
```

Branch `main`. It holds the 22 themes, the Python kit that draws them, and the CI that publishes
them. MainUI's Theme Downloader reads that release.

### Emulator forks (only to change their MainUI patch)

You don't need these to work on MainUI. Clone one only when its save/load (State API) patch
changes. Each fork is upstream plus a few patch commits.

| Fork (github.com/MainUI-Android/...) | Branch | Upstream | Stable patch file |
|---|---|---|---|
| `dolphin-mainui` | master | dolphin-emu/dolphin | — |
| `melonds-android-mainui` | master | rafaelvcaetano/melonDS-android | — |
| `flycast-mainui` | master | flyinghead/flycast | — |
| `armsx2-mainui` | master | ARMSX2/ARMSX2 | — |
| `armsx3-mainui` | master | ARMSX2/ARMSX3 | — |
| `hakux-mainui` | master | rfandango/hakuX | — |
| `ppsspp-mainui` | master | hrydgard/ppsspp | `.github/mainui/stable/v1.20.4.patch` |
| `azahar-mainui` | master | azahar-emu/azahar | `.github/mainui/stable/2126.2.patch` |
| `mupen64plus-ae-mainui` | master | mupen64plus-ae/mupen64plus-ae | — |
| `seedlessds-mainui` | main | SapphireRhodonite/SeedlessDS | `.github/mainui/stable/1.0.1.patch` |
| `mame4droid-current-mainui` | main | seleuco/MAME4droid-Current | — |

These are kept identical to upstream and are not patched: `retroarch-mainui` (RetroArch already
has network commands) and `rpcsx-ui-android-mainui`. `melonds-mainui`, the desktop melonDS, is
unused.

Package names: each MainUI build is the official package plus `.mainui` (for example
`org.ppsspp.ppsspp.mainui`), so it installs beside the official app. ARMSX3's is
`com.armsx3.mainui`. ARMSX2 keeps upstream's package.

---

## 3. Local configuration

Create `MainUI/local.properties`. It is git-ignored, so never commit it:

```properties
sdk.dir=/path/to/Android/Sdk
# optional, for the ScreenScraper scraper (developer credentials from screenscraper.fr):
screenscraper.devid=...
screenscraper.devpassword=...
```

On Windows, write `sdk.dir` with escaped backslashes: `C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`.
Android Studio writes this line for you when you open the project. The same two ScreenScraper values
can come from the environment variables `SS_DEV_ID` / `SS_DEV_PASSWORD`. CI takes them from the repo
secrets of the same names.

---

## 4. Build and test

From the `MainUI` folder (on Windows use `gradlew.bat`, or `./gradlew` in Git Bash):

```bash
./gradlew testDebugUnitTest
```

```bash
./gradlew assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- `assembleRelease` builds `app/build/outputs/apk/release/app-release.apk`, the same thing CI builds.
- Toolchain: AGP 8.9.1, Kotlin 2.1.20, Gradle 8.14.3 (the wrapper downloads it), compileSdk 35,
  minSdk 29 (Android 10), targetSdk 34.
- Application id: `io.github.marsinsanity.mainui`, for debug and release alike. Debug has no
  suffix, so only one of the two can be installed at a time.

### Signing: two devices, two keys

Android only updates an installed app with an APK signed by the **same key**, so each device
stays with one kind of build:

| Device | Builds | Signed with | Updated by |
|---|---|---|---|
| Retroid Pocket 2+ (test device) | `assembleDebug` on the PC | the PC's debug key (`~/.android/debug.keystore`, made by the SDK) | `adb install -r` from the PC |
| Retroid Pocket Mini V2 (yours) | GitHub Actions releases | the release key (repo secrets `MAINUI_KEYSTORE` (base64), `MAINUI_KEYSTORE_PASSWORD`, `MAINUI_KEY_ALIAS`, `MAINUI_KEY_PASSWORD`) | Settings > Updates |

- Never install a GitHub release on the Pocket 2+, and don't use its Settings > Updates: a
  release-signed APK can't replace the PC-signed one. You'd have to uninstall first, which deletes
  MainUI's settings, recents and so on. The same goes the other way for the Pocket Mini.
- Keep the PC's `~/.android/debug.keystore` (back it up). If it's lost or the PC changes, the next
  build has a new key, and the Pocket 2+ needs one uninstall and a fresh setup.
- Debug builds are also the only ones `run-as` and the `debug.mainui.*` properties work with
  (section 6).
- The release key lives only in GitHub secrets. Building release-signed APKs on the PC isn't
  needed. Never commit, paste or print a keystore or its passwords, Claude included.

---

## 5. Setting up the test device (Retroid Pocket 2+) for Claude

The test device is a **Retroid Pocket 2+** running **LineageOS 19 (Android 12L)**: Unisoc T310,
2 GB RAM, 3.5" 640x480 touchscreen. What that means for testing:
- **Its screen is 640x480, the Miyoo Mini's own size**, so OnionOS themes show 1:1 there. That
  makes it the right device for the theme fidelity work (`docs/onion-fidelity/`). The Pocket Mini
  has a different screen shape, so check a layout change on both: on the Pocket 2+ over adb, and
  on the Pocket Mini after the release lands.
- **It's a slow chip**: RetroArch with 8/16/32-bit cores, PPSSPP's lighter games and other light
  emulators run. GameCube/Wii, PS2, 3DS, PS3 and Switch emulators don't. Test those on the Pocket
  Mini with release builds.
- **Android 12L, not the Pocket Mini's Android 13**: a few things MainUI does differ by version
  (the in-game menu's handling of a paused emulator on Android 14+, notification and receiver
  rules on 13+), so a change in those areas still needs a check on the Pocket Mini.
- **Root**: everything below works without root except the root-only features. MainUI's own root
  needs Magisk (step 3). If your LineageOS build has Developer options > **Rooted debugging**,
  turning it on lets `adb root` give Claude a root shell even without Magisk.

1. **Developer options**: Settings > About > tap Build number 7 times. Then in Developer options
   turn on **USB debugging** (and "Stay awake" helps while testing).
2. Plug it in and accept the "Allow USB debugging" prompt (tick "Always allow"). Then
   `adb devices` should show it as `device`.
3. **Root (Magisk)**: in Magisk > Settings > Superuser access, choose "Apps and ADB". Then run
   `adb shell su -c id` once and grant **Shell** in the prompt, so Claude can run root commands.
   MainUI also needs superuser (it asks on first use). Without root, MainUI still runs, but core
   detection, the process manager, key injection, force stop and freeze-on-sleep are unavailable.
4. Install a debug MainUI (section 4). Then do the setup screen's steps, or set them over adb:

   ```bash
   adb shell appops set --uid io.github.marsinsanity.mainui MANAGE_EXTERNAL_STORAGE allow
   ```

   ```bash
   adb shell cmd package set-home-activity io.github.marsinsanity.mainui/.MainActivity
   ```

   ```bash
   adb shell settings put secure enabled_accessibility_services io.github.marsinsanity.mainui/io.github.marsinsanity.mainui.service.OverlayService
   ```

   ```bash
   adb shell settings put secure accessibility_enabled 1
   ```

   The accessibility service is the in-game menu overlay (Home opens it during a game).
5. **Emulators** (the light ones, see above): install them from MainUI itself (Apps > Emulators: the "MainUI build" entries
   download the fork's latest stable release). Or download the APK from a fork's GitHub
   releases: `<upstream tag>-mainui` is stable, `nightly` is a test build.
   **RetroArch**: get the website or F-Droid build (https://www.retroarch.com), not the Play Store
   one. Then run Settings > Emulators & cores > **Configure RetroArch for MainUI** once, and
   **Get cores for my games**.
6. **Games**: put roms in `/sdcard/roms/<system>/` (dArkOS folder names, see README "Storage
   layout"; Settings > Library & storage > Create dArkOS folders makes them). Use your own dumps or
   free homebrew. A free PSP test image is `cube.iso` from
   https://github.com/hrydgard/pspautotests (`demos/`). Push it with
   `adb push cube.iso /sdcard/roms/psp/`.
7. **First launch of each emulator**: open it once by hand and get past its first-run screens
   (permissions, folder pickers, PPSSPP's storage dialog) before testing it from MainUI. Some ask
   for a folder with Android's file picker, which needs a person to tap through.

---

## 6. ADB cheat sheet for testing

```bash
adb logcat -s MainUI.Session MainUI.StateApi MainUIStateApi MainUI.Overlay MainUI.Freezer MainUI.Root
```

- Tags: `MainUI.*` is the app (others: `MainUI.InGame`, `.Keys`, `.Cores`, `.AppStore`,
  `.EmuUpdates`, `.Scraper`, `.Saf`, `.Haptics`). `MainUIStateApi` is the forks' side of save/load.
- MainUI's own game log: `adb shell cat /sdcard/MainUI/logs/session.txt` (launches, in-game menu,
  save / load, auto load). Look here first after a save/load problem.
- Screenshot: `adb exec-out screencap -p > shot.png`
- Buttons: `adb shell input keyevent KEYCODE_BUTTON_A` (also `_B`, `_X`, `_Y`, `_L1`, `_R1`,
  `_START`, `_SELECT`), `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT`, `KEYCODE_HOME`, `KEYCODE_BACK`.
  Touch: `adb shell input tap X Y`.
- Sleep / wake: `adb shell input keyevent KEYCODE_SLEEP`, then `KEYCODE_WAKEUP` and
  `adb shell wm dismiss-keyguard`.
- **Keys injected with adb skip accessibility filters**, so an adb `KEYCODE_HOME` does not open the
  in-game menu the way the real Home button does. To open the menu from adb, use
  `adb shell am start -n io.github.marsinsanity.mainui/.MainActivity --ez mainui.ingame_menu true`.
  Don't use `am start` on the plain launcher activity mid-game: MainUI takes that as leaving the
  game and ends the session.
- MainUI's settings (debug builds): `adb shell run-as io.github.marsinsanity.mainui ls shared_prefs`.
- Force stop: `adb shell am force-stop <package>`. Which app is on top:
  `adb shell dumpsys activity activities | grep -m1 topResumedActivity`.
- Debug-only system properties: these point a debug build at local stand-ins instead of the
  internet, or at a fake root. On a real rooted device with internet you won't need them.
  - `debug.mainui.rootport`: a root shell server on 127.0.0.1:<port>, for devices without `su`.
  - `debug.mainui.themebase`: OnionOS theme repository mirror.
  - `debug.mainui.mainuithemes`: MainUI themes release mirror.
  - `debug.mainui.corebase` and `debug.mainui.rabase`: libretro buildbot mirrors.
  - `debug.mainui.updateapi` and `debug.mainui.githubapi`: GitHub API stand-ins.
  - `debug.mainui.artbase`: box art sources.

  Set one with `adb shell setprop <name> <url>`. Clear it with `adb shell setprop <name> ""`.

---

## 7. CI, releases and the forks

### MainUI: `.github/workflows/build.yml`

- **Every push to `main`** (pushes that change only docs or `*.md` are skipped) runs the unit tests, builds a release-signed APK and publishes it
  as the GitHub release **"MainUI 0.1.N"** (tag `v0.1.N`, N = commit count). It keeps the newest 20
  releases.
- The in-app updater on the Pocket Mini (Settings > Updates) installs the latest release. So
  pushing to `main` is how a change reaches your own device, a few minutes after CI finishes.
- `cores.yml` builds the extra RetroArch cores weekly (Monday 05:23 UTC) into the `cores` release.
  MainUI's core installer downloads them from there.

### Themes: `MainUI-Android/themes/.github/workflows/release.yml`

- Every push to `main` rebuilds all themes and replaces the assets of the `themes` release
  (`catalog.json`, `<id>.zip`, `<id>.png`). MainUI's Theme Downloader reads
  `releases/latest/download/catalog.json`, so a pushed theme shows up in the app within minutes.
- To check a theme locally: `python3 kit/build.py <id> --mock dist/mock`. It should report no
  PROBLEMS. Then look at `dist/mock/<id>-mock.png`.
- After changing a theme, run `python3 kit/build.py <id> --previews previews` and
  `python3 kit/build.py --gallery README.md`.
- MainUI's own default theme is made with `--mainui` and bundled in MainUI at
  `app/src/main/assets/themes/MainUI`. When it changes, copy it over there.
- Use the pinned Pillow/numpy versions (`kit/requirements.txt`) so your builds match CI's.
- License: CC BY-NC 4.0 for the themes and kit. Fonts are SIL OFL only, each with its licence text.

### Emulator forks: `.github/workflows/mainui.yml` in each fork

- A push to the fork's branch builds a **nightly** pre-release.
- Running the workflow by hand (workflow_dispatch) builds the **stable** `<upstream tag>-mainui`
  release. It builds from upstream's newest release tag with the MainUI patch applied, or with
  `.github/mainui/stable/<tag>.patch` when the branch's patch doesn't apply to that tag. The first
  line of that file is `MainUI patch <id>`.
- All the logic is in `.github/mainui/ci.sh`. The master copies of the shared files are in MainUI's
  `tools/stateapi/` (`ci.sh`, `MainUIStateApi.java`). Change them there, then copy them to the forks.
- The protocol and the per-fork details (adapter file, slots, resume slot) are in
  `tools/stateapi/README.md`. Read it before touching save/load on either side.
- Signing: the forks use the same four `MAINUI_*` secrets, set at the MainUI-Android **org** level.
- When something needs a person (an upstream merge conflict, a stale stable patch), the fork's CI
  opens an issue on the fork (on forks with issues turned on) and always logs an error in the run.

### Nightly fork maintenance (runs in the cloud, not on your PC)

The scheduled task **"MainUI-Android emulator forks: nightly upstream merge"** runs daily at 00:00
Los Angeles time on your claude.ai account. For each fork it:
- merges upstream and checks the patch is still small;
- starts CI;
- rewrites stable patches when needed.

It keeps running whether or not you develop on your PC. You can see, pause or change it at
claude.ai under scheduled tasks.

---

## 8. Rules this project follows (for Claude on the PC)

- **Work on `main` only.** Commit there and push there.
- **Never create, commit, paste or print signing keys** or their passwords. Never commit
  `local.properties`.
- **Forks change nothing but the save/load State API** (plus packaging/CI: `.mainui` package
  suffix, release signing; and Dolphin has savestates on by default). No other behaviour changes,
  so they stay mergeable with upstream.
- **hakuX: never commit the DOA3 mod** (`mods/`).
- **Don't build the emulator forks locally.** Push and let GitHub Actions build them.
- Don't open pull requests unless asked.
- Stage files by name (`git add path/to/file`), never `git add -A` or a whole directory.
- Keep personal details (email etc.) out of commits, code and requests to outside services.
- Commit messages: short subject, brief body on what changed and why.
- The cloud session could use `gh` only for search. On your PC, `gh` works normally once logged in.

---

## 9. Where things stand

Recently finished and verified (CI green):
- PPSSPP save/load made reliable (fork patch + `v1.20.4.patch`).
- Touch support in the in-game menu and process popup.
- MainUI-style icons for Hidden apps and the App Store.
- The themes repo with 22 themes; MainUI installs them from its release.
- The game is paused while the device sleeps (fixes the heat). Setting: Controls > Pause game while asleep.
- Dolphin: a resume state older than the game's own saves is no longer loaded (fixes Wii/GC saves
  disappearing). MainUI also continues from the resume state only when the game was left with it.

Open, not started or paused:
- **OnionOS fidelity**: where MainUI draws OnionOS themes differently from a Miyoo Mini. See
  [`docs/onion-fidelity/README.md`](onion-fidelity/README.md) for the status, every open finding
  with its proposed fix, and the device reference. Lanes A and B (theme loading, main menu, bars,
  battery) are done. Lane B still needs a review. Lanes C (lists, pop-ups, dialogs) and D (Game
  Switcher, in-game menu) are open. Test them on the Pocket 2+, whose screen is the Miyoo's size.
- **RetroArch auto state**: RetroArch's own auto-save state could in principle be older than the
  game's in-game save (the hazard fixed for Dolphin). Confirmed by code review in the bug hunt
  (section 10); now fixed there: an auto state older than the game's `.srm` is set aside.
- Relaunching the game that is already running logs "the game wasn't ready" for auto load. Harmless.
- Standalone emulators other than RetroArch, PPSSPP and Dolphin are only partly tested on a real
  device. README "Known limitations" has the list. The light ones can be tested on the Pocket 2+.

---

## 10. Usability bug hunt (2026-11-15)

A hunt for bugs that ruin using MainUI, run from the PC with Claude Code agents: 12 agents each
read one area of the code, device testers drove the Pocket 2+ over adb (one at a time), and
reviewer agents checked every fix before it stayed. Every finding below was checked against the
code before it was fixed or listed. Device-test results are at the end of this section.

### Fixed

Battery, heat and memory:
- **The screen never turned off on MainUI or with the in-game menu open**: `MainActivity` and the
  overlay window set `FLAG_KEEP_SCREEN_ON` for their whole life, so the screen timeout never applied. Now only a scene running a long job keeps it on
  (`Scene.keepsScreenOn`: busy panels, store downloads, the emulator update check, a scrape).
- **Idle redraws at 60 fps**: a selected name too long for its row scrolled with a full-screen redraw
  every frame, pauses included. Now ~30 fps while it moves, nothing during the pauses.
- **Pictures kept while a game runs**: box art, screenshots and icons (tens of MB) stayed decoded
  behind games on a 2 GB device. They are dropped 10 s after MainUI stops (and on `onTrimMemory`,
  which doesn't come while the overlay service keeps the process important).

Crashes:
- **A root command outliving its timeout killed the launcher**: its reader threads threw after
  `destroyForcibly()` (`Root.run`).
- **Out of memory during a big scrape killed the launcher**: the scraper's worker threads now catch
  it; the game counts as an error.

Lost progress or data:
- **A game whose save failed could never be left** (found on the device: a RetroArch that hung, a
  PPSSPP that couldn't write its states): Exit Game refused, with no way out but a force stop. Now
  the message says so, the item then reads "Exit without saving" (until a save works again), and
  choosing it exits without saving.
- **RetroArch's auto save wrote into the user's own slot**: the Game Switcher / Exit auto save pressed
  the save key on the slot RetroArch was on (slot 0 at the start, or the user's last slot), replacing
  their save (seen in the device log). It now steps to RetroArch's auto slot (-1), saves, and steps
  back. Verified on the Pocket 2+ (RetroArch 1.22): "slot 0 -> -1", saved `.state.auto`, "slot
  -1 -> 0", and slot 0's file untouched. (A RetroArch whose slot key stops at 0 saves into slot 0 as
  before.)
- **An older RetroArch auto state overwrote a newer in-game save**: after RetroArch died mid-game, it
  auto-loaded `.state.auto` and its older SRAM over the newer `.srm`. Before a RetroArch start (root,
  auto save on), an auto state more than a minute older than the game's `.srm` is renamed aside
  (`.older`), so the game starts from its save. Only when exactly one of each is found (two games
  with the same name in other folders are left alone).
- **A second game in the same emulator stacked on the first**: emulators whose recipe has only
  `NEW_TASK` (Flycast, PPSSPP, melonDS, Mupen64Plus, MAME4droid, Redream) could stack a second, empty
  game screen on the running one, with the old game still running behind. With root the emulator is
  now stopped before another game starts in it (the Game Switcher saved the old one on leaving);
  never an emulator the user opened by itself (its game is unknown to MainUI and unsaved by it).
  RetroArch too: switching between two RetroArch games deadlocked it ("isn't responding", found on
  the device) while it tore its old game down; stopped first, the second game starts (verified).
  (Other CLEAR_TASK emulators close their old game themselves and aren't stopped; a standalone one
  that is gets its writes finished first.)
- **Emulators running their game in a sub-process looked stopped** (found on the device: Mupen64Plus
  runs it in `pkg:EmulationProcess`, and `pidof pkg` missed it once Android killed the idle main
  process): MainUI started the game again, its auto load waited for nothing, automatic saves stayed
  off and Exit lost the progress. Running checks now count every `pkg:*` process (`Root.processes`).
- **The in-game menu showed over the lock screen** after waking (B there resumed the game behind the
  lock screen; with the screen timeout now applying, this happened whenever the menu was left open).
  It is now transparent and ignores keys, touches and HOME until the device is unlocked; it keeps
  the focus, so a game frozen behind it is never reported as not responding. Verified on the
  Pocket 2+: the lock screen shows after waking, the menu is back after unlocking.
- **Going back to a game still running in its emulator turned auto save off** (found on the
  device): MainUI started it again, the emulator only came to the front, and the auto load waited
  3 minutes for a fresh start that never came, keeping automatic saves off (progress lost on Exit).
  With root, a game left for another one and still running is now brought back where it was.
- **Exit Game stopped games while frozen**: behind the in-game menu a game never gets `onPause` /
  `onStop`, where Android games, ports and many emulators save. With root it is now thawed, MainUI
  comes in front, its writes finish, then it is stopped; nothing can be started meanwhile
  ("Still closing the last game...").
- **A twice started a game twice**, using up its resume state and then saving the fresh boot over it.
  A start is now guarded until MainUI leaves the screen or the start fails.
- **"MainUI data folder" moved data on every LEFT / RIGHT step** and could leave favorites, recents
  and play time split over two folders. Now a choice with a confirmation; the whole MainUI folder is
  copied (library first) before switching; "SD card" is refused when the card can't be written.
- **Slot preview said "Empty"** for slots that hold a state saved outside MainUI's menu: now
  "No preview".

Things that didn't work:
- **Short Home inside MainUI did nothing** (Android pauses the activity before `onNewIntent`, so
  `resumed` was always false). Verified on the Pocket 2+: Home goes to the main menu, then the Game
  Switcher.
- **Games vanished**: a `.cue` / `.gdi` / `.m3u` hid the files it lists even in a system that can't
  open it (PS2 `.cue`, Amiga or C64 `.m3u`).
- **ES-DE / Batocera folder names weren't recognized**: `n3ds`, `fbneo`, `gamecube`, `pokemini`,
  `colecovision`, `tg16`, `tg-cd`, `megacd`, `msx1` are now aliases (`systems.json`; note that
  `tools/gen_systems.py` would drop them if `systems.json` were regenerated).
- **Turning off the Emulators menu or the Android system made their apps unreachable**: they are
  listed in Apps while their menu is off.
- **"Emulator not installed" in MainUI's first seconds** (detection still running): launches wait
  for the first detection.
- **RetroArch never opened by hand hung on a black screen** (found on the device: it had no storage
  permission, which its own first start asks for). With root, MainUI now grants an emulator the
  storage permission it declares, at install and before each start (`pm grant`, only that
  permission, never all of an app's). Verified on the Pocket 2+: µCity now loads in RetroArch.
  "Configure RetroArch" now says what to do when `retroarch.cfg` is still a stub.
- **A core download the user left with Home still started its game later** over the running one.
- **Scraping one game gave up when ScreenScraper refused the account** (login, quota, closed); the
  free sources are now tried, as in a full run.
- **libretro's game lists (DATs) never downloaded** (found on the device): Android asks for gzip by
  itself, raw.githubusercontent.com then gzips the byte ranges MainUI downloads in, and every length
  check failed ("Download incomplete"). CRC identification was lost for every source, and a game
  nobody found counted as an error ("libretro's lists couldn't be downloaded"). Downloads now ask for
  the bytes as they are (`Accept-Encoding: identity`). Verified on the device: "No box art found"
  instead of the error.
- **Dialogs cut their text at five lines** (an app's About in the App Store, long warnings): a longer
  message now scrolls with UP / DOWN, with ▲ / ▼ marks.
- **App Store search buried what was searched for** ("nes" listed 208 apps from "2fa" on, summary hits
  first by name): apps whose name starts with or contains the search come first.
- **Long press opened the options of the selected item**, not the one under the finger (lists, grid,
  row).
- **Recents put the cursor on another game** after playing one.
- **Holding Back for the process popup also left the screen** behind it; with hold-Back on, Back now
  acts on release when it was a short press.
- **App Store > Emulators jumped back to the top** after every emulator page.
- **A session restored after MainUI's process restarted was never ended** when its game was quit
  meanwhile (Android kills MainUI under memory pressure during big games): its game counted as still
  playing (play time, resume instead of start, "is running" on Delete ROM). With root, MainUI now
  checks once after a restart and ends it when its emulator (or a MainUI build's game) isn't running.
- **A MainUI build's emulator that wasn't running was started and frozen in the background**: each
  return to MainUI and each screen-off sent it "pause", which starts a stopped app, and its "no game
  is running" answer then got it frozen; picking its game later brought back the emulator's own
  menus. "pause" now goes only to a running emulator, one with no game is never frozen, and the
  game is unpaused before MainUI asks whether it ended.
- **MainUI killed while a game started forgot that its auto load was pending**, and the next leave
  saved the fresh start over the state: the pending load is now kept with the session on disk.
  The list of games left running in their emulators is kept on disk too (a restart lost it).
- **The core list couldn't tell RetroArch's cores from standalone emulators** ("Default (Flycast)"
  beside "Flycast"): cores now read "Flycast, RetroArch".
- **A session (or a State API pause) from before a reboot could come back** when the clock was set
  back at boot (a drained battery): boots are now told apart by Android's boot count
  (`Settings.Global.BOOT_COUNT`), the clock only when it is unknown.
- **A copied `MainUI/emulators.json` silently hid MainUI's later recipe fixes** (an entry replaces the
  built-in recipe whole): Settings > Emulators & cores now lists the recipes it replaces.
- **HOME skipped the in-game menu after a permission prompt flashed over the game at its start**
  (found on the device: Android 13 asks for notifications on most apps' first start; LineageOS Clock
  every time): MainUI took the permission screen for the app in front and HOME went to MainUI with
  the game running on. Permission and install prompts over another app no longer count as the
  app in front (over MainUI's own screens they still do, so HOME there isn't taken for the game).
- **Two stray taps deleted a ROM** (found on the device): a tap on the footer strip hit a tall pop
  menu's half-hidden last row ("Delete ROM", "Uninstall"), and a tap anywhere on a dialog's box
  confirmed it. The footer strip is no menu row now, and a two-button dialog is confirmed only by
  its confirm hint or A (a tap beside it still cancels). Verified on the device.
- **A force stop of MainUI turns its in-game menu service off** (Android does that) without a word:
  MainUI now says so after it restarts.
- **The App Store and its repository page swallowed B while refreshing** (minutes on slow Wi-Fi with
  20 MB of indexes): B now leaves; the refresh finishes in the background.
- **The daily emulator update check downloaded ~22 MB** of F-Droid and IzzyOnDroid indexes every day:
  it now refreshes only the repositories an installed emulator comes from (all of them only while
  none was ever downloaded).
- **A theme update unpacked over the installed theme** (files the new version dropped stayed; a
  failed unpack left it half-written, and the theme in use kept old cached pictures): themes are now
  unpacked beside it and swapped in whole, and the theme in use is reloaded.
- **Emulators for Android 11+ never opened by hand couldn't read roms** (they read with "All files
  access", asked for on their first start): with root it is granted when the app declares it, like
  the storage permission.
- Smaller: generic pads sending `KEYCODE_BUTTON_1..10` (DInput "USB Gamepad"s) can select, go back and
  page; holding B on the on-screen keyboard keeps deleting; the folder picker lists folders off the
  UI thread (it froze on big rom folders); games marked hidden in gamelist.xml show with "Show hidden
  games"; the scraper no longer reads `.chd` / `.cso` files whole for a CRC that can't match, nor a
  file twice when ScreenScraper already hashed it.
- **Magisk's "granted Superuser" toast covered the footer hints on most screens** (49 toasts in 38
  minutes of device testing): every root command started its own `su`. The frequent, short ones
  (process list, `pidof`, the core scan and `retroarch.cfg` read on each return to MainUI, the SAF
  grant check at each start, brightness) now run through the persistent root shell
  (`Root.quick` / `Root.readText`), and a denied root request isn't asked again for a minute.

Store and updates:
- **An emulator update that can never install (other signature) was offered and downloaded every
  day**: now refused once, with the reason, and not offered again while the same app is installed.
- **Debug builds got MainUI release notices** they can't install (release key over the PC's key):
  none now, and Settings > Updates says to update from the PC.
- Store icons and theme previews are kept out of the gallery (`MainUI/cache/.nomedia`); APKs handed
  to Android's installer are deleted after an hour; the App Store list says when it stops at 1500.
- **Process manager**: START (kill all) is in the hints and says what it did.
- **SD card games failed in emulators that open roms through a folder grant** (found on the device:
  SeedlessDS, Flycast and the other `%ROMSAF%` recipes with only an internal `roms` folder
  granted got a link they can't open). MainUI now checks the emulator's folder grants before
  starting and says which folder to add in that emulator ("SeedlessDS has no access to SD
  card/roms/nds..."); the grants are read again (not from the minute's cache) before refusing. The
  launch error's "Install it" hint now shows only when the emulator is missing. Verified on the
  device (and that the internal copy still starts).
- **Switching "MainUI data folder" back silently brought back an old copy** (found on the device):
  a folder that already held MainUI data (left by an earlier move) got nothing copied, so
  favorites, recents and play time went back in time. MainUI now asks which data to keep: the data
  in use (the older MainUI folder is set aside whole as `MainUI.old`, `MainUI.old2`..., nothing of
  it deleted or merged) or the older data; B backs out. Asking creates no folder (an empty SD card
  `MainUI` folder would have become Auto's data), the library is copied before the setting changes
  and a failed copy undoes everything, and "Auto" shows where it resolves ("Auto (Internal)",
  "Auto (SD card)"). Verified on the device: Internal > SD card > Internal > Auto, and backing out.
- **A big library (thousands of roms on an SD card) sat on "Loading..." and counted itself again
  and again**: the Games screen counted every system before showing any, statted every file,
  parsed every gamelist.xml and DAT and read every .cue / .m3u, twice at once on a cold start, and
  again after deleting one rom, unzipping or scraping. Now names decide without a stat, a gamelist
  is parsed for the count only when it hides games, disc lists are kept per folder, one count runs
  at a time, systems show as they are counted, and those actions count only their own system.
- **A full arcade set's Logiqx DAT (20-60 MB) never loaded** (read whole it took several times its
  size; the error was swallowed, so arcade games kept their zip names): big read-only DATs are now
  parsed as they are read, keeping only what the lists use. Unit tests compare the streamed
  sanitizer with the old one.
- **Parsed gamelists and DATs stayed in memory behind every game** (tens of MB on a big scraped
  library, on a 2 GB device): given back when MainUI is hidden, read again only when a list needs
  them; one parse per file at a time; a gamelist too big to read is remembered, not retried.
- **Game lists froze with many favorites** (each row searched the whole favorites list, on the UI
  thread, on every return from a game): favorites are a set and the rows are made in the
  background. Search says when it stopped at 500 results.
- **An emulator never pointed at a folder got an SD/ROMs link anyway** (found on the device:
  Dolphin's "the file does not exist", Azahar's empty game list on their first start): with root,
  "no folder at all" is known for sure and gets the same "has no access to ..., add that folder"
  dialog. Verified on the device (ARMSX2, which has no folder).
- **The in-game menu stayed up over an emulator that died** (crashed, or killed by Android) and its
  Continue / Save / Game Switcher started the emulator again, empty, logging a save that never
  happened (found on the device): the menu now checks every few seconds, closes with "SeedlessDS
  closed (it crashed or Android stopped it)...", and the session ends; a dead emulator is never
  asked anything. Verified on the device. Save / Load with no game running fail at once (not after 20 s).
- **HOME over a heavy game under memory pressure opened MainUI's last screen instead of the menu**
  (found on the device, once in four): Android took the press first; MainUI now opens its own
  in-game menu then.
- **Android 13+ greys out the in-game menu service of an APK installed from a file** ("Restricted
  setting") and nothing said what to do (the user's own Pocket Mini V2 runs Android 13): Setup and
  Settings > Controls explain "Allow restricted settings", Setup has an App info shortcut, and the
  README says it.
- **AYN Thor (Android 13+): an app on the second screen stopped HOME from opening the menu** over
  the game: window events from other displays are ignored.
- **The App Store offered apps Android 14 refuses** (made for Android 5 or older, or with a max SDK
  below the device's): marked incompatible.
- **Game Switcher auto load picked the wrong game** (reported by the user on Wii; any two games in
  one emulator): after leaving a game, Android killed the emulator behind MainUI (memory, or a
  broadcast it was too busy to take) but kept its task, so starting another game in it only
  brought the old one back, and the auto load loaded the old game's state for the new one. With
  root the emulator is now stopped before the new game also when it no longer runs. Found and
  fixed by agent W1, verified on the device (Dolphin, GameCube and Wii homebrew).
- **Android 13+ dropped MainUI's launch of several emulators** (a start that matches none of the
  emulator's intent filters; the Android 12 test device never shows it, the user's Pocket Mini V2
  does): Azahar / Lime3DS / Citra now get VIEW, Flycast its game activity, SeedlessDS its
  filter-less alias (also for names like "Dr. Mario.nds"), ARMSX2 / ARMSX3 their splash screen,
  melonDS's builds their own action, Vita3K the USB action both its builds declare; MAME4droid
  keeps its own roms folder; a game whose activity takes only its own start is resumed through the
  app's launcher start. A launch Android would drop is said in words.
  Flycast and SeedlessDS verified on the device (Android 12); reviewed against each emulator's source.
- **Without root, a first-time user's RetroArch game opened on a missing core** with nothing said:
  a one-time dialog says what RetroArch needs, and the cores screen no longer says "nothing missing".
- **Without root, Exit closed games MainUI can't save without a word**: the menu says "Exit Game
  (not saved)" and why; a native menu that can't be opened says so; a second RetroArch game closes
  the first RetroArch (Android 10-13), which hung "not responding".
- **Two MainUI screens after opening it from the old launcher, then making it the home app**: the
  older one is closed when the home one starts.
- **An empty library never said where games go** (the Android and Ports tiles hid the hint); "Create
  dArkOS folders" said "Created 0" when it couldn't write; theme downloads failed every time on
  Android 10 with an SD card (now internal storage).

### Emulators only on Google Play

Checked each emulator whose only source in `emulators.json` was Google Play or a website:
- **DuckStation**: its official beta APKs (signed like the Play version) are on
  `downloads.duckstation.org/android-testing/`. New source type `"apkpage"`: MainUI installs the
  newest APK that page links, and finds updates by the build number in its name (which is the
  version code). Only a build that came from that page gets automatic updates; installing it over
  another copy asks first, since the beta resets settings (saves, memory cards and BIOS stay). The
  page says the APK may not be redistributed: MainUI downloads it from duckstation.org itself.
- **DraStic**: no longer on Google Play (its page is gone) and not sold anywhere else: the Play link
  is removed; the note points to SeedlessDS (DraStic's core, from GitHub).
- **Redream** and **Yaba Sanshiro 2**: no Android download outside Google Play exists (Redream's site
  has desktop builds only; Yaba Sanshiro's GitHub repository and website are gone). Their notes say
  so and name what else runs those systems (Flycast; RetroArch's Saturn cores).
- PPSSPP, Dolphin and Mupen64Plus AE are on F-Droid (found automatically). Kenji-NX's and Eden's
  GitHub release repositories are blocked by a DMCA notice; not used.

### Open (real, not fixed yet)

Most severe first, each with the fix the review suggested:
- **Magisk's "granted Superuser" toast for the remaining root commands**: the frequent ones now go
  through the persistent `Root.Shell` (see Fixed); writes, installs and long jobs still start their
  own `su`. Set Magisk > Settings > Superuser notification to None to hide the rest.
- **A background job that throws leaves a scene's busy text set** (`host.io` shows a toast but
  doesn't call `done`); rare (out of memory on a huge catalog). Fix: wrap such jobs in `runCatching`.
- **Dreamcast games start in RetroArch's Flycast core even with Flycast's MainUI build installed**
  (systems.json lists the core first; found on the device). Not changed: preferring the MainUI build
  would hide existing players' RetroArch memory cards and states. Pick it per system (SELECT on the
  Dreamcast tile > Core), or prefer a MainUI build only over a RetroArch core of the same emulator.
- **Upgrading from v0.1.118 or older**: resume states from before v0.1.119 have no "fresh" mark, so
  the first start of each game skips them and its first leave saves over them (one time per game).
  Fix: a one-time migration marking states saved before the update.
- **"Auto" follows the first volume holding a `MainUI` folder, SD card first**: a card carrying a
  `MainUI` folder (another device's, or an earlier move's) takes over Auto's data silently. Fix:
  store the folder Auto resolved to and ask when another one appears.
- **A game on both volumes (same folder and name) starts the SD card copy**, so saves and states
  beside the internal copy go unused. Fix: show both, or prefer the copy last played.
- **The search walks systems in name order and stops at 500 matches**, so a common word never
  reaches later systems (it now says the list is cut). Fix: rank across systems, or stream results.
- **Root.foregroundPackage takes the first resumed activity** of `dumpsys`: on a two-screen device
  (AYN Thor) after waking it can be the other screen's. Fix: the default display's.
- **MainUI's park pause can get a slow emulator killed**: the State API "pause" goes on the
  foreground broadcast queue (10 s) right after MainUI comes in front, while the emulator's main
  thread is still writing its state (Dolphin, Wii): Android kills it (background ANR). The fix above
  makes that harmless for the next game. Fix: skip park's pause for emulators that pause themselves
  in the background, or send it later.
- **Android 13+: Mupen64Plus refuses rom names with an extra dot** ("Dr. Mario 64 (USA).z64": its
  path patterns stop at the first dot); MainUI says so. Its MAIN start with the rom as an extra
  works (tried on the device) but restarts a re-picked game without root. Fix: use that start only
  when the VIEW one is refused.
- **Without root on Android 14, a second RetroArch game can still hang RetroArch** (Android no longer
  lets MainUI end it). Close RetroArch from Recents first.
- **Vita3K's store entry installs the 2024 build** (Vita3K/Vita3K-Android); newer builds are on
  vita3k.org and in Vita3K/Vita3K's continuous release. The recipe now launches both.
- Smaller ones: network previews load first-in-first-out (the selected one waits behind skipped
  rows); package-manager label/icon lookups on the UI thread in the process lists; SD card mount
  reloads run on the main thread; a ScreenScraper screenshot is saved as box art when SS has no box;
  free-source JPEGs are re-encoded as PNG; with two RetroArch builds installed there is no way to
  pick one; the in-game menu opened at the moment the screen turns off can show over the lock
  screen; the in-game menu stays up when Android kills the emulator under it (B closes it, but its
  "resume" wakes the emulator's process just to hear that no game is running).

### Device testing (Pocket 2+, LineageOS 19.1)

- Navigation with an empty library (every menu, settings page, store page, theme screens, Game
  Switcher, process manager, keyboard; rapid and held keys): no crash, no ANR, no freeze. Found the
  Home, hold-Back and Emulators-list bugs above, and the Magisk toast.
- Games (free homebrew: µCity in RetroArch, `cube.iso` in PPSSPP's MainUI build): launch from lists
  and Recents with the right emulator and core; A twice starts once; real Home opens the menu with
  the game paused; Save / Load with slots and previews; Native Menu; Exit with auto save stops the
  emulator and MainUI comes back cleanly; "Still closing the last game..." right after Exit; auto
  load after Exit and relaunch; resume from Recents; the Game Switcher; sleep / wake pauses and
  resumes. Found the save-failed, auto-save-off, RetroArch-storage and overlay screen-on bugs above.
  Open from it: PPSSPP's MainUI build started straight into a game before its first-run setup has
  no memory stick (every save fails; open it once by hand first, as section 5 says).
- Store, themes, scraper: DuckStation installed through the new duckstation.org source (versionCode
  12126, storage granted), its page asks before installing over it, Open works, and the in-game
  menu works over it; no false DuckStation update. An OnionOS theme (Analogue Blanche) downloaded,
  applied and used on every main screen and the in-game menu, then MainUI's theme back. The scraper
  (free sources) finished without hanging and wrote valid gamelist.xml files (LaunchBox's 110 MB
  index takes ~3 minutes to read on this chip). App Store search, categories, app pages and the
  "N more apps" end work. Found the DAT download, dialog and search bugs above.

- Standalone MainUI builds (Mupen64Plus AE, SeedlessDS, Flycast; free homebrew): each one's first-run
  folder picker, Save / Load with previews, Exit (stops), auto load on relaunch, sleep / wake with
  the game running and with the menu open (lock screen, menu back after unlocking), the Game
  Switcher across emulators ("continued where it was") and within one (Flycast: "stopped before
  starting"). Found the RetroArch switch deadlock, the sub-process and the Dreamcast default bugs
  above. Flycast's MainUI build answers "can't pause" once MainUI is in front, so it is frozen
  instead (sound comes back on resume).
- Touch, Android apps, Ports, process manager: taps, swipes, flings, long presses (the pressed
  item's options), footer hints, the keyboard, Settings, dialogs and pop menus, the in-game menu and
  Game Switcher by touch; hiding apps, Android games, Ports and Emulators moves and launches; the
  process manager with a game behind and the hold-Back popup over games. Found the permission-prompt
  HOME and the stray-tap delete bugs above.
- SD card roms, folder grants, scraper art, data folder: games on the SD card in RetroArch and the
  standalone builds, emulators without an SD card grant, moving MainUI's data between Internal,
  the SD card and Auto and back. Found the folder-grant and data-folder bugs above.
- Several games on 2 GB, Dolphin / Azahar, Expert, Device, cores: five emulators at once (MainUI
  never killed), parked games killed by `am kill` and by Android's low-memory killer came back from
  the Game Switcher, Recents and the lists with their resume states; Dolphin and Azahar with free
  homebrew (Snes9xGX .dol, ftpd .3dsx); Expert, Settings > Device, Emulators & cores. Found the
  no-folder, dead-emulator, HOME-under-pressure and slow failed-save bugs above. Mupen64Plus AE
  crashes on N64NICCC by itself (glide64mk2); MainUI handles it.
- Wii and GameCube in Dolphin (agent W1, Snes9xGX homebrew), recipes changed for Android 13 (Flycast,
  SeedlessDS; Mupen64Plus's MAIN start tried) and the no-folder message for ARMSX2. Found the Game Switcher wrong-game bug above.
---

## 11. Quick start (copy in order)

```bash
git clone https://github.com/MarsInsanity/MainUI.git
```

```bash
cd MainUI && ./gradlew testDebugUnitTest assembleDebug
```

```bash
adb devices
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then start Claude Code in the folder that holds the clones (`claude`) and point it at this file.
