# Soul Fjord: video lifecycle investigation

Status: **cause unknown; no automatic repair justified yet**. Read-only investigation,
2026-11-03, on `feature/unity-video-surface`, based on `f6764c1`.

## Input and executed checks

The owner's public Drive folder supplied the complete original Soul Fjord 1.0 APK
(`com.airtightgames.soulfjord`, version code 1). Its manifest targets API 16 and
launches `com.airtightgames.soulfjord.SoulFjord`.

| Check | Result |
| --- | --- |
| Download and ZIP CRC check of all 1,719 entries | Passed |
| Manifest inspection with Android SDK `aapt` | Completed |
| Original `classes.dex` disassembly with existing SDK `dexdump -d` | Completed |
| Trace Java video entry/exit, game pause/resume and view manager | Completed; findings below |
| Compare graphics pause semantics with Android 14 AOSP | Completed |
| Reproduce crash on Android / validate a repair | Not executed; no attached phone |
| Inspect newly supplied global log and crash-buffer stack | Completed; see follow-up below |
| Independently inspect a full native tombstone | Unavailable: Android attached no dump to the supplied exit record |

APK SHA-256: `c9fdb8d57d79fa0e48c78924e712c74f928c6dd22a280234f3a37d0235507e64`.
Original DEX SHA-256: `4465b986ca2f1f7b982b3b1542c0d813c303b119d2ecdf2a6931d61d72dd848b`.
These identify inspected artifacts, **not repair fingerprints**. No game binaries or
disassembly are committed. Existing binary repair algorithms and selection are unchanged.

## Independently confirmed Java behavior

Names below identify this inspected build. They must not become obfuscated-name or
offset selectors for a reusable repair.

1. `UnityPlayer.showVideoPlayer(String, int, int, int, boolean, int, int)`
   dispatches `UnityPlayer$18.run()` to its UI runnable dispatcher.
2. That runnable checks that there is no existing video, acquires the video wake
   lock, and calls the normal `UnityPlayer.pause()` **before** constructing or
   assigning the video player. The apparent boolean setter
   `UnityPlayer.d(UnityPlayer, boolean)` is a wake-lock helper, not a video-mode flag.
3. `pause()` updates Unity's lifecycle state and queues native pause work on the
   graphics thread, waiting on a semaphore. On the applicable GLSurfaceView path,
   it removes the game view through the view manager and subsequently calls
   `UnityGL.onPause()`. It also handles audio, receivers and sensors.
4. The view manager (`y.e(View)`) removes auxiliary views and the game view from
   the root, clears its tracked game-view field, and reveals a fallback view.
5. The video class (`ae`, a FrameLayout) creates its own SurfaceView and registers
   a SurfaceHolder callback. The show runnable adds that video container to Unity
   and gives it focus.
6. `hideVideoPlayer()` dispatches `UnityPlayer$20.run()`. This removes the **video**
   container, clears the video reference, calls `UnityPlayer.resume()`, and releases
   the video wake lock. Its `removeView` call is not the game-view removal.
7. The resume helper (`UnityPlayer.h()`) resumes the video if one still exists;
   otherwise it calls `UnityGL.onResume()`, restores lifecycle resources, re-adds
   the game view when absent, and queues native resume work where applicable.
8. The game GLSurfaceView subclass (`ac`) extends Android's GLSurfaceView directly
   and does not override `onPause`/`onResume`. Its `onDetachedFromWindow` override
   does not call the superclass. A separate shutdown method explicitly calls the
   superclass detach method. This intentional distinction must be preserved.
9. `UnityPlayer.onDrawFrame()` calls `nativeRender()` before checking its later
   lifecycle flags. Skipping the graphics-thread pause while leaving native pause
   in place is therefore not established as safe: Java does not first guard the
   native render call with the video's paused state.

## Why keeping the view attached is insufficient

[Android 14 GLSurfaceView source](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/opengl/java/android/opengl/GLSurfaceView.java)
has two separate operations in `GLThread.guardedRun()`:

- When pausing with an EGL surface, it calls `stopEglSurfaceLocked()`.
- When pausing with an EGL context, it releases the context only if context
  preservation is disabled.

