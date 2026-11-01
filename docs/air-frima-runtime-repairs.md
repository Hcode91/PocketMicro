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

## Abbigale is blocked on its real embedded runtimes

Both supplied Abbigale ZIPs contain identical small loader proxies:
lib/armeabi-v7a/libmono.so (92,744 bytes) and libunity.so (47,688 bytes), plus the
OUYA extension. Their hashes match the already-supported proxy profiles. The
proxies refer to real native payloads in:

- assets/libs/armeabi-v7a/libmono.so
- assets/libs/armeabi-v7a/libunity.so

Those embedded files are absent from both ZIPs. The actual failing Mono build
cannot be identified from this loader alone: multiple payload versions can share
the same proxy. The catalog explicitly reports the missing payloads and does not
patch a proxy with offsets from a guessed runtime. The owner needs to supply
these two **assets/libs** files, or use a complete original APK and provide its
conversion report (including unrecognized payload hashes). The ordinary lib/
folder is insufficient. No claim of an implemented Abbigale repair is made.
