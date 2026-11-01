#include "apk.h"

#include <cstring>
#include <fstream>
#include <stdexcept>
#include <zlib.h>

namespace po {

template <typename T> static T rd(const uint8_t *p) {
    T v;
    std::memcpy(&v, p, sizeof v);
    return v;
}

Apk::Apk(const std::string &path) : path_(path) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) throw std::runtime_error("can't open " + path);
    uint64_t size = static_cast<uint64_t>(f.tellg());
    uint64_t tail = std::min<uint64_t>(size, 65557);
    std::vector<uint8_t> buf(tail);
    f.seekg(static_cast<std::streamoff>(size - tail));
    f.read(reinterpret_cast<char *>(buf.data()), static_cast<std::streamsize>(tail));
    int64_t eocd = -1;
    for (int64_t i = static_cast<int64_t>(tail) - 22; i >= 0; i--)
        if (rd<uint32_t>(&buf[i]) == 0x06054b50) { eocd = i; break; }
    if (eocd < 0) throw std::runtime_error(path + " is not a zip/APK file");
    uint32_t cd_size = rd<uint32_t>(&buf[eocd + 12]), cd_off = rd<uint32_t>(&buf[eocd + 16]);
    std::vector<uint8_t> cd(cd_size);
    f.seekg(cd_off);
    f.read(reinterpret_cast<char *>(cd.data()), cd_size);
    for (size_t p = 0; p + 46 <= cd.size();) {
        if (rd<uint32_t>(&cd[p]) != 0x02014b50) break;
        Entry e;
        e.method = rd<uint16_t>(&cd[p + 10]);
        e.comp_size = rd<uint32_t>(&cd[p + 20]);
        e.size = rd<uint32_t>(&cd[p + 24]);
        uint16_t nlen = rd<uint16_t>(&cd[p + 28]), xlen = rd<uint16_t>(&cd[p + 30]), clen = rd<uint16_t>(&cd[p + 32]);
        e.local_offset = rd<uint32_t>(&cd[p + 42]);
        entries_[std::string(reinterpret_cast<char *>(&cd[p + 46]), nlen)] = e;
        p += 46 + nlen + xlen + clen;
    }
}

std::vector<std::string> Apk::names() const {
    std::vector<std::string> n;
    for (auto &e : entries_) n.push_back(e.first);
    return n;
}

std::vector<uint8_t> Apk::read(const std::string &name) const {
    auto it = entries_.find(name);
    if (it == entries_.end()) throw std::runtime_error(name + " not found in the APK");
    const Entry &e = it->second;
    std::ifstream f(path_, std::ios::binary);
    uint8_t lh[30];
    f.seekg(e.local_offset);
    f.read(reinterpret_cast<char *>(lh), 30);
    uint32_t data = e.local_offset + 30 + rd<uint16_t>(lh + 26) + rd<uint16_t>(lh + 28);
    std::vector<uint8_t> comp(e.comp_size);
    f.seekg(data);
    f.read(reinterpret_cast<char *>(comp.data()), e.comp_size);
    if (e.method == 0) return comp;
    std::vector<uint8_t> out(e.size);
    z_stream z{};
    inflateInit2(&z, -MAX_WBITS);
    z.next_in = comp.data();
    z.avail_in = e.comp_size;
    z.next_out = out.data();
    z.avail_out = e.size;
    int r = inflate(&z, Z_FINISH);
    inflateEnd(&z);
    if (r != Z_STREAM_END) throw std::runtime_error("couldn't decompress " + name);
    return out;
}

}  // namespace po
