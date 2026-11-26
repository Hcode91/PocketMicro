/*
 * PocketMicro OUYA GPU-limit shim (libpogles.so), used by ouya.gles-vertex-attribs.
 *
 * Scaleform's Unity plugin (libgfxunity3d.so) saves one "attribute enabled" byte per vertex
 * attribute into a 16-byte array in its manager (SFManagerImpl::SaveStateBlock). The OUYA's
 * Tegra 3 reported GL_MAX_VERTEX_ATTRIBS = 16; modern GPUs report 32, so the extra bytes
 * overwrite the manager's next fields (render context and render-thread pointers) and the
 * first frame crashes. The converter points that library's libGLESv2.so dependency at this
 * shim (same-length name). The shim depends on libGLESv2.so itself, so every other GL function
 * still resolves to Android's library; only glGetIntegerv is answered here, capping
 * GL_MAX_VERTEX_ATTRIBS at the OUYA's 16. Other queries pass through unchanged.
 */
#include <dlfcn.h>
#include <stddef.h>
#include <GLES2/gl2.h>

#define OUYA_MAX_VERTEX_ATTRIBS 16

typedef void (*get_integerv_fn)(GLenum, GLint *);

static get_integerv_fn real_get_integerv(void) {
    static get_integerv_fn real;
    if (!real) {
        void *gles = dlopen("libGLESv2.so", RTLD_NOW | RTLD_NOLOAD);
        if (!gles) gles = dlopen("libGLESv2.so", RTLD_NOW);
        if (gles) real = (get_integerv_fn) dlsym(gles, "glGetIntegerv");
    }
    return real;
}

__attribute__((visibility("default"))) void glGetIntegerv(GLenum pname, GLint *data) {
    get_integerv_fn real = real_get_integerv();
    if (!real) return;
    real(pname, data);
    if (pname == GL_MAX_VERTEX_ATTRIBS && data && *data > OUYA_MAX_VERTEX_ATTRIBS) *data = OUYA_MAX_VERTEX_ATTRIBS;
}
