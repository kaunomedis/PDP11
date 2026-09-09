/*
 * dump2bin.c
 *
 * Converts a PDP-11 style octal memory-dump text file into a raw
 * binary image covering the 16-bit address space 0000-FFFF (64 KiB).
 *
 * Only lines that look like a dump record are used:
 *
 *      000000:  000 100 350 210 354 323 340 000  .@......
 *
 * i.e. an octal address, a colon, then exactly 8 octal byte values.
 * Every other line (register dumps, "===" separators, ">> r", etc.)
 * is silently ignored.
 *
 * Build (MinGW / gcc on Windows):
 *      gcc -O2 -Wall -o dump2bin.exe dump2bin.c
 *
 * Usage:
 *      dump2bin.exe input.txt output.bin [fillbyte]
 *
 *      input.txt   - the octal dump text file
 *      output.bin  - resulting binary image, always 65536 (0x10000) bytes
 *      fillbyte    - optional hex byte (00-FF) used for addresses that
 *                    never appear in the dump. Default is 00.
 *
 * The address printed in the dump is itself written in octal digits
 * (e.g. "000010" means octal 10 = decimal 8), matching the PDP-11
 * "db" (dump bytes) command output. Addresses are interpreted modulo
 * 65536 in case a dump ever lists something outside that range.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define IMAGE_SIZE 65536UL /* 0000-FFFF */

int main(int argc, char *argv[])
{
    if (argc < 3) {
        fprintf(stderr,
            "Usage: %s <input_octal_dump.txt> <output.bin> [fillbyte_hex]\n",
            argv[0]);
        return 1;
    }

    const char *in_path  = argv[1];
    const char *out_path = argv[2];

    unsigned fill = 0x00;
    if (argc >= 4) {
        unsigned long v = strtoul(argv[3], NULL, 16);
        if (v > 0xFF) {
            fprintf(stderr, "fillbyte must be 00-FF (hex)\n");
            return 1;
        }
        fill = (unsigned)v;
    }

    unsigned char *image = (unsigned char *)malloc(IMAGE_SIZE);
    if (!image) {
        fprintf(stderr, "Out of memory\n");
        return 1;
    }
    memset(image, fill, IMAGE_SIZE);

    FILE *fin = fopen(in_path, "r");
    if (!fin) {
        perror("fopen input");
        free(image);
        return 1;
    }

    char line[512];
    unsigned long lines_used = 0;
    unsigned long bytes_written = 0;

    while (fgets(line, sizeof line, fin)) {
        char addr_str[16];
        unsigned b[8];

        /* Expect: optional leading spaces, digits, ':', then 8 octal bytes.
         * The trailing ASCII column (if present) is ignored automatically
         * since sscanf stops once the 8 numeric fields are consumed. */
        int n = sscanf(line, " %15[0-9] : %o %o %o %o %o %o %o %o",
                        addr_str,
                        &b[0], &b[1], &b[2], &b[3],
                        &b[4], &b[5], &b[6], &b[7]);

        if (n != 9)
            continue; /* not a data line -> ignore */

        /* Reject anything that isn't a plausible byte value just in case */
        int ok = 1;
        for (int i = 0; i < 8; i++) {
            if (b[i] > 0xFF) { ok = 0; break; }
        }
        if (!ok)
            continue;

        /* The address digits themselves are octal (PDP-11 "db" format) */
        unsigned long addr = strtoul(addr_str, NULL, 8);
        addr &= (IMAGE_SIZE - 1); /* wrap/mask into 0000-FFFF */

        for (int i = 0; i < 8; i++) {
            unsigned long a = (addr + (unsigned long)i) & (IMAGE_SIZE - 1);
            image[a] = (unsigned char)b[i];
            bytes_written++;
        }
        lines_used++;
    }

    fclose(fin);

    FILE *fout = fopen(out_path, "wb");
    if (!fout) {
        perror("fopen output");
        free(image);
        return 1;
    }

    size_t written = fwrite(image, 1, IMAGE_SIZE, fout);
    fclose(fout);
    free(image);

    if (written != IMAGE_SIZE) {
        fprintf(stderr, "Error writing output file\n");
        return 1;
    }

    fprintf(stderr,
        "Parsed %lu data line(s), wrote %lu byte(s) into a %lu-byte image -> %s\n",
        lines_used, bytes_written, (unsigned long)IMAGE_SIZE, out_path);

    return 0;
}
