cmake_minimum_required(VERSION 3.20)
project(pocketouya_engine C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
include(FetchContent)
if(POLICY CMP0169)
    cmake_policy(SET CMP0169 OLD)  # allow FetchContent_Populate(name)
endif()

# Newer NDK libc++ no longer pulls in <cstdlib>/<cstring> transitively; dynarmic's bundled fmt
# relies on that, so force-include them for all C++ code.
add_compile_options("$<$<COMPILE_LANGUAGE:CXX>:SHELL:-include cstdlib>"
                    "$<$<COMPILE_LANGUAGE:CXX>:SHELL:-include cstring>")

# ---- dynarmic (32-bit ARM JIT) --------------------------------------------
# Local checkouts can be used with -DPO_DYNARMIC_DIR=... -DPO_BOOST_DIR=...
set(PO_DYNARMIC_DIR "" CACHE PATH "Local dynarmic source")
set(PO_BOOST_DIR "" CACHE PATH "Local Boost headers (azahar-emu/ext-boost)")
if(NOT PO_BOOST_DIR)
    FetchContent_Declare(ext_boost GIT_REPOSITORY https://github.com/azahar-emu/ext-boost.git
                         GIT_TAG 6a85c3100499e886e11c87a5c2109eedacea0a61 GIT_SHALLOW FALSE)
    FetchContent_Populate(ext_boost)
    set(PO_BOOST_DIR ${ext_boost_SOURCE_DIR})
endif()
set(Boost_INCLUDE_DIR ${PO_BOOST_DIR} CACHE PATH "" FORCE)

set(DYNARMIC_TESTS OFF CACHE BOOL "" FORCE)
set(DYNARMIC_FRONTENDS "A32" CACHE STRING "" FORCE)
set(DYNARMIC_USE_BUNDLED_EXTERNALS ON CACHE BOOL "" FORCE)
set(DYNARMIC_WARNINGS_AS_ERRORS OFF CACHE BOOL "" FORCE)
set(DYNARMIC_USE_PRECOMPILED_HEADERS OFF CACHE BOOL "" FORCE)
if(PO_DYNARMIC_DIR)
    add_subdirectory(${PO_DYNARMIC_DIR} dynarmic EXCLUDE_FROM_ALL)
else()
    FetchContent_Declare(dynarmic GIT_REPOSITORY https://github.com/azahar-emu/dynarmic.git
                         GIT_TAG a466015 GIT_SHALLOW FALSE
                         GIT_SUBMODULES externals/fmt externals/mcl externals/oaknut externals/robin-map
                                        externals/xbyak externals/zycore externals/zydis)
    FetchContent_GetProperties(dynarmic)
    if(NOT dynarmic_POPULATED)
        FetchContent_Populate(dynarmic)
        add_subdirectory(${dynarmic_SOURCE_DIR} ${dynarmic_BINARY_DIR} EXCLUDE_FROM_ALL)
    endif()
endif()

# ---- engine ----------------------------------------------------------------
find_package(ZLIB REQUIRED)
add_library(po_engine STATIC
    src/guest_memory.cpp
    src/elf32_loader.cpp
    src/thunks.cpp
    src/runtime.cpp
    src/cpu.cpp
    src/shims_libc.cpp
    src/shims_stdio.cpp
    src/shims_pthread.cpp
    src/shims_misc.cpp
    src/fake_jni.cpp
    src/apk.cpp
    src/yoyo.cpp)
target_include_directories(po_engine PUBLIC src)
target_link_libraries(po_engine PUBLIC dynarmic ZLIB::ZLIB)
if(NOT ANDROID)
    find_package(Threads REQUIRED)
    target_link_libraries(po_engine PUBLIC Threads::Threads)
endif()

if(ANDROID)
    add_library(pocketouya_engine SHARED android/jni_bridge.cpp src/shims_gl.cpp src/shims_gl_generated.cpp)
    target_link_libraries(pocketouya_engine po_engine GLESv2 log)
endif()

if(NOT ANDROID)
    add_executable(so_inspect tools/so_inspect.cpp)
    target_link_libraries(so_inspect po_engine)
    add_executable(yoyo_boot tools/yoyo_boot.cpp)
    target_link_libraries(yoyo_boot po_engine)
endif()
