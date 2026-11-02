# PocketOuya – plan & progress

Goal: play Ouya games on a modern 64-bit-only Android phone (owner's device: Samsung Galaxy S23,
Snapdragon 8 Gen 2, One UI 6+ → **no 32-bit app support**).

This file is the hand-off between sessions. **Read it first, update the Progress log last, push often.**

---

## What we know

### Why Ouya games don't run as-is
1. **Old target SDK.** Ouya games target Android 4.1 (API 16). Android 14+ refuses to install
   apps targeting < 23. → **Solved** by PocketOuya v1 (patches `targetSdkVersion` to 23 in the
   binary manifest, re-signs with apksig, installs via PackageInstaller).
2. **32-bit native code.** Ouya = Tegra 3 (ARMv7). Game engines ship only `armeabi`/`armeabi-v7a`
   `.so` files. The S23 has dropped 32-bit support (system dialog: *"This app isn't compatible with
   the latest version of Android"*). → **Unsolved – this is the project.**
3. **Ouya services** (store/IAP/account, `tv.ouya.console.api`). Not needed by the first test game.
   Some games will need service compatibility later. The owner has now requested investigation of Knightmare Tower purchase compatibility through Engine test.

### Test game: Maldita Castilla 1.0.20 (`com.locomalito.malditacastilla`, freeware)
- GameMaker: Studio 1.x runner, 2013. Package classes `com.locomalito.malditacastilla.RunnerActivity` etc.
- Native libs: `libyoyo.so` (GameMaker runner, ~3.2 MB) + `libopenal.so`, for armeabi, armeabi-v7a, x86, mips. **No arm64.**
- Game data: `assets/game.droid` (49 MB), IFF `FORM` file. **GEN8 bytecode version = 13** (early GMS 1.x).
- Manifest: minSdk 7, targetSdk 16, GLES 2.0 required, declares `tv.ouya.intent.category.GAME`.
  Uses no Ouya SDK classes at all.
- PocketOuya v1 installs it fine on the S23; it then fails with the 32-bit dialog.

### Approaches considered
| | Approach | Verdict |
|---|---|---|
| A | **Engine swap**: run the game data on a *64-bit* GameMaker runner | Test first – full native speed if it works |
| B | **so-loader + dynarmic**: load the 32-bit `libyoyo.so` in-process, execute it with an ARM32 JIT, fake the Android/JNI/GL environment | Main fallback – proven on PS Vita (YoYo Loader) |
| C | Full-system emulation (QEMU + user-supplied Android 7 image) | Rejected for now: whole-OS emulation is slow and needs a GPU passthrough (gfxstream-scale work) |
| – | vPhoneOS (closed-source virtual Android that claims 32-bit support on 64-bit phones) | Owner was trying it; couldn't import an APK yet. Result would show achievable speed. |

---

## Approach A – engine swap (try first)

Idea: in GameMaker games, the game is `game.droid` (data + bytecode); `libyoyo.so` is only the player.
A 64-bit player + compatible data = native speed, real GPU.

Problem: Maldita Castilla's data is **bytecode 13**. 64-bit Android runners only exist in
GameMaker Studio 2 (2019+), which expect **bytecode 17+** and a newer chunk layout.

Steps:
1. Research which GMS versions shipped an **arm64 Android runner** and which bytecode versions they accept.
2. Research **UndertaleModTool** (open source, reads/writes GM data files): can it load BC13 and
   save as BC16/17? Is there any existing "upgrade data.win" tooling?
3. Runner source: the 64-bit `libyoyo.so` must come from a GameMaker game the user owns (we can't
   redistribute YoYo's runner). Pick a free arm64 GMS2 game APK as donor; match its Java side
   (`RunnerActivity`/`RunnerJNILib` call signatures) – the old game's dex expects the old JNI API,
   so the donor's Java classes would likely be used instead.
4. Prototype on desktop first if possible (GM data files are platform-independent: `data.win` ≈ `game.droid`).
5. Decision gate: if BC13 → BC17 conversion isn't feasible in reasonable time, go to Approach B.

### Research result (2026-10-30) – A looks high-risk
- 64-bit Android runners arrived with **GameMaker Studio 2.1.5** (2018), which uses **bytecode 17**.
  No GMS 1.x runner ever shipped for arm64.
- Tooling can *parse* old data: **DogScepter** serialises format ID 13 up to 17; **UndertaleModTool**
  reads bytecode 13–17. But no tool *converts* BC13 games to BC17 – that means re-encoding VM
  bytecode (instruction format/semantics changed), adding GMS2 chunks, and remapping built-in functions.
- Runners are strict about data versions (e.g. Deltarune on Linux only ran on runner V1.3, i.e.
  runtimes 2.1.4.202–2.2.1.287), so even a converted file would need a carefully matched runner.
- **Decision: go with Approach B.** Revisit A only if a "BC13→BC17 upgrader" appears or B stalls.

## Approach B – so-loader + dynarmic (main engine)

Reference projects:
- **so-loader** (TheFloW) – ELF loader for Android ARM `.so` files on PS Vita.
- **YoYo Loader** (Rinnegatamante, `github.com/Rinnegatamante/yoyoloader_vita`) – so-loader
  specialised for GameMaker runners: knows which libc/JNI/GL/OpenAL imports `libyoyo.so` uses and
  how to fake the Java side. **Use as the map of what to implement.** Check its licence before reusing code.
- **FalsoJNI** (v-atamanenko) – fake JNI environment for so-loader ports.
- **dynarmic** (`github.com/lioncash/dynarmic` / Citra & forks) – ARMv7 (A32) JIT with an
  **arm64 host backend** (Citra Android runs 32-bit ARM code on 64-bit phones with it).

Key difference from the Vita: the Vita is 32-bit ARM so the game code runs natively and shims are
plain function pointers. Here guest code runs in dynarmic with a **32-bit address space** mapped
into the 64-bit host, so every host shim must translate pointers (guest addr + base) and convert
struct layouts (32-bit `long`/pointers, bionic32 `FILE`, `stat`, etc.).

Architecture (native lib `libpocketyoyo.so` inside PocketOuya, arm64):
```
PocketOuya GameActivity (Java, SurfaceView/GLSurfaceView, input, audio)
   │ JNI
   ▼
Host core (C++, arm64)
   ├─ Guest memory: reserve 4 GB, map ELF segments, guest heap (malloc → host allocator in-region)
   ├─ ELF32 loader: load libyoyo.so (+ libopenal.so or replace OpenAL with host OpenAL-soft), relocate,
   │                 resolve imports → "thunk" addresses
   ├─ dynarmic A32 JIT: runs guest code; SVC/thunk trap → host shim
   ├─ Shims: libc (string/stdio/pthread/time/math…), libm, liblog, libdl, libz,
   │         GLESv2 + EGL (pointer translation), OpenSL/OpenAL, android_* (AAsset etc.)
   └─ Fake JNI: guest-visible JNIEnv/JavaVM tables → host calls into the real game Java code or
                our own replacements (decide per method; YoYo Loader shows which ones matter)
```
Threads: each guest pthread = host thread with its own dynarmic CPU state (shared memory + JIT cache).

Milestones (each ends with a pushed, testable build):
- **M1 Load**: ELF32 loader + dynarmic hello-world; load `libyoyo.so`, resolve imports, log every
  unresolved symbol. Desktop/CI unit test first (dynarmic runs on x86-64 too). No phone needed.
- **M2 Boot**: call `JNI_OnLoad` and the runner's init (`RunnerJNILib.Startup` equivalent) with fake JNI;
  read `game.droid` from the APK; log until it reaches the first GL call.
- **M3 Picture**: GLES2 shims + EGL surface from Android `SurfaceView` → title screen visible.
- **M4 Input**: keyboard/gamepad (Ouya O/U/Y/A mapping) + optional touch overlay.
- **M5 Sound**: OpenAL (host OpenAL-soft) or OpenSL.
- **M6 Polish**: saves (redirect to app storage), performance (JIT cache, fastmem), PocketOuya integration
  (library detects GameMaker games: `assets/game.droid` + `libyoyo.so` and launches them in the engine).

Build: add CMake/NDK to the Gradle project (`externalNativeBuild`), dynarmic as a git submodule or
FetchContent. CI is GitHub Actions (`.github/workflows/android.yml`) – add the NDK step.

## Approach C notes (parked)
QEMU system emulation of an ARMv7 board running a user-supplied Android image. Blockers: whole-OS
TCG speed, and GLES2 needs either software rendering in the guest (very slow) or host GPU
passthrough (Android Emulator's gfxstream – huge to port). Revisit only if A and B fail.

---

## Working rules
- Push after every working step (sessions can end when usage runs out; the cloud workspace is temporary).
- Update the Progress log below at the end of each session: what's done, what's next, anything learned.
- No game files or YoYo runner binaries in the repo. Users supply their own APKs.
- Purchase compatibility must be explicit and title-specific; do not weaken host/device encryption or report success for unimplemented operations.

## Progress log
- **2026-10-30** – PocketOuya v1: APK patcher (targetSdk → 23), apksig re-signing, installer, console-style
  library. Confirmed S23 is 64-bit-only. Analysed Maldita Castilla (GMS1, BC13, 32-bit only). Wrote this plan.
  Researched Approach A → high-risk (see "Research result"); chose **Approach B**.
- **2026-10-30 (later)** – **M1 part 1 done**: `engine/` (C++20, CMake) with a 4 GiB guest address
  space, ELF32 ARM loader (REL relocations: RELATIVE/ABS32/REL32/GLOB_DAT/JUMP_SLOT, DT_INIT/INIT_ARRAY)
  and a thunk table (`svc #id; bx lr` stubs). `engine/tools/so_inspect` loads `libopenal.so` +
  `libyoyo.so` cleanly. Findings in `docs/libyoyo-imports.md`:
  - libyoyo needs **226 imports** (156 libc, 23 libm, 22 pthread, 11 zlib, 3 libdl, 2 log, 3 C++ ABI,
    5 data objects: `__sF`, `__stack_chk_guard`, `_ctype_`, `_tolower_tab_`, `_toupper_tab_`).
  - **No GL imports** – it `dlopen`s libGLESv2/libEGL; our `dlsym` must hand out GL thunks.
  - We can run the bundled **libopenal.so** as guest code (it only needs libc/pthread/libm/libdl/log),
    but it outputs through OpenSL/AudioTrack via dlopen – decide in M5 whether to shim OpenAL on host instead.
  - Java side: 36 `Java_com_yoyogames_runner_RunnerJNILib_*` natives + `JNI_OnLoad` (class lives in the game's dex).
  M1 part 2 next (done below).
- **2026-10-30 (evening)** – **M1 part 2 + M2 done.** Engine now runs the game's 32-bit code:
  - `cpu.cpp`: dynarmic A32 JIT (fastmem over the 4 GiB guest space, shared exclusive monitor, nested
    guest calls via child CPUs, return-sentinel thunk). Built from `azahar-emu/dynarmic` @a466015 +
    Boost headers from `azahar-emu/ext-boost` (CMake FetchContent; local dirs via `PO_DYNARMIC_DIR`/`PO_BOOST_DIR`).
  - Shims: libc (memory/strings/ctype tables/stdlib/time/maths), stdio (FILE*, printf/scanf engine,
    files, dirs, bionic32 `stat`/`dirent` layouts), pthreads/semaphores (host threads, one Cpu each),
    zlib (z_stream mirroring), libdl, Android log, setjmp/longjmp, offline network stubs.
  - `jni.cpp`: fake JNIEnv/JavaVM (all 233 slots; strings = guest C strings, arrays = guest blocks);
    Java methods answered by name (`FakeJni::java`). `yoyo.cpp`: GameMaker boot (ctors, JNI_OnLoad,
    `RunnerJNILib.Startup`, `Process`) + console hooks (from YoYo Loader, MIT).
  - `yoyo_boot game.apk` on x86-64: loads game.droid, inits GML, reaches **Init Graphics → first GL call
    `glGetError`** (log: `docs/m2-boot-log.md`). Only JNI gap so far: `GetStaticObjectField`.
  **Next (M3):** GLES2 + EGL shims (pointer translation for glVertexAttribPointer/glBufferData/glTexImage2D…,
  string returns for glGetString), run on Android with a GLSurfaceView; Android build of the engine
  (NDK arm64, CMake via Gradle `externalNativeBuild`) and a GameActivity in PocketOuya. Then verify
  dynarmic's arm64 backend on the S23.
- **2026-10-30 (night)** – **M3a done: engine runs on the Galaxy S23.** Gradle builds the engine for
  arm64-v8a (NDK = runner's newest, `ANDROID_NDK_LATEST_HOME`; force-included `<cstdlib>/<cstring>` for
  fmt with new libc++; renamed our JNI header to `fake_jni.h`). Long-press a game → *Engine test* boots
  Maldita Castilla's 32-bit libyoyo.so through dynarmic's **arm64 backend** to `glGetError` in **0.19 s**,
  same log as on x86-64. Translation speed is not a concern so far.
  **Next (M3b):** game screen – `GameActivity` with a `GLSurfaceView` (GLES 2 context), run boot + `Process`
  on the GL thread, shim GLES2/EGL calls (start with the ones the runner imports via dlsym; pointer args
  translated from guest addresses; `glGetString` returns guest copies).
- **M3b (in progress)** – GLES2 shims generated by `engine/tools/gen_gl.py`; `GameActivity` (own `:game`
  process, GLSurfaceView) runs Startup + Process. First phone run: GPU init OK (Adreno, GLES 3.2), whole
  game.droid loaded, 435 objects prepared; stopped at OpenSL ES (`slCreateEngine`). Fixes: `dlopen` refuses
  libOpenSLES and `alcOpenDevice` is hooked to return NULL → game runs silently (sound = M5).
  `yoyo_boot --headless` (fake GL) now runs the main loop for 120 frames on x86-64.
  NB: "getTextureInfo :: a PNG error occured" is printed by GameMaker on *every* texture, success included
  (checked in the disassembly) – not a real error.
- **2026-10-30 19:00 – MILESTONE 3 REACHED on the Galaxy S23: Maldita Castilla's title screen renders
  correctly** (logo, lions, "PRESS FIRE", credits) through dynarmic + the GLES2 bridge, full screen.
  Known: log spam "OpenAL error 40964 (Audio_Tick …)" every frame because there's no audio device (M5).
  **Next (M4 – controls):** feed input into the runner. GameMaker 1.x Android: `RunnerJNILib.KeyEvent(type,
  keycode, …)`, `TouchEvent(type, id, x, y)`, gamepad via Java `GamepadConnected`/`GamepadDescription` +
  axis/button arrays (see YoYo Loader gamepad.c). Map a Bluetooth controller (Android KeyEvent/MotionEvent)
  and add an on-screen touch pad.
- **M4 piece 1 (controller/keyboard)** – found in the disassembly: `RunnerJNILib.KeyEvent(env, cls, action, keycode)`
  → `RegisterAndroidKeyEvent` queue → `IO_UpdateM` maps Android key codes (DPAD 19-22 → vk 37-40, DPAD_CENTER → space,
  ENTER → 13, BACK → 8, others as-is); action 0 = down, 1 = up. GameActivity forwards all key events (except volume and
  the phone's own Back) plus analog stick/hat → DPAD presses; engine delivers them before each `Process`.

- **2026-11-01 — Knightmare Tower purchase investigation:** owner selected
  Engine test. Added bounded, non-executing APK inspection to that path before
  guest boot: engine/native library indicators, OUYA purchase DEX markers and
  explicit unsupported-engine reporting. Host fixture tests cover GameMaker,
  Unity, AIR, arm64-only/missing runner and DEX markers across read boundaries.
  CI builds the investigation branch without replacing the main latest release.
  No Knightmare Tower APK was supplied, so its actual engine/SDK and receipt
  callbacks remain unverified. Xposed's Java Cipher hook does not run in the
  native engine; no purchase/unlock callbacks have been implemented or claimed.
  **Next:** inspect the owner's Knightmare Tower APK (or new report plus trace),
  identify actual Java/native purchase entry points and implement/test the
  title-specific service/callback bridge against those interfaces.

- **2026-11-01 — Knightmare Tower files inspected:** archive without assets is
  confirmed Unity 4.5.0f6 / Mono, armv7-only, with the OUYA Unity Java SDK.
  This cannot run through the current GameMaker-only boot. Mapped Java purchase
  and receipt delivery to UnitySendMessage/OuyaGameObject; details are in
  docs/knightmare-tower-compatibility.md. Added Mono/managed-assembly diagnostics
  and Unity purchase-marker detection with synthetic regression coverage.
  **Blocked inputs:** managed DLLs from assets/bin/Data/Managed are needed to
  identify the game-side entitlement conditions. Unity runtime support remains
  a separate prerequisite for executing callbacks through Engine test.

- **2026-11-01 — normal launch confirmed; offline purchase profile implemented:**
  owner clarified that Knightmare Tower launches after normal conversion and
  does not need Engine test. Added an opt-in, exact-DEX-fingerprint conversion
  profile for the supplied OUYA 2.1.0 build. Six verified Java Unity SDK entry
  points delegate to our injected DEX, which delivers local product/gamer/
  purchase/receipt callbacks and persists local durable purchase state. No
  Xposed/root/global Cipher hook is used. Normal conversion remains unchanged.
  Synthetic DEX and mocked-runtime tests pass, and rewriting the actual supplied
  archive preserves its resources/native libraries. No game files are committed.
  Actual unlock still needs a phone run with the complete original APK; the
  supplied archive omitted managed assets. See docs/knightmare-tower-compatibility.md.
  **Next:** build branch APK, reimport original full Knightmare Tower APK with
  offline profile, launch normally and use the purchase/unlock button. Investigate
  actual managed callbacks/logs if the title does not accept the local receipts.
- **2026-11-01 — Android compiler dependency fix:** the purchase/host-engine tests
  passed, but APK Java compilation failed resolving Guava ImmutableList exposed
  by dexlib2's API. Added explicit Guava 33.3.1-android to both the application
  and host-test configurations. Compiled all app Java against Android API stubs
  and reran protocol/DEX tests with that exact Guava variant. CI now compiles
  Android Java and generates the bridge before the expensive native-engine step
  so dependency/resource errors surface early. Full APK packaging remains CI's
  check; no actual game unlock has been verified.

- **2026-11-01 — injected touch controller:** owner has no physical controller
  and needs the normally converted Knightmare Tower menu/calibration/unlock
  inputs. Added a game-owned pad to the existing exact-build import profile,
  attached/released through preserved lifecycle methods. Verified SDK native
  handler signatures and button constants in supplied files; direct player-1
  dispatch avoids the InputDevice dependency. Added directions plus analog axes,
  OUYA face/shoulder/menu buttons, multitouch ownership, hide/show and release
  cleanup. Host state/reflection/DEX lifecycle tests and Android helper/D8 checks
  cover the implementation; real phone input still needs testing. Reimport the
  complete original APK with the combined touch/offline profile to embed it.

- **2026-11-01 — cross-game converter options:** owner confirmed the injected
  Knightmare Tower controls work, requested touch controls and an unlock attempt
  across games, and selected Final Fantasy III as the next title to investigate.
  Added independent touch/offline options and pre-install capability reports.
  Replaced the production exact-title/DEX gate with multi-DEX activity/alias and
  OUYA Unity API/callback matching. Unsupported purchase protocols retain their
  original checks; matching protocols use game-private receipt state and local
  identities. Lifecycle wrappers preserve original methods and bootstrap activity
  watching. Touch Auto retains Knightmare's native input path, with selectable
  Android gamepad/Unity input and keyboard fallback, Start/Select, and stored mode.
  No OS-wide input/overlay permission added. Original stored assets stream through
  rebuilding without whole-entry allocations; DEX analysis has size/count bounds.
  Cross-game fixtures, multidex injection, typed-string activity aliases, inherited
  lifecycle, unsupported reporting, feature/storage isolation and existing tests
  pass. All app Java and the Android bridge compile; D8 produces the helper. The
  owner's partial Knightmare archive still rewrites correctly with six purchase
  hooks and preserved native libraries/resources. Device compatibility for other
  games and game-side unlock acceptance remain unverified. Final Fantasy III files
  have not been supplied: inspect its manifest, DEX and relevant native/managed
  billing code before claiming its protocol is supported. Engine test is separate;
  this pad is injected into normally converted game APKs.
  **Next:** install branch Actions APK, reconvert complete original games, read
  capability report and test ordinary Play. Obtain Final Fantasy III manifest and
  classes*.dex (small ZIP without assets) to prioritize its exact input/purchase
  integration. Full APK packaging is checked by the new CI run.

- **2026-11-01 — FFIII supplied and startup blockers identified:** inspected owner's
  FFIII 1.0.1 (package com.square_enix.android_OUYA.FFIII) archive without assets.
  MainActivity static initialization loads legacy lib__57d5__.so before jniproxy.
  That library declares DT_TEXTREL, which conflicts with converted effective
  target 23; Android's DEX verifier also rejects the original classes.dex. Dummy
  class initializers contain reserved instructions, and a scratch reserialization
  still fails verification. jniproxy additionally uses a legacy absolute native
  library path. No launch/unlock fix was demonstrated; a device crash log is needed
  to establish the first failure. Details/hashes in docs/final-fantasy-iii-compatibility.md.
  Added bounded ELF program-header diagnostics for text relocations, exposed in
  Engine test and conversion reports (including normal mode), plus a converter
  guard that preserves all original DEX bytes and skips optional hooks/helpers
  for unknown/reserved instructions. This prevents misleading claims and
  unsupported rewriting; it does not fix the legacy runtime. No linker/security
  checks disabled. Existing tests, new ELF32/64 diagnostics/reserved-opcode tests
  and Android Java compilation pass. Real FFIII normal/compatibility archive
  rewrites preserve original DEX/resources/libraries and show correct warnings.
  **Next:** obtain exact device crash excerpt and/or a compatible original FFIII
  build. Reconstructing the wrapper/runtime is separate substantial work, not a
  generic receipt or input change. Assets are absent, so actual game initialization
  and entitlement remain untested. Owner paused the separate NativeActivity touch
  prototype for A Ride into the Mountains; it remains outside Git, unshipped.

- **2026-11-01 — phone-only crash collection:** owner parked FFIII, asked about
  global controls (Shizuku preference remains unanswered), then requested an app
  to collect crash logs without a computer. Added Capture crash log to each
  installed game's long-press menu. Optional Shizuku 13+ authorization binds a
  non-daemon, owner-UID-restricted user service running a fixed crash-buffer
  logcat command for at most 60 seconds/256 KiB; Capture and launch starts the
  selected game. Stop/show filters and displays identified Java/native crash
  groups; Copy and SAF Save export only the extracted report. No upload added.
  Without Shizuku, SAF bug-report import accepts Samsung ZIP/text reports, bounds
  temporary copying and decoded scanning, deletes temporary files and extracts
  selected-package/subprocess groups. Added source/bounds/privacy/setup docs in
  docs/crash-capture.md. Shizuku API/provider pinned at 13.1.5; provider is protected
  by INTERACT_ACROSS_USERS_FULL, activity is non-exported, no READ_LOGS grant,
  accessibility service or overlay permission requested by the launcher.
  Plain-Java tests cover fatal Java/native groups, package/subprocess boundaries,
  PID reuse and unrelated-data exclusion, UID/year formats and oversized/bounded
  input. Existing inspection tests and all app Java compilation with pinned API
  artifacts pass. Gradle dependency/manifest/APK checks run in CI. Automatic
  Shizuku access, capture lifecycle and Samsung report variants need real phone
  testing; absence of a matching crash is explicitly inconclusive. No new FFIII
  runtime work or global controller implementation was undertaken.
  **Next:** owner installs branch artifact, long-presses a game > Capture crash
  log, then uses optional Shizuku capture or imports an Android bug-report ZIP.
  Review/save extracted .txt before closing the capture screen and share it for
  diagnosis. If device access is denied, report the displayed capture status;
  do not claim ordinary app-only cross-UID logging is supported.

- **2026-11-01 — crash capture CI configuration fix:** initial crash-capture run
  41309076053 passed inspection/filter and purchase tests but failed AAR metadata
  validation because Shizuku provider's AndroidX annotation dependency requires
  android.useAndroidX. Enabled the project flag; no runtime capture logic changed.
  Replacement CI run verifies the Android dependency/manifest/Java step before
  the longer engine/APK build.

- **2026-11-01 — Muffin Knight native startup repair candidate:** owner supplied
  a Shizuku crash trace proving libmono.so TEXTREL rejection during Unity startup,
  then native/DEX files and embedded assets/libs/armeabi-v7a runtimes. Outer mono
  and unity libraries are proxy loaders; real payloads identify Unity 4.1.5f1.
  Found one non-writable relocation in real Mono and two in real Unity. Added an
  explicit Muffin Knight runtime-repair import option, separate from touch and
  purchase changes. It verifies package and four source library SHA-256 values,
  promotes the game's own embedded runtimes, changes initializer literals to
  PC-relative arithmetic, and routes a Thumb destructor thunk through the original
  writable GOT/PLT. Updates unwind metadata for the now-frameless tail call,
  compacts relocation tables/counts and checks all remaining relocation writes are
  in writable segments before removing obsolete DT_TEXTREL. Program segment
  permissions/layout unchanged; no OS loader check disabled or game files shipped.
  Repaired output hashes are checked too. Native warning inspection uses output
  replacement bytes, with its per-library cap raised to 16 MiB (32 MiB total).
  libjni.so remains unchanged and warns about TEXTREL; later managed/plugin use
  could still require another repair. Actual full-game launch is unverified.
  Validation: ARM/Thumb synthetic fixtures, relocation/count/section/unwind and
  fail-closed checks, prior host suites, and Android Java compilation pass. Actual
  Java repair bytes match an independent Python implementation; Unicorn 2.1.4
  checks affected code at three load biases, ARM/Thumb caller/callee combinations,
  preserved arguments/return/stack/register state and original RX/RW mappings.
  Rebuilding a scratch archive from owner's partial files plus embedded libraries
  changes only manifest and the two installed native libraries; DEX, assets and
  other native libraries preserved, no helper DEX. This archive omits other game
  assets and is not playable. See docs/muffin-knight-runtime-repair.md and optional
  scripts/test-muffin-runtime-pic.py for reproduction with user-owned APKs.
  **Next:** owner installs branch artifact, reconverts ORIGINAL COMPLETE Muffin
  Knight APK with the explicit runtime-repair mode, installs update, uses normal
  Play and captures a fresh crash log if another failure follows. No successful
  boot, touch/purchase behavior or gameplay is claimed from offline checks.

- **2026-11-01 — game-independent runtime repair catalog:** owner requested the
  native fix be usable with other games. Replaced the Muffin-specific import
  option/package/four-file gate with Attempt Unity runtime repair (experimental).
  UnityRuntimeRepair matches each Mono/Unity component by exact source/output
  fingerprints independent of game name. Known direct runtimes need no embedded
  assets; known proxy loaders require matching embedded payloads. Supports existing
  armeabi-v7a/armeabi directories without creating an ABI, caches identical output
  bytes, and recognizes already-repaired inputs. Unknown hashes/missing payloads,
  inspection limits and unsupported ABIs are left unchanged and reported; partial
  results are explicit. Failed patch/output validation still aborts conversion.
  The unchanged instruction engine was separated as ArmRuntimePicRepair; the
  initial catalog still contains only the inspected 4.1.5f1 Mono/Unity builds.
  No guessed version-string/offset matching, new game payloads, runtime swaps or
  extra OS permissions. Runtime mode remains separate from touch/purchases.
  Added docs/unity-runtime-repair.md and updated the original investigation/UI.
  Tests cover direct/proxy layouts, aliases, partial/unknown/missing/idempotent
  inputs, multiple catalog versions sharing a proxy and output-hash failure.
  Previous host suites and Android Java compilation pass. Actual supplied native
  bytes in proxy and direct/no-assets scratch APKs with package org.example both
  repair to prior validated hashes while preserving all other source bytes. The
  optional Unicorn check also passes the direct layout using original RX/RW
  protections and ARM/Thumb calling combinations. No additional game boot was
  demonstrated; unsupported native libraries and services can still prevent it.
  **Next:** owner installs new branch artifact, adds any ORIGINAL COMPLETE game
  APK, chooses Attempt Unity runtime repair, reads changed/unsupported counts,
  installs and uses normal Play. If no candidates match, no repair was applied;
  inspect a new game's native libraries/crash before extending the catalog.

- **2026-11-01 — Vector 1.0.7 Marmalade candidate:** owner supplied a crash trace
  proving libvector.so TEXTREL rejection, then a …5133 tokens truncated…on and nonpersistent upstream purchase handler disclosed.
  Offline options preserved. Unsupported SDK/developer fields and API30+ provider
  visibility remain unchanged/reported. No PHP implementation or binaries copied.
  Tests: protocol/transport errors and bounds, envelope decoding, purchase request
  correlation, Unity callbacks/failure routes/no invented local persistence,
  six DEX hooks, offline isolation, touch coexistence, complete ZIP helper/assets
  and API30 skip fixtures pass. App/bridge Android compile and D8 pass; prior
  host compatibility/native/log suites pass. docs/stouyapi-server.md has scope,
  privacy/setup and explicit device-test gaps. No public-server/gameplay success
  claimed. **Next:** install branch build, TEST CONNECTION on phone, save/enable,
  reconvert original supported Unity game with Community server (+touch), install,
  approve installed game's access, normal Play; export global log on failure.
  Device must validate provider authorization/revocation and real API coverage.


- **2026-11-01 — Remove stouyapi at owner's request; Abbigale new trace:**
  removed the entire 5fc257b server integration: settings/test UI, global enable,
  per-game approvals, exported provider, client, injected ServiceOuya, conversion
  modes, INTERNET permission and server-specific tests/docs. Pre-server offline,
  touch, native repairs and global logging restored exactly. Historical progress
  entry above retained as history, not an active feature. No installed-game/APK
  files can be changed remotely: prior server-converted games need reconversion
  from their original APK after installing this update.
  Abbigale 19:22 trace now SIGSEGV/SEGV_ACCERR with 512 frames and repeating
  anonymous offsets 0x7c3248/0x7d84e8/0x7d8784/0x7d8868, strongly suggesting native
  recursion/stack exhaustion. Different visible failure from TEXTREL rejection;
  no full-boot or correctness claim. Reinspected all supplied archives: only
  92,744-byte Mono and 47,688-byte Unity proxies plus OUYA extension; embedded
  assets/libs/armeabi-v7a runtimes absent. Proxy ELF load ranges end at 0x1fc84
  and 0x14988, so new anonymous offsets cannot be directly symbolized against
  supplied files. Added evidence/limits/next files to air-frima runtime document.
  No speculative recursion branch patch or stack-limit workaround. **Next:**
  original APK's assets/libs/armeabi-v7a/libmono.so and libunity.so, individually
  zipped if needed, full launch global log and tombstone maps when available.


- **2026-11-01 — Real Abbigale payloads supplied and fingerprinted:** owner
  supplied full Mono (3,917,412 bytes, SHA9816e13e...) and Unity (9,204,864 bytes,
  SHA2442a0cf...), unlike previous small proxies. Unity version 4.1.1f4. All four
  repeating anonymous-frame offsets map to this Unity C++ string/error cycle.
  strlen PLT0x63350/GOT0x8bbd74 feeds a size that exceeds _S_create's limit, then
  constructing the size-error message repeats. Message at0x80a4a0 matches runtime
  address; r4/r5=0xe63320e5 is consistent with a resolver/function-pointer return
  used as a length. IFUNC handling by the legacy embedded loader is a plausible
  cause, NOT proven without phone GOT/libc evidence. Controlled Unicorn test
  reproduces exact four frames with that return and avoids cycle with length22.
  Added two exact runtime catalog profiles using existing PIC primitive: Mono
  init0x15a60,target0x3bc2d0; Unity init0x64910,target0x8bd000,Thumb thunk0x64920,
  atexitPLT0x64028. Outputs e888ab00e407ab46d67d6ab32e7573ca0f571fcc9c3fe57d214c336e7e53498e
  and15b92db63f6869bd1f34f13db7a12a0e89bea9765f87d7f08b114cf490482746.
  Catalog promotes repaired actual assets/libs payloads into installed lib paths
  when known proxies match, bypassing custom symbol resolution through normal
  Android loading. No strlen/exception bypass, added stack, vendor substitution,
  segment permission/mapping change or embedded asset modification. Exact unknown
  builds still unchanged; old 4.1.5f1/loader/AIR/Frima/Vector profiles retained.
  Tests: actual archive two-payload promotion, output hashes, unrelated bytes,
  no repair-only helper, idempotence; combined real-APK touch hooks/helper plus
  two runtime promotions; instruction initializer/atexit argument/stack/interwork
  at three biases under RX/RW; modeled recursion/control; host suites; Android
  app/bridge Java and D8 pass. Optional script test-abbigale-runtime-pic.py uses
  user-owned APKs, no game/native bytes committed. Docs/labels updated.
  **Next:** install branch build, reconvert ORIGINAL COMPLETE Abbigale APK with
  Startup repairs (+touch if desired), verify TWO real Unity4.1.1f4 runtimes
  repaired/promoted rather than loader-only, install update, normal Play, export
  fresh global log on failure. Gameplay remains UNVERIFIED; further engine/JNI/
  Android/dependency issues possible. Server integration remains removed.


- **2026-11-01 — Global offline purchase default; Abbigale launch confirmed:**
  owner confirmed the 850753c real-runtime repair launches Abbigale. Recorded
  exact-build phone confirmation; does not prove resolver cause or purchase
  compatibility. Owner then requested a global option for offline purchase
  attempts across games. Added OFFLINE PURCHASE OPTIONS, private persistent
  default (initially off), and a visible per-conversion checkbox for every mode.
  Explicit offline modes preselect it too; individual overrides do not change
  global preference. Removed native-repair/offline mutual-exclusion guard so
  users can retain working startup fixes and touch while attempting local
  receipts. Reports remain separated; no new SDK adapter/universal unlock,
  server, network permission or runtime-wide hook. Existing installed games
  require original-APK reconversion; toggling default does not alter them.
  Updated UI/docs on local receipts, restarts/data removal, original game network
  behavior and unsupported SDKs. Native report no longer incorrectly says no
  purchase changes in combined mode; records Vector and Abbigale confirmations.
  Tests: combined native-inspection/touch/offline ZIP fixture validates six SDK
  hooks, helper and assets; prior host/native/receipt/log suites and Android
  compile pass. Actual supplied Abbigale conversion preserves BOTH repaired
  native output hashes/assets and touch helper, but SDK match is FALSE/zero
  purchase hooks. No purchase support/unlock is claimed for Abbigale; separate
  adapter needed. Docs/global-offline-purchases.md explains scope. No runtime or
  vendor bytes committed. **Next:** branch build, enable/save default, reconvert
  original APKs with desired startup/touch mode, review supported/unchanged
  purchase report, normal Play and try in-game purchase button where supported.
  Global-dialog behavior and game acceptance remain phone tests.


- **2026-11-01 — Unity 4.3.4f1 NativeActivity / AD2442 text relocations:**
  owner supplied libmain, Mono, Unity, OUYA extension and original manifest after
  NativeActivity rejected libmain for TEXTREL. All three runtimes use existing
  ARM initializer + optional Thumb atexit repair patterns, so added exact catalog
  profiles, not a new repair algorithm. Version string4.3.4f1(e444f76e01cd).
  Main source df20f390..., init0x21f8,target0x8000,thunk0x2208,atexitPLT0x20b0;
  Mono source3ff6f788...,init0x15b08,target0x3bd2b0; player source9f1bd219...,
  init0x6bb30,target0xa2e000,thunk0x6bb40,atexitPLT0x6b140. Outputs respectively
  bc152baf4f9d17ec8f47040b0988bc3a1b2b914d4b93c45f723cdac6f00e6326,
  580c868118528120640588c4f993cf25c1f16b7a77882262c7fd6092be06d9bd,
  2d11f8117a4db369788e3185d05185b39dff9cfdd08aa06c13ed47501be6633e.
  OUYA extension has no TEXTREL/nonwritable relocation and remains unchanged.
  Source manifest package com.burningthumb.mobile.AD2442,target16->23 explains
  linker enforcement; touch wrapper frame alone does not prove regression.
  Existing routine compacts code relocations/counts, updates thunk unwind and
  verifies all remaining writes target RW before clearing declaration. No
  mapping/permission/Android-policy changes or broad unknown-library patches.
  Tests: supplied manifest+native packaging fixture (NOT full game: no DEX/assets
  supplied), THREE exact native outputs, no TEXTREL, package/extension/asset
  marker preservation, no helper and idempotence. Unicorn initializer/atexit
  arguments, ARM/Thumb caller/target, stack/callee-saved registers and original
  RX/RW mappings at three biases pass. Optional test-ad2442-runtime-pic.py uses
  user-owned APKs. Prior host/native/receipt/log suites and Android Java compile
  pass. Labels/docs updated, no binaries committed. **Next:** install branch
  build, reconvert ORIGINAL COMPLETE game APK with Startup repairs (+touch if
  wanted), leave offline purchase checkbox off for isolated startup test, confirm
  THREE Unity4.3.4f1 files repaired, install update, normal Play and fresh global
  log on failure. No Android startup/gameplay/input/purchase success claimed.


- **2026-11-01 — APK compatibility doctor inside PocketOuya:** owner chose
  integration into PocketOuya rather than separate app. Added unexported doctor
  activity and home button: select original APK without install permission,
  bounded private streaming copy, non-executing preflight, supported-fix preview,
  explicit Prepare, separate signed output with ApkVerifier verification,
  post-conversion inspection plus applied/original reports, full APK/report
  document-picker export, optional explicit PackageInstaller handoff and library
  tracking for ordinary launch/global logs. No automatic installation/launch,
  network, new permissions, vendor bytes or touch/purchase injection. Add game
  global purchase default remains separate. Temporary copies/cache restoration,
  close cleanup, export-before-exit and signer/update/save risks documented.
  Scanner checks SDK requirements, packaged ABI vs Build.SUPPORTED_ABIS, missing
  DEX/split hints, duplicate entries/structural bounds, exact native repair plan,
  residual TEXTREL. Reports unresolved CPU/minSdk/API/server/runtime/16KiB/security
  limits explicitly; silence not success. Phone APIs35/36 use doctor target floor
  24; existing converter methods retain floor23. Parameterized fixed-size manifest
  patch/Rebuilder overloads preserve already-higher targets and old behavior.
  No-target/minSdk fallback retains prior semantics and is disclosed. Post36
  policy unverified. No universal compatibility or APK-safety claim.
  Tests: API floors/high target/minSdk/device ABI/residual native/split hints,
  immutable scan, conversion asset preservation, malformed manifest/duplicate ZIP
  rejection; all prior host/native/receipt/journal suites; Android Java compilation.
  Supplied AD2442 native/manifest fixture (not complete game) passes full doctor
  scan -> three repairs/target24 -> v1/v2 signing -> signature verification ->
  post-scan, with original unchanged. Docs/apk-doctor.md details limitations.
  **Next:** branch build, APK COMPATIBILITY DOCTOR -> scan/review -> Prepare ->
  Export APK/report or explicit Install; protect existing saves/signature updates.
  Phone UI/picker/installer/lifecycle restoration and actual app launches remain
  device tests. Use existing global log after a chosen launch for later blockers.


- **2026-11-01 — Doctor CI compilation fix:** run41355964249 passed both host
  suites but failed Android Java compilation with SIX unavailable desktop-JDK
  Files.readString/writeString calls in ApkDoctorActivity. Replaced all with
  UTF-8 FileInputStream/OutputStreamWriter helpers; report read bounded4MiB.
  No SDK bump, dependency or feature behavior change. Root cause of missed local
  error: android.jar on javac classpath still resolved java.* from desktop JDK.
  Corrected validation using AGP-generated Android core jdkImage via --system.
  API36 core stub also omits these methods. Original committed source reproduces
  exactly those missing-method errors under this check; corrected FULL app
  compiles successfully with only existing annotation/deprecation warnings.
  CI uses API35 and its own Android core image and must verify the new build.
  docs/apk-doctor.md records correct local verification. **Next:** replacement
  APK build, user monitors full build; phone doctor/export/install still pending.


- **2026-11-01 — Evidence baseline and read-only structural prototype:** owner
  authorized only Phase1 baseline/structured evidence and read-only Phase2 for
  the 13 initializer/atexit profiles. Dedicated branch feature/compat-evidence-detector
  from0555487. Existing native catalog/ALL four transformation files have frozen
  source checksums and remain byte-identical; no purchase/unrelated changes.
  Added immutable CompatibilityEvidence statuses and ArmFinalizerDetector, which
  compiles without catalog/transformer classes. No filenames/hashes/build offsets
  in detector; no write API or modified ELF output; transformationAuthorized=false
  on every outcome. Not referenced by production repair/UI paths.
  Actual common root is DT_FINI_ARRAY ARM routine, tail-binding __cxa_finalize,
  not an arbitrary initializer offset. Derives target/optional thunk/PLT from
  arrays, REL/ABS32, symbols, restricted instructions and unwind metadata. Bounds,
  CPU/layout, mapping conflicts and data/code distinctions are explicit. Six
  stripped Thumb cases have shapes/unwind but no independent mode evidence;
  UNKNOWN rather than guessed acceptance. Global reference/whole-program safety
  remains outside local recognition; no transformation integration authorized.
  All17 real native fixtures AVAILABLE locally. Audit verifies expected native
  output SHA, input preservation, actual catalog selection and idempotence17/17.
  Family13 evaluated: structural TP7, conservative FN6 (UNKNOWN Thumb mode),
  other-family TN4, FP0; derived parameter oracle matches13/13 including uncertain
  partial candidates (not full matches). Generated43 cases: TP12/TN31/FP0/FN0,
  layout shifts and typed/mapped Thumb evidence, near matches/malformed/truncated/
  unsupported/skipped. Empty fixture-index control records17 SKIPPED,0verified
  and exits nonzero. Missing never passing. Fresh legacy host suites and complete
  Android Java compile against Android core jdkImage pass; no new phone/Unicorn
  runs, explicitly recorded as not executed. Proprietary bytes/paths not committed.
  tests/native-profile-matrix.json and docs/evidence/structural-prototype retain
  baseline and per-case outcomes. New dedicated CI runs existing/generated/core
  compile checks; public private-fixture replay explicitly SKIPPED. Does not
  build/install a modified app automatically. docs/structural-detector-milestone.md
  explains scope, repro and remaining evidence. **Milestone limitation:** full
  13-profile acceptance NOT demonstrated; six need trustworthy Thumb entry/mapping
  or validated rooted interworking CFG evidence. Keep exact-build operational
  fallback. Next work must obtain that evidence, not add fingerprint overrides.

- **2026-11-02 — Build the evidence baseline for phone testing:** owner requested
  an app build while the next instruction-mode investigation remains unfinished.
  Enable the existing Android APK workflow on feature/compat-evidence-detector
  and gate packaging on both existing compatibility profiles and generated
  read-only detector tests. No production Java/native/bridge changes; frozen
  transformation checksums unchanged. Branch builds upload signed APK artifacts
  but do not publish the main-branch release. Supplied cumulative game journal
  confirms Monocle Man's libmono TEXTREL rejection and Polarity's OUYA content
  service initialization error followed by null-context unbind during shutdown;
  these are observations, not newly implemented fixes. Latest Tomatoes Vs Onions
  capture loads its native runtime but reports a surface without a frame; no
  fatal exception in that session and no gameplay-success conclusion. Existing
  detector remains 7/13 supported, 6 unknown; bounded CFG work is still pending.
  Local compatibility checks run with the available JDK. Full APK/CI outcomes
  must be checked on the resulting workflow run; phone installation untested.

- **2026-11-02 — Shared repair attempts, separately scoped from the detector:**
  owner asked to return to fixes applicable to other APKs and selected BOTH the
  OUYA cleanup and native families. Branch feature/shared-compatibility-rules
  from1d85252 preserves the detector-only branch. Baseline APK run41421926645
  succeeded and uploaded PocketOuya-apk; no phone install was executed.
  Added independent WritableRelocationRule: no fingerprints/offsets/instruction
  decoding; only clears stale DT_TEXTREL/DF_TEXTREL after bounded ARM32 REL/PLT
  proof that every supported loader write is aligned RW and avoids metadata.
  Rejects actual code writes, unsupported types/formats/resolvers/COPY, aliased
  mappings, incomplete evidence and malformed ranges/symbols/RELCOUNT. Separate
  copy, reparse/proof and byte-edit-scope checks. No RX permission changes.
  OuyaServiceCleanupRule matches only shared SDK shutdown's two declared instance
  field loads, single Context.unbindService and return; adds verified null-context
  branch, preserving the non-null path and exceptions. No service replacement,
  billing changes or helper. Other forms UNKNOWN, reserved/malformed DEX unchanged.
  Coordinator retains legacy native plan first, then these opt-in shared rules.
  Doctor read-only preview and explicit Prepare/startup conversion report rule
  IDs/status/reasons; ordinary conversion stays isolated. New repair DEX is input
  to optional touch/purchase processing rather than discarded. Original 17-profile
  catalog and FOUR algorithm source checksums, purchase/bridge code and structural
  detector byte-identical; no integration of finalizer recognition into repairs.
  Fresh full host suites and Android-core compilation pass; new fixtures exercise
  shifted addresses/registers/names, null/non-null paths, negative native forms,
  truncated/malformed inputs, ZIP/asset preservation, option isolation and repeat
  edits. Existing generated43 remain TP12/TN31/FP0/FN0. Real replay NOW has12
  VERIFIED and5 SKIPPED (native01-05 attachment originals unavailable); both direct
  audit and coordinator comparison match every available expected output/hash/set.
  Audit exits nonzero for missing5, never counts them as passing. No source-based
  assumption substitutes for missing fixtures. docs/shared-compatibility-rules.md
  and docs/evidence/shared-rules document outcomes. Polarity/Monocle original APKs
  unavailable: applicability/device success unverified. Cleanup guard alone cannot
  fix missing OUYA services; real Mono code relocations remain unsupported by the
  stale-declaration rule. Six-profile instruction-mode/CFG work remains pending.

- **2026-11-02 — Supplied Polarity Java evidence:** manifest confirms package
  com.bluebox.PolarityCoop/target16. DEX proves typed ServiceConnection-self
  shutdown variant; generalized existing cleanup rule to one Context field plus
  verified self receiver, preserving the original two-field form. No title/hash/
  offset selection. Fresh host suites and Android-core compilation pass; typed/
  untyped-self positive/negative paths, null/non-null behavior and idempotence
  tested. Actual supplied DEX repair verified; original preserved and canonical
  serialization excluding only shutdown identical across before/after. Earlier
  init exception precedes Context storage and facade listener construction,
  explaining prior null listener/shutdown errors. Blank-screen cause remains
  UNKNOWN; this cleanup change does not supply a service or repair initialization.
  Need managed assemblies/current runtime trace for the remaining startup wait.
  No purchase/native/finalizer transformations changed; proprietary DEX not shipped.

- **2026-11-02 — Explicit embedded Unity experiment in PocketOuya:** user
  expressly requested the Monocle experiment as an app option after distinguishing
  it from already pushed shared repairs. Added separate Add game experimental
  choice, with confirmation/report before install, isolated from ordinary startup
  repairs/doctor and touch/purchase hooks. Structural proxy SONAME pair, complete
  embedded payloads, supported ARM-only finalizers, clean Mono and post-repair
  writable Unity relocations required; Thumb/uncertain/missing cases rejected.
  No APK package/hash/offset selection. Detector remains read-only; caller passes
  derived ARM addresses to unchanged existing transformer only for this explicit
  option. Both promotions transactional; all assets/DEX/input preserved. Original
  17 catalog entries/four algorithm checksums unchanged. Fresh host and generated
  suites, new shifted/negative/isolated-path fixtures and Android-core compilation
  pass. Full supplied Monocle APK recovered from three verified split volumes;
  app option reproduces prior emulated Unity bytes and preserves 29 assets/DEX.
  Prior native emulator experiment:9 comparison pairs/3biases/18 executions, same
  call argument/registers/SP/LR/CPSR under original permissions. Whole-program
  reference safety and Android/gameplay remain UNVERIFIED, disclosed in UI/report.
  No game bytes committed. docs/experimental-unity-runtime.md records boundaries.
  Build new PocketOuya in GitHub; user selects original complete APK and explicit
  experimental option, then normal Play and full runtime log if unsuccessful.

- **2026-11-02 — Monocle phone test reaches new CPU-string crash:** owner supplied
  UnityMain SIGSEGV in strlen(NULL), with libunity callers70dcc/2dcb98/265058.
  Traced those bytes to JNI getCPUType and a nullable Java CPU-description getter.
  Its provider reads/caches Processor from /proc/cpuinfo; missing Processor is
  plausible but the actual phone file is not captured. Added independent shared
  UnityCpuStringRule for exactly the supported field/delegate/result/return SDK
  contract. Preserve original provider/non-null/empty results and exceptions;
  replace only null description with Unknown CPU. CPU capability flags unchanged.
  Experimental conversion attempts this after successful native promotion; no
  ordinary/doctor/purchase changes. Assets/prior native bytes unchanged, eligible
  DEX now differs only in getter (canonical comparison verified on supplied DEX).
  Tests: full host,43 detector cases,new CPU null/non-null/empty/Unicode/register/
  idempotence/negative fixtures; Android-core compile; full APK preserves29 assets
  and DEX outside getter. Native algorithms/catalog checksums unchanged. Previous
  startup repair alone did NOT establish successful gameplay; new guard untested
  on phone. Push fixes/tests to GitHub; owner explicitly says do not wait for build
  completion. Provide running build link and ask reconversion of original APK.

- **2026-11-02 — CPU guard API floor:** use Collections.emptyList for generated
  method metadata, avoiding newer collection factories on API29. Targeted getter
  tests pass unchanged. GitHub build remains asynchronous as requested.
