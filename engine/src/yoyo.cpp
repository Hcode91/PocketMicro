#include "yoyo.h"

#include <sys/stat.h>

namespace po {

static const char *kLibDirs[] = {"lib/armeabi-v7a/", "lib/armeabi/"};

YoyoGame::YoyoGame(const std::string &apk, const std::string &save) : apk_path(apk), save_dir(save) {}
YoyoGame::~YoyoGame() = default;

gaddr YoyoGame::fn(const char *name) {
    gaddr a = rt.symbol(name);
    if (!a) rt.log("note: engine has no %s", name);
    return a;
}

bool YoyoGame::boot() {
    try {
        apk_ = std::make_unique<Apk>(apk_path);
    } catch (const std::exception &e) {
        rt.log("can't read the game: %s", e.what());
        return false;
    }
    register_libc(rt);
    register_stdio(rt);
    register_pthread(rt);
    register_misc(rt);
    if (platform_setup) platform_setup(rt);
    else rt.is_graphics = [](const std::string &n) { return n.rfind("gl", 0) == 0 || n.rfind("egl", 0) == 0; };

    // Load the 32-bit libraries (OpenAL first – libyoyo depends on it).
    for (const char *lib : {"libopenal.so", "libyoyo.so"}) {
        bool loaded = false;
        for (const char *dir : kLibDirs) {
            std::string p = std::string(dir) + lib;
            if (!apk_->has(p)) continue;
            rt.load(lib, apk_->read(p));
            rt.log("loaded %s (%s)", lib, dir);
            loaded = true;
            break;
        }
        if (!loaded && std::string(lib) == "libyoyo.so") {
            rt.log("this APK has no 32-bit GameMaker engine (libyoyo.so)");
            return false;
        }
    }
    jni_ = std::make_unique<FakeJni>(rt);
    install_java_methods();
    install_hooks();

    gaddr stack = rt.mem.reserve_region(8 << 20);
    main_ = std::make_unique<Cpu>(rt, stack + (8 << 20) - 16);

    // C++ constructors of each library.
    for (const Module &m : rt.modules) {
        rt.log("running %zu constructors of %s", m.init_array.size(), m.name.c_str());
        for (gaddr f : m.init_array) {
            main_->call(f);
            if (rt.stop_reason != StopReason::None) return false;
        }
    }

    if (gaddr onload = rt.modules.back().find("JNI_OnLoad")) {
        uint32_t v = static_cast<uint32_t>(main_->call(onload, {jni_->vm(), 0}));
        rt.log("JNI_OnLoad returned 0x%x", v);
        if (rt.stop_reason != StopReason::None) return false;
    }

    mkdir(save_dir.c_str(), 0755);
    gaddr startup = fn("Java_com_yoyogames_runner_RunnerJNILib_Startup");
    if (!startup) return false;
    std::string save = save_dir + "/";
    rt.log("calling GameMaker Startup…");
    main_->call(startup, {jni_->env(), 0, jni_->new_string(apk_path), jni_->new_string(save),
                          jni_->new_string(package.empty() ? "com.pocketouya.game" : package), 0});
    if (rt.stop_reason != StopReason::None) return false;
    rt.log("Startup finished");
    return true;
}

void YoyoGame::key_event(int action, int keycode) {
    std::lock_guard<std::mutex> lk(input_mutex_);
    pending_keys_.emplace_back(action, keycode);
}

bool YoyoGame::frame(int w, int h) {
    gaddr process = fn("Java_com_yoyogames_runner_RunnerJNILib_Process");
    if (!process) return false;
    // Deliver input first: RunnerJNILib.KeyEvent(action, androidKeyCode) – the same call an Ouya makes.
    std::vector<std::pair<int, int>> keys;
    {
        std::lock_guard<std::mutex> lk(input_mutex_);
        keys.swap(pending_keys_);
    }
    if (!keys.empty()) {
        static gaddr key_fn = rt.symbol("Java_com_yoyogames_runner_RunnerJNILib_KeyEvent");
        for (auto &k : keys)
            if (key_fn) main_->call(key_fn, {jni_->env(), 0, static_cast<uint32_t>(k.first), static_cast<uint32_t>(k.second)});
    }
    auto fbits = [](float f) { uint32_t u; std::memcpy(&u, &f, 4); return u; };
    // Process(env, cls, w, h, accelX, accelY, accelZ, keypadOpen, orientation, refreshRate)
    main_->call(process, {jni_->env(), 0, static_cast<uint32_t>(w), static_cast<uint32_t>(h), fbits(0), fbits(0),
                          fbits(0), 0, 0, fbits(60.0f)});
    return rt.stop_reason == StopReason::None;
}

void YoyoGame::install_java_methods() {
    FakeJni &j = *jni_;
    auto ret_str = [this](const std::string &s) {
        return [this, s](Cpu &, const JavaCall &) -> uint64_t { return jni_->new_string(s); };
    };
    auto zero = [](Cpu &, const JavaCall &) -> uint64_t { return 0; };
    j.java("ShowMessage", [this](Cpu &, const JavaCall &c) -> uint64_t {
        rt.log("[game message] %s", rt.read_string(static_cast<gaddr>(c.args.at(0))).c_str());
        return 0;
    });
    j.java("GetUDID", ret_str("pocketouya-0000"));
    j.java("GetAppID", ret_str("pocketouya"));
    j.java("OsGetInfo", zero);
    j.java("GamepadConnected", zero);
    j.java("GetDefaultFrameBuffer", zero);
    j.java("getMinBufferSize", [](Cpu &, const JavaCall &) -> uint64_t { return 4096; });
}

void YoyoGame::install_hooks() {
    // Route GameMaker's own console output into our log (YoYo Loader does the same).
    auto console = [this](int fmt_arg) {
        return [this, fmt_arg](Cpu &c) {
            RegArgs a(c, fmt_arg + 1);
            std::string s = guest_format(rt, rt.mem.ptr<char>(c.arg(fmt_arg)), a);
            while (!s.empty() && s.back() == '\n') s.pop_back();
            rt.log("[GameMaker] %s", s.c_str());
            c.ret(0);
        };
    };
    if (gaddr a = rt.symbol("_ZN11TRelConsole6OutputEPKcz")) rt.hook(a, "hook:TRelConsole::Output", console(1));
    if (gaddr a = rt.symbol("_ZN17TErrStreamConsole6OutputEPKcz")) rt.hook(a, "hook:TErrStreamConsole::Output", console(1));
    if (gaddr a = rt.symbol("_Z7YYErrorPKcz")) rt.hook(a, "hook:YYError", console(0));
    // No sound yet (milestone 5): report "no audio device" so OpenAL never starts its mixer thread.
    if (gaddr a = rt.symbol("alcOpenDevice"))
        rt.hook(a, "hook:alcOpenDevice", [this](Cpu &c) {
            rt.log("sound: no audio device yet (game runs silently)");
            c.ret(0);
        });
    if (debug_png)
        if (gaddr a = rt.symbol("png_error"))
            rt.hook(a, "hook:png_error", [this](Cpu &c) {
                rt.stop(StopReason::CpuException, "libpng error: " + rt.read_string(c.arg(1)));
                c.halt();
            });
    // Debug server and memory dumps aren't wanted.
    for (const char *s : {"_ZN9DbgServer4InitEv", "_ZN9DbgServerC2Eb", "_ZN9DbgServerD2Ev"})
        if (gaddr a = rt.symbol(s)) rt.hook(a, std::string("hook:") + s, [](Cpu &c) { c.ret(0); });
}

}  // namespace po
