# What to test on the Retroid Pocket 2+

Bug areas for the test device, most important first. Each says what to try, what should happen,
and where to look. Set the device up first ([HANDOFF.md](HANDOFF.md) section 5).

## How to work

- **Three kinds of emulator** take different code paths, so test each area with one of each:
  - **State API forks** (MainUI saves and loads through the emulator itself): PPSSPP,
    Mupen64Plus AE, melonDS, SeedlessDS, MAME4droid, Flycast. Install the "MainUI build" from
    Apps > Emulators.
  - **RetroArch** (MainUI presses its keyboard hotkeys; needs root): NES, SNES, GBA, Genesis or
    PS1 cores.
  - **A standalone emulator without the State API** (MainUI sends its save/load keys and freezes
    it with root), for example DuckStation or an official build.
- **Logs**: `MainUI/logs/session.txt` on the storage MainUI uses (usually `/sdcard`) says what
  MainUI did around a game. Also look at logcat: `adb logcat -s MainUI.Session MainUI.StateApi MainUIStateApi MainUI.Overlay MainUI.Freezer MainUI.InGame`.
  Read both before deciding where a bug is.
- **The Home button**: keys sent with `adb shell input` skip MainUI's accessibility service, so
  they never open the in-game menu or Game Switcher. With root, send real key events instead.
  Find the button's device and code with `adb shell getevent -lp` (press Home while
  `adb shell getevent -l` runs). Then use, for example,
  `adb shell su -c 'sendevent /dev/input/eventN 1 102 1; sendevent /dev/input/eventN 0 0 0'`, and
  the same with `0` for the release. A hold is the press, a wait longer than 500 ms, then the release.
  Without root, ask the owner to press it. The fallback is
  `adb shell am start -n io.github.marsinsanity.mainui/.MainActivity --ez mainui.ingame_menu true`.
- **Check first** that the Pocket 2+'s Home button arrives as `KEYCODE_HOME` under LineageOS
  (`MainUI.Overlay` logs it). If it doesn't, the Home features can't work there, and that's the
  first thing to report.
- **A bug in the emulator's side of save/load** belongs in that fork's patch, and only there
  (see `tools/stateapi/README.md`). Everything else is fixed in MainUI on `main`.
- **For every bug**, write down: the steps, what happened, what should have happened, the
  emulator, and the matching lines from session.txt and logcat.

## 1. Save and load from the in-game menu

- Open the menu during a game, then Save to a slot, Continue, play on, and Load the slot. The game
  should come back exactly as it was saved, with a slot picture and time.
- Try every slot, an empty slot, and saving over a slot. Try saving or loading right after the
  game starts, before it's ready: MainUI should say so instead of failing silently.
- Load, then open the menu again at once, and save again. Watch for a "Load failed" or
  "State saved" that isn't true. Then check the slot really changed: load it again later.
- RetroArch: the first Save/Load of a run has been ignored before. Check the first one works,
  that slot changes reach RetroArch, and that no extra "Virtual" controller appears in
  RetroArch's port list.
- A standalone emulator without the State API: Save / load keys... (bind, Send save key, Send
  load key) should press that emulator's own keys.

## 2. Game Switcher and resuming

- **Leaving a game**: hold Home, or use the Game Switcher. MainUI should save the resume state,
  and the game should show in the Game Switcher with a screenshot.
- **Coming back**: the game should start from the resume state where it was left.
- **The resume state is only used when MainUI saved it as the game was left.** Check each of
  these: the game should start from its own saves, not an old resume state, and session.txt
  should say "skipped, its last session didn't end with MainUI's save".
  - Force stop MainUI during a game (`adb shell am force-stop io.github.marsinsanity.mainui`),
    then start the game again from MainUI.
  - Close the emulator from its own menu, then start the game again.
  - Let Android kill the emulator in the background. The Pocket 2+'s 2 GB of RAM makes this easy:
    open a few big apps.
  - Save in the game itself (its own save file), leave without MainUI's save, then start again.
    The in-game save must still be there. This is the bug that lost Dolphin saves, so check it
    with every State API fork.
- Starting a game from the resume state, then leaving it again, should save a new resume state.
  Starting it once more should continue from the newer one, not the first.
- Start a different game while one is running: the first should be saved and closed properly.
  Start the same game while it's running: it should simply come back. A harmless "the game
  wasn't ready" in the log is a known issue.
