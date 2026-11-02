# Legacy OUYA JSON input and right-stick controls

The supplied Monocle Man APK reaches gameplay, but the old Android fallback pad
provides no registered controller for its managed SDK. Inspection shows:

- The launcher owns `GenericSendMessage` overloads that serialize input/device
  DTOs with Gson and forward JSON to `OuyaGameObject` via `UnitySendMessage`.
- Its physical device snapshot excludes Android virtual devices. The old pad's
  virtual-keyboard ID therefore has no matching device descriptor.
- Managed `InputListener` returns without dispatch when the input's device ID
  is absent from the `onSetDevices` snapshot. It then selects mappings using the
  device name. Button input reads nested `KeyEvent.mKeyCode`/`mRepeatCount`;
  movement/look read `AxisX`, `AxisY`, `AxisZ` and `AxisRZ`.
- The Java producer numbers managed players from 1; its Android `playerNum`
  is independently numbered from 0. These are not interchangeable with the
  separate modern OUYA JNI handler's player-index argument.

These facts explain an incompatibility in the old fallback path. They do not
prove which Android callback consumed every event on the phone or establish
successful input response with this change.

## Shared route

Conversion recognizes this SDK contract from its activity, DTO field types,
input gates, device enumeration, Gson/Unity sender references, message target,
callback names and device-snapshot call. It adds separate legacy lifecycle hooks
only when all these conditions match. There is no game package ID, fingerprint,
file offset, native patch or managed-assembly modification in this selection.
Wrong field types/callbacks/message targets retain the ordinary fallback.
Modern OUYA native and Marmalade routes retain priority and existing behavior.

The legacy bridge announces an in-process `OUYA Game Controller` descriptor for
managed player 1, then sends the SDK's existing JSON button/axis callbacks. It
preserves physical descriptors from the game's current snapshot and refuses a
virtual-ID collision. It respects the game's input/Unity enable flags. Device
announcement precedes events so an initial Android resume before GameObject
creation does not permanently lose registration. This does not register a real
Android device or guarantee device-presence checks outside this managed protocol.
Physical player-1 input and virtual player-1 input can overlap the same game state.
No purchase/developer identity, SDK initialization or receipt logic is changed.

The first use of this newly matched route resets an old Android/Keyboard pad
selection to **Auto (legacy OUYA JSON)** once. Later manual mode selections remain
respected. The selected route is logged with `PocketOuyaControls`.

## Look control and cleanup

The directional buttons continue to send left-stick X/Y and digital directions.
On sufficiently wide screens, a center-bottom **Look / RS** drag control sends
right-stick Z/RZ independently. Values are radially clamped with a small center
dead zone; repeated positions are suppressed. Android fallback now carries these
axes too, while native OUYA already forwards axis IDs. The look control is hidden
for the digital-only Marmalade route and narrow layouts. Keyboard mode does not
provide analog look. Release, cancel, hide, focus loss, pause and mode changes
neutralize held sticks/buttons. Cleanup still attempts every release if a sink
fails.

## Verification and use

Generated SDK fixtures use two unrelated activity namespaces; wrong field,
callback and target cases reject the legacy route. Tests cover lifecycle routing,
physical snapshots, virtual device/player numbering, nested button JSON, both
sticks, disabled gates, ID collision and cleanup. They use a synthetic message
consumer, not the game's managed runtime. Input-state tests cover diagonal
clamping, dead zones, repeat suppression, independent movement/look and cleanup
when a release fails. Modern native and Marmalade/input/purchase suites remain.

The actual supplied Monocle APK matches the new route. Full reconversion with the
freshly D8-built bridge preserves the original APK, all 29 assets, the CPU getter
guard and the previous emulated native repair bytes. The legacy lifecycle calls
resolve to the new helper DEX. Java 8 bridge compilation, D8 min-API23, Android-core
app compilation and host/generated suites pass. Phone touch response, View
multitouch dispatch and gameplay remain unverified until the owner tests them.
All 17 legacy native profiles/four frozen native algorithm sources are unchanged.

Install the updated PocketOuya, then reconvert the **complete original Monocle
APK** with **Experimental Unity embedded-runtime repair + touch**. Updating
PocketOuya alone cannot replace code already embedded in an installed game. The
conversion report should say **1 use the legacy OUYA JSON controller route**.
Use Auto, arrows for movement, O for jump and Look / RS for the camera. Existing
native repairs and the CPU guard remain part of this conversion. If input still
fails, capture the game's runtime/global log including `PocketOuyaControls`.
