#include "elf32_loader.h"

#include <cstring>
#include <fstream>
#include <stdexcept>

namespace po {
namespace {

// Minimal ELF32 definitions (kept local so the engine builds on any host).
struct Ehdr {
    uint8_t ident[16];
    uint16_t type, machine;
    uint32_t version, entry, phoff, shoff, flags;
    uint16_t ehsize, phentsize, phnum, shentsize, shnum, shstrndx;
};
struct Phdr { uint32_t type, offset, vaddr, paddr, filesz, memsz, flags, align; };
struct Dyn { int32_t tag; uint32_t val; };
struct Sym { uint32_t name, value, size; uint8_t info, other; uint16_t shndx; };
struct Rel { uint32_t offset, info; };

enum : uint32_t { PT_LOAD = 1, PT_DYNAMIC = 2 };
enum : int32_t {
    DT_NULL = 0, DT_NEEDED = 1, DT_PLTRELSZ = 2, DT_HASH = 4, DT_STRTAB = 5, DT_SYMTAB = 6,
    DT_RELA = 7, DT_INIT = 12, DT_FINI = 13, DT_REL = 17, DT_RELSZ = 18, DT_JMPREL = 23,
    DT_INIT_ARRAY = 25, DT_FINI_ARRAY = 26, DT_INIT_ARRAYSZ = 27, DT_FINI_ARRAYSZ = 28,
};
enum : uint32_t {
    R_ARM_NONE = 0, R_ARM_ABS32 = 2, R_ARM_REL32 = 3, R_ARM_GLOB_DAT = 21,
    R_ARM_JUMP_SLOT = 22, R_ARM_RELATIVE = 23,
};
constexpr uint16_t EM_ARM = 40;
constexpr uint8_t STT_FUNC = 2, STT_OBJECT = 1, STT_GNU_IFUNC = 10;

template <typename T> T at(const std::vector<uint8_t> &f, uint32_t off) {
    if (off + sizeof(T) > f.size()) throw std::runtime_error("ELF truncated");
    T v;
    std::memcpy(&v, f.data() + off, sizeof(T));
    return v;
}

}  // namespace

std::vector<uint8_t> read_file(const std::string &path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) throw std::runtime_error("can't open " + path);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}

gaddr Module::find(const std::string &sym) const {
    auto it = exports.find(sym);
    return it == exports.end() ? 0 : it->second;
}