- Remove from history in the Game Switcher.

## 3. Exiting a game

- Use Exit Game in the in-game menu: "Saving and exiting...", then MainUI.
  - With **Settings: auto save on exit** on, the next start should resume.
  - The emulator should be fully closed afterwards (`adb shell pidof <package>` prints nothing).
- Make an in-game save just before Exit Game. MainUI waits until the emulator has finished
  writing before closing it, so the save must survive. Check that it does.

## 4. Sleep mode

This was changed recently: the game is parked while the device sleeps, to stop the heat.
- During a game, press Power. The game should pause:
  - with the State API, through the emulator's own pause;
  - otherwise, a root freeze.

  Check CPU while the screen is off: `adb shell top -n 1 -m 10` should show nothing
  busy. Then wake and unlock. The game should continue about half a second later, with sound and
  working buttons.
- Do the same with the in-game menu open, with the Game Switcher open, and while sitting in
  MainUI's own screens. After waking, the menu should still be open, holding the game paused.
- Test both with and without a lock screen. Then test a long sleep (10+ minutes), and sleeping
  twice in a row.
- Turn off **Controls > Pause game while asleep**: the game should keep running while asleep.
- A game you paused yourself in the emulator should stay paused after waking.

## 5. In-game menu, pausing and input

- Open and close the menu many times, quickly too. Each time:
  - the game should pause behind it (**pause on menu**);
  - it should resume when the menu closes, with sound and controls;
  - the triggers and shoulder buttons shouldn't be stuck afterwards. They were before.
- Native Menu should open the emulator's own menu.
- Settings to try:
  - **Freeze standalone**: standalone emulators without the State API are frozen behind the menu.
  - **Swap A/B**, and the Home press, Home hold, Back hold and combo settings in Controls.

## 6. Touch (changed recently)

- In the in-game menu and in the process popup (hold Back):
  - tap every item;
  - tap a slot to pick it;
  - scroll lists by dragging;
  - tap outside to close.
- Mix touch and buttons in one session. The selection should stay where you'd expect.
- The process popup should list only apps that can be closed, and closing one should work.

## 7. Launching games

- Start one game in every system you have roms for. Check that the right emulator opens straight
  into the game, and that MainUI's session starts (it shows in session.txt).
- PPSSPP: it gets the rom as a document link (`%ROMDOC%`). The game should open, not PPSSPP's
  file browser.
- RetroArch:
  - Run Settings > Emulators & cores > Configure RetroArch for MainUI once.
  - Get cores for my games.
  - Each core's games should then start.
  - Pick another core for a game (game options, SELECT), and use "Default".
- The first launch of an emulator that still shows its first-run screens should be reported
  clearly, not hang MainUI.

## 8. Themes at 640x480

The Pocket 2+ has the Miyoo Mini's screen size, so OnionOS themes should look exactly as they do
on a Miyoo.
- Review lane B (`docs/onion-fidelity/README.md`) against the reference on the device:
  - main menu tiles at their own size, draw order, bars, battery and Wi-Fi icon, dots;
  - the B BACK hint;
  - the Games grid.

  Take screenshots of a few OnionOS themes and compare them with each theme's `preview.png`.
- Apps > Theme Downloader:
  - MainUI themes come first;
  - A installs, Y installs and applies;
  - an outdated installed theme shows "update", and updating replaces it in place.
- Theme Switcher:
  - switching works;
  - the first theme with music offers to turn music on, once.
- MainUI's own themes should look as designed on the smaller screen: nothing cut off, no
  overlaps.

## 9. Setup on a fresh install

- Uninstall, install, and go through the setup screen: all files access, home app, accessibility
  service, root.
- Then check MainUI after a reboot: it is the home screen and the overlay service is on.

## 10. Updater on a PC build

- Settings > Updates on the Pocket 2+ offers a GitHub release signed with another key. It should
  refuse it with a clear message, not start an install that fails or leave a broken download.

## 11. Library and other screens

