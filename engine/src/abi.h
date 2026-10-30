// Helpers for reading variadic arguments the 32-bit ARM way (AAPCS, soft-float):
// from registers/stack (for "...") or from a guest va_list pointer.
#pragma once
#include "cpu.h"

#include <cstring>

namespace po {

struct ArgSource {
    virtual ~ArgSource() = default;
    virtual uint32_t next32() = 0;
    virtual uint64_t next64() = 0;
    double nextd() {
        uint64_t v = next64();
        double d;
        std::memcpy(&d, &v, 8);
        return d;
    }
};

// Variadic args of the current host call, starting at argument slot `first`.
struct RegArgs final : ArgSource {
    Cpu &cpu;
    int slot;
    RegArgs(Cpu &c, int first) : cpu(c), slot(first) {}
    uint32_t next32() override { return cpu.arg(slot++); }
    uint64_t next64() override {
        if (slot & 1) slot++;
        uint64_t lo = cpu.arg(slot), hi = cpu.arg(slot + 1);
        slot += 2;
        return lo | (hi << 32);
    }
};

// A guest va_list (ARM EABI: just a pointer into the argument area).
struct VaArgs final : ArgSource {
    GuestMemory &mem;
    gaddr p;
    VaArgs(GuestMemory &m, gaddr ap) : mem(m), p(ap) {}
    uint32_t next32() override {
        uint32_t v = mem.read32(p);
        p += 4;
        return v;
    }
    uint64_t next64() override {
        p = (p + 7) & ~7u;
        uint64_t lo = mem.read32(p), hi = mem.read32(p + 4);
        p += 8;
        return lo | (hi << 32);
    }
};

// printf-style formatting of a guest format string.
std::string guest_format(Runtime &rt, const char *fmt, ArgSource &args);
// sscanf-style parsing; writes results into guest pointers taken from args.
int guest_scan(Runtime &rt, const char *input, const char *fmt, ArgSource &args, size_t *consumed = nullptr);

}  // namespace po
