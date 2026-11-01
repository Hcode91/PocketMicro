# Baseline evidence and read-only structural prototype

Branch: `feature/compat-evidence-detector`. Baseline: `0555487`.

## Result and limits

Phase 1's native baseline/evidence work is implemented. Phase 2 demonstrates
hash-independent, read-only recognition of a deliberately restricted contract.
It does **not** demonstrate full acceptance of all 13 known family members:
seven are accepted and six remain `UNKNOWN`. The broader family milestone must
not be called fully complete or connected to transformations on this evidence.

| Evaluation | Executed | Result |
| --- | ---: | --- |
| Existing native profile behavior | 17/17 | 17 expected output hashes, immutable inputs, catalog selection and idempotence verified |
| Known initializer/atexit family | 13/13 | 7 supported; 6 unknown due to missing Thumb instruction-mode evidence |
| Derived parameters in known family | 13/13 | Match the independent regression oracle; partial parameters do not override an unknown result |
| Other native profiles | 4/4 | No full matches: 3 unsupported and 1 unknown; zero observed false positives |
| Generated positive layouts | 12/12 | Supported, including differently located functions, target data and PLTs, nonadjacent thunks, typed and mapping-symbol Thumb evidence |
| Near matches / malformed / truncated / unsupported / budget cases | 31/31 | Rejected or explicitly uncertain/skipped; zero observed false positives |
| Missing-fixture control | 17 unavailable by deliberate empty index | All SKIPPED, zero verified; audit command fails |

Real-family recognition confusion counts: **TP 7, FN 6** (including conservative
unknown), **FP 0, TN 4** across all 17 native cases. Generated-contract cases:
**TP 12, TN 31, FP 0, FN 0**. These are separate test sets with different ground
truth definitions. Do not combine them into a claimed universal success rate.
Zero observed false positives in a finite corpus is not a general guarantee.

All real fixtures were available **locally** in this session. They are not
committed or shipped. Public CI records their replay as SKIPPED/unavailable;
its generated-case pass does not mean it replayed the real libraries.

## What changed

- `CompatibilityEvidence`: immutable result/finding/candidate objects with
  `SUPPORTED`, `UNSUPPORTED`, `MALFORMED`, `SKIPPED`, `UNKNOWN`, `VERIFIED`, reason
  codes, scope, addresses and evidence. Verification is always scoped.
- `ArmFinalizerDetector`: standalone bounded ELF32 ARM reader and restricted
  recognizer. It imports neither the catalog nor any transformation class. It
  can compile with only the evidence DTO and Java APIs. No filename, package,
  input hash or build-specific address selects a match.
- A frozen 17-row native matrix and source checksums for all four existing
  native transformation/catalog files.
- Generated structural cases, a private-fixture audit runner, machine-readable
  executed evidence and a dedicated CI workflow.

The existing exact-build catalog, repair algorithms, purchase validation, input,
GameMaker runtime, signing and UI flows are unchanged. No fingerprints were
added or removed. The new detector is not called by the doctor, rebuilder or
automatic repair path. It never returns modified ELF bytes; every result has
`transformationAuthorized=false`. The baseline audit separately executes existing
transformers to verify their behavior; that is not part of detection.

Legacy UI warning strings and older scanner booleans are intentionally not
replaced in this milestone. The new structured results are available through
the read-only API and evidence reports. This does not claim that every existing
diagnostic ambiguity has already been migrated.

## Common binary structure actually found

The historical repair API calls its first address an initializer. In these 13
files it is a finalizer root in `DT_FINI_ARRAY`, with a RELATIVE relocation for
the array entry. Its ARM sequence loads a relocated address literal and tail
branches into a PLT entry bound to `__cxa_finalize`. The literal points into a
writable load mapping. This supplies rooted ARM instruction boundaries rather
than requiring an executable-segment byte scan.

Seven files have only that non-writable relocation. Six have a second ABS32
relocation for `__cxa_atexit` in a Thumb-shaped thunk. The thunk's two literal
loads, shared target, eight instruction halfwords, return, exidx extent, stack
recipe and relocation-bound PLT can be derived without catalog parameters.
The exidx entry identifies a boundary and recipe but does not independently
identify the instruction set for the six stripped files.

The detector derives the finalizer from the array, its target from the RELATIVE
literal, an optional thunk from symbol-backed relocation structure, and PLT/GOT
addresses by decoding the restricted ARM PLT form and checking jump relocation
bindings. Fixed numbers inside the recognizer describe ELF fields, instruction
lengths/encodings and supported unwind semantics; none is a fixture's address.

### Required evidence and rejection behavior

- ELF32, little endian, EM_ARM, ET_DYN, bounded valid headers/tables/load mappings.
- Consistent section/dynamic symbol/string tables and executable PLT mapping.
- Metadata-rooted ARM entry; expected instructions and unconditional tail branch
  ending before a relocated data literal; no contradictory mapping/root evidence.
- Writable target, symbol-bound finalize PLT and coverage of every non-writable
  relocation by the supported local family. Other code relocation types reject.
- For a thunk: exact bounded local form, matching literals, ABS32 binding,
  compatible unwind recipe/extent and uniquely identified atexit PLT.
