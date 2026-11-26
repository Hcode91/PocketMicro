# Crash repair pass: 2026-11-02

The owner supplied a global journal and confirmed 21 games as running. This pass
prioritizes startup/crash defects. Controls, the game bridge and the parked AI/
backend project are unchanged. Older crashes for owner-confirmed working games
are not evidence that those games currently require another repair.

## New reusable rules

Select **Startup repairs + touch — native engines / legacy SDKs** on an original
complete APK, or use the APK doctor. Pre-install assessment can recommend that
route when supported structure is found. Existing games must be reconverted to
receive the changes. The doctor invalidates older prepared copies with a new
preparation marker; the original copy remains available for preparation.

### Explicit legacy service starts and bindings

`android.legacy-sdk.explicit-service` matches the old MOGA and Google Analytics
SDK namespaces and platform `Context.bindService(Intent, ServiceConnection, int)`
and `Context.startService(Intent)` calls. Eligible virtual calls use a private static helper in that
SDK class. The receiver/argument registers, range form, return value and original
method body are retained. Super calls, custom receiver descriptors, other
signatures and colliding helpers are not changed.

At runtime the helper checks two SDK contracts:

| Implicit action | Resolution |
| --- | --- |
| `com.bda.controller.IControllerService` | Unique visible registered service; use its actual package and class |
| `com.google.android.gms.analytics.service.START` | `com.google.android.gms` |

Only an otherwise implicit Intent for one of these actions is copied and given
its resolved component (MOGA) or known provider package (Analytics).
MOGA's action namespace does not establish a provider package. Zero visible
matches produce unavailable (`false` for bind, `null` for start); multiple or
invalid results reject with an explicit error. Registry visibility can limit
discovery. Component-explicit, package-explicit, unknown-action
and null inputs are passed to the original platform call. The original Intent,
extras, binding flags and connection are retained. Android performs the actual
binding or start when a provider is selected: actual Boolean/ComponentName/null
results, permission errors and background-start restrictions propagate. No physical controller is
created and no analytics event, store response or purchase is invented.

The log identifies this exception in Abbaye des Morts, Zombie Kill of the Week,
Zombie Outbreak and YOU DON'T KNOW JACK. Their APKs were not supplied for this
pass: they are **candidates**, not verified repaired games. Other APKs using these
supported SDK/API contracts can match without adding a game identifier or hash.

