// libc: memory, strings, ctype tables, stdlib, time, maths.
#include "abi.h"

#include <cctype>
#include <cerrno>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstdlib>
#include <ctime>
#include <thread>
#include <unistd.h>

namespace po {

#define S(i) rt.mem.ptr<char>(c.arg(i))
#define P(i) rt.mem.host(c.arg(i))
#define GADDR(hostptr) rt.mem.guest(hostptr)

static void fill_tm(Runtime &rt, gaddr out, const struct tm &t) {
    int32_t v[9] = {t.tm_sec, t.tm_min, t.tm_hour, t.tm_mday, t.tm_mon, t.tm_year, t.tm_wday, t.tm_yday, t.tm_isdst};
    std::memcpy(rt.mem.host(out), v, sizeof v);
    rt.mem.write32(out + 36, static_cast<uint32_t>(t.tm_gmtoff));
    rt.mem.write32(out + 40, 0);  // tm_zone
}
static struct tm read_tm(Runtime &rt, gaddr in) {
    struct tm t{};
    int32_t v[9];
    std::memcpy(v, rt.mem.host(in), sizeof v);
    t.tm_sec = v[0]; t.tm_min = v[1]; t.tm_hour = v[2]; t.tm_mday = v[3]; t.tm_mon = v[4];
    t.tm_year = v[5]; t.tm_wday = v[6]; t.tm_yday = v[7]; t.tm_isdst = v[8];
    return t;
}

void register_libc(Runtime &rt) {
    // ---- ctype tables (bionic layout) ----
    {
        gaddr ct = rt.data("_ctype_", 260);
        uint8_t *t = rt.mem.host(ct);
        for (int ch = 0; ch < 256; ch++) {
            uint8_t f = 0;
            if (ch < 128) {
                if (isupper(ch)) f |= 0x01;
                if (islower(ch)) f |= 0x02;
                if (isdigit(ch)) f |= 0x04;
                if (isspace(ch)) f |= 0x08;
                if (ispunct(ch)) f |= 0x10;
                if (iscntrl(ch)) f |= 0x20;
                if (isxdigit(ch) && !isdigit(ch)) f |= 0x40;
                if (ch == ' ') f |= 0x80;
            }
            t[1 + ch] = f;  // _ctype_[0] is for EOF
        }
        gaddr lo = rt.data("_tolower_tab_", 520), up = rt.data("_toupper_tab_", 520);
        auto *l = rt.mem.ptr<int16_t>(lo), *u = rt.mem.ptr<int16_t>(up);
        l[0] = u[0] = -1;
        for (int ch = 0; ch < 256; ch++) {
            l[1 + ch] = static_cast<int16_t>(ch < 128 ? tolower(ch) : ch);
            u[1 + ch] = static_cast<int16_t>(ch < 128 ? toupper(ch) : ch);
        }
        rt.mem.write32(rt.data("__stack_chk_guard", 4), 0x2B7E1516);
    }

    // ---- memory ----
    rt.def("malloc", [&rt](Cpu &c) { c.ret(rt.malloc(c.arg(0))); });
    rt.def("calloc", [&rt](Cpu &c) {
        uint64_t n = static_cast<uint64_t>(c.arg(0)) * c.arg(1);
        gaddr p = n < 0xF0000000ull ? rt.malloc(static_cast<uint32_t>(n)) : 0;
        if (p) std::memset(rt.mem.host(p), 0, n);
        c.ret(p);
    });
    rt.def("realloc", [&rt](Cpu &c) { c.ret(rt.realloc(c.arg(0), c.arg(1))); });
    rt.def("free", [&rt](Cpu &c) { rt.free(c.arg(0)); });
    rt.def("memcpy", [&rt](Cpu &c) { std::memcpy(P(0), P(1), c.arg(2)); c.ret(c.arg(0)); });
    rt.def("memmove", [&rt](Cpu &c) { std::memmove(P(0), P(1), c.arg(2)); c.ret(c.arg(0)); });
    rt.def("memset", [&rt](Cpu &c) { std::memset(P(0), static_cast<int>(c.arg(1)), c.arg(2)); c.ret(c.arg(0)); });
    rt.def("memcmp", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(std::memcmp(P(0), P(1), c.arg(2)))); });
    rt.def("memchr", [&rt](Cpu &c) {
        void *r = std::memchr(P(0), static_cast<int>(c.arg(1)), c.arg(2));
        c.ret(r ? GADDR(r) : 0);
    });
    rt.def("memrchr", [&rt](Cpu &c) {
        auto *p = reinterpret_cast<uint8_t *>(P(0));
        uint32_t n = c.arg(2);
        uint8_t ch = static_cast<uint8_t>(c.arg(1));
        gaddr r = 0;
        while (n--) if (p[n] == ch) { r = c.arg(0) + n; break; }
        c.ret(r);
    });
    rt.def("memmem", [&rt](Cpu &c) {
        void *r = ::memmem(P(0), c.arg(1), P(2), c.arg(3));
        c.ret(r ? GADDR(r) : 0);
    });
    rt.def("__aeabi_memcpy", [&rt](Cpu &c) { std::memcpy(P(0), P(1), c.arg(2)); });

    // ---- strings ----
    rt.def("strlen", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(std::strlen(S(0)))); });
    rt.def("strcmp", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(std::strcmp(S(0), S(1)))); });
    rt.def("strncmp", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(std::strncmp(S(0), S(1), c.arg(2)))); });
    rt.def("strcasecmp", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(::strcasecmp(S(0), S(1)))); });
    rt.def("strncasecmp", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(::strncasecmp(S(0), S(1), c.arg(2)))); });
    rt.def("strcpy", [&rt](Cpu &c) { std::strcpy(S(0), S(1)); c.ret(c.arg(0)); });
    rt.def("strncpy", [&rt](Cpu &c) { std::strncpy(S(0), S(1), c.arg(2)); c.ret(c.arg(0)); });
    rt.def("strcat", [&rt](Cpu &c) { std::strcat(S(0), S(1)); c.ret(c.arg(0)); });
    rt.def("strncat", [&rt](Cpu &c) { std::strncat(S(0), S(1), c.arg(2)); c.ret(c.arg(0)); });
    rt.def("strlcat", [&rt](Cpu &c) {
        char *d = S(0);
        const char *s = S(1);
        size_t n = c.arg(2), dl = strnlen(d, n), sl = std::strlen(s);
        if (dl < n) {
            size_t k = std::min(sl, n - dl - 1);
            std::memcpy(d + dl, s, k);
            d[dl + k] = 0;
        }
        c.ret(static_cast<uint32_t>(dl + sl));
    });
    auto find = [&rt](Cpu &c, char *r) { c.ret(r ? GADDR(r) : 0); };
    rt.def("strchr", [&rt, find](Cpu &c) { find(c, std::strchr(S(0), static_cast<int>(c.arg(1)))); });
    rt.def("strrchr", [&rt, find](Cpu &c) { find(c, std::strrchr(S(0), static_cast<int>(c.arg(1)))); });
    rt.def("strstr", [&rt, find](Cpu &c) { find(c, std::strstr(S(0), S(1))); });
    rt.def("strpbrk", [&rt, find](Cpu &c) { find(c, std::strpbrk(S(0), S(1))); });
    rt.def("strdup", [&rt](Cpu &c) { c.ret(rt.strdup(S(0))); });
    rt.def("strndup", [&rt](Cpu &c) { c.ret(rt.strdup(std::string(S(0), strnlen(S(0), c.arg(1))))); });
    rt.def("strtok_r", [&rt, find](Cpu &c) {
        char *save = c.arg(2) && rt.mem.read32(c.arg(2)) ? rt.mem.ptr<char>(rt.mem.read32(c.arg(2))) : nullptr;
        char *r = strtok_r(c.arg(0) ? S(0) : nullptr, S(1), &save);
        rt.mem.write32(c.arg(2), save ? GADDR(save) : 0);
        find(c, r);
    });
    rt.def("strtok", [&rt, find](Cpu &c) {
        static thread_local char *save = nullptr;
        find(c, strtok_r(c.arg(0) ? S(0) : nullptr, S(1), &save));
    });
    rt.def("strerror", [&rt](Cpu &c) {
        static std::map<int, gaddr> cache;
        static std::mutex m;
        std::lock_guard<std::mutex> lk(m);
        int e = static_cast<int>(c.arg(0));
        if (!cache.count(e)) cache[e] = rt.strdup(std::strerror(e));
        c.ret(cache[e]);
    });
    rt.def("strerror_r", [&rt](Cpu &c) {
        std::snprintf(S(1), c.arg(2), "%s", std::strerror(static_cast<int>(c.arg(0))));
        c.ret(0);
    });
    rt.def("wcslen", [&rt](Cpu &c) {
        auto *w = rt.mem.ptr<uint32_t>(c.arg(0));
        uint32_t n = 0;
        while (w[n]) n++;
        c.ret(n);
    });
    rt.def("basename", [&rt](Cpu &c) {
        const char *s = S(0);
        const char *b = std::strrchr(s, '/');
        c.ret(b ? c.arg(0) + static_cast<uint32_t>(b + 1 - s) : c.arg(0));
    });

    // ---- numbers ----
    rt.def("atoi", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(std::atoi(S(0)))); });
    rt.def("atol", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(std::atol(S(0)))); });
    auto endp = [&rt](Cpu &c, int i, const char *start, char *end) {
        if (c.arg(i)) rt.mem.write32(c.arg(i), c.arg(0) + static_cast<uint32_t>(end - start));
    };
    rt.def("strtol", [&rt, endp](Cpu &c) {
        char *e;
        long v = std::strtol(S(0), &e, static_cast<int>(c.arg(2)));
        endp(c, 1, S(0), e);
        if (v > INT32_MAX) v = INT32_MAX;
        if (v < INT32_MIN) v = INT32_MIN;
        c.ret(static_cast<uint32_t>(v));
    });
    rt.def("strtoul", [&rt, endp](Cpu &c) {
        char *e;
        unsigned long v = std::strtoul(S(0), &e, static_cast<int>(c.arg(2)));
        endp(c, 1, S(0), e);
        c.ret(static_cast<uint32_t>(std::min<unsigned long>(v, UINT32_MAX)));
    });
    rt.def("strtod", [&rt, endp](Cpu &c) {
        char *e;
        double v = std::strtod(S(0), &e);
        endp(c, 1, S(0), e);
        c.retd(v);
    });
    rt.def("ldiv", [&rt](Cpu &c) {  // struct return via hidden pointer in r0
        int32_t n = static_cast<int32_t>(c.arg(1)), d = static_cast<int32_t>(c.arg(2));
        rt.mem.write32(c.arg(0), static_cast<uint32_t>(n / d));
        rt.mem.write32(c.arg(0) + 4, static_cast<uint32_t>(n % d));
    });
    rt.def("qsort", [&rt](Cpu &c) {
        gaddr base = c.arg(0), cmp = c.arg(3);
        uint32_t n = c.arg(1), sz = c.arg(2);
        // Simple insertion sort through the guest comparator (sizes are small at startup).
        std::vector<uint8_t> tmp(sz);
        for (uint32_t i = 1; i < n; i++) {
            std::memcpy(tmp.data(), rt.mem.host(base + i * sz), sz);
            gaddr scratch = rt.malloc(sz);
            std::memcpy(rt.mem.host(scratch), tmp.data(), sz);
            uint32_t j = i;
            while (j > 0 && static_cast<int32_t>(c.call(cmp, {base + (j - 1) * sz, scratch})) > 0) {
                std::memcpy(rt.mem.host(base + j * sz), rt.mem.host(base + (j - 1) * sz), sz);
                j--;
            }
            std::memcpy(rt.mem.host(base + j * sz), tmp.data(), sz);
            rt.free(scratch);
        }
    });
    rt.def("srand48", [](Cpu &c) { srand48(c.arg(0)); });
    rt.def("lrand48", [](Cpu &c) { c.ret(static_cast<uint32_t>(lrand48() & 0x7fffffff)); });
    rt.def("getenv", [](Cpu &c) { c.ret(0); });
    rt.def("abort", [&rt](Cpu &c) { rt.stop(StopReason::Exit, "game called abort()"); c.halt(); });
    rt.def("exit", [&rt](Cpu &c) { rt.stop(StopReason::Exit, "game called exit(" + std::to_string(c.arg(0)) + ")"); c.halt(); });
    rt.def("_exit", [&rt](Cpu &c) { rt.stop(StopReason::Exit, "game called _exit()"); c.halt(); });
    rt.def("__stack_chk_fail", [&rt](Cpu &c) { rt.stop(StopReason::CpuException, "stack corruption detected"); c.halt(); });
    rt.def("__assert2", [&rt](Cpu &c) {
        rt.stop(StopReason::CpuException, "assertion failed: " + rt.read_string(c.arg(3)) + " at " +
                                              rt.read_string(c.arg(0)) + ":" + std::to_string(c.arg(1)));
        c.halt();
    });
    rt.def("__errno", [](Cpu &c) { c.ret(c.errno_addr); });

    // ---- time ----
    rt.def("time", [&rt](Cpu &c) {
        uint32_t t = static_cast<uint32_t>(::time(nullptr));
        if (c.arg(0)) rt.mem.write32(c.arg(0), t);
        c.ret(t);
    });
    rt.def("clock_gettime", [&rt](Cpu &c) {
        struct timespec ts;
        clock_gettime(static_cast<clockid_t>(c.arg(0)), &ts);
        rt.mem.write32(c.arg(1), static_cast<uint32_t>(ts.tv_sec));
        rt.mem.write32(c.arg(1) + 4, static_cast<uint32_t>(ts.tv_nsec));
        c.ret(0);
    });
    rt.def("gettimeofday", [&rt](Cpu &c) {
        struct timeval tv;
        gettimeofday(&tv, nullptr);
        if (c.arg(0)) {
            rt.mem.write32(c.arg(0), static_cast<uint32_t>(tv.tv_sec));
            rt.mem.write32(c.arg(0) + 4, static_cast<uint32_t>(tv.tv_usec));
        }
        c.ret(0);
    });
    rt.def("nanosleep", [&rt](Cpu &c) {
        uint32_t s = rt.mem.read32(c.arg(0)), ns = rt.mem.read32(c.arg(0) + 4);
        std::this_thread::sleep_for(std::chrono::seconds(s) + std::chrono::nanoseconds(ns));
        c.ret(0);
    });
    rt.def("usleep", [](Cpu &c) { std::this_thread::sleep_for(std::chrono::microseconds(c.arg(0))); c.ret(0); });
    rt.def("sleep", [](Cpu &c) { std::this_thread::sleep_for(std::chrono::seconds(c.arg(0))); c.ret(0); });
    rt.def("sched_yield", [](Cpu &c) { std::this_thread::yield(); c.ret(0); });
    auto tm_fn = [&rt](bool local, bool r) {
        return [&rt, local, r](Cpu &c) {
            static thread_local gaddr buf = 0;
            time_t t = static_cast<time_t>(static_cast<int32_t>(rt.mem.read32(c.arg(0))));
            struct tm out;
            local ? localtime_r(&t, &out) : gmtime_r(&t, &out);
            gaddr dst = r ? c.arg(1) : (buf ? buf : (buf = rt.malloc(44)));
            fill_tm(rt, dst, out);
            c.ret(dst);
        };
    };
    rt.def("localtime", tm_fn(true, false));
    rt.def("gmtime", tm_fn(false, false));
    rt.def("gmtime_r", tm_fn(false, true));
    rt.def("localtime64", [&rt](Cpu &c) {  // takes const time64_t*
        static thread_local gaddr buf = 0;
        time_t t = static_cast<time_t>(rt.mem.read32(c.arg(0)) | (static_cast<uint64_t>(rt.mem.read32(c.arg(0) + 4)) << 32));
        struct tm out;
        localtime_r(&t, &out);
        if (!buf) buf = rt.malloc(44);
        fill_tm(rt, buf, out);
        c.ret(buf);
    });
    rt.def("gmtime64", [&rt](Cpu &c) {
        static thread_local gaddr buf = 0;
        time_t t = static_cast<time_t>(rt.mem.read32(c.arg(0)) | (static_cast<uint64_t>(rt.mem.read32(c.arg(0) + 4)) << 32));
        struct tm out;
        gmtime_r(&t, &out);
        if (!buf) buf = rt.malloc(44);
        fill_tm(rt, buf, out);
        c.ret(buf);
    });
    rt.def("mktime", [&rt](Cpu &c) {
        struct tm t = read_tm(rt, c.arg(0));
        c.ret(static_cast<uint32_t>(::mktime(&t)));
    });
    rt.def("mktime64", [&rt](Cpu &c) {
        struct tm t = read_tm(rt, c.arg(0));
        c.ret64(static_cast<uint64_t>(::mktime(&t)));
    });
    rt.def("timegm64", [&rt](Cpu &c) {
        struct tm t = read_tm(rt, c.arg(0));
        c.ret64(static_cast<uint64_t>(::timegm(&t)));
    });
    rt.def("strftime", [&rt](Cpu &c) {
        struct tm t = read_tm(rt, c.arg(3));
        c.ret(static_cast<uint32_t>(::strftime(S(0), c.arg(1), S(2), &t)));
    });

    // ---- maths (soft-float ABI: floats/doubles travel in core registers) ----
