#include "fake_jni.h"

#include <set>

namespace po {

namespace {
const char *kTypes[] = {"Object", "Boolean", "Byte", "Char", "Short", "Int", "Long", "Float", "Double", "Void"};
const char *kPrims[] = {"Boolean", "Byte", "Char", "Short", "Int", "Long", "Float", "Double"};
const uint32_t kPrimSize[] = {1, 1, 2, 2, 4, 8, 4, 8};

// The 233 slots of JNINativeInterface, in order.
std::vector<std::string> jni_names() {
    std::vector<std::string> n = {"reserved0", "reserved1", "reserved2", "reserved3", "GetVersion", "DefineClass",
        "FindClass", "FromReflectedMethod", "FromReflectedField", "ToReflectedMethod", "GetSuperclass",
        "IsAssignableFrom", "ToReflectedField", "Throw", "ThrowNew", "ExceptionOccurred", "ExceptionDescribe",
        "ExceptionClear", "FatalError", "PushLocalFrame", "PopLocalFrame", "NewGlobalRef", "DeleteGlobalRef",
        "DeleteLocalRef", "IsSameObject", "NewLocalRef", "EnsureLocalCapacity", "AllocObject", "NewObject",
        "NewObjectV", "NewObjectA", "GetObjectClass", "IsInstanceOf", "GetMethodID"};
    for (const char *t : kTypes) for (const char *v : {"", "V", "A"}) n.push_back(std::string("Call") + t + "Method" + v);
    for (const char *t : kTypes) for (const char *v : {"", "V", "A"}) n.push_back(std::string("CallNonvirtual") + t + "Method" + v);
    n.push_back("GetFieldID");
    for (int i = 0; i < 9; i++) n.push_back(std::string("Get") + kTypes[i] + "Field");
    for (int i = 0; i < 9; i++) n.push_back(std::string("Set") + kTypes[i] + "Field");
    n.push_back("GetStaticMethodID");
    for (const char *t : kTypes) for (const char *v : {"", "V", "A"}) n.push_back(std::string("CallStatic") + t + "Method" + v);
    n.push_back("GetStaticFieldID");
    for (int i = 0; i < 9; i++) n.push_back(std::string("GetStatic") + kTypes[i] + "Field");
    for (int i = 0; i < 9; i++) n.push_back(std::string("SetStatic") + kTypes[i] + "Field");
    for (const char *s : {"NewString", "GetStringLength", "GetStringChars", "ReleaseStringChars", "NewStringUTF",
                          "GetStringUTFLength", "GetStringUTFChars", "ReleaseStringUTFChars", "GetArrayLength",
                          "NewObjectArray", "GetObjectArrayElement", "SetObjectArrayElement"})
        n.push_back(s);
    for (const char *p : kPrims) n.push_back(std::string("New") + p + "Array");
    for (const char *p : kPrims) n.push_back(std::string("Get") + p + "ArrayElements");
    for (const char *p : kPrims) n.push_back(std::string("Release") + p + "ArrayElements");
    for (const char *p : kPrims) n.push_back(std::string("Get") + p + "ArrayRegion");
    for (const char *p : kPrims) n.push_back(std::string("Set") + p + "ArrayRegion");
    for (const char *s : {"RegisterNatives", "UnregisterNatives", "MonitorEnter", "MonitorExit", "GetJavaVM",
                          "GetStringRegion", "GetStringUTFRegion", "GetPrimitiveArrayCritical",
                          "ReleasePrimitiveArrayCritical", "GetStringCritical", "ReleaseStringCritical",
                          "NewWeakGlobalRef", "DeleteWeakGlobalRef", "ExceptionCheck", "NewDirectByteBuffer",
                          "GetDirectBufferAddress", "GetDirectBufferCapacity", "GetObjectRefType"})
        n.push_back(s);
    return n;
}

// Return type of a JNI call slot name like "CallStaticIntMethodV" -> 'I'.
char ret_kind(const std::string &slot) {
    static const std::pair<const char *, char> k[] = {{"Object", 'L'}, {"Boolean", 'Z'}, {"Byte", 'B'}, {"Char", 'C'},
        {"Short", 'S'}, {"Int", 'I'}, {"Long", 'J'}, {"Float", 'F'}, {"Double", 'D'}, {"Void", 'V'}};
    for (auto &p : k) if (slot.find(p.first) != std::string::npos) return p.second;
    return 'V';
}

std::vector<char> param_kinds(const std::string &sig) {
    std::vector<char> v;
    size_t i = sig.find('(') + 1;
    while (i < sig.size() && sig[i] != ')') {
        char ch = sig[i];
        if (ch == 'L') { v.push_back('L'); i = sig.find(';', i) + 1; continue; }
        if (ch == '[') {
            while (sig[i] == '[') i++;
            if (sig[i] == 'L') i = sig.find(';', i);
            v.push_back('L');
            i++;
            continue;
        }
        v.push_back(ch);
        i++;
    }
    return v;
}

constexpr uint32_t kArrHeader = 8;  // [length][element size] then data
}  // namespace

FakeJni::FakeJni(Runtime &rt) : rt_(rt) {
    std::vector<std::string> names = jni_names();
    // JNIEnv: a pointer to the function table, followed by the table itself.
    env_ = rt.malloc(4 + 4 * static_cast<uint32_t>(names.size()));
    gaddr table = env_ + 4;
    rt.mem.write32(env_, table);
    for (size_t i = 0; i < names.size(); i++) {
        std::string slot = names[i];
        std::string thunk = "JNIEnv::" + slot;
        HostFn fn;
        bool is_call = slot.rfind("Call", 0) == 0;
        if (is_call) {
            char rk = ret_kind(slot);
            bool is_static = slot.find("Static") != std::string::npos;
            bool nonvirt = slot.find("Nonvirtual") != std::string::npos;
            char mode = slot.back() == 'V' ? 'V' : slot.back() == 'A' ? 'A' : '.';
            int first = nonvirt ? 4 : 3;  // env, obj/cls, [cls,] methodID, args...
            fn = [this, rk, mode, first, is_static](Cpu &c) {
                (void)is_static;
                uint32_t mid = c.arg(first - 1);
                uint64_t r;
                if (mode == 'V') { VaArgs a(rt_.mem, c.arg(first)); r = invoke(c, mid, &a, 0); }
                else if (mode == 'A') r = invoke(c, mid, nullptr, c.arg(first));
                else { RegArgs a(c, first); r = invoke(c, mid, &a, 0); }
                if (rk == 'J' || rk == 'D') c.ret64(r);
                else c.ret(static_cast<uint32_t>(r));
            };
        } else if (slot == "FindClass") {
            fn = [this](Cpu &c) { c.ret(intern_class(rt_.read_string(c.arg(1)))); };
        } else if (slot == "GetMethodID" || slot == "GetStaticMethodID" || slot == "GetFieldID" || slot == "GetStaticFieldID") {
            bool st = slot.find("Static") != std::string::npos;
            fn = [this, st](Cpu &c) {
                c.ret(member(c.arg(1), rt_.read_string(c.arg(2)), rt_.read_string(c.arg(3)), st));
            };
        } else if (slot == "GetObjectClass") {
            fn = [this](Cpu &c) { c.ret(intern_class("java/lang/Object")); };
        } else if (slot == "NewStringUTF") {
            fn = [this](Cpu &c) { c.ret(c.arg(1) ? rt_.strdup(rt_.read_string(c.arg(1))) : 0); };
        } else if (slot == "GetStringUTFChars" || slot == "GetStringChars" || slot == "GetStringCritical") {
            fn = [this](Cpu &c) {
                if (c.arg(2)) rt_.mem.write32(c.arg(2), 0);
                c.ret(c.arg(1));
            };
        } else if (slot == "GetStringUTFLength" || slot == "GetStringLength") {
            fn = [this](Cpu &c) { c.ret(static_cast<uint32_t>(rt_.read_string(c.arg(1)).size())); };
        } else if (slot == "NewGlobalRef" || slot == "NewLocalRef" || slot == "NewWeakGlobalRef") {
            fn = [](Cpu &c) { c.ret(c.arg(1)); };
        } else if (slot == "PopLocalFrame") {
            fn = [](Cpu &c) { c.ret(c.arg(1)); };
        } else if (slot == "IsInstanceOf" || slot == "IsSameObject") {
            fn = [](Cpu &c) { c.ret(c.arg(1) == c.arg(2) || c.arg(2) ? 1 : 0); };
        } else if (slot == "GetVersion") {
            fn = [](Cpu &c) { c.ret(0x00010006); };
        } else if (slot == "GetJavaVM") {
            fn = [this](Cpu &c) { rt_.mem.write32(c.arg(1), vm_); c.ret(0); };
        } else if (slot == "GetArrayLength") {
            fn = [this](Cpu &c) { c.ret(c.arg(1) ? rt_.mem.read32(c.arg(1) - kArrHeader) : 0); };
        } else if (slot.rfind("New", 0) == 0 && slot.find("Array") != std::string::npos) {
            uint32_t esz = 4;
            for (int k = 0; k < 8; k++) if (slot == std::string("New") + kPrims[k] + "Array") esz = kPrimSize[k];
            fn = [this, esz](Cpu &c) {
                uint32_t n = c.arg(1);
                gaddr b = rt_.malloc(kArrHeader + n * esz);
                std::memset(rt_.mem.host(b), 0, kArrHeader + n * esz);
                rt_.mem.write32(b, n);
                rt_.mem.write32(b + 4, esz);
                c.ret(b + kArrHeader);
            };
        } else if ((slot.rfind("Get", 0) == 0 && slot.find("ArrayElements") != std::string::npos) ||
                   slot == "GetPrimitiveArrayCritical") {
            fn = [this](Cpu &c) { if (c.arg(2)) rt_.mem.write32(c.arg(2), 0); c.ret(c.arg(1)); };
        } else if (slot.find("ArrayRegion") != std::string::npos) {
            bool get = slot.rfind("Get", 0) == 0;
            fn = [this, get](Cpu &c) {
                gaddr arr = c.arg(1);
                uint32_t esz = rt_.mem.read32(arr - 4), start = c.arg(2), len = c.arg(3);
                uint8_t *data = rt_.mem.host(arr + start * esz), *buf = rt_.mem.host(c.arg(4));
                if (get) std::memcpy(buf, data, len * esz);
                else std::memcpy(data, buf, len * esz);
            };
        } else if (slot == "GetObjectArrayElement") {
            fn = [this](Cpu &c) { c.ret(rt_.mem.read32(c.arg(1) + 4 * c.arg(2))); };
        } else if (slot == "SetObjectArrayElement") {
            fn = [this](Cpu &c) { rt_.mem.write32(c.arg(1) + 4 * c.arg(2), c.arg(3)); };
        } else if (slot.rfind("Get", 0) == 0 && slot.find("Field") != std::string::npos) {
            fn = [this, slot](Cpu &c) {
                warn_once(slot + " (returning 0)");
                c.ret64(0);
            };
        } else {
            // Release*, Delete*, Exception*, MonitorEnter/Exit, RegisterNatives, EnsureLocalCapacity, …
            bool quiet = slot.rfind("Release", 0) == 0 || slot.rfind("Delete", 0) == 0 ||
                         slot.rfind("Exception", 0) == 0 || slot.rfind("Monitor", 0) == 0 ||
                         slot == "EnsureLocalCapacity" || slot == "PushLocalFrame" || slot == "RegisterNatives";
            fn = [this, slot, quiet](Cpu &c) {
                if (!quiet) warn_once("JNI " + slot + " is not implemented (returning 0)");
                c.ret(0);
            };
        }
        rt.def(thunk, fn);
        rt.mem.write32(table + 4 * static_cast<uint32_t>(i), rt.thunks.function(thunk, true));
    }

    // JavaVM: DestroyJavaVM, AttachCurrentThread, DetachCurrentThread, GetEnv, AttachCurrentThreadAsDaemon.
    vm_ = rt.malloc(4 + 4 * 8);
    gaddr vt = vm_ + 4;
    rt.mem.write32(vm_, vt);
    const char *vm_names[] = {"reserved0", "reserved1", "reserved2", "DestroyJavaVM", "AttachCurrentThread",
                              "DetachCurrentThread", "GetEnv", "AttachCurrentThreadAsDaemon"};
    for (uint32_t i = 0; i < 8; i++) {
        std::string n = std::string("JavaVM::") + vm_names[i];
        bool writes_env = i == 4 || i == 6 || i == 7;
        rt.def(n, [this, writes_env](Cpu &c) {
            if (writes_env && c.arg(1)) rt_.mem.write32(c.arg(1), env_);
            c.ret(0);
        });
        rt.mem.write32(vt + 4 * i, rt.thunks.function(n, true));
    }
}

void FakeJni::warn_once(const std::string &what) {
    std::lock_guard<std::mutex> lk(mx_);
    if (warned_.insert(what).second) rt_.log("%s", what.c_str());
}

uint32_t FakeJni::intern_class(const std::string &n) {
    std::lock_guard<std::mutex> lk(mx_);
    for (size_t i = 0; i < classes_.size(); i++) if (classes_[i] == n) return 0x10000 + static_cast<uint32_t>(i);
    classes_.push_back(n);
    return 0x10000 + static_cast<uint32_t>(classes_.size() - 1);
}

uint32_t FakeJni::member(uint32_t cls, const std::string &name, const std::string &sig, bool is_static) {
    std::lock_guard<std::mutex> lk(mx_);
    for (size_t i = 0; i < members_.size(); i++)
        if (members_[i].cls == cls && members_[i].name == name && members_[i].sig == sig) return 0x20000 + static_cast<uint32_t>(i);
    members_.push_back({cls, name, sig, is_static});
    return 0x20000 + static_cast<uint32_t>(members_.size() - 1);
}

uint64_t FakeJni::invoke(Cpu &c, uint32_t mid, ArgSource *src, gaddr jvalues) {
    Member m;
    std::string cls;
    {
        std::lock_guard<std::mutex> lk(mx_);
        if (mid < 0x20000 || mid - 0x20000 >= members_.size()) {
            rt_.log("JNI call with unknown method id 0x%x", mid);
            return 0;
        }
        m = members_[mid - 0x20000];
        cls = m.cls >= 0x10000 && m.cls - 0x10000 < classes_.size() ? classes_[m.cls - 0x10000] : "?";
    }
    JavaCall call{cls, m.name, m.sig, {}};
    std::vector<char> kinds = param_kinds(m.sig);
    for (size_t i = 0; i < kinds.size(); i++) {
        char k = kinds[i];
        uint64_t v;
        if (jvalues) {
            gaddr p = jvalues + 8 * static_cast<uint32_t>(i);
            v = (k == 'J' || k == 'D') ? (rt_.mem.read32(p) | (static_cast<uint64_t>(rt_.mem.read32(p + 4)) << 32)) : rt_.mem.read32(p);
        } else if (k == 'J' || k == 'D') {
            v = src->next64();
        } else if (k == 'F') {  // varargs promote float to double
            float f = static_cast<float>(src->nextd());
            uint32_t u;
            std::memcpy(&u, &f, 4);
            v = u;
        } else {
            v = src->next32();
        }
        call.args.push_back(v);
    }
    std::string simple = cls.substr(cls.find_last_of('/') + 1);
    JavaMethod fn;
    {
        std::lock_guard<std::mutex> lk(mx_);
        auto it = methods_.find(simple + "." + m.name);
        if (it == methods_.end()) it = methods_.find(m.name);
        if (it != methods_.end()) fn = it->second;
    }
    if (fn) return fn(c, call);
    warn_once("Java method not provided yet: " + cls + "." + m.name + m.sig + " (returning 0)");
    return 0;
}

}  // namespace po
