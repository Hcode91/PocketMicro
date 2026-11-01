# Space Shooter / Adobe AIR text-relocation candidates and Abbigale inputs

The owner's phone logs show separate loader failures: Frima Space Shooter's
libSpaceShooter.so, Unity Abbigale's libmono.so and AIR Zombie Toaster's libCore.so
all declare text relocations. The AIR missing-nativeSurfaceCreated exception is
secondary to Core's failed load. These are different native builds, so a single
flag removal or runtime substitution is not used.

Select **Startup repairs — Unity / Marmalade / AIR / Frima**, or the corresponding
**Startup repairs + touch** choice, when importing an **original complete APK**.
Read matched/unsupported/missing-payload entries before installation, then use
ordinary Play. A matching patch validates bytes, not a successful game launch.
All supplied game archives and native binaries remain outside Git.

## Frima Space Shooter 1.1.3

The supplied ZIP has one library, lib/armeabi/libSpaceShooter.so (2,022,368 bytes).
It contains one non-writable relocation: R_ARM_RELATIVE at 0x7421c. The compiler
initializer at 0x74210 loads __dso_handle 0x1ed620 and tail-branches to its existing
PLT entry. The prior initializer PIC routine replaces the absolute load with a
PC-relative distance/add, preserving its branch, registers and flags. Remove only
that relocation, update table/count/section sizes and validate every remaining
loader write is writable before removing DT_TEXTREL. Original segments/permissions
and all other library code are retained.

| Source SHA-256 | Repaired SHA-256 |
| --- | --- |
| `92a31b66bf1127993764a29481866c6dfa0b02889f1d850ceca06a74b3f3fe33` | `f242435f27d97c46cbe4e26d5b6baf2ce41e6ef72bca1985425af3699f77870e` |

Unicorn checks the original/repaired initializer at three load biases with the
same register/flag/branch result. Actual archive repair-only and repair+touch
conversion passes; output hashes, unrelated payload preservation and repeated
conversion idempotence are verified. Phone launch, graphics and input remain
unverified; this engine uses the generic Android gamepad/keyboard touch route.

## Inspected Adobe AIR build

The earlier A Ball Slapping Good Time! archive supplies libCore.so and
libysshared.so. Zombie Toaster's native files have not been supplied. The registry
is package-independent: the same bytes in another game can match, but the AIR
class name/version string alone is not sufficient.

Core has 17 R_ARM_RELATIVE code literals used by 18 unconditional ARM LDR sites.
The patch redirects each to a small RX stub computing the same relocated value
with PC-relative arithmetic and returning to the original next instruction. Only
the original destination register changes; flags and stack are retained. No
writable executable segment is added, and original segment/GNU_STACK/RELRO flags
are preserved. Existing literals remain as inactive source data.

Core's unwind table moves to the appended mapped region. Every original PREL31
function start and extab pointer is rebased to preserve its decoded meaning;
inline recipes remain unchanged. New stub entries use the original site's unwind
recipe, plus a terminal CANTUNWIND entry. PT_ARM_EXIDX and the section metadata
point to the new table; an explicit resident PT_PHDR describes the new headers.
Unknown load/address-taking forms, unexpected counts, SP/PC destinations, range
violations and unhandled code relocations are rejected.

libysshared has two relative code literals and one R_ARM_ABS32 symbolic literal.
Relative loads use the same PIC calculation. The symbolic relocation is retargeted
to a new RW data cell, retaining its original symbol index and addend. Its load
stub reads that cell, preserving null/unavailable weak-symbol behavior as well as
resolved symbols. The library originally has no unwind table; none is invented.
Remaining loader writes must target RW before DT_TEXTREL is removed. This
repairs actual address handling rather than suppressing Android linker checks.

| Library | Source SHA-256 | Repaired SHA-256 |
| --- | --- | --- |
| libCore.so | `6cc57698961c0dab6bf2d32d443a1c07c5f6befcd552dc25738893f77104c22c` | `b6f2b60be0bd662bf62a1afb4f23deb7d33e715c20fc04b66d322927acd20813` |
| libysshared.so | `b943b7e7631b4ef047f2eeeef6c50ff77dd6756c66f9eb7954a3e846f387b69c` | `92ee9b5a46bf4bf1092bfc73cc747d8cc102312fdad3631d74bc2812765b96a2` |

Unicorn checks all 18 + 3 affected loads at three biases and null/resolved symbolic
values, comparing every general register, flags and next PC. Independent metadata
checks verify all original decoded unwind recipes, corresponding stub recipes,
unchanged original mappings, no new RWX load and only writable remaining loader
relocations. Synthetic fixtures exercise symbolic cells, absent/present unwind,
PREL31 rebasing and rejection paths. Actual AIR archive conversion changes the two
known libraries and preserves DEX/resources/other libraries; output hashes and
idempotence pass. These are isolated instruction/packaging tests, not ART or game
execution. The source has old symbol metadata that standard disassemblers may
reject; inspection used a scratch-only symbol metadata copy, not a runtime fix.

