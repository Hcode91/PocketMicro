/*
 * PocketOuya low-address helper (32-bit ARM, loaded into a converted game on request).
 *
 * The OUYA ran a 32-bit kernel, so a game's memory always sat below 3 GB (in practice mostly
 * below 2 GB). On a 64-bit phone a 32-bit process can get memory right up to 4 GB. Some old
 * engines with their own memory managers misbehave above 2-3 GB. This helper runs once, when
 * the game's launch activity starts, and reserves (PROT_NONE, no memory committed) the free
 * address space above a limit so later allocations land below it, as on the OUYA.
 *
 * It never touches existing mappings (MAP_FIXED_NOREPLACE), leaves room below the main
 * thread's stack, and does nothing if too little space would remain below the limit.
 * It logs what it reserved and where test allocations land, under the tag PocketOuyaLowAddr.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "pocketouya_lowaddr.h"

#define MB (1024u * 1024u)

/* Plans reservations from /proc/self/maps text. Pure: no system calls, testable on a host. */
int lowaddr_plan(const char *maps, struct lowaddr_plan *plan) {
    static const uint32_t limits[] = {0x80000000u, 0xC0000000u};
    static uint32_t start[LOWADDR_MAX_MAPS], end[LOWADDR_MAX_MAPS];
    static unsigned char stack[LOWADDR_MAX_MAPS];
    int n = 0;
    memset(plan, 0, sizeof *plan);
    for (const char *line = maps; line && *line && n < LOWADDR_MAX_MAPS; ) {
        const char *next = strchr(line, '\n');
        size_t len = next ? (size_t) (next - line) : strlen(line);
        unsigned long long s, e;
        if (sscanf(line, "%llx-%llx", &s, &e) == 2 && s < e && e <= 0x100000000ull) {
            if (n > 0 && s < end[n - 1]) return -1; /* maps must be sorted and non-overlapping */
            start[n] = (uint32_t) s;
            end[n] = (uint32_t) (e == 0x100000000ull ? 0xFFFFF000u : e);
            const char *tag = "[stack]";
            stack[n] = 0;
            for (size_t i = 0; i + 7 <= len; i++) if (!memcmp(line + i, tag, 7)) { stack[n] = 1; break; }
            n++;
        }
        line = next ? next + 1 : NULL;
    }
    if (n == 0) return -1;
    for (unsigned li = 0; li < sizeof limits / sizeof limits[0]; li++) {
        uint32_t limit = limits[li], free_below = 0, prev = LOWADDR_FLOOR;
        for (int i = 0; i <= n; i++) {
            uint32_t gap_start = prev, gap_end = i < n ? start[i] : LOWADDR_TOP;
            if (gap_end > limit) gap_end = limit;
            if (gap_end > gap_start) free_below += gap_end - gap_start;
            if (i < n && end[i] > prev) prev = end[i];
            if (prev >= limit) break;
        }
        plan->limit = limit;
        plan->free_below = free_below;
        if (free_below < LOWADDR_MIN_FREE_BELOW) continue;
        plan->count = 0;
        plan->reserved = 0;
        prev = 0;
        for (int i = 0; i <= n; i++) {
            uint32_t gap_start = prev, gap_end = i < n ? start[i] : LOWADDR_TOP;
            if (i < n && stack[i]) gap_end = gap_end > gap_start + LOWADDR_STACK_ROOM ? gap_end - LOWADDR_STACK_ROOM : gap_start;
            if (gap_start < limit) gap_start = limit;
            if (gap_end > LOWADDR_TOP) gap_end = LOWADDR_TOP;
            if (gap_end > gap_start && gap_end - gap_start >= LOWADDR_MIN_RANGE && plan->count < LOWADDR_MAX_RANGES) {
                plan->start[plan->count] = gap_start;
                plan->size[plan->count] = gap_end - gap_start;
                plan->reserved += gap_end - gap_start;
                plan->count++;
            }
            if (i < n && end[i] > prev) prev = end[i];
        }
        return 0;
    }
    plan->count = 0;
    return 1; /* not enough room below either limit: reserve nothing */
}

#ifdef __ANDROID__
#include <android/log.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif
#define TAG "PocketOuyaLowAddr"

static char maps_text[2 * 1024 * 1024];

static int read_maps(void) {
    int fd = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    size_t used = 0;
    for (ssize_t r; used < sizeof maps_text - 1 && (r = read(fd, maps_text + used, sizeof maps_text - 1 - used)) > 0; ) used += (size_t) r;
    close(fd);
    maps_text[used] = 0;
    return used == sizeof maps_text - 1 ? -1 : 0;
}

static void reserve_high_addresses(void) {
    static int done;
    if (done) return;
    done = 1;
    static struct lowaddr_plan plan;
    if (read_maps() != 0) { __android_log_print(ANDROID_LOG_WARN, TAG, "could not read the memory map; nothing reserved"); return; }
    int r = lowaddr_plan(maps_text, &plan);
    if (r < 0) { __android_log_print(ANDROID_LOG_WARN, TAG, "memory map not understood; nothing reserved"); return; }
    if (r > 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "only %u MB free below 0x%08x; nothing reserved", plan.free_below / MB, plan.limit);
        return;
    }
    uint32_t reserved = 0; int ranges = 0, refused = 0;
    for (int i = 0; i < plan.count; i++) {
        void *want = (void *) (uintptr_t) plan.start[i];
        void *got = mmap(want, plan.size[i], PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED_NOREPLACE, -1, 0);
        if (got == MAP_FAILED) { refused++; continue; }
        if (got != want) { munmap(got, plan.size[i]); refused++; continue; } /* kernel ignored NOREPLACE */
        reserved += plan.size[i]; ranges++;
    }
    void *small = malloc(256), *large = malloc(8 * MB);
    __android_log_print(ANDROID_LOG_INFO, TAG,
            "reserved %u MB above 0x%08x in %d range(s) (%d refused); %u MB free below; test allocations small=%p large=%p",
            reserved / MB, plan.limit, ranges, refused, plan.free_below / MB, small, large);
    free(small); free(large);
}

__attribute__((constructor)) static void on_load(void) { reserve_high_addresses(); }

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) { (void) vm; (void) reserved; return JNI_VERSION_1_4; }
#endif
