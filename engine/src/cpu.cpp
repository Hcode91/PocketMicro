#include "cpu.h"

#include <cstring>
#include <dynarmic/interface/A32/a32.h>
#include <dynarmic/interface/A32/config.h>
#include <dynarmic/interface/exclusive_monitor.h>

namespace po {

namespace {
thread_local Cpu *t_current = nullptr;
}

struct Cpu::Impl final : public Dynarmic::A32::UserCallbacks {
    Cpu &cpu;
    Runtime &rt;
    std::unique_ptr<Dynarmic::A32::Jit> jit;

    Impl(Cpu &c) : cpu(c), rt(c.rt) {
        Dynarmic::A32::UserConfig cfg;
        cfg.callbacks = this;
        cfg.processor_id = rt.next_processor_id();
        cfg.global_monitor = rt.monitor.get();
        cfg.fastmem_pointer = reinterpret_cast<uintptr_t>(rt.mem.base());
        cfg.fastmem_exclusive_access = true;
        cfg.recompile_on_fastmem_failure = true;
        cfg.enable_cycle_counting = false;
        cfg.define_unpredictable_behaviour = true;
        cfg.arch_version = Dynarmic::A32::ArchVersion::v7;
        cfg.code_cache_size = 64 * 1024 * 1024;
        jit = std::make_unique<Dynarmic::A32::Jit>(cfg);
    }

    // Slow-path memory callbacks: only reached when fastmem faults (unmapped guest memory).
    template <typename T> T rd(uint32_t a) {
        rt.stop(StopReason::BadMemory, "game read unmapped memory at 0x" + hex(a) + " (pc 0x" + hex(jit->Regs()[15]) + ")");
        jit->HaltExecution();
        return 0;
    }
    template <typename T> void wr(uint32_t a, T) {
        rt.stop(StopReason::BadMemory, "game wrote unmapped memory at 0x" + hex(a) + " (pc 0x" + hex(jit->Regs()[15]) + ")");
        jit->HaltExecution();
    }
    static std::string hex(uint32_t v) {
        char b[16];
        snprintf(b, sizeof b, "%08x", v);
        return b;
    }

    std::optional<std::uint32_t> MemoryReadCode(Dynarmic::A32::VAddr a) override {
        uint32_t v;
        std::memcpy(&v, rt.mem.host(a), 4);  // code pages are always committed
        return v;
    }
    std::uint8_t MemoryRead8(Dynarmic::A32::VAddr a) override { return rd<uint8_t>(a); }
    std::uint16_t MemoryRead16(Dynarmic::A32::VAddr a) override { return rd<uint16_t>(a); }
    std::uint32_t MemoryRead32(Dynarmic::A32::VAddr a) override { return rd<uint32_t>(a); }
    std::uint64_t MemoryRead64(Dynarmic::A32::VAddr a) override { return rd<uint64_t>(a); }
    void MemoryWrite8(Dynarmic::A32::VAddr a, std::uint8_t v) override { wr(a, v); }
    void MemoryWrite16(Dynarmic::A32::VAddr a, std::uint16_t v) override { wr(a, v); }
    void MemoryWrite32(Dynarmic::A32::VAddr a, std::uint32_t v) override { wr(a, v); }
    void MemoryWrite64(Dynarmic::A32::VAddr a, std::uint64_t v) override { wr(a, v); }
    bool MemoryWriteExclusive8(Dynarmic::A32::VAddr a, std::uint8_t v, std::uint8_t) override { wr(a, v); return false; }
    bool MemoryWriteExclusive16(Dynarmic::A32::VAddr a, std::uint16_t v, std::uint16_t) override { wr(a, v); return false; }
    bool MemoryWriteExclusive32(Dynarmic::A32::VAddr a, std::uint32_t v, std::uint32_t) override { wr(a, v); return false; }
    bool MemoryWriteExclusive64(Dynarmic::A32::VAddr a, std::uint64_t v, std::uint64_t) override { wr(a, v); return false; }