Regression checks. These worked before, so a quick pass is enough:
- recents and favorites;
- search (X in a system's list);
- hidden games and Hidden apps (new icon);
- App Store icons (new);
- Ports;
- unzip all;
- delete ROM;
- the scraper (it needs credentials);
- Settings > Device (brightness, volume), haptics, the header clock and battery.

## 12. Emulator controls (Dolphin)

*Settings > Controls > Emulator controls > Set up emulators* writes the handheld's buttons into
Dolphin. The cloud emulator checked that Dolphin reads them and lists the profiles. It did that
with a virtual controller, and without a game, so this part needs a real device. Use a light
GameCube game on the Pocket 2+, and Wii games on the Pocket Mini.
- **Setup**:
  - Start Dolphin once, then Set up.
  - Dolphin > Settings > GameCube Input and Wii Input should show the handheld's name as Device,
    and its buttons.
  - Check `MainUI/logs/session.txt`: Dolphin names the controller "Android/<number>/<name>"; the
    number must match Dolphin's Device line.
- **GameCube**:
  - every button, both sticks, L/R analog (half pressed) and fully pressed, the D-pad, rumble.
- **Wii, default (Nunchuk)**:
  - A, B (R2 or the right face button), 1, 2, −, +;
  - Home (Select + Start);
  - Nunchuk stick, C (L1), Z (L2);
  - shake (R1; Nunchuk shake: L3);
  - pointer on the right stick.
- **Motion**:
  - shaking and tilting the handheld should shake and tilt the Wii Remote (Wii Sports, Mario Kart
    Wii sideways);
  - *Wii: pointer > Tilt*: the pointer should follow the handheld, and Select + R3 recenters it;
  - say which way an axis is wrong, if one is.
- **Per game**:
  - a game's **Wii controller...** (SELECT) should load that profile when the game starts. Dolphin
    shows "Loading game specific input profile".
  - Sideways: the D-pad is turned for the sideways Wii Remote, and 1 and 2 are the bottom and
    right buttons.
- **Hotkeys**:
  - Select + R1 toggles the speed limit, Select + L1 takes a screenshot;
  - in-game menu Save / Load with official Dolphin (MainUI presses F1 / F2 on its keyboard; needs
    root).
- **Handheld buttons**:
  - bind a button by pressing it; the row should show the new key and the next Set up should use it;
  - bind a D-pad direction by pressing the D-pad: the row should say Up / Down / Left / Right;
  - bind each stick by moving it: the row should say its axes (X / Y, Z / RZ), and *Automatic*
    should name the axes found;
  - check that A still works right after binding.

## 13. Emulator controls (the other emulators)

Set up writes the same buttons into RetroArch, PPSSPP, ARMSX2, Azahar, melonDS, SeedlessDS,
Flycast, Mupen64Plus and MAME4droid. Only unit tests cover these: nothing has been checked in a
real emulator yet. For each one that is installed:
- **Setup**:
  - Before starting it once, Set up should say "Start <name> once, then set up again" (not
    for RetroArch, which already has a config).
  - After starting it once, Set up should report it as set up. The dialog lists each emulator's
    result.
- **Buttons**: every face button by position (the bottom one is the system's bottom one), the
  D-pad, sticks and triggers it has. Check that a button the system lacks does nothing unexpected.
- **Hotkeys**:
  - RetroArch, PPSSPP, ARMSX2, Flycast: Select + R1 fast forward, Select + L1 screenshot.
  - melonDS: R2 fast forward, L2 swap screens. Azahar and SeedlessDS: R3 fast forward, L3 swap
    screens.
  - PPSSPP: L2 opens its menu, R2 fast forwards while held.
  - Mupen64Plus: R2 fast forwards while held.
- **Save / Load** with the official builds (MainUI presses the emulator's keys on its keyboard;
  needs root): PPSSPP, ARMSX2, Azahar, melonDS, Flycast, Mupen64Plus.
- **Per emulator**:
  - RetroArch: binds should win over the controller's autoconfig. The in-game menu's
    Save / Load / Menu (F keys) should still work, and holding Select shouldn't block them.
  - Flycast: a Naomi or Atomiswave game should use the arcade layout, with coin on Select.
    `mappings/` should hold `Android_<controller>.cfg`, `..._arcade.cfg` and
    `Android_MainUI Keyboard.cfg`.
  - Mupen64Plus: Settings > Controller profiles should list "MainUI", picked for player 1.
    Check the C buttons (right stick, or the D-pad without one) and Z on L2.
  - MAME4droid: its key settings should show the handheld's buttons as a custom profile ("custom
    profile" toast in green). Check that coin and start work, and that Back still opens the exit
    menu.
  - Azahar: check whichever settings form the installed version has (newer: Settings > Controls
    should list the gamepad buttons).
