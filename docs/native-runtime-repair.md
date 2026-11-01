# Game-independent native runtime repair (experimental)

**Startup repairs — Unity / Marmalade / AIR / Frima** can be selected when importing
any game's original complete APK. The converter does not gate this option on a
title/package name. It matches native files against a catalog of inspected
fingerprints, so the same verified repair can be tried in another game with the
same runtime bytes. A Unity version string alone does not establish a match.

The catalog contains the Mono and Unity 4.1.5f1 payloads inspected in
Muffin Knight, plus Vector 1.0.7 Marmalade and three inspected extensions. The
Vector profile also guards the null native-buffer release identified in the
owner's subsequent JNI crash.
See [the Vector investigation](vector-runtime-repair.md) for that repair. The code repair, source/output hashes and instruction validation
are documented in [that investigation](muffin-knight-runtime-repair.md). Each engine repair requires its own validated binary layout.

The catalog also includes the inspected Frima Space Shooter 1.1.3 initializer,
Adobe AIR Core (17 relative code literals / 18 loads), and AIR libysshared (two
relative literals plus a symbolic literal moved to a writable relocation cell).
See [AIR/Frima repair evidence](air-frima-runtime-repairs.md). Per-file read and
startup-inspection limits are now 24 MiB to include the inspected 18 MiB Core;
overall repair/inspection budgets remain 64/32 MiB.

## Matching and behavior

For cataloged library names (Mono/Unity, Vector and the three supported Marmalade
extensions) under existing `lib/armeabi-v7a/` or
`lib/armeabi/` directories:

- A matching installed original runtime is repaired directly. Embedded assets
  and proxy files are not required for this layout.
- A matching proxy promotes its embedded payload only when that payload matches
  a catalog profile. If it does not match or is unavailable, a separate exact
  loader-only profile can repair the known small loader itself, preserving the
  original embedded bytes and reporting the unvalidated payload. This is a
  partial repair, not promotion or validation of an unknown runtime. These particular proxies refer to
  `assets/libs/armeabi-v7a/<library>`. Unknown loaders are not bypassed merely
  because a matching asset happens to exist.
- Each component/ABI is checked independently, preserving the game's original
  pairing. The converter never substitutes a different game's runtime version.
  The same repaired byte array may be reused for identical matches in two
  existing ABI directories; no new ABI directory is created.
- The previous Vector PIC-only output is recognized and upgraded with the JNI
  surface guard. Current already-repaired installed payloads are left unchanged.
  A known proxy around an already-repaired payload can promote that payload.
- Unknown hashes, missing payloads, size limits and unsupported ABIs are listed
  and left unchanged. Unknown hashes are included to help future investigations.
  No guessed offsets, blanket flag removal or unverified version-string matching
  are used. Unexpected patch/output validation errors abort the conversion.

The report begins with changed/unsupported counts. With no matches, no native
repair is applied; normal manifest/signing conversion still proceeds. With a
partial match, other native loading failures may remain. Existing native warning
inspection runs against the replacement bytes so it reports the actual candidate
APK rather than the old proxy libraries.

Inspection has a 12 MiB per-candidate and 64 MiB aggregate input budget. Unsupported
ARM64/x86 libraries are not passed to an ARM32 patch routine. The catalog is
structured to distinguish multiple verified versions, including versions sharing
a proxy fingerprint, using the embedded payload's own fingerprint.

## What this does not establish

A matching patch means the converter recognizes and validates those bytes. It
does not prove the whole game works on this phone. `libjni.so`, other native
plugins, permissions, graphics, assets and discontinued OUYA services can still
fail. No successful game launch is inferred from catalog matching or offline
instruction tests.

The repair-only import mode changes no DEX. **Startup repairs + touch — Unity / Marmalade / AIR / Frima** also runs the optional DEX touch conversion and appends the bridge
when supported; both results are included in the report. Neither mode changes
purchases. Native repair itself changes no game assets, unrelated native
libraries, Android security settings, native
segment permission flags or target-SDK policy. The Vector repair appends an RX
code segment and extends its unwind table into existing padding; see its report
for program-header and mapping details. No vendor runtime files are bundled.
Use ordinary **Play** and **Capture crash log** for the next failure, if any.

## Validation

Synthetic tests cover direct and proxy layouts, ARM ABI aliases, independent
partial matches, unknown loaders/payloads, absent payloads, unsupported ABIs,
already-repaired payloads, multiple versions sharing a proxy, and output-hash
failure. Existing ARM/Thumb relocation/unwind/permission checks remain in place.
Actual supplied runtime tests compare both direct and embedded layouts against
the prior validated output hashes without relying on a Muffin package identity.
Those bytes retain the earlier ARM instruction-emulation evidence; additional
games still require phone testing.
