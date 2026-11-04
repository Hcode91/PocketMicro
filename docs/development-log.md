# PocketOuya – development log

Goal: play Ouya games on a modern Android phone (owner's device: Samsung Galaxy S23, Snapdragon 8 Gen 2).
**Correction (2026-11-03):** launch logs show 32-bit (`lib/arm`) games starting on the S23, so the
earlier "no 32-bit app support" assumption was wrong. Repairing APKs to run directly is the main route;
the dynarmic engine is a fallback.

**2026-11-03: offline purchase emulation removed** (patcher, bridge, UI, docs, tests). Games' own purchase
checks are always left unchanged. Older progress entries below mention it historically.

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

## Parked todos
- **Embedded 2014 Chromium fonts (Worm Copter, `tv.ouya.sdk.WebActivity`).** The bundled
  `libcontent_shell_content_view.so` (Dec 2014, ~33 MB, stripped) crashes in Blink measuring the
  space glyph (factory at 0x83782c returns null, caller 0xd4d3a8). Likely cause, unproven: its Skia
  reads only `/system/etc/system_fonts.xml` and `fallback_fonts.xml`, which Android 5+ replaced
  with `/system/etc/fonts.xml` (new format), so no fonts load. Possible general fix: at startup,
  convert `fonts.xml` to the old format in the app's files dir; repoint the library's path literal
  (reuse the unused 52-char test path slot `/data/local/tmp/content_shell/android_main_fonts.xml`);
  add the startup hook. Other 2014-engine issues may follow. Owner parked it 2026-11-03.
- **Soul Fjord Scaleform crash (libgfxunity3d.so).** Same crash on every run (3 logs + full
  crash dump from the new Android crash record, 2026-11-03): SIGSEGV at 0x40 in
  pthread_mutex_lock <- RTCommandQueue::PopCommand <- SFUnityRenderThread::ProcessCommands <-
  SFManagerImpl::Display <- UnityRenderEvent, on UnityMain (Unity renders on its main thread; no
  race). The manager's render-thread pointer (SFManagerImpl+0x2c) is null; the manager itself
  (0xe7875050, ordinary malloc memory) still has a valid vtable and Init's +0x38 flag set.
  - Trigger: the game's intro video. Unity's SurfaceView is destroyed and detached while the
    video's own SurfaceView plays (~5 s); Unity keeps its GL context (no second "Creating OpenGL
    ES context"). The crash comes ~0.3 s after the video ends and Unity's surface is recreated.
  - Static facts: only the SFManagerImpl constructor writes +0x2c (destructor releases but does
    not clear it); SF_DestroyManager / UnitySetGraphicsDevice shutdown clear the global; the
    game's C# never calls SFManager.Destroy (OnDisable -> SF_Uninit only; finalizer frees the
    marshalled init buffers). What empties +0x2c is still unproven; the crash dump does not
    include the manager's memory.
  - Ruled out: OUYA store (answered), Android API/SDK settings, a dangling manager after the
    destroy paths, evaluation message, GL extension string handling.
  - Next idea (not started): keep Unity's GL view attached while the intro video plays (a
    Java-side change in how the game shows the video), so the surface hand-back never happens.
  - Not to retry: native trace/logging wrapper, patching libgfxunity3d.so checks, and building
    DEX/Java-code reading tools for this game were all stopped by Claude's safety checks.
  - Owner parked it again 2026-11-03.
  - **2026-11-04 (this branch), original-code baseline from `feature/unity-video-surface` (0.1.90):**
    same crash with every PocketOuya repair off except Unity runtime promotion, so the SDK/DEX
    repairs are not the cause. Static facts added from the APK the owner supplied:
    the game's C# (`Assembly-CSharp-firstpass`) creates the manager only in
    `TryGfxManager.Awake/OnEnable/get_SFMgr` -> `new SFManager` -> `SF_Init`; `OnDisable` only
    calls `SF_Uninit` (destroys movies, not the render thread); nothing calls `SFManager.Destroy`,
    so `SF_DestroyManager`/`SF_Destroy` never run. `UnitySetGraphicsDevice` init and `SF_Init` both
    use the same singleton (`CreateManager` returns the existing one); device shutdown frees it and
    clears the same global. A second check confirms only the constructor writes manager+0x2c
    (`ShutdownRenderHAL`'s +0x2c store is to a MovieNode). No other thread was inside Scaleform at
    the crash. From saved registers in the 08:15 tombstone the manager is at `0xe4edb050`, i.e.
    above 3 GB: a 32-bit process on a 64-bit kernel gets addresses the OUYA (32-bit kernel, <3 GB)
    never produced. Leading hypothesis, unproven: Scaleform's own heap (SysAllocMalloc + MH heap
    engine) mishandles high addresses and hands out memory overlapping the live manager, zeroing
    +0x2c. Confirming it needs either the manager's memory at crash time or a low-address
    experiment; owner to decide.
  - **2026-11-04: owner chose the low-address experiment.** Added opt-in conversion option
    `pocketouya.low-addresses` (see shared-compatibility-rules.md). Test: convert the original
    Soul Fjord APK with startup repairs and the new box ticked; the global log must show a
    `PocketOuyaLowAddr: reserved ... MB above 0x80000000` line with low test allocations. If
    the crash is unchanged and Scaleform's addresses are low, the high-address hypothesis is
    wrong; if it gets further, keep the option and consider auto-suggesting it.
  - **2026-11-04 08:46 result (0.1.94, low-address box ticked):** helper reserved 1034 MB above
    2 GB; new large allocations land low (0x7f7ff380). **The Scaleform crash did not happen**: the
    game continued to the point where it used to crash and went on to start the intro video
    (game view detached, video SurfaceView added). It then crashed in Android's
    `SurfaceView.performDrawFinished` (null parent) because Unity's `ac` view skips SurfaceView's
    detach. Added startup repair `android.surfaceview-detached-draw` for that. One run is
    supporting evidence for the high-address hypothesis, not proof; next log should show whether
    the video plays and whether Unity's view comes back afterwards.
  - **2026-11-04 09:13 result (0.1.95):** guard caught the SurfaceView callback, the intro video
    played to completion, Unity's view returned, then the same Scaleform crash 0.4 s later with the
    manager at 0x78353050: **high-address hypothesis disproven**; the trigger is Unity's
    pause/resume around full-screen video (consistent with the very first investigation). The
    low-address option stays as an opt-in experiment but is not needed for this. Added
    `unity.scaleform-skip-video`; next log should show `PocketOuyaUnityVideo` skip lines and
    whether the menu appears without a crash.
  - **2026-11-04 09:27 result (0.1.96): video skipped, same crash 0.45 s later.** Root cause
    found: `SaveStateBlock` writes GL_MAX_VERTEX_ATTRIBS (32 on Adreno, 16 on the OUYA) flag bytes
    into a 16-byte manager array, overwriting the render-thread pointer at +0x2c on the first
    Scaleform frame. Added `ouya.gles-vertex-attribs` (GL shim capping the limit at 16 for
    libgfxunity3d.so) and withdrew `unity.scaleform-skip-video`. The SurfaceView guard stays (real
    separate bug when Unity plays a video). Next: confirm the menu renders.
  - **2026-11-04 09:43: Soul Fjord launches and runs (0.1.97).** Touch controls do nothing: the
    touch pad's "Android fallback" events come from device id -1, and Unity 4 only treats real
    InputDevices as joysticks (`com.unity3d.player.f` lists `InputDevice.getDeviceIds()`); the
    game's OuyaInput also matches controllers by name (OUYA GAME CONTROLLER, Xbox 360, PS3…).
    Owner chose the Shizuku virtual controller: see docs/virtual-controller.md (step 1 device test).
