// jpdfium_redact_internal.h - shared declarations for the redaction engine.
//
// jpdfium_redact.cpp grew past 4800 lines, so the engine is split into one
// focused translation unit per stage (text, fonts, image, fission, pattern,
// audit, api). This header is their contract: shared types and one
// declaration per cross-module function. It holds no logic.

#pragma once

#include <fpdfview.h>

#include <cstdint>
#include <memory>
#include <mutex>
#include <span>
#include <string>
#include <string_view>
#include <vector>

struct DocCore;

#ifdef JPDFIUM_HAS_PCRE2
#define PCRE2_CODE_UNIT_WIDTH 32
#include <pcre2.h>
#endif

#ifdef JPDFIUM_HAS_FREETYPE
#include <ft2build.h>
#include FT_FREETYPE_H
#endif

// Form XObject traversal bound shared by fission and audit.
// Form XObject traversal helpers.
//
// PDFium parses nested form content with a recursion limit of 40 levels
// (kMaxFormLevel in core/fpdfapi/page/cpdf_streamcontentparser.cpp), so deeper
// nesting cannot appear in the page-object model either; mirror that limit to
// stay safe against self-referential form streams.
static constexpr int kMaxFormNesting = 40;

static constexpr FS_MATRIX kIdentityMatrix{1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f};

struct TextMatch {
    std::vector<int> charIndices;                      // text-page char indices for matched chars
    float bboxL = 0, bboxB = 0, bboxR = 0, bboxT = 0;  // tight aggregate bbox (PDF coords)
};

// PCRE2 matching layer (replaces std::wregex entirely).
//
// Limits cap backtracking work and recursion depth; PCRE2_UTF|PCRE2_UCP
// keep \w/\d/\b correct on non-ASCII text.
#ifdef JPDFIUM_HAS_PCRE2
struct Pcre2Pattern {
    pcre2_code* code = nullptr;
    pcre2_match_data* md = nullptr;
    pcre2_match_context* mctx = nullptr;
    pcre2_jit_stack* jst = nullptr;
    bool ownsCode = true;

    Pcre2Pattern() = default;
    Pcre2Pattern(const Pcre2Pattern&) = delete;
    Pcre2Pattern& operator=(const Pcre2Pattern&) = delete;
    Pcre2Pattern(Pcre2Pattern&& o) noexcept
        : code(o.code), md(o.md), mctx(o.mctx), jst(o.jst), ownsCode(o.ownsCode) {
        o.code = nullptr;
        o.md = nullptr;
        o.mctx = nullptr;
        o.jst = nullptr;
        o.ownsCode = false;
    }
    ~Pcre2Pattern() {
        if (mctx) pcre2_match_context_free(mctx);
        if (md) pcre2_match_data_free(md);
        if (ownsCode) {
            if (jst) pcre2_jit_stack_free(jst);
            if (code) pcre2_code_free(code);
        }
    }
    bool valid() const {
        return code && md && mctx;
    }
};

struct CachedPcre2Pattern {
    uint64_t signature = 0;
    std::string canonicalKey;
    std::vector<pcre2_code*> codes;
    pcre2_jit_stack* jst = nullptr;

    ~CachedPcre2Pattern() {
        if (jst) pcre2_jit_stack_free(jst);
        for (pcre2_code* c : codes) {
            if (c) pcre2_code_free(c);
        }
    }
};

struct PatternCache {
    static constexpr size_t kMaxEntries = 32;
    std::vector<std::unique_ptr<CachedPcre2Pattern>> entries;

    CachedPcre2Pattern* find(uint64_t sig, std::string_view key) {
        for (auto& entry : entries) {
            if (entry->signature == sig && entry->canonicalKey == key) {
                return entry.get();
            }
        }
        return nullptr;
    }

    void put(std::unique_ptr<CachedPcre2Pattern> entry) {
        if (entries.size() >= kMaxEntries) {
            entries.erase(entries.begin());
        }
        entries.push_back(std::move(entry));
    }
};
#endif  // JPDFIUM_HAS_PCRE2

