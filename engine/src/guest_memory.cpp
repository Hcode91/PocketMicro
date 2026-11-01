#include "guest_memory.h"

#include <cstring>
#include <stdexcept>
#include <sys/mman.h>

namespace po {

GuestMemory::GuestMemory() {
    void *p = mmap(nullptr, kSize, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (p == MAP_FAILED) throw std::runtime_error("could not reserve 4 GiB guest address space");
    base_ = static_cast<uint8_t *>(p);
}

GuestMemory::~GuestMemory() {
    if (base_) munmap(base_, kSize);
}

bool GuestMemory::commit(gaddr addr, uint32_t size) {
    uint64_t start = addr & ~0xfffull;
    uint64_t end = (static_cast<uint64_t>(addr) + size + 0xfff) & ~0xfffull;
    return mprotect(base_ + start, end - start, PROT_READ | PROT_WRITE) == 0;
}

gaddr GuestMemory::reserve_region(uint32_t size, uint32_t align) {
    std::lock_guard<std::mutex> lk(region_mutex_);
    gaddr a = (next_region_ + align - 1) & ~(align - 1);
    next_region_ = (a + size + 0xfff) & ~0xfffu;
    if (!commit(a, size)) throw std::runtime_error("guest commit failed");
    return a;
}

uint32_t GuestMemory::read32(gaddr a) const {
    uint32_t v;
    std::memcpy(&v, base_ + a, 4);
    return v;
}

void GuestMemory::write32(gaddr a, uint32_t v) { std::memcpy(base_ + a, &v, 4); }

}  // namespace po