- **OuyaFacade layout variant (Worm Copter).** `ouya.store-offline.answer-requests` reports
  "request queue layout not recognised"; support that ODK version so store requests can't hang.

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

- **2026-11-02 — Assess APKs and suggest supported conversion paths:** owner
  requested a checker when importing/installing. Add game now copies to bounded
  temporary storage, runs ApkRepairAssessment off UI thread and shows evidence
  before choosing conversion. Suggest standard/startup/explicit experimental or
  no fix for proven minSdk/ABI/package limits. Individual statuses/reasons and
  skipped budgets retained; no scan installs or edits APKs. Manual options and
  experiment confirmation remain. Doctor report includes same recommendation and
  explains that its Prepare action does not run experiments. Added read-only
  inspect entry points for experimental Unity and CPU getter; no output bytes in
  those inspectors. Existing catalog preview uses transient copies as before.
  Main conversion target floor now agrees with doctor/assessment for phone API35+
  (24; API34 remains23), without lowering higher targets. No purchase logic or
  native algorithms/catalog/detector changes. Tests: all host/generated suites;
  standard/stale/SDK/experimental routing, CPU/minSdk limits, malformed/missing/
  Thumb cases, read-only preservation and API floor alignment; Android-core Java
  compile. Real supplied Monocle is an experimental candidate with original bytes
  unchanged; known native15 suggests startup catalog repair. Native/coordinator
  replay12 VERIFIED,5 unavailable SKIPPED, incomplete exits nonzero. Phone UI and
  installs untested. docs/apk-assessment.md documents bounds and limitations.
  Push changes/tests and provide GitHub build link without waiting for completion.


