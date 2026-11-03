# Global game launch log

Every launch through PocketOuya's Play, GameMaker gameplay, Engine test or manual
Capture-and-launch path now appends to **one shared on-device journal**. Each
entry begins with the game name, timestamp and source, followed by its log text.
There are no per-game log files. Launch requests, launch failures and session
return events are saved even if no crash occurs. Dispatching an Android intent
is recorded as a request, not evidence that gameplay succeeded.

Open **GLOBAL LOG / EXPORT** in the library. **Refresh** reads the newest 256 KiB
for display. **Export full log** opens Android's file picker and streams the entire
saved journal into one text file, including older entries absent from the preview.
Choose the destination; nothing is uploaded. Clear log removes the saved journal,
while an active capture may continue adding new output. The journal survives app
and device restarts, until cleared, app data is erased, or PocketOuya is uninstalled.
There is no automatic rotation or historical export truncation: long recordings
consume storage, and a full disk prevents further writes. Exported copies remain
where the user saved them and are not deleted by Clear log.

## Runtime and crash sources

- With Shizuku running and PocketOuya's existing access granted, launch starts a
  non-daemon, owner-UID-restricted reader before/as the game opens. Fixed logcat
  commands read main/system output for the target app's UID at all priorities and
  a separate crash-buffer stream. CrashLogFilter retains only Java/native blocks
  identifying that package, including subprocesses. Other apps' normal output is
  not written to the journal. A shared UID can contain more than one package.
- The built-in engine runs under PocketOuya's UID. Without Shizuku, a local reader
  can still capture that UID's normal logs, including the existing native engine
  Android log output. GameActivity also saves engine start/stop/quit output, and
  Engine test saves its returned report. Shizuku can add system-native crash output.
- On returning from the built-in engine, Android 11+ historical exit information
  for our own :game process is inspected for crashes/ANRs since that launch.
  Available exit traces are saved up to 512 KiB, with truncation marked. Binary
  traces (such as native tombstone protobufs) are labelled and preserved as
  base64 rather than interpreted as text. Android
  does not provide this cross-app capability for arbitrary installed games.
- Without Shizuku access, external games still get launch/status entries, with an
  explicit unavailable-output explanation. No root, READ_LOGS permission or
  security bypass is added. Use **Shizuku access** / **Setup guide** in the log
  screen, then launch again. Existing manual crash capture/import remains available.

An app foreground service saves output while another game is in front. It stops
when PocketOuya's library is resumed, the capture notification's Stop action is
used, a new launch replaces it, capture fails, or Android stops/times out the
service. The notification opens the global viewer. It is subject to Android's
notification permission and foreground-service limits; target-35 dataSync time
limits also apply. No service is auto-started at device boot. If the process is
killed abruptly, an end marker may be absent; saved output remains on disk.

## Completeness and privacy

This records available emitted game logs, not every internal game operation or a
full device-wide bug report. Logcat can omit or truncate output; Android access,
service/OS interruptions, PID/UID scope, buffer retention and native crash timing
can leave gaps. Reader polling is every 250 ms. The normal-output transfer queue
holds 128 Ki characters and reports overflow; crash input is a rolling 256 Ki
character window, then uses the existing bounded crash grouping/output filter.
Previously journaled output is not removed when the reader window rolls. Final
shutdown drains available buffered output, but no zero-loss guarantee is claimed.
Capture status/errors are appended under the game name; missing crash output
never establishes a successful run. Export includes **all saved content**, not
output that Android never made available or that a marked capture limit dropped.

Storage is app-private files/logs/global-launch.log. A stable sidecar file lock
serializes append, clear and full export across the launcher and :game processes;
export is a consistent snapshot while the lock is held. The preview alone can be
a partial live view. Logs may contain private game/device data: review exports
before sharing. No network upload or third-party telemetry is introduced.

## Validation

Host tests check one shared file across multiple game names, successful output
and crash text, reopening/persistence, concurrent writers, Unicode, clear/reuse
and byte-for-byte full export beyond the preview limit. Existing package/native
crash isolation tests still pass. Android Java compilation and merged-manifest/CI
packaging checks cover the integration. Actual Shizuku streaming, foreground
service behavior and export-provider behavior still require a phone run.


## Fast-return startup recovery

The owner's first multi-game report confirmed normal output, Java crash output
and engine-report capture, but several fast-return launches had only headings:
they returned before the Shizuku reader connected. Shutdown now preserves a
pending reader for up to its 8-second connection deadline, then reads logcat from
the original launch timestamp and gives startup output a 750 ms drain interval.
An already-started reader also gets a short final drain before teardown. A new
launch replacing an unconnected session is explicitly marked as a capture gap.

Expected read interruptions caused by deliberately closing logcat during shutdown
are not reported as capture errors; unexpected active-reader exceptions remain
visible. Service shutdown uses the latest delivered start ID so a delayed reader
cannot leave an idle service behind after a newer Stop request. These changes
compile and preserve the file/export tests; fast-return recovery still needs an
on-device repeat. They cannot recover output already evicted from Android's buffer.


## Full crash dumps (Android crash record)

When a launched game's session ends, the Shizuku reader also asks Android how
the game's process exited (`ActivityManager.getHistoricalProcessExitReasons`
for that package; the shell identity Shizuku runs as holds the DUMP permission
this needs). Only crashes, native crashes and ANRs at or after the launch time
are considered. For a native crash Android attaches the full crash dump (the
tombstone record). It is streamed back through a pipe, decoded by
`TombstoneText` and appended to the global log as **Android crash record**:
signal, cause, registers, backtraces of every thread, memory near the crashing
thread's registers and the memory map. The record's log buffers and open-file
list are deliberately not included. ANR traces are kept as text; a dump that
cannot be decoded is kept as base64 so it is not lost. Android writes the dump a
moment after the crash, so a missing dump is retried for a few seconds.

This works for already-installed games without reconverting them, but needs
Shizuku like the rest of game-output capture. `TombstoneTextTest` covers the
decoder with a hand-built record; the Shizuku call itself needs a phone run.
