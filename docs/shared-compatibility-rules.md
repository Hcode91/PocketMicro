# Shared repair attempts

These rules run when the user chooses **Engine startup repairs** during import,
or **Prepare fixes** in the APK compatibility doctor. Normal conversion leaves
them off. The doctor first reports read-only applicability results. Reconvert
the original complete APK to try a rule; changing PocketOuya does not change
already installed games.

This work is on `feature/shared-compatibility-rules`, based on `1d85252`.
The read-only finalizer detector and its seven-supported/six-unknown milestone
are unchanged and remain disconnected from transformation selection.

## `elf.arm32.stale-textrel`

This rule supports ELF32 little-endian ARM shared libraries with complete,
bounded ordinary REL/PLT relocation metadata. It checks each supported loader
write's width, alignment and writable load mapping. It rejects code writes,
unknown relocation types, COPY/resolver relocations, packed/RELA/RELR tables,
missing descriptors, overlapping tables/mappings, aliased load bytes and writes
into dynamic/relocation metadata. RELCOUNT prefixes are checked independently.
Symbol-indexed relocations require file-backed SYMTAB/SYMENT evidence.

Only when every write passes does it remove a stale DT_TEXTREL entry and clear
DF_TEXTREL. Other flags, load permissions, instructions and relocation records
are unchanged. It reparses the result, rechecks the relocation proof and checks
that every changed byte is within dynamic metadata. Inspection returns no
modified bytes. Repairs return a separate copy.

This is **not** a general text-relocation rewrite. A libmono.so that actually
writes to executable memory stays unsupported by this rule. Monocle Man's log
proves loader rejection but cannot establish whether its declarations are stale;
the original library must be inspected. Filenames, APK packages, hashes and
instruction-pattern guesses do not select this rule.

## `ouya.content.null-context-cleanup`

This rule recognizes the shared OUYA content SDK's instance `shutdown()V`
method, rather than an application package. The supported body consists solely
of two instance-field loads (Context and ServiceConnection), one exact
Context.unbindService call and return. Both registers must be distinct and
loaded from this instance's declared fields. Branches, handlers, other effects,
different signatures and instruction forms are rejected or left unknown.

The transformation inserts a null-context branch to the original return, and
checks the serialized instructions, register and branch destination. With a
non-null context the original unbind executes and its exceptions still propagate.
No exception swallowing, replacement service, network backend, purchase changes
or helper DEX is added. Already guarded output is skipped. Duplicate classes,
malformed or reserved-opcode DEX preserve the original DEX set.

Polarity's log verifies a null-context crash in OuyaContent.shutdown after an
earlier implicit service-intent failure. This rule addresses only the cleanup
crash **if its original DEX matches**. It does not resolve failed initialization,
an unavailable service or missing native dependencies. Polarity's original APK
was unavailable for this implementation, so applicability and phone behavior
remain unverified; no working-game claim is made.

## Composition and diagnostics

`SharedCompatibilityRepairs` keeps all existing exact native plans first, then
attempts the independent stale-declaration rule on remaining native entries and
the SDK cleanup rule on bounded application DEX. Subsequent touch and
purchase conversion reads the resulting DEX; existing feature-isolation suites
are retained. A combined shared-rule/touch path is checked in the APK fixture.
Purchase validation and the four frozen native transformation/catalog sources
are unchanged. No new fingerprint or build-specific offset was added.

Reports show rule ID, inspected file where relevant, status and reason:
SUPPORTED means applicability evidence; VERIFIED means the named local output
checks passed; UNSUPPORTED means an excluded form; UNKNOWN means insufficient
evidence; MALFORMED means invalid structure; SKIPPED means absence, an existing
guard, or an explicit analysis limit. These results do not certify gameplay or
APK safety. Separate original/native loading warnings may still be shown in
the read-only preview, before an eligible stale-declaration repair is applied.

## Executed checks

- Fresh host compatibility/purchase/input/native/doctor suites pass.
- Shared-rule fixtures vary native layout, data addresses, registers, field
  names, unrelated game classes and native filename. Read-only inspection and
  original APK preservation, null/non-null cleanup execution, idempotence,
  asset preservation, no helper injection and disabled-option isolation pass.