Thus a preserved context does not establish a preserved EGL surface. Removing only
the view-manager detach call would still reach `GLSurfaceView.onPause()` and its
surface-release path. Conversely, skipping the whole Unity pause would also skip
native lifecycle synchronization, audio and resource handling. Neither is a
verified, narrowly scoped fix for the reported Scaleform fault.

This is verified against AOSP Android 14 behavior, not the exact Samsung framework
binary. The APK call path is verified statically; its execution in the failing
installed conversion still needs correlation with the runtime log.

## Evidence inherited from the prior investigation

The handoff reports a repeatable Scaleform render-queue null pointer after a video
ends, with `RTCommandQueue::PopCommand`, `SFUnityRenderThread::ProcessCommands`,
`SFManagerImpl::Display` and `UnityRenderEvent` in the stack. It also reports that
the manager's render-thread field is null while other manager state looks valid.

Those observations have **not been independently rechecked in this session**.
The original APK alone does not identify which installed conversion produced the
crash, which movie played, or which operation made the field null. It contains
`sf_AT_logo.mp4`, `sf_intro.mp4` and `sf_outro.mp4`; the reported five-second video
must not be assumed to be `sf_intro.mp4` just from its description.

The handoff's observation that rendering occurs on UnityMain does not itself rule
out earlier cross-thread writes or memory corruption. A temporal relationship to
video completion is a useful hypothesis, not proof of the null write's cause.

## Next bounded experiment

First obtain the full global log with Android's crash record, starting before launch
and continuing through the video and crash. Record the installed conversion mode
and PocketOuya revision alongside it. Compare native-library identities, lifecycle
events, video completion and crash frames against this original artifact.

If a surface-preservation experiment is then warranted, it needs all of these:

- Structural identification of the linked video show/hide, native pause/resume,
  GL view and view-manager paths; reject ambiguous layouts.
- A distinct video-only suspension state, preserving normal Home/Back/screen-off
  lifecycle behavior. Keep native pause/resume paired and prevent rendering while
  native state is paused without releasing the EGL surface.
- Correct layering and focus for the second SurfaceView; retention alone does not
  establish that video will be visible above the game.
- Cleanup for completion, media failure, cancellation and activity destruction,
  including wake locks, video references, audio, receivers and sensors.
- Runtime evidence that the intended surface was actually retained. A changed
  crash time or a menu appearing once is not proof of surface preservation.
- Explicit opt-in, reversible conversion until phone tests establish behavior.

Required device checks: repeated cold launches; normal completion and skipping the
video where supported; Home/resume and screen-off during playback; cancellation;
subsequent movies; menu input, gameplay and audio. Compare against the unchanged
conversion. Host regression tests must cover lifecycle ordering, ambiguous/near-match
layouts, no-video Unity APKs, repeated conversion and unchanged unrelated DEX/native
payloads. No tests or repair success are claimed for this proposed experiment.

## Follow-up: crash before any video (23:43 run)

The owner subsequently supplied `PocketOuya-global-game-log (2).txt` and confirmed
that this run closed **before any video**. This supersedes video handover as a
necessary-trigger explanation. Do not implement the proposed surface workaround
on the strength of the previous handoff.

The log identifies PocketOuya 0.1.84 launching a conversion made by 0.1.75 at
15:30 UTC, with startup repairs and touch enabled and experimental runtime disabled.
Android reports target API 16, and both Unity/Mono loads succeed. The text-relocation
message for Unity is explicitly a warning permitting the load, not this crash.

| Event | Time on 2026-11-03 |
| --- | --- |
| Process startup log | 23:43:25.743 |
| Unity game SurfaceView created | 23:43:26.047 |
| OpenGL ES context created | 23:43:26.050 |
| Scaleform library loaded | 23:43:26.881 |
| Store-unavailable callback | 23:43:27.443 |
| Crash-dump collection begins | 23:43:27.656 |
| Tombstone timestamp in crash buffer | 23:43:27.671586 |

No game-surface destruction/detachment, MediaPlayer playback, or second video
surface is recorded before the crash. Absence of a log marker alone would not
prove no video ran; the owner's observation independently confirms it here.
The exit-record section says “Reason: Java crash” but contains no dump. The actual
crash buffer identifies native SIGSEGV on UnityMain, at `pthread_mutex_lock+4`,
with address/register r0 = `0x40`. Prefer that explicit signal/backtrace over the
generic exit-record description.

