// so_inspect – load 32-bit ARM Android libraries the way the engine will and
// report what they need. Milestone 1 tool: no CPU emulation yet.
//
// Usage: so_inspect libopenal.so libyoyo.so   (dependencies first)
#include "../src/elf32_loader.h"
#include "../src/thunks.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <map>
#include <set>
#include <string>
#include <vector>

using namespace po;

static std::string category(const std::string &n) {
    auto starts = [&](const char *p) { return n.rfind(p, 0) == 0; };
    if (starts("gl") || starts("egl")) return "OpenGL ES / EGL";
    if ((starts("al") && n.size() > 2 && isupper((unsigned char) n[2])) || starts("alc")) return "OpenAL";
    if (starts("pthread_") || starts("sem_")) return "threads";
    if (starts("__android_log")) return "Android log";
    if (starts("dl")) return "dynamic loading (libdl)";
    if (starts("_Z") || starts("__cxa") || starts("__gxx") || starts("__aeabi") || starts("__gnu")) return "C++ / ARM EABI runtime";
    static const std::set<std::string> zlib = {"inflate", "inflateInit_", "inflateInit2_", "inflateEnd", "deflate",
        "deflateInit_", "deflateInit2_", "deflateEnd", "crc32", "adler32", "uncompress", "compress", "compress2",
        "inflateReset", "deflateReset", "zlibVersion", "compressBound"};
    if (zlib.count(n)) return "zlib";
    static const std::set<std::string> m = {"sin", "cos", "tan", "asin", "acos", "atan", "atan2", "sqrt", "pow",
        "exp", "log", "log10", "floor", "ceil", "fmod", "sinf", "cosf", "tanf", "sqrtf", "powf", "floorf", "ceilf",
        "fmodf", "atan2f", "acosf", "asinf", "atanf", "expf", "logf", "log10f", "modf", "frexp", "ldexp", "lrint",
        "lrintf", "round", "roundf", "trunc", "truncf", "fabs", "fabsf", "hypot", "sinh", "cosh", "tanh", "log2",
        "exp2", "cbrt", "rint", "rintf", "nearbyint", "copysign", "fmin", "fmax", "fminf", "fmaxf"};
    if (m.count(n)) return "libm";
    return "libc";
}

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s lib1.so [lib2.so ...]  (dependencies first)\n", argv[0]);
        return 1;
    }
    GuestMemory mem;
    ThunkTable thunks(mem);
    Elf32Loader loader(mem);
    std::vector<Module> mods;

    for (int i = 1; i < argc; i++) {
        std::string path = argv[i];
        std::string name = path.substr(path.find_last_of('/') + 1);
        auto resolve = [&](const std::string &sym, bool fn) -> gaddr {
            for (const Module &m : mods)
                if (gaddr a = m.find(sym)) return a;
            return fn ? thunks.function(sym) : thunks.data(sym, 0x400);
        };
        mods.push_back(loader.load(name, read_file(path), resolve));
    }

    for (const Module &m : mods) {
        std::printf("## %s\n\n", m.name.c_str());
        std::printf("- Loaded at guest 0x%08x, %u KB\n", m.base, m.size / 1024);
        std::printf("- Needs:");
        for (auto &n : m.needed) std::printf(" %s", n.c_str());
        std::printf("\n- Exports: %zu symbols, constructors to run: %zu\n", m.exports.size(), m.init_array.size());

        std::vector<std::string> jni;
        for (auto &e : m.exports)
            if (e.first.rfind("Java_", 0) == 0 || e.first == "JNI_OnLoad") jni.push_back(e.first);
        if (!jni.empty()) {
            std::printf("\n### Java entry points (%zu)\n\n", jni.size());
            for (auto &j : jni) std::printf("- `%s`\n", j.c_str());
        }

        std::map<std::string, std::vector<std::string>> bycat;
        size_t from_other = 0;
        for (auto &kv : m.imports) {
            const Import &imp = kv.second;
            bool own = false;
            for (const Module &o : mods)
                if (&o != &m && o.find(imp.name)) own = true;
            if (own) { from_other++; continue; }
            bycat[imp.is_function ? category(imp.name) : std::string("data objects")].push_back(imp.name);
        }
        std::printf("\n### Imports the engine must provide (%zu; %zu more come from other loaded libraries)\n\n",
                    m.imports.size() - from_other, from_other);
        for (auto &c : bycat) {
            std::printf("**%s** (%zu): ", c.first.c_str(), c.second.size());
            for (size_t i = 0; i < c.second.size(); i++) std::printf("%s`%s`", i ? ", " : "", c.second[i].c_str());
            std::printf("\n\n");
        }
    }
    std::printf("Total host thunks created: %zu\n", thunks.all().size());
    return 0;
}
