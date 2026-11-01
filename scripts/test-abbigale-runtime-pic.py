#!/usr/bin/env python3
"""Optional checks using user-supplied Abbigale APKs; no game files bundled.
Usage: python3 scripts/test-abbigale-runtime-pic.py original.apk converted.apk
Requires unicorn==2.1.4. Does not boot Android or prove the device's resolver behavior.
"""
import hashlib, importlib.util, pathlib, struct, sys, zipfile
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('runtime_pic', pathlib.Path(__file__).with_name('test-muffin-runtime-pic.py'))
pic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pic)
from unicorn import Uc, UC_ARCH_ARM, UC_MODE_ARM, UC_HOOK_CODE, UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC
from unicorn.arm_const import UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_SP, UC_ARM_REG_LR

PROFILES = [
    ('libmono.so', '9816e13ed0ce4d690c9c99ca5187168e08dfacfb4387002becf072153868970c',
     'e888ab00e407ab46d67d6ab32e7573ca0f571fcc9c3fe57d214c336e7e53498e', (0x15a60, 0, 0, 0, 0x150e4)),
    ('libunity.so', '2442a0cffee5f77854895316151573bceabcb2b6278e472aff74b3236d0d3c2a',
     '15b92db63f6869bd1f34f13db7a12a0e89bea9765f87d7f08b114cf490482746', (0x64910, 0x64920, 0x64934, 0x8bc1bc, 0x637d0)),
]

def string_cycle(data, incorrect):
    """Reproduce the observed cycle if strlen returns an address instead of a length.
    This models a resolver-style return, not an observed/verified device libc resolver.
    A correct length reaches normal allocation instead. Never patch the error branch.
    """
    vm = Uc(UC_ARCH_ARM, UC_MODE_ARM)
    bias = 0x10000000
    loads = pic.layout(data)[0]
    size = (max(a+m for _, a, _, m, _ in loads)+4095) & ~4095
    vm.mem_map(bias, size)
    for offset, address, length, _, _ in loads:
        vm.mem_write(bias+address, data[offset:offset+length])
    # Only this call path's external symbol is used before stopping at allocation.
    vm.mem_write(bias+0x8bbd74, struct.pack('<I', 0x50000001))
    vm.mem_protect(bias, size, UC_PROT_READ)
    for _, address, _, memory, flags in loads:
        start = address & ~4095
        length = (address-start+memory+4095) & ~4095
        perms = (UC_PROT_READ if flags & 4 else 0) | (UC_PROT_WRITE if flags & 2 else 0) | (UC_PROT_EXEC if flags & 1 else 0)
        vm.mem_protect(bias+start, length, perms)
    at = pic.file_offset(data, 0x80a4a0)
    message = data[at:data.index(b'\0', at)]
    assert message == b'basic_string::_S_create'
    result = 0xe63320e5 if incorrect else len(message)
    vm.mem_map(0x50000000, 4096)
    vm.mem_write(0x50000000, bytes.fromhex('00487047')+struct.pack('<I', result))
    vm.mem_map(0x60000000, 4096)
    vm.mem_map(0x70000000, 65536)
    vm.reg_write(UC_ARM_REG_SP, 0x7000f000)
    vm.reg_write(UC_ARM_REG_LR, 0x60000000)
    vm.reg_write(UC_ARM_REG_R0, 0x70000000)
    vm.reg_write(UC_ARM_REG_R1, bias+0x80a4a0)
    vm.reg_write(UC_ARM_REG_R2, 0x70000020)
    trace = []
    def hook(machine, pc, _, __):
        offset = pc-bias
        if offset in (0x7c322c, 0x7d84e8, 0x7d8784, 0x7d8868):
            trace.append(offset)
            if len(trace) >= 12:
                machine.emu_stop()
        if not incorrect and offset == 0x7d8464:
            assert machine.reg_read(UC_ARM_REG_R0) == len(message)
            machine.emu_stop()
    vm.hook_add(UC_HOOK_CODE, hook)
    vm.emu_start(bias+0x7d8838, 0x60000004, count=2000)
    if incorrect:
        assert trace == [0x7d8868, 0x7d8784, 0x7d84e8, 0x7c322c]*3
    else:
        assert trace == [0x7d8868, 0x7d8784]


with zipfile.ZipFile(sys.argv[1]) as original, zipfile.ZipFile(sys.argv[2]) as converted:
    for name, source_hash, output_hash, profile in PROFILES:
        asset = 'assets/libs/armeabi-v7a/'+name
        installed = 'lib/armeabi-v7a/'+name
        before = original.read(asset)
        after = converted.read(installed)
        assert hashlib.sha256(before).hexdigest() == source_hash
        assert hashlib.sha256(after).hexdigest() == output_hash
        assert converted.read(asset) == before, 'Embedded asset changed'
        assert pic.layout(before) == pic.layout(after), 'ELF mappings/permissions changed'
        for bias in (0x01000000, 0x10000000, 0x31000000):
            assert pic.execute(before, bias, False, False, profile=profile) == pic.execute(after, bias, False, True, profile=profile)
            if profile[1]:
                for target_thumb in (False, True):
                    for caller_thumb in (False, True):
                        old = pic.execute(before, bias, False, False, True, target_thumb, caller_thumb, profile)
                        new = pic.execute(after, bias, False, True, True, target_thumb, caller_thumb, profile)
                        assert old[:4] == new[:4] and old[4]+8 == new[4]
        if name == 'libunity.so':
            string_cycle(before, True)
            string_cycle(before, False)
        print(name+': exact hashes, preserved assets/mappings, initializer and interworking checks passed')
print('Modeled strlen address-return reproduces four crash frames; correct length avoids that cycle.')
print('This does not establish the device resolver cause or successful Android gameplay.')
