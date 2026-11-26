#!/usr/bin/env python3
"""Writes notices/THIRD_PARTY_NOTICES.txt: the licence notices for everything PocketMicro ships.

The app shows that file under Manage -> Licences (it is packaged as an asset). Rerun this after
adding or removing a dependency, and commit the output.

Licence texts are read from where they already live: the vendored licence files in the WPR-droid
checkout (winphone/wpr, or a path given as the first argument) and the standard texts in
/usr/share/common-licenses (any Debian/Ubuntu machine, including GitHub's runners).

Usage: scripts/make-notices.py [path to a WPR-droid checkout]
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
WPR = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "winphone/wpr"
COMMON = pathlib.Path("/usr/share/common-licenses")
FNA = WPR / "Src/Backends/FNA.Platform"


def read(path):
    return pathlib.Path(path).read_text(encoding="utf-8", errors="replace").strip()


MIT = """Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE."""

ZERO_BSD = """Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
PERFORMANCE OF THIS SOFTWARE."""

ISC = """Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE."""

BSD3 = """Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE."""

BOOST = """Boost Software License - Version 1.0 - August 17th, 2003

Permission is hereby granted, free of charge, to any person or organization
obtaining a copy of the software and accompanying documentation covered by
this license (the "Software") to use, reproduce, display, distribute,
execute, and transmit the Software, and to prepare derivative works of the
Software, and to permit third-parties to whom the Software is furnished to
do so, all subject to the following:

The copyright notices in the Software and this entire statement, including
the above license grant, this restriction and the following disclaimer,
must be included in all copies of the Software, in whole or in part, and
all derivative works of the Software, unless such copies or derivative
works are solely in the form of machine-executable object code generated by
a source language processor.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE, TITLE AND NON-INFRINGEMENT. IN NO EVENT
SHALL THE COPYRIGHT HOLDERS OR ANYONE DISTRIBUTING THE SOFTWARE BE LIABLE
FOR ANY DAMAGES OR OTHER LIABILITY, WHETHER IN CONTRACT, TORT OR OTHERWISE,
ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE."""

LLVM_EXCEPTION = """---- LLVM Exceptions to the Apache 2.0 License ----

As an exception, if, as a result of your compiling your source code, portions
of this Software are embedded into an Object form of such source code, you
may redistribute such embedded portions in such Object form without complying
with the conditions of Sections 4(a), 4(b) and 4(d) of the License.