#define F1(name, fn) rt.def(name, [](Cpu &c) { c.retf(fn(c.argf(0))); })
#define F2(name, fn) rt.def(name, [](Cpu &c) { c.retf(fn(c.argf(0), c.argf(1))); })
#define D1(name, fn) rt.def(name, [](Cpu &c) { c.retd(fn(c.argd(0))); })
#define D2(name, fn) rt.def(name, [](Cpu &c) { c.retd(fn(c.argd(0), c.argd(2))); })
    F1("sinf", sinf); F1("cosf", cosf); F1("tanf", tanf); F1("asinf", asinf); F1("acosf", acosf);
    F1("atanf", atanf); F1("sqrtf", sqrtf); F1("floorf", floorf); F1("ceilf", ceilf); F1("expf", expf);
    F1("logf", logf); F1("log10f", log10f);
    F2("atan2f", atan2f); F2("powf", powf); F2("fmodf", fmodf);
    D1("sin", sin); D1("cos", cos); D1("tan", tan); D1("sqrt", sqrt); D1("floor", floor); D1("ceil", ceil);
    D1("exp", exp); D1("log", log); D1("log10", log10);
    D2("pow", pow); D2("fmod", fmod); D2("atan2", atan2);
    rt.def("lrint", [](Cpu &c) { c.ret(static_cast<uint32_t>(static_cast<int32_t>(lrint(c.argd(0))))); });
    rt.def("modf", [&rt](Cpu &c) {
        double ip;
        double f = modf(c.argd(0), &ip);
        std::memcpy(rt.mem.host(c.arg(2)), &ip, 8);
        c.retd(f);
    });
    rt.def("modff", [&rt](Cpu &c) {
        float ip;
        float f = modff(c.argf(0), &ip);
        std::memcpy(rt.mem.host(c.arg(1)), &ip, 4);
        c.retf(f);
    });
    rt.def("__isfinitef", [](Cpu &c) { c.ret(std::isfinite(c.argf(0)) ? 1 : 0); });
}

}  // namespace po
