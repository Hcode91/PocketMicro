# PocketOuya

Play Ouya games on a modern Android phone.

The Ouya ran Android 4.1, so its games are ordinary Android apps – but Android 14 and newer
refuse to install apps built for anything older than Android 6. PocketOuya fixes that:

1. **Add game** – pick an Ouya game's `.apk`.
2. PocketOuya raises the app's target Android version (a single number inside the app's
   manifest), removes the old signature and re-signs it with its own key.
3. Android installs it, and it appears in PocketOuya's library alongside any other app that
   declares itself an Ouya game (`tv.ouya.intent.category.GAME`).

**Download:** the newest APK is on the [latest release](../../releases/tag/latest).

## Notes
- Most Ouya games were designed for a controller; a Bluetooth controller works best.
- Ouya's game code is 32-bit ARM. Phones whose chips can still run 32-bit apps (e.g. Snapdragon 8 Gen 2)
  run them; some newer 64-bit-only phones can't.
- Games that rely on the Ouya store/account services may not start yet – PocketOuya doesn't
  provide general OUYA services. Experimental touch controls are available during conversion (see below). PocketOuya does not unlock paid content.
- If you have the original (un-patched) game installed, uninstall it first – the signatures differ.

## APK compatibility doctor

Use **APK COMPATIBILITY DOCTOR** to scan an original APK against this phone,
review known fixes and unresolved issues, prepare a separately signed copy, and
export the APK/report or explicitly install it. The first version handles SDK
target conversion and fingerprinted native repairs. It does not add touch
controls. It cannot guarantee a launch or repair every Android/API/CPU
incompatibility. See [workflow and limitations](docs/apk-doctor.md).

## Touch controls (experimental)

Conversion can now inspect games by their activity and SDK layout, across multiple
DEX files. It no longer requires Knightmare Tower's package name or DEX fingerprint.
**Knightmare Tower 2.1.0 touch input and Vector 1.0.7 startup plus embedded
touch input were confirmed working by the owner.** Other games remain unverified. The supplied **Final Fantasy III 1.0.1** has legacy
startup incompatibilities; see [its investigation](docs/final-fantasy-iii-compatibility.md).

1. Install the `feature/ouya-purchase-compatibility` branch's **PocketOuya-apk**
   artifact from [GitHub Actions](../../actions/workflows/android.yml).
2. Choose **Add game** and select the **original complete APK**, with its assets.
3. Select **Touch controls — OUYA / Android input**. For a
   supported legacy runtime, choose **Startup repairs + touch — Unity / Marmalade / AIR / Frima**.
   Normal conversion and repair without controls remain available.
4. Read the conversion report. It identifies which features could be added.
5. Install the conversion and use ordinary **Play**. **Engine test** is separate
   and does not receive this injected pad or run Unity games.

Updating PocketOuya alone cannot update compatibility code already embedded in a
game: **reimport the original APK**. An already modified APK is rejected for
compatibility reinjection. The same bundled game signing key is retained, so an
existing PocketOuya-signed copy should update without deleting its saves. A copy
signed by someone else may require uninstalling; back up saves before doing so.

### Touch controller

The game-owned pad includes directions, O/U/Y/A, L1/R1, Start, Select and Menu.
Separate fingers can hold movement and buttons together. **Hide pad** releases
held input; **Show pad** restores the controls. Pause, focus loss, cancellation
and detachment also release held buttons and axes.

Use **Input** to choose a mode; the game remembers the selection:

- **Auto** uses the known OUYA Unity native handlers where available, as player 1.
  This is the route confirmed with Knightmare Tower. For Marmalade activities
  (including the inspected Vector build), **Auto (Marmalade keys)** calls the
  game's key callbacks directly, preserving game overrides and providing digital
  directions. Otherwise it uses Android gamepad events inside the running game.
- **Android gamepad** dispatches gamepad buttons and joystick/hat axes; Unity's
  own input method is used when available.
- **Keyboard** sends arrows, O=Space, U=X, Y=Y, A=Escape, L1=Q, R1=E,
  Start=Enter and Select=Tab. Menu keeps its Android menu key code.