- Actual code writes, unknown/COPY/resolver relocation types, packed formats,
  incomplete descriptors, invalid symbols/RELCOUNT, self-modifying metadata,
  truncated ELF, different cleanup effects, malformed and duplicate-class DEX
  are rejected as tested. No tested negative was repaired. These bounded tests
  do not establish universal false-positive or false-negative rates.
- The unchanged detector's 43 generated cases still pass: TP12/TN31/FP0/FN0.
- Local real-native replay: **12 verified, five unavailable and SKIPPED**
  (native-01 through native-05). Both direct baseline replay and shared
  coordinator comparison preserve every available expected output hash and
  replacement set. Missing fixtures cause audit commands to exit nonzero;
  the complete 17-profile replay is **not** a new passing result.
- Full app Java compilation against Android core stubs passes; normal existing
  annotation/deprecation warnings remain. Phone installation/gameplay and the
  two new rules on Monocle Man/Polarity are not executed.

Reports: `docs/evidence/shared-rules/native-profile-replay.json` and
`coordinator-replay.json`. Private binaries and their local paths are not shipped.

Reproduce with the normal Android/Gradle setup:

```sh
bash scripts/test-compatibility.sh
gradle :app:testPurchaseProfile :app:testStructuralDetector
gradle :app:compileDebugJavaWithJavac
gradle :app:auditNativeProfileMatrix -PnativeFixtureIndex=/private/fixtures.json
```

The optional `SharedRepairFixtureAudit` host runner accepts matrix, private
fixture index and result JSON paths. It compares the coordinator against the
unchanged native plan and frozen output hashes. Neither rule reads this oracle.

## Supplied Polarity DEX follow-up

The supplied manifest identifies com.bluebox.PolarityCoop, target16. Its SDK
shutdown loads a Context field then passes `this` to unbindService. The receiver
class directly implements ServiceConnection. Extend the same rule to that
three-instruction form, requiring the declared interface, distinct receiver and
context registers, the exact call and return, with no other effects or handlers.
This is an SDK structural variant, with no application ID/hash/offset selection.

Fresh host suites and Android-core compilation pass. Typed-self fixtures cover
both context paths, repeat repairs and rejection when the interface is absent.
On the supplied original DEX: rule repair VERIFIED, original input unchanged,
canonical DEX output with only shutdown omitted identical before/after, repeat
inspection SKIPPED. The earlier two-field form remains supported.

The Java evidence confirms OuyaContent.init calls bindService with an implicit
COMMUNITY_CONTENT intent before setting its Context field. UnityOuyaFacade.Init
calls OuyaFacade.init before constructing its callback listeners. This explains
the earlier service exception, null listener and null-context shutdown sequence.
It does not establish the precise cause of the current blank/loading screen.
Startup service binding, missing native plugin resolution and gameplay remain
unresolved; managed assemblies and a fresh runtime log are still needed. No
purchase implementation, native transformer or finalizer detector changes.

## 2026-11-03 additions (general rules, no per-game fingerprints)

- **`android.legacy-sdk.explicit-service` widened.** No longer limited to the MOGA and
  Google Analytics packages: any `Context.bindService`/`startService` call in game or SDK
  code (receiver resolved through the DEX class hierarchy) goes through a helper. An
  implicit Intent is resolved via PackageManager; exactly one match is called explicitly,
  none or several return "unavailable" (false/null) instead of the Android 5+ crash.
  Bundled `android.support.*`/`android.annotation.*` classes no longer block the rule
  (they did before, which is why GameMaker games such as Abbaye des Morts kept crashing).
- **`android.legacy-device-id`.** TelephonyManager device ID/IMEI/MEID/SIM/subscriber/number
  reads are wrapped; only a refused read (Android 10+ SecurityException) returns
  `000000000000000` (device IDs) or null (SIM/subscriber/number). Wraithborne (UE3).
