// yoyo_boot – milestone 2 test: boot a GameMaker Ouya game's 32-bit engine on any
// 64-bit machine and run until it first needs graphics.
//
// Usage: yoyo_boot game.apk [save_dir] [--trace]
#include "../src/yoyo.h"
#include "headless_gl.h"

#include <cstdio>
#include <cstring>

int main(int argc, char **argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s game.apk [save_dir] [--trace]\n", argv[0]);
        return 2;
    }
    std::string save = argc > 2 && argv[2][0] != '-' ? argv[2] : "/tmp/pocketmicro-save";
    po::YoyoGame game(argv[1], save);
    int frames = 3;
    for (int i = 2; i < argc; i++) {
        if (!std::strcmp(argv[i], "--trace")) game.rt.trace_calls = true;
        if (!std::strcmp(argv[i], "--pngdebug")) {  // diagnostic: report libpng's first error and stop
            game.platform_setup = [&game](po::Runtime &rt) {
                po::register_headless_gl(rt);
            };
            game.debug_png = true;
        }
        if (!std::strcmp(argv[i], "--trace-textures")) {  // trace host calls while textures load
            game.rt.log_sink = [&game](const std::string &l) {
                std::fprintf(stderr, "%s\n", l.c_str());
                if (l.find("Process Chunk: TXTR") != std::string::npos) game.rt.trace_calls = true;
                if (l.find("PNG error") != std::string::npos) game.rt.trace_calls = false;
            };
        }
        if (!std::strcmp(argv[i], "--headless")) {  // fake GPU: run past graphics
            game.platform_setup = [](po::Runtime &rt) { po::register_headless_gl(rt); };
            frames = 120;
        }
    }

    bool ok = game.boot();
    for (int f = 0; ok && f < frames; f++) {
        game.rt.log("frame %d", f);
        ok = game.frame(1280, 720);
    }
    auto r = game.rt.stop_reason.load();
    if (r == po::StopReason::GraphicsReached) {
        std::printf("\nMILESTONE 2 REACHED: the engine booted and asked for graphics.\n%s\n", game.rt.stop_message.c_str());
        return 0;
    }
    std::printf("\nStopped: %s\n", r == po::StopReason::None ? "ran without needing graphics?" : game.rt.stop_message.c_str());
    return 1;
}
