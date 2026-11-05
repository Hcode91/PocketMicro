#!/usr/bin/env python3
"""Prepare the WPR checkout (winphone/wpr) to build as the Windows Phone edition of PocketOuya.

WPR's Android head is a .NET app. Rather than keeping a fork, this script makes the few changes
needed for it to become PocketOuya, every time CI builds:

  * PocketOuya's Java app (built by Gradle as an Android library, plus its dependencies) is added
    to the APK, and its MainActivity becomes the launcher. WPR's own screens are left in the code
    but have no way in.
  * The package name, label and icon become PocketOuya's, so it installs over PocketOuya.
  * WPR's game process is renamed :wpgame, because PocketOuya already has a :game process.
  * PocketOuya's bridge (winphone/Bridge/*.cs) is compiled into WPR's head.

Every edit checks its anchor and stops the build with a clear message if WPR's code moved.

Usage: prepare-wpr.py <wpr checkout> <pocketouya repo root> <folder with PocketOuya .aar/.jar files>
"""
import pathlib
import re
import sys


def fail(message):
    sys.exit("prepare-wpr: " + message)


def edit(path, old, new, count=1):
    text = path.read_text(encoding="utf-8-sig")
    if old not in text:
        fail(f"anchor not found in {path}: {old[:80]!r} (WPR changed; update winphone/prepare-wpr.py)")
    path.write_text(text.replace(old, new, count), encoding="utf-8")


def main():
    if len(sys.argv) != 4:
        fail(__doc__)
    wpr = pathlib.Path(sys.argv[1]).resolve()
    repo = pathlib.Path(sys.argv[2]).resolve()
    libs = pathlib.Path(sys.argv[3]).resolve()
    head = wpr / "Src/Platforms/WPR.Platform.Android"
    if not (head / "WPR.Platform.Android.csproj").is_file():
        fail(f"no WPR Android head under {wpr}")
    if not any(libs.glob("*.aar")):
        fail(f"no PocketOuya .aar in {libs}")

    # 1. Project: PocketOuya's identity, its Java library and the bridge.
    csproj = head / "WPR.Platform.Android.csproj"
    addition = f"""
  <!-- PocketOuya (added by winphone/prepare-wpr.py) -->
  <PropertyGroup>
    <ApplicationId>io.github.hozdogus.pocketouya</ApplicationId>
    <ApplicationTitle>PocketOuya</ApplicationTitle>
    <!-- PocketOuya's manifest (and Shizuku's provider in it) uses ${{applicationId}}. -->
    <AndroidManifestPlaceholders>applicationId=io.github.hozdogus.pocketouya</AndroidManifestPlaceholders>
    <!-- PocketOuya's own minimum; its library manifest declares it too. -->
    <SupportedOSPlatformVersion>29.0</SupportedOSPlatformVersion>
    <!-- PocketOuya ships the full Guava (dexlib2 needs it), which already contains
         ListenableFuture: two copies would be a duplicate class. -->
    <XamarinGoogleGuavaListenableFutureOptOut>true</XamarinGoogleGuavaListenableFutureOptOut>
    <XamarinGoogleGuavaOptOut>true</XamarinGoogleGuavaOptOut>
    <!-- arm64 only. Set here, never as -p:RuntimeIdentifiers on the command line: a global
         property flows into every referenced library, which then look for their output under
         obj/.../android-arm64/ and fail with MSB3030 "Could not copy the file". -->
    <RuntimeIdentifiers>android-arm64</RuntimeIdentifiers>
  </PropertyGroup>
  <ItemGroup>
    <Compile Include="{repo.as_posix()}/winphone/Bridge/*.cs" />
    <AndroidLibrary Include="{libs.as_posix()}/*.aar" Bind="false" />
    <AndroidLibrary Include="{libs.as_posix()}/*.jar" Bind="false" />
  </ItemGroup>
</Project>"""
    text = csproj.read_text(encoding="utf-8-sig")
    if "winphone/prepare-wpr.py" in text:
        fail("checkout already prepared; use a fresh one")
    idx = text.rstrip().rfind("</Project>")
    if idx < 0:
        fail("csproj has no </Project>")
    csproj.write_text(text[:idx] + addition.lstrip("\n") + "\n", encoding="utf-8")

    # 2. PocketOuya's MainActivity is the launcher, not WPR's.
    edit(head / "MainActivity.cs", "MainLauncher = true", "MainLauncher = false")

    # 3. PocketOuya already uses :game for its own engine.
    edit(head / "GameActivity.cs", 'Process = ":game"', 'Process = ":wpgame"')
    exits = head / "Online/AndroidProcessExits.cs"
    if exits.is_file():
        edit(exits, 'EndsWith(":game"', 'EndsWith(":wpgame"')

    # 4. Manifest: PocketOuya's package and label; its icon comes from the library.
    manifest = head / "Properties/AndroidManifest.xml"
    edit(manifest, 'package="com.wpr.android"', 'package="io.github.hozdogus.pocketouya"')
    edit(manifest, '<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
         '<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"')
    edit(manifest, '<application android:label="WPR" android:icon="@mipmap/ic_launcher"',
         '<application android:label="PocketOuya" android:icon="@mipmap/ic_launcher" android:allowBackup="false"'
         ' tools:replace="android:label,android:icon,android:allowBackup"')
    # (.NET Android writes allowBackup="true" when the app manifest leaves it out, which
    #  clashes with PocketOuya's library manifest saying false.)

    # 5. App resources override library ones of the same name: drop WPR's launcher icon so
    #    @mipmap/ic_launcher is PocketOuya's.
    removed = 0
    for icon in (head / "Resources").glob("mipmap-*/ic_launcher.*"):
        icon.unlink()
        removed += 1
    if removed == 0:
        fail("no WPR launcher icon found to replace")

    bridge = repo / "winphone/Bridge"
    if not any(bridge.glob("*.cs")):
        fail(f"no bridge sources in {bridge}")
    print(f"prepare-wpr: ready ({removed} WPR icon files replaced, libraries from {libs})")


if __name__ == "__main__":
    main()