- **`elf.arm32.detected-finalizer`.** For native libraries not in the fingerprint catalogue,
  the read-only `ArmFinalizerDetector` supplies the finalizer/target/thunk/PLT addresses and
  the unchanged catalogue routine `ArmRuntimePicRepair.repairLayout` performs the repair,
  re-checking every instruction byte. The detector also accepts Thumb mode proven by direct
  call sites (Thumb `BL` / ARM `BLX` to the thunk, and no ARM-mode `BL`). Target: Unity /
  Marmalade / Frima libraries such as Wrecking Balls Arena's `libmain.so`.
- **Conversion record.** Converted APKs carry `assets/pocketouya-conversion.txt` (PocketOuya
  version, options, rule results); launch log entries show it.

## Old engines: removed private libraries and original-version install (2026-11-03)

- **`android.removed-private-libs`.** When a game library DT_NEEDs a private Android library
  (libstagefright, libui, libutils, libcutils, libgui, ...) that the APK does not bundle, the
  conversion adds a generated placeholder (`StubLibraryWriter`) exporting every `android::`
  symbol the game imports. Android binds all imports at load time, so removed symbols such as
  `OMXClient`, the old `GraphicBuffer` and `MetaData` otherwise stop the engine loading.
  Placeholder functions return their first argument (constructors return `this`, status checks
  fail), so engines take fallback paths such as no hardware video. Checked on
  A Zombie Stole My Toaster (Adobe AIR 2013): 35/35 libCore android symbols resolve through the
  placeholders' hash tables and an ARM lld link succeeds.
- **Keep original Android version (Shizuku install).** Conversion option that leaves the game's
  target SDK unchanged and installs through PocketOuya's Shizuku service with
  `pm install --bypass-low-target-sdk-block` (Android 14+). Apps built for pre-Android 6 may
  load libraries with text relocations (AIR libCore, old Unity/Marmalade), so no native code
  patching is needed for those. Shizuku is needed only during install.

## Clickteam Fusion: game surface created mid-layout (2026-11-03)

- **`clickteam.surface-layout`.** The Clickteam Fusion (MMF) Android runtime creates its game
  `SurfaceView` from `MainView.onSizeChanged`, while the window is still laying out, then calls
  `setLayoutParams` on it. Android 4.x re-ran layout for that request; Android 4.3+ drops it, so
  the view is never measured or placed (`SurfaceView updateSurface: has no frame`), no GL surface
  is created and the game stays on a blank screen. Each `Runtime.SurfaceView.setLayoutParams`
  call is routed through a helper that makes the same call and, only when the view has never
  been placed and its parent already has a size, measures and places it as `MainView.onLayout`
  would (LayoutParams size, MATCH_PARENT = parent size, at x/y). Detected by the runtime's
  `Runtime/MMFRuntime`, `Runtime/SurfaceView` and `Runtime/MainView$LayoutParams` classes, so it
  applies to any Clickteam game. Seen on Tomatoes Vs Onions; device test pending.

## WebView games: JavaScript bridge under the raised target (2026-11-03)

- **`android.webview-js-interface`.** Since Android 4.2, a WebView only lets page JavaScript call
  Java methods marked `@JavascriptInterface` once the app targets API 17+. Conversions target
  API 23, so PhoneGap/Cordova 2.x games lose their bridge (`nativeApiProvider.get(...).exec is
  not a function`, `deviceready has not fired`) and stay on their first page (Vector 360). For
  each `addJavascriptInterface(object, name)` call the object's class is found from the
  instruction that last set that register (new-instance, field, call result, cast, parameter).
  Dedicated bridge objects (only app classes between them and `Object`) get the runtime
  annotation on the public instance methods they and their app superclasses declare. Activities,
  Views and other framework subclasses used as bridges (e.g. Flurry's catalog Activity) are left
  alone, as are sites whose object type can't be found (reported). Device test pending.

## OUYA saves without the OUYA system (2026-11-03)

- **`ouya.game-data.local-store`.** `OuyaFacade.getGameData`/`putGameData` store a game's saves
  (high scores, options) in the OUYA system's `tv.ouya.userdata` content provider. On a phone it
  does not exist: the query returns null and the SDK crashes on `cursor.moveToNext()` /
  `getCount()` (X S.E.E.D loading hiscores). Both methods get a short prefix: if
  `acquireContentProviderClient("tv.ouya.userdata")` returns null, values are read from / written
  to the game's own private SharedPreferences file `pocketouya_ouya_gamedata` (same key/value
  strings, kept across launches); otherwise the original SDK code runs. Saves made this way stay
  on the phone with the game. Purchases and receipts are not involved.

