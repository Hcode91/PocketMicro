#!/usr/bin/env python3
"""Offline Vector instruction checks with user-owned files; requires unicorn==2.1.4.
Usage: python3 scripts/test-vector-runtime-pic.py original.apk converted.apk
Only affected instruction sequences execute; this is not an Android boot test.
"""
import argparse,zipfile
import struct,pathlib,hashlib,sys
from unicorn import *
from unicorn.arm_const import *
def u(b,o):return struct.unpack_from('<I',b,o)[0]
def put(b,o,v):struct.pack_into('<I',b,o,v&0xffffffff)
def loads(b):
 at=u(b,28);stride,n=struct.unpack_from('<HH',b,42)
 return [struct.unpack_from('<8I',b,at+i*stride) for i in range(n)]
def off(b,v):
 for t,o,a,_,fs,ms,f,al in loads(b):
  if t==1 and a<=v<a+fs:return o+v-a
 raise AssertionError(hex(v))
def dynamic(b):
 for t,o,a,_,fs,ms,f,al in loads(b):
  if t==2:
   entries={}
   for p in range(o,o+fs,8):
    tag,value=struct.unpack_from('<II',b,p)
    if tag==0:break
    entries[tag]=(p,value)
   return o,fs,entries
 raise AssertionError('no dynamic')
def relocations(b):
 d=dynamic(b)[2]
 for at,sz in [(17,18),(23,2)]:
  if at not in d:continue
  p=off(b,d[at][1])
  for i in range(p,p+d[sz][1],8):yield i,*struct.unpack_from('<II',b,i)
REGS=[UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_R2,UC_ARM_REG_R3,UC_ARM_REG_R4,UC_ARM_REG_R5,UC_ARM_REG_R6,UC_ARM_REG_R7,UC_ARM_REG_R8,UC_ARM_REG_R9,UC_ARM_REG_R10,UC_ARM_REG_R11,UC_ARM_REG_R12,UC_ARM_REG_SP,UC_ARM_REG_LR]
def run(b,base,site,count,patched,end=None):
 m=Uc(UC_ARCH_ARM,UC_MODE_ARM);ps=loads(b);size=(max(p[2]+p[5] for p in ps if p[0]==1)+4095)&~4095;m.mem_map(base,size)
 for t,o,a,_,fs,ms,f,al in ps:
  if t==1:m.mem_write(base+a,b[o:o+fs])
 for _,target,info in relocations(b):
  if info&255==23:m.mem_write(base+target,struct.pack('<I',(base+u(b,off(b,target)))&0xffffffff))
 m.mem_protect(base,size,UC_PROT_READ)
 for t,o,a,_,fs,ms,f,al in ps:
  if t==1:
   start=a&~4095;sz=(a-start+ms+4095)&~4095;perm=(UC_PROT_READ if f&4 else 0)|(UC_PROT_WRITE if f&2 else 0)|(UC_PROT_EXEC if f&1 else 0);m.mem_protect(base+start,sz,perm)
 for i,r in enumerate(REGS):m.reg_write(r,0x12300000+i*0x1000)
 m.reg_write(UC_ARM_REG_CPSR,0xa0000010)
 m.emu_start(base+site,base+site+4 if end is None else end,count=count)
 return [m.reg_read(r) for r in REGS]+[m.reg_read(UC_ARM_REG_PC),m.reg_read(UC_ARM_REG_CPSR)]

