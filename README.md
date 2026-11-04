# PocketOuya

Play OUYA games on a modern Android phone.

> PocketOuya is an independent fan project. It is not affiliated with, endorsed by or connected
> to OUYA, Razer or any game publisher. Use it only with game files you are entitled to use.
> PocketOuya never unlocks paid content: games' own purchase checks are always left unchanged.

The OUYA ran Android 4.1, so its games are ordinary Android apps, but current Android versions
refuse to install apps built for anything that old, and many of those games no longer start on a
modern phone. PocketOuya converts a game's original `.apk` so it installs and runs:

1. **Add game** and pick the game's original, complete `.apk`.
2. PocketOuya raises the app's target Android version, applies any known compatibility repairs,
   optionally adds on-screen controls, and re-signs it.
3. Android installs the converted copy and it appears in PocketOuya's library.

**Download:** the newest build is on the [latest release](../../releases/tag/latest).

## Requirements and limits

- OUYA games are 32-bit ARM. Your phone must still run 32-bit apps (for example the Snapdragon
  8 Gen 2 in the Galaxy S23); some newer 64-bit-only phones cannot.
- Compatibility differs per game. Many start and play; others need repairs that don't exist yet.
  Games that depend on the closed OUYA store or account servers may not work.
- If the original, unconverted game is installed, uninstall it first: the signatures differ.
  Back up any saves before uninstalling.
- [Shizuku](https://shizuku.rikka.app/) is optional. It enables the virtual controller, game
  installs without prompts and on-phone log capture.

## Controls

A physical Bluetooth controller works best. Converted games can also get an on-screen pad laid
out like the OUYA controller: two analogue sticks, d-pad, O/U/Y/A, L1/L2, R1/R2 and Menu.
**Hide pad** hides it; **Input** chooses how it reaches the game:

- **Auto** uses the game engine's own input route where PocketOuya knows one (OUYA Unity,
  GameMaker, Unreal Engine 3, Adobe AIR, Marmalade), otherwise the virtual controller.
- **Virtual controller (Shizuku)** creates a real Android controller named "OUYA Game Controller",
  so games that only accept a physical device see one. See [details](docs/virtual-controller.md).
- **Android gamepad** and **Keyboard** send gamepad or keyboard events inside the game.

More: [touch controls](docs/touch-controls.md).

## Other features

- **Store:** browse and download games from an OUYA community server. The default is
  [ouya.cweiske.de](http://ouya.cweiske.de), run by Christian Weiske with his open-source
  [stouyapi](https://github.com/cweiske/stouyapi). PocketOuya is not affiliated with it.
  Downloads go through the normal conversion. See [store](docs/store.md).
- **APK compatibility doctor:** scans an APK against your phone, lists known fixes and open
  issues, and can prepare a repaired copy. See [doctor](docs/apk-doctor.md).
- **Startup repairs:** fixes for specific engine builds (Unity, Marmalade, Adobe AIR and others)
  that crash on modern Android, matched by exact library fingerprints rather than game names.
  See [native runtime repair](docs/native-runtime-repair.md) and
  [shared compatibility rules](docs/shared-compatibility-rules.md).
- **Logs:** a global game log with full export, and crash capture through Shizuku or from a
  bug-report file. Nothing is uploaded. See [global log](docs/global-game-log.md) and
  [crash capture](docs/crash-capture.md).
- **GameMaker engine (beta):** a built-in translated engine for some GameMaker games.

## Building

GitHub Actions builds the APK on every push (`.github/workflows/android.yml`). Locally, run
`gradle assembleDebug`. The host test suite is `gradle :app:testPurchaseProfile`; the task name
is historical. Signing is explained in [docs/signing.md](docs/signing.md), and the development
history in [docs/development-log.md](docs/development-log.md).

## Licence

PocketOuya is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; see
[LICENSE](LICENSE) for the full text.

Copyright (C) 2026 Hcode91

Third-party components keep their own licences: Shizuku API (Apache-2.0), dexlib2/smali
(BSD-3-Clause), apksig (Apache-2.0), Guava (Apache-2.0), dynarmic (0BSD) and Boost (BSL-1.0).
OUYA is a trademark of its respective owner.