    void InterpreterFallback(Dynarmic::A32::VAddr pc, size_t) override {
        rt.stop(StopReason::CpuException, "unsupported instruction at 0x" + hex(pc));
        jit->HaltExecution();
    }
    void CallSVC(std::uint32_t swi) override { rt.dispatch(cpu, swi); }
    void ExceptionRaised(Dynarmic::A32::VAddr pc, Dynarmic::A32::Exception e) override {
        rt.stop(StopReason::CpuException,
                "CPU exception " + std::to_string(static_cast<int>(e)) + " at 0x" + hex(pc));
        jit->HaltExecution();
    }
    void AddTicks(std::uint64_t) override {}
    std::uint64_t GetTicksRemaining() override { return 1ull << 40; }
};

Cpu::Cpu(Runtime &r, gaddr stack_top) : rt(r), impl_(std::make_unique<Impl>(*this)), stack_top_(stack_top) {
    errno_addr = rt.malloc(4);
}

Cpu::~Cpu() = default;

Cpu *Cpu::current() { return t_current; }
void Cpu::halt() { impl_->jit->HaltExecution(); }

uint32_t &Cpu::reg(int i) { return impl_->jit->Regs()[i]; }
uint32_t Cpu::sp() { return reg(13); }
uint32_t &Cpu::ext(int i) { return impl_->jit->ExtRegs()[i]; }

uint32_t Cpu::arg(int i) {
    if (i < 4) return reg(i);
    return rt.mem.read32(sp() + 4 * (i - 4));
}
uint64_t Cpu::arg64(int r) {
    uint32_t lo, hi;
    if (r < 4) {
        lo = reg(r);
        hi = r + 1 < 4 ? reg(r + 1) : rt.mem.read32(sp());
    } else {
        lo = arg(r);
        hi = arg(r + 1);
    }
    return lo | (static_cast<uint64_t>(hi) << 32);
}
float Cpu::argf(int i) {
    uint32_t v = arg(i);
    float f;
    std::memcpy(&f, &v, 4);
    return f;
}
double Cpu::argd(int r) {
    uint64_t v = arg64(r);
    double d;
    std::memcpy(&d, &v, 8);
    return d;
}
void Cpu::ret(uint32_t v) { reg(0) = v; }
void Cpu::ret64(uint64_t v) {
    reg(0) = static_cast<uint32_t>(v);
    reg(1) = static_cast<uint32_t>(v >> 32);
}
void Cpu::retf(float v) {
    uint32_t u;
    std::memcpy(&u, &v, 4);
    reg(0) = u;
}
void Cpu::retd(double v) {
    uint64_t u;
    std::memcpy(&u, &v, 8);
    ret64(u);
}

void Cpu::set_errno(int e) { rt.mem.write32(errno_addr, static_cast<uint32_t>(e)); }

void Cpu::set_return(gaddr pc, gaddr sp, const uint32_t callee_saved[8]) {
    for (int i = 0; i < 8; i++) reg(4 + i) = callee_saved[i];
    reg(13) = sp;
    reg(14) = pc;  // the thunk's "bx lr" jumps here
}

uint64_t Cpu::call(gaddr fn, const std::vector<uint32_t> &args) {
    if (running_) {
        // Host code (e.g. qsort, pthread_once) calling back into the game:
        // use a child CPU whose stack starts below our current stack pointer.
        if (!child_) child_ = std::make_unique<Cpu>(rt, 0);
        child_->stack_top_ = (sp() - 256) & ~7u;
        child_->errno_addr = errno_addr;
        return child_->call(fn, args);
    }
    auto &jit = *impl_->jit;
    std::array<uint32_t, 16> saved = jit.Regs();
    uint32_t saved_cpsr = jit.Cpsr();

    auto &r = jit.Regs();
    uint32_t sp = stack_top_;
    size_t nstack = args.size() > 4 ? args.size() - 4 : 0;
    sp -= static_cast<uint32_t>(nstack * 4);
    sp &= ~7u;
    for (size_t i = 0; i < args.size(); i++) {
        if (i < 4) r[i] = args[i];
        else rt.mem.write32(sp + static_cast<uint32_t>(4 * (i - 4)), args[i]);
    }
    r[13] = sp;
    r[14] = rt.return_sentinel();
    r[15] = fn & ~1u;
    jit.SetCpsr(0x10 | ((fn & 1) ? 0x20 : 0));  // user mode, Thumb bit from address
    jit.SetFpscr(0);

    Cpu *prev = t_current;
    t_current = this;
    running_ = true;
    while (rt.stop_reason == StopReason::None) {
        jit.Run();
        if (returned) break;
    }
    returned = false;
    running_ = false;
    t_current = prev;

    uint64_t result = r[0] | (static_cast<uint64_t>(r[1]) << 32);
    jit.Regs() = saved;
    jit.SetCpsr(saved_cpsr);
    return result;
}

}  // namespace po