The pad is embedded during conversion and does not require Shizuku or a system
overlay permission. Its buttons cannot take keyboard focus away from the game.
A common layout does not guarantee a common input implementation: games requiring
a physical device or analog-only input may still ignore it. The Marmalade
route has automated callback tests and was confirmed working with Vector 1.0.7. See
[touch control details](docs/touch-controls.md).

The launcher must have a recognizable Activity hierarchy. Lifecycle hooks also
watch subsequent activities from the game package and common game engines.
Custom activity layouts, engines or games requiring a physical InputDevice can
still reject virtual input. No universal controller compatibility is claimed.
These controls need no system overlay, accessibility service, root or global
input-injection permission. They operate inside the converted game's process.

### Purchases

Offline purchase emulation has been removed. Games' own purchase checks are
always left unchanged, and PocketOuya does not unlock paid content.

### Validation and diagnostics

Validation: `bash scripts/test-compatibility.sh` and
`gradle :app:testPurchaseProfile` (host test suite; the task name is historical),
plus Android Java/bridge compilation in CI. Synthetic tests cover multiple DEX
files, activity aliases and inherited lifecycle methods.

Conversion also reports detected native text-relocation libraries, which Android
rejects when loaded at target API 23+. Optional compatibility hooks are skipped
for DEX files containing unknown/reserved instructions; the original bytecode is
preserved. These diagnostics do not repair a legacy runtime or prove a game can
launch. Full details are in the FFIII investigation above.

## On-phone crash logs

Long-press an installed game and choose **Capture crash log**. Optional Shizuku
access enables a 60-second crash-buffer capture while launching the game; return
and tap **Stop / show log**, then Copy or Save the extracted report. Phone-only
wireless-debugging setup is supported by Shizuku on Android 11+.

Without Shizuku, **Import bug report** can read a Samsung bug-report ZIP or text
file and extract identified Java/native crash blocks for that game. Nothing is
uploaded. Review extracted details before exporting them; no matching entry does
not mean the game did not fail. See [setup, limits and privacy details](docs/crash-capture.md).
Device capture still needs phone testing.

### GameMaker 32-bit engine controls (Maldita Castilla)

For games already working through PocketOuya's translated GameMaker engine,
long-press the game and choose **Play with GameMaker engine + touch (beta)**.
The pad is built into that game screen; updating PocketOuya is enough and no game
reconversion is needed. The inspection-only **Engine test** option is separate.

Directions and O/U/Y/A, L1/R1, Start, Select and Menu queue keys directly to the
runner. Default face keys are Z/X/C/Space; L1/R1 use Shift/Ctrl, Start uses Enter,
Select uses Tab and Menu uses Escape. These are key bindings, not guaranteed game
actions. The labels show each binding. **Controls** changes individual buttons
(including an OUYA-key choice) or resets them; settings are saved per game.
**Hide pad / Show pad** keeps physical controllers usable. Input is disabled until
the engine starts, and pause/focus loss/dialogs release held input. Touch and
physical inputs sharing a key do not release each other's held state.

Android compilation and input ownership tests pass. The existing Maldita engine
path was previously confirmed working; its new touch pad still needs a phone test.
This does not add GameMaker engine support to Unity, Marmalade or AIR games.

## What each conversion option attempts

| Menu option | Purpose and current scope |
| --- | --- |
| Android install compatibility | Updates old manifest targets and signs the APK; no engine repair or touch injection. |
| Touch controls — OUYA / Android input | Embedded pad: OUYA Unity native input, Marmalade key callbacks, or Android gamepad/keyboard fallback. |
| Startup repairs — Unity / Marmalade / AIR / Frima | Exact-library fixes: Unity 4.3.4f1 NativeActivity main/Mono/player loading, Unity 4.1.1f4 embedded-runtime promotion/loading (Abbigale), Unity/Mono 4.1.5f1 loading, Vector 1.0.7 Marmalade loading/JNI surface release, inspected Adobe AIR code literals and Frima Space Shooter 1.1.3 initializer. |
| Startup repairs + touch — Unity / Marmalade / AIR / Frima | Same startup fixes plus the embedded pad. |