- **2026-11-02 — Touch choice with suggested/experimental fixes; game support export:**
  owner confirmed repaired game works but lacked controls, and requested game-log
  export with installed APK, split above20MB. Suggested conversion and explicit
  Unity confirmation now offer checked touch checkbox; manual choices retain
  previous initial selection. Optional experimental rebuild passes existing bridge
  through the existing input patcher after the CPU guard, preserving both. Old
  native-only overloads remain; purchases stay off in experiment, global/default
  purchase behavior elsewhere unchanged. No new native algorithms/catalog entries.
  Game menu Capture log + export APK opens/starts Shizuku capture when ready;
  return stops/displays package-scoped crash output. Export log + APK chooses SAF
  folder, streams installed base/splits as unchanged APKs or raw numbered parts
  <=20,000,000bytes, plus named-game log and completion/reassembly/SHA-256 file.
  Excludes OBB/downloads/saves/private data; converted installed copy is identified
  as such, never claimed original. No capture/missing final output remains explicit.
  New folder per export; source-change/write failures reject and clean partial
  folder when provider permits. No upload. Global journal/export unchanged.
  Tests executed: compatibility/log/export script, full host suites, unchanged43
  structural cases TP12/TN31/FP0/FN0, new combined native/CPU/touch fixtures and
  missing-bridge rejection; Android-core compile. Export boundaries, actual20MB+1,
  split installs, byte reassembly, SHA-256, immutable input, missing/unsafe/duplicate
  sources, destination failure and concurrent truncation pass. Full supplied
  Monocle conversion preserves29 assets, original APK and prior emulated native
  output; CPU guard retained with input hooks/helper. Native matrix replay verifies
  12 available outputs;5 unavailable SKIPPED, nonzero INCOMPLETE, no failures;
  frozen17 profiles/four source hashes unchanged. No phone touch/provider/Shizuku
  flow test available. docs/game-support-export.md and updated conversion docs
  describe usage/limits. Push source/tests to GitHub and link build without waiting.


- **2026-11-02 — Automatic touch preparation for every installation method:**
  owner requested controls by default regardless of install route. Add game
  standard/suggested/manual/startup/purchase/experimental and APK doctor now use
  one prepareForInstall entry point, which always requests the existing touch
  patcher. Removed touch checkboxes and duplicate with/without-touch menu modes;
  four manual repair/purchase choices remain. Input support/skips still reported;
  protected/reserved DEX stays byte-identical without an appended helper. Doctor
  now loads bridge and prepares controls before signing/export/install. Versioned
  readiness marker prevents doctor copies prepared under the old controls-off
  policy being installed/exported without re-preparation. Other purchase defaults,
  experimental purchase isolation, native algorithms/catalog and detector intact.
  Low-level explicit conversion APIs keep prior behavior for tests/inspection.
  Already installed games still need reconverting; outside installers unaffected.
  Tests executed: full host compatibility/input/purchase/native/doctor suite,
  structural/CPU/assessment/experimental suite (43 detector cases unchanged,
  TP12/TN31/FP0/FN0), compatibility/log/export script, Android-core compilation.
  New preparation checks cover ordinary and startup/doctor paths with purchases
  on/off at both API floors, input hooks/helper/assets/original preservation,
  unchanged purchase DEX when off, missing bridge rejection, protected DEX skips
  and experimental CPU/native/touch composition. All pass. Four frozen native
  source hashes checked unchanged;17 catalog profiles retained. Native replay
  not repeated for this policy/UI change (prior12 VERIFIED,5 fixture SKIPPED).
  Phone touch/provider flows untested. Updated assessment/experiment/shared-rule
  docs. Push source/tests and link GitHub build without waiting for completion.


