#include "runtime.h"

#include "cpu.h"

#include <cstdio>
#include <cstring>
#include <set>
#include <dynarmic/interface/exclusive_monitor.h>

namespace po {

static constexpr gaddr kHeapStart = 0x40000000;
static constexpr gaddr kHeapEnd = 0xF0000000;
static constexpr uint32_t kHeapCommitStep = 16 << 20;

Runtime::Runtime() : monitor(std::make_unique<Dynarmic::ExclusiveMonitor>(64)) {
    heap_start_ = heap_top_ = heap_committed_ = kHeapStart;
    heap_end_ = kHeapEnd;
    return_sentinel_ = thunks.function("__po_return", true);
    handlers_.resize(1);
    handlers_[0] = [](Cpu &cpu) {
        cpu.returned = true;
        cpu.halt();
    };
}

Runtime::~Runtime() = default;

void Runtime::log(const char *fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    std::lock_guard<std::mutex> lk(log_mutex_);
    if (log_sink) log_sink(buf);
    else std::fprintf(stderr, "%s\n", buf);
}

void Runtime::def(const std::string &name, HostFn fn) {
    gaddr a = thunks.function(name, true);
    uint32_t id = (a - thunks.all().front().addr) / 8;
    if (handlers_.size() <= id) handlers_.resize(id + 1);
    handlers_[id] = std::move(fn);
    (void)pending_;
}

void Runtime::alias(const std::string &name, const std::string &target) {
    gaddr t = thunks.function(target, true);
    uint32_t id = (t - thunks.all().front().addr) / 8;
    if (id < handlers_.size() && handlers_[id]) def(name, handlers_[id]);
}

gaddr Runtime::resolve_import(const std::string &name, bool is_function) {
    for (const Module &m : modules)
        if (gaddr a = m.find(name)) return a;
    auto d = data_overrides_.find(name);
    if (d != data_overrides_.end()) return d->second;
    if (!is_function) {
        if (thunks.has(name)) return thunks.data(name, 0);
        log("note: unknown data import %s – giving it 1 KB of zeroed memory", name.c_str());
        return thunks.data(name, 1024);
    }
    return thunks.function(name, false);  // existing thunk if def() ran, else a "missing" placeholder
}

Module &Runtime::load(const std::string &name, const std::vector<uint8_t> &elf) {
    Elf32Loader loader(mem);
    modules.push_back(loader.load(name, elf, [this](const std::string &n, bool fn) { return resolve_import(n, fn); }));
    return modules.back();
}

gaddr Runtime::symbol(const std::string &name) const {
    for (const Module &m : modules)
        if (gaddr a = m.find(name)) return a;
    return 0;
}

void Runtime::hook(gaddr fn, const std::string &name, HostFn host) {
    def(name, std::move(host));
    gaddr target = thunks.function(name, true);
    gaddr at = fn & ~1u;
    if (fn & 1) {
        // Thumb: ldr.w pc, [pc, #0] needs a 4-byte aligned literal right after it.
        if (at & 2) {
            mem.ptr<uint16_t>(at)[0] = 0xBF00;  // nop to align
            at += 2;
        }
        mem.ptr<uint16_t>(at)[0] = 0xF8DF;
        mem.ptr<uint16_t>(at)[1] = 0xF000;
        mem.write32(at + 4, target);
    } else {
        mem.write32(at, 0xE51FF004);  // ldr pc, [pc, #-4]
        mem.write32(at + 4, target);
    }
}

void Runtime::dispatch(Cpu &cpu, uint32_t id) {
    const Thunk *t = thunks.by_svc(id);
    if (id < handlers_.size() && handlers_[id]) {
        if (trace_calls && id != 0) log("-> %s", t ? t->name.c_str() : "?");
        handlers_[id](cpu);
        return;
    }
    std::string name = t ? t->name : "svc #" + std::to_string(id);
    if (is_graphics && is_graphics(name)) {
        stop(StopReason::GraphicsReached, "reached the first graphics call: " + name);
        cpu.halt();
        return;
    }
    if (stop_on_missing) {
        stop(StopReason::MissingFunction, "the game called a function PocketOuya doesn't provide yet: " + name);
        cpu.halt();
    } else {
        static std::set<std::string> seen;
        static std::mutex m;
        std::lock_guard<std::mutex> lk(m);
        if (seen.insert(name).second) log("missing function %s – returning 0", name.c_str());
        cpu.ret(0);
    }
}

void Runtime::stop(StopReason r, const std::string &why) {
    StopReason expected = StopReason::None;
    if (stop_reason.compare_exchange_strong(expected, r)) {
        stop_message = why;
        log("STOP: %s", why.c_str());
    }
}

std::string Runtime::read_string(gaddr p, size_t max) const {
    if (!p) return {};
    const char *s = mem.ptr<const char>(p);
    return std::string(s, strnlen(s, max));
}

gaddr Runtime::strdup(const std::string &s) {
    gaddr p = malloc(static_cast<uint32_t>(s.size() + 1));
    std::memcpy(mem.host(p), s.c_str(), s.size() + 1);
    return p;
}

// ---- Guest heap ---------------------------------------------------------
// Blocks carry an 8-byte header (size). Small sizes use power-of-two free lists.

static uint32_t size_class(uint32_t n) {
    uint32_t c = 16;
    while (c < n) c <<= 1;
    return c;
}

gaddr Runtime::heap_bump(uint32_t total) {
    if (heap_top_ + total > heap_end_ || heap_top_ + total < heap_top_) return 0;
    gaddr p = heap_top_;
    heap_top_ += total;
    while (heap_committed_ < heap_top_) {
        mem.commit(heap_committed_, kHeapCommitStep);
        heap_committed_ += kHeapCommitStep;
    }
    return p;
}

gaddr Runtime::malloc(uint32_t size) {
    std::lock_guard<std::mutex> lk(heap_mutex_);
    uint32_t need = (size + 15) & ~15u;
    if (need == 0) need = 16;
    gaddr block = 0;
    uint32_t cap;
    if (need <= 65536) {
        cap = size_class(need);
        auto &fl = free_small_[cap];
        if (!fl.empty()) {
            block = fl.back();
            fl.pop_back();
        }
    } else {
        cap = (need + 4095) & ~4095u;
        auto it = free_large_.lower_bound(cap);
        if (it != free_large_.end() && it->first <= cap * 2) {
            cap = it->first;
            block = it->second;
            free_large_.erase(it);
        }
    }
    if (!block) {
        block = heap_bump(cap + 16);
        if (!block) return 0;
    }
    mem.write32(block, cap);
    mem.write32(block + 4, 0xA110CA7E);
    return block + 16;  // 16-byte aligned payload
}

uint32_t Runtime::alloc_size(gaddr p) const { return p ? mem.read32(p - 16) : 0; }

void Runtime::free(gaddr p) {
    if (!p) return;
    std::lock_guard<std::mutex> lk(heap_mutex_);
    gaddr block = p - 16;
    if (mem.read32(block + 4) != 0xA110CA7E) {
        log("warning: free() of an address that wasn't malloc'd: 0x%08x", p);
        return;
    }
    mem.write32(block + 4, 0xF4EEF4EE);
    uint32_t cap = mem.read32(block);
    if (cap <= 65536) free_small_[cap].push_back(block);
    else free_large_.emplace(cap, block);
}

gaddr Runtime::realloc(gaddr p, uint32_t size) {
    if (!p) return malloc(size);
    uint32_t cap = alloc_size(p);
    if (size <= cap) return p;
    gaddr n = malloc(size);
    if (!n) return 0;
    std::memcpy(mem.host(n), mem.host(p), cap);
    free(p);
    return n;
}

}  // namespace po