The conversion report names the engine/component and problem for each matched
native repair, alongside unchanged/unsupported components. Engine names do not
imply all versions are supported. Inspected AIR Core/native-extension and Frima
Space Shooter 1.1.3 builds now have fingerprinted text-relocation repairs. AIR
can still fail on obsolete private media APIs; Final Fantasy III startup remains
unsupported. See [AIR/Frima evidence and Abbigale payload requirements](docs/air-frima-runtime-repairs.md).

## Native runtime repair across games (experimental)

Choose **Startup repairs — Unity / Marmalade / AIR / Frima** when adding any game's
original complete APK. Repair matching uses exact native-library fingerprints,
not a game/package name. The catalog supports the inspected ARM32 Mono/Unity
4.1.5f1 builds (direct or verified proxy/embedded layouts), the inspected Unity
4.1.1f4 Abbigale payloads and Unity 4.3.4f1 NativeActivity main/Mono/player builds, plus the inspected
Vector 1.0.7 Marmalade library and three extension libraries. Vector repair also
guards the null JNI buffer release identified in the follow-up phone crash.
It checks each component independently and keeps
existing ABI directories.

The report lists repaired files, already-repaired files and unsupported candidates.
Unknown versions, missing payloads and unsupported ABIs stay unchanged. A partial
repair can leave another startup blocker; this is not a universal text-relocation
fix. Startup repair without touch adds no controls. Choose **Startup repairs + touch — Unity / Marmalade / AIR / Frima** to also embed the pad; its DEX compatibility
is reported separately. Use normal Play and
capture a fresh crash if it fails. Vector 1.0.7 launch and touch input were confirmed on the owner's phone;
Abbigale launch was also confirmed for its inspected runtime pair. Other game/build combinations remain unverified.
See [Unity NativeActivity evidence](docs/unity-nativeactivity-runtime-repair.md), [catalog behavior](docs/native-runtime-repair.md) and
[the original runtime investigation](docs/muffin-knight-runtime-repair.md).


## One global game log and full export

Every game launched through PocketOuya appends to a single persistent journal,
with the **game name followed by log output**, whether or not it crashes. Open
**GLOBAL LOG / EXPORT** in the library, then **Export full log** to save the entire
journal as one text file using Android's file picker. The on-screen preview shows
only the latest 256 KiB; export includes older saved entries as well. No reconversion
is needed for this launcher feature.

For installed games' normal and crash output, keep Shizuku running with PocketOuya
access granted. Without it, launch events and available built-in-engine output
are still saved, and missing access is stated in the journal. Capture runs while
the game is in front and stops on return to the library or when capture is stopped.
Android/log-reader limits can leave gaps; no crash text does not prove success.
The saved journal persists until cleared or app data is removed. It is not uploaded.
See [capture, storage and export details](docs/global-game-log.md).


The owner's multi-game log identified a missing `glStencilMask` in the GameMaker
bridge. The engine now forwards the GLES2 stencil state calls (mask, function,
operation and clear value, including separate-face variants) to the real host GL.
Abbaye des Morts needs another phone run; its boot-test success only establishes
that it reaches graphics, not that the full game works. The global logger also
retains pending startup capture briefly after a fast return so a cold Shizuku
connection can read the buffered launch/crash output.


Startup repairs now include **Frima / Space Shooter 1.1.3** and the inspected
**Adobe AIR Core and native-extension** libraries. Choose either engine startup
repair option when importing the original complete APK; the report names the
matched files. Zombie Toaster is only covered if its AIR library hashes match.
Abbigale's full Unity 4.1.1f4 payloads are now fingerprinted. For a matching
original complete APK, startup repairs promote the repaired real Mono/player
runtimes from assets/libs into the installed lib paths, bypassing its legacy
embedded loader. The owner confirmed that this resolves the repeating native
string-error launch crash for the inspected build. The report should show two real runtimes
repaired/promoted. Unknown/missing payloads retain the partial loader-only option
and its explicit limitations. No other game's runtimes are substituted.

## Licence

PocketOuya is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; see
[LICENSE](LICENSE) for the full text.

Copyright (C) 2026 Hcode91

Third-party components keep their own licences: Shizuku API (Apache-2.0), dexlib2/smali
(BSD-3-Clause), apksig (Apache-2.0), Guava (Apache-2.0), dynarmic (0BSD) and Boost (BSL-1.0).
