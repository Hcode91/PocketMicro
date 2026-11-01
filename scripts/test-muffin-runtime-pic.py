#!/usr/bin/env python3
"""Offline instruction checks against user-owned APKs; pip install unicorn==2.1.4.
Usage: python3 scripts/test-muffin-runtime-pic.py original.apk converted.apk
No game files are bundled. Only the small repaired code sequences are executed.
"""
import argparse
import hashlib
import struct
import zipfile
from unicorn import Uc, UC_ARCH_ARM, UC_MODE_ARM, UC_HOOK_CODE, UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC
from unicorn.arm_const import UC_ARM_REG_SP, UC_ARM_REG_LR, UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R4, UC_ARM_REG_CPSR


def word(data, offset):
    return struct.unpack_from('<I', data, offset)[0]


def layout(data):
    table = word(data, 28)
    stride, count = struct.unpack_from('<HH', data, 42)
    loads, dynamic = [], None
    for i in range(count):
        kind, offset, address, _, size, memory, flags, _ = struct.unpack_from('<8I', data, table+i*stride)
        if kind == 1:
            loads.append((offset, address, size, memory, flags))
        if kind == 2:
            dynamic = (offset, size)
    return loads, dynamic


def file_offset(data, address):
    for offset, start, size, _, _ in layout(data)[0]:
        if start <= address < start+size:
            return offset+address-start
    raise AssertionError('Address is not file-backed')


def relocations(data):
    offset, size = layout(data)[1]
    tags = {}
    for at in range(offset, offset+size, 8):
        tag, value = struct.unpack_from('<II', data, at)
        if not tag:
            break
        tags[tag] = value
    for address_tag, size_tag in [(17, 18), (23, 2)]:
        start = file_offset(data, tags[address_tag])
        for at in range(start, start+tags[size_tag], 8):
            yield struct.unpack_from('<II', data, at)


def execute(data, bias, unity, patched, thunk=False, callee_thumb=False, caller_thumb=False, profile=None):
    init, destructor, literal, got, stop_offset = profile or ((0x64720, 0x64730, 0x64744, 0x8bc1bc, 0x635e0) if unity else (0x15a60, 0, 0, 0, 0x150e4))
    vm = Uc(UC_ARCH_ARM, UC_MODE_ARM)
    loads = layout(data)[0]
    size = (max(address+memory for _, address, _, memory, _ in loads)+4095) & ~4095
    vm.mem_map(bias, size)
    for offset, address, length, _, _ in loads:
        vm.mem_write(bias+address, data[offset:offset+length])
    for target, info in relocations(data):
        kind = info & 255
        if patched and kind:
            assert any(start <= target and target+4 <= start+memory and flags & 2
                       for _, start, _, memory, flags in loads), 'Remaining code relocation'
        if kind == 23:
            value = (bias+word(data, file_offset(data, target))) & 0xffffffff
            vm.mem_write(bias+target, struct.pack('<I', value))
        if (unity or profile) and target in (literal, got) and kind in (2, 22):
            vm.mem_write(bias+target, struct.pack('<I', 0x50000000 | int(callee_thumb)))
    # Execute with the ELF's original RX/RW permissions, never an added RWX mapping.
    vm.mem_protect(bias, size, UC_PROT_READ)
    for _, address, _, memory, flags in loads:
        start = address & ~4095
        length = ((address-start+memory)+4095) & ~4095
        perms = (UC_PROT_READ if flags & 4 else 0) | (UC_PROT_WRITE if flags & 2 else 0) | (UC_PROT_EXEC if flags & 1 else 0)
        vm.mem_protect(bias+start, length, perms)
    vm.mem_map(0x50000000, 4096)
    vm.mem_write(0x50000000, bytes.fromhex('7047' if callee_thumb else '1eff2fe1'))  # BX LR stand-in.
    vm.mem_map(0x60000000, 4096)
    vm.mem_map(0x70000000, 8192)
    vm.reg_write(UC_ARM_REG_SP, 0x70001000)
    vm.reg_write(UC_ARM_REG_LR, 0x60000000 | int(caller_thumb))
    vm.reg_write(UC_ARM_REG_R0, 0xabc123)
    vm.reg_write(UC_ARM_REG_R4, 0x12345678)
    trace = []
    stop = bias+stop_offset

    def hook(machine, pc, _, __):
        if (not thunk and pc == stop) or (thunk and pc == 0x50000000):
            trace.append(tuple(machine.reg_read(r) for r in (UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R4, UC_ARM_REG_SP)))
            if not thunk:
                machine.emu_stop()
        if pc == 0x60000000:
            machine.emu_stop()

    vm.hook_add(UC_HOOK_CODE, hook)
    entry = (destructor | 1) if thunk else init
    vm.emu_start(bias+entry, 0x60000004, count=100)
    assert len(trace) == 1
    if thunk:
        assert vm.reg_read(UC_ARM_REG_SP) == 0x70001000
        assert vm.reg_read(UC_ARM_REG_R4) == 0x12345678
        assert bool(vm.reg_read(UC_ARM_REG_CPSR) & 32) == caller_thumb
    return trace[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('original_apk')
    parser.add_argument('converted_apk')
    args = parser.parse_args()
    with zipfile.ZipFile(args.original_apk) as original, zipfile.ZipFile(args.converted_apk) as converted:
        for unity, name in ((False, 'libmono.so'), (True, 'libunity.so')):
            abi = 'armeabi-v7a' if 'lib/armeabi-v7a/'+name in original.namelist() else 'armeabi'
            asset = 'assets/libs/armeabi-v7a/'+name
            before = original.read('lib/'+abi+'/'+name)
            expected = ('e827ed895c38e3753c132ba1e0b95207c55cc16b8474fb4e26f1d7f5c719170e' if unity
                        else 'cf6e3b14a342ea3a6d5f064dfb7d8314aa46adb784c5d98e42b1d32a5864029a')
            if hashlib.sha256(before).hexdigest() != expected:
                before = original.read(asset)
            assert hashlib.sha256(before).hexdigest() == expected, 'This instruction test requires the cataloged source build'
            after = converted.read('lib/'+abi+'/'+name)
            assert layout(before) == layout(after), 'Program segment layout/permissions changed'
            for bias in (0x01000000, 0x10000000, 0x31000000):
                assert execute(before, bias, unity, False) == execute(after, bias, unity, True)
                if unity:
                    for callee_thumb in (False, True):
                        for caller_thumb in (False, True):
                            old = execute(before, bias, True, False, True, callee_thumb, caller_thumb)
                            new = execute(after, bias, True, True, True, callee_thumb, caller_thumb)
                            assert old[:4] == new[:4] and old[4]+8 == new[4]
            print(name+': startup arguments, tail calls, ARM/Thumb interworking, stack preservation and RX/RW checks passed')
    print('These instruction checks do not verify a full Android game launch.')


if __name__ == '__main__':
    main()
