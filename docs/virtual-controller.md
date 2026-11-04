# Shizuku virtual controller

Goal: let PocketOuya's touch pad drive a real Android input device named "OUYA Game Controller",
so every engine sees a genuine controller (Unity 4 only lists real `InputDevice`s as joysticks;
OUYA-era scripts such as Soul Fjord's OuyaInput also pick button layouts by controller name).

Step 1 (this change) only proves the device can be created on the owner's phone:

- `VirtualPadService`: Shizuku user service (shell user), fixed operations only — create one
  uhid gamepad, send one input state, remove it, report status. Opens `/dev/uhid`, writes
  `UHID_CREATE2`, answers `GET_REPORT`/`SET_REPORT` so the kernel never waits, tracks
  start/open, and destroys the device on request, on service destroy, or when the process exits.
- `UhidGamepad`: packed `struct uhid_event` encoding and a standard HID game pad descriptor
  (16 buttons → Linux `BTN_A`…, X/Y and Z/Rz sticks, Brake/Accelerator triggers → Android
  BRAKE/GAS + LTRIGGER/RTRIGGER, hat → HAT_X/HAT_Y), 9-byte report. Vendor 0x1209 (pid.codes),
  product 0x0f0a; games see the name.
- `VirtualPadTestActivity` ("VIRTUAL CONTROLLER TEST (SHIZUKU)" on the main screen): creates the
  pad, checks Android lists it (sources, axes), presses A and moves the left stick, and shows the
  key/motion events that arrive from it.

Unverified until the phone test: whether the shell user may open `/dev/uhid` on Samsung Android
14 (SELinux/group), and how the device's keys map. Next steps after a pass: route the touch pad
to the service while a game runs (OUYA button order O/U/Y/A, d-pad), remove the pad when the
game closes, and check Unity 4 reports the name to Soul Fjord.

**Step 1 result (2026-11-04, S23, Android 14):** pass. Android listed "OUYA Game Controller"
(id 8, vendor 1209, product f0a) as an external gamepad and joystick with axes X Y Z RZ
LTRIGGER RTRIGGER BRAKE GAS HAT_X HAT_Y; the kernel started and opened it; A arrived as
`KEYCODE_BUTTON_A` down/up and the left stick as X 1.00.

## Step 2: touch pad drives the controller in games

- Report changed: the hat is replaced by key bits for the d-pad and Menu (HID keyboard usages
  Right/Left/Down/Up → `DPAD_*`, Application → `KEY_COMPOSE` → `MENU`), because the OUYA pad
  sent its d-pad as keys. Report stays 9 bytes.
- `VirtualPadState` (helper DEX, pure Java): touch keys → OUYA layout. O → bit 0 (`BUTTON_A`),
  A → 1 (`BUTTON_B`), U → 3 (`BUTTON_X`), Y → 4 (`BUTTON_Y`), L1/R1 → 6/7, Select/Start → 10/11;
  d-pad/Menu → key bits; axes 0/1 → left stick, 11/14 (look stick) → Z/RZ.
- `VirtualDeviceSink` (helper DEX): asks `content://io.github.hozdogus.pocketouya.virtualpad`
  `call("connect")` with a per-process token, sends `CREATE`, then one-way `INPUT` states. Until
  connected, or if Shizuku is unavailable, input goes to the in-game route.
- `VirtualPadProvider` (PocketOuya): answers only packages in PocketOuya's imported list, waits
  up to 3 s for Shizuku's binder in a cold process, requires Shizuku permission already granted,
  binds `VirtualPadService` as a daemon (tag `virtual_pad_game`) and calls owner-only `ALLOW(uid,
  token)`. The service accepts input from that uid and removes the pad when the token dies.
- Touch pad modes: "Auto" uses the virtual controller when the game has no engine-specific route
  (e.g. Soul Fjord); new explicit mode "Virtual controller (Shizuku)" for any game.

Untested on the phone: provider start from a game, Unity 4 reporting the name to Soul Fjord's
OuyaInput, and whether d-pad presses (also sent as left stick) clash with Soul Fjord's d-pad actions.

## Step 2 fix: Unity uses the OUYA pad's raw button order (2026-11-04)

Owner report with step 2 in Soul Fjord: U did nothing, A acted like O. Unity 4's key handler
passes `(keyCode, unicode, down, scanCode, deviceId)` to `nativeKeyState`, and the symptoms fit
Unity numbering joystick buttons by **scan code**. The real OUYA pad's key layout
(Vendor_2836_Product_0001.kl from an OUYA) is `0x130 BUTTON_A` (O), `0x131 BUTTON_X` (U),
`0x132 BUTTON_Y` (Y), `0x133 BUTTON_B` (A), `0x134/0x135` L1/R1, `0x136/0x137` L3/R3,
`0x138-0x13b` d-pad up/down/left/right, `0x13c/0x13d` L2/R2, `0x13e` Home, while step 2 sent the
generic Linux order (A at 0x131, U at 0x133): Soul Fjord saw A in U's slot and U in A's slot.
Stock Android ships no OUYA key layout (AOSP data/keyboards has none for vendor 2836), so the
virtual pad always gets Generic.kl and cannot carry both the OUYA scan codes and its key codes.
`VirtualPadState` therefore has two layouts: **OUYA scan order** for games containing
`com.unity3d.player.UnityPlayer` (report bit n = scan 0x130 + n, d-pad as buttons 8-11, Menu and
Start as 0x13e plus the MENU key), and the previous **Android key order** for other engines.

