// OpenGL ES 2 for guest code (Android build only): forwards to the phone's GPU driver.
#pragma once
#include "cpu.h"

namespace po {

struct GlState {
    uint32_t array_buffer = 0;          // currently bound GL_ARRAY_BUFFER
    uint32_t element_array_buffer = 0;  // currently bound GL_ELEMENT_ARRAY_BUFFER
};

void register_gl(Runtime &rt, GlState &st);
void register_gl_generated(Runtime &rt, GlState &st);  // shims_gl_generated.cpp
void gl1_warn(Runtime &rt, const char *name);

}  // namespace po
