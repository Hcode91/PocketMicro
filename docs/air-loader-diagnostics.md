# Hidden Adobe AIR loader errors

Some legacy AIR Java loaders catch `UnsatisfiedLinkError` from their native
library loads and discard it. The game then continues until a call such as
`AIRWindowSurfaceView.nativeSurfaceCreated()` fails. That later exception proves
that the native binding is unavailable; it does not identify the initial loading
failure. Missing files, text relocations and unavailable dependencies can all
lead to it.

Startup repairs and the APK doctor's shared rule coordinator now include
`air.loader.report-hidden-error`. Its applicability is structural within the
known AIR SDK API, independent of game package, filename, hashes or code offsets:

- The instance loader checks a declared static Boolean success flag.
- An Application field supplies `Utils.GetLibSTLPath(Context)`; the resulting
  String feeds `System.load`.
- The same instance's `GetLibCorePath()` supplies the second `System.load`.
- The success flag is set only after both loads. The initial branch skips to
  the same void return.
- One `UnsatisfiedLinkError` catch covers that load sequence. Its handler only
  receives the exception and branches to the return.
- Registers, declared fields, instruction boundaries, branch destinations and
  handler ranges must agree. Other instruction or catch layouts are UNKNOWN and
  retained. Reserved instructions, duplicate classes, malformed DEX and inspection
  budgets also prevent the edit.

The rule inserts a call immediately after the handler's `move-exception`, using
the original exception register and no new registers in the loader. A private
static helper calls `Log.w(String, Throwable)` with tag `PocketOuyaAIR`. A separate
Throwable handler isolates logging failures. Original loads, exception handling,
success flag, return path and native files remain unchanged. The helper is in the
existing SDK class; no additional helper DEX is needed for diagnostics.

The rewritten DEX is parsed again and the complete hook/helper contract checked
before a replacement is returned. Repeated input is recognized without another
edit. A colliding helper name with an unrecognized body rejects the edit.
SUPPORTED and VERIFIED describe diagnostic eligibility and serialized edit
checks, respectively. Neither establishes a native repair or successful launch.
The pre-install assessment reports diagnostics separately and does not recommend
a native repair merely because this hook is available.

## Phone test

1. Install the updated PocketOuya build.
2. Convert the **original complete APK** using **Startup repairs + touch**.
   Previously converted APKs with embedded touch helpers should be reimported
   from the original, as usual.
3. With Shizuku running and PocketOuya authorized, launch the game and reproduce
   the failure.
4. Export the **full global game log**. Find `PocketOuyaAIR` and the preceding
   native loader output. Crash-buffer-only reports may omit this warning.

The diagnostic does not make Android accept text relocations, supply missing
dependencies, suppress a native-method exception or indicate successful loading.

## Zombie Hearts Chicken evidence, 2026-11-02

The supplied DEX has this empty catch, and the supplied Core exports the JNI
method reported missing. Its Core still declares TEXTREL and has 17 RELATIVE
loader writes to non-writable memory. The existing catalog rejects that Core
build; its native extension is already the known repaired output. STL and both
supplied Stagefright wrappers do not declare TEXTREL. Core also requires private
Android media libraries and old C++ symbols; their availability and ABI on the
phone have not been measured. The later missing-native-method crash alone does
not distinguish the initial loader failure.

An **offline scratch candidate**, produced by the unchanged existing literal PIC
algorithm, passes isolated tests of 18 load sites at three load biases, with
registers, flags, next-PC, original unwind meanings/mappings and remaining RW
loader-write checks preserved. These results do not prove complete reference
coverage, independently establish every instruction mode/boundary, or execute
Android's linker/JNI/runtime. No mapping-symbol/full symbol-table evidence was
present to establish all those sites independently. The candidate is **not**
registered, bundled or selected by the app. No new fingerprint or native
transformation has been added.

The diagnostic hook matches the supplied real DEX and is idempotent. The primary
DEX retains its class set and 1,603 unrelated method opcode/reference sequences;
the secondary DEX's 118 method sequences remain unchanged. This is a partial
input inspection, not a reconstruction of a complete game APK. Phone logging,
native loading and gameplay remain unverified.

## Executed checks

- Host suite: both register layouts; successful, first-load-failed and
  second-load-failed paths; already-loaded and failed/retry flag behavior;
  original throwable identity; logger exceptions; idempotence; six near-matches;
  truncated DEX, duplicate classes and absent loader.
- Coordinator and APK conversion: read-only inspection, asset/native isolation,
  same-DEX composition with the OUYA cleanup rule, diagnostic reporting and
  assessment that does not equate diagnostic support with native repair support.
- Existing host/input/purchase/doctor/assessment/shared/experimental suites,
  generated structural detector suite and Android source compilation.
- Compatibility/log/export script; native replay results and missing-fixture
  counts are recorded in PLAN.md. No missing fixture counts as passing.

No vendor APK, DEX, library, assets or scratch candidate is stored in Git.
