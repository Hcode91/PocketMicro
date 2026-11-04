/* Host test for the low-address planner: cc -I../../lowaddr test_lowaddr.c ../../lowaddr/pocketouya_lowaddr.c */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "pocketouya_lowaddr.h"

#define CHECK(c, ...) do { if (!(c)) { printf("FAIL: " __VA_ARGS__); printf("\n"); exit(1); } } while (0)

/* Shaped like a 32-bit game process on a 64-bit kernel: ART heaps low, libraries and Scaleform-era
   allocations near 4 GB (the Soul Fjord manager sat at 0xe4edb050), main stack at the top. */
static const char *phone =
    "00008000-00010000 r--p 00000000 00:00 0\n"
    "12c00000-32c00000 rw-p 00000000 00:00 0   [anon:dalvik-main space]\n"
    "70000000-70a00000 rw-p 00000000 fd:00 1   /system/framework/arm/boot.art\n"
    "70a00000-71000000 r--p 00000000 fd:00 2   /system/framework/arm/boot.oat\n"
    "d5d25000-d6263000 r-xp 00000000 fd:00 3   /data/app/x/lib/arm/libgfxunity3d.so\n"
    "e4e00000-e5000000 rw-p 00000000 00:00 0   [anon:libc_malloc]\n"
    "e6600000-e6700000 r-xp 00000000 fd:00 4   /apex/com.android.runtime/lib/bionic/libc.so\n"
    "ff000000-ff800000 rw-p 00000000 00:00 0   [stack]\n"
    "ffff0000-ffff1000 r-xp 00000000 00:00 0   [vectors]\n";

static int overlaps(const char *maps, uint32_t s, uint32_t e) {
    for (const char *l = maps; l && *l; ) {
        unsigned long long a, b;
        if (sscanf(l, "%llx-%llx", &a, &b) == 2 && s < b && a < e) return 1;
        l = strchr(l, '\n'); if (l) l++;
    }
    return 0;
}

int main(void) {
    static struct lowaddr_plan p;
    CHECK(lowaddr_plan(phone, &p) == 0, "phone layout planned");
    CHECK(p.limit == 0x80000000u, "2 GB limit chosen when enough room below (limit=%08x)", p.limit);
    CHECK(p.free_below > 768u << 20, "free below counted (%u MB)", p.free_below >> 20);
    CHECK(p.count > 0 && p.reserved > 1000u << 20, "high space reserved (%u MB in %d)", p.reserved >> 20, p.count);
    for (int i = 0; i < p.count; i++) {
        uint32_t s = p.start[i], e = p.start[i] + p.size[i];
        CHECK(s >= 0x80000000u, "range %d starts above the limit", i);
        CHECK(e <= LOWADDR_TOP && e > s, "range %d below the top", i);
        CHECK(!overlaps(phone, s, e), "range %d avoids existing mappings (%08x-%08x)", i, s, e);
        CHECK(!(e > 0xff000000u - LOWADDR_STACK_ROOM && s < 0xff000000u), "range %d leaves room under the stack", i);
    }
    /* Low space mostly taken: falls back to the 3 GB limit. */
    const char *crowded =
        "00010000-7f000000 rw-p 00000000 00:00 0\n"
        "e4e00000-e5000000 rw-p 00000000 00:00 0\n"
        "ff000000-ff800000 rw-p 00000000 00:00 0   [stack]\n";
    CHECK(lowaddr_plan(crowded, &p) == 0 && p.limit == 0xC0000000u, "falls back to 3 GB (limit=%08x)", p.limit);
    for (int i = 0; i < p.count; i++) CHECK(p.start[i] >= 0xC0000000u, "3 GB ranges above 3 GB");
    /* No room anywhere: reserves nothing. */
    const char *full = "00010000-ff000000 rw-p 00000000 00:00 0\nff000000-ff800000 rw-p 00000000 00:00 0   [stack]\n";
    CHECK(lowaddr_plan(full, &p) == 1 && p.count == 0, "nothing reserved without room");
    /* Unsorted or empty maps are refused. */
    CHECK(lowaddr_plan("e0000000-e1000000 r--p 0 0 0\n10000000-11000000 r--p 0 0 0\n", &p) == -1, "unsorted maps refused");
    CHECK(lowaddr_plan("", &p) == -1, "empty maps refused");
    printf("lowaddr planner tests pass\n");
    return 0;
}