- Independent Thumb evidence: a defined Thumb `STT_FUNC` entry with an appropriate
  extent, or consistent executable `$t` / literal `$d` mapping. Conflicting
  metadata, overlapping roots and unsupported forms reject or remain unknown.
- Invalid/truncated data is MALFORMED. Unsupported CPU/forms are UNSUPPORTED.
  Missing required metadata is UNKNOWN. Inspection limits are SKIPPED.

SUPPORTED means **local read-only pattern recognition**, not a complete reference
analysis or a certificate that rewriting is safe. The detector does not establish
absence of all external references to literal storage, whole-program behavior or
device compatibility. Those remain transformation-stage obligations.

## Exact profile matrix

The machine-readable oracle is `tests/native-profile-matrix.json`. Expected
hashes and addresses live only in that regression data and the unchanged legacy
catalog, not in the detector.

| ID | Existing profile | Baseline | Detector |
| --- | --- | --- | --- |
| native-01 | Unity 4.3.4f1 libmain | VERIFIED | UNKNOWN: Thumb mode unproven |
| native-02 | Unity 4.3.4f1 Mono | VERIFIED | SUPPORTED local pattern |
| native-03 | Unity 4.3.4f1 player | VERIFIED | UNKNOWN: Thumb mode unproven |
| native-04 | Unity 4.1.1f4 Mono | VERIFIED | SUPPORTED local pattern |
| native-05 | Unity 4.1.1f4 player | VERIFIED | UNKNOWN: Thumb mode unproven |
| native-06 | Mono loader-only | VERIFIED | UNKNOWN: Thumb mode unproven |
| native-07 | Unity loader-only | VERIFIED | UNKNOWN: Thumb mode unproven |
| native-08 | Frima initializer | VERIFIED | SUPPORTED local pattern |
| native-09 | AIR Core literal repair | VERIFIED | UNKNOWN: family root evidence absent |
| native-10 | AIR extension literal repair | VERIFIED | UNSUPPORTED: outside local family |
| native-11 | Unity 4.1.5f1 Mono | VERIFIED | SUPPORTED local pattern |
| native-12 | Unity 4.1.5f1 player | VERIFIED | UNKNOWN: Thumb mode unproven |
| native-13 | Vector loading + JNI guard | VERIFIED | UNSUPPORTED: additional code relocations |
| native-14 | Vector prior-output guard upgrade | VERIFIED | UNSUPPORTED: no family code relocations |
| native-15 | Marmalade dialog initializer | VERIFIED | SUPPORTED local pattern |
| native-16 | Marmalade webview initializer | VERIFIED | SUPPORTED local pattern |
| native-17 | Marmalade billing initializer | VERIFIED | SUPPORTED local pattern |

Baseline VERIFIED here means the named byte/hash/catalog/idempotence tests,
not Android loading, gameplay, purchases, or proof of a hypothesized crash cause.

## Executed evidence and reproducibility

`docs/evidence/structural-prototype/` contains:

- `native-profile-results.json`: each profile's baseline result, detector status,
  every match/rejection finding and derived candidate parameters.
- `generated-results.json`: every generated case, expected/actual result and
  observed false-positive/negative counts.
- `missing-fixture-behavior.json`: deliberate missing-fixture test; it is not a
  second baseline pass.
- `execution-record.json`: executed and not-executed tests and detector source
  checksums. No private fixture paths or native binary bytes are included.

Executed locally: fresh existing `PurchaseProfileTests` suites; the compatibility,
native warning, crash filtering and journal script; standalone detector tests;
all 17 private baseline replays; missing-fixture handling; full Android Java
compile against Android's core jdkImage. New device launches and Unicorn scripts
were not executed in this milestone; historical checks are not counted as new.

Commands after configuring the normal Android/Gradle environment:

```sh
gradle :app:testPurchaseProfile :app:testStructuralDetector
gradle :app:compileDebugJavaWithJavac
gradle :app:auditNativeProfileMatrix -PnativeFixtureIndex=/private/fixtures.json
```

The private index is a JSON object mapping matrix IDs to original file paths,
for example `{"native-01":"/private/original-libmain.so"}`. All 17 are needed for
a complete replay. Missing entries are recorded as SKIPPED and cause a nonzero
audit result after the report is written. Incorrect source identities or baseline
differences fail verification. CI does not receive those private files and records
that absence explicitly while running generated and existing public suites.

## Missing evidence and next boundary

No catalog expansion was used to improve these results. Full recognition of the
six stripped Thumb cases needs independently established instruction-mode
evidence, such as a trusted function/mapping symbol source, or validated
control-flow decoding from known entry points through interworking calls. A
matching byte string, an adjacent ARM routine or an exidx boundary alone is not
sufficient. Adding six hardcoded mode overrides would defeat this milestone.

Before any future automatic transformation, also establish complete supported
reference/use coverage, revalidate derived edits independently, compose unwind/
relocation changes transactionally and replay the complete corpus. None of that
is authorized by the current SUPPORTED recognition state. Existing exact-build
support remains the operational fallback for the six uncertain cases.
