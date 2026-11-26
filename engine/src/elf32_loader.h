// PocketMicro engine – loads 32-bit ARM Android shared libraries (.so) into
// guest memory: maps segments, applies relocations and resolves imports.
#pragma once
#include "guest_memory.h"

#include <functional>
#include <map>
#include <string>
#include <vector>

namespace po {

struct Import {
    std::string name;
    bool is_function;  // false = data object (e.g. __sF, __stack_chk_guard)
    gaddr resolved;    // 0 = unresolved
};

struct Module {
    std::string name;
    gaddr base = 0;
    uint32_t size = 0;
    std::vector<std::string> needed;              // DT_NEEDED
    std::map<std::string, gaddr> exports;         // defined dynamic symbols
    std::map<std::string, Import> imports;        // undefined dynamic symbols
    std::vector<gaddr> init_array;                // constructors to run (DT_INIT + DT_INIT_ARRAY)
    std::vector<gaddr> fini_array;
    gaddr exidx = 0;          // ARM unwind table (PT_ARM_EXIDX), for C++ exceptions
    uint32_t exidx_count = 0;
    gaddr find(const std::string &sym) const;
};

// Called for every undefined symbol; return its guest address or 0 if unknown.
using ImportResolver = std::function<gaddr(const std::string &name, bool is_function)>;

class Elf32Loader {
public:
    explicit Elf32Loader(GuestMemory &mem) : mem_(mem) {}

    // Load from an in-memory ELF image (e.g. read straight out of the APK).
    Module load(const std::string &name, const std::vector<uint8_t> &elf, const ImportResolver &resolve);

private:
    GuestMemory &mem_;
};

std::vector<uint8_t> read_file(const std::string &path);

}  // namespace po
