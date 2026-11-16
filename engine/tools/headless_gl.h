// Fake OpenGL for desktop/CI runs: lets the engine run past graphics without a GPU.
#pragma once
#include "../src/cpu.h"

#include <atomic>

namespace po {

inline void register_headless_gl(Runtime &rt) {
    static std::atomic<uint32_t> next_id{1};
    rt.def("glGetError", [](Cpu &c) { c.ret(0); });
    rt.def("glGetString", [&rt](Cpu &c) {
        static gaddr s = 0;
        if (!s) s = rt.strdup("OpenGL ES 2.0 (PocketMicro headless)");
        c.ret(s);
    });
    auto gen = [&rt](Cpu &c) {
        for (uint32_t i = 0; i < c.arg(0); i++) rt.mem.write32(c.arg(1) + 4 * i, next_id++);
    };
    for (const char *n : {"glGenTextures", "glGenBuffers", "glGenFramebuffers", "glGenRenderbuffers",
                          "glGenFramebuffersOES", "glGenRenderbuffersOES"})
        rt.def(n, gen);
    rt.def("glCreateShader", [](Cpu &c) { c.ret(next_id++); });
    rt.def("glCreateProgram", [](Cpu &c) { c.ret(next_id++); });
    auto status_ok = [&rt](Cpu &c) { if (c.arg(2)) rt.mem.write32(c.arg(2), 1); };
    rt.def("glGetShaderiv", status_ok);
    rt.def("glGetProgramiv", status_ok);
    rt.def("glGetIntegerv", [&rt](Cpu &c) { if (c.arg(1)) rt.mem.write32(c.arg(1), 4096); });
    rt.def("glCheckFramebufferStatus", [](Cpu &c) { c.ret(0x8CD5); });
    rt.def("glCheckFramebufferStatusOES", [](Cpu &c) { c.ret(0x8CD5); });
    rt.def("glGetUniformLocation", [](Cpu &c) { c.ret(1); });
    rt.def("glGetAttribLocation", [](Cpu &c) { c.ret(1); });
    // Everything else gl* is a no-op returning 0.
    rt.is_graphics = nullptr;
    rt.stop_on_missing = false;
}

}  // namespace po
