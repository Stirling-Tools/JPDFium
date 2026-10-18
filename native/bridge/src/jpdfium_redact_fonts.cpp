// jpdfium_redact_fonts.cpp - font loading for redaction.
//
// ICU initialization plus FreeType setup and system-font fallback
// used when subsetting or measuring glyphs during redaction.

#include <epdf_redact.h>
#include <fpdf_annot.h>
#include <fpdf_edit.h>
#include <fpdf_flatten.h>
#include <fpdf_save.h>
#include <fpdf_text.h>
#include <fpdfview.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <limits>
#include <map>
#include <mutex>
#include <optional>
#include <set>
#include <span>
#include <sstream>
#include <string>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_internal.h"
#include "jpdfium_redact_internal.h"

#ifdef JPDFIUM_HAS_ICU
#include <unicode/normalizer2.h>
#include <unicode/putil.h>
#include <unicode/uclean.h>
#include <unicode/udata.h>
#include <unicode/utypes.h>
#endif

#ifdef JPDFIUM_HAS_FREETYPE
#include <ft2build.h>
#include FT_FREETYPE_H
#endif

#ifdef JPDFIUM_HAS_ICU
void ensureIcuInit() {
    static std::once_flag s_icu_once;
    std::call_once(s_icu_once, [] {
        UErrorCode status = U_ZERO_ERROR;
        u_init(&status);
        if (U_SUCCESS(status)) return;

        static const char* const candidateDirs[] = {"/usr/share/icu",
                                                    "/usr/share/icu/78.1",
                                                    "/usr/share/icu/77.1",
                                                    "/usr/share/icu/76.1",
                                                    "/usr/share/icu/75.1",
                                                    "/usr/share/icu/74.2",
                                                    "/usr/share/icu/74.1",
                                                    "/usr/share/icu/73.2",
                                                    "/usr/share/icu/73.1",
                                                    "/usr/share/icu/72.1",
                                                    "/usr/share/icu/71.1",
                                                    "/usr/share/icu/70.1",
                                                    "/usr/share/icu/69.1",
                                                    "/usr/share/icu/68.2",
                                                    "/usr/share/icu/67.1",
                                                    "/usr/share/icu/66.1",
                                                    "/usr/share/icu/65.1",
                                                    "/usr/share/icu/64.2",
                                                    "/usr/share/icu/60.2",
                                                    "/usr/lib/x86_64-linux-gnu",
                                                    "/usr/lib/aarch64-linux-gnu",
                                                    "/usr/lib",
                                                    "/usr/lib64",
                                                    "/usr/local/share/icu",
                                                    "/opt/homebrew/share/icu",
                                                    "/usr/share",
                                                    "/etc/icu",
                                                    "."};

        for (const char* dir : candidateDirs) {
            status = U_ZERO_ERROR;
            u_setDataDirectory(dir);
            u_init(&status);
            if (U_SUCCESS(status)) {
                return;
            }
        }
    });
}
#endif

#ifdef JPDFIUM_HAS_FREETYPE
FT_Library g_ft_lib = nullptr;
std::once_flag g_ft_once;
void ensureFreeTypeInit() {
    std::call_once(g_ft_once, [] {
        if (!g_ft_lib) FT_Init_FreeType(&g_ft_lib);
    });
}
#endif

namespace fs = std::filesystem;

/**
 * @brief Attempts to locate and load matching TrueType/OpenType font binary data
 *        from system font directories when an embedded font cannot be extracted.
 *
 * Scans standard OS font directories (Linux, macOS, system packages, Java home,
 * and user-supplied environment variables), normalizes font names by removing subset
 * prefixes, resolves style keywords (bold, italic, serif, sans, monospace), indexes
 * available system font files once in a thread-safe cache, and returns loaded bytes.
 *
 * @param fontName The base font name (e.g. "Arial,Bold", "ABCDEF+LiberationSans").
 * @return std::vector<uint8_t> Raw font bytes if found and successfully read, or empty vector.
 */
