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
   Some games will need stubs later. Never bypass purchase checks.

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
- Don't bypass Ouya/IAP purchase checks.

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