AIR Core also imports old private libstagefright/libui/libutils APIs and Android
C++ symbols. Those may still be unavailable or incompatible after the TEXTREL
repair. No stubs fabricate their success, no libraries from another device are
bundled and no target/linker security policy is lowered. AIR graphics, extensions,
Java/native API compatibility and touch handling remain phone-test questions.

To reproduce isolated AIR checks with user-owned library files:

```sh
python3 -m pip install unicorn==2.1.4
python3 scripts/test-arm-literal-pic.py original.so repaired.so
```

## Abbigale loader-only repair and embedded runtime limits

Both supplied Abbigale ZIPs contain identical small loader proxies:
lib/armeabi-v7a/libmono.so (92,744 bytes) and libunity.so (47,688 bytes), plus the
OUYA extension. Their hashes match the already-supported proxy profiles. The
proxies refer to real native payloads in:

- assets/libs/armeabi-v7a/libmono.so
- assets/libs/armeabi-v7a/libunity.so

Those embedded files are absent from all three supplied ZIPs. The latest
armeabi-v7a.zip has the same known small loaders. Their own text relocations can
be repaired independently, even though that cannot validate the unseen payloads.

The catalog now prefers promotion of a recognized real embedded runtime. If the
payload is absent, over the inspection limit, or unrecognized, it can instead
apply an exact-fingerprint **loader-only** repair to the installed small loader.
The embedded assets stay unchanged. Missing/unknown payloads remain reported as
unsupported components; they are not relabelled as fixed. Unknown loader bytes
are still left unchanged. A recognized payload's failed patch validation aborts
rather than silently falling back. Already-repaired loaders retain the explicit
embedded-runtime warning in their label.

Both loaders use the existing validated ARM initializer and Thumb __cxa_atexit
repair. The initializer computes __dso_handle with PC-relative arithmetic; the
Thumb destructor-registration thunk tail-calls the original writable-GOT PLT
entry, preserving the caller frame/ARM-Thumb return mode. Its unwind recipe is
updated for the tail call. Code relocations and counts are removed only after
all remaining writes are validated RW. Original segments/permissions, filenames,
SONAMEs and original runtime loading/forwarding logic are retained.

| Library | Source SHA-256 | Loader-only output SHA-256 |
| --- | --- | --- |
| libmono.so | `a280a957e102a2b0e075f339ddff6a648a27af8d8ecfb2a994a43905d38cf15c` | `857d1f4e4f107dbcc429dcddb5a3bd6fa2f10fdbb42b953da141d4ac3d8d4e06` |
| libunity.so | `aee89345929061f1e52fe72a7874796a8d3cdba8a663bf370a14cea896b05961` | `cdbe592192dc48608ca8d908d4879f120ee33eb0cd2bfa128fd7169baa598ab5` |

Unicorn checks initializer/call arguments, ARM/Thumb callers and targets, stack
and callee-saved state at three biases. Original RX/RW mappings are retained and
remaining loader writes target RW. Actual Abbigale archive conversion repairs
both loaders, preserves DEX/resources/the OUYA extension, adds no helper DEX in
repair-only mode and recognizes repeated input as already repaired. Synthetic
catalog tests check preferred real-runtime promotion, missing/unknown payload
fallback, preserved assets and loader-output idempotence. Full Android Java and
existing host suites pass. Reproduce the isolated checks with:

```sh
python3 scripts/test-unity-loader-pic.py original.apk converted.apk
```

Use the original complete APK on the phone with either startup repair option.
Loader-only results are partial: another error can follow while loading the
embedded runtime, which may itself have text relocations. Capture the new global
log to identify the next stage. The original payload files are needed if that
stage requires further repair, but not to try this independent loader fix. No
successful Abbigale launch is claimed and no donor runtime is substituted.

### Follow-up device crash: 2026-11-01 19:22

This was the assessment before the full payloads were supplied. The next section
records the subsequent matching and candidate repair.

The owner's new trace reports native SIGSEGV/SEGV_ACCERR, a stack-overflow hint,
and 512 frames. Offsets `0x7c3248`, `0x7d84e8`, `0x7d8784`, `0x7d8868` repeat
in the anonymous mapping based at `0xdb8e0000`; the top frame is `0x7c322c`.
This strongly suggests recursive native calls exhausting the main-thread stack.
The visible failure differs from the previous Android linker TEXTREL rejection.
It does not prove the entire runtime repair is correct or establish the loop's
cause. No library name, symbols or complete memory map identify these functions.

Reinspection of all three supplied archives confirms that none includes the
embedded `assets/libs/armeabi-v7a/libmono.so` or `libunity.so`. The small supplied
loader ELF load ranges end at virtual offsets `0x1fc84` and `0x14988`; the new
anonymous-frame offsets cannot be directly resolved against those files. Do not
attribute the recursion to a named runtime function or patch an arbitrary branch
from this trace alone. Increasing the stack size would not resolve the cause of
an unbounded call cycle.

