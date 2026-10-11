// api_image.cpp - jpdf_image_load_file on hand-built PNGs of every colour type.

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

#include "jpdfium_api.h"

namespace fs = std::filesystem;

static int g_failures = 0;

#define CHECK(cond, msg)                                       \
    do {                                                       \
        if (!(cond)) {                                         \
            std::printf("FAIL %s (line %d)\n", msg, __LINE__); \
            ++g_failures;                                      \
        }                                                      \
    } while (0)

#define CHECK_STATUS(expr, expected, msg)                                                   \
    do {                                                                                    \
        jpdf_status _st = (expr);                                                           \
        if (_st != (expected)) {                                                            \
            std::printf("FAIL %s: got %s, want %s (line %d)\n", msg, jpdf_status_name(_st), \
                        jpdf_status_name(expected), __LINE__);                              \
            ++g_failures;                                                                   \
        }                                                                                   \
    } while (0)

namespace {

uint32_t crc32(const std::string& data) {
    uint32_t c = 0xFFFFFFFFu;
    for (unsigned char b : data) {
        c ^= b;
        for (int k = 0; k < 8; ++k) c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
    }
    return c ^ 0xFFFFFFFFu;
}

void be32(std::string& out, uint32_t v) {
    for (int s = 24; s >= 0; s -= 8) out += static_cast<char>((v >> s) & 0xFF);
}

void chunk(std::string& png, const char* type, const std::string& payload) {
    be32(png, static_cast<uint32_t>(payload.size()));
    const std::string body = std::string(type, 4) + payload;
    png += body;
    be32(png, crc32(body));
}

// zlib with stored (uncompressed) deflate blocks.
std::string zlib_stored(const std::string& raw) {
    std::string out = "\x78\x01";
    size_t pos = 0;
    do {
        const size_t n = std::min<size_t>(65535, raw.size() - pos);
        const bool last = pos + n == raw.size();
        out += static_cast<char>(last ? 1 : 0);
        out += static_cast<char>(n & 0xFF);
        out += static_cast<char>(n >> 8);
        out += static_cast<char>(~n & 0xFF);
        out += static_cast<char>((~n >> 8) & 0xFF);
        out += raw.substr(pos, n);
        pos += n;
    } while (pos < raw.size());
    uint32_t a = 1, b = 0;
    for (unsigned char c : raw) {
        a = (a + c) % 65521;
        b = (b + a) % 65521;
    }
    be32(out, (b << 16) | a);
    return out;
}

// rows: filter byte 0 is prepended to each row here.
std::string png(int w, int h, int depth, int color, const std::vector<std::string>& rows,
                const std::string& extra_chunks = "") {
    std::string out = "\x89PNG\r\n\x1a\n";
    std::string ihdr;
    be32(ihdr, static_cast<uint32_t>(w));
    be32(ihdr, static_cast<uint32_t>(h));
    ihdr += static_cast<char>(depth);
    ihdr += static_cast<char>(color);
    ihdr += std::string(3, '\0');
    chunk(out, "IHDR", ihdr);
    out += extra_chunks;
    std::string raw;
    for (const std::string& r : rows) raw += '\0' + r;
    chunk(out, "IDAT", zlib_stored(raw));
    chunk(out, "IEND", "");
    return out;
}

std::string write(const std::string& name, const std::string& bytes) {
    const fs::path p = fs::temp_directory_path() / ("jpdf-image-" + name);
    std::ofstream(p, std::ios::binary) << bytes;
    return p.string();
}

struct Loaded {
    jpdf_status status = JPDF_E_INTERNAL;
    jpdf_bitmap_info info{};
    std::vector<uint8_t> pixels;
};

Loaded load(const std::string& path, uint64_t max_pixels = 0) {
    Loaded out;
    jpdf_image_load_options o{};
    o.struct_size = sizeof(o);
    o.max_pixels = max_pixels;
    jpdf_handle bmp = 0;
    out.status = jpdf_image_load_file(path.c_str(), &o, &bmp);
    if (out.status != JPDF_OK) return out;
    out.info.struct_size = sizeof(out.info);
    jpdf_bitmap_get_info(bmp, &out.info);
    uint64_t size = 0;
    jpdf_bitmap_copy_pixels(bmp, nullptr, 0, &size);
    out.pixels.resize(size);
    jpdf_bitmap_copy_pixels(bmp, out.pixels.data(), size, &size);
    jpdf_bitmap_close(bmp);
    return out;
}

std::string bytes(std::initializer_list<int> v) {
    std::string s;
    for (int b : v) s += static_cast<char>(b);
    return s;
}

}  // namespace

