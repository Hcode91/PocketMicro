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
  proving libvector.so TEXTREL rejection, then a ZIP believed to lack native libs.
  It contains lib/armeabi with seven libraries. Main has 12 non-writable relative
  relocations; dialog/webview/billing extensions have one each. Added exact source/
  output catalog entries for all four and renamed the option/registry to Attempt
  native runtime repair / NativeRuntimeRepair. Existing Unity behavior retained.
  Three extension initializers use the prior PIC repair. Main initializer is
  repaired likewise; 43 ARM loads of the other shared code literals branch to
  small appended RX address-calculation stubs and return to the original next
  instruction without flags/stack/other-register changes. Add resident PT_PHDR/
  PT_LOAD and extend the unwind table by eight verified padding bytes with a
  CANTUNWIND boundary for new stubs. Existing source mapping flags and GNU_STACK
  flag preserved; no new writable executable code segment. Compact relocations/
  counts and validate remaining writes are RW before clearing DT_TEXTREL. Reject
  unknown loads, SP/PC destinations, unhandled ADR/Thumb references, address-taken
  literals, occupied padding and unexpected unwind coverage. No vendor bytes shipped.
  Validation: Java output matches independent Python main repair; actual four-file
  conversion preserves source DEX/resources/other native libraries, adds no DEX,
  and has no remaining declared text relocations. Unicorn checks all 43 literal
  sites plus four initializer sequences at three biases with unchanged register/
  flag/PC state and actual RX/RW mappings. New structural/rejection fixtures, prior
  suites and Android Java compile pass. See docs/vector-runtime-repair.md and
  optional scripts/test-vector-runtime-pic.py. Provided ZIP omits assets, so no
  Android linker acceptance, full boot, graphics or gameplay has been verified.
  **Next:** install branch APK, reconvert ORIGINAL COMPLETE Vector APK with Attempt
  native runtime repair, expect four repaired entries, install update and use
  ordinary Play. Capture another crash if later CPU/JNI/service/asset issues follow.


- **2026-11-01 — embedded touch controls with native repair:** owner requested
  conversion-time controls after earlier generic input failed. Added a combined
  Native runtime repair + touch controls import option while retaining repair-only
  and existing touch/purchase modes. Both conversion reports are preserved.
  Added Marmalade direct Activity key callbacks (including game overrides),
  structural route detection/reporting, digital D-pad and keyboard mapping. The
  supplied Vector Java path reaches LoaderKeyboard native key input without
  requiring a physical InputDevice. Buttons now cannot take keyboard focus;
  release input before mode dialogs, avoid queued attachment after pause, and
  probe engine classes without initialization. No global overlay or new OS
  permission, analog-device emulation or resumed NativeActivity prototype.
  Validation: full host suites including callback override/time/source/release/
  JNI-failure tests, app Android Java compilation and bridge Java8/D8 pass.
  Actual combined conversion of supplied Vector partial ZIP repairs four native
  components, adds three inherited lifecycle hooks and helper DEX, and preserves
  unrelated payloads; rewritten DEX checksum verifies. Updated README and runtime/
  touch docs. No full Vector/Muffin boot or new touch response on a phone verified.
  **Next:** install new branch artifact, reimport ORIGINAL COMPLETE game APK,
  choose Native runtime repair + touch controls (or Touch controls without repair),
  install update and use ordinary Play. Try Input Auto then Keyboard; report
  whether the pad appears and which buttons register. Unknown engine/device gates
  and analog-only games still require their own adapters.


- **2026-11-01 — Vector follow-up native crash:** owner provided SIGSEGV/null
  memcpy via ART ReleaseIntArrayElements, native return PC 0x33157, r10=0.
  Inspected Thumb call loads JNI slot 195 (0x30c), passes r10 as native elements
  and mode 0, guarding the array handle but not the pointer. Native execution
  now gets past the earlier dlopen rejection; successful gameplay remains unproven.
  Added exact-build surface-release guard: replace movs/BLX pair with Thumb BL
  to a 12-byte stub in the existing RX extension. Null elements skip only release;
  valid elements tail-call original JNI with original args/LR. Both retain
  DeleteLocalRef/handle clearing; no dummy buffer, JNI hook or security bypass.
  Catalog original and previous PIC-only sources converge to deterministic hash
  d0c1dfc8fc4c5cd447b7e1aef90c74eaa7cac3b06c7b74bb0f9684d6ffbfd58e;
  current output is idempotent. No game binaries committed.
  Tests: full host suites; opcode/layout/branch/RX rejection fixtures; original
  null release reproduced with JNI test doubles and guarded null/non-null paths
  checked using ARM/Thumb callbacks at three biases, retaining cleanup and
  callee-saved registers/stack; prior 43 literal/four initializer tests pass.
  Actual repair-only and touch+repair archive conversions preserve unrelated
  payloads; previous catalog output upgrades identically. Android app Java
  compilation passes. Reason for initial missing buffer and complete startup
  remain unknown; no phone run of this guard has occurred.
  **Next:** install newest branch artifact, reconvert ORIGINAL COMPLETE Vector
  APK with native repair (with touch if desired), install update, normal Play,
  capture another fresh crash if it still exits.