- **2026-11-02 — Monocle visible pad ignored; shared legacy OUYA JSON input:**
  owner screenshot shows gameplay and pad, but input not registering. Kept AI/
  GitHub-only diagnosis work PARKED on its separate branch/PR4; returned to
  feature/shared-compatibility-rules at4fecc665 for this game-input repair.
  Inspected supplied original DEX and managed assemblies without executing them.
  Launcher owns old Gson/UnitySendMessage JSON SDK, not modern OUYA JNI route.
  checkDevices excludes Android virtual devices; managed InputListener drops
  events without a matching descriptor, maps DeviceName, reads nested KeyEvent
  mKeyCode/mRepeatCount and AxisX/Y/Z/RZ. Existing fallback's keyboard device ID
  does not establish that controller. Java SDK managed player starts1, playerNum0.
  Added structural route recognition based on DTO/gate types, enumeration and
  snapshot references, Gson/Unity target and callback signatures/names. No game
  name/hash/offset selection, purchase changes or native/managed algorithm edits.
  Matched lifecycle hooks select new LegacyOuyaPadSink. It announces an in-process
  player1 descriptor, retains physical snapshot descriptors, respects enable
  gates, rejects ID collision, and sends existing button/axis JSON messages.
  Does not create a real Android InputDevice. Reannounces before events to cover
  GameObject readiness/later device snapshots. First use migrates old fallback
  mode once to Auto(legacy OUYA JSON); subsequent manual choices respected.
  Added center-bottom Look/RS drag pad on wide non-Marmalade layouts, independent
  Z/RZ axes in VirtualPad and Android fallback. Radial clamp/deadzone/dedup;
  release/cancel/hide/focus/pause/mode switch cleanup neutralizes both sticks,
  continuing other releases if one sink operation fails. Existing movement keys
  and modern OUYA/Marmalade behavior retained. Selected route logged.
  Tests executed: full host/native/input/purchase/doctor/shared rules and generated
  structural/CPU/assessment/experimental suites pass;43 native detector cases
  unchanged TP12/TN31/FP0/FN0. New two-namespace SDK fixtures and near-matches reject
  wrong fields/callbacks/targets; fake consumer covers descriptors/player numbers,
  key JSON, both sticks, physical preservation, disabled gates/collision/release.
  Stick tests cover clamp, deadzone, repeats, overlap and failed-axis cleanup.
  Java8 bridge + D8 minAPI23, Android-core compile and compatibility/log/export
  script pass. Full supplied Monocle conversion matches new route, resolves new
  lifecycle/helper methods, preserves29 assets/original, CPU getter guard and
  prior emulated native repair bytes.17-profile/four frozen source hashes remain
  unchanged; native replay not repeated (previous12 verified,5 fixtures skipped).
  Phone View multitouch/managed callback response/gameplay remain UNVERIFIED.
  docs/legacy-ouya-input.md records evidence/limits and reconversion instructions.
  No vendor code/assets committed. Push source/tests; link build without waiting.

