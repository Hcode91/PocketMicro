// Everything else: libdl, Android log, C++ ABI, setjmp/longjmp, zlib, processes,
// signals and networking (networking is stubbed – Ouya games don't need it offline).
#include "abi.h"

#include <arpa/inet.h>
#include <cerrno>
#include <chrono>
#include <thread>
#include <unistd.h>
#include <zlib.h>

namespace po {

namespace {
// zlib: guest z_stream (56 bytes, 32-bit pointers) mirrored into a host z_stream.
struct ZGuest {
    uint32_t next_in, avail_in, total_in, next_out, avail_out, total_out, msg, state, zalloc, zfree, opaque;
    int32_t data_type;
    uint32_t adler, reserved;
};
std::mutex g_zmx;
std::map<gaddr, std::unique_ptr<z_stream>> g_z;

z_stream *zhost(Runtime &rt, gaddr g, bool create = false) {
    std::lock_guard<std::mutex> lk(g_zmx);
    auto &z = g_z[g];
    if (!z && create) { z = std::make_unique<z_stream>(); std::memset(z.get(), 0, sizeof(z_stream)); }
    return z.get();
}
void zin(Runtime &rt, gaddr g, z_stream *z) {
    ZGuest s;
    std::memcpy(&s, rt.mem.host(g), sizeof s);
    z->next_in = s.next_in ? rt.mem.host(s.next_in) : nullptr;
    z->avail_in = s.avail_in;
    z->next_out = s.next_out ? rt.mem.host(s.next_out) : nullptr;
    z->avail_out = s.avail_out;
    z->total_in = s.total_in;
    z->total_out = s.total_out;
}
void zout(Runtime &rt, gaddr g, z_stream *z) {
    ZGuest s;
    std::memcpy(&s, rt.mem.host(g), sizeof s);
    s.next_in = z->next_in ? rt.mem.guest(z->next_in) : 0;
    s.avail_in = z->avail_in;
    s.next_out = z->next_out ? rt.mem.guest(z->next_out) : 0;
    s.avail_out = z->avail_out;
    s.total_in = static_cast<uint32_t>(z->total_in);
    s.total_out = static_cast<uint32_t>(z->total_out);
    s.adler = static_cast<uint32_t>(z->adler);
    s.data_type = z->data_type;
    s.msg = 0;
    std::memcpy(rt.mem.host(g), &s, sizeof s);
}
}  // namespace

void register_misc(Runtime &rt) {
    auto ok = [](Cpu &c) { c.ret(0); };
    auto fail = [](Cpu &c) { c.set_errno(ENOSYS); c.ret(static_cast<uint32_t>(-1)); };

    // ---- libdl: graphics libraries are "opened" and their functions handed out as thunks ----
    rt.def("dlopen", [&rt](Cpu &c) {
        std::string n = rt.read_string(c.arg(0));
        // No sound bridge yet (milestone 5): refuse OpenSL ES so OpenAL falls back to silence.
        if (n.find("OpenSLES") != std::string::npos) {
            rt.log("game dlopen(\"%s\") – not available yet, game continues without sound", n.c_str());
            c.ret(0);
            return;
        }
        rt.log("game dlopen(\"%s\")", n.c_str());
        c.ret(0x7F000001);  // any non-null handle; dlsym searches everything
    });
    rt.def("dlclose", ok);
    rt.def("dlerror", [&rt](Cpu &c) {
        static gaddr msg = 0;
        if (!msg) msg = rt.strdup("library not available");
        c.ret(msg);
    });
    rt.def("dlsym", [&rt](Cpu &c) {
        std::string n = rt.read_string(c.arg(1));
        gaddr a = rt.symbol(n);
        if (!a) a = rt.thunks.function(n, false);
        c.ret(a);
    });

    // ---- Android log ----
    auto prio = [](uint32_t p) { static const char *n[] = {"?", "V", "V", "D", "I", "W", "E", "F", "S"}; return n[p < 9 ? p : 0]; };
    rt.def("__android_log_write", [&rt, prio](Cpu &c) {
        rt.log("[%s/%s] %s", prio(c.arg(0)), rt.read_string(c.arg(1)).c_str(), rt.read_string(c.arg(2)).c_str());
        c.ret(1);
    });
    rt.def("__android_log_print", [&rt, prio](Cpu &c) {
        RegArgs a(c, 3);
        rt.log("[%s/%s] %s", prio(c.arg(0)), rt.read_string(c.arg(1)).c_str(),
               guest_format(rt, rt.mem.ptr<char>(c.arg(2)), a).c_str());
        c.ret(1);
    });
    rt.def("__android_log_vprint", [&rt, prio](Cpu &c) {
        VaArgs a(rt.mem, c.arg(3));
        rt.log("[%s/%s] %s", prio(c.arg(0)), rt.read_string(c.arg(1)).c_str(),
               guest_format(rt, rt.mem.ptr<char>(c.arg(2)), a).c_str());
        c.ret(1);
    });

    // ---- C++ ABI ----
    rt.def("__cxa_atexit", ok);
    rt.def("__aeabi_atexit", ok);
    rt.def("__cxa_finalize", [](Cpu &) {});
    rt.def("__gnu_Unwind_Find_exidx", [&rt](Cpu &c) {
        gaddr pc = c.arg(0);
        for (const Module &m : rt.modules) {
            if (pc >= m.base && pc < m.base + m.size + 0x100000 && m.exidx) {
                rt.mem.write32(c.arg(1), m.exidx_count);
                c.ret(m.exidx);
                return;
            }
        }
        c.ret(0);
    });

    // ---- setjmp / longjmp: save r4-r11, sp, lr and d8-d15 into the guest jmp_buf ----
    auto save = [&rt](Cpu &c) {
        gaddr b = c.arg(0);
        for (int i = 0; i < 8; i++) rt.mem.write32(b + 4 * i, c.reg(4 + i));
        rt.mem.write32(b + 32, c.reg(13));
        rt.mem.write32(b + 36, c.reg(14));
        for (int i = 0; i < 16; i++) rt.mem.write32(b + 40 + 4 * i, c.ext(16 + i));
        c.ret(0);
    };
    auto jump = [&rt](Cpu &c) {
        gaddr b = c.arg(0);
        uint32_t val = c.arg(1) ? c.arg(1) : 1;
        uint32_t saved[8];
        for (int i = 0; i < 8; i++) saved[i] = rt.mem.read32(b + 4 * i);
        for (int i = 0; i < 16; i++) c.ext(16 + i) = rt.mem.read32(b + 40 + 4 * i);
        c.set_return(rt.mem.read32(b + 36), rt.mem.read32(b + 32), saved);
        c.ret(val);
    };
    rt.def("setjmp", save);
    rt.def("sigsetjmp", save);
    rt.def("longjmp", jump);
    rt.def("siglongjmp", jump);

    // ---- zlib ----
    rt.def("crc32", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(::crc32(c.arg(0), c.arg(1) ? rt.mem.host(c.arg(1)) : nullptr, c.arg(2)))); });
    rt.def("zError", [&rt](Cpu &c) { c.ret(rt.strdup(::zError(static_cast<int>(c.arg(0))))); });
    rt.def("inflateInit_", [&rt](Cpu &c) { z_stream *z = zhost(rt, c.arg(0), true); zin(rt, c.arg(0), z); int r = inflateInit(z); zout(rt, c.arg(0), z); c.ret(static_cast<uint32_t>(r)); });
    rt.def("inflateInit2_", [&rt](Cpu &c) { z_stream *z = zhost(rt, c.arg(0), true); zin(rt, c.arg(0), z); int r = inflateInit2(z, static_cast<int>(c.arg(1))); zout(rt, c.arg(0), z); c.ret(static_cast<uint32_t>(r)); });
    rt.def("deflateInit_", [&rt](Cpu &c) { z_stream *z = zhost(rt, c.arg(0), true); zin(rt, c.arg(0), z); int r = deflateInit(z, static_cast<int>(c.arg(1))); zout(rt, c.arg(0), z); c.ret(static_cast<uint32_t>(r)); });
    rt.def("deflateInit2_", [&rt](Cpu &c) {
        z_stream *z = zhost(rt, c.arg(0), true);
        zin(rt, c.arg(0), z);
        int r = deflateInit2(z, static_cast<int>(c.arg(1)), static_cast<int>(c.arg(2)), static_cast<int>(c.arg(3)),
                             static_cast<int>(c.arg(4)), static_cast<int>(c.arg(5)));
        zout(rt, c.arg(0), z);
        c.ret(static_cast<uint32_t>(r));
    });
    auto zop = [&rt](int (*fn)(z_stream *, int), bool has_flush) {
        return [&rt, fn, has_flush](Cpu &c) {
            z_stream *z = zhost(rt, c.arg(0));
            if (!z) { c.ret(static_cast<uint32_t>(Z_STREAM_ERROR)); return; }
            zin(rt, c.arg(0), z);
            int r = fn(z, has_flush ? static_cast<int>(c.arg(1)) : 0);
            zout(rt, c.arg(0), z);
            c.ret(static_cast<uint32_t>(r));
        };
    };
    rt.def("inflate", zop([](z_stream *z, int f) { return ::inflate(z, f); }, true));
    rt.def("deflate", zop([](z_stream *z, int f) { return ::deflate(z, f); }, true));
    rt.def("inflateReset", zop([](z_stream *z, int) { return ::inflateReset(z); }, false));
    rt.def("deflateReset", zop([](z_stream *z, int) { return ::deflateReset(z); }, false));
    auto zend = [&rt](bool inflate_side) {
        return [&rt, inflate_side](Cpu &c) {
            z_stream *z = zhost(rt, c.arg(0));
            int r = z ? (inflate_side ? ::inflateEnd(z) : ::deflateEnd(z)) : Z_STREAM_ERROR;
            std::lock_guard<std::mutex> lk(g_zmx);
            g_z.erase(c.arg(0));
            c.ret(static_cast<uint32_t>(r));
        };
    };
    rt.def("inflateEnd", zend(true));
    rt.def("deflateEnd", zend(false));

    // ---- process / signals ----
    rt.def("getpid", [](Cpu &c) { c.ret(static_cast<uint32_t>(::getpid())); });
    rt.def("getppid", [](Cpu &c) { c.ret(1); });
    rt.def("getuid", [](Cpu &c) { c.ret(10000); });
    rt.def("geteuid", [](Cpu &c) { c.ret(10000); });
    rt.def("getpwuid", ok);
    rt.def("alarm", ok);
    rt.def("sigaction", ok);
    rt.def("raise", ok);
    rt.def("vfork", fail);
    rt.def("execl", fail);

    // ---- networking (offline) ----
    for (const char *n : {"socket", "bind", "connect", "listen", "accept", "send", "sendto", "recv", "recvfrom",
                          "setsockopt", "getsockopt", "getsockname", "getpeername", "shutdown"})
        rt.def(n, fail);
    rt.def("getaddrinfo", [](Cpu &c) { c.ret(static_cast<uint32_t>(-4)); });  // EAI_FAIL
    rt.def("freeaddrinfo", [](Cpu &) {});
    rt.def("gethostbyname", ok);
    rt.def("inet_addr", [&rt](Cpu &c) { c.ret(::inet_addr(rt.read_string(c.arg(0)).c_str())); });
    rt.def("inet_ntoa", [&rt](Cpu &c) {
        static thread_local gaddr buf = 0;
        if (!buf) buf = rt.malloc(16);
        struct in_addr a;
        a.s_addr = c.arg(0);
        std::snprintf(rt.mem.ptr<char>(buf), 16, "%s", ::inet_ntoa(a));
        c.ret(buf);
    });
    rt.def("inet_ntop", fail);
    rt.def("inet_pton", ok);
    rt.def("poll", [&rt](Cpu &c) {
        int32_t ms = static_cast<int32_t>(c.arg(2));
        if (ms > 0) std::this_thread::sleep_for(std::chrono::milliseconds(ms));
        c.ret(0);
    });
    rt.def("select", [&rt](Cpu &c) {
        if (c.arg(4)) {
            uint32_t s = rt.mem.read32(c.arg(4)), us = rt.mem.read32(c.arg(4) + 4);
            std::this_thread::sleep_for(std::chrono::seconds(s) + std::chrono::microseconds(us));
        }
        c.ret(0);
    });
}

}  // namespace po
