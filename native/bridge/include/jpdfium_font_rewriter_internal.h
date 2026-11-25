// jpdfium_font_rewriter_internal.h - Internal declarations connecting the modular
// font conformance and PDF/A rewriter translation units.
#pragma once

#include <cstdint>
#include <map>
#include <set>
#include <string>
#include <string_view>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_font_internal.h"
#include "jpdfium_qpdf_internal.h"

namespace jpdfium::rewriter {

constexpr uint32_t kFlagEmbedMissing = 1U << 0;
constexpr uint32_t kFlagReconcileWidths = 1U << 1;
constexpr uint32_t kFlagRepairNotdef = 1U << 2;
constexpr uint32_t kFlagNormalizeCmaps = 1U << 3;
constexpr uint32_t kFlagCleanMetadata = 1U << 4;
constexpr uint32_t kFlagConvertPdfA2b = 1U << 5;
constexpr uint32_t kFlagStripSignatures = 1U << 6;
constexpr uint32_t kFlagConvertPdfA1b = 1U << 7;
constexpr uint32_t kFlagConvertPdfA3b = 1U << 8;
constexpr uint32_t kFlagPdfALevelU = 1U << 9;
constexpr uint32_t kFlagPdfALevelA = 1U << 10;
constexpr uint32_t kFlagInjectDefaultRgb = 1U << 11;

// ---- Shared inline helpers (delegating to jpdfium::font / jpdfium_font_encoding) ----

inline uint16_t readU16(const uint8_t* p) { return jpdfium::font::readU16BE(p); }
inline uint32_t readU32(const uint8_t* p) { return jpdfium::font::readU32BE(p); }
inline void writeU16(std::vector<uint8_t>& buf, size_t off, uint16_t v) {
    jpdfium::font::writeU16BE(buf.data() + off, v);
}
inline void writeU32(std::vector<uint8_t>& buf, size_t off, uint32_t v) {
    jpdfium::font::writeU32BE(buf.data() + off, v);
}
inline void appendU16(std::vector<uint8_t>& buf, uint16_t v) {
    buf.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    buf.push_back(static_cast<uint8_t>(v & 0xFF));
}
inline void appendU32(std::vector<uint8_t>& buf, uint32_t v) {
    buf.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
    buf.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
    buf.push_back(static_cast<uint8_t>(v & 0xFF));
}
inline uint32_t sfntChecksum(const uint8_t* data, size_t len) {
    return jpdfium::font::calcSfntTableChecksum(data, len);
}
inline uint32_t winAnsiToUnicode(uint8_t c) {
    uint32_t u = jpdfium_font_encoding::baseEncodingUnicode(jpdfium_font_encoding::BaseEncoding::WinAnsi, c);
    return u ? u : static_cast<uint32_t>(c);
}
inline uint32_t macRomanToUnicode(uint8_t c) {
    uint32_t u = jpdfium_font_encoding::baseEncodingUnicode(jpdfium_font_encoding::BaseEncoding::MacRoman, c);
    return u ? u : static_cast<uint32_t>(c);
}
inline uint32_t aglNameToUnicode(const std::string& name) {
    return jpdfium_font_encoding::unicodeForGlyphName(name);
}
inline std::string jsonEscape(std::string_view s) { return jpdfium::font::jsonEscape(s); }

std::string toLowerAscii(std::string s);
std::string xmlEscape(std::string_view s);
bool isValidUtf8(const std::string& s);

// ---- SFNT, CFF, FreeType metrics & Donor cache (jpdfium_rewriter_sfnt.cpp) ----

struct SfntFont {
    uint32_t sfntVersion = 0x00010000U;
    std::map<std::string, std::vector<uint8_t>> tables;