Read-only LLVM disassembly of the supplied original Scaleform library independently
confirms the arithmetic behind this fault. Exported ELF function symbols have the
Thumb bit set; the following instruction trace uses Thumb mode from those entries:

- `SFManagerImpl::Display(bool)` loads the render-thread pointer at manager +
  `0x2c`, then calls `SFUnityRenderThread::ProcessCommands()`.
- `ProcessCommands()` adds `0x34` to its receiver to obtain its embedded command
  queue, then calls `RTCommandQueue::PopCommand()`.
- `PopCommand()` adds `0x0c` to that queue receiver before calling the mutex lock.
- The crash's preserved queue/mutex registers are `0x34` / `0x40`, consistent
  with a null render-thread receiver. It is not evidence of a valid queue whose
  internal mutex merely needs reinitialization.

The original Scaleform library SHA-256 is
`e2d6063ffd93b4dfdbb523333b64628d922ea5c4c51f005b7a536f6f1cf8d77c`.
The crash stack's function offsets agree with this artifact. This is corroboration,
not a byte-for-byte identity check of the installed library.

The normal factory allocates the manager and calls its constructor before publishing
the pointer. That constructor first clears +`0x2c`, allocates/constructs a
render-thread object, and finally stores its address in +`0x2c`. A crash-time null
does not establish that this final store executed successfully or that a particular
later operation erased it. This session did not recover the manager's memory or
observe writes to it. Allocation failure, partial/repeated initialization, lifetime
errors and corruption are investigation possibilities, not verified diagnoses.

The log also reports a missing static `fetchGamerInfo()` Java method and a store
failure callback. Their presence does not establish the cause of the native queue
fault. Do not alter purchases, fabricate callbacks, skip rendering or insert a
blanket null guard to make this report appear successful.

**Next evidence needed:** observe manager construction, publication and the first
render-thread-pointer read in the failing installed build, or obtain a native dump
that includes the manager/allocation state. The previous native-instrumentation
approaches remain deferred per the owner's handoff. Without that evidence, static
analysis cannot identify the write or initialization failure safely enough for an
automatic repair. Another ordinary crash-log capture alone may repeat this stack
without supplying the missing state.

The current result is **UNKNOWN**, not “fixed” or “unfixable”. No surface, native
library, purchase or input transformation was added in this investigation.

## First device experiment: original-code baseline

The owner authorized trying a fix. Before changing native rendering, the branch now
offers an explicit isolation test: import the complete original APK, choose **All
conversion options → Diagnostic baseline — original code, Shizuku install → Prepare
baseline**, inspect the report, then install with Shizuku running. Ordinary install
paths retain their automatic touch-pad behavior.

This mode retains the original target, copies DEX/native/assets without edits,
does not inject the bridge, strips old signatures and signs with PocketOuya's
existing game key. Normal manifest installation adjustments and a conversion
record still apply. It cannot reverse changes already present in the selected APK,
so the original input is required. An in-place update retains saves/settings;
do not clear data or uninstall for this first comparison. Signature mismatch can
prevent an update; do not resolve it by deleting saves.

This is an attempt to isolate a cause, not a Scaleform repair. The previous installed
build includes runtime promotion and multiple SDK changes. If the baseline gets
past the crash, compare individual conversion changes next. If it produces the
same Scaleform stack, those changes are not necessary for that failure. If it stops
earlier in the original loader, it establishes a different blocker and says nothing
about whether the rendering fault is fixed. Capture the global log in each case.

Executed checks: baseline regression covers unchanged source, retained target,
unchanged original ZIP payloads, no extra bridge DEX, and diagnostic conversion
provenance; ordinary default-touch regressions still pass. On the actual complete
Soul Fjord APK the baseline retained API 16, all 1,715 non-manifest/non-signature
payload entries matched SHA-256, ZIP CRC passed, and the only added entry was the
conversion record. Aggregate host tests and compatibility/log/export tests passed.
Android Java compilation passed with existing annotation warnings. Device execution
and the full CI APK build are pending; neither success nor a changed crash is claimed.

## Baseline device result and second trial (2026-11-04)

