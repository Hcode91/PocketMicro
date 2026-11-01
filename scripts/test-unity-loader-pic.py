#!/usr/bin/env python3
"""Offline instruction checks for the exact Mono/Unity loader-only repairs.
Usage: python3 scripts/test-unity-loader-pic.py original.apk converted.apk
Requires unicorn==2.1.4. Uses user-supplied binaries; no Android boot is performed.
"""
import pathlib,sys,importlib.util,zipfile,hashlib
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('runtime_pic',pathlib.Path(__file__).with_name('test-muffin-runtime-pic.py'))
pic=importlib.util.module_from_spec(spec);spec.loader.exec_module(pic)
profiles=[('libmono.so','a280a957e102a2b0e075f339ddff6a648a27af8d8ecfb2a994a43905d38cf15c',(0x3af8,0x3b08,0x3b1c,0x16f88,0x396c)),
          ('libunity.so','aee89345929061f1e52fe72a7874796a8d3cdba8a663bf370a14cea896b05961',(0x1760,0x1770,0x1784,0xbf88,0x15d4))]
with zipfile.ZipFile(sys.argv[1]) as original,zipfile.ZipFile(sys.argv[2]) as repaired:
 for name,expected,profile in profiles:
  path='lib/armeabi-v7a/'+name;before=original.read(path);after=repaired.read(path)
  assert hashlib.sha256(before).hexdigest()==expected
  assert pic.layout(before)==pic.layout(after),'Original segments/permissions changed'
  for base in (0x01000000,0x10000000,0x31000000):
   assert pic.execute(before,base,False,False,profile=profile)==pic.execute(after,base,False,True,profile=profile)
   for target_thumb in (False,True):
    for caller_thumb in (False,True):
     old=pic.execute(before,base,False,False,True,target_thumb,caller_thumb,profile)
     new=pic.execute(after,base,False,True,True,target_thumb,caller_thumb,profile)
     assert old[:4]==new[:4] and old[4]+8==new[4]
  print(name+': initializer/atexit arguments, ARM/Thumb interworking, callee-saved registers, caller stack and original RX/RW mappings verified at three biases')
print('Loader-only instruction tests do not verify the embedded runtime or a full game launch.')
