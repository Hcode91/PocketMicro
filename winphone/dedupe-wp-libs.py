#!/usr/bin/env python3
"""Remove from PocketMicro's libraries every Java class the WPR build already ships.

Usage: dedupe-wp-libs.py <project.assets.json> <wpr dir> <libs dir>

The Windows Phone edition puts PocketMicro's .aar/.jar files (libs dir) next to the Java
libraries WPR gets from NuGet packages and from its own projects. D8 rejects any class
that appears twice ("Type X is defined multiple times") and only reports the first one,
so this script finds them all up front. For every duplicate, the WPR copy is kept and
PocketMicro's copy is removed; a library left with no classes is deleted. Run it after
`dotnet restore` (it needs project.assets.json) and before the build. Only the Windows
Phone edition's copies are touched; the normal PocketMicro APK never sees this folder.

It also warns about native libraries (.so) present on both sides, which do not fail the
build but mean only one copy ends up in the APK.
"""
import io
import json
import pathlib
import sys
import zipfile


def classes_in_jar(data):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        return {n for n in z.namelist() if n.endswith(".class") and not n.startswith("META-INF/")}


def scan(path):
    """Yield (inner jar name or None, set of classes, set of .so names) for a .jar or .aar."""
    if path.suffix == ".jar":
        yield None, classes_in_jar(path.read_bytes()), set()
        return
    with zipfile.ZipFile(path) as z:
        so = {pathlib.PurePosixPath(n).name for n in z.namelist() if n.startswith("jni/") and n.endswith(".so")}
        first = True
        for n in z.namelist():
            if n == "classes.jar" or (n.startswith("libs/") and n.endswith(".jar")):
                yield n, classes_in_jar(z.read(n)), so if first else set()
                first = False
        if first:
            yield None, set(), so


def strip_jar(data, drop):
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(data)) as src, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if info.filename not in drop:
                dst.writestr(info, src.read(info.filename))
    return out.getvalue()


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    assets = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8-sig"))
    wpr = pathlib.Path(sys.argv[2]).resolve()
    libs = pathlib.Path(sys.argv[3]).resolve()

    # Everything the WPR side brings: resolved NuGet packages plus .aar/.jar files in WPR's tree.
    theirs = []
    roots = [pathlib.Path(p) for p in assets.get("packageFolders", {})]
    for lib in assets.get("libraries", {}).values():
        if lib.get("type") != "package":
            continue
        for root in roots:
            d = root / lib["path"]
            if d.is_dir():
                theirs += [f for f in d.rglob("*") if f.suffix in (".jar", ".aar") and f.is_file()]
                break
    theirs += [f for f in wpr.rglob("*") if f.suffix in (".jar", ".aar") and f.is_file()
               and "/obj/" not in f.as_posix() and "/bin/" not in f.as_posix()]

    their_classes, their_so = {}, {}
    for f in theirs:
        try:
            for _, classes, so in scan(f):
                for c in classes:
                    their_classes.setdefault(c, f.name)
                for s in so:
                    their_so.setdefault(s, f.name)
        except zipfile.BadZipFile:
            print(f"dedupe: skipping unreadable {f}")
    print(f"dedupe: {len(their_classes)} classes in {len(theirs)} WPR-side libraries")

    removed_total = 0
    for f in sorted(libs.iterdir()):
        if f.suffix not in (".jar", ".aar"):
            continue
        if f.suffix == ".jar":
            classes = classes_in_jar(f.read_bytes())
            dup = classes & their_classes.keys()
            if not dup:
                continue
            removed_total += len(dup)
            sources = sorted({their_classes[c] for c in dup})
            if dup == classes:
                f.unlink()
                print(f"dedupe: deleted {f.name} (all {len(dup)} classes already in {', '.join(sources)})")
            else:
                f.write_bytes(strip_jar(f.read_bytes(), dup))
                print(f"dedupe: {f.name}: removed {len(dup)} of {len(classes)} classes "
                      f"already in {', '.join(sources)} (e.g. {sorted(dup)[0]})")
            continue

        # .aar: rewrite classes.jar and libs/*.jar inside it.
        with zipfile.ZipFile(f) as z:
            entries = [(i, z.read(i.filename)) for i in z.infolist()]
        changed = False
        rebuilt = []
        for info, data in entries:
            n = info.filename
            if n.endswith(".so") and n.startswith("jni/"):
                name = pathlib.PurePosixPath(n).name
                if name in their_so:
                    print(f"dedupe: warning: {f.name} and {their_so[name]} both ship {name}")
            if n == "classes.jar" or (n.startswith("libs/") and n.endswith(".jar")):
                dup = classes_in_jar(data) & their_classes.keys()
                if dup:
                    removed_total += len(dup)
                    changed = True
                    sources = sorted({their_classes[c] for c in dup})
                    print(f"dedupe: {f.name}!{n}: removed {len(dup)} classes already in "
                          f"{', '.join(sources)} (e.g. {sorted(dup)[0]})")
                    data = strip_jar(data, dup)
            rebuilt.append((info, data))
        if changed:
            out = io.BytesIO()
            with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
                for info, data in rebuilt:
                    dst.writestr(info, data)
            f.write_bytes(out.getvalue())

    print(f"dedupe: done, {removed_total} duplicate classes removed from PocketMicro's libraries")


if __name__ == "__main__":
    main()