    static bool parse(const uint8_t* data, size_t len, SfntFont& out);
    std::vector<uint8_t> serialize();
};

struct CmapSubtable {
    uint16_t platformId = 0;
    uint16_t encodingId = 0;
    std::vector<uint8_t> data;
};

struct DonorFont {
    std::string path;
    std::vector<uint8_t> raw;
    std::vector<uint8_t> cff;
    int upem = 1000;
    int ascent = 800;
    int descent = -200;
    int capHeight = 700;
    int bbox[4] = {-200, -250, 1200, 1000};
    std::vector<int> gidWidths;
    std::vector<int> winAnsiWidths;
    bool loaded = false;
};

std::vector<uint8_t> buildCmapFormat4(const std::map<uint16_t, uint16_t>& mapping);
std::vector<CmapSubtable> parseCmapSubtables(const std::vector<uint8_t>& cmap);
std::vector<uint8_t> serializeCmapTable(std::vector<CmapSubtable> subs);
bool sfntEnsureCmap30(std::vector<uint8_t>& fontBytes, FT_Face face,
                      const std::set<uint32_t>* usedCodes = nullptr,
                      bool singleSubtableOnly = false);
bool sfntPatchHmtx(std::vector<uint8_t>& fontBytes, const std::map<uint16_t, uint16_t>& gidAdvances);
uint16_t sfntAppendBlankGlyph(std::vector<uint8_t>& fontBytes, uint16_t advanceFUnits = 0);

bool isSafeFontBuffer(const std::vector<uint8_t>& buf);
double cffFdScaleFactor(const std::vector<uint8_t>& cff);
double ftGlyphWidth1000Exact(FT_Face face, FT_UInt gid, int upem, bool isCffOrType1, double extraScale = 1.0);
int ftGlyphWidth1000(FT_Face face, FT_UInt gid, int upem, bool isCffOrType1, double extraScale = 1.0);

const DonorFont* getCachedDonor(FT_Library ftLib, const char* donorDirs, const std::string& key);
std::string selectStyledDonorKey(const std::string& baseFont);

// ---- Content-stream scanning & rewriting (jpdfium_rewriter_streams.cpp) ----

void collectAllUsedFontCodes(QPDF& qpdf, std::map<QPDFObjGen, std::set<uint32_t>>& usedCodes,
                             std::set<QPDFObjGen>& type0Fonts);
void rewriteAllContentStreamCodes(QPDF& qpdf,
                                  const std::map<QPDFObjGen, std::map<uint32_t, uint32_t>>& codeRemaps,
                                  const std::set<QPDFObjGen>& type0Fonts);
bool convertCmykOpsToRgb(std::string& data, const std::string& iccCmykName = "");
bool convertRgbOpsToIcc(std::string& data, const std::string& iccRgbName);
bool disableInlineImageInterpolate(std::string& data);

// ---- PDF/A (1b/1a, 2b/2u/2a, 3b/3u/3a) document structure pass (jpdfium_rewriter_pdfa.cpp) ----

struct PdfAStructureContext {
    QPDF& qpdf;
    int pdfaPart;
    char pdfaConformance;
    const uint8_t* srgbIcc;
    int64_t srgbIccLen;
    const uint8_t* cmykIcc;
    int64_t cmykIccLen;
    std::vector<std::string>& transforms;
    QPDFObjectHandle root{};
    bool useCmykIntent = false;
    QPDFObjectHandle iccRgbArr{};
    QPDFObjectHandle iccCmykArr{};
    bool cmykOpsFixed = false;
    bool annotsFixed = false;
    bool extGStateFixed = false;
    bool interpolateFixed = false;
    bool groupFixed = false;
};

void stripNonRootMetadataRecursive(QPDFObjectHandle obj, QPDFObjGen rootGen,
                                   std::set<QPDFObjGen>& visited, int depth = 0);
void applyPdfAXmpAndOutputIntent(PdfAStructureContext& pctx,
                                 const uint8_t* srgbIcc, int64_t srgbIccLen,
                                 const uint8_t* cmykIcc, int64_t cmykIccLen,
                                 int unembeddedFontsRemaining);
void applyPdfAObjectAndPageNormalization(PdfAStructureContext& pctx);
void applyPdfAProfileExtensions(PdfAStructureContext& pctx);
void applyPdfAStructure(QPDF& qpdf, int pdfaPart, char pdfaConformance,
                        const uint8_t* srgbIcc, int64_t srgbIccLen,
                        const uint8_t* cmykIcc, int64_t cmykIccLen,
                        int unembeddedFontsRemaining,
                        std::vector<std::string>& transforms);

// ---- Font enumeration & rewrite stages (jpdfium_rewriter_fonts*.cpp) ----

struct FontRewriteStats {
    int fontsAudited = 0;
    int embeddedCount = 0;
    int widthsFixedCount = 0;
    int cmapFixedCount = 0;
    int notdefFixedCount = 0;
    int metaFixedCount = 0;
    int unembeddedRemaining = 0;
};

struct FontRewriteContext {
    QPDF& qpdf;
    FT_Library ftLib = nullptr;
    uint32_t flags = 0;
    const char* donorDirs = nullptr;
    std::map<std::string, QPDFObjectHandle> embeddedDonorStreams;
    std::map<QPDFObjGen, std::set<uint32_t>> usedFontCodes;
    std::set<QPDFObjGen> type0FontGens;
    std::map<QPDFObjGen, std::map<uint32_t, uint32_t>> codeRemaps;
    int embeddedCount = 0;
    int widthsFixedCount = 0;
    int cmapFixedCount = 0;
    int notdefFixedCount = 0;
    int metaFixedCount = 0;

    QPDFObjectHandle getDonorStream(const std::string& key, bool isCff);
};

FT_UInt resolveSimpleGid(FT_Face face, int code, bool isSymbolic, bool isTrueType,
                         const std::string& baseEnc, const std::map<int, std::string>& diffNames);
std::map<int, int> parseWArray(QPDFObjectHandle wArr);
QPDFObjectHandle serializeWMap(const std::map<int, int>& wmap);
bool validateOrStripCorruptFontStreams(QPDFObjectHandle fd);

void rewriteType0Font(FontRewriteContext& ctx, QPDFObjectHandle& f, const std::string& bf);
void rewriteSimpleFont(FontRewriteContext& ctx, QPDFObjectHandle& f, std::string& sub,
                       const std::string& bf);
void reconcileSimpleFontWidths(FontRewriteContext& ctx, QPDFObjectHandle& f, QPDFObjectHandle& fd,
                               const std::string& sub, bool& isSymbolic, int& fl);

void rewriteDocumentFonts(QPDF& qpdf, FT_Library ftLib, uint32_t flags, const char* donorDirs,
                          FontRewriteStats& stats, std::vector<std::string>& transforms);

}  // namespace jpdfium::rewriter