The owner's subsequent Abbaye trace at **23:44:16** explicitly identifies
`ContextImpl.startService`, called by `Controller.init`. The previous pass covered
only `bindService` and missed this path. Both calls now match, including direct/
range forms and an initialization sequence that starts and then binds. Public
Android service-registry APIs supply the component; a
[public MOGA integration example](https://github.com/Rakashazi/emu-ex-plus-alpha/blob/1c12fac5ce49badaadff2e2f210dcc30b89f4943/imagine/src/base/android/imagine-v9/src/main/java/com/bda/controller/Controller.java)
also illustrates component discovery. That example does not establish the exact
SDK bytecode in the owner's APK, which remains unavailable for direct inspection.
Reconvert the original APK; unrecognized older injected helper layouts are
retained with a collision result. Doctor readiness version3 requires preparation
of cached older copies. Successful Abbaye boot remains unverified.

### OUYA game-data cleanup

`ouya.game-data.null-cursor-close` adds a null guard before `Cursor.close()` calls
in `OuyaFacade.getGameData(String): String`. Existing queries, data/default return
paths, non-null closes and original exceptions are retained. Incoming branch and
try-boundary labels move to the guard so that branches cannot bypass it. No saved
data, provider, cursor contents or receipt is synthesized. Other OUYA methods,
including purchase handling, remain unchanged.

Unsupported register/continuation shapes are retained. Serialized output is
reparsed; recognized guards and helpers make repeated conversion idempotent.
Duplicate classes, reserved instructions, shadowed platform classes, malformed
DEX or budget limits prevent edits. Both SDK rules compose with the existing
OUYA shutdown cleanup and AIR diagnostics in the same DEX.

X S.E.E.D's trace identifies the null `Cursor.close()` failure in this SDK method.
Its APK was not supplied, so actual game eligibility and startup remain unverified.
The supplied Polarity DEX independently contains three matching cursor-close
sites and the already-supported null-context shutdown cleanup. Their composition
is checked on that real DEX without claiming successful Polarity loading.

### Host NDK dependency paths

`elf.arm32.ndk-dependency-path` recognizes absolute Windows/Unix NDK stub paths
inside ARM32 ET_DYN `DT_NEEDED` entries. It supports a small allowlist of public
Android NDK sonames, including EGL/GLES, and rejects private-library paths.

The rule derives the basename suffix already present in bounded `DT_STRTAB`
bytes, then changes only the corresponding dependency string indices in
`PT_DYNAMIC`. File length, native code, strings, relocations and permissions stay
unchanged. Headers/mappings, string bounds, aliases, relocation layouts and
dynamic-metadata write targets are checked; ambiguous/unsupported metadata is
retained. The output is reparsed and changed-byte scope verified. This does not
repair text relocations, supply missing libraries or expose private Android APIs.

Zombie Driver HD's log names an NVPACK build-machine `libEGL.so` path matching
this defect. Its native library has not been supplied. The rule can be attempted
through conversion, but actual eligibility, namespace resolution and game startup
are not verified by the log alone.

## Remaining logged failures

The following excludes the owner's working baseline. Entries describe observed
failures rather than successful launch predictions. A matching static repair can
leave another issue visible after installation.

| Game(s) | Observed failure | This pass / next evidence |
| --- | --- | --- |
| Abbaye des Morts; Zombie Kill of the Week | Implicit MOGA service start/bind | Both APIs covered by the shared rule; test original APK conversion. Abbaye's earlier engine `glStencilMask` stop is a separate path. |
| Zombie Outbreak; YOU DON'T KNOW JACK | Implicit Google Analytics service bind | New service rule candidate; test original APK conversion. |
| X S.E.E.D | Null Cursor cleanup in OUYA game data | New null-close rule candidate; original APK needed to confirm actual match. |
| Zombie Driver HD | Absolute host NDK EGL dependency | New dependency rule candidate; original APK needed to confirm ELF layout. |
| A.D. 2442 | `libmain.so` TEXTREL | Existing exact native profiles may apply; reconvert original with startup repairs and check all reported matches. No new profile added. |
| Polarity: Partners In Crime | Null-context OUYA cleanup on activity destruction | Existing cleanup matches supplied DEX; new cursor rule also matches. This does not establish a fix for its separate loading stall. |
| A Zombie Stole My Toaster; XP1728; Wrestling Revolution; Wrestling Revolution 3D | Core TEXTREL in runtime output, then missing AIR JNI method | Exact supported Core builds can match existing catalog. Original native files needed for unknown builds; AIR diagnostics can expose subsequent errors. |
| Zombie Hearts Chicken | Confirmed Core TEXTREL rejection | Files already inspected; automatic repair remains unsupported pending independent mode/reference evidence. No extra crash-only log needed. |
| Ziggy Putts; Wrecking Balls Arena | `libmain.so` TEXTREL | Need original APK/native libraries to inspect supported structure; do not remove flags for actual code writes. |
| Zombies and Trains! | Repeating native stack-overflow cycle in anonymous mapping | Need original embedded Mono/Unity runtimes and loader/DEX context. Similarity to an older game is not sufficient to select a patch. |
| XY Cannon | Array bounds rejection in `GLES20.glUniform4fv` | Need DEX and shader/data context to validate count/array/offset semantics; no automatic count clamping. |
| Wraithborne | Restricted `TelephonyManager.getDeviceId` in startup | Need DEX to determine how the identifier is used; no fabricated device ID or blanket exception suppression. |
| Worm Copter | Native null dereference in old Chromium content library | Need complete APK/runtime for symbol/control-flow investigation. |
| World Forge 2D | Translated engine unmapped-memory read | Need original APK/payload and engine trace context; no guessed memory/PC patch. |

Use **Capture log + export APK** when supplying a missing game fixture; the
exporter splits APKs above 20 MB and preserves the installed bytes. An original
APK is preferable when testing a fresh conversion. No user binaries/logs are
published in Git.

## Owner-confirmed running baseline

| Game | Owner's additional note |
| --- | --- |
| Zzap Lines | Runs |
| A Space Shooter For 2 Bucks | Runs; older crash excluded |
| Abbigale and The Monster | Runs; older crash excluded |
| Knightmare Tower | Runs; no journal entry identified |
| Maldita Castilla | Runs |
| Monocle Man | Runs; older crash excluded |
| Muffin Knight | Runs |
| unwind | Runs |
| Vector | Runs |
| ZombTack | Runs |
| Zombie Trek Driver Survival | Runs |
| Zipangu | Runs |
| Zeus vs Zombies | Possible control issue, deferred |
| ZeptoRacer 3D | Runs |
| ZeptoRacer | Runs |
| YuletideRush | Runs |
| You Are Here At Your Own Risk | Pad disappears, deferred |
| XD: No crosswalk | Controls do not respond, deferred |
| XD: MiniGravtron feat. VVVVVV | Controls do not respond, deferred |
| XD: Mini Pang! | Controls do not respond, deferred |
| XD: DrunkPunk's Bug Catcher! | Controls do not respond, deferred |

This is owner evidence, not 21 automated test passes. The complete Monocle fixture
is available locally and the new SDK/dependency rules do not target its DEX or
five native inputs. The other 20 working-game APK fixtures are unavailable for
this pass; they are not counted as passing regression tests.

## Validation scope

Generated ELF fixtures cover multiple layouts and Windows/Unix separators, public
basenames, idempotence/immutability, unchanged bytes outside dependency indices,
near-matches, private imports, truncated bounds, aliases, alignment, metadata/BSS
relocations, unsupported resolvers and packed layouts. SDK fixtures cover direct
and high-register range start/bind calls in both SDK namespaces; recognized/unknown/null
actions, explicit Intents, original Intent identity and extras, real bind results,
missing provider and permission failures. Cursor fixtures exercise null/non-null
cleanup, incoming branches and catch-all exception paths, and retained returns
and purchase methods. Near-matches, collisions, malformed/duplicate/protected DEX,
combined conversion, asset preservation and assessment routing are checked.
Start tests also cover actual ComponentName/null identity, background-start
exceptions, no/unique/multiple/invalid registry matches, matching-helper
idempotence and start-before-bind ordering. Foreground-service APIs are excluded.

Generated fixtures and bounded semantics models are not ART or phone execution.
The six newly targeted affected APKs were not supplied and remain unverified.
Full suite/native replay, D8/source compilation and available real-fixture results
are recorded in the development log; unavailable fixtures are explicitly skipped.
