# Unity 4.3.4f1 NativeActivity loading repair

The owner supplied a crash for `com.burningthumb.mobile.AD2442`: Android's
`NativeActivity.onCreate` rejected `libmain.so` because it declares text
relocations. The supplied original manifest targets API 16; conversion raises
it to API 23, at which Android enforces this restriction. A touch lifecycle
wrapper appears in the trace, but failure occurs in the original NativeActivity
library load. The trace alone does not establish a new conversion regression.

Inspection of the four supplied ARM32 libraries:

| Library | Bytes | Non-writable relocation targets | Action |
| --- | ---: | --- | --- |
| libmain.so | 29,832 | RELATIVE at 0x2204, ABS32 at 0x221c | Repair initializer and destructor-registration thunk |
| libmono.so | 3,925,576 | RELATIVE at 0x15b14 | Repair initializer |
| libunity.so | 10,720,300 | RELATIVE at 0x6bb3c, ABS32 at 0x6bb54 | Repair initializer and destructor-registration thunk |
| lib-ouya-ndk.so | 13,432 | None; no TEXTREL flag | Preserve unchanged |

Unity contains version `4.3.4f1 (e444f76e01cd)`. The affected patterns match the
existing `ArmRuntimePicRepair` routine. This change adds exact catalog profiles,
not another repair algorithm or an engine-wide blind patch.

| Library | Source SHA-256 | Repaired SHA-256 |
| --- | --- | --- |
| libmain.so | `df20f3907015929b5dc1f8d797aaa8333ae6b92ee1c087d384a82f9569fa8dfb` | `bc152baf4f9d17ec8f47040b0988bc3a1b2b914d4b93c45f723cdac6f00e6326` |
| libmono.so | `3ff6f788b6c25c1fc7e74694b56a00c5fdc873b1310a524055c200aeb41c47e1` | `580c868118528120640588c4f993cf25c1f16b7a77882262c7fd6092be06d9bd` |
| libunity.so | `9f1bd21992ebdb845947a9595365ae919e0daacfd019ec6abdb1863fbe284157` | `2d11f8117a4db369788e3185d05185b39dff9cfdd08aa06c13ed47501be6633e` |

The ARM initializer now computes its address relative to PC, keeping its original
tail branch. Parameters (initializer, target) are main `(0x21f8,0x8000)`, Mono
`(0x15b08,0x3bd2b0)`, and Unity `(0x6bb30,0xa2e000)`. Main's Thumb thunk at
`0x2208` tail-calls the original `__cxa_atexit` PLT at `0x20b0`; Unity's thunk at
`0x6bb40` uses PLT `0x6b140`. Relocation tables/counts and thunk unwind records
are updated. The routine verifies that all remaining loader relocations target
writable segments before removing DT_TEXTREL. Original ELF load mappings,
permission flags, names, dependencies and unrelated runtime code stay intact.
No new writable/executable segment or Android security-policy change is used.

Matching works by exact installed library bytes in `lib/armeabi-v7a/` or
`lib/armeabi/`, independently of package name. These profiles repair direct
runtimes; they do not bypass unidentified proxy loaders. Other builds remain
unchanged. All three files are covered so the first repaired library does not
simply expose the same loading restriction in another supplied component.

## Validation and phone test

Using the supplied manifest and four native files in a local packaging fixture:
three files repaired, exact output hashes, no remaining TEXTREL declarations,
OUYA extension and unrelated asset marker unchanged, original package retained,
no helper DEX in repair-only mode, and a second inspection is idempotent. This
fixture is not the complete game APK; no game DEX or assets were supplied.

Unicorn compares original/repaired initializer arguments and branch behavior at
three load biases under the original RX/RW mappings. Both thunks are checked
with ARM/Thumb callers and targets, matching call arguments, restored caller
stack/callee-saved registers and correct return mode. Reproduction with matching
user-owned original/converted APKs:

```sh
python3 scripts/test-ad2442-runtime-pic.py original.apk converted.apk
```

These checks do not establish successful Android startup, controls, purchases
or gameplay. Install the new PocketMicro build, reconvert the **original complete
APK** using **Startup repairs** (optionally with touch), and verify the report
shows **three Unity 4.3.4f1 libraries repaired**. Install the converted
update and use normal Play. A fresh global log is needed if another failure
follows. No game/native binaries are bundled in PocketMicro or committed.
