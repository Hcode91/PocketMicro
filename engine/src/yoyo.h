// PocketMicro engine – runs a GameMaker: Studio 1.x Android game (libyoyo.so).
#pragma once
#include "apk.h"
#include "cpu.h"
#include "fake_jni.h"

#include <memory>
#include <string>

namespace po {

class YoyoGame {
public:
    YoyoGame(const std::string &apk_path, const std::string &save_dir);
    ~YoyoGame();

    Runtime rt;
    // Load the engine, run its constructors and the GameMaker Startup call.
    bool boot();
    // Run one frame (RunnerJNILib.Process).
    bool frame(int width, int height);
    // A key press/release (action 0 = down, 1 = up; Android key code), queued until the next frame.
    void key_event(int action, int keycode);

    std::string apk_path, save_dir, package;
    // Extra shims for the platform (Android: OpenGL ES). Without it, the first GL call stops the engine.
    std::function<void(Runtime &)> platform_setup;
    bool debug_png = false;  // diagnostic: stop at libpng's first error and report it

private:
    std::unique_ptr<Apk> apk_;
    std::unique_ptr<FakeJni> jni_;
    std::unique_ptr<Cpu> main_;
    gaddr fn(const char *name);
    void install_java_methods();
    std::mutex input_mutex_;
    std::vector<std::pair<int, int>> pending_keys_;
    void install_hooks();
};

}  // namespace po
