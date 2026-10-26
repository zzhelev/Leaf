#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define FNV_PRIME 0x01000193u
#define FNV_OFFSET 2166136261u

/* FNV-1a over a buffer. */
static uint32_t fnv1a(const unsigned char *data, size_t length) {
    uint32_t hash = FNV_OFFSET;
    for (size_t i = 0; i < length; i++) {
        hash ^= data[i];
        hash *= FNV_PRIME;
    }
    return hash;
}

int main(int argc, char **argv) {
    const char *text = argc > 1 ? argv[1] : "leaf";
    // Prints the hash in hex.
    printf("%s -> %08x\n", text, fnv1a((const unsigned char *)text, strlen(text)));
    return 0;
}
