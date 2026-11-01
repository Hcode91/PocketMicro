// PocketOuya engine – the runtime: guest memory, CPU threads, host functions
// ("shims") that stand in for Android's libraries, and loaded modules.
#pragma once
#include "elf32_loader.h"
#include "guest_memory.h"
#include "thunks.h"

#include <atomic>
#include <cstdarg>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace Dynarmic { class ExclusiveMonitor; }

namespace po {

class Cpu;
using HostFn = std::function<void(Cpu &)>;

enum class StopReason { None, Finished, MissingFunction, BadMemory, CpuException, Exit, GraphicsReached };

class Runtime {
public:
    Runtime();
    ~Runtime();

    GuestMemory mem;
    ThunkTable thunks{mem};
    std::vector<Module> modules;
    std::unique_ptr<Dynarmic::ExclusiveMonitor> monitor;

    // Register a host implementation for an imported function name.
    void def(const std::string &name, HostFn fn);
    // Make `name` behave exactly like the already-defined `target`.
    void alias(const std::string &name, const std::string &target);
    // Address of a guest data object provided by us (e.g. __sF), created on first use.
    gaddr data(const std::string &name, uint32_t size) { return thunks.data(name, size); }
    void def_data(const std::string &name, gaddr addr) { data_overrides_[name] = addr; }

    // Load a 32-bit ARM .so (dependencies must already be loaded).
    Module &load(const std::string &name, const std::vector<uint8_t> &elf);
    gaddr symbol(const std::string &name) const;  // export from any loaded module
    // Replace a guest function with a host one (writes a jump to a thunk).
    void hook(gaddr fn, const std::string &name, HostFn host);

    // Called from the CPU when it executes "svc #id".
    void dispatch(Cpu &cpu, uint32_t id);
    gaddr return_sentinel() const { return return_sentinel_; }

    // Stop everything (all threads leave their run loops).
    void stop(StopReason r, const std::string &why);
    std::atomic<StopReason> stop_reason{StopReason::None};
    std::string stop_message;

    // Logging (Android build routes this to logcat).
    std::function<void(const std::string &)> log_sink;
    void log(const char *fmt, ...) __attribute__((format(printf, 2, 3)));
    bool trace_calls = false;    // log every host function call
    bool stop_on_missing = true; // stop when the game calls something we don't implement yet
    std::function<bool(const std::string &)> is_graphics;  // names that mean "graphics reached"

    // Guest heap.
    gaddr malloc(uint32_t size);
    void free(gaddr p);
    gaddr realloc(gaddr p, uint32_t size);
    uint32_t alloc_size(gaddr p) const;
    gaddr strdup(const std::string &s);

    std::string read_string(gaddr p, size_t max = 1 << 20) const;
    size_t next_processor_id() { return next_proc_++; }

private:
    std::vector<HostFn> handlers_;
    std::map<std::string, HostFn> pending_;  // defined before the thunk existed
    std::map<std::string, gaddr> data_overrides_;
    gaddr return_sentinel_ = 0;
    std::atomic<size_t> next_proc_{0};
    std::mutex log_mutex_;

    // Heap state
    mutable std::mutex heap_mutex_;
    gaddr heap_start_ = 0, heap_top_ = 0, heap_end_ = 0, heap_committed_ = 0;
    std::map<uint32_t, std::vector<gaddr>> free_small_;  // size class -> blocks
    std::multimap<uint32_t, gaddr> free_large_;           // size -> block
    gaddr heap_bump(uint32_t total);

    gaddr resolve_import(const std::string &name, bool is_function);
};

// Register all the libc/libm/pthread/... shims.
void register_libc(Runtime &rt);
void register_stdio(Runtime &rt);
void register_pthread(Runtime &rt);
void register_misc(Runtime &rt);

}  // namespace po
