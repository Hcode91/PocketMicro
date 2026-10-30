// PocketOuya engine – the 32-bit guest address space.
//
// 32-bit game code (run by dynarmic) sees addresses 0..4 GiB. We reserve one
// 4 GiB block of host memory and map guest address A to host (base + A), so
// translating a guest pointer is a single add.
#pragma once
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>

namespace po {

using gaddr = uint32_t;  // guest (32-bit) address

class GuestMemory {
public:
    GuestMemory();
    ~GuestMemory();
    GuestMemory(const GuestMemory &) = delete;
    GuestMemory &operator=(const GuestMemory &) = delete;

    // Make [addr, addr+size) usable (read/write/exec); page-aligned internally.
    bool commit(gaddr addr, uint32_t size);

    // Simple bump allocator for loader-owned regions (modules, thunks, data).
    gaddr reserve_region(uint32_t size, uint32_t align = 0x1000);

    uint8_t *host(gaddr a) const { return base_ + a; }
    template <typename T> T *ptr(gaddr a) const { return reinterpret_cast<T *>(base_ + a); }
    uint32_t read32(gaddr a) const;
    void write32(gaddr a, uint32_t v);
    gaddr guest(const void *h) const { return static_cast<gaddr>(static_cast<const uint8_t *>(h) - base_); }
    uint8_t *base() const { return base_; }

    static constexpr uint64_t kSize = 1ull << 32;

private:
    uint8_t *base_ = nullptr;
    gaddr next_region_ = 0x10000000;
    std::mutex region_mutex_;  // keep low memory free (null-pointer traps)
};

}  // namespace po
