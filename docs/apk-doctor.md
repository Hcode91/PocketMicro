# APK compatibility doctor

Open **APK COMPATIBILITY DOCTOR** in PocketOuya. This is separate from Add game
and the GameMaker engine test; it accepts ordinary original APKs as well as games.

1. **SELECT APK AND SCAN** copies the chosen file to private temporary storage
   and inspects it without executing any APK code. Installation permission is
   not required for scanning/export.
2. Review phone API/ABIs, declared SDKs, packaged native libraries, known repair
   preview and remaining native loading warnings. A matching ABI is only a
   structural indication; it does not establish CPU/API compatibility.
3. **PREPARE SUPPORTED FIXES** asks for confirmation, patches a separate copy,
   applies fingerprinted native repairs, signs it with PocketOuya's game key and
   verifies the resulting signature. A post-conversion scan is included in the
   report, alongside original findings and applied actions. Unresolved warnings
   stay visible. Nothing is installed automatically.
4. **EXPORT APK** saves the prepared file through Android's document picker.
   **EXPORT REPORT** saves the full report, including after preparation.
5. **INSTALL** explicitly hands the prepared copy to Android's package installer.
   If needed, enable installation from PocketOuya and tap Install again. The
   package is remembered for PocketOuya's library so normal launch/global logging
   can be used. Installation dispatch does not establish that the app runs.

Export before leaving this screen. Working copies use app cache and are removed
when closing the screen where possible; process termination can leave cache
files until Android clears them. Completed work can be restored from activity
state while those files remain. Selecting another APK replaces the previous
working copy. Inputs are streamed with a 2 GiB cap; report/manifest/native scans
have separate bounds. No APK contents or report is uploaded.

## Supported first-version actions

- Raise the effective manifest target to at least API 23 for the existing
  converter baseline, or API 24 on Android API 35/36. Targets already above the
  floor are preserved. No explicit target with an integer minSdk uses the
  existing converter's fallback of raising minSdk. Old Add game conversion APIs
  retain their existing API 23 default.
- Apply the existing exact-library catalog: Unity/Mono/player/NativeActivity,
  recognized Unity embedded-payload promotion, Marmalade, inspected AIR and Frima
  builds. Unknown binary fingerprints stay unchanged. All remaining relocation
  writes must pass the existing repair checks; no broad TEXTREL flag removal.
- Strip old APK signature metadata and sign a separate output using the same
  key used by PocketOuya conversions. The original file is unchanged.

The doctor never changes a game's purchase checks.

## Limits that must stay visible

This is not a universal automatic porter, emulator or security audit. There is
no guarantee of a launch. The scan reports a minSdk newer than the phone, no
matching packaged ABI, and potential split/resource-only APKs; the current
conversion does not fix those conditions. Native code can also live in assets
or be downloaded later. Split-set installation/merging is not implemented.

It does not establish missing Java/private native APIs, online service
availability, licensing behavior, runtime permission handling, graphics/audio
correctness, 16 KiB page support, or APK trust. Policy after Android API 36 is
unverified. Malformed manifests or unsupported SDK layouts may be refused.
Duplicate archive entries are rejected. Inspection bounds can leave files
uninspected, and absence of a warning is not an all-clear.

Raising targets can change permissions, storage behavior and native loading.
The new signing identity differs from the original publisher. It can prevent
updating an installed original, break signature-dependent features or trigger
game checks. Do not uninstall an existing app without protecting its saves.
Known repairs are attempts for specific bytes, not a claim that every app with
the same engine is compatible. Export and review the report before installation.

## Validation

Host tests cover API 23/24 floors, preserving a higher target, minSdk and ABI
warnings, unknown native TEXTREL remaining after the repair preview, no DEX/split
warning, immutable inspection, ZIP asset preservation, malformed manifest and
duplicate-entry rejection. Existing host/native/receipt/log suites pass.
Android Java compilation checks the screen and signature-verifier APIs.

The supplied AD2442 manifest/four-library fixture also passed scan, three native
repairs with API 24 conversion, v1/v2 signing, APK signature verification and
post-conversion inspection with the original unchanged. This fixture is not a
complete game APK. File-picker export, installer permissions/session behavior,
activity restoration and real converted app launches still need phone testing.


### Android core API compilation

The initial CI build exposed six desktop-JDK `Files.readString/writeString`
references unavailable in the Android compile API. Doctor report/package I/O
now uses UTF-8 file streams, with a 4 MiB saved-text read bound. Validate local
Android compilation with the AGP-generated Android core `jdkImage` passed to
`javac --system`; putting android.jar only on the classpath can accidentally
resolve java.* methods from the desktop JDK. The original source fails this
Android-core check on the same six calls; the updated full app passes it. CI's
normal Android Gradle compile remains the authoritative build check.