- **2026-11-02 — AIR hidden loading error evidence (Zombie Hearts Chicken):**
  owner supplied current ARM32 Core/STL/Stagefright/extension files and repeated
  nativeSurfaceCreated crash. Core exports that JNI method but still has17 real
  non-writable RELATIVE writes and TEXTREL; extension matches the existing repaired
  output; STL/two wrapper files have no TEXTREL. Current catalog changes0 files;
  writable-only rule correctly rejects Core. Core imports private Android media
  dependencies; actual device load/ABI outcome remains unknown. Original DEX
  silently discards UnsatisfiedLinkError from its pair of System.load calls.
  Unchanged literal PIC algorithm produced a PRIVATE scratch-only candidate:
  isolated18 loads at3 biases preserve registers/flags/next-PC, unwind meaning,
  original mappings and RW remaining writes. Mode/reference coverage is not
  independently established for every site; no mapping/full symbol-table evidence
  present. Candidate is NOT selected, registered or bundled; no new fingerprint,
  native algorithm or automatic native repair selection added.
  Added independent air.loader.report-hidden-error diagnostic rule. Requires
  matching AIR SDK load/getter/field/register/branch/flag/catch structure; adds
  one call after move-exception and a private helper using Log.w(String,Throwable),
  tag PocketOuyaAIR. Helper isolates logging failure; original successful/failed
  loading, flags, retries and catch continuation retained. Reparse verifies hook
  and helper; unknown shapes/collisions/protected/malformed/budget inputs retained.
  Shared startup/doctor coordinator composes this after OUYA cleanup, preserving
  same-DEX prior edits. Assessment reports diagnostic support separately without
  recommending native repair solely for logging. Conversion UI explains full log.
  Supplied DEX matched and idempotence verified: class sets retained;1603 unrelated
  primary and118 secondary method opcode/reference sequences retained; secondary
  DEX not replaced. D8 minAPI23 accepts resulting supplied DEX pair. No complete
  game APK/assets were supplied for an end-to-end repack or phone execution.
  Executed: new two-layout bounded loader tests (success, either load failing,
  already loaded, fail-then-success retry, logging failure, throwable identity),
  idempotence, six near-matches, reserved code in another DEX, malformed/duplicate
  input, read-only/coordinator/assessment/conversion preservation and same-DEX
  cleanup composition. Full host/input/purchase/native/doctor/shared suite passes;
  structural/CPU/assessment/experimental suite passes,43 generated detector cases
  unchanged TP12/TN31/FP0/FN0. Compatibility/log/export script passes with local JDK
  PATH; full Android core compile passes (existing annotation/deprecation warnings).
  Native shared-coordinator replay:12 VERIFIED outputs/replacement scopes match
  frozen catalog;5 missing fixtures SKIPPED with expected nonzero INCOMPLETE.
  All17 profiles/four frozen native source hashes unchanged. No purchase logic,
  touch routes or parked AI/backend work changed. docs/air-loader-diagnostics.md
  records evidence, limits and original-APK reconversion/full-global-log steps.
  Phone logging/linker/JNI/gameplay UNVERIFIED. No vendor files/candidate committed.
  Push source/tests; link GitHub build without waiting for APK completion.

- **2026-11-02 — AIR diagnostic confirmed by phone global log:**
  owner supplied full global journal. Latest Zombie Hearts launch at22:59:29
  shows STL load ok, then Android linker and PocketOuyaAIR both report Core
  rejected for TEXTREL, followed by nativeSurfaceCreated missing binding.
  Diagnostic executes on the actual Android14 phone; this initial loader cause
  is now VERIFIED. No private-media dependency error is observed at this stage;
  native repair/full startup remain unverified. No additional crash-only log is
  required to establish this blocker. Raw owner journal remains private.
  Continued private read-only validation with Capstone5.0.6, rooted in ELF
  function symbols/entry point/dynamic init/fini metadata, not a repair oracle.
  Bounded exploratory traversal:858 roots,111872 decoded starts,62 overlapping
  instruction/data claims,2444 unresolved indirect/PC-write/system edges; no
  independently rooted path to the18 proposed load sites established. Counts
  are research observations, not passed tests or complete control-flow proof.
  Cannot safely authorize the scratch PIC candidate using successful raw ARM
  decoding/unwind proximity alone. Current Core result remains UNSUPPORTED for
  automatic repair; no fingerprint/catalog expansion or native/source algorithm
  changes. Updated diagnostic evidence document with observed cause and precise
  remaining mode/reference evidence gap. All17 catalog profiles/four frozen
  source hashes checked unchanged; no application code changed this follow-up,
  so app suites/build need not be repeated. Push evidence only; no new APK fix
  or confirmed gameplay is claimed.

