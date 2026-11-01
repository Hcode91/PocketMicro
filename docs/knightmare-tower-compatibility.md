> Current converter note: the exact-package/fingerprint gate described below is historical. The converter now matches activity and OUYA Unity SDK capabilities across games; see the README. Knightmare Tower touch controls were confirmed working by the owner. Unlock acceptance and Final Fantasy III remain unverified.

# Knightmare Tower 2.1.0: confirmed compatibility findings

Inspected the owner's archive on 2026-11-01. It contains the APK files except
`assets/`; it is not a complete runnable or signature-verifiable APK. No game
files or decompiled game code are committed here.

Archive SHA-256:
`0c3c10916a142db6d520099087ab910692a3e5148f28902d6cc9b9624aba210e`.

## Confirmed engine and Android integration

- Package `com.juicybeast.KnightmareTower`, displayed version 2.1.0, code 2.
- min/target SDK 16. The launcher declares the OUYA GAME category.
- All supplied native libraries are `armeabi-v7a` (32-bit ARM): `libunity.so`,
  `libmono.so`, `libmain.so`, and `lib-ouya-ndk.so`.
- `libunity.so` contains version string **4.5.0f6**. This is a Unity/Mono build,
  not a GameMaker runner. No `libyoyo.so` was supplied.
- Java includes UnityPlayer and `tv.ouya.sdk.OuyaUnityActivity`;
  the game's MainActivity participates in the Unity lifecycle.

The owner confirmed the normally converted APK launches successfully. The new
purchase profile therefore targets the separately installed game, not guest
boot. PocketOuya's current guest boot explicitly loads libopenal/libyoyo and invokes
GameMaker RunnerJNILib entry points. It does not load this Unity runtime or
execute its Java player/managed scripts. The purchase hook cannot resolve that
execution-engine mismatch. The dynarmic ARM translator is reusable infrastructure,
but Unity needs its own loader, runtime/JNI, rendering, audio and lifecycle work.
This requires execution support before an engine-side unlock can be tested.

## Confirmed purchase bridge

Java DEX contains:

- `tv.ouya.sdk.OuyaUnityPlugin`: `getProductsAsync`, `getReceiptsAsync`,
  `requestPurchaseAsync`, initialization and developer-ID setup.
- `tv.ouya.sdk.UnityOuyaFacade`: product, receipt and purchase requests.
- `tv.ouya.console.api.OuyaEncryptionHelper`: purchase/receipt decryption and
  plaintext JSON receipt parsing methods.

The Java bridge sends callbacks through
`UnityPlayer.UnitySendMessage("OuyaGameObject", callbackName, payload)`.
Receipt callbacks include `ReceiptListClearListener`, `ReceiptListListener`,
`ReceiptListCompleteListener`, `ReceiptListFailureListener`, and
`ReceiptListCancelListener`. Purchase callbacks include
`PurchaseSuccessListener`, `PurchaseFailureListener`, and
`PurchaseCancelListener`.

Receipt payload construction includes `identifier`, `purchaseDate`, `gamer`,
`uuid`, `localPrice`, `currency`, and `generatedDate`. Purchase-success payloads
include the product identifier and product metadata. The bridge checks purchase
responses against the outstanding request/product rather than using an
unconditional generic success flag.

These facts identify the delivery boundary, not the game-side condition for
ownership. The managed scripts decide which product unlocks the game and what
state to save. Their code is in the omitted assets. No exact unlock product ID,
managed callback implementation or save-state format has been verified.

The Xposed plaintext module hooks Java Cipher in separately running Android
processes. It does not load into PocketOuya's native guest engine, implement
UnitySendMessage there, provide a replacement store, or establish ownership.
Do not install a global crypto bypass as an engine fix.

## Implemented normal-conversion profile

The selected runtime path is **normal converted APK launch**, confirmed working
by the owner. Unity engine emulation is not a prerequisite for this route.
The opt-in converter profile checks the package and this original classes.dex:

`87153fb8bd18a716dbb448744ff4cf295c7503f066856573a4b392d71e138b47`.

It requires a single original DEX and these six public static SDK entry points:

- `addGetProduct(String): void`
- `clearGetProductList(): void`
- `getProductsAsync(): void`
- `getReceiptsAsync(): void`
- `fetchGamerInfo(): void`
- `requestPurchaseAsync(String): String`