The supplied global log `(3)` confirms conversion and launch with PocketOuya
0.1.89 and `startup repairs=no, touch=no, experimental runtime=no`, plus the
`diagnostic.baseline` marker. This time a full native dump is available. Its main
thread (PID/TID 4634) faults near the stack guard at `0xff511ff0`; Android reports
likely stack overflow. The sole unwound frame is anonymous PC `0x7d7c88` and stack
memory shows repeated return-address groups and `basic_string::_S_create` text.
Startup output shows the original proxy loader loading the embedded Mono runtime,
then loading the Unity proxy. This is consistent with the previously observed
embedded-loader recursive string-error failure; its precise initiating error is
not established by the string alone.

This is an earlier, different fault from the UnityMain Scaleform queue crash. The
baseline did not reach the comparison point and cannot exonerate or implicate the
other startup repairs. Duplicate records with PID4634 and the same timestamp in
the journal refer to the same captured crash, not independent repetitions.

The next explicit test option is **All conversion options → Diagnostic Unity
runtime promotion only → Prepare runtime-only test**, using the complete original
APK with Shizuku running. It invokes only the existing structural embedded-runtime
promotion rule. It retains the original target, DEX, SDK behavior, assets and plugin
libraries; no input bridge or native instruction patches are selected. If no
runtime matches, preparation fails with an explicit unavailable result.

This trial intentionally uses the original embedded runtimes without catalog
repairs. A new earlier native loader failure is a possible result, and must not be
reported as a fix for Scaleform. If the queue fault returns, the disabled SDK/input
changes are not necessary for that failure. If it progresses further, add those
changes independently before attributing the difference to any one rule.

Host regression tests cover both runtime replacements, unchanged DEX/other payloads,
no bridge injection, provenance, retained target, immutable source and no-match
rejection. Aggregate host tests and Android Java compile passed. Actual Soul Fjord
conversion retains API16; both promoted libraries equal the original asset bytes.
Phone outcome for this second trial remains unverified. No new fingerprint,
instruction patch, purchase or Scaleform algorithm was introduced.

## Runtime-only device result (08:15 run, 2026-11-04)

The next supplied log, `PocketOuya-global-game-log (1).txt`, records v0.1.90 and
`diagnostic.runtime-only`, with both embedded runtimes promoted. Startup repairs,
touch injection and experimental runtime repairs are disabled. The recorded
process1351/thread1379 creates an OpenGL context at 08:15:27.557, then crashes on
UnityMain at 08:15:31.172448596. The signal, address0x40 and all four Scaleform
frame offsets match the earlier SDK/input-enabled conversion:

| Stage | Observed result |
| --- | --- |
| Original-code baseline | Main-thread embedded-loader stack overflow; comparison point not reached |
| Runtime promotion only | Loader passed; same Scaleform command-queue null-pointer fault |
| Earlier full startup repairs + touch | Same Scaleform command-queue null-pointer fault |

SDK/input edits are therefore **not necessary** to produce the queue crash in
this configuration. This does not prove that promotion is correct in every respect,
that it causes the queue fault, or that the untouched APK would reach that point
on this phone. The retained saves/settings were not controlled by a data reset.

The original Java `fetchGamerInfo` lookup error is still present. No synthetic
store-offline callback is recorded in this run; the native queue fault still follows.
There are no recorded MediaPlayer or game-surface-destruction events before it.
This further weakens a video-handover repair as the next experiment.

A full decoded native tombstone is now present for the Scaleform fault. It confirms
queue receiver0x34 and mutex pointer0x40. The stack contains repeated references to
`0xe4edb050`, consistent with the saved manager receiver and its +0x80 field address.
However, its memory windows cover stack/register targets, library code and GOT;
they **do not contain bytes at the manager's heap address**. A pointer appearing in
stack memory must not be mistaken for a dump of the pointed-to object. There is
still no observation of the constructor's final pointer store or the subsequent
write/lifetime event responsible for the null.

No further install-option permutations are justified by this result. The next
technical requirement is targeted native initialization/lifetime evidence, rather
than another ordinary startup log or a guard that silently skips drawing. No new
repair or build was added for this log-only result. The runtime-only trial did not
fix gameplay; the Scaleform cause remains UNKNOWN.