Module Elf32Loader::load(const std::string &name, const std::vector<uint8_t> &f, const ImportResolver &resolve) {
    Ehdr eh = at<Ehdr>(f, 0);
    if (std::memcmp(eh.ident, "\x7f" "ELF", 4) != 0 || eh.ident[4] != 1 /*32-bit*/ || eh.machine != EM_ARM)
        throw std::runtime_error(name + " is not a 32-bit ARM ELF file");

    // 1. Work out the size of the image and reserve guest memory for it.
    uint32_t lo = 0xffffffff, hi = 0;
    std::vector<Phdr> ph;
    for (int i = 0; i < eh.phnum; i++) {
        Phdr p = at<Phdr>(f, eh.phoff + i * eh.phentsize);
        ph.push_back(p);
        if (p.type == PT_LOAD) {
            lo = std::min(lo, p.vaddr & ~0xfffu);
            hi = std::max(hi, p.vaddr + p.memsz);
        }
    }
    if (hi <= lo) throw std::runtime_error(name + " has no loadable segments");

    Module m;
    m.name = name;
    m.size = hi - lo;
    m.base = mem_.reserve_region(m.size) - lo;  // so that vaddr + base = guest address

    // 2. Copy segments (zero-fill BSS is free: committed memory is zeroed).
    uint32_t dyn_vaddr = 0;
    for (const Phdr &p : ph) {
        if (p.type == PT_LOAD && p.filesz) {
            if (p.offset + p.filesz > f.size()) throw std::runtime_error("segment outside file");
            std::memcpy(mem_.host(m.base + p.vaddr), f.data() + p.offset, p.filesz);
        }
        if (p.type == PT_DYNAMIC) dyn_vaddr = p.vaddr;
        if (p.type == 0x70000001) {  // PT_ARM_EXIDX
            m.exidx = m.base + p.vaddr;
            m.exidx_count = p.memsz / 8;
        }
    }
    if (!dyn_vaddr) throw std::runtime_error(name + " has no dynamic section");

    // 3. Read the dynamic section (from the loaded image).
    uint32_t strtab = 0, symtab = 0, hash = 0, rel = 0, relsz = 0, jmprel = 0, pltrelsz = 0;
    uint32_t init = 0, init_array = 0, init_arraysz = 0, fini_array = 0, fini_arraysz = 0;
    std::vector<uint32_t> needed_off;
    for (gaddr d = m.base + dyn_vaddr;; d += 8) {
        Dyn dyn{static_cast<int32_t>(mem_.read32(d)), mem_.read32(d + 4)};
        if (dyn.tag == DT_NULL) break;
        switch (dyn.tag) {
            case DT_NEEDED: needed_off.push_back(dyn.val); break;
            case DT_STRTAB: strtab = dyn.val; break;
            case DT_SYMTAB: symtab = dyn.val; break;
            case DT_HASH: hash = dyn.val; break;
            case DT_REL: rel = dyn.val; break;
            case DT_RELSZ: relsz = dyn.val; break;
            case DT_JMPREL: jmprel = dyn.val; break;
            case DT_PLTRELSZ: pltrelsz = dyn.val; break;
            case DT_INIT: init = dyn.val; break;
            case DT_INIT_ARRAY: init_array = dyn.val; break;
            case DT_INIT_ARRAYSZ: init_arraysz = dyn.val; break;
            case DT_FINI_ARRAY: fini_array = dyn.val; break;
            case DT_FINI_ARRAYSZ: fini_arraysz = dyn.val; break;
            case DT_RELA: throw std::runtime_error("RELA relocations are not used on 32-bit ARM");
            default: break;
        }
    }
    auto str = [&](uint32_t off) { return std::string(mem_.ptr<const char>(m.base + strtab + off)); };
    for (uint32_t o : needed_off) m.needed.push_back(str(o));

    // 4. Symbols: number of entries = nchain from the SysV hash table.
    uint32_t nsyms = hash ? mem_.read32(m.base + hash + 4) : 0;
    std::vector<Sym> syms(nsyms);
    for (uint32_t i = 0; i < nsyms; i++) std::memcpy(&syms[i], mem_.host(m.base + symtab + i * 16), 16);
    for (uint32_t i = 1; i < nsyms; i++) {
        const Sym &s = syms[i];
        if (s.shndx != 0 && s.name) m.exports[str(s.name)] = m.base + s.value;
    }

    // Resolve a symbol: own definition first (DT_SYMBOLIC), then the host.
    auto sym_addr = [&](uint32_t idx) -> gaddr {
        const Sym &s = syms.at(idx);
        if (s.shndx != 0) return m.base + s.value;
        std::string n = str(s.name);
        auto it = m.imports.find(n);
        if (it != m.imports.end()) return it->second.resolved;
        uint8_t type = s.info & 0xf;
        bool fn = type == STT_FUNC || type == STT_GNU_IFUNC || type == 0 /* NOTYPE: usually a function */;
        if (type == STT_OBJECT) fn = false;
        gaddr a = resolve ? resolve(n, fn) : 0;
        m.imports[n] = Import{n, fn, a};
        return a;
    };

    // 5. Relocations.
    auto apply = [&](uint32_t table, uint32_t size) {
        for (uint32_t off = 0; off < size; off += 8) {
            Rel r;
            std::memcpy(&r, mem_.host(m.base + table + off), 8);
            uint32_t type = r.info & 0xff, symi = r.info >> 8;
            gaddr where = m.base + r.offset;
            switch (type) {
                case R_ARM_NONE: break;
                case R_ARM_RELATIVE: mem_.write32(where, mem_.read32(where) + m.base); break;
                case R_ARM_ABS32: mem_.write32(where, mem_.read32(where) + sym_addr(symi)); break;
                case R_ARM_REL32: mem_.write32(where, mem_.read32(where) + sym_addr(symi) - where); break;
                case R_ARM_GLOB_DAT:
                case R_ARM_JUMP_SLOT: mem_.write32(where, sym_addr(symi)); break;
                default: throw std::runtime_error("unsupported relocation type " + std::to_string(type));
            }
        }
    };
    apply(rel, relsz);
    apply(jmprel, pltrelsz);

    // 6. Constructors / destructors (run later by the CPU, in this order).
    if (init) m.init_array.push_back(m.base + init);
    for (uint32_t i = 0; i < init_arraysz / 4; i++) {
        gaddr fnp = mem_.read32(m.base + init_array + i * 4);
        if (fnp != 0 && fnp != 0xffffffff) m.init_array.push_back(fnp);
    }
    for (uint32_t i = 0; i < fini_arraysz / 4; i++) {
        gaddr fnp = mem_.read32(m.base + fini_array + i * 4);
        if (fnp != 0 && fnp != 0xffffffff) m.fini_array.push_back(fnp);
    }
    return m;
}

}  // namespace po