The converter redirects only these methods to a helper DEX built from our own
Java source. It preserves their signatures and the asynchronous purchase return
convention. The remaining game classes and all copied assets/native libraries
are preserved. Generic APK conversion does not perform this rewrite.

The injected helper returns requested product IDs as zero-price local products,
delivers the observed Unity callbacks on the Android main looper, and stores
completed local durable purchases in game-private SharedPreferences. Receipt
replay is driven by stored product IDs/timestamps, with stable UUIDs tied to the
game and product. The gamer response is explicitly local. It makes no network
purchase or payment. It rejects invalid IDs, excessive product/receipt counts,
wrong package contexts and failed persistence. A failed preference commit rolls
back the in-memory entry and sends failure rather than success.

This provides an offline purchase compatibility path, not real account-backed
ownership, historical purchase restoration or a consumable billing system.
The plaintext-Xposed mechanism is not used and no global encryption is weakened.

### Validation

- Synthetic DEX tests verify the six redirects, unchanged extra methods,
  unknown-fingerprint rejection and missing-method rejection.
- Tested the actual supplied archive through the converter: all six entry points
  are rewritten, the helper DEX is added, and its supplied resources/native
  libraries are byte-identical after reconstruction. The archive omitted assets;
  it was never treated as a complete runnable APK or redistributed in Git.
- Product/receipt JSON tests check field types, escaping, timestamps and stable
  per-game identities.
- Test-only Android/Unity doubles exercise asynchronous callback order, empty
  initial receipts, product deduplication, durable-repeat timestamps, preference-
  backed receipt replay, invalid requests, wrong-package refusal and failed
  persistence. These are not tests inside Unity on Android.
- Application Java and bridge compile against Android API stubs; D8 emits the
  helper. Full application packaging is checked by CI.

A successful Knightmare Tower unlock has **not** been verified. The C# game
logic was omitted, and callback delivery, product assumptions and actual local
state acceptance still need a phone test. Reimport the **full original APK**,
select the offline purchase profile, install and use ordinary Play. If the game
rejects it, collect the error/log and provide the DLLs from
`assets/bin/Data/Managed/` (especially Assembly-CSharp and OUYA plugin assemblies)
for deeper managed-code inspection. Do not upload scenes/textures/audio or put
game files in this source repository.

## Game-owned touch pad

The selected profile now also adds install/release hooks at MainActivity's
onResume/onPause returns. DEX return labels are retained at the inserted call,
so branches targeting the old return do not bypass the hook. Original lifecycle
code is preserved. Existing six purchase redirects remain unchanged.

The provided SDK's OuyaUnityActivity has native handlers
`dispatchKeyEventNative(int player,int keyCode,int action)` and
`dispatchGenericMotionEventNative(int player,int axis,float value)`. Its normal
onKeyDown/onKeyUp path first resolves an InputDevice to a player. Synthetic
Android key events can fail that lookup or hit a null device. The pad calls
these app-owned handlers directly with player index 0 (player 1), without
registering a physical controller or injecting events into Android globally.
The supplied native implementation accepts indices 0–3; its dispatcher and SDK
constants were inspected, not guessed.

Verified mappings: O=96, U=99, Y=100, A=97, L1=102, R1=103, Menu=82 and directions
19–22. Direction state also updates axes X=0/Y=1 with values -1/0/1. Native
coordinates are game controller state, not screen touch coordinates.

The overlay stays inside the game activity. Only buttons consume touches;
other screen regions remain available to the game. It has a hide/show control,
per-button press ownership, multitouch and accessibility click pulses. All held
state is released when hidden, paused, unfocused or detached. It uses weak
activity references and retains the existing game's permissions. It is scoped
to the exact Knightmare Tower profile, not a system-wide gamepad.

Tests cover player/argument reflection into a fake matching SDK, key/axis state,
multiple owners, diagonal/opposite directions, release/failure cleanup and
return-target lifecycle rewriting. The complete helper compiles against Android
API stubs and D8. Rewriting the supplied archive verifies the six purchase hooks,
two lifecycle hooks and unchanged supplied resources/native libraries. These
checks do not prove the on-device UI/JNI interaction; that needs a phone run.