#ifdef JPDFIUM_HAS_FREETYPE
extern FT_Library g_ft_lib;
extern std::once_flag g_ft_once;
#endif

void ensureIcuInit();
void ensureFreeTypeInit();
bool loadFontDataWithFallback(FPDF_FONT font, std::vector<uint8_t>& outData);
std::u32string utf8_to_u32(const char* utf8);
std::vector<uint16_t> u32_to_utf16le(const std::u32string& us);
std::u32string fpdfWcharBufToU32(const FPDF_WCHAR* buf, size_t n);
bool isFullyContained(float al, float ab, float ar, float at, float bl, float bb, float br,
                      float bt);
FS_RECTF makePageRect(float l, float b, float r, float t);
bool rectsOverlap(float al, float ab, float ar, float at, float bl, float bb, float br, float bt);
void markTouchedByRects(FPDF_TEXTPAGE textPage, int count, const std::vector<FS_RECTF>& rects,
                        std::vector<char>& out);
float overlapRatio(float al, float ab, float ar, float at, float bl, float bb, float br, float bt);
void buildNormalizedText(FPDF_TEXTPAGE textPage, int count, std::vector<int>& normIdxMap,
                         std::u32string& norm);
std::u32string buildNormalizedText(FPDF_TEXTPAGE textPage, int count, std::vector<int>& normIdxMap);
std::u32string survivingFingerprint(const std::u32string& normalizedText,
                                    const std::vector<int>& normIdxMap,
                                    const std::vector<char>& redactSet);
bool eraseImagePixels(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_PAGEOBJECT imageObj,
                      const FS_MATRIX& imgMatrix, std::span<const FS_RECTF> rects, uint32_t argb,
                      bool* pixelsChanged = nullptr);
bool charInRect(double l, double b, double r, double t, float rl, float rb, float rr, float rt);
std::u32string decomposeLigatures(const std::u32string& input);
uint32_t unicodeToWinAnsiCharcode(uint32_t unicode);
FS_MATRIX concatMatrix(const FS_MATRIX& m, const FS_MATRIX& t);
const char* getStandard14FontName(const char* baseName);
bool isStandard14Font(FPDF_FONT font);
int32_t objectFissionRedact(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_TEXTPAGE textPage,
                            const std::vector<TextMatch>& matches, uint32_t argb,
                            const std::shared_ptr<DocCore>& core,
                            std::vector<FPDF_PAGEOBJECT>* paintedCovers = nullptr,
                            const FS_RECTF* cropRect = nullptr, bool skipTextObjects = false);
bool compilePcre2(const std::u32string& pattern, bool caseless, Pcre2Pattern& out,
                  std::string& err);
bool recomputeMatchBbox(FPDF_TEXTPAGE textPage, TextMatch& m);
void collectPcre2Matches(FPDF_TEXTPAGE textPage, const std::u32string& text,
                         const std::vector<int>& idxMap, const Pcre2Pattern& pc, float padding,
                         std::vector<TextMatch>& out);
void scanPagePatterns(DocCore* core, FPDF_TEXTPAGE tp, const std::u32string& wtext,
                      const std::vector<int>& idxMap, const char** words, int32_t wordCount,
                      bool wholeWord, bool caseSensitive, bool useRegex, float padding,
                      std::vector<TextMatch>& matches,
                      std::vector<Pcre2Pattern>* outCompiledPatterns, int& rejectedPatterns,
                      int& compiledCount);
void alignMatchesToGraphemes(FPDF_TEXTPAGE textPage, const std::vector<uint32_t>& unicodeSeq,
                             std::vector<TextMatch>& matches);
void alignMatchesToShapedClusters(FPDF_TEXTPAGE tp, std::vector<TextMatch>& matches);
bool auditNoSurvivorsInRegion(FPDF_PAGE page, const std::vector<FS_RECTF>& regions,
                              const std::vector<FPDF_PAGEOBJECT>& exclude);
void deletePatternCache(void* p);
