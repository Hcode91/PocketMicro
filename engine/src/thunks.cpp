#include "thunks.h"

#include <stdexcept>

namespace po {

static constexpr uint32_t kThunkArea = 0x100000;  // room for 128k thunks

ThunkTable::ThunkTable(GuestMemory &mem) : mem_(mem) {
    code_ = mem_.reserve_region(kThunkArea);
    code_end_ = code_ + kThunkArea;
}

gaddr ThunkTable::function(const std::string &name, bool implemented) {
    auto it = by_name_.find(name);
    if (it != by_name_.end()) return it->second;
    uint32_t id = static_cast<uint32_t>(thunks_.size());
    gaddr a = code_ + id * 8;
    if (a + 8 > code_end_) throw std::runtime_error("out of thunk space");
    mem_.write32(a, 0xEF000000u | (id & 0x00FFFFFFu));  // svc #id   (ARM mode)
    mem_.write32(a + 4, 0xE12FFF1Eu);                  // bx lr
    thunks_.push_back({name, a, implemented});
    by_name_[name] = a;
    return a;
}

gaddr ThunkTable::data(const std::string &name, uint32_t size) {
    auto it = by_name_.find(name);
    if (it != by_name_.end()) return it->second;
    gaddr a = mem_.reserve_region(size ? size : 4, 16);
    by_name_[name] = a;
    return a;
}

}  // namespace po
