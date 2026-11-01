# Embedded touch controls

Choose **Touch controls — OUYA / Android input** when converting an original complete APK, or **Startup repairs + touch — Unity / Marmalade / AIR / Frima** when the game also needs a matching native
repair. Install the converted result and use ordinary **Play**. Updating
PocketOuya does not replace helpers already embedded in installed games: reimport
the original APK after updating PocketOuya. Do not reconvert an already-injected APK.

The game-owned view has directions, O/U/Y/A, L1/R1, Start, Select and Menu, with
per-finger held state. Hide, pause, focus loss, cancellation and detachment release
held input. Buttons block descendant keyboard focus so the pad cannot take focus
from the game. Activity tracking avoids a queued attachment after that Activity
has paused. No accessibility service, system overlay permission or Shizuku input
injection is used.

## Input delivery

The layout is shared, but engines receive input differently:

- OUYA Unity handlers: player-one native key and axis callbacks. Knightmare Tower
  input was previously confirmed by the owner; other versions remain unverified.
- Marmalade Activity: direct public key callbacks, including subclass overrides.
  The inspected Vector route reaches LoaderKeyboard's native keyboard callback
  without a physical InputDevice. Directions are digital keys; analog axes are
  not supplied by this adapter. **Auto (Marmalade keys)** selects this route.
- Generic Android: gamepad key/axis events, using Unity's injectEvent when present
  and otherwise Activity dispatch. Games requiring a physical device may ignore
  virtual events.
- **Keyboard**: arrows, O=Space, U=X, Y=Y, A=Escape, L1=Q, R1=E, Start=Enter,
  Select=Tab, Menu=Android Menu. Marmalade uses its direct callbacks for this mode.

Mode selection is remembered per game. Try Auto first, then Keyboard if the game
ignores it. Engine test is separate and does not execute these converted Java
activities. Unsupported or protected DEX may prevent injection; read the report.
A common OUYA controller scheme alone cannot guarantee compatibility across all
engines, NativeActivity implementations, or games requiring analog input.

## Validation and remaining work

Host tests exercise Marmalade callback overrides, action/down-time/source/device
fields, keyboard mapping, held ownership and release, native-linkage failure
handling, and structural DEX detection. Existing lifecycle, pad state, native OUYA,
purchase and runtime-repair suites also pass. The bridge compiles against Android
and through D8; app Java compiles separately. Combined conversion of the supplied
Vector partial archive preserves unrelated payloads and validates rewritten DEX.

The owner confirmed Vector 1.0.7 launch and touch input with the Marmalade
adapter and focus changes. Other games/builds remain unverified. A callback returning without an exception is not proof that a
game consumed it. Game startup, assets, rendering, analog-only input and further
native failures can still block testing. No game binaries are distributed here.


## PocketOuya GameMaker engine pad

**Play with GameMaker engine + touch (beta)** opens the existing translated
32-bit GameMaker runtime with its own game-owned touch view. It does not inject
the conversion bridge or dispatch synthetic Android events. The renderer and
original engine binaries stay unchanged; Engine.key queues presses for delivery
before a runner frame. Engine test is an inspection/boot trace, not this screen.

Directions use the existing runner D-pad key path. The visible face/shoulder/menu
labels show the actual legacy runner key binding: O=Z, U=X, Y=C, A=Space, L1=Shift,
R1=Ctrl, Start=Enter, Select=Tab, Menu=Escape. **Controls** remaps each nondirectional
button to a listed key or original OUYA button code and persists per game. This
is keyboard-style input to the inspected runner, not analog-controller emulation.
Different games may require different bindings; no universal action layout is claimed.

Per-key ownership combines physical buttons, stick directions and touch without
premature key-up events. Each touch button supports held fingers, drag-out and
cancellation. Hide, mapping dialogs, window focus loss, pause, detach and engine
stop clear held controls. Queued accessibility pulses are cancelled on release.
Buttons do not take game keyboard focus. Controls become enabled only after engine
startup and while the game is resumed/focused. Empty pad areas leave the game view
available. No extra permission, Shizuku, service or external overlay is involved.

New host tests cover mixed-source ownership, repeat suppression, multiple keys,
release/repress and idempotent cleanup. Full Android Java compilation passes.
Actual Maldita touch input and view layout still require verification on a phone;
no new successful gameplay run is inferred from these tests.
