// JNI entry points used by PocketMicro's Java code (64-bit side of the app).
#include "shims_gl.h"
#include "yoyo.h"

#include <android/log.h>
#include <jni.h>
#include <sstream>

using namespace po;

static std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

namespace {
// In-app log: the start of the run (the interesting part) plus the most recent lines.
std::mutex g_log_mutex;
std::string g_log_head, g_log_tail;
constexpr size_t kHead = 48 * 1024, kTail = 32 * 1024;
void attach_log(Runtime &rt) {
    rt.log_sink = [](const std::string &line) {
        __android_log_print(ANDROID_LOG_INFO, "PocketMicro", "%s", line.c_str());
        std::lock_guard<std::mutex> lk(g_log_mutex);
        std::string &dst = g_log_head.size() < kHead ? g_log_head : g_log_tail;
        dst += line;
        dst += "\n";
        if (g_log_tail.size() > 2 * kTail) g_log_tail.erase(0, g_log_tail.size() - kTail);
    };
}
std::string take_log() {
    std::lock_guard<std::mutex> lk(g_log_mutex);
    if (g_log_tail.empty()) return g_log_head;
    return g_log_head + "\n[… skipped …]\n\n" + g_log_tail;
}

// The running game (one per process; the game screen runs in its own process).
std::unique_ptr<YoyoGame> g_game;
GlState g_gl;
}  // namespace

// Milestone 3a: boot a GameMaker game's 32-bit engine until its first graphics call.
extern "C" JNIEXPORT jstring JNICALL
Java_com_hcode91_pocketmicro_Engine_bootTest(JNIEnv *env, jclass, jstring apk, jstring save) {
    std::string result;
    {
        YoyoGame game(jstr(env, apk), jstr(env, save));
        attach_log(game.rt);
        bool ok = game.boot();
        for (int f = 0; ok && f < 3; f++) ok = game.frame(1280, 720);
        StopReason r = game.rt.stop_reason.load();
        if (r == StopReason::GraphicsReached)
            result = "SUCCESS – the 32-bit engine booted on this phone and asked for graphics.\n(" + game.rt.stop_message + ")\n\n";
        else
            result = "Stopped: " + (r == StopReason::None ? std::string("finished without needing graphics") : game.rt.stop_message) + "\n\n";
    }
    result += "---- log ----\n" + take_log();
    return env->NewStringUTF(result.c_str());
}

// Game screen: called on the GL thread once the surface exists.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hcode91_pocketmicro_Engine_start(JNIEnv *env, jclass, jstring apk, jstring save, jstring pkg) {
    g_game = std::make_unique<YoyoGame>(jstr(env, apk), jstr(env, save));
    g_game->package = jstr(env, pkg);
    attach_log(g_game->rt);
    g_game->platform_setup = [](Runtime &rt) { register_gl(rt, g_gl); };
    g_game->rt.stop_on_missing = true;
    return g_game->boot() ? JNI_TRUE : JNI_FALSE;
}

// Game screen: one frame (GL thread).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hcode91_pocketmicro_Engine_frame(JNIEnv *, jclass, jint w, jint h) {
    return g_game && g_game->frame(w, h) ? JNI_TRUE : JNI_FALSE;
}

// Input: queue a key press (0 = down, 1 = up) with an Android key code. Any thread.
extern "C" JNIEXPORT void JNICALL
Java_com_hcode91_pocketmicro_Engine_key(JNIEnv *, jclass, jint action, jint keycode) {
    if (g_game) g_game->key_event(action, keycode);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hcode91_pocketmicro_Engine_log(JNIEnv *env, jclass) {
    std::string s;
    if (g_game) {
        StopReason r = g_game->rt.stop_reason.load();
        if (r != StopReason::None) s = "Stopped: " + g_game->rt.stop_message + "\n\n";
    }
    s += "---- log ----\n" + take_log();
    return env->NewStringUTF(s.c_str());
}
