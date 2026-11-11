# MainUI State API

Lets MainUI save and load states in an Android emulator directly, with broadcasts, instead of
pressing hotkeys with root. The emulators' MainUI builds
([MainUI-Android](https://github.com/MainUI-Android): Dolphin, melonDS, Flycast, ARMSX2, ARMSX3,
hakuX) carry it; MainUI's side is `app/src/main/java/.../emu/StateApi.kt`.

## Protocol (version 1)

- The emulator declares `<meta-data android:name="io.github.mainui.STATE_API" android:value="1"/>`
  in its application and an exported receiver
  `io.github.mainui.stateapi.MainUIStateApi$Receiver` for `io.github.mainui.stateapi.COMMAND`.
- MainUI sends that broadcast to the emulator's package (foreground queue) with extras `command`
  (`save` / `load` / `status`), `slot` (0-based; `-1` is MainUI's own resume state, never one of the
  user's slots), `request` (an id), `reply_to` (MainUI's package), `wait_ms` (how long to wait for
  the game to be ready, 15000 by default), `settle_ms` (how long it must have been ready, 300 by
  default) and `not_before` (`SystemClock.uptimeMillis`: only a game attached at or after it counts).
- The emulator waits for the best moment ([`MainUIStateApi.waitReady`](MainUIStateApi.java)): a game
  attached, running and ready (unpaused, not loading, not in its menu: whatever the emulator can tell)
  steadily for `settle_ms`. Then it saves or loads on a worker thread and answers with
  `io.github.mainui.stateapi.RESULT` to `reply_to`: `request`, `ok`, `error` (words for the user),
  `path` (save), `version`; `status` answers `running`, `ready`, `slots`, `slot_times` (ms, 0 =
  empty) and `resume_time`.
- The emulator must not be frozen (SIGSTOP) while a command is under way: MainUI thaws it first.

## Files

- `MainUIStateApi.java`: the emulator side, the same file in every fork
  (`io/github/mainui/stateapi/`); each fork adds an `Emulator` adapter (`isRunning`, `isReady`,
  `slotCount`, `save`, `load`, `slotTime`) and attaches it while a game runs.
- `ci.sh`: the forks' CI helper (`.github/mainui/ci.sh` in each): nightly upstream merge, the stable
  release to build (upstream's newest release matching the fork's tag pattern), cherry-picking the
  patch onto it, version numbers, signing (organization secrets `MAINUI_KEYSTORE` (base64),
  `MAINUI_KEYSTORE_PASSWORD`, `MAINUI_KEY_ALIAS`, `MAINUI_KEY_PASSWORD`) and publishing.

## Forks

| Fork | Upstream | Adapter | Resume state |
|---|---|---|---|
| dolphin-mainui | dolphin-emu/dolphin | `DolphinStates.kt` (slots 0-9: Dolphin's own) | `StateSaves/<game id>.mainui.sav` |
| melonDS-android-mainui | rafaelvcaetano/melonDS-android | `MelonStates.kt` (slots 0-8) | `<rom>.mainui` beside the slots |
| flycast-mainui | flyinghead/flycast | `FlycastStates.java` + JNI (`gui_mainuiState`) | slot 99 |
| ARMSX2-mainui | ARMSX2/ARMSX2 | `Armsx2States.java` (`saveStateToSlot` / `loadStateFromSlot`) | slot 99 |
| armsx3-mainui | ARMSX2/ARMSX3 | `Armsx3States.kt` (waits for RPCS3's stop-and-restart) | slot 99 |
| hakux-mainui | rfandango/hakuX | in `NativeCoreActivity` (a load restarts the core: it answers first) | slot 99 |

Every fork's default branch is upstream plus the patch commits; keep the patch small and in new
files where possible, so nightly merges stay clean.
