# Windows Phone edition

The Windows Phone edition of PocketOuya runs Windows Phone 7/8 games through
[WPR (Windows Phone Runner)](https://github.com/Bubbleshum/WPR), built into PocketOuya. It is one
app: PocketOuya's library and menus, with WPR's game runtime inside. WPR's own launcher screens
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
- Every launch and how it ended goes into PocketOuya's global log.

## Logs

**Export Windows Phone logs** (MANAGE, or a game's long-press menu) saves one `.zip` with:

- `summary.txt`: device, WPR patcher version and every installed game (type, version, patch level)
- `Logs/`: WPR's session logs from every process (launcher and game)
- `last_game_error.txt`: why the last game stopped, when it stopped with an error
- `Games/<id>/wpr_game_debug.log`: the detailed per-game log, written by **Play with logging**

## How it is built

`.github/workflows/android.yml`, job `winphone`:

1. Gradle builds PocketOuya as an Android library (`-PpocketouyaLibrary`) and collects it with its
   dependencies into `build/winphone-libs` (`:app:collectWinPhoneLibraries`).
2. `winphone/wpr` is WPR, pinned as a submodule (0.2.09). `prepare-wpr.py` adapts its Android
   head: PocketOuya's package name, label and icon; PocketOuya's MainActivity as the launcher; WPR's
   game process renamed `:wpgame` (PocketOuya has its own `:game`); the library and the bridge
   (`Bridge/WinPhoneBridgeActivity.cs`) added to the build.
3. .NET builds and signs the APK with PocketOuya's key and run number, so it installs over the
   normal PocketOuya APK and the other way round.

The bridge is the only link between the two halves: PocketOuya's `WinPhone.java` starts it with an
action (install, launch, uninstall, export logs), and it writes `files/winphone/games.json` for the
library.

## Licences

PocketOuya is GPL-3.0. WPR is MIT-licensed (Copyright (c) 2026 MediaExplorer; see
`winphone/wpr/LICENSE`) and includes components under their own licences (FNA, SDL, FAudio,
FFmpeg and others; see WPR's repository).
