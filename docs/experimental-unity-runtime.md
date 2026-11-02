# Explicit Unity embedded-runtime experiment

Add game → **Experimental Unity embedded-runtime repair** → **Inspect and prepare
test**. Select the complete original APK, not an extracted folder or multipart
archive. The conversion report precedes the separate Install action. Existing
game signing and install behavior are retained.

This option reproduces the locally tested Monocle experiment using structural
evidence. It can match other APKs with the same restricted structure. No package
IDs, build hashes or catalog offsets select it. The original APK stays intact.
Ordinary startup repairs and the APK doctor do not enable this option. Every app conversion,
including this experiment, now attempts the existing shared touch-input hooks
automatically. Purchases remain off in this experiment. Low-level native-only
programmatic overloads keep their original behavior. Touch hooks compose after
the CPU guard and the report names the input route or explains why it was
skipped; phone input is unverified.

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
  and initially changed only the manifest and the two root runtime libraries (the CPU follow-up below additionally guards the supported getter). Original
  input bytes are preserved. The previous experiment compared 9 original/repaired
  finalizer pairs at 3 biases (18 emulator executions), with the same call
  argument, registers, SP, LR and CPSR and original RX/RW protections.
- Android-core Java compilation passes. Device installation and launch remain
  untested. Existing native replay evidence is 12 available profiles verified,
  5 unavailable/skipped; this change does not claim a new full 17-fixture replay.

No proprietary native libraries, assemblies, game assets or game APK are committed.

## Null CPU-description follow-up

The phone test progressed past text-relocation loading, then crashed in libc
strlen with a null argument. Its Unity frames map to a string constructor whose
caller obtains getCPUType through JNI. The supplied DEX delegates that getter to
an old CPU-info provider. The provider caches the nullable Processor field from
/proc/cpuinfo. A missing field is a plausible source; the phone file itself has
not been captured. No broad libc or native string patch is applied.

The explicit experiment now also attempts unity.cpu-description.non-null:
recognize the shared Unity getter's four-instruction field/delegate/result/return
contract, retain that code and add a null-only Unknown CPU label before return.
Register/type/signature checks, duplicate/reserved-instruction rejection and
serialized guard verification bound applicability. Real descriptions, empty
strings, provider exceptions, CPU capability flags and purchase handling are
unchanged. Different getter implementations remain unsupported. No helper DEX.

This supersedes the original DEX-preservation statement for eligible getters:
all DEX content outside getCPUType remains unchanged, checked through canonical
serialization on the supplied DEX. All 29 game assets and the prior native output
remain identical. Host suites plus null/non-null/empty/Unicode/register-layout/
idempotence/negative fixtures pass. The earlier 43 detector cases remain passing.
Phone startup after this guard remains untested. CI is triggered on push; per the
owner's instruction, do not wait for APK build completion before handing over its
GitHub link. Reconvert the complete original APK using the same explicit option.

## Touch composition

Generated fixtures verify the ARM native promotion, non-null CPU getter and
OUYA native touch lifecycle hooks together, with one helper DEX and unchanged
purchase code, assets and source APK. A missing requested bridge is rejected.
The native-only overload still preserves unrelated DEX. Full supplied Monocle
conversion also preserves all 29 assets and the prior emulated native output,
retains the CPU getter guard and adds the existing touch bridge/lifecycle calls.
This is bytecode/ZIP verification, not an Android touch-response test.
