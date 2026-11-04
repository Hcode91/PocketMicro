# Shizuku virtual controller (step 1: device test)

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
