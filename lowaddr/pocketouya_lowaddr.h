#ifndef POCKETOUYA_LOWADDR_H
#define POCKETOUYA_LOWADDR_H
#include <stdint.h>

#define LOWADDR_MAX_MAPS 16384
#define LOWADDR_MAX_RANGES 256
#define LOWADDR_FLOOR 0x10000u                       /* never count the null page area */
#define LOWADDR_TOP 0xFFFF0000u                      /* vectors page and above stay alone */
#define LOWADDR_MIN_FREE_BELOW (768u * 1024u * 1024u) /* keep at least this much for the game */
#define LOWADDR_STACK_ROOM (64u * 1024u * 1024u)     /* left free under the main thread stack */
#define LOWADDR_MIN_RANGE 0x10000u                   /* skip slivers smaller than 64 KB */

struct lowaddr_plan {
    uint32_t limit, free_below, reserved;
    int count;
    uint32_t start[LOWADDR_MAX_RANGES], size[LOWADDR_MAX_RANGES];
};

/* 0: plan filled; 1: too little room below either limit (count 0); -1: maps not understood. */
int lowaddr_plan(const char *maps, struct lowaddr_plan *plan);
#endif
