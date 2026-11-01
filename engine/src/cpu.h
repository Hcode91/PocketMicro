// PocketOuya engine – one emulated 32-bit ARM CPU (dynarmic JIT).
// Each guest thread owns one Cpu; nested guest calls from host code use a child Cpu.
#pragma once
#include "runtime.h"

#include <array>
#include <cstdint>
#include <memory>

namespace Dynarmic::A32 { class Jit; }

namespace po {

class Cpu {
public:
    Cpu(Runtime &rt, gaddr stack_top);
    ~Cpu();

    Runtime &rt;

    // Call a guest function (ARM or Thumb) with up to N 32-bit args; returns r0 | r1 << 32.
    uint64_t call(gaddr fn, const std::vector<uint32_t> &args = {});

    // Registers of the current call.
    uint32_t &reg(int i);
    uint32_t sp();
    uint32_t &ext(int i);  // VFP registers as 32-bit words (s0..s63)
    // Argument i of the host function being executed (AAPCS: r0-r3, then the stack).
    uint32_t arg(int i);
    uint64_t arg64(int first_reg_index);  // 64-bit arg in an even register pair
    float argf(int i);
    double argd(int first_reg_index);
    void ret(uint32_t v);
    void ret64(uint64_t v);
    void retf(float v);
    void retd(double v);

    // Change the guest's control flow after the current host call returns (longjmp).
    void set_return(gaddr pc, gaddr sp, const uint32_t callee_saved[8]);

    // Per-thread guest data
    gaddr errno_addr = 0;
    void set_errno(int e);

    void halt();           // stop the JIT loop (used by the return sentinel / stops)
    bool returned = false; // set when the called guest function has returned

    static Cpu *current();  // the Cpu running on this host thread (innermost)

    struct Impl;
private:
    std::unique_ptr<Impl> impl_;
    std::unique_ptr<Cpu> child_;
    gaddr stack_top_;
    bool running_ = false;
};

}  // namespace po
