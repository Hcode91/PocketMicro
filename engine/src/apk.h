// Minimal ZIP reader for pulling .so files and assets out of an APK.
#pragma once
#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace po {

class Apk {
public:
    explicit Apk(const std::string &path);
    bool has(const std::string &name) const { return entries_.count(name) != 0; }
    std::vector<uint8_t> read(const std::string &name) const;
    std::vector<std::string> names() const;

private:
    struct Entry { uint32_t local_offset, comp_size, size; uint16_t method; };
    std::string path_;
    std::map<std::string, Entry> entries_;
};

}  // namespace po
