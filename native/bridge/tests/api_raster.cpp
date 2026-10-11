// api_raster.cpp - jpdf_rasterize_page_file contract and jpdf_bitmap_rectify, real PDFium.

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#if defined(_WIN32)
#include <process.h>
#else
#include <unistd.h>
#endif

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

// --- image load suite (merged from api_image.cpp) ---
// jpdf_image_load_file on hand-built PNGs of every colour type. Kept in its own
// namespace so its counter and helpers cannot collide with the host file's.
namespace image_suite {

int g_failures = 0;

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

int run_image_suite() {
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
    std::printf("=== image failures: %d ===\n", g_failures);
    return g_failures;
}

}  // namespace image_suite

namespace {

fs::path scratch() {
#if defined(_WIN32)
    const long pid = static_cast<long>(::_getpid());
#else
    const long pid = static_cast<long>(::getpid());
#endif
    static const fs::path dir = fs::temp_directory_path() / ("jpdf-raster-" + std::to_string(pid));
    fs::create_directories(dir);
    return dir;
}

// One-page PDF: the given page dictionary entries and content stream.
std::string one_page(const std::string& page_extra, const std::string& content) {
    std::vector<std::string> objects = {
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R " + page_extra + " /Contents 4 0 R >>",
        "<< /Length " + std::to_string(content.size()) + " >>\nstream\n" + content + "\nendstream",
    };
    std::string pdf = "%PDF-1.7\n";
    std::vector<size_t> offsets;
    for (size_t i = 0; i < objects.size(); ++i) {
        offsets.push_back(pdf.size());
        pdf += std::to_string(i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n";
    }
    const size_t xref = pdf.size();
    pdf += "xref\n0 5\n0000000000 65535 f \n";
    for (size_t off : offsets) {
        char line[32];
        std::snprintf(line, sizeof(line), "%010zu 00000 n \n", off);
        pdf += line;
    }
    pdf += "trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n" + std::to_string(xref) + "\n%%EOF\n";
    return pdf;
}

std::string write(const std::string& name, const std::string& bytes) {
    const fs::path p = scratch() / name;
    std::ofstream(p, std::ios::binary) << bytes;
    return p.string();
}

struct PngHeader {
    uint32_t width = 0, height = 0, ppm_x = 0, ppm_y = 0;
    int bit_depth = 0, color_type = 0;
};

uint32_t be32(const std::string& s, size_t at) {
    return (static_cast<uint32_t>(static_cast<uint8_t>(s[at])) << 24) |
           (static_cast<uint32_t>(static_cast<uint8_t>(s[at + 1])) << 16) |
           (static_cast<uint32_t>(static_cast<uint8_t>(s[at + 2])) << 8) |
           static_cast<uint32_t>(static_cast<uint8_t>(s[at + 3]));
}

PngHeader read_png(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    const std::string s((std::istreambuf_iterator<char>(in)), {});
    PngHeader h;
    for (size_t pos = 8; pos + 8 <= s.size();) {
        const uint32_t len = be32(s, pos);
        const std::string kind = s.substr(pos + 4, 4);
        if (kind == "IHDR") {
            h.width = be32(s, pos + 8);
            h.height = be32(s, pos + 12);
            h.bit_depth = static_cast<uint8_t>(s[pos + 16]);
            h.color_type = static_cast<uint8_t>(s[pos + 17]);
        } else if (kind == "pHYs") {
            h.ppm_x = be32(s, pos + 8);
            h.ppm_y = be32(s, pos + 12);
        }
        pos += 12 + len;
    }
    return h;
}

jpdf_raster_options raster(double dpi_x, double dpi_y, int32_t device) {
    jpdf_raster_options o{};
    o.struct_size = sizeof(o);
    o.raster_dpi_x = dpi_x;
    o.raster_dpi_y = dpi_y;
    o.device = device;
    return o;
}

jpdf_raster_result rasterize(const std::string& pdf, const jpdf_raster_options& o,
                             const std::string& out_name, jpdf_status* status) {
    jpdf_raster_result r{};
    r.struct_size = sizeof(r);
    *status = jpdf_rasterize_page_file(pdf.c_str(), 0, &o, (scratch() / out_name).string().c_str(),
                                       nullptr, &r);
    return r;
}

const std::string kSquare = "0 0 0 rg 72 600 100 100 re f";

void test_sizes_dpi_and_devices() {
    const std::string pdf = write("letter.pdf", one_page("/MediaBox [0 0 612 792]", kSquare));
    jpdf_status st;
    auto r = rasterize(pdf, raster(300, 300, JPDF_RASTER_RGB), "rgb.png", &st);
    CHECK_STATUS(st, JPDF_OK, "rgb raster");
    CHECK(r.width == 2550 && r.height == 3300 && r.bits_per_pixel == 24, "300 dpi letter");
    PngHeader h = read_png((scratch() / "rgb.png").string());
    CHECK(h.width == 2550 && h.height == 3300 && h.bit_depth == 8 && h.color_type == 2, "rgb png");
    CHECK(h.ppm_x == 11811 && h.ppm_y == 11811, "pHYs uses Pillow's int(dpi / 0.0254 + 0.5)");

    r = rasterize(pdf, raster(150, 150, JPDF_RASTER_GRAY), "gray.png", &st);
    h = read_png((scratch() / "gray.png").string());
    CHECK(st == JPDF_OK && h.bit_depth == 8 && h.color_type == 0 && r.bits_per_pixel == 8, "gray");
    r = rasterize(pdf, raster(150, 150, JPDF_RASTER_MONOD), "mono.png", &st);
    h = read_png((scratch() / "mono.png").string());
    CHECK(st == JPDF_OK && h.bit_depth == 1 && h.color_type == 0 && r.bits_per_pixel == 1, "mono");
    r = rasterize(pdf, raster(72, 72, JPDF_RASTER_INDEXED), "indexed.png", &st);
    CHECK(st == JPDF_OK && read_png((scratch() / "indexed.png").string()).color_type == 2,
          "png256 is written as RGB");

    jpdf_raster_options o = raster(300, 300, JPDF_RASTER_RGB);
    o.output_dpi_x = 600;
    o.output_dpi_y = 600;
    r = rasterize(pdf, o, "out-dpi.png", &st);
    h = read_png((scratch() / "out-dpi.png").string());
    CHECK(st == JPDF_OK && r.width == 2550 && h.ppm_x == 23622, "output dpi only changes pHYs");
}

void test_rounding_matches_python() {
    // 5pt at 36 dpi is 2.5 px: Python's round() gives 2 (half to even), not 3.
    const std::string pdf = write("tiny.pdf", one_page("/MediaBox [0 0 5 5]", "0 g 0 0 5 5 re f"));
    jpdf_status st;
    auto r = rasterize(pdf, raster(36, 36, JPDF_RASTER_GRAY), "tiny.png", &st);
    CHECK(st == JPDF_OK && r.width == 2 && r.height == 2, "half to even, then trimmed canvas");
    // 7pt at 36 dpi is 3.5 px: half to even gives 4.
    const std::string pdf7 =
        write("tiny7.pdf", one_page("/MediaBox [0 0 7 7]", "0 g 0 0 7 7 re f"));
    r = rasterize(pdf7, raster(36, 36, JPDF_RASTER_GRAY), "tiny7.png", &st);
    CHECK(st == JPDF_OK && r.width == 4 && r.height == 4, "3.5 rounds to 4");
    // DPI is rounded to 6 decimals first, as the reference does.
    const std::string letter = (scratch() / "letter.pdf").string();
    r = rasterize(letter, raster(299.9999999, 299.9999999, JPDF_RASTER_GRAY), "near300.png", &st);
    CHECK(st == JPDF_OK && r.width == 2550 && r.height == 3300, "dpi rounded to 6 decimals");
}

void test_rotation_and_boxes() {
    const std::string letter = (scratch() / "letter.pdf").string();
    jpdf_status st;
    jpdf_raster_options o = raster(300, 150, JPDF_RASTER_GRAY);
    o.rotation_ccw = 90;
    auto r = rasterize(letter, o, "rot90.png", &st);
    // Ghostscript semantics: render at (300, 150) on the page's own axes, then turn the image.
    CHECK(st == JPDF_OK && r.width == 1650 && r.height == 2550, "quarter turn swaps the size");
    CHECK(r.dpi_x == 150 && r.dpi_y == 300, "and the written dpi axes");

    const std::string rotated =
        write("rotate90.pdf", one_page("/MediaBox [0 0 612 792] /Rotate 90", kSquare));
    r = rasterize(rotated, raster(72, 72, JPDF_RASTER_GRAY), "page-rot.png", &st);
    CHECK(st == JPDF_OK && r.width == 792 && r.height == 612, "page /Rotate is honoured");
    o = raster(72, 72, JPDF_RASTER_GRAY);
    o.rotation_ccw = 90;
    r = rasterize(rotated, o, "page-rot-corrected.png", &st);
    CHECK(st == JPDF_OK && r.width == 612 && r.height == 792, "correction applies on top of it");

    const std::string boxed = write(
        "cropbox.pdf", one_page("/MediaBox [0 0 612 792] /CropBox [100 100 400 500]", kSquare));
    r = rasterize(boxed, raster(300, 300, JPDF_RASTER_GRAY), "media.png", &st);
    CHECK(st == JPDF_OK && r.width == 2550 && r.height == 3300, "MediaBox by default");
    o = raster(300, 300, JPDF_RASTER_GRAY);
    o.flags = JPDF_RASTER_USE_CROPBOX;
    r = rasterize(boxed, o, "crop.png", &st);
    CHECK(st == JPDF_OK && r.width == 1250 && r.height == 1667, "CropBox when asked");
}

void test_rejections() {
    const std::string letter = (scratch() / "letter.pdf").string();
    jpdf_status st;
    jpdf_raster_options o = raster(300, 300, JPDF_RASTER_RGB);
    o.flags = JPDF_RASTER_FILTER_VECTOR;
    rasterize(letter, o, "x.png", &st);
    CHECK(st == JPDF_E_UNSUPPORTED, "filter_vector is rejected, not ignored");
    o = raster(300, 300, JPDF_RASTER_RGB);
    o.rotation_ccw = 45;
    rasterize(letter, o, "x.png", &st);
    CHECK(st == JPDF_E_INVALID_ARGUMENT, "rotation must be a quarter turn");
    rasterize(letter, raster(300, 300, 99), "x.png", &st);
    CHECK(st == JPDF_E_INVALID_ARGUMENT, "unknown device");
    o = raster(300, 300, JPDF_RASTER_RGB);
    o.max_pixels = 1000;
    rasterize(letter, o, "x.png", &st);
    CHECK(st == JPDF_E_LIMIT, "pixel budget");
    jpdf_raster_options ok = raster(72, 72, JPDF_RASTER_RGB);
    CHECK_STATUS(jpdf_rasterize_page_file(letter.c_str(), 1, &ok,
                                          (scratch() / "x.png").string().c_str(), nullptr, nullptr),
                 JPDF_E_NOT_FOUND, "page out of range");
    const std::string missing = (scratch() / "missing.pdf").string();
    CHECK_STATUS(jpdf_rasterize_page_file(missing.c_str(), 0, &ok,
                                          (scratch() / "x.png").string().c_str(), nullptr, nullptr),
                 JPDF_E_IO, "missing input");
    CHECK(!fs::exists(scratch() / "x.png"), "no output from refused rasters");
}

jpdf_handle render_bitmap(const std::string& pdf, double dpi, int32_t format) {
    jpdf_handle doc = 0, page = 0, bmp = 0;
    jpdf_render_options o{};
    o.struct_size = sizeof(o);
    o.dpi_x = o.dpi_y = dpi;
    o.pixel_format = format;
    jpdf_doc_open_file(pdf.c_str(), nullptr, &doc);
    jpdf_page_open(doc, 0, &page);
    jpdf_page_render(page, &o, &bmp);
    jpdf_page_close(page);
    jpdf_doc_close(doc);
    return bmp;
}

std::vector<uint8_t> pixels(jpdf_handle bmp) {
    uint64_t size = 0;
    jpdf_bitmap_copy_pixels(bmp, nullptr, 0, &size);
    std::vector<uint8_t> out(size);
    jpdf_bitmap_copy_pixels(bmp, out.data(), size, &size);
    return out;
}

void test_pixel_kernels() {
    // The render path allocates RGBA, narrows in place, and (as of this change) shrinks the
    // allocation. Verify the shipped kernels against the scalar reference formulas by comparing a
    // narrowed render with the same render's RGBA pixels: byte-for-byte, no compiler autovectorizer
    // help, and a width that is not a multiple of the vector length exercises the tail.
    const std::string letter = (scratch() / "letter.pdf").string();
    const jpdf_handle rgba_bmp = render_bitmap(letter, 72, JPDF_PIXEL_RGBA8);
    const jpdf_handle rgb_bmp = render_bitmap(letter, 72, JPDF_PIXEL_RGB8);
    const jpdf_handle gray_bmp = render_bitmap(letter, 72, JPDF_PIXEL_GRAY8);

    jpdf_bitmap_info info{};
    info.struct_size = sizeof(info);
    CHECK_STATUS(jpdf_bitmap_get_info(rgba_bmp, &info), JPDF_OK, "rgba bitmap info");
    const size_t count = static_cast<size_t>(info.width) * static_cast<size_t>(info.height);
    CHECK(info.width > 0 && info.byte_size == count * 4, "rgba info reports the full allocation");
    CHECK(info.width % 16 != 0, "render width exercises the vector tail");

    const std::vector<uint8_t> rgba = pixels(rgba_bmp);
    const std::vector<uint8_t> rgb = pixels(rgb_bmp);
    const std::vector<uint8_t> gray = pixels(gray_bmp);
    CHECK(rgba.size() == count * 4 && rgb.size() == count * 3 && gray.size() == count,
          "narrowed bitmaps report the logical size they were shrunk to");

    bool rgb_exact = rgb.size() == count * 3;
    for (size_t i = 0; rgb_exact && i < count; ++i) {
        rgb_exact = rgb[i * 3] == rgba[i * 4] && rgb[i * 3 + 1] == rgba[i * 4 + 1] &&
                    rgb[i * 3 + 2] == rgba[i * 4 + 2];
    }
    CHECK(rgb_exact, "RGB8 is byte-identical to the scalar RGBA->RGB narrowing");

    bool gray_exact = gray.size() == count;
    for (size_t i = 0; gray_exact && i < count; ++i) {
        const uint32_t r = rgba[i * 4];
        const uint32_t g = rgba[i * 4 + 1];
        const uint32_t b = rgba[i * 4 + 2];
        const uint32_t luma = (r * 19595 + g * 38470 + b * 7471 + 0x8000) >> 16;
        gray_exact = gray[i] == static_cast<uint8_t>(luma);
    }
    CHECK(gray_exact, "GRAY8 is byte-identical to the Pillow ITU-R 601-2 luma formula");

    jpdf_bitmap_close(rgba_bmp);
    jpdf_bitmap_close(rgb_bmp);
    jpdf_bitmap_close(gray_bmp);
}

void test_rectify() {
    const std::string letter = (scratch() / "letter.pdf").string();
    const jpdf_handle src = render_bitmap(letter, 72, JPDF_PIXEL_GRAY8);
    const std::vector<uint8_t> source = pixels(src);
    jpdf_rectify_options o{};
    o.struct_size = sizeof(o);
    jpdf_rectify_result r{};
    r.struct_size = sizeof(r);
    jpdf_handle out = 0;

    // The full image as its own quad, in any corner order, is the identity for every filter.
    const double full[8] = {612, 792, 0, 0, 0, 792, 612, 0};
    for (int32_t interp : {JPDF_INTERP_NEAREST, JPDF_INTERP_BILINEAR, JPDF_INTERP_BICUBIC}) {
        o.interpolation = interp;
        CHECK_STATUS(jpdf_bitmap_rectify(src, full, &o, &out, &r), JPDF_OK, "identity rectify");
        CHECK(r.width == 612 && r.height == 792 && pixels(out) == source, "identity is exact");
        jpdf_bitmap_close(out);
    }
    CHECK(r.quad[0] == 0 && r.quad[1] == 0 && r.quad[2] == 612 && r.quad[5] == 792,
          "corners reordered top-left, top-right, bottom-right, bottom-left");

    // An axis-aligned sub-rectangle with nearest sampling is an exact crop.
    const double crop[8] = {72, 92, 172, 92, 172, 192, 72, 192};
    o.interpolation = JPDF_INTERP_NEAREST;
    CHECK_STATUS(jpdf_bitmap_rectify(src, crop, &o, &out, &r), JPDF_OK, "crop rectify");
    const std::vector<uint8_t> cropped = pixels(out);
    bool exact = r.width == 100 && r.height == 100;
    for (int y = 0; exact && y < 100; ++y) {
        exact = std::equal(cropped.begin() + y * 100, cropped.begin() + y * 100 + 100,
                           source.begin() + (92 + y) * 612 + 72);
    }
    CHECK(exact, "nearest crop matches the source pixels");
    CHECK(cropped[50 * 100 + 50] == 0, "the black square is inside the crop");
    // The homography maps output corners back onto the quad.
    const double* H = r.to_source;
    const double w = H[6] * 100 + H[7] * 100 + H[8];
    CHECK(std::abs((H[0] * 100 + H[1] * 100 + H[2]) / w - 172) < 1e-9 &&
              std::abs((H[3] * 100 + H[4] * 100 + H[5]) / w - 192) < 1e-9,
          "to_source maps the far corner onto the quad");
    jpdf_bitmap_close(out);

    // A skewed quad derives PaddleOCR's truncated edge lengths.
    const double skew[8] = {10, 10, 110.9, 15, 115, 60.7, 12, 55};
    o.interpolation = JPDF_INTERP_BICUBIC;
    o.border = JPDF_BORDER_REPLICATE;
    CHECK_STATUS(jpdf_bitmap_rectify(src, skew, &o, &out, &r), JPDF_OK, "skew rectify");
    CHECK(r.width == 103 && r.height == 45, "derived size truncates the longer edges");
    jpdf_bitmap_close(out);

    const double collinear[8] = {0, 0, 10, 0, 20, 0, 30, 0};
    const double concave[8] = {0, 0, 10, 0, 2, 2, 0, 10};
    const double nan_quad[8] = {0, 0, 10, 0, 10, NAN, 0, 10};
    o = jpdf_rectify_options{};
    o.struct_size = sizeof(o);
    CHECK_STATUS(jpdf_bitmap_rectify(src, collinear, &o, &out, nullptr), JPDF_E_INVALID_ARGUMENT,
                 "collinear quad");
    CHECK_STATUS(jpdf_bitmap_rectify(src, concave, &o, &out, nullptr), JPDF_E_INVALID_ARGUMENT,
                 "concave quad");
    CHECK_STATUS(jpdf_bitmap_rectify(src, nan_quad, &o, &out, nullptr), JPDF_E_INVALID_ARGUMENT,
                 "non-finite quad");
    o.width = 10000;
    o.height = 10000;
    o.max_pixels = 1000000;
    CHECK_STATUS(jpdf_bitmap_rectify(src, crop, &o, &out, nullptr), JPDF_E_LIMIT, "budget");
    jpdf_bitmap_close(src);
}

}  // namespace

int main() {
    std::printf("=== jpdf raster contract and rectify (real PDFium) ===\n");
    if (jpdf_runtime_init(nullptr) != JPDF_OK) {
        std::printf("FAIL runtime init\n");
        return 1;
    }
    test_sizes_dpi_and_devices();
    test_rounding_matches_python();
    test_rotation_and_boxes();
    test_rejections();
    test_pixel_kernels();
    test_rectify();
    g_failures += image_suite::run_image_suite();
    std::error_code ec;
    fs::remove_all(scratch(), ec);
    std::printf("=== failures: %d ===\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
