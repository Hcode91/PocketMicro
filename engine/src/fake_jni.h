// PocketMicro engine – a fake Java environment (JNIEnv / JavaVM) for guest code.
// Java strings are plain guest C strings; arrays are guest blocks with a small header.
// Java methods the game calls are answered by host code registered with java().
#pragma once
#include "abi.h"

#include <functional>
#include <set>
#include <string>
#include <vector>

namespace po {

struct JavaCall {
    std::string cls, name, sig;
    std::vector<uint64_t> args;  // 32-bit values zero-extended; J/D as 64-bit; F as float bits
};
using JavaMethod = std::function<uint64_t(Cpu &, const JavaCall &)>;

class FakeJni {
public:
    explicit FakeJni(Runtime &rt);
    gaddr env() const { return env_; }
    gaddr vm() const { return vm_; }
    // Answer calls to "name" (any class) – or "Class.name" to be specific.
    void java(const std::string &name, JavaMethod m) { methods_[name] = std::move(m); }
    gaddr new_string(const std::string &s) { return rt_.strdup(s); }

private:
    Runtime &rt_;
    gaddr env_ = 0, vm_ = 0;
    std::vector<std::string> classes_;
    struct Member { uint32_t cls; std::string name, sig; bool is_static; };
    std::vector<Member> members_;
    std::map<std::string, JavaMethod> methods_;
    std::mutex mx_;
    std::set<std::string> warned_;

    uint32_t intern_class(const std::string &n);
    uint32_t member(uint32_t cls, const std::string &name, const std::string &sig, bool is_static);
    uint64_t invoke(Cpu &c, uint32_t mid, ArgSource *src, gaddr jvalues);
    void warn_once(const std::string &what);
};

}  // namespace po
