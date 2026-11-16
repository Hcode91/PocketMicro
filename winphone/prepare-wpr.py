#!/usr/bin/env python3
"""Prepare the WPR checkout (winphone/wpr) to build as the Windows Phone edition of PocketMicro.

WPR's Android head is a .NET app. Rather than keeping a fork, this script makes the few changes
needed for it to become PocketMicro, every time CI builds:

  * PocketMicro's Java app (built by Gradle as an Android library, plus its dependencies) is added
    to the APK, and its MainActivity becomes the launcher. WPR's own screens are left in the code
    but have no way in.
  * The package name, label and icon become PocketMicro's, so it installs over PocketMicro.
  * WPR's game process is renamed :wpgame, because PocketMicro already has a :game process.
  * PocketMicro's bridge (winphone/Bridge/*.cs) is compiled into WPR's head.

Every edit checks its anchor and stops the build with a clear message if WPR's code moved.

Usage: prepare-wpr.py <wpr checkout> <pocketouya repo root> <folder with PocketMicro .aar/.jar files>
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
        fail(f"no PocketMicro .aar in {libs}")

    # 1. Project: PocketMicro's identity, its Java library and the bridge.
    csproj = head / "WPR.Platform.Android.csproj"
    addition = f"""
  <!-- PocketMicro (added by winphone/prepare-wpr.py) -->
  <PropertyGroup>
    <ApplicationId>com.hcode91.pocketmicro</ApplicationId>
    <ApplicationTitle>PocketMicro</ApplicationTitle>
    <!-- PocketMicro's manifest (and Shizuku's provider in it) uses ${{applicationId}}. -->
    <AndroidManifestPlaceholders>applicationId=com.hcode91.pocketmicro</AndroidManifestPlaceholders>
    <!-- PocketMicro's own minimum; its library manifest declares it too. -->
    <SupportedOSPlatformVersion>29.0</SupportedOSPlatformVersion>
    <!-- PocketMicro ships the full Guava (dexlib2 needs it). These opt-outs do not stop
         Xamarin.Google.Guava.ListenableFuture coming in, so app/build.gradle also strips
         ListenableFuture.class from PocketMicro's Guava jar. -->
    <XamarinGoogleGuavaListenableFutureOptOut>true</XamarinGoogleGuavaListenableFutureOptOut>
    <XamarinGoogleGuavaOptOut>true</XamarinGoogleGuavaOptOut>
    <!-- arm64 only. Set here, never as -p:RuntimeIdentifiers on the command line: a global
         property flows into every referenced library, which then look for their output under
         obj/.../android-arm64/ and fail with MSB3030 "Could not copy the file". -->
    <RuntimeIdentifiers>android-arm64</RuntimeIdentifiers>
    <!-- WPR-droid leaves WPR Hub out of this build (AndroidPlatform.cs): PocketMicro sends nothing
         to any server. -->
    <DefineConstants>$(DefineConstants);POCKETOUYA</DefineConstants>
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

    # 2. PocketMicro's MainActivity is the launcher, not WPR's.
    edit(head / "MainActivity.cs", "MainLauncher = true", "MainLauncher = false")

    # 3. PocketMicro already uses :game for its own engine.
    # Every occurrence: the class comment mentions it before the [Activity] attribute does.
    game = head / "GameActivity.cs"
    edit(game, 'Process = ":game"', 'Process = ":wpgame"', count=-1)
    if not re.search(r'\[Activity\([^\]]*Process = ":wpgame"', game.read_text(encoding="utf-8")):
        fail("GameActivity's [Activity] attribute was not moved to :wpgame")
    exits = head / "Online/AndroidProcessExits.cs"
    if exits.is_file():
        edit(exits, 'EndsWith(":game"', 'EndsWith(":wpgame"')

    # 4. Manifest: PocketMicro's package and label; its icon comes from the library.
    manifest = head / "Properties/AndroidManifest.xml"
    edit(manifest, 'package="com.wpr.android"', 'package="com.hcode91.pocketmicro"')
    edit(manifest, '<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
         '<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"')
    edit(manifest, '<application android:label="WPR" android:icon="@mipmap/ic_launcher"',
         '<application android:label="PocketMicro" android:icon="@mipmap/ic_launcher" android:allowBackup="false"'
         ' tools:replace="android:label,android:icon,android:allowBackup"')
    # (.NET Android writes allowBackup="true" when the app manifest leaves it out, which
    #  clashes with PocketMicro's library manifest saying false.)

    # 5. App resources override library ones of the same name: drop WPR's launcher icon so
    #    @mipmap/ic_launcher is PocketMicro's.
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