In addition, if you combine or link compiled forms of this Software with
software that is licensed under the GPLv2 ("Combined Software") and if a
court of competent jurisdiction determines that the patent provision (Section
3), the indemnity provision (Section 9) or other Section of the License
conflicts with the conditions of the GPLv2, you may retroactively and
prospectively choose to deem waived or otherwise exclude such Section(s) of
the License, but only in their entirety and only with respect to the Combined
Software."""

# (name, copyright, licence, source, text or None when the full text is at the end)
BOTH = [
    ("apksig", "Copyright (C) The Android Open Source Project", "Apache License 2.0",
     "https://android.googlesource.com/platform/tools/apksig", None),
    ("smali / dexlib2", "Copyright (c) Ben Gruver (JesusFreke) and Google Inc.", "BSD 3-Clause",
     "https://github.com/JesusFreke/smali", BSD3),
    ("Guava", "Copyright (C) The Guava Authors", "Apache License 2.0",
     "https://github.com/google/guava", None),
    ("Shizuku API", "Copyright (c) RikkaW", "MIT", "https://github.com/RikkaApps/Shizuku-API", MIT),
    ("dynarmic (32-bit ARM translator)", "Copyright (c) 2016 MerryMage and the dynarmic contributors",
     "0BSD", "https://github.com/azahar-emu/dynarmic", ZERO_BSD),
    ("{fmt}", "Copyright (c) 2012 - present, Victor Zverovich and {fmt} contributors", "MIT",
     "https://github.com/fmtlib/fmt", MIT),
    ("mcl", "Copyright (c) 2022 merryhime", "MIT", "https://github.com/azahar-emu/mcl", MIT),
    ("oaknut", "Copyright (c) 2022 merryhime", "MIT", "https://github.com/merryhime/oaknut", MIT),
    ("robin-map", "Copyright (c) 2017 Thibaut Goetghebuer-Planchon", "MIT",
     "https://github.com/Tessil/robin-map", MIT),
    ("Boost (headers)", "Copyright the Boost authors", "Boost Software License 1.0",
     "https://github.com/azahar-emu/ext-boost", BOOST),
]

WINPHONE = [
    ("WPR (Windows Phone Runtime), via WPR-droid", None, "MIT",
     "https://github.com/Bubbleshum/WPR and https://github.com/Hcode91/WPR-droid",
     read(WPR / "LICENSE")),
    ("FNA", None, "Microsoft Public License (Ms-PL)", "https://github.com/FNA-XNA/FNA",
     read(FNA / "licenses/LICENSE")),
    ("Mono.Xna (parts of FNA)", None, "MIT", "https://github.com/FNA-XNA/FNA",
     read(FNA / "licenses/monoxna.LICENSE")),
    ("LzxDecoder (part of FNA)", None, "LGPL 2.1 / MS-PL (see text)", "https://github.com/FNA-XNA/FNA",
     read(FNA / "licenses/lzxdecoder.LICENSE")),
    ("FNA3D", None, "zlib", "https://github.com/FNA-XNA/FNA3D", read(FNA / "lib/FNA3D/LICENSE")),
    ("MojoShader (part of FNA3D)", None, "zlib", "https://github.com/icculus/mojoshader",
     read(FNA / "lib/FNA3D/MojoShader/LICENSE.txt")),
    ("FAudio", None, "zlib", "https://github.com/FNA-XNA/FAudio", read(FNA / "lib/FAudio/LICENSE")),
    ("SDL2", None, "zlib", "https://github.com/libsdl-org/SDL", read(FNA / "lib/SDL2/LICENSE.txt")),
    ("SDL2#", None, "zlib", "https://github.com/flibitijibibo/SDL2-CS", read(FNA / "lib/SDL2-CS/LICENSE")),
    ("Theorafile", None, "zlib", "https://github.com/FNA-XNA/Theorafile",
     read(FNA / "lib/Theorafile/licenses/LICENSE")),
    ("libogg (in Theorafile)", None, "BSD 3-Clause", "https://xiph.org",
     read(FNA / "lib/Theorafile/licenses/libogg.LICENSE")),
    ("libtheora (in Theorafile)", None, "BSD 3-Clause", "https://xiph.org",
     read(FNA / "lib/Theorafile/licenses/libtheora.LICENSE")),
    ("libvorbis (in Theorafile)", None, "BSD 3-Clause", "https://xiph.org",
     read(FNA / "lib/Theorafile/licenses/libvorbis.LICENSE")),
    ("cubeb", "Copyright (c) Mozilla Foundation and the cubeb contributors", "ISC",
     "https://github.com/mozilla/cubeb", ISC),
    ("FFmpegKit (audio package)", "Copyright (c) Taner Sener (ARTHENICA)", "LGPL 3.0",
     "https://github.com/arthenica/ffmpeg-kit (source of the build shipped: release 4.5.1-lts)", None),
    ("FFmpeg", "Copyright (c) the FFmpeg developers", "LGPL 2.1 or later",
     "https://ffmpeg.org (built by FFmpegKit 4.5.1-lts; its build scripts give the exact source and options)", None),
    ("Audio libraries in FFmpegKit's audio package",
     "chromaprint, LAME, libilbc, libvorbis, opencore-amr, Opus, Shine, SoX Resampler, Speex, TwoLAME, vo-amrwbenc",
     "each under its own licence (LGPL, BSD, MIT or Apache 2.0)",
     "https://github.com/arthenica/ffmpeg-kit/wiki/External-Libraries", None),
    ("dynarmic, Zydis and Zycore (in wprcpu, WPR's ARM translator)",
     "Copyright (c) MerryMage and the dynarmic contributors; Copyright (c) zyantific", "0BSD and MIT",
     "https://github.com/merryhime/dynarmic, https://github.com/zyantific/zydis", ZERO_BSD + "\n\n" + MIT),
    (".NET runtime and libraries", "Copyright (c) .NET Foundation and Contributors", "MIT",
     "https://github.com/dotnet/runtime (its THIRD-PARTY-NOTICES.TXT lists what it includes)", MIT),
    ("Entity Framework Core, System.Reactive", "Copyright (c) .NET Foundation and Contributors", "MIT",
     "https://github.com/dotnet/efcore, https://github.com/dotnet/reactive", MIT),
    ("Mono.Cecil", "Copyright (c) 2008 - 2015 Jb Evain; Copyright (c) 2008 - 2011 Novell, Inc.", "MIT",
     "https://github.com/jbevain/cecil", MIT),
    ("Newtonsoft.Json", "Copyright (c) 2007 James Newton-King", "MIT",
     "https://github.com/JamesNK/Newtonsoft.Json", MIT),
    ("NVorbis", "Copyright (c) 2020 Andrew Ward", "MIT", "https://github.com/NVorbis/NVorbis", MIT),
    ("Avalonia (Android theme resources)", "Copyright (c) AvaloniaUI OU", "MIT",
     "https://github.com/AvaloniaUI/Avalonia", MIT),
    ("SQLitePCLRaw", "Copyright (c) Zumero, LLC", "Apache License 2.0",
     "https://github.com/ericsink/SQLitePCL.raw", None),
    ("SQLite", "The authors disclaim copyright; SQLite is in the public domain", "Public domain",
     "https://sqlite.org", None),
    ("AndroidX libraries", "Copyright (C) The Android Open Source Project", "Apache License 2.0",
     "https://developer.android.com/jetpack/androidx", None),
    ("Firebase Cloud Messaging", "Copyright Google LLC", "Apache License 2.0 (Google Play services parts: "
     "Android Software Development Kit License)", "https://github.com/firebase/firebase-android-sdk", None),
    ("LLVM libc++ (libc++_shared.so)", "Copyright (c) the LLVM Project contributors",
     "Apache License 2.0 with LLVM Exceptions", "https://llvm.org", LLVM_EXCEPTION),
]


def section(lines, entries):
    for name, copyright, licence, source, text in entries:
        lines.append("-" * 72)
        lines.append(name)
        if copyright:
            lines.append(copyright)
        lines.append(f"Licence: {licence}")
        lines.append(f"Source: {source}")
        lines.append("")
        if text:
            lines.append(text)
            lines.append("")


def main():
    lines = [
        "PocketMicro - third-party notices",
        "=" * 72,
        "",
        "PocketMicro is free software under the GNU General Public License, version 3",
        "(full text at the end). Source: https://github.com/Hcode91/PocketMicro",
        "",
        "It includes the components below, each under its own licence. Where a",
        "licence's full text is not given with the component, it is at the end of",
        "this file.",
        "",
        "PocketMicro is an independent project. It is not affiliated with, endorsed",
        "by or connected to Razer, OUYA, Microsoft or any game publisher. No games",
        "are included.",
        "",
        "=" * 72,
        "In every edition",
        "=" * 72,
    ]
    section(lines, BOTH)
    lines += ["", "=" * 72, "In the Windows Phone edition", "=" * 72]
    section(lines, WINPHONE)
    for title, file in [("Apache License 2.0", "Apache-2.0"),
                        ("GNU Lesser General Public License 3.0", "LGPL-3"),
                        ("GNU Lesser General Public License 2.1", "LGPL-2.1"),
                        ("GNU General Public License 3.0", "GPL-3")]:
        lines += ["", "=" * 72, title, "=" * 72, "", read(COMMON / file), ""]
    out = ROOT / "notices/THIRD_PARTY_NOTICES.txt"
    out.parent.mkdir(exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {out} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