int main() {
    std::printf("=== jpdf_image_load_file ===\n");
    // pHYs: 11811 px/m is 300 dpi (11811 * 0.0254 = 299.9994).
    std::string phys;
    be32(phys, 11811);
    be32(phys, 11811);
    phys += '\1';
    std::string phys_chunk;
    chunk(phys_chunk, "pHYs", phys);

    Loaded gray = load(write("gray.png", png(2, 1, 8, 0, {bytes({0, 200})}, phys_chunk)));
    CHECK_STATUS(gray.status, JPDF_OK, "8-bit gray");
    CHECK(
        gray.info.pixel_format == JPDF_PIXEL_GRAY8 && gray.pixels == std::vector<uint8_t>({0, 200}),
        "gray stays one channel");
    CHECK(gray.info.dpi_x > 299.99 && gray.info.dpi_x < 300.01, "DPI from pHYs");

    Loaded mono = load(write("mono.png", png(10, 1, 1, 0, {bytes({0xA0, 0x40})})));
    CHECK(mono.status == JPDF_OK &&
              mono.pixels == std::vector<uint8_t>({255, 0, 255, 0, 0, 0, 0, 0, 0, 255}),
          "1-bit expands to 0 and 255");

    Loaded deep = load(write("deep.png", png(2, 1, 16, 0, {bytes({0x12, 0x34, 0xFF, 0x01})})));
    CHECK(deep.status == JPDF_OK && deep.pixels == std::vector<uint8_t>({0x12, 0xFF}),
          "16-bit keeps the high byte");

    Loaded rgba = load(write("rgba.png", png(1, 1, 8, 6, {bytes({10, 20, 30, 0})})));
    CHECK(rgba.status == JPDF_OK && rgba.info.pixel_format == JPDF_PIXEL_RGB8 &&
              rgba.pixels == std::vector<uint8_t>({10, 20, 30}),
          "alpha is dropped without compositing");

    Loaded ga = load(write("ga.png", png(1, 1, 8, 4, {bytes({77, 0})})));
    CHECK(ga.status == JPDF_OK && ga.info.pixel_format == JPDF_PIXEL_GRAY8 &&
              ga.pixels == std::vector<uint8_t>({77}),
          "gray with alpha becomes gray");

    std::string palette;
    chunk(palette, "PLTE", bytes({255, 0, 0, 0, 0, 255}));
    Loaded pal = load(write("pal.png", png(2, 1, 8, 3, {bytes({1, 0})}, palette)));
    CHECK(pal.status == JPDF_OK && pal.pixels == std::vector<uint8_t>({0, 0, 255, 255, 0, 0}),
          "palette expands to RGB");

    CHECK_STATUS(
        load(write("big.png", png(4, 4, 8, 0, {"aaaa", "aaaa", "aaaa", "aaaa"})), 15).status,
        JPDF_E_LIMIT, "max_pixels bounds the decode");
    CHECK_STATUS(load(write("junk.png", "not a png at all")).status, JPDF_E_FORMAT, "not a PNG");
    CHECK_STATUS(load(write("cut.png", png(2, 2, 8, 0, {"ab", "cd"}).substr(0, 40))).status,
                 JPDF_E_FORMAT, "truncated PNG");
    CHECK_STATUS(load((fs::temp_directory_path() / "jpdf-image-missing.png").string()).status,
                 JPDF_E_IO, "missing file");
    jpdf_handle bmp = 0;
    jpdf_image_load_options flags{};
    flags.struct_size = sizeof(flags);
    flags.flags = 1;
    CHECK_STATUS(
        jpdf_image_load_file(write("flags.png", png(1, 1, 8, 0, {"a"})).c_str(), &flags, &bmp),
        JPDF_E_UNSUPPORTED, "unknown flags");

    for (const char* name : {"gray.png", "mono.png", "deep.png", "rgba.png", "ga.png", "pal.png",
                             "big.png", "junk.png", "cut.png", "flags.png"}) {
        std::error_code ec;
        fs::remove(fs::temp_directory_path() / (std::string("jpdf-image-") + name), ec);
    }
    std::printf("=== failures: %d ===\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
