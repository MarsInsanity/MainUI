# Moving MainUI development to your PC

This file lists what your PC needs: what to install, what to download and where from, how to build
and test, and how to connect an Android device over ADB so Claude Code can test on it. It also
covers how the CI, releases and emulator forks work, and the rules this project follows. Give it to
the Claude Code session on your PC as its first read.

Last updated 2026-11-15 (MainUI 0.1.122).

**Two devices, two keys:**
- **Retroid Pocket 2+**: the test device on the PC's USB. It runs builds from the PC, signed with
  the PC's debug key. Claude installs and tests on it.
- **Retroid Pocket Mini V2**: your own device. It keeps running the GitHub Actions releases, signed
  with the release key, and updates through Settings > Updates.

**All development happens on `main`.** No other branches.

---

## 1. What to install on the PC

| Tool | Why | Where to get it |
|---|---|---|
| **Git** | cloning and committing | https://git-scm.com/downloads (on Windows this includes Git Bash) |
| **JDK 17** (or newer) | Gradle / Android build | https://adoptium.net (Temurin 17), or the JDK bundled with Android Studio |
| **Android SDK**: platform 35, build-tools 35, platform-tools (adb) | build and install the app | Easiest: **Android Studio** (https://developer.android.com/studio), then SDK Manager. Without Studio: "Command line tools only" from the same page, then `sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"` |
| **Claude Code** | your agent on the PC | https://code.claude.com/docs (macOS / Linux / WSL: `curl -fsSL https://claude.ai/install.sh \| bash`; Windows PowerShell: `irm https://claude.ai/install.ps1 \| iex`) |
| **GitHub CLI `gh`** (recommended) | watch CI runs, read releases, start workflows | https://cli.github.com, then `gh auth login` |
| **Python 3.13** + Pillow 12.3.0, numpy 2.5.3 | only for the themes repo (its kit draws every theme) | https://www.python.org/downloads, then `pip install -r kit/requirements.txt` in the themes repo |
| **scrcpy** (optional) | see and control the test device's screen live | https://github.com/Genymobile/scrcpy |

You don't need the Android NDK. MainUI is Kotlin only. The emulator forks need the NDK, but they
are built by GitHub Actions, not on your machine (see section 7).

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
  minSdk 28 (Android 9, for the Retroid Pocket 2+), targetSdk 34.
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

The test device is a **Retroid Pocket 2+**: Android 9, Unisoc T310, 2 GB RAM, 3.5" 640x480
touchscreen. MainUI supports Android 9 for it (minSdk 28). What that means for testing:
- **Its screen is 640x480, the Miyoo Mini's own size**, so OnionOS themes show 1:1 there. That
  makes it the right device for the theme fidelity work (`docs/onion-fidelity/`). The Pocket Mini
  has a different screen shape, so check a layout change on both: on the Pocket 2+ over adb, and
  on the Pocket Mini after the release lands.
- **It's a slow chip**: RetroArch with 8/16/32-bit cores, PPSSPP's lighter games and other light
  emulators run. GameCube/Wii, PS2, 3DS, PS3 and Switch emulators don't. Test those on the Pocket
  Mini with release builds. Some forks may not even install on Android 9 (their own minimum).
- **Android 9 differences** MainUI already handles: storage is the old read/write permission (no
  "All files access" screen); there is no Android 10+ Wi-Fi panel or newer haptics.
- **Root**: everything below works without root except the root-only features. If you root it
  with Magisk, do step 3.

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
   adb shell pm grant io.github.marsinsanity.mainui android.permission.WRITE_EXTERNAL_STORAGE
   ```

   (On Android 11+ devices it's `adb shell appops set --uid io.github.marsinsanity.mainui MANAGE_EXTERNAL_STORAGE allow` instead.)

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
  game's in-game save (the hazard fixed for Dolphin). Not looked at yet.
- Relaunching the game that is already running logs "the game wasn't ready" for auto load. Harmless.
- Standalone emulators other than RetroArch, PPSSPP and Dolphin are only partly tested on a real
  device. README "Known limitations" has the list. The light ones can be tested on the Pocket 2+.
- **Android 9 (Pocket 2+)**: MainUI was made to install on Android 9 for the test device. It
  builds and passes its tests, but hasn't been tried on Android 9 yet.

---

## 10. Quick start (copy in order)

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