## GLES uniform array counts (2026-11-03)

- **`android.gles-uniform-count`.** `GLES20`/`GLES30` `glUniform{1-4}{f,i}v` and
  `glUniformMatrix{2-4}fv` array forms take a count of vectors/matrices. Android 4.x only checked
  the array against `count`, so games passing the number of floats worked; Android 5+ checks
  `count * size` and throws `IllegalArgumentException: length - offset < count*4 < needed` on the
  first frame (XY Cannon passes `MAX_RIP * 4` to `glUniform4fv`). Each call goes through a helper
  in the calling class that lowers `count` to the whole vectors/matrices the array holds after
  `offset`; fitting counts, null arrays and bad offsets are passed through unchanged.

## OUYA account lookups and AndEngine base64 maps (2026-11-03)

- **`ouya.account-placeholder`.** OUYA games call
  `AccountManager.getAccountsByType("tv.ouya.account.v1")` and take element 0 (Zombie Driver HD
  keys its cached receipts by that account in its store-failure path). Phones have no OUYA
  account, so the array is empty and the game crashes. In games that mention that account type,
  each lookup goes through a helper that returns the real result, or, when it is empty and the
  OUYA type was asked for, one local placeholder `Account("OUYA", "tv.ouya.account.v1")`. It is not
  registered with Android and has no receipts; it only keys data the game stores itself.
- **`andengine.base64-unsigned-read`.** AndEngine's `Base64InputStream.read()` returns signed
  bytes. Android 5+ `GZIPInputStream` rejects values outside -1..255, so gzip+base64 TMX maps fail
  to load and the game crashes drawing a textureless layer (Zombie Outbreak). The decoded byte is
  masked with `0xFF`; the end-of-stream `-1` is unchanged.

## Store-offline answers arrive on the main thread (2026-11-03)

- **`ouya.store-offline.answer-requests` (updated).** The SERVER_OFFLINE (3005) failure was
  delivered synchronously on the requesting thread. Games expect the real store's behaviour: a
  reply on the main thread after the request returns. Zombies and Trains! requests products from
  Unity's GL thread and shows a Toast in `onFailure` ("Can't toast on a thread that has not called
  Looper.prepare()"). The helper now posts a small added `PocketouyaStoreOfflineFailure` Runnable
  (same package as OuyaFacade) to `new Handler(Looper.getMainLooper())`; its `run()` calls
  `listener.onFailure(3005, "OUYA store unavailable", new Bundle())`.

## OUYA controller player numbers without the OUYA system (2026-11-03)

