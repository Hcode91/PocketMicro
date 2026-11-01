# Vector 1.0.7 Marmalade runtime repair (experimental)

The owner supplied an Android crash trace showing `libvector.so` rejected for
text relocations inside `com.ideaworks3d.marmalade.LoaderActivity.onCreate`.
The subsequent ZIP **does contain** `lib/armeabi/`, with seven libraries. Its
manifest identifies package `com.nekki.vector`, version 1.0.7 / code 1000007,
minSdk 7 and targetSdk 14. Game assets were omitted.

**The owner confirmed successful Vector startup and embedded touch input after
the JNI surface-buffer guard. This confirms the inspected build on that phone,
not compatibility with every Marmalade runtime.**
All game/native files used for investigation remain outside this repository.

## Apply on the phone

Install the branch Actions APK, add the **original complete Vector APK**, and
select **Unity / Marmalade — startup repairs**, or **Native runtime
repair + touch controls** to embed the pad as well. The report should list
four repaired files for this inspected build. Install the update over the
PocketOuya-signed copy and use normal **Play**, not Engine test. Capture a fresh
crash trace if another error follows. The inspection ZIP lacks game assets and
is not a playable APK.

## Findings and repair

Four files declare text relocations:

- `libvector.so`: 12 R_ARM_RELATIVE targets in read-only code. One is a standard
  runtime initializer literal. The remaining 11 shared literals are referenced by
  43 ARM loads in Marmalade locking/stack-switch routines.
- `libs3edialog_ext.so`, `libs3ewebview_ext.so` and
  `libs3eandroidmarketbilling_ext.so`: one initializer literal each.

The other three libraries (key events, OUYA and input-device extensions) have no
observed writes into non-writable segments and are preserved byte-for-byte.
No purchase logic in the billing extension is altered; its initializer address
calculation is the only executable code changed there.

The three extensions use the already-tested initializer repair: read a fixed
PC-relative distance, add the PC and retain the original branch/argument.
The main library applies the same initializer change. Each of its 43 absolute
literal loads becomes a branch to a 16-byte ARM stub that computes the original
loaded address using the PC, then branches to the next original instruction.
Stubs change only the destination register, not flags, stack or other registers.
Only the inspected unconditional ARM loads are accepted; SP/PC destinations,
unhandled Thumb/ADR references and address-taken code literals are rejected.

The stubs and a new resident program-header table occupy an appended **RX** load
segment. An explicit PT_PHDR describes that resident table. Existing RX/RW flags
are retained; no writable/executable code segment is introduced. Eight zero bytes
of existing RX-segment padding extend the ARM unwind table with a CANTUNWIND
entry covering the new stubs. This prevents the last unrelated function's unwind
recipe from being applied there. Original patched assembly sites are before the
first unwind-covered function. Other unwind entries remain at their original
addresses. The executable-stack GNU_STACK flag already present in the source is
preserved; this repair is not a full security-hardening audit.

Relocation records are compacted and counts/section sizes updated. Every remaining
loader relocation must target writable memory before DT_TEXTREL is cleared.
Android's target policy and linker checks are not disabled. Other native/JNI,
CPU-instruction, service, graphics or asset failures may follow this loader fix.

## Follow-up JNI crash and surface guard

The owner supplied a native crash at 2026-11-01 15:06:02: SIGSEGV/null pointer in
libc memcpy, called by ART ReleasePrimitiveArray / ReleaseIntArrayElements, with
libvector return PC `0x33157`. This differs from the earlier dlopen rejection:
native execution now reaches a Marmalade surface-state cleanup routine.

The inspected Thumb call at `0x33156` loads JNI table offset `0x30c`
(ReleaseIntArrayElements), passes the array handle in r1, r10 as the elements
pointer in r2, and mode 0 in r3. r10 is zero in the supplied crash. The routine
checks that the array handle exists, but not that its elements pointer exists.
Passing null elements for a nonempty array violates the JNI release contract.
The trace does not establish why this legacy runtime retained a handle without
an acquired native buffer; it does establish the immediate invalid release.

Replace only the four-byte movs-mode/indirect-call pair at `0x33154` with a
Thumb BL to a 12-byte guard appended to the existing RX extension. For a valid
pointer it sets the original mode and tail-calls the original JNI function with
the same arguments and return address. For null it returns directly to
`0x33158`. Both paths retain the original DeleteLocalRef call and handle clearing.
No dummy buffer, fabricated JNI success, global JNI hook or Android-check bypass
is introduced. The extension remains covered by its CANTUNWIND entry. Exact
surrounding opcodes and extension layout are checked; unknown builds stay unchanged.