- **2026-11-02 — Crash pass first; owner running baseline and reusable SDK/NDK rules:**
  owner confirms21 running games (including Knightmare Tower, absent from journal)
  and identifies six control-issue titles. Recorded baseline and deferred controls
  in docs/crash-repair-pass.md. Exclude old Space Shooter/Abbigale/Monocle crashes
  from current crash targets. Journal contains20 other crash/engine-stop titles;
  separated confirmed causes, existing repairs, new candidates and missing inputs.
  Implemented independent android.legacy-sdk.explicit-service rule for legacy
  MOGA/Google Analytics SDK Context.bindService calls. Direct/range invokes retain
  registers/args/results; private helper copies only otherwise implicit Intents
  for the two SDK actions and sets their provider package. Explicit/unknown/null
  inputs retain original platform call; no fabricated binding, service, permissions
  or controller. Candidate games: Abbaye des Morts, Zombie Kill of the Week,
  Zombie Outbreak, YOU DON'T KNOW JACK. Their actual APKs not supplied/verified.
  Added ouya.game-data.null-cursor-close: null-only Cursor.close guards in SDK
  getGameData(String):String. Move incoming/try labels to guard, preserving
  queries/returns/non-null closes/original exceptions. No fake provider, saved
  data or receipts. X S.E.E.D is a logged candidate; actual APK unavailable.
  Added elf.arm32.ndk-dependency-path: strict bounded ELF/dynamic/string/mapping
  and relocation checks; allowlisted public NDK host paths become existing basename
  suffix indices in DT_NEEDED. Only those index words change; code/strings/relocs/
  permissions/length retained. Reject aliases, private imports, unsupported layouts,
  metadata writes and excessive dependencies. Zombie Driver HD log identifies this
  defect, but its native fixture is unavailable; namespace/phone boot unverified.
  Coordinator composes new rules with old cleanup/AIR diagnostics/stale declarations.
  Assessment recommends startup route for matching defects; report names each rule
  and residual unsupported findings. No helper DEX needed for new rules. Updated
  startup menu/explanation; doctor preparation marker v2 requires regeneration of
  older prepared copies. Purchase code, input mappings, native algorithms/catalog,
  read-only finalizer detector and parked AI/backend remain unchanged.
  Executed: generated NDK fixtures across layouts and Windows/Unix separators,
  read-only/immutability/byte-scope/idempotence, near-matches/private names, malformed
  bounds, file aliases/alignment, metadata/BSS relocations, resolver/packed rejection
  and dependency budget. Two SDK namespaces/direct+high-range registers; recognized/
  unknown/null actions, explicit Intent identity/extras, real true/false binding,
  missing provider/permission errors/null Intent, collisions/signature/receiver/
  super-call near-matches, protected/malformed/duplicate DEX. Cursor models cover
  null/non-null cleanup, incoming branches and catch-all original error propagation,
  purchase-method retention. Combined conversion/asset preservation/assessment pass.
  Full host/input/purchase/native/doctor/shared and structural/CPU/assessment/
  experimental suites pass. Generated detector unchanged43 cases TP12/TN31/FP0/FN0.
  Full Android core compile passes (existing annotation/deprecation warnings);
  generated high-register helper/caller D8 minAPI23 and compatibility/log/export
  script pass. Available real Polarity DEX matches3 cursor sites and old shutdown
  cleanup; composed repair and idempotence pass. Running Monocle's complete APK
  has no new SDK match; all5 native inputs have no eligible dependency edit. Its
  original bytes retained. Other20 baseline APK fixtures unavailable, not passing.
  Native replay12 VERIFIED with exact old replacement sets/output hashes;5 fixtures
  unavailable (including after attachment search), explicitly SKIPPED with nonzero
  INCOMPLETE. All17 profiles/four frozen source hashes checked unchanged. Actual
  six targeted game APKs/phone startup remain UNVERIFIED. Native/graphics/device-ID
  and translated-engine failures remain pending necessary binaries/context; no
  guessed flags/count clamps/IDs or new APK-specific fingerprints. No vendor input
  or raw journal committed. Push source/tests/report; link build without waiting.

