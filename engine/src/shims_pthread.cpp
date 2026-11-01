// Threads: pthreads and semaphores. Every guest thread is a host thread with its own Cpu.
#include "abi.h"

#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <thread>

namespace po {

namespace {
std::mutex g_mx;  // guards the maps below
std::map<gaddr, std::unique_ptr<std::recursive_mutex>> g_mutexes;
std::map<gaddr, std::unique_ptr<std::condition_variable_any>> g_conds;
struct Sem { std::mutex m; std::condition_variable cv; uint32_t count = 0; };
std::map<gaddr, std::unique_ptr<Sem>> g_sems;
struct Thread { std::thread t; uint32_t result = 0; };
std::map<uint32_t, std::unique_ptr<Thread>> g_threads;
uint32_t g_next_thread = 1;
uint32_t g_next_key = 1;
thread_local std::map<uint32_t, uint32_t> t_keys;

std::recursive_mutex &mutex_for(gaddr a) {
    std::lock_guard<std::mutex> lk(g_mx);
    auto &m = g_mutexes[a];
    if (!m) m = std::make_unique<std::recursive_mutex>();
    return *m;
}
std::condition_variable_any &cond_for(gaddr a) {
    std::lock_guard<std::mutex> lk(g_mx);
    auto &c = g_conds[a];
    if (!c) c = std::make_unique<std::condition_variable_any>();
    return *c;
}
Sem &sem_for(gaddr a) {
    std::lock_guard<std::mutex> lk(g_mx);
    auto &s = g_sems[a];
    if (!s) s = std::make_unique<Sem>();
    return *s;
}
}  // namespace

void register_pthread(Runtime &rt) {
    auto ok = [](Cpu &c) { c.ret(0); };
    rt.def("pthread_attr_init", ok);
    rt.def("pthread_attr_destroy", ok);
    rt.def("pthread_attr_setstacksize", ok);
    rt.def("pthread_mutexattr_init", [&rt](Cpu &c) { rt.mem.write32(c.arg(0), 0); c.ret(0); });
    rt.def("pthread_mutexattr_destroy", ok);
    rt.def("pthread_mutexattr_settype", [&rt](Cpu &c) { rt.mem.write32(c.arg(0), c.arg(1)); c.ret(0); });

    rt.def("pthread_mutex_init", [](Cpu &c) { mutex_for(c.arg(0)); c.ret(0); });
    rt.def("pthread_mutex_destroy", [](Cpu &c) {
        std::lock_guard<std::mutex> lk(g_mx);
        g_mutexes.erase(c.arg(0));
        c.ret(0);
    });
    rt.def("pthread_mutex_lock", [](Cpu &c) { mutex_for(c.arg(0)).lock(); c.ret(0); });
    rt.def("pthread_mutex_unlock", [](Cpu &c) { mutex_for(c.arg(0)).unlock(); c.ret(0); });

    rt.def("pthread_cond_init", [](Cpu &c) { cond_for(c.arg(0)); c.ret(0); });
    rt.def("pthread_cond_destroy", ok);
    rt.def("pthread_cond_signal", [](Cpu &c) { cond_for(c.arg(0)).notify_one(); c.ret(0); });
    rt.def("pthread_cond_broadcast", [](Cpu &c) { cond_for(c.arg(0)).notify_all(); c.ret(0); });
    rt.def("pthread_cond_wait", [](Cpu &c) {
        auto &m = mutex_for(c.arg(1));
        std::unique_lock<std::recursive_mutex> lk(m, std::adopt_lock);
        cond_for(c.arg(0)).wait(lk);
        lk.release();
        c.ret(0);
    });
    rt.def("pthread_cond_timedwait", [&rt](Cpu &c) {
        auto &m = mutex_for(c.arg(1));
        int64_t s = static_cast<int32_t>(rt.mem.read32(c.arg(2)));
        int64_t ns = static_cast<int32_t>(rt.mem.read32(c.arg(2) + 4));
        auto deadline = std::chrono::system_clock::time_point(
            std::chrono::duration_cast<std::chrono::system_clock::duration>(std::chrono::seconds(s) + std::chrono::nanoseconds(ns)));
        std::unique_lock<std::recursive_mutex> lk(m, std::adopt_lock);
        auto r = cond_for(c.arg(0)).wait_until(lk, deadline);
        lk.release();
        c.ret(r == std::cv_status::timeout ? ETIMEDOUT : 0);
    });

    rt.def("pthread_once", [&rt](Cpu &c) {
        static std::recursive_mutex once_mutex;
        std::lock_guard<std::recursive_mutex> lk(once_mutex);
        if (rt.mem.read32(c.arg(0)) == 0) {
            rt.mem.write32(c.arg(0), 1);
            c.call(c.arg(1));
        }
        c.ret(0);
    });

    rt.def("pthread_key_create", [&rt](Cpu &c) {
        std::lock_guard<std::mutex> lk(g_mx);
        rt.mem.write32(c.arg(0), g_next_key++);
        c.ret(0);
    });
    rt.def("pthread_key_delete", ok);
    rt.def("pthread_getspecific", [](Cpu &c) {
        auto it = t_keys.find(c.arg(0));
        c.ret(it == t_keys.end() ? 0 : it->second);
    });
    rt.def("pthread_setspecific", [](Cpu &c) { t_keys[c.arg(0)] = c.arg(1); c.ret(0); });

    rt.def("pthread_create", [&rt](Cpu &c) {
        gaddr out = c.arg(0), fn = c.arg(2), argp = c.arg(3);
        gaddr stack = rt.mem.reserve_region(1 << 20);
        uint32_t id;
        Thread *th;
        {
            std::lock_guard<std::mutex> lk(g_mx);
            id = g_next_thread++;
            auto &slot = g_threads[id];
            slot = std::make_unique<Thread>();
            th = slot.get();
        }
        rt.log("game started thread %u (entry 0x%08x)", id, fn);
        th->t = std::thread([&rt, th, fn, argp, stack] {
            Cpu cpu(rt, stack + (1 << 20) - 16);
            th->result = static_cast<uint32_t>(cpu.call(fn, {argp}));
        });
        rt.mem.write32(out, id);
        c.ret(0);
    });
    rt.def("pthread_join", [&rt](Cpu &c) {
        std::unique_ptr<Thread> th;
        {
            std::lock_guard<std::mutex> lk(g_mx);
            auto it = g_threads.find(c.arg(0));
            if (it == g_threads.end()) { c.ret(ESRCH); return; }
            th = std::move(it->second);
            g_threads.erase(it);
        }
        if (th->t.joinable()) th->t.join();
        if (c.arg(1)) rt.mem.write32(c.arg(1), th->result);
        c.ret(0);
    });
    rt.def("pthread_detach", [](Cpu &c) {
        std::lock_guard<std::mutex> lk(g_mx);
        auto it = g_threads.find(c.arg(0));
        if (it != g_threads.end() && it->second->t.joinable()) it->second->t.detach();
        c.ret(0);
    });

    rt.def("sem_init", [](Cpu &c) { sem_for(c.arg(0)).count = c.arg(2); c.ret(0); });
    rt.def("sem_destroy", ok);
    rt.def("sem_post", [](Cpu &c) {
        Sem &s = sem_for(c.arg(0));
        { std::lock_guard<std::mutex> lk(s.m); s.count++; }
        s.cv.notify_one();
        c.ret(0);
    });
    rt.def("sem_wait", [](Cpu &c) {
        Sem &s = sem_for(c.arg(0));
        std::unique_lock<std::mutex> lk(s.m);
        s.cv.wait(lk, [&] { return s.count > 0; });
        s.count--;
        c.ret(0);
    });
}

}  // namespace po