- **2026-11-01 — engine/problem labels:** owner requested clearer fix names.
  Renamed import choices to distinguish Android install compatibility, embedded
  OUYA/Android touch input, supported OUYA Unity offline receipts, and Unity /
  Marmalade startup repairs (with/without touch). Startup dialog explains exact
  Unity/Mono 4.1.5f1 text-relocation scope versus Vector 1.0.7 Marmalade loading
  and null JNI surface-buffer fixes. Each matched native component reports its
  engine and specific problem. Unknown-build behavior and repair selection are
  unchanged; AIR/FFIII startup explicitly not covered. Updated current docs/menu
  mapping and recorded owner's confirmed Vector launch AND embedded touch input
  after 1b2c7fb. This does not establish every Marmalade game's compatibility.
  Android Java compilation and whitespace checks pass; this is a labeling/docs
  change, no new native patch or permissions. AIR diagnostic work was parked
  before this change at /workspace/scratch/pocket-air-diagnostics-paused-20261101
  and is not included/published. AIR investigation remains on hold.
  **Next:** owner can use newly labeled choices in the next branch APK. Wii U
  Downloader redesign discussion may resume separately; no changes made there.


- **2026-11-01 — GameMaker engine touch pad:** owner requested touch for the
  existing Maldita Castilla 32-bit GameMaker translator path. Added an app-owned
  pad over GLSurfaceView, using the existing Engine.key queue directly, not APK
  conversion/injection. Renamed gameplay option Play with GameMaker engine +
  touch (beta); inspection-only Engine test stays separate. Directions, O/U/Y/A,
  L1/R1, Start/Select/Menu, hide/show and per-game remapping/reset are included.
  Labels show legacy runner keyboard defaults Z/X/C/Space, Shift/Ctrl, Enter/Tab/
  Escape; OUYA key bindings are also selectable. Game actions may require remap.
  Input enables after successful startup while resumed/focused. Per-key ownership
  aggregates touchscreen, physical buttons and stick direction inputs, suppressing
  repeated downs and premature releases. Pause/focus loss/quit/log dialogs/engine
  stop/detach release held state and cancel accessibility pulses; overlay buttons
  cannot take game keyboard focus. Renderer/native code/assets unchanged, no new
  permissions or Shizuku requirement. Applies to other already-compatible games
  using GameActivity, not Unity/Marmalade/AIR or arbitrary 32-bit games.
  Validation: new pure-Java overlap/repeat/multi-key/pause-cleanup/repress tests
  and full Android Java compilation pass; diff checks clean. No new phone test
  of Maldita touch input/layout; existing game boot was previously confirmed.
  **Next:** install branch artifact, long-press Maldita, Play with GameMaker engine
  + touch (beta). No reconversion required. Check movement and Z/X/Space bindings;
  use Controls to adjust buttons per game, report actual phone response.


- **2026-11-01 — one global launch/runtime/crash journal + full export:** owner
  clarified ONE shared saved log for every launched game, including successful
  runs, with game name followed by output; then requested full-file export.
  Added app-private files/logs/global-launch.log, timestamped name/source headings,
  persistent append, stable cross-process file lock, explicit clear and streaming
  full export through ACTION_CREATE_DOCUMENT. Library GLOBAL LOG / EXPORT opens
  new private viewer (latest 256 KiB preview); export includes ALL saved bytes,
  not only the preview. No per-game journal files or automatic rotation/truncation.
  Play, GameMaker gameplay, Engine test and manual Capture-and-launch paths are
  integrated; requests/failures/return events are always recorded. Dispatch is
  not mislabeled as confirmed gameplay success.
  Private foreground dataSync service records emitted main/system output for the
  game UID and identified crash blocks via separate non-daemon owner-UID-restricted
  Shizuku GameLogReader (fixed commands, no READ_LOGS permission). Reader polls at
  250 ms, marks 128-Ki-character queue overflow and rolling crash-window/filter
  limits; nothing uploaded. Existing manual 60-second capture is separate.
  Without Shizuku external-game output is explicitly unavailable; own GameMaker
  engine logs can use a local same-UID logcat reader. Engine start/stop/quit and
  boot-test reports also append directly. On return, API30+ own :game crash/ANR
  exit info/traces are recovered when available (512-KiB bound; binary traces
  labelled/base64). Foreground-service interruption/time limits are recorded;
  returning to library/notification Stop ends collection, saved data stays.
  Added FGS/dataSync/notification permissions and private viewer/service manifest
  declarations; no global device-wide normal logs, root or auto-boot collector.
  Tests: shared multi-game success/crash headings, reopen/persistence, concurrent
  append, Unicode, clear/reuse, byte-identical full export past preview limit;
  prior inspection/ELF/crash-isolation suites; full Android Java compilation;
  source manifest private components/type/no-READ_LOGS checks and diff checks pass.
  See docs/global-game-log.md for scope, privacy and source/OS completeness limits.
  No phone validation of Shizuku streaming, FGS lifetime or destination provider
  yet. Full saved export does not recover unavailable/dropped source output.
  **Next:** install branch artifact; GLOBAL LOG / EXPORT → Shizuku access if
  needed; launch games normally, return, Refresh, Export full log. No game APK
  reconversion. Check successful game output and a known crash plus full export.


