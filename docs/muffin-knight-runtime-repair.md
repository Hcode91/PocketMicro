# Muffin Knight 1.9.1 runtime repair (experimental)

The owner's phone crash trace identifies an actual startup error: Android rejects
`libmono.so` for text relocations while `UnityPlayer` initializes. This investigation
uses the owner's native files; no game libraries or assets are distributed in
PocketOuya. **The candidate repair has not yet been tested in a running game.**

## Use on the phone

1. Install the investigation branch's PocketOuya Actions APK.
2. Add the **original complete Muffin Knight APK**, including its assets. The
   partial inspection ZIPs are not playable APKs.
3. Choose **Startup repairs — Unity / Marmalade / AIR / Frima** and review its report.
4. Install the conversion over the existing PocketOuya-signed copy and use
   ordinary **Play**, not Engine test.
5. If it still crashes, use **Capture crash log** for a fresh trace.

Repair-only conversion isolates native startup and adds no touch pad, receipt
changes or unlocks. To test embedded controls too, choose **Native runtime repair
+ touch controls** instead. The touch result is reported separately; neither
repair option changes purchases.
The existing signing key remains in use. Preserve saves before uninstalling any
copy signed by a different key; uninstalling is not required for an update over
an existing PocketOuya-signed installation.

## What was found

The game package is `com.angrymobgames.muffinknight`. Its Java startup explicitly
loads `mono` and `unity`. The small files in `lib/armeabi-v7a/` are proxy loaders:
their SONAMEs are `libmono_proxy.so` and `libunity_proxy.so`, and they refer to the
actual runtime payloads in `assets/libs/armeabi-v7a/`. The owner subsequently
provided those payloads. The embedded Unity runtime identifies itself as
**Unity 4.1.5f1**.

Both real payloads also declare text relocations. Relocation-table inspection
finds one write into a non-writable segment in Mono and two in Unity. The other
relocations target writable data/GOT. Therefore simply promoting the embedded
files, or deleting a declaration without changing code/relocations, is insufficient.

Accepted SHA-256 fingerprints:

| Input | SHA-256 |
| --- | --- |
| Proxy libmono.so | `a280a957e102a2b0e075f339ddff6a648a27af8d8ecfb2a994a43905d38cf15c` |
| Proxy libunity.so | `aee89345929061f1e52fe72a7874796a8d3cdba8a663bf370a14cea896b05961` |
| Embedded libmono.so | `cf6e3b14a342ea3a6d5f064dfb7d8314aa46adb784c5d98e42b1d32a5864029a` |
| Embedded libunity.so | `e827ed895c38e3753c132ba1e0b95207c55cc16b8474fb4e26f1d7f5c719170e` |

These fingerprints now form a game-independent catalog. The package name is not
a restriction. Directly installed matching runtimes can be repaired without any
proxy/assets requirement; a recognized proxy additionally needs its matching
embedded payload. Unknown or missing components stay unchanged and are reported.
Each component is matched independently; a partial repair can leave other errors.
See [current catalog behavior](native-runtime-repair.md).

## Repair

The converter replaces the two proxy entries under `lib/armeabi-v7a/` with repaired
copies of the same game's embedded runtime payloads. Original DEX, embedded asset
payloads, other assets/resources and the OUYA/JNI libraries are preserved.

- Mono initializer at `0x15a60` and Unity initializer at `0x64720`: replace the
  absolute pointer literal with a PC-relative distance and compute the address
  in registers. The existing branch and argument value are preserved.
- Unity Thumb destructor-registration thunk at `0x64730`: compute the same
  arguments and tail-call the existing `__cxa_atexit` PLT entry. Its writable GOT
  slot remains resolved by Android's linker. Update the corresponding ARM unwind
  descriptor from a pushed-register frame to a frameless return description.
- Remove the now-obsolete three relocation entries; compact `.rel.dyn` and
  update `DT_RELSZ`, `DT_RELCOUNT` and section size. All remaining relocation
  writes are checked to target writable segments before clearing `DT_TEXTREL`.
- Preserve program-header layout and RX/RW permissions. No RWX segment is added,
  target SDK lowered, linker check disabled, or alternate system loader installed.

The resulting payload hashes are also checked:

| Repaired output | SHA-256 |
| --- | --- |
| libmono.so | `f366859ac255785e660ff9af9a46cd146d460d4037496d39128530ffc3eb87b1` |
| libunity.so | `aa37e7afd709c19469ed2e7f63f4452a6d6b0275e0801a8c263c14ddcad0268a` |

## Validation

Synthetic fixtures test ARM/Thumb changes, relocation compaction/counts, unwind
metadata, unchanged segment permissions, unknown-source rejection and rejection
when any code relocation remains. Existing conversion/inspection/protocol tests
also run. Android Java compilation is checked separately and by CI.

The Java output for the actual supplied payloads matches an independent Python
implementation byte-for-byte. Unicorn 2.1.4 instruction checks execute original
and repaired code at three load biases, including ARM/Thumb caller/callee
interworking. They compare startup/destructor arguments, preserved stack/register
state and return mode while using the libraries' original RX/RW permissions.
This checks the affected routines, **not the entire Mono/Unity runtime**.

For reproduction with user-owned files:

```sh
python3 -m pip install unicorn==2.1.4
python3 scripts/test-muffin-runtime-pic.py original.apk converted.apk
```

A scratch APK assembled from the owner's partial archive plus embedded runtime
files passed the public conversion path. It changes only the install manifest and
two selected native entries; original DEX, supplied assets/resources, libjni and
OUYA native library bytes are preserved, with no helper DEX added. The scratch
archive still lacks the rest of the game's assets and is not a playable build.

## Remaining risks and next test

`libjni.so` remains unmodified and declares text relocations. It is not named in
the observed Java startup load sequence or either runtime's DT_NEEDED list, but
later managed/plugin paths could still load it. The conversion report continues
to warn about it; if the next crash identifies it, it needs separate repair.
Other old-runtime dependencies, JNI behavior, assets, graphics, permissions and
OUYA services can also fail after the reported loader error is cleared.

Actual phone launch, gameplay, touch input, purchase compatibility and saves are
unverified. This is a scoped candidate fix for the observed loader error, not a
claim that Muffin Knight now runs or unlocks. Reconvert the original complete APK
and collect a new trace if it fails at a later stage.
