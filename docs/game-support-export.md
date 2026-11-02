# Game log and installed APK export

Open a game's menu → **Capture log + export APK**. This opens its log screen and
starts capture/launch when Shizuku access is ready. After the game crashes or you
return, PocketOuya stops the capture and displays the package-filtered crash log.
You can also use **Capture and launch** to retry, or import a bug report without
Shizuku. This is the existing crash-buffer capture (up to 60 seconds), not proof
that a game ran successfully and not a capture of all runtime output.

Choose **Export log + APK**, then a destination folder. A new game-named subfolder
contains:

- `crash-log.txt`: game name, package, export timestamp and captured/imported log.
  No capture, unavailable final capture and no identified crash remain explicit.
- `base.apk` if its size is at most **20,000,000 bytes**. Otherwise it becomes raw
  byte parts `base.apk.001`, `.002`, …, each at most 20,000,000 bytes.
- For split installations, each additional component as `split-1.apk`, etc.,
  subject to the same per-file splitting. All components are needed for diagnosis.
- `EXPORT-COMPLETE.txt`: exact sizes, part and full APK SHA-256 checksums,
  reassembly instructions and exclusions. It is written only after every copy
  succeeds. Files without this completion marker are an incomplete export.

Send the log, all APK files or parts, and the completion file. Join numbered parts
in numeric order to recover their APK, without decompressing or altering bytes.
This export preserves the installed files, signatures and embedded assets; if
the game was converted, the installed copy contains that conversion. It does not
recover the original download, include external OBB/downloads/saves/private app
data or reinstall split packages automatically. It sends nothing automatically
and does not upload game files to GitHub.

Export streams fixed buffers to Android's document provider off the UI thread.
Provider errors and source file truncation/change fail the operation. Failed
folders are deleted when the provider allows it; otherwise the UI asks the user
to delete the incomplete folder. If Android kills the process while exporting,
an incomplete folder can remain without a completion marker. Export should stay
in the foreground until complete. The global saved log/export remains separate.

Host tests cover exact threshold and multiple-part boundaries, byte-for-byte
reassembly, SHA-256, split APK components, source preservation, invalid/missing/
duplicate paths, write failure and concurrent source truncation. Android-core
Java compilation passes. On-device Shizuku, folder-provider and touch input flows
have not been exercised in this environment.
