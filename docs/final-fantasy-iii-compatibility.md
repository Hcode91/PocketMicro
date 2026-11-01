# Final Fantasy III 1.0.1 startup investigation

Inspected the owner's archive on 2026-11-01. This report concerns the exact supplied
files, not every FFIII release. No game code or binaries are included in this
repository. The archive omits the assets, and no device crash log was supplied.
**A launch fix has not been verified or implemented.**

## Identity and execution path

- Package: `com.square_enix.android_OUYA.FFIII`, version `1.0.1`, code 101.
- Launcher: `com.square_enix.FFIII_J.MainActivity`, extending Android `Activity`
  and implementing `GLSurfaceView.Renderer`.
- Manifest declares minSdk 13 with no explicit targetSdk. Android uses minSdk as
  the effective target in this case. Current conversion raises that value to 23.
- Three ARM32 `armeabi` libraries: `lib__57d5__.so`, `libjniproxy.so`, `libff3.so`.
  No arm64 runtime or GameMaker runner/data was supplied. Ordinary installed-app
  launch is the applicable path; PocketOuya's GameMaker Engine test cannot run it.
- OUYA Java interfaces are present, but this is not the matching Unity SDK
  protocol supported by the current purchase bridge.

SHA-256 evidence:

| File | SHA-256 |
| --- | --- |
| classes.dex | `3adcab783844714c34bae015b6b25b1f708ff729469474edf2dfdce14fb30be9` |
| lib__57d5__.so | `49588317d53c37ae402efcf948076df8b1a482b6beaa178f6a2a1c61eb6ede3e` |
| libjniproxy.so | `89192063a00ff257c80fc69434172e712d4c7dfc8412f74ce7738b322f3a389f` |
| libff3.so | `ff3fd2e59aa824f83e0c4236f7e0f84717cd327e69a138790360b5a39f536ab1` |

## Confirmed static incompatibilities

### Legacy wrapper loads before game initialization

Actual DEX instructions, rather than the included Java backup, establish this
startup chain: MainActivity's static initializer calls `MainActivitya.a()`, which
initializes MainActivitya. Its initializer calls `System.loadLibrary("__57d5__")`
and repeatedly invokes the native integer helper `MainActivityb.a(int)`. The
main initializer later loads `jniproxy`.

`readelf -d` finds `DT_TEXTREL` (twice) in `lib__57d5__.so`. Android's linker
rejects text-relocation libraries for apps targeting API 23 or later. This
conflicts with the effective target used by conversion on modern Android.
The restriction is documented in Android
[Bionic changes for NDK developers](https://android.googlesource.com/platform/bionic/+/master/android-changes-for-ndk-developers.md).
This is an independent native-load blocker if startup reaches that load; touch
and receipt callbacks cannot repair it. Removing the dynamic tag would not
remove the relocations and is not a fix.

The library also has no section headers and an out-of-file section-table offset.
That is further evidence of an unusual/packed binary, not by itself proof of
malware, tampering, or the exact observed device failure. Its native integer
helpers participate in initialization, so simply deleting the wrapper call would
change required program behavior.

### Original DEX fails structural verification

Android SDK build-tools 36.0.0 `dexdump -c classes.dex` rejects the supplied,
unmodified file with `out-of-order static field indexes 70 and 35`. Read-only
inspection additionally finds dummy `MainActivitx*` class initializers containing
reserved opcode `0x3e` and other unusual metadata. This is consistent with
legacy runtime protection or nonstandard/malformed bytecode; provenance is not
established by these findings.

A scratch-only dexlib2 serialization round trip does not make it valid: the
verifier next rejects a bad method name. Ordinary rewriting cannot be claimed
to repair this. The scratch output is not shipped and no protected code was
removed or substituted.

## Additional risks, not confirmed crash causes

- Disassembly of `libjniproxy.so`'s `MainActivity_init` confirms a `dlopen` using
  `/data/data/com.square_enix.android_OUYA.FFIII/lib/libff3.so`. That relies on an
  old native-library path. Whether the necessary compatibility symlink exists
  on the owner's phone is unverified. The function returns -1 if loading fails.
- The runtime needs ARM32 support, GLES1 and the original native dependencies.
  The supplied archive cannot establish the phone's actual ABI/runtime state.
- OUYA services, the game's own purchase flow and account setup need separate
  work after startup. Generic marker matches are not a working implementation.
- Missing assets prevent full initialization, gameplay or entitlement validation
  with this archive. The included `.java.bak` is reference material, not proof
  of the compiled build's complete behavior.

A filtered device crash log is needed to identify the first failure on the
owner's exact installation. Static evidence establishes incompatibilities but
does not identify the precise exception or signal shown by Samsung's dialog.

## PocketOuya changes

- Native inspection now detects `DT_TEXTREL` and `DF_TEXTREL` through ELF program
  headers without executing libraries or relying on section headers. It reports
  the filenames and API-23 loading restriction in Engine test diagnostics and
  in the conversion report, including normal conversions with native warnings.
  Parsing has per-library, total-byte and count bounds. Other incompatibilities
  and libraries skipped by those limits can remain; no clean-scan claim is made.
- Optional touch/purchase conversion now skips all compatibility changes when
  any input DEX contains an unknown/reserved instruction. Original DEX bytes are
  preserved and no helper is appended. The report states that the runtime is
  unsupported, instead of announcing usable hooks for bytecode the rewriter
  cannot safely treat as an ordinary DEX.
- No OS verification/linker checks, target restrictions, billing checks or native
  protections are disabled. Existing working-game input paths are unchanged.

## Validation and remaining work

Synthetic ELF32/ELF64 tests cover both text-relocation declarations, nonmatching
flags, truncated inputs and invalid bounds. A protected-bytecode fixture checks
that neither touch nor purchase hooks are emitted. Existing protocol, DEX,
state, activity-alias and multidex tests pass. Android app Java compilation was
checked separately; CI checks Gradle packaging.

The supplied FFIII archive was rebuilt in both normal and requested compatibility
modes. Both show the native warning and keep the original DEX, resources and
native libraries byte-for-byte. Requested compatibility is skipped and no extra
DEX is added. This is conversion validation, **not a successful game launch**.

Getting this exact build running requires a compatible original runtime/build
or substantial reconstruction/recompilation of its legacy wrapper and bytecode.
This investigation has not demonstrated a working reconstruction. A clean
compatible build, if available, and a device crash trace are the next useful
inputs. No FFIII unlock or launch success is claimed.