def surface_release(b,base,pointer,thumb_target):
 """Execute the real cleanup call and subsequent DeleteLocalRef with JNI test doubles."""
 m=Uc(UC_ARCH_ARM,UC_MODE_ARM);ps=loads(b);size=(max(p[2]+p[5] for p in ps if p[0]==1)+4095)&~4095
 m.mem_map(base,size)
 for t,o,a,_,fs,ms,f,al in ps:
  if t==1:m.mem_write(base+a,b[o:o+fs])
 m.mem_protect(base,size,UC_PROT_READ)
 for t,o,a,_,fs,ms,f,al in ps:
  if t==1:
   start=a&~4095;sz=(a-start+ms+4095)&~4095
   m.mem_protect(base+start,sz,(UC_PROT_READ if f&4 else 0)|(UC_PROT_WRITE if f&2 else 0)|(UC_PROT_EXEC if f&1 else 0))
 scratch=0x50000000;m.mem_map(scratch,0x3000)
 env,table,state,release,delete,reference=[scratch+n for n in (0x100,0x200,0x1000,0x300,0x320,0x1800)]
 for address,value in ((env,table),(table+0x58,delete|thumb_target),(state+0x17c,reference)):
  m.mem_write(address,struct.pack('<I',value))
 instruction=bytes.fromhex('7047') if thumb_target else bytes.fromhex('1eff2fe1')
 m.mem_write(release,instruction);m.mem_write(delete,instruction)
 for i,r in enumerate(REGS):m.reg_write(r,0x12300000+i*0x1000)
 m.reg_write(UC_ARM_REG_CPSR,0xa0000030)
 for reg,value in ((UC_ARM_REG_R6,env),(UC_ARM_REG_R8,state),(UC_ARM_REG_R7,0x17c),(UC_ARM_REG_R10,pointer),(UC_ARM_REG_R1,reference),(UC_ARM_REG_R3,release|thumb_target)):
  m.reg_write(reg,value)
 before={r:m.reg_read(r) for r in REGS[4:12]+[UC_ARM_REG_SP]};calls=[]
 def hook(cpu,address,size,user):
  if address not in (release,delete):return
  assert cpu.reg_read(UC_ARM_REG_R0)==env and cpu.reg_read(UC_ARM_REG_R1)==reference
  if address==release:
   assert pointer!=0,'JNI ReleaseIntArrayElements called with null native elements'
   assert cpu.reg_read(UC_ARM_REG_R2)==pointer and cpu.reg_read(UC_ARM_REG_R3)==0
  calls.append('release' if address==release else 'delete')
  # JNI may clobber condition flags. Model the same ABI-conforming result in both paths.
  cpu.reg_write(UC_ARM_REG_CPSR,cpu.reg_read(UC_ARM_REG_CPSR)&0x0fffffff)
 m.hook_add(UC_HOOK_CODE,hook)
 m.emu_start((base+0x3314e)|1,base+0x3316a,count=100)
 assert m.reg_read(UC_ARM_REG_PC)==base+0x3316a
 assert calls==(['release','delete'] if pointer else ['delete'])
 assert bytes(m.mem_read(state+0x17c,4))==b'\0'*4,'Local array handle not cleared'
 assert all(m.reg_read(r)==value for r,value in before.items()),'Callee-saved register/stack changed'
 return [m.reg_read(r) for r in REGS]+[m.reg_read(UC_ARM_REG_PC),m.reg_read(UC_ARM_REG_CPSR)]

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('original_apk');p.add_argument('converted_apk');args=p.parse_args()
 files=[('libvector.so','cf4cd687b0c3c8e1d87c995a96c21c15cdb0c598d59ff35faea620de524c57fe',0xea60),
        ('libs3edialog_ext.so','74f31fd2bec42cb0ccdf4a910d366eb98e27460ef6cb753a8184a21c96d2a22a',0x5c4),
        ('libs3ewebview_ext.so','75070fd39ffca484ee68a55916656d0f8a966e0f05cea2dc80d2e52e5e3c8ec8',0x764),
        ('libs3eandroidmarketbilling_ext.so','7e83e8e141245455d6f39c6c751a58323ffb01b4a5427c5c7c6f79537663d367',0x4a94)]
 with zipfile.ZipFile(args.original_apk) as old,zipfile.ZipFile(args.converted_apk) as new:
  for name,expected,init in files:
   before=old.read('lib/armeabi/'+name);after=new.read('lib/armeabi/'+name)
   assert hashlib.sha256(before).hexdigest()==expected,'Unexpected source build'
   segments=loads(after)
   for _,target,info in relocations(after):
    assert not info&255 or any(t==1 and a<=target and target+4<=a+ms and flags&2 for t,o,a,_,fs,ms,flags,al in segments),'Remaining code relocation'
   for base in (0x01000000,0x10000000,0x31000000):
    assert run(before,base,init,3,False,0xffffffff)==run(after,base,init,3,True,0xffffffff)
   if name=='libvector.so':
    bad={target for _,target,info in relocations(before) if not any(t==1 and a<=target and target+4<=a+ms and f&2 for t,o,a,_,fs,ms,f,al in loads(before))}
    sites=[]
    for kind,o,a,_,fs,ms,f,al in loads(before):
     if kind!=1 or not f&1:continue
     for site in range(a,a+fs-3,4):
      ins=u(before,off(before,site))
      if ins&0x0f7f0000==0x051f0000:
       literal=site+8+((ins&4095) if ins&0x800000 else -(ins&4095))
       if literal in bad:sites.append(site)
    assert len(sites)==43
    for base in (0x01000000,0x10000000,0x31000000):
     for site in sites:assert run(before,base,site,1,False)==run(after,base,site,4,True),hex(site)
    for base in (0x01000000,0x10000000,0x31000000):
     for thumb in (0,1):
      for pointer in (0x50002000,0x50002100):
       assert surface_release(before,base,pointer,thumb)==surface_release(after,base,pointer,thumb)
      try:surface_release(before,base,0,thumb)
      except AssertionError as error:assert str(error)=='JNI ReleaseIntArrayElements called with null native elements'
      else:raise AssertionError('Original null-buffer crash path was not reproduced')
      try:surface_release(before,base,0,thumb)
      except AssertionError as error:assert str(error)=='JNI ReleaseIntArrayElements called with null native elements'
      else:raise AssertionError('Original null-buffer crash path was not reproduced')
      surface_release(after,base,0,thumb)
    print(name+': 43 literal loads and initializer preserve register/flag/PC state at three load biases; JNI surface guard skips null releases, preserves valid releases and retains reference cleanup with ARM/Thumb targets')
   else:print(name+': initializer preserves register/flag/PC state at three load biases')
 print('No full game launch is established by these instruction checks.')

if __name__=='__main__':main()