The catalog also recognizes the previous PIC-only output hash
`3aa7134392604f6f2f0877729550862e19f44c5c486743d97cf45a64de40f18b`
and upgrades it to the current output. Prefer reconverting the original complete
APK, especially when adding/updating touch controls. This narrowly handles one
invalid cleanup operation; it does not guarantee surface initialization or gameplay.

## Fingerprints

Selection is by exact native fingerprints, not a package-name bypass. Repaired
output hashes are checked before packaging.

| File | Source SHA-256 | Repaired SHA-256 |
| --- | --- | --- |
| libvector.so | `cf4cd687b0c3c8e1d87c995a96c21c15cdb0c598d59ff35faea620de524c57fe` | `d0c1dfc8fc4c5cd447b7e1aef90c74eaa7cac3b06c7b74bb0f9684d6ffbfd58e` |
| libs3edialog_ext.so | `74f31fd2bec42cb0ccdf4a910d366eb98e27460ef6cb753a8184a21c96d2a22a` | `f100ed0c715e1592d4cacb2475c6e1e9b04d8e8578c409c671c22604ed7716b3` |
| libs3ewebview_ext.so | `75070fd39ffca484ee68a55916656d0f8a966e0f05cea2dc80d2e52e5e3c8ec8` | `33c18ceddc44b8c8ec02d309a36ebab70e07e7304f4692b75fbce7526d276634` |
| libs3eandroidmarketbilling_ext.so | `7e83e8e141245455d6f39c6c751a58323ffb01b4a5427c5c7c6f79537663d367` | `b4ac21ffaeb1845d434f0b8cf5130d68606e9d67edec3bcb5cdca9dd70a99f43` |

Unknown files stay unchanged and are reported by the general catalog. These
fingerprints do not establish compatibility with every Marmalade game.

## Validation and remaining test

The prior PIC-only Java main-library output matches an independent Python
implementation byte-for-byte. The current output adds the JNI surface guard. Unicorn 2.1.4 runs each of the 43 original/repaired loads at three
load biases, comparing all general registers, flags and next PC. It also checks
all four initializer sequences at three biases. ELF RX/RW permissions are used
while executing these small sequences. No Android JNI or entire game executes.

Synthetic tests cover program-header residency, branch destinations, new RX
permissions, safe unwind-table extension, relocation compaction and rejection
of unsupported sites/padding/unwind coverage. The public conversion path for the
supplied archive repairs all four files, preserves original DEX/resources/other
native bytes, adds no helper DEX and reports no remaining DT_TEXTREL declaration.
This validates candidate packaging, not successful gameplay. The follow-up
phone trace separately shows native execution past the original loader rejection.

The instruction script also runs the real cleanup call sequence with ARM and
Thumb JNI test doubles at three load biases. Original null-pointer release is
reproduced; the guarded path skips that call and still deletes/clears the handle.
Non-null calls preserve arguments, return state and callee-saved registers/stack.
Actual source and prior PIC-only inputs produce identical guarded output hashes;
the guarded output is recognized as already repaired. Synthetic fixtures check
branch destinations, RX extension, opcode/layout rejection and unchanged relocation
policy. JNI is mocked here; this is not a full ART or game execution.

With user-owned original/converted APKs, reproduce the instruction checks using:

```sh
python3 -m pip install unicorn==2.1.4
python3 scripts/test-vector-runtime-pic.py original.apk converted.apk
```

The owner subsequently installed the repaired complete game and confirmed both
launch and embedded touch controls. Further crashes or other runtime builds
require separate evidence.

## Embedded touch input

The combined option also hooks the launcher lifecycle and adds the shared OUYA
pad. **Input → Auto (Marmalade keys)** invokes the game Activity's public
`onKeyDown`/`onKeyUp`, including Vector's CustomActivity override. The inspected
LoaderActivity forwards keys through LoaderView/LoaderKeyboard to
`onKeyEventNative(int,int,int)`. This avoids dispatch through focused overlay
buttons and does not require a physical InputDevice. Directions use digital
D-pad keys; this does not emulate an analog controller. Keyboard mode uses the
same callbacks with the documented keyboard mapping.

An actual combined conversion of the supplied partial archive repairs all four
native files, adds three lifecycle hooks and the helper DEX, and preserves
unrelated resources/native libraries. The output DEX checksum and callback
fixtures pass. The owner also confirmed that the repaired complete game launches
and that the on-screen controls register on the phone.
