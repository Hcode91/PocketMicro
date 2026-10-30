// stdio and file system: FILE*, printf/scanf families, POSIX file calls, directories.
#include "abi.h"

#include <cerrno>
#include <cstdio>
#include <dirent.h>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

namespace po {

// ---------------------------------------------------------------- printf

std::string guest_format(Runtime &rt, const char *f, ArgSource &a) {
    std::string out;
    char spec[64], buf[512];
    while (*f) {
        if (*f != '%') { out += *f++; continue; }
        if (f[1] == '%') { out += '%'; f += 2; continue; }
        const char *start = f++;
        while (*f && std::strchr("-+ #0'", *f)) f++;
        int width = -1, prec = -1;
        bool star_w = false, star_p = false;
        if (*f == '*') { star_w = true; f++; } else while (isdigit(*f)) f++;
        if (*f == '.') {
            f++;
            if (*f == '*') { star_p = true; f++; } else while (isdigit(*f)) f++;
        }
        int lng = 0;  // 0 int, 1 long, 2 long long
        while (*f && std::strchr("hlLqjzt", *f)) {
            if (*f == 'l') lng++;
            if (*f == 'q' || *f == 'L' || *f == 'j') lng = 2;
            f++;
        }
        char conv = *f ? *f++ : 0;
        if (!conv) break;
        // Build a host spec without length modifiers; we pass correctly-typed values.
        std::string s(start, f - start);
        std::string clean;
        for (char ch : s) if (!std::strchr("hlLqjzt", ch)) clean += ch;
        std::snprintf(spec, sizeof spec, "%s", clean.c_str());
        if (star_w) width = static_cast<int32_t>(a.next32());
        if (star_p) prec = static_cast<int32_t>(a.next32());
        auto emit = [&](auto v) {
            int n;
            if (star_w && star_p) n = std::snprintf(buf, sizeof buf, spec, width, prec, v);
            else if (star_w) n = std::snprintf(buf, sizeof buf, spec, width, v);
            else if (star_p) n = std::snprintf(buf, sizeof buf, spec, prec, v);
            else n = std::snprintf(buf, sizeof buf, spec, v);
            if (n > 0) out.append(buf, std::min<size_t>(n, sizeof buf - 1));
        };
        switch (conv) {
            case 'd': case 'i':
                if (lng == 2) emit(static_cast<long long>(a.next64()));
                else emit(static_cast<int>(a.next32()));
                break;
            case 'u': case 'x': case 'X': case 'o':
                if (lng == 2) emit(static_cast<unsigned long long>(a.next64()));
                else emit(static_cast<unsigned>(a.next32()));
                break;
            case 'c': emit(static_cast<int>(a.next32())); break;
            case 'f': case 'F': case 'e': case 'E': case 'g': case 'G': case 'a': case 'A':
                emit(a.nextd());
                break;
            case 's': {
                gaddr p = a.next32();
                std::string str = p ? rt.read_string(p) : "(null)";
                // Long strings would overflow buf; handle the common plain "%s" directly.
                if (clean == "%s") out += str;
                else emit(str.c_str());
                break;
            }
            case 'p': {
                std::snprintf(buf, sizeof buf, "0x%x", a.next32());
                out += buf;
                break;
            }
            case 'n': {
                gaddr p = a.next32();
                if (p) rt.mem.write32(p, static_cast<uint32_t>(out.size()));
                break;
            }
            default: out += s; break;
        }
    }
    return out;
}

// ---------------------------------------------------------------- scanf

int guest_scan(Runtime &rt, const char *in, const char *f, ArgSource &a, size_t *consumed) {
    const char *s = in;
    int assigned = 0;
    while (*f) {
        if (isspace(static_cast<unsigned char>(*f))) {
            while (isspace(static_cast<unsigned char>(*s))) s++;
            f++;
            continue;
        }
        if (*f != '%' || f[1] == '%') {
            if (*f == '%') f++;
            if (*s != *f) break;
            s++, f++;
            continue;
        }
        const char *start = f++;
        bool suppress = false;
        if (*f == '*') { suppress = true; f++; }
        while (isdigit(*f)) f++;
        int lng = 0, half = 0;
        while (*f && std::strchr("hlLqjzt", *f)) {
            if (*f == 'l') lng++;
            if (*f == 'h') half++;
            if (*f == 'L' || *f == 'q') lng = 2;
            f++;
        }
        std::string set;
        char conv = *f;
        if (conv == '[') {
            const char *e = f + 1;
            if (*e == '^') e++;
            if (*e == ']') e++;
            while (*e && *e != ']') e++;
            set.assign(f, e + 1);
            f = *e ? e + 1 : e;
        } else if (conv) {
            f++;
        } else {
            break;
        }
        if (!*s && conv != 'n') break;
        // Rebuild a single-conversion host format with our own length modifiers.
        std::string spec(start, (conv == '[' ? f - set.size() : f - 1) - start);
        std::string base;
        for (char ch : spec) if (!std::strchr("hlLqjzt", ch)) base += ch;
        int n = 0;
        if (conv == 'n') {
            if (!suppress) rt.mem.write32(a.next32(), static_cast<uint32_t>(s - in));
            continue;
        }
        if (std::strchr("diuxXo", conv)) {
            long long v = 0;
            std::string fmt = base + "ll" + conv + "%n";
            if (std::sscanf(s, fmt.c_str(), &v, &n) < 1) break;
            if (!suppress) {
                gaddr p = a.next32();
                if (lng == 2) { rt.mem.write32(p, static_cast<uint32_t>(v)); rt.mem.write32(p + 4, static_cast<uint32_t>(v >> 32)); }
                else if (half == 1) *rt.mem.ptr<int16_t>(p) = static_cast<int16_t>(v);
                else if (half >= 2) *rt.mem.ptr<int8_t>(p) = static_cast<int8_t>(v);
                else rt.mem.write32(p, static_cast<uint32_t>(v));
                assigned++;
            }
        } else if (std::strchr("feEgGaA", conv)) {
            double v = 0;
            std::string fmt = base + "l" + conv + "%n";
            if (std::sscanf(s, fmt.c_str(), &v, &n) < 1) break;
            if (!suppress) {
                gaddr p = a.next32();
                if (lng >= 1) std::memcpy(rt.mem.host(p), &v, 8);
                else { float fv = static_cast<float>(v); std::memcpy(rt.mem.host(p), &fv, 4); }
                assigned++;
            }
        } else if (conv == 's' || conv == 'c' || conv == '[') {
            std::string fmt = base + (conv == '[' ? set : std::string(1, conv)) + "%n";
            std::vector<char> tmp(std::strlen(s) + 2);
            if (std::sscanf(s, fmt.c_str(), tmp.data(), &n) < 1) break;
            if (!suppress) {
                gaddr p = a.next32();
                size_t len = conv == 'c' ? static_cast<size_t>(n) : std::strlen(tmp.data()) + 1;
                std::memcpy(rt.mem.host(p), tmp.data(), len);
                assigned++;
            }
        } else {
            break;
        }
        s += n;
    }
    if (consumed) *consumed = static_cast<size_t>(s - in);
    return assigned;
}

// ---------------------------------------------------------------- FILE*

namespace {
constexpr uint32_t kFileSize = 84;  // sizeof(FILE) in 32-bit bionic
std::mutex g_files_mutex;
std::map<gaddr, FILE *> g_files;
gaddr g_sF = 0;

FILE *host_file(Runtime &rt, gaddr g) {
    std::lock_guard<std::mutex> lk(g_files_mutex);
    auto it = g_files.find(g);
    return it == g_files.end() ? nullptr : it->second;
}
gaddr wrap_file(Runtime &rt, FILE *f) {
    if (!f) return 0;
    gaddr g = rt.malloc(kFileSize);
    std::memset(rt.mem.host(g), 0, kFileSize);
    std::lock_guard<std::mutex> lk(g_files_mutex);
    g_files[g] = f;
    return g;
}
}  // namespace

// Guest paths are used as-is; the Android app passes real paths in (APK, save folder).
static std::string path(Runtime &rt, gaddr p) { return rt.read_string(p); }

static void put_stat(Runtime &rt, gaddr out, const struct stat &st) {
    uint8_t *b = rt.mem.host(out);
    std::memset(b, 0, 104);
    auto w32 = [&](int off, uint32_t v) { std::memcpy(b + off, &v, 4); };
    auto w64 = [&](int off, uint64_t v) { std::memcpy(b + off, &v, 8); };
    w64(0, st.st_dev);
    w32(12, static_cast<uint32_t>(st.st_ino));
    w32(16, st.st_mode);
    w32(20, static_cast<uint32_t>(st.st_nlink));
    w32(24, st.st_uid);
    w32(28, st.st_gid);
    w64(32, st.st_rdev);
    w64(48, static_cast<uint64_t>(st.st_size));
    w32(56, static_cast<uint32_t>(st.st_blksize));
    w64(64, static_cast<uint64_t>(st.st_blocks));
    w32(72, static_cast<uint32_t>(st.st_atime));
    w32(80, static_cast<uint32_t>(st.st_mtime));
    w32(88, static_cast<uint32_t>(st.st_ctime));
    w64(96, st.st_ino);
}

void register_stdio(Runtime &rt) {
    g_sF = rt.data("__sF", kFileSize * 3);
    {
        std::lock_guard<std::mutex> lk(g_files_mutex);
        g_files[g_sF] = stdin;
        g_files[g_sF + kFileSize] = stdout;
        g_files[g_sF + 2 * kFileSize] = stderr;
    }
    auto F = [&rt](Cpu &c, int i) { return host_file(rt, c.arg(i)); };
    auto fail = [](Cpu &c, int e) { c.set_errno(e); c.ret(static_cast<uint32_t>(-1)); };

    rt.def("fopen", [&rt](Cpu &c) {
        FILE *f = std::fopen(path(rt, c.arg(0)).c_str(), rt.read_string(c.arg(1)).c_str());
        if (!f) c.set_errno(errno);
        c.ret(wrap_file(rt, f));
    });
    rt.def("fdopen", [&rt](Cpu &c) { c.ret(wrap_file(rt, ::fdopen(static_cast<int>(c.arg(0)), rt.read_string(c.arg(1)).c_str()))); });
    rt.def("freopen", [&rt, F](Cpu &c) {
        FILE *f = std::freopen(path(rt, c.arg(0)).c_str(), rt.read_string(c.arg(1)).c_str(), F(c, 2));
        std::lock_guard<std::mutex> lk(g_files_mutex);
        if (f) g_files[c.arg(2)] = f;
        c.ret(f ? c.arg(2) : 0);
    });
    rt.def("tmpfile", [&rt](Cpu &c) { c.ret(wrap_file(rt, std::tmpfile())); });
    rt.def("fclose", [&rt, F](Cpu &c) {
        FILE *f = F(c, 0);
        int r = f ? std::fclose(f) : -1;
        {
            std::lock_guard<std::mutex> lk(g_files_mutex);
            g_files.erase(c.arg(0));
        }
        if (c.arg(0) < g_sF || c.arg(0) >= g_sF + 3 * kFileSize) rt.free(c.arg(0));
        c.ret(static_cast<uint32_t>(r));
    });
    rt.def("fread", [&rt, F](Cpu &c) {
        FILE *f = F(c, 3);
        c.ret(f ? static_cast<uint32_t>(std::fread(rt.mem.host(c.arg(0)), c.arg(1), c.arg(2), f)) : 0);
    });
    rt.def("fwrite", [&rt, F](Cpu &c) {
        FILE *f = F(c, 3);
        c.ret(f ? static_cast<uint32_t>(std::fwrite(rt.mem.host(c.arg(0)), c.arg(1), c.arg(2), f)) : 0);
    });
    rt.def("fseek", [F](Cpu &c) { FILE *f = F(c, 0); c.ret(f ? static_cast<uint32_t>(std::fseek(f, static_cast<int32_t>(c.arg(1)), static_cast<int>(c.arg(2)))) : -1u); });
    rt.def("fseeko", [F](Cpu &c) { FILE *f = F(c, 0); c.ret(f ? static_cast<uint32_t>(fseeko(f, static_cast<int32_t>(c.arg(1)), static_cast<int>(c.arg(2)))) : -1u); });
    rt.def("ftell", [F](Cpu &c) { FILE *f = F(c, 0); c.ret(f ? static_cast<uint32_t>(std::ftell(f)) : -1u); });
    rt.def("ftello", [F](Cpu &c) { FILE *f = F(c, 0); c.ret(f ? static_cast<uint32_t>(ftello(f)) : -1u); });
    rt.def("fgetpos", [&rt, F](Cpu &c) { FILE *f = F(c, 0); rt.mem.write32(c.arg(1), f ? static_cast<uint32_t>(std::ftell(f)) : 0); c.ret(0); });
    rt.def("fflush", [F](Cpu &c) { FILE *f = F(c, 0); c.ret(static_cast<uint32_t>(std::fflush(f))); });
    rt.def("fgetc", [F](Cpu &c) { FILE *f = F(c, 0); c.ret(f ? static_cast<uint32_t>(std::fgetc(f)) : -1u); });
    rt.def("ungetc", [F](Cpu &c) { FILE *f = F(c, 1); c.ret(f ? static_cast<uint32_t>(std::ungetc(static_cast<int>(c.arg(0)), f)) : -1u); });
    rt.def("fgets", [&rt, F](Cpu &c) {
        FILE *f = F(c, 2);
        c.ret(f && std::fgets(rt.mem.ptr<char>(c.arg(0)), static_cast<int>(c.arg(1)), f) ? c.arg(0) : 0);
    });
    rt.def("fputc", [F](Cpu &c) { FILE *f = F(c, 1); c.ret(f ? static_cast<uint32_t>(std::fputc(static_cast<int>(c.arg(0)), f)) : -1u); });
    rt.def("__swbuf", [&rt, F](Cpu &c) {  // slow path of the putc() macro
        FILE *f = F(c, 1);
        rt.mem.write32(c.arg(1) + 8, 0);  // reset _w so the macro keeps calling us
        c.ret(f ? static_cast<uint32_t>(std::fputc(static_cast<int>(c.arg(0)), f)) : -1u);
    });
    rt.def("fputs", [&rt, F](Cpu &c) { FILE *f = F(c, 1); c.ret(f ? static_cast<uint32_t>(std::fputs(rt.mem.ptr<char>(c.arg(0)), f)) : -1u); });
    rt.def("puts", [&rt](Cpu &c) { rt.log("[game] %s", rt.read_string(c.arg(0)).c_str()); c.ret(1); });
    rt.def("perror", [&rt](Cpu &c) { rt.log("[game] perror: %s", rt.read_string(c.arg(0)).c_str()); });
    rt.def("clearerr", [F](Cpu &c) { if (FILE *f = F(c, 0)) std::clearerr(f); });
    rt.def("setvbuf", [](Cpu &c) { c.ret(0); });

    // printf family
    rt.def("printf", [&rt](Cpu &c) {
        RegArgs a(c, 1);
        std::string s = guest_format(rt, rt.mem.ptr<char>(c.arg(0)), a);
        rt.log("[game] %s", s.c_str());
        c.ret(static_cast<uint32_t>(s.size()));
    });
    rt.def("vprintf", [&rt](Cpu &c) {
        VaArgs a(rt.mem, c.arg(1));
        std::string s = guest_format(rt, rt.mem.ptr<char>(c.arg(0)), a);
        rt.log("[game] %s", s.c_str());
        c.ret(static_cast<uint32_t>(s.size()));
    });
    auto to_file = [&rt, F](Cpu &c, const std::string &s) {
        FILE *f = F(c, 0);
        if (f == stdout || f == stderr) rt.log("[game] %s", s.c_str());
        else if (f) std::fwrite(s.data(), 1, s.size(), f);
        c.ret(static_cast<uint32_t>(s.size()));
    };
    rt.def("fprintf", [&rt, to_file](Cpu &c) { RegArgs a(c, 2); to_file(c, guest_format(rt, rt.mem.ptr<char>(c.arg(1)), a)); });
    rt.def("vfprintf", [&rt, to_file](Cpu &c) { VaArgs a(rt.mem, c.arg(2)); to_file(c, guest_format(rt, rt.mem.ptr<char>(c.arg(1)), a)); });
    auto to_buf = [&rt](Cpu &c, gaddr dst, uint32_t cap, const std::string &s) {
        if (cap) {
            size_t n = std::min<size_t>(s.size(), cap - 1);
            std::memcpy(rt.mem.host(dst), s.data(), n);
            rt.mem.host(dst)[n] = 0;
        }
        c.ret(static_cast<uint32_t>(s.size()));
    };
    rt.def("sprintf", [&rt, to_buf](Cpu &c) { RegArgs a(c, 2); to_buf(c, c.arg(0), 0x7fffffff, guest_format(rt, rt.mem.ptr<char>(c.arg(1)), a)); });
    rt.def("vsprintf", [&rt, to_buf](Cpu &c) { VaArgs a(rt.mem, c.arg(2)); to_buf(c, c.arg(0), 0x7fffffff, guest_format(rt, rt.mem.ptr<char>(c.arg(1)), a)); });
    rt.def("snprintf", [&rt, to_buf](Cpu &c) { RegArgs a(c, 3); to_buf(c, c.arg(0), c.arg(1), guest_format(rt, rt.mem.ptr<char>(c.arg(2)), a)); });
    rt.def("vsnprintf", [&rt, to_buf](Cpu &c) { VaArgs a(rt.mem, c.arg(3)); to_buf(c, c.arg(0), c.arg(1), guest_format(rt, rt.mem.ptr<char>(c.arg(2)), a)); });
    rt.def("vasprintf", [&rt](Cpu &c) {
        VaArgs a(rt.mem, c.arg(2));
        std::string s = guest_format(rt, rt.mem.ptr<char>(c.arg(1)), a);
        rt.mem.write32(c.arg(0), rt.strdup(s));
        c.ret(static_cast<uint32_t>(s.size()));
    });
    rt.def("sscanf", [&rt](Cpu &c) {
        RegArgs a(c, 2);
        c.ret(static_cast<uint32_t>(guest_scan(rt, rt.mem.ptr<char>(c.arg(0)), rt.mem.ptr<char>(c.arg(1)), a)));
    });
    rt.def("fscanf", [&rt, F](Cpu &c) {
        // Read the rest of the current line and scan it (sufficient for config-style files).
        FILE *f = F(c, 0);
        char line[4096];
        if (!f || !std::fgets(line, sizeof line, f)) { c.ret(static_cast<uint32_t>(-1)); return; }
        RegArgs a(c, 2);
        c.ret(static_cast<uint32_t>(guest_scan(rt, line, rt.mem.ptr<char>(c.arg(1)), a)));
    });

    // POSIX file descriptors (guest fds are host fds)
    rt.def("open", [&rt, fail](Cpu &c) {
        int fd = ::open(path(rt, c.arg(0)).c_str(), static_cast<int>(c.arg(1)), c.arg(2));
        if (fd < 0) return fail(c, errno);
        c.ret(static_cast<uint32_t>(fd));
    });
    rt.def("close", [](Cpu &c) { c.ret(static_cast<uint32_t>(::close(static_cast<int>(c.arg(0))))); });
    rt.def("read", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(::read(static_cast<int>(c.arg(0)), rt.mem.host(c.arg(1)), c.arg(2)))); });
    rt.def("write", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(::write(static_cast<int>(c.arg(0)), rt.mem.host(c.arg(1)), c.arg(2)))); });
    rt.def("lseek", [](Cpu &c) { c.ret(static_cast<uint32_t>(::lseek(static_cast<int>(c.arg(0)), static_cast<int32_t>(c.arg(1)), static_cast<int>(c.arg(2))))); });
    rt.def("fcntl", [](Cpu &c) { c.ret(0); });
    rt.def("flock", [](Cpu &c) { c.ret(0); });
    rt.def("ioctl", [fail](Cpu &c) { fail(c, ENOTTY); });
    rt.def("stat", [&rt, fail](Cpu &c) {
        struct stat st;
        if (::stat(path(rt, c.arg(0)).c_str(), &st) != 0) return fail(c, errno);
        put_stat(rt, c.arg(1), st);
        c.ret(0);
    });
    rt.def("fstat", [&rt, fail](Cpu &c) {
        struct stat st;
        if (::fstat(static_cast<int>(c.arg(0)), &st) != 0) return fail(c, errno);
        put_stat(rt, c.arg(1), st);
        c.ret(0);
    });
    rt.def("access", [&rt, fail](Cpu &c) {
        if (::access(path(rt, c.arg(0)).c_str(), static_cast<int>(c.arg(1))) != 0) return fail(c, errno);
        c.ret(0);
    });
    rt.def("mkdir", [&rt, fail](Cpu &c) {
        if (::mkdir(path(rt, c.arg(0)).c_str(), c.arg(1)) != 0) return fail(c, errno);
        c.ret(0);
    });
    rt.def("remove", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(std::remove(path(rt, c.arg(0)).c_str()))); });
    rt.def("rename", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(std::rename(path(rt, c.arg(0)).c_str(), path(rt, c.arg(1)).c_str()))); });
    rt.def("chmod", [](Cpu &c) { c.ret(0); });
    rt.def("umask", [](Cpu &c) { c.ret(022); });
    rt.def("mknod", [fail](Cpu &c) { fail(c, EPERM); });
    rt.def("mkstemp", [&rt](Cpu &c) { c.ret(static_cast<uint32_t>(::mkstemp(rt.mem.ptr<char>(c.arg(0))))); });

    // Directories: DIR* is a guest block holding a host DIR* and a dirent buffer.
    static std::mutex dirs_mutex;
    static std::map<gaddr, DIR *> dirs;
    rt.def("opendir", [&rt](Cpu &c) {
        DIR *d = ::opendir(path(rt, c.arg(0)).c_str());
        if (!d) { c.set_errno(errno); c.ret(0); return; }
        gaddr g = rt.malloc(280);
        std::lock_guard<std::mutex> lk(dirs_mutex);
        dirs[g] = d;
        c.ret(g);
    });
    rt.def("readdir", [&rt](Cpu &c) {
        DIR *d;
        {
            std::lock_guard<std::mutex> lk(dirs_mutex);
            auto it = dirs.find(c.arg(0));
            d = it == dirs.end() ? nullptr : it->second;
        }
        struct dirent *e = d ? ::readdir(d) : nullptr;
        if (!e) { c.ret(0); return; }
        uint8_t *b = rt.mem.host(c.arg(0));
        std::memset(b, 0, 280);
        uint64_t ino = e->d_ino;
        std::memcpy(b, &ino, 8);
        uint16_t reclen = 280;
        std::memcpy(b + 16, &reclen, 2);
        b[18] = e->d_type;
        std::snprintf(reinterpret_cast<char *>(b + 19), 256, "%s", e->d_name);
        c.ret(c.arg(0));
    });
    rt.def("closedir", [&rt](Cpu &c) {
        std::lock_guard<std::mutex> lk(dirs_mutex);
        auto it = dirs.find(c.arg(0));
        if (it != dirs.end()) { ::closedir(it->second); dirs.erase(it); rt.free(c.arg(0)); }
        c.ret(0);
    });
    rt.def("scandir", [fail](Cpu &c) { fail(c, ENOSYS); });
}

}  // namespace po
