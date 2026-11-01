#!/usr/bin/env python3
"""Offline validation of the cataloged AIR literal PIC repairs. Requires unicorn==2.1.4.
Usage: python3 scripts/test-arm-literal-pic.py original.so repaired.so
No Android loading, native JNI calls, or full game execution is established.
"""
import pathlib,struct,sys,importlib.util
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('vector_pic',pathlib.Path(__file__).with_name('test-vector-runtime-pic.py'))
pic=importlib.util.module_from_spec(spec);spec.loader.exec_module(pic)
from unicorn import Uc,UC_ARCH_ARM,UC_MODE_ARM,UC_PROT_READ,UC_PROT_WRITE,UC_PROT_EXEC
from unicorn.arm_const import UC_ARM_REG_CPSR,UC_ARM_REG_PC

def unwind(data):
 for typ,offset,address,_,size,ms,flags,align in pic.loads(data):
  if typ==0x70000001:
   out=[]
   for n in range(0,size,8):
    first,word=struct.unpack_from('<II',data,offset+n)
    signed=lambda v:v-(1<<31) if v&(1<<30) else v
    fn=address+n+signed(first&0x7fffffff)
    recipe=('extab',address+n+4+signed(word)) if word!=1 and not word&0x80000000 else ('inline',word)
    out.append((fn,recipe))
   return out
 return []

def run(data,base,site,patched,source_bad,resolve):
 m=Uc(UC_ARCH_ARM,UC_MODE_ARM);segments=pic.loads(data);size=(max(a+ms for t,o,a,_,fs,ms,f,al in segments if t==1)+4095)&~4095
 m.mem_map(base,size)
 for t,o,a,_,fs,ms,f,al in segments:
  if t==1:m.mem_write(base+a,data[o:o+fs])
 # Only operands of the isolated tested instructions need relocation. This is
 # intentionally not an emulation of the complete Android dynamic loader.
 for _,target,info in pic.relocations(data):
  if target in source_bad and info==23:m.mem_write(base+target,struct.pack('<I',base+pic.u(data,pic.off(data,target))))
  if info&255==2 and ((info>>8) in source_bad.values()):m.mem_write(base+target,struct.pack('<I',(resolve+pic.u(data,pic.off(data,target)))&0xffffffff))
 m.mem_protect(base,size,UC_PROT_READ)
 for t,o,a,_,fs,ms,f,al in segments:
  if t==1:
   lo=a&~4095;sz=(a-lo+ms+4095)&~4095;perm=(UC_PROT_READ if f&4 else 0)|(UC_PROT_WRITE if f&2 else 0)|(UC_PROT_EXEC if f&1 else 0);m.mem_protect(base+lo,sz,perm)
 for i,r in enumerate(pic.REGS):m.reg_write(r,0x12300000+i*0x1000)
 m.reg_write(UC_ARM_REG_CPSR,0xa0000010)
 m.emu_start(base+site,base+site+4,count=8 if patched else 1)
 assert m.reg_read(UC_ARM_REG_PC)==base+site+4
 return [m.reg_read(r) for r in pic.REGS]+[m.reg_read(UC_ARM_REG_PC),m.reg_read(UC_ARM_REG_CPSR)]

def main():
 before=pathlib.Path(sys.argv[1]).read_bytes();after=pathlib.Path(sys.argv[2]).read_bytes()
 segments=pic.loads(before);bad={t:i>>8 for _,t,i in pic.relocations(before) if i&255 and not any(k==1 and a<=t and t+4<=a+ms and f&2 for k,o,a,_,fs,ms,f,al in segments)}
 sites=[]
 for t,o,a,_,fs,ms,f,al in segments:
  if t!=1 or not f&1:continue
  for v in range(a,a+fs-3,4):
   ins=pic.u(before,pic.off(before,v))
   if ins&0x0f7f0000==0x051f0000:
    target=v+8+((ins&4095) if ins&0x800000 else -(ins&4095))
    if target in bad:sites.append(v)
 for _,target,info in pic.relocations(after):
  assert not info&255 or any(t==1 and a<=target and target+4<=a+ms and f&2 for t,o,a,_,fs,ms,f,al in pic.loads(after))
 assert 22 not in pic.dynamic(after)[2]
 old_unwind,new_unwind=unwind(before),unwind(after)
 if old_unwind:
  assert new_unwind[:len(old_unwind)]==old_unwind,'Original unwind starts/recipes changed'
  for site,entry in zip(sites,new_unwind[len(old_unwind):]):
   recipe=[r for fn,r in old_unwind if (fn&~1)<=site][-1]
   assert entry[1]==recipe,'Stub has different unwind semantics'
  assert new_unwind[-1][1]==('inline',1)
 else:assert not new_unwind,'Unexpected unwind table added'
 original_loads=[p for p in segments if p[0]==1]
 assert [p for p in pic.loads(after) if p[0]==1][:len(original_loads)]==original_loads
 assert all(p[6]!=7 for p in pic.loads(after) if p[0]==1),'Writable executable segment introduced'
 for base in (0x01000000,0x10000000,0x31000000):
  for resolved in (0,0x12345678):
   for site in sites:assert run(before,base,site,False,bad,resolved)==run(after,base,site,True,bad,resolved),hex(site)
 print(f'{len(sites)} ARM literal loads preserve all registers/flags/next-PC at three biases with null/resolved symbols; unwind recipes preserved; all remaining relocation writes RW. No full boot established.')
if __name__=='__main__':main()
