#include "shims_gl.h"

#include <GLES2/gl2.h>
#include <set>

namespace po {

void gl1_warn(Runtime &rt, const char *name) {
    static std::set<std::string> seen;
    static std::mutex m;
    std::lock_guard<std::mutex> lk(m);
    if (seen.insert(name).second) rt.log("note: old GLES1 call %s ignored", name);
}

void register_gl(Runtime &rt, GlState &st) {
    register_gl_generated(rt, st);

    // Calls that need more than pointer translation:
    rt.def("glBindBuffer", [&st](Cpu &c) {
        GLenum target = c.arg(0);
        GLuint buf = c.arg(1);
        if (target == GL_ARRAY_BUFFER) st.array_buffer = buf;
        if (target == GL_ELEMENT_ARRAY_BUFFER) st.element_array_buffer = buf;
        glBindBuffer(target, buf);
    });
    rt.def("glGetString", [&rt](Cpu &c) {
        static std::map<GLenum, gaddr> cache;
        static std::mutex m;
        std::lock_guard<std::mutex> lk(m);
        GLenum name = c.arg(0);
        if (!cache.count(name)) {
            const GLubyte *s = glGetString(name);
            cache[name] = rt.strdup(s ? reinterpret_cast<const char *>(s) : "");
        }
        c.ret(cache[name]);
    });
    rt.def("glShaderSource", [&rt](Cpu &c) {
        GLuint shader = c.arg(0);
        GLsizei count = static_cast<GLsizei>(c.arg(1));
        gaddr strings = c.arg(2), lengths = c.arg(3);
        std::vector<const GLchar *> src(count);
        std::vector<GLint> len(count);
        for (GLsizei i = 0; i < count; i++) {
            src[i] = rt.mem.ptr<const GLchar>(rt.mem.read32(strings + 4 * i));
            len[i] = lengths ? static_cast<GLint>(rt.mem.read32(lengths + 4 * i)) : -1;
        }
        glShaderSource(shader, count, src.data(), len.data());
    });
}

}  // namespace po
