// ocr_engine.cpp - the OCR module end to end on a rendered page. Needs a fetched profile in
// JPDFIUM_OCR_PROFILE (native/ocr/tools/fetch_profile.py); exits 77 (skipped) without one.

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

#include "jpdfium_api.h"
#include "jpdfium_ocr.h"

namespace fs = std::filesystem;

namespace {

int g_failures = 0;

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

std::string last_error() {
    uint64_t needed = 0;
    jpdf_last_error_message(nullptr, 0, &needed);
    std::string s(static_cast<size_t>(needed), '\0');
    jpdf_last_error_message(s.data(), needed, &needed);
    if (!s.empty()) s.pop_back();
    return s;
}

std::string one_page(const std::string& content) {
    const std::string objects[] = {
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 << "
        "/Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> /Contents 4 0 R >>",
        "<< /Length " + std::to_string(content.size()) + " >>\nstream\n" + content + "\nendstream",
    };
    std::string pdf = "%PDF-1.7\n";
    std::vector<size_t> offsets;
    for (size_t i = 0; i < 4; ++i) {
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

struct Result {
    std::vector<std::string> lines;
    std::vector<double> confidence;
};

Result read_result(jpdf_handle result) {
    Result out;
    jpdf_ocr_result_info info{};
    info.struct_size = sizeof(info);
    if (jpdf_ocr_result_get_info(result, &info) != JPDF_OK) return out;
    for (int32_t i = 0; i < info.line_count; ++i) {
        jpdf_ocr_line line{};
        line.struct_size = sizeof(line);
        uint64_t needed = 0;
        jpdf_ocr_result_get_line(result, i, &line, nullptr, 0, &needed);
        std::string text(static_cast<size_t>(needed), '\0');
        jpdf_ocr_result_get_line(result, i, &line, text.data(), needed, &needed);
        text.pop_back();
        out.lines.push_back(text);
        out.confidence.push_back(line.confidence);
    }
    return out;
}

}  // namespace

int main() {
    const char* profile = std::getenv("JPDFIUM_OCR_PROFILE");
    if (!profile || !*profile) {
        std::printf("SKIP: set JPDFIUM_OCR_PROFILE to a fetched profile directory\n");
        return 77;
    }
    std::printf("=== OCR module end to end ===\n");
    int32_t level = -1;
    CHECK(jpdf_ocr_capability("ocr.recognize", &level) == JPDF_OK && level == JPDF_CAP_IMPLEMENTED,
          "ocr.recognize implemented");
    CHECK(jpdf_ocr_capability("ocr.word_geometry", &level) == JPDF_OK && level == JPDF_CAP_ABSENT,
          "word geometry absent");
    if (jpdf_runtime_init(nullptr) != JPDF_OK) return 1;

    const fs::path dir = fs::temp_directory_path() / "jpdf-ocr-engine";
    fs::create_directories(dir);
    const std::string pdf_path = (dir / "page.pdf").string();
    std::ofstream(pdf_path, std::ios::binary) << one_page(
        "BT /F1 18 Tf 72 700 Td (The quick brown fox jumps over the lazy dog.) Tj "
        "0 -40 Td /F1 12 Tf (Invoice 2026-10-11 total 1,234.56 EUR) Tj ET");

    jpdf_ocr_options o{};
    o.struct_size = sizeof(o);
    o.profile_dir_utf8 = profile;
    jpdf_handle engine = 0;
    CHECK_STATUS(jpdf_ocr_engine_open(&o, &engine), JPDF_OK, "open engine");
    if (!engine) {
        std::printf("  %s\n", last_error().c_str());
        return 1;
    }
    jpdf_ocr_engine_info info{};
    info.struct_size = sizeof(info);
    CHECK_STATUS(jpdf_ocr_engine_get_info(engine, &info), JPDF_OK, "engine info");
    // Either PP-OCRv6 small profile: they differ only in detection size.
    CHECK(std::strncmp(info.profile_id, "pp-ocrv6-small", 14) == 0, "profile id");
    std::printf("  runtime %s, profile %s\n", info.runtime_version, info.profile_id);

    jpdf_handle doc = 0, page = 0, bitmap = 0;
    jpdf_doc_open_file(pdf_path.c_str(), nullptr, &doc);
    jpdf_page_open(doc, 0, &page);
    jpdf_render_options r{};
    r.struct_size = sizeof(r);
    r.dpi_x = r.dpi_y = 300;
    r.pixel_format = JPDF_PIXEL_GRAY8;
    CHECK_STATUS(jpdf_page_render(page, &r, &bitmap), JPDF_OK, "render page");
    jpdf_page_close(page);
    jpdf_doc_close(doc);

    jpdf_handle result = 0;
    CHECK_STATUS(jpdf_ocr_recognize(engine, bitmap, nullptr, &result), JPDF_OK, "recognize");
    const Result got = read_result(result);
    CHECK(got.lines.size() == 2, "two lines");
    if (got.lines.size() == 2) {
        CHECK(got.lines[0] == "The quick brown fox jumps over the lazy dog.", "first line text");
        CHECK(got.lines[1] == "Invoice 2026-10-11 total 1,234.56 EUR", "second line text");
    }
    for (size_t i = 0; i < got.lines.size(); ++i) {
        std::printf("  [%s] %.4f\n", got.lines[i].c_str(), got.confidence[i]);
    }

    // Closing the bitmap does not affect the result; results and engines close independently.
    jpdf_bitmap_close(bitmap);
    CHECK_STATUS(jpdf_ocr_result_get_line(result, 5, nullptr, nullptr, 0, nullptr),
                 JPDF_E_INVALID_ARGUMENT, "line needs outputs");
    jpdf_ocr_line line{};
    line.struct_size = sizeof(line);
    uint64_t needed = 0;
    CHECK_STATUS(jpdf_ocr_result_get_line(result, 99, &line, nullptr, 0, &needed), JPDF_E_NOT_FOUND,
                 "line out of range");
    CHECK_STATUS(jpdf_ocr_recognize(result, bitmap, nullptr, &result), JPDF_E_WRONG_HANDLE_TYPE,
                 "a result is not an engine");
    CHECK_STATUS(jpdf_ocr_result_close(result), JPDF_OK, "close result");
    CHECK_STATUS(jpdf_ocr_result_close(result), JPDF_E_STALE_HANDLE, "closed result is stale");

    // Concurrent recognitions on one engine, then a close while none run.
    jpdf_handle shared = 0;
    jpdf_render_options small = r;
    small.dpi_x = small.dpi_y = 150;
    jpdf_doc_open_file(pdf_path.c_str(), nullptr, &doc);
    jpdf_page_open(doc, 0, &page);
    jpdf_page_render(page, &small, &shared);
    jpdf_page_close(page);
    jpdf_doc_close(doc);
    std::vector<std::thread> workers;
    std::vector<std::string> first(4);
    for (int t = 0; t < 4; ++t) {
        workers.emplace_back([&, t] {
            jpdf_handle res = 0;
            if (jpdf_ocr_recognize(engine, shared, nullptr, &res) == JPDF_OK) {
                const Result out = read_result(res);
                if (!out.lines.empty()) first[static_cast<size_t>(t)] = out.lines[0];
                jpdf_ocr_result_close(res);
            }
        });
    }
    for (std::thread& w : workers) w.join();
    CHECK(!first[0].empty() && first[0] == first[1] && first[1] == first[2] && first[2] == first[3],
          "concurrent recognitions agree");
    jpdf_bitmap_close(shared);

    // A tampered profile file is refused before any model loads.
    CHECK_STATUS(jpdf_ocr_engine_close(engine), JPDF_OK, "close engine");
    const fs::path bad = dir / "tampered";
    fs::remove_all(bad);
    fs::copy(profile, bad);
    {
        std::ofstream f(bad / "dictionary.txt", std::ios::binary | std::ios::app);
        f << "x\n";
    }
    o.profile_dir_utf8 = nullptr;
    const std::string bad_dir = bad.string();
    o.profile_dir_utf8 = bad_dir.c_str();
    jpdf_handle refused = 0;
    CHECK_STATUS(jpdf_ocr_engine_open(&o, &refused), JPDF_E_FORMAT, "tampered profile refused");
    CHECK(last_error().find("digest") != std::string::npos, "the refusal names the digest");

    std::error_code ec;
    fs::remove_all(dir, ec);
    std::printf("=== failures: %d ===\n", g_failures);
    return g_failures == 0 ? 0 : 1;
}
