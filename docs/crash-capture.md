# Capture game crashes on the phone

PocketMicro's game menu now has **Capture crash log**. Long-press a game in the
library to open it. This screen has two independent ways to collect evidence;
neither requires a computer. It does not repair a crash automatically.

## Automatic capture with optional Shizuku

Android does not let an ordinary app read other installed games' logs. Install
[Shizuku](https://shizuku.rikka.app/download/) and follow its
[wireless debugging setup](https://shizuku.rikka.app/guide/setup/). Android 11+
can start it on the phone without root or a computer. Shizuku typically needs
starting again after the phone reboots. PocketMicro requires Shizuku 13 or later.

1. In PocketMicro, long-press the game and select **Capture crash log**.
2. Tap **Shizuku access**, then authorize PocketMicro when Shizuku asks.
3. Tap **Capture and launch**. The game opens while a short capture runs.
4. Let the game crash, then return to the capture screen through Recent apps.
5. Tap **Stop / show log**. Review the result and use **Copy** or **Save**.

Capture reads only Android's crash buffer, beginning about one second before the
session, and ends after at most 60 seconds. A Shizuku user service executes one
fixed logcat command with no shell interpolation or arbitrary-command endpoint.
The app's own UID controls its capture Binder; Shizuku can stop the service.
The helper is non-daemon and is removed when the capture screen is destroyed.
Closing that screen ends the session, so copy/save a report before leaving it.
No continuously running logger or foreground monitoring service is installed.

This feature depends on the device allowing Shizuku's shell/root identity to
read the crash buffer. Actual Samsung permission prompts and log visibility
still require device testing. Some failures never produce a standard crash
block; custom process names can also prevent a package match. A message saying
no matching crash was found is not proof that no crash happened. It may mean
log access, timing, process matching or available buffers were insufficient.

## Import a Samsung bug report without Shizuku

1. Enable Developer options by tapping **Settings > About phone > Software
   information > Build number** seven times, if needed.
2. Reproduce the game crash and immediately choose **Developer options > Take
   bug report > Interactive report**. Samsung labels and availability can vary.
3. Save the completed report ZIP through its notification to My Files.
4. Open the game's **Capture crash log** screen in PocketMicro and tap
   **Import bug report**. Select that ZIP, or a previously extracted text file.
5. Review the extracted details, then copy/save those instead of sharing the
   whole device report.

ZIP import reads one `bugreport*.txt` member without extracting arbitrary paths.
The selected file is copied into private cache with a 128 MiB cap and deleted
after processing; stale import copies are cleaned when the screen next opens.
Filtering scans at most 64 MiB of decoded report characters and bounds line,
process-group and output sizes. Large/incompatible reports can therefore be
incomplete. Report ZIPs remain in the location where the user saved them.

## What is retained and shared

The capture helper holds a bounded, temporary crash-buffer snapshot in memory.
Filtering looks for AndroidRuntime `Process:` or native DEBUG `Cmdline:` / PID
headers identifying the selected package (including its `:subprocess` names).
Only matching groups are displayed/exported; unrelated tags and identified
other-app crashes are excluded. Crash blocks may still contain private data
from the selected game, so review the output before sharing it.

PocketMicro uploads nothing. **Copy** places the extracted text on Android's
clipboard; **Save** writes it only to the destination selected in Android's
file picker. A standalone capture report is not automatically persisted across
closing the screen or killing the launcher process.

No READ_LOGS grant, accessibility service, root installation, global input
permission or device-security change is added by PocketMicro. Shizuku authorization
is optional and only needed for automatic capture; the existing game conversion
and imported-bug-report path work without it. The dependency uses Shizuku API and
provider 13.1.5, with the provider protected by INTERACT_ACROSS_USERS_FULL as
recommended upstream.

## Validation

Plain-Java tests cover Java exceptions, native tombstones, subprocess matching,
PID reuse, unrelated package/tag exclusion, UID-bearing and year-bearing log
formats, oversized lines and input limits. App Java compiles against Android
API stubs and pinned Shizuku API/provider artifacts. CI checks dependencies,
manifest merging and APK packaging. No successful phone capture is claimed
until the owner tests the build on their device.