static std::vector<uint8_t> loadSystemFontFallback(const std::string& fontName) {
    // Return early if no font name is supplied
    if (fontName.empty()) return {};

    // Standard system font search roots across Linux distributions and macOS
    static const std::vector<std::string> fontSearchPaths = [] {
        std::vector<std::string> paths = {"/usr/share/fonts",
                                          "/usr/share/fonts/truetype",
                                          "/usr/share/fonts/truetype/dejavu",
                                          "/usr/share/fonts/truetype/liberation",
                                          "/usr/share/fonts/truetype/noto",
                                          "/usr/share/fonts/truetype/freefont",
                                          "/usr/share/fonts/opentype",
                                          "/usr/share/fonts/dejavu",
                                          "/usr/share/fonts/liberation",
                                          "/usr/share/fonts/noto",
                                          "/usr/share/fonts/freefont",
                                          "/usr/share/fonts/type1",
                                          "/usr/share/fonts/X11",
                                          "/usr/local/share/fonts",
                                          "/var/lib/ghostscript/fonts",
                                          "/usr/share/ghostscript/fonts",
                                          "/System/Library/Fonts",
                                          "/System/Library/Fonts/Supplemental",
                                          "/Library/Fonts"};
        // Check for custom environment variable overrides
        const char* customPath = std::getenv("JPDFIUM_FONT_PATH");
        if (customPath && *customPath) paths.insert(paths.begin(), customPath);
        const char* fcPath = std::getenv("FONTCONFIG_PATH");
        if (fcPath && *fcPath) paths.insert(paths.begin(), fcPath);
        const char* javaHome = std::getenv("JAVA_HOME");
        if (javaHome && *javaHome) {
            paths.push_back(std::string(javaHome) + "/lib/fonts");
        }
        return paths;
    }();

    // Helper lambda to lowercase an ASCII string
    auto toLower = [](std::string s) {
        std::transform(s.begin(), s.end(), s.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        return s;
    };

    // Normalize font name and strip 6-letter subset tag prefix (e.g. "ABCDEF+FontName")
    std::string lowerName = toLower(fontName);
    auto plusPos = lowerName.find('+');
    if (plusPos != std::string::npos && plusPos + 1 < lowerName.size()) {
        lowerName = lowerName.substr(plusPos + 1);
    }

    // Determine candidate font file stems based on family and font style traits
    std::vector<std::string> searchKeywords;
    if (lowerName.find("helvetica") != std::string::npos ||
        lowerName.find("arial") != std::string::npos ||
        lowerName.find("sans") != std::string::npos) {
        // Sans-serif font family matching
        bool bold = lowerName.find("bold") != std::string::npos;
        bool italic = lowerName.find("oblique") != std::string::npos ||
                      lowerName.find("italic") != std::string::npos;
        if (bold && italic) {
            searchKeywords = {"liberationsans-bolditalic", "dejavusans-boldoblique",
                              "arial-bolditalic", "freesansboldoblique", "helvetica-boldoblique"};
        } else if (bold) {
            searchKeywords = {"liberationsans-bold", "dejavusans-bold", "arial-bold",
                              "freesansbold", "helvetica-bold"};
        } else if (italic) {
            searchKeywords = {"liberationsans-italic", "dejavusans-oblique", "arial-italic",
                              "freesansoblique", "helvetica-oblique"};
        } else {
            searchKeywords = {
                "liberationsans-regular", "dejavusans", "freesans", "arial", "helvetica",
                "notosans-regular"};
        }
    } else if (lowerName.find("times") != std::string::npos ||
               lowerName.find("serif") != std::string::npos ||
               lowerName.find("roman") != std::string::npos) {
        // Serif font family matching
        bool bold = lowerName.find("bold") != std::string::npos;
        bool italic = lowerName.find("italic") != std::string::npos;
        if (bold && italic) {
            searchKeywords = {"liberationserif-bolditalic", "dejavuserif-bolditalic",
                              "times-bolditalic", "freeserifbolditalic"};
        } else if (bold) {
            searchKeywords = {"liberationserif-bold", "dejavuserif-bold", "times-bold",
                              "freeserifbold"};
        } else if (italic) {
            searchKeywords = {"liberationserif-italic", "dejavuserif-italic", "times-italic",
                              "freeserifitalic"};
        } else {
            searchKeywords = {"liberationserif-regular", "dejavuserif", "freeserif", "times",
                              "notoserif-regular"};
        }
    } else if (lowerName.find("courier") != std::string::npos ||
               lowerName.find("mono") != std::string::npos) {
        // Monospace font family matching
        bool bold = lowerName.find("bold") != std::string::npos;
        bool italic = lowerName.find("oblique") != std::string::npos ||
                      lowerName.find("italic") != std::string::npos;
        if (bold && italic) {
            searchKeywords = {"liberationmono-bolditalic", "dejavusansmono-boldoblique",
                              "freemonoboldoblique", "courier-boldoblique"};
        } else if (bold) {
            searchKeywords = {"liberationmono-bold", "dejavusansmono-bold", "freemonobold",
                              "courier-bold"};
        } else if (italic) {
            searchKeywords = {"liberationmono-italic", "dejavusansmono-oblique", "freemonooblique",
                              "courier-oblique"};
        } else {
            searchKeywords = {"liberationmono-regular", "dejavusansmono", "freemono", "courier",
                              "notosansmono-regular"};
        }
    } else {
        // Use sanitized font name directly as primary keyword
        searchKeywords = {lowerName};
    }

    // Process-wide font cache state protected by mutex
    static std::mutex s_font_mutex;
    static std::unordered_map<std::string, std::vector<uint8_t>> s_font_data_cache;
    static std::vector<std::pair<std::string, std::string>> s_system_fonts;
    static bool s_indexed = false;

    std::lock_guard<std::mutex> lock(s_font_mutex);

    // Return cached result if this font was previously resolved
    auto cit = s_font_data_cache.find(lowerName);
    if (cit != s_font_data_cache.end()) return cit->second;

    // Scan font directories once on first request and index candidate font files
    if (!s_indexed) {
        s_indexed = true;
        for (const auto& searchPath : fontSearchPaths) {
            std::error_code ec;
            if (!fs::exists(searchPath, ec) || !fs::is_directory(searchPath, ec)) continue;
            for (const auto& entry : fs::recursive_directory_iterator(
                     searchPath, fs::directory_options::skip_permission_denied, ec)) {
                if (ec) break;
                if (!entry.is_regular_file(ec)) continue;
                std::string ext = entry.path().extension().string();
                std::string lowerExt = toLower(ext);
                if (lowerExt == ".ttf" || lowerExt == ".otf" || lowerExt == ".ttc" ||
                    lowerExt == ".pfb") {
                    s_system_fonts.emplace_back(toLower(entry.path().stem().string()),
                                                entry.path().string());
                }
            }
        }
    }

    // Match indexed system fonts against resolved keywords
    std::string matchedPath;
    for (const auto& kw : searchKeywords) {
        for (const auto& [stem, path] : s_system_fonts) {
            if (stem == kw || stem.find(kw) != std::string::npos ||
                kw.find(stem) != std::string::npos) {
                matchedPath = path;
                break;
            }
        }
        if (!matchedPath.empty()) break;
    }

    // Fallback: match any common fallback font if specific keyword match was not found
    if (matchedPath.empty() && !s_system_fonts.empty()) {
        for (const auto& [stem, path] : s_system_fonts) {
            if (stem.find("sans") != std::string::npos ||
                stem.find("dejavu") != std::string::npos ||
                stem.find("liberation") != std::string::npos) {
                matchedPath = path;
                break;
            }
        }
        if (matchedPath.empty()) matchedPath = s_system_fonts[0].second;
    }

    // Read font file contents into memory if a match was identified
    if (!matchedPath.empty()) {
        std::ifstream file(matchedPath, std::ios::binary | std::ios::ate);
        if (file) {
            auto size = file.tellg();
            if (size > 0 && size < 100 * 1024 * 1024) {
                std::vector<uint8_t> data(static_cast<size_t>(size));
                file.seekg(0, std::ios::beg);
                if (file.read(reinterpret_cast<char*>(data.data()), size)) {
                    s_font_data_cache[lowerName] = data;
                    return data;
                }
            }
        }
    }

    // Cache negative lookup so repeated misses don't re-read disk
    s_font_data_cache[lowerName] = {};
    return {};
}

bool loadFontDataWithFallback(FPDF_FONT font, std::vector<uint8_t>& outData) {
    outData.clear();
    if (!font) return false;
    size_t buflen = 0;
    if (FPDFFont_GetFontData(font, nullptr, 0, &buflen) && buflen > 0) {
        outData.resize(buflen);
        size_t actual = 0;
        if (FPDFFont_GetFontData(font, outData.data(), buflen, &actual) && actual > 0) {
            outData.resize(actual);
            return true;
        }
        outData.clear();
    }

    char baseName[256] = {0};
    if (FPDFFont_GetBaseFontName(font, baseName, sizeof(baseName)) > 0 && baseName[0]) {
        outData = loadSystemFontFallback(baseName);
        return !outData.empty();
    }
    return false;
}

// UTF-8 -> std::u32string. Decodes DEFENSIVELY: a truncated multi-byte
// sequence at the end of the buffer (FFI input from Java strings) must never
// read past the NUL terminator; invalid sequences are dropped.
