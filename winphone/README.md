# Windows Phone edition

The Windows Phone edition of PocketMicro runs Windows Phone 7/8 games through
[WPR (Windows Phone Runner)](https://github.com/Bubbleshum/WPR), built into PocketMicro. It is one
app: PocketMicro's library and menus, with WPR's game runtime inside. WPR's own launcher screens
and its online Hub are not used.

## What works

WPR runs a game's original files. When a game is installed, WPR rewrites the game's .NET code so
its Windows Phone, Silverlight and XNA calls go to WPR's own versions; the game then runs on SDL and
FNA. On Android:

- **XNA games** work best.
- **Silverlight** games are partial.
- **Unity** games and **C++ (WP8 native)** games are not supported here.

Only game files that can be read as they are can be installed. Copy-protected Store packages are
not supported.

## Using it

- **MANAGE → Add Windows Phone game** picks a `.xap` and installs it. The game then appears in PLAY.
- Long-press a Windows Phone game for **Play with logging**, **Export Windows Phone logs** and
  **Remove**.
- In a game, the **☰** button at the top left has **Back** (one Back press, for gesture
  navigation) and **Quit** (tap twice). Quit lets the game close properly, which is also what makes
  a WP8 C++ game write its summary of unanswered Windows calls to the log.
- Every launch and how it ended goes into PocketMicro's global log.

## Logs

**Export Windows Phone logs** (MANAGE, or a game's long-press menu) saves one `.zip` with:

- `summary.txt`: device, WPR patcher version and every installed game (type, version, patch level)
- `Logs/`: WPR's session logs from every process (launcher and game)
- `last_game_error.txt`: why the last game stopped, when it stopped with an error
- `Games/<id>/wpr_game_debug.log`: the detailed per-game log, written by **Play with logging**

## How it is built

`.github/workflows/android.yml`, job `winphone`:

1. Gradle builds PocketMicro as an Android library (`-PpocketouyaLibrary`) and collects it with its
   dependencies into `build/winphone-libs` (`:app:collectWinPhoneLibraries`). `winphone/dedupe-wp-libs.py` then
   removes any Java class WPR's NuGet packages already ship, so D8 never sees one twice.
2. `winphone/wpr` is WPR-droid (https://github.com/Hcode91/WPR-droid), PocketMicro's fork of WPR
   0.2.09, pinned as a submodule. Runtime fixes and the in-game controls live there. `prepare-wpr.py` adapts its Android
   head: PocketMicro's package name, label and icon; PocketMicro's MainActivity as the launcher; WPR's
   game process renamed `:wpgame` (PocketMicro has its own `:game`); the library and the bridge
   (`Bridge/WinPhoneBridgeActivity.cs`) added to the build.
3. .NET builds and signs the APK with PocketMicro's key and run number, so it installs over the
   normal PocketMicro APK and the other way round.

The bridge is the only link between the two halves: PocketMicro's `WinPhone.java` starts it with an
action (install, launch, uninstall, export logs), and it writes `files/winphone/games.json` for the
library.

## Licences

PocketMicro is GPL-3.0. WPR is MIT-licensed (Copyright (c) 2026 MediaExplorer; see
`winphone/wpr/LICENSE`) and includes components under their own licences (FNA, SDL, FAudio,
FFmpeg and others; see WPR's repository).