- **`ouya.controller-player-numbers`.** The OUYA SDK's `OuyaController` asks the OUYA system's
  `tv.ouya.controllerdata` provider which player each input device is. On a phone that provider
  is missing, so every controller (Bluetooth pads and PocketOuya's touch pad, device id -1) is
  player -1: `getControllerByPlayer()` stays null, games ignore input (Zombies and Trains! "controller
  state for player -1") or crash on the missing controller (Zombie Driver HD `OnPadButtonEvent`).
  When the provider is unreachable, `getPlayerNumFromDeviceId`/`getDeviceIdFromPlayerNum` answer
  from an added `PocketouyaPlayers` table: the first gamepad/joystick devices (by InputDevice
  source) or PocketOuya's virtual pad to send input become players 1-4; keyboards, touchscreens
  and phone buttons never take a slot. With the provider present the SDK runs unchanged; the
  SDK's own button/axis mapping is used. Applies to 11 of the 14 uploaded test APKs (Worm
  Copter's older SDK layout and two games without the OUYA controller API are unchanged).

## OUYA TV layouts on phones (2026-11-03)

- **`ouya.tv-layouts`.** Android reports the OUYA's TV as a large landscape screen, and some games
  keep their real screens only in `-large-land` resources. A phone is a normal-size screen, gets
  the default resources, and crashes when a view the game expects is missing (Word Shaker:
  `findViewById` returns null for an ImageView that only exists in `layout-large-land/main.xml`).
  In `resources.arsc`, each resource group configured "large + landscape" has its screen-size bits
  cleared, so landscape phones use the OUYA's resources. Groups are left alone when the game
  already has a plain landscape version (or two large groups would merge). Only those bits change;
  files, IDs and the table size are unchanged. Of the 15 uploaded test APKs only Word Shaker has
  TV-only resources (layout and drawable groups).
- **`ouya.tv-layouts.landscape`.** Runs only when `ouya.tv-layouts` changed resources. Those
  resources now need a landscape screen, but Word Shaker declares no orientation (the OUYA was
  always a landscape TV), so on a phone held upright it still opened in portrait, got the default
  layout and crashed the same way (`WordShakerOuyaActivity.j` -> `ImageView.setImageResource` on
  null; the log's Config line showed `port`). In the binary `AndroidManifest.xml`, every
  `<activity>` without its own `android:screenOrientation` gets `sensorLandscape`, inserted in
  resource-ID order; activities that already declare an orientation keep it. If the attribute name
  is not yet in the string pool it is added at the end of the resource-mapped prefix and every later
  string reference is shifted by one. Malformed or styled string pools leave the manifest unchanged.
- **`ouya.tv-layouts.screen-size`.** Also runs only when `ouya.tv-layouts` changed resources. The
  landscape manifest alone was not enough for Word Shaker: AFKLib `b.e()` returns true only for a
  large/xlarge `Configuration.screenLayout`, and when it is false `WordShakerActivity.onCreate`
  calls `setRequestedOrientation(PORTRAIT)`, so the activity started in landscape, was recreated in
  portrait with the phone layout and crashed the same way (log: first `Config` `land`, second
  `port`). After every game-code `iget Configuration.screenLayout` the size bits are set to LARGE
  (`and-int/lit8 -16`, `or-int/lit8 3`), which is what the OUYA reported, so the game's own size
  checks agree with the TV resources it now gets. Other screenLayout bits and PocketOuya's own
  classes are unchanged; already-forced reads are recognised, so a second pass changes nothing.
  The converter run on the Word Shaker APK applies all three parts (2 resource groups,
  10 activities, 2 screenLayout reads); gameplay on a phone is not yet confirmed.

## Low memory addresses experiment (2026-11-04, opt-in)

- **`pocketouya.low-addresses`.** Conversion option "Experiment: keep the game's memory below
  2 GB", unticked by default. The OUYA's 32-bit kernel kept a game's memory below 3 GB; a 32-bit
  game on a 64-bit kernel also gets addresses up to 4 GB. Under test for Soul Fjord, whose
  Scaleform manager sat at `0xe4edb050` when its render-thread pointer was found null; that
  high-address memory mishandling causes the crash is **unproven**. Each launch activity's
  `onCreate` first calls the helper DEX's `LowAddress.reserve()` (an `onCreate` calling super is
  added when missing), which loads `libpocketouya_lowaddr.so`, added beside the game's own
  `lib/armeabi-v7a` (or `lib/armeabi`) libraries. Its constructor reads `/proc/self/maps` and
  maps the free gaps above 2 GB as `PROT_NONE`, `MAP_NORESERVE`, `MAP_FIXED_NOREPLACE` (never
  over an existing mapping), leaving 64 MB under the main stack. If less than 768 MB would stay
  free below 2 GB it uses 3 GB instead, and below that it reserves nothing. It logs the result
  and test allocation addresses under `PocketOuyaLowAddr`. Refused for APKs with arm64/x86
  libraries (they may not run as 32-bit ARM), Java-only APKs, and already converted APKs. Game
  code and libraries are otherwise unchanged. Host tests cover the planner (`tests/lowaddr`) and
  the DEX hook/packaging (`LowAddressRuleTest`, including Soul Fjord's real launcher locally);
  the library itself is built by CI with the NDK and is untested on a phone.