- **2026-11-03 — Abbaye follow-up: missed startService path and MOGA resolution:**
  owner's23:44:16 trace explicitly fails at ContextImpl.startService from MOGA
  Controller.init. Previous pass covered bindService only and missed this API.
  Extended the same SDK/API-based rule to direct/range startService(Intent),
  preserving actual ComponentName/null return and caller register/try flow.
  Separate private start/bind helpers; skip helper bodies during matching.
  Also corrected the earlier MOGA provider-package assumption: action namespace
  does not establish a package. Both helpers now query Android's service registry
  for the original implicit MOGA Intent; one visible valid result supplies its
  component on a copy. Zero/null query results return unavailable(false/null),
  multiple/invalid records reject, and platform security/background exceptions
  propagate. Explicit/unknown/null Intents retain original call. Analytics keeps
  its known Google provider scope. No service success, IDs, input or data invented.
  Public MOGA integration source used as API-context evidence, not as proof of the
  owner's SDK bytecode; no upstream/vendor code included. Old unrecognized helper
  layouts remain collision/unchanged and require original-APK reconversion.
  Added literal values to serialized-helper contract checks; changed registry
  cardinality literal is rejected rather than recognized. Doctor marker v3
  invalidates older prepared copies. No purchase/input/native/profile changes.
  New tests: both SDK namespaces/direct+high-range start calls, actual object/null
  identity, recognized/unknown/null actions, explicit Intent identity/extras,
  zero/unique/multiple/invalid provider results, security/background errors,
  receiver/signature/super/foreground/collision near-matches, matching-helper
  retention/idempotence and start-before-bind ordering. Binding models updated
  for actual registered components and absent/ambiguous providers. Full host/
  input/purchase/native/doctor/shared and structural/CPU/assessment/experimental
  suites pass; generated detector43 cases unchanged TP12/TN31/FP0/FN0. Full
  Android core compile passes (existing warnings); generated combined high-range
  start+bind helpers/callers pass D8 minAPI23. Monocle actual fixture remains
  untargeted; Polarity's3 cursor guards + old cleanup still compose/idempotent.
  All17 profiles/four frozen native source hashes unchanged. Native fixture replay
  not repeated for this SDK-only update; previous12 verified/5 unavailable and
  skipped remain the recorded scope. Abbaye APK fixture and phone boot remain
  UNVERIFIED. Updated evidence/instructions; push source/tests, link queued/running
  APK build without waiting for completion. Controls and parked backend deferred.

- **2026-11-03 — Store (community server), phase 1:** owner asked for stouyapi again as a store.
  Added STORE: StoreClient (discover pages, details, download link; http/https redirects; size +
  MD5 check; host-tested with server-shaped data and a fake network), StoreActivity (rows,
  categories, bundles; editable server, default http://ouya.cweiske.de), StoreGameActivity
  (details, screenshots, download with progress/cancel, hands the APK in files/store/ to the
  normal MainActivity assessment/conversion/install). INTERNET permission and cleartext traffic
  (server is HTTP-only). Formats taken from the owner's pasted server responses. Android UI is
  compiled by CI only; device test pending. **Next:** server purchases (owner chose store +
  purchases): products/gamer info for all OUYA SDK games; receipts only where games accept
  plain data (no receipt-encryption bypass).

- **2026-11-04 — OUYA-shaped touch pad:** owner asked for the OUYA controller's layout with two
  analogue sticks, for all games. TouchControls (shared by every game) now has left stick + d-pad
  left, face diamond + right stick right, L1/L2 and R1/R2, Menu centre. VirtualPad gained
  leftStick() and L2/R2; VirtualPadState maps L2/R2 to buttons plus trigger axes; GameMaker maps
  them to gp_shoulderlb/rb. Host tests pass; TouchControls compiled by CI only; phone untested.
  Engine-specific input routes are unchanged (only the on-screen layout is shared).