Next evidence: extract **only** those two files from the original APK's
`assets/libs/armeabi-v7a/` directory (not its top-level `lib/` directory), plus the
full global log from that launch. Separate ZIPs avoid uploading all game assets.
A complete tombstone with memory maps, if available, can identify the anonymous
mapping. No additional Abbigale binary change is made for this unsymbolized
crash; the loader-only repair remains explicitly partial.

### Full Unity 4.1.1f4 payloads supplied: normal-loader candidate

The owner subsequently supplied the real embedded files: Mono 3,917,412 bytes
and Unity 9,204,864 bytes. Unity contains version `4.1.1f4 (89a83a44d45a)`.
All four repeating crash offsets match instruction boundaries/calls in this
Unity payload. The actual cycle constructs a C++ string, requests its backing
storage, rejects an excessive size and constructs the size-error string again.
The error string at `0x80a4a0` is `basic_string::_S_create`.

The call at `0x7d8854` reaches the `strlen` PLT at `0x63350`, whose GOT slot is
`0x8bbd74`. The exception message's runtime address (`0xdc0ea4a0`) equals the
anonymous base plus that string offset. The captured `r4/r5 = 0xe63320e5` is
consistent with a function address being used as a length, rather than the
message's 22-byte length. An old custom symbol resolver calling an IFUNC resolver
as if it were `strlen` is a plausible cause. The phone's libc and GOT contents
were not captured, so **that cause is not established**.

An instruction test of these actual functions reproduces the exact four-frame
cycle when a `strlen` stand-in returns `0xe63320e5`. Returning the correct length
instead reaches normal allocation without the cycle. This is a controlled
mechanism test, not a measurement of the phone's resolver.

Added exact-source/output catalog entries using the existing PC-relative repair:

| Payload | Source SHA-256 | Repaired SHA-256 |
| --- | --- | --- |
| Mono | `9816e13ed0ce4d690c9c99ca5187168e08dfacfb4387002becf072153868970c` | `e888ab00e407ab46d67d6ab32e7573ca0f571fcc9c3fe57d214c336e7e53498e` |
| Unity | `2442a0cffee5f77854895316151573bceabcb2b6278e472aff74b3236d0d3c2a` | `15b92db63f6869bd1f34f13db7a12a0e89bea9765f87d7f08b114cf490482746` |

Mono has one text relocation: its ARM initializer at `0x15a60` references
`0x3bc2d0`. Unity has two: the initializer at `0x64910` references `0x8bd000`,
and the Thumb destructor thunk at `0x64920` has an ABS32 function literal.
Its repaired tail call uses the original `__cxa_atexit` PLT at `0x64028`.
Relocation tables/counts and Thumb unwind metadata are updated; remaining writes
must target writable segments before DT_TEXTREL is removed. No permission flags
or original segment mappings are changed.

When the known proxies and these exact payloads occur together in an original
APK, the converter promotes the repaired payloads to the installed `lib/` paths.
Android then loads the real libraries directly, bypassing the legacy embedded
loader's resolution path. SONAMEs, dependencies, symbol version tables and all
other runtime code remain intact. Embedded asset copies stay byte-identical.
No `strlen` replacement, exception suppression, larger stack or donor runtime is
introduced. Other payload builds remain independently fingerprinted/unsupported.

Local verification: two-file promotion; unrelated DEX/resources/OUYA extension
preserved; no helper DEX in repair-only mode; output hashes and idempotence;
initializer/atexit instruction behavior, ARM/Thumb caller/target combinations at
three load biases under original RX/RW mappings. Optional reproduction:

```sh
python3 scripts/test-abbigale-runtime-pic.py original.apk converted.apk
```

The input must include the matching original payloads under `assets/libs/`.
This candidate has **not** been validated by Android gameplay. To test, install
the new PocketOuya build, reconvert the original complete Abbigale APK with
**Startup repairs** (optionally with touch), and verify the report says two real
Unity 4.1.1f4 runtimes were repaired/promoted, rather than loader-only repair.
Install the converted update and use normal Play. Export a fresh global log if
another failure follows. Server integration remains removed.


### Owner validation and optional offline purchases

The owner subsequently confirmed that Abbigale launches with the real-runtime
repair in commit `850753c`. This establishes launch compatibility for that exact
build on the owner's phone, not every Unity game or the hypothesized resolver
cause. Runtime output hashes remain unchanged when the optional offline attempt is
selected. The current offline SDK matcher does not match the supplied Abbigale
DEX; its purchase checks are left unchanged (zero receipt hooks), alongside the
working native repair and touch hooks. The new global default only preselects
the conversion checkbox and does not expand SDK coverage. A separate purchase
adapter would be needed for this game.