- **2026-11-01 — multi-game log triage / stencil / capture startup race:** owner
  supplied global journal showing real Shizuku runtime + Java crash capture for
  Abbigale, Zombie Toaster and Space Shooter, plus engine reports for Abbaye.
  Abbigale: libmono TEXTREL (Unity; version/fingerprint not supplied). Zombie
  Toaster: libCore TEXTREL precedes missing AIR nativeSurfaceCreated; confirms
  that runtime failure's immediate cause for this title, no AIR patch resumed.
  Space Shooter: libSpaceShooter TEXTREL, engine not established by filename alone.
  These native builds require original files/exact matching; no blanket fix claim.
  Abbaye GameMaker boot test reaches glGetError but gameplay stops at unimplemented
  glStencilMask. Added all seven GLES2 stencil state wrappers in generator and
  regenerated C++: ClearStencil, StencilFunc/Mask/Op and separate-face forms.
  Forward real typed args to host GL on existing render thread; no no-op substitution.
  Android ARM64 NDK compilation succeeds and object imports all seven GLES symbols.
  No new full Abbaye gameplay verified; subsequent gaps may remain.
  Log also shows fast return before capture connects, dropping cold-start output.
  Preserve pending session through existing 8-second bind deadline, backfill from
  original launch time, allow 750-ms final reader drain; guard delayed callbacks
  against replacement sessions and stop using latest delivered service ID.
  New-launch replacement of unconnected session explicitly logged. Expected IO
  interruptions after deliberate stop are suppressed; active read errors retained.
  Existing inspection/ELF/crash-isolation/global journal/full-export suites, Android
  Java compilation and diff checks pass. New bind-race fix needs device verification.
  **Next:** new branch APK, retry Abbaye with GameMaker engine gameplay; retry a
  normal quick crash after cold Shizuku bind and check recovered output. For
  Abbigale try original APK with Unity/Marmalade startup repairs + touch; report
  matching/unsupported entries or supply original native files for new builds.


