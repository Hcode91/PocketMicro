# Explicit Unity embedded-runtime experiment

Add game → **Experimental Unity embedded-runtime repair** → **Inspect and prepare
test**. Select the complete original APK, not an extracted folder or multipart
archive. The conversion report precedes the separate Install action. Existing
game signing and install behavior are retained.

This option reproduces the locally tested Monocle experiment using structural
evidence. It can match other APKs with the same restricted structure. No package
IDs, build hashes or catalog offsets select it. The original APK stays intact.
Ordinary startup repairs and the APK doctor do not enable this option. Touch and
offline purchase hooks are off in this isolated experiment.

Both packaged ARM32 libraries must declare matching proxy SONAMEs and have the
read-only detector's fully recognized, ARM-only finalizer form. Both real payloads
must exist under assets/libs/armeabi-v7a with matching normal SONAMEs. Mono must
already have supported writable relocations and no TEXTREL declaration. Unity
must have exactly the recognized ARM finalizer family with no Thumb thunk. The
existing transformation algorithm receives only independently derived addresses.
Its output must have supported writable loader writes, no TEXTREL declaration,
and byte-identical program headers. Both promotions are committed together;
missing, uncertain, malformed or unsupported cases leave all native files intact.

The detector itself remains read-only. The user explicitly authorized this
separate experimental transformation entry point after the earlier read-only
milestone. No automatic/default selection was added; the six uncertain Thumb
cases remain excluded. All 17 legacy profiles and four frozen native algorithm/
catalog sources are unchanged.

**Limits:** local pattern recognition and relocation checks do not prove complete
reference safety, JNI compatibility, Android loading or gameplay. Proxy bypass
may expose further runtime differences. Reports and the confirmation UI disclose
these limits. No successful phone launch has been observed for this experiment.

## Verification

- Full host compatibility/input/purchase/doctor suites pass. Generated detector
  cases remain TP12/TN31/FP0/FN0; their detection logic is unchanged.
- New fixtures vary function/data addresses, proxy/payload SONAMEs and include
  missing/malformed payloads and an independently typed Thumb near-match. They
  check transactionality, DEX/assets/input preservation, disabled-path isolation
  and repeated conversion rejection. All pass.
- On the complete supplied Monocle APK, the app option reproduces the previously
  emulated Unity output exactly, preserves all 29 assets and the original DEX,
  and changes only the manifest and the two root runtime libraries. Original
  input bytes are preserved. The previous experiment compared 9 original/repaired
  finalizer pairs at 3 biases (18 emulator executions), with the same call
  argument, registers, SP, LR and CPSR and original RX/RW protections.
- Android-core Java compilation passes. Device installation and launch remain
  untested. Existing native replay evidence is 12 available profiles verified,
  5 unavailable/skipped; this change does not claim a new full 17-fixture replay.

No proprietary native libraries, assemblies, game assets or game APK are committed.
