#!/usr/bin/env python3
"""Optional instruction checks with user-owned Unity 4.3.4f1 APKs (AD2442).
Usage: python3 scripts/test-ad2442-runtime-pic.py original.apk converted.apk
Requires unicorn==2.1.4. No native/game files bundled; does not boot Android.
"""
import hashlib, importlib.util, pathlib, sys, zipfile
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('runtime_pic', pathlib.Path(__file__).with_name('test-muffin-runtime-pic.py'))
pic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pic)
profiles = [
    ('libmain.so', 'df20f3907015929b5dc1f8d797aaa8333ae6b92ee1c087d384a82f9569fa8dfb',
     'bc152baf4f9d17ec8f47040b0988bc3a1b2b914d4b93c45f723cdac6f00e6326', (0x21f8, 0x2208, 0x221c, 0x7f4c, 0x1f78)),
    ('libmono.so', '3ff6f788b6c25c1fc7e74694b56a00c5fdc873b1310a524055c200aeb41c47e1',
     '580c868118528120640588c4f993cf25c1f16b7a77882262c7fd6092be06d9bd', (0x15b08, 0, 0, 0, 0x15190)),
    ('libunity.so', '9f1bd21992ebdb845947a9595365ae919e0daacfd019ec6abdb1863fbe284157',
     '2d11f8117a4db369788e3185d05185b39dff9cfdd08aa06c13ed47501be6633e', (0x6bb30, 0x6bb40, 0x6bb54, 0xa2d120, 0x6a84c)),
]
with zipfile.ZipFile(sys.argv[1]) as original, zipfile.ZipFile(sys.argv[2]) as repaired:
    for name, source_hash, output_hash, profile in profiles:
        path = next(p for p in ('lib/armeabi-v7a/'+name, 'lib/armeabi/'+name) if p in original.namelist())
        before, after = original.read(path), repaired.read(path)
        assert hashlib.sha256(before).hexdigest() == source_hash
        assert hashlib.sha256(after).hexdigest() == output_hash
        assert pic.layout(before) == pic.layout(after), 'ELF mappings/permissions changed'
        for base in (0x01000000, 0x10000000, 0x31000000):
            assert pic.execute(before, base, False, False, profile=profile) == pic.execute(after, base, False, True, profile=profile)
            if profile[1]:
                for target_thumb in (False, True):
                    for caller_thumb in (False, True):
                        old = pic.execute(before, base, False, False, True, target_thumb, caller_thumb, profile)
                        new = pic.execute(after, base, False, True, True, target_thumb, caller_thumb, profile)
                        assert old[:4] == new[:4] and old[4]+8 == new[4]
        print(name+': exact hashes, original RX/RW mappings, initializer/atexit arguments, stack and ARM/Thumb interworking passed')
    for path in original.namelist():
        if path.endswith('/lib-ouya-ndk.so'):
            assert original.read(path) == repaired.read(path), 'OUYA extension changed'
print('Three-library instruction checks passed; Android startup/gameplay remains unverified.')
