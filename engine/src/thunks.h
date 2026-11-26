// PocketMicro engine – "thunks": guest addresses that stand in for host functions.
//
// Each imported function the game calls (malloc, glDrawArrays, …) gets a tiny
// ARM stub in guest memory:   svc #<id> ; bx lr
// When the CPU emulator hits the SVC, it calls the host implementation
// registered under <id>. Data imports (e.g. __sF) get a block of guest memory.
#pragma once
#include "guest_memory.h"

#include <functional>
#include <map>
#include <string>
#include <vector>

namespace po {

struct Thunk {
    std::string name;
    gaddr addr;
    bool implemented;  // false = placeholder that will report "missing function"
};

class ThunkTable {
public:
    explicit ThunkTable(GuestMemory &mem);
    gaddr function(const std::string &name, bool implemented = false);
    gaddr data(const std::string &name, uint32_t size);
    bool has(const std::string &name) const { return by_name_.count(name) != 0; }
    const std::vector<Thunk> &all() const { return thunks_; }
    const Thunk *by_svc(uint32_t id) const { return id < thunks_.size() ? &thunks_[id] : nullptr; }

private:
    GuestMemory &mem_;
    gaddr code_ = 0, code_end_ = 0;
    std::vector<Thunk> thunks_;
    std::map<std::string, gaddr> by_name_;
};

}  // namespace po