- **2026-11-01 — Space Shooter + inspected AIR PIC candidates; Abbigale payloads
  still absent:** owner supplied Space Shooter 1.1.3 ZIP, then Abbigale game ZIP
  and lib ZIP, requesting all fixes. Space Shooter has one ARM initializer
  R_ARM_RELATIVE at 0x7421c; use existing PIC routine (init 0x74210, target 0x1ed620).
  Catalog source 92a31b66bf1127993764a29481866c6dfa0b02889f1d850ceca06a74b3f3fe33
  -> output f242435f27d97c46cbe4e26d5b6baf2ce41e6ef72bca1985425af3699f77870e.
  Real source/repaired initializer matches registers/flags/branch at three biases.
  Actual repair-only and repair+touch conversion, unrelated payload preservation
  and idempotence pass. Phone startup/input remain unverified.
  Revisited earlier Ball Slapping AIR files: Core has 17 relative code literals
  read at 18 ARM LDR sites, ysshared has two relative literals and one symbolic
  absolute literal (three loads). New ArmLiteralPicRepair redirects exact-profile
  loads through RX PC-relative stubs. Symbolic relocation moves to a new RW cell
  with original symbol/addend; valid and null weak resolutions preserved. Core
  unwind table is rebased with decoded original PREL31 recipes retained and stub
  entries cloned from original sites; PT_ARM_EXIDX/section metadata updated.
  Existing mappings/GNU_STACK/RELRO flags preserved, no new RWX, only RW remaining
  loader writes before DT_TEXTREL removal. Originally absent ysshared unwind stays
  absent. Unknown/ADR/Thumb/address-taken/SP/PC/out-of-range/count layouts reject.
  Core source 6cc57698961c0dab6bf2d32d443a1c07c5f6befcd552dc25738893f77104c22c
  -> b6f2b60be0bd662bf62a1afb4f23deb7d33e715c20fc04b66d322927acd20813;
  ysshared source b943b7e7631b4ef047f2eeeef6c50ff77dd6756c66f9eb7954a3e846f387b69c
  -> 92ee9b5a46bf4bf1092bfc73cc747d8cc102312fdad3631d74bc2812765b96a2.
  Per-file runtime read/startup inspection increased to 24 MiB for Core; aggregate
  budgets unchanged. UI labels list Unity/Marmalade/AIR/Frima with exact-build
  limitations and AIR old-private-media dependency risk. No AIR full boot verified;
  Zombie Toaster native bytes not supplied, only covered if fingerprints match.
  All 18+3 loads checked in Unicorn at three biases with null/resolved symbols,
  full register/flag/PC equality, unchanged source mappings, original/stub unwind
  recipe equality and remaining writable relocation checks. Synthetic present/
  absent unwind, symbolic-cell, PREL31 and rejection fixtures; prior host suites;
  actual AIR two-file archive preservation/idempotence; Android Java and prior
  inspection/crash/global-export tests pass. No game/native files committed.
  Both Abbigale ZIPs have the same known Mono/Unity loader proxies (92,744/47,688
  bytes), NOT real payloads. assets/libs/armeabi-v7a/libmono.so and libunity.so are
  missing; source builds cannot be inferred from proxies alone. Improved missing
  runtime report, verified no patch/donor substitution occurs on those archives.
  See docs/air-frima-runtime-repairs.md and scripts/test-arm-literal-pic.py.
  **Next:** install branch APK, reconvert original complete Space Shooter and AIR
  APKs with startup repairs (with touch if desired); check matched/unsupported
  file counts, normal Play, fresh global log. Supply Abbigale's two actual files
  from assets/libs/armeabi-v7a (or complete-APK conversion report with payload
  hashes) to finish that repair. New candidates still need phone validation.


- **2026-11-01 — Abbigale known-loader-only repair:** owner supplied another
  armeabi-v7a.zip and explicitly prioritized fixing Abbigale. It contains the
  same known Mono/player loader bytes. Corrected prior overly categorical claim
  that nothing could be repaired without payloads: the loaders themselves have
  two TEXTREL writes each and can be fixed independently. Added exact source/
  output profiles using existing validated ARM initializer + Thumb atexit PIC
  routine. Mono init 0x3af8 / target 0x17000 / thunk 0x3b08 / atexit PLT 0x3a14;
  Unity init 0x1760 / target 0xc000 / thunk 0x1770 / atexit PLT 0x167c.
  Outputs 857d1f4e4f107dbcc429dcddb5a3bd6fa2f10fdbb42b953da141d4ac3d8d4e06
  (Mono) and cdbe592192dc48608ca8d908d4879f120ee33eb0cd2bfa128fd7169baa598ab5
  (Unity). Original load/forward logic, SONAMEs, mappings and assets preserved.
  Catalog prefers recognized real embedded payload promotion; only if unavailable,
  oversized or unrecognized can an independently fingerprinted installed loader
  receive its own repair. Missing/unknown embedded runtimes remain unsupported
  and explicit in reports; unchanged payloads declaring TEXTREL also flagged.
  Unknown loaders remain unchanged; known payload patch/hash failure aborts and
  never silently falls back. Already-repaired loader labels retain payload caveat.
  This fixes the known loader's error, not a verified full Abbigale launch.
  Tests: actual archive two-loader output hashes, unchanged DEX/resources/OUYA
  extension, repair-only adds no helper, idempotence; Unicorn init/call arguments,
  original RX/RW protections, stack/callee-saved state and ARM/Thumb caller/target
  combinations at three biases; synthetic missing/unknown fallback, preferred
  supported promotion, preserved assets, no fallback on bad payload output; prior
  host suites and full Android Java compile pass. Existing Mono/Unity and AIR/
  Frima/Marmalade matching retained. Added scripts/test-unity-loader-pic.py and
  generalized existing optional instruction checker without changing defaults.
  **Next:** install new branch APK, reconvert ORIGINAL COMPLETE Abbigale APK using
  startup repairs + touch, review whether full-payload promotion or loader-only
  repair was selected, install update and normal Play. If it fails later, export
  new global log to determine embedded runtime versus other native/API blockers.
  No new APK/game launch has been verified on the phone; AIR/stouyapi not changed.
