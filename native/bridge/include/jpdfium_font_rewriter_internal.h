// jpdfium_font_rewriter_internal.h - Internal declarations connecting the modular
// font conformance and PDF/A rewriter translation units.
#pragma once

#include <cstddef>
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

constexpr size_t kMaxRewriterStreamBytes = size_t{64} * 1024U * 1024U;
constexpr size_t kMaxRewriterGraphNodes = 65536;

// Bounded QPDF stream decompression helpers (defined in jpdfium_font_rewriter.cpp).
bool readStreamBounded(QPDFObjectHandle stream, std::string& out,
                       qpdf_stream_decode_level_e dl = qpdf_dl_generalized,
                       size_t maxBytes = kMaxRewriterStreamBytes);
bool readStreamBytesBounded(QPDFObjectHandle stream, std::vector<uint8_t>& out,
                            qpdf_stream_decode_level_e dl = qpdf_dl_generalized,
                            size_t maxBytes = kMaxRewriterStreamBytes);

// Mutex-guarded FreeType library pool (safe across concurrent threads; avoids per-call init).
struct PooledFreeTypeSession {
    FT_Library lib = nullptr;
    PooledFreeTypeSession() = default;
    PooledFreeTypeSession(PooledFreeTypeSession const&) = delete;
    PooledFreeTypeSession& operator=(PooledFreeTypeSession const&) = delete;
    ~PooledFreeTypeSession();
    bool init();
};

// Single-pass categorized QPDF object index to eliminate redundant qpdf.getAllObjects() sweeps.
struct PdfRewriterDocIndex {
    std::vector<QPDFObjectHandle> allObjects;
    bool built = false;
    bool hasOversizedArray = false;
    bool usesDeviceCmyk = false;

    void build(QPDF& qpdf);
};

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

inline uint16_t readU16(const uint8_t* p) {
    return jpdfium::font::readU16BE(p);
}
inline uint32_t readU32(const uint8_t* p) {
    return jpdfium::font::readU32BE(p);
}
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
    uint32_t u =
        jpdfium_font_encoding::baseEncodingUnicode(jpdfium_font_encoding::BaseEncoding::WinAnsi, c);
    return u ? u : static_cast<uint32_t>(c);
}
inline uint32_t macRomanToUnicode(uint8_t c) {
    uint32_t u = jpdfium_font_encoding::baseEncodingUnicode(
        jpdfium_font_encoding::BaseEncoding::MacRoman, c);
    return u ? u : static_cast<uint32_t>(c);
}
inline constexpr uint16_t kAdobeSymbol[224] = {
    // 0x20..0x7F
    0x0020, 0x0021, 0x2200, 0x0023, 0x2203, 0x0025, 0x0026, 0x220B, 0x0028, 0x0029, 0x2217, 0x002B,
    0x002C, 0x2212, 0x002E, 0x002F, 0x0030, 0x0031, 0x0032, 0x0033, 0x0034, 0x0035, 0x0036, 0x0037,
    0x0038, 0x0039, 0x003A, 0x003B, 0x003C, 0x003D, 0x003E, 0x003F, 0x2245, 0x0391, 0x0392, 0x03A7,
    0x2206, 0x0395, 0x03A6, 0x0393, 0x0397, 0x0399, 0x03D1, 0x039A, 0x039B, 0x039C, 0x039D, 0x039F,
    0x03A0, 0x0398, 0x03A1, 0x03A3, 0x03A4, 0x03A5, 0x03C2, 0x2126, 0x039E, 0x03A8, 0x0396, 0x005B,
    0x2234, 0x005D, 0x22A5, 0x005F, 0xF8E5, 0x03B1, 0x03B2, 0x03C7, 0x03B4, 0x03B5, 0x03C6, 0x03B3,
    0x03B7, 0x03B9, 0x03D5, 0x03BA, 0x03BB, 0x00B5, 0x03BD, 0x03BF, 0x03C0, 0x03B8, 0x03C1, 0x03C3,
    0x03C4, 0x03C5, 0x03D6, 0x03C9, 0x03BE, 0x03C8, 0x03B6, 0x007B, 0x007C, 0x007D, 0x223C, 0x0000,
    // 0x80..0x9F
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    // 0xA0..0xFF
    0x20AC, 0x03D2, 0x2032, 0x2264, 0x2044, 0x221E, 0x0192, 0x2663, 0x2666, 0x2665, 0x2660, 0x2194,
    0x2190, 0x2191, 0x2192, 0x2193, 0x00B0, 0x00B1, 0x2033, 0x2265, 0x00D7, 0x221D, 0x2202, 0x2022,
    0x00F7, 0x2260, 0x2261, 0x2248, 0x2026, 0xF8E6, 0xF8E7, 0x21B5, 0x2135, 0x2111, 0x211C, 0x2118,
    0x2297, 0x2295, 0x2205, 0x2229, 0x222A, 0x2283, 0x2287, 0x2284, 0x2282, 0x2286, 0x2208, 0x2209,
    0x2220, 0x2207, 0xF6DA, 0xF6D9, 0xF6DB, 0x220F, 0x221A, 0x22C5, 0x00AC, 0x2227, 0x2228, 0x21D4,
    0x21D0, 0x21D1, 0x21D2, 0x21D3, 0x25CA, 0x2329, 0xF8E8, 0xF8E9, 0xF8EA, 0x2211, 0xF8EB, 0xF8EC,
    0x239D, 0x23A1, 0x23A2, 0x23A3, 0x23A7, 0x23A8, 0x23A9, 0x23AA, 0x0000, 0x232A, 0x222B, 0x2320,
    0x23AE, 0x2321, 0x239E, 0x239F, 0x23A0, 0x23A4, 0x23A5, 0x23A6, 0x23AB, 0x23AC, 0x23AD, 0x0000};

inline uint32_t aglNameToUnicode(const std::string& name) {
    return jpdfium_font_encoding::unicodeForGlyphName(name);
}
inline std::string jsonEscape(std::string_view s) {
    return jpdfium::font::jsonEscape(s);
}

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
    std::vector<uint16_t> bmpUnicodeToGid;
    bool loaded = false;
};

std::vector<uint8_t> buildCmapFormat4(const std::map<uint16_t, uint16_t>& mapping);
std::vector<CmapSubtable> parseCmapSubtables(const std::vector<uint8_t>& cmap);
std::vector<uint8_t> serializeCmapTable(std::vector<CmapSubtable> subs);
// These helpers replace fontBytes in place. Callers that also hold an FT_Face
// created from fontBytes.data() must patch a copy, release the face, and only
// then swap the new buffer in. Never dereference a face after the vector that
// backs it has been reassigned, reallocated, or destroyed.
bool sfntEnsureCmap30(std::vector<uint8_t>& fontBytes, FT_Face face,
                      const std::set<uint32_t>* usedCodes = nullptr,
                      bool singleSubtableOnly = false);
bool sfntEnsureUnicodeCmap(std::vector<uint8_t>& fontBytes, FT_Face face);
// PDF/A-1 6.3.7: symbolic TrueType programs must expose exactly one cmap encoding.
bool sfntPruneSymbolicCmap(std::vector<uint8_t>& fontBytes);
// Applies sfntPruneSymbolicCmap to a symbolic FontFile2 descriptor, replacing the
// stream and returning true when the font program changed.
bool pruneSymbolicTrueTypeProgram(QPDF& qpdf, QPDFObjectHandle& fontObj, QPDFObjectHandle& fd);
bool sfntPatchHmtx(std::vector<uint8_t>& fontBytes,
                   const std::map<uint16_t, uint16_t>& gidAdvances);
uint16_t sfntAppendBlankGlyph(std::vector<uint8_t>& fontBytes, uint16_t advanceFUnits = 0);
bool isSafeFontBuffer(const std::vector<uint8_t>& buf);
// PDF/A-1 permits only Type1C/CIDFontType0C in /FontFile3, not /OpenType.
// Converts a CFF-flavoured OpenType wrapper to a bare CFF stream, or a
// TrueType-flavoured wrapper to /FontFile2, when the outlines can be preserved.
bool convertOpenTypeWrapperForPdfA1(QPDF& qpdf, QPDFObjectHandle& fd, FT_Library ftLib, bool cid);
bool repairCffFontProgramForPdfA(QPDF& qpdf, QPDFObjectHandle& fd);
double cffFdScaleFactor(const std::vector<uint8_t>& cff);
double ftGlyphWidth1000Exact(FT_Face face, FT_UInt gid, int upem, bool isCffOrType1,
                             double extraScale = 1.0);
int ftGlyphWidth1000(FT_Face face, FT_UInt gid, int upem, bool isCffOrType1,
                     double extraScale = 1.0);

const DonorFont* getCachedDonor(FT_Library ftLib, const char* donorDirs, const std::string& key);
std::string selectStyledDonorKey(const std::string& baseFont);

// ---- Content-stream scanning & rewriting (jpdfium_rewriter_streams.cpp) ----

void collectAllUsedFontCodes(QPDF& qpdf, std::map<QPDFObjGen, std::set<uint32_t>>& usedCodes,
                             std::set<QPDFObjGen>& type0Fonts);
void rewriteAllContentStreamCodes(
    QPDF& qpdf, const std::map<QPDFObjGen, std::map<uint32_t, uint32_t>>& codeRemaps,
    const std::set<QPDFObjGen>& type0Fonts);
bool convertCmykOpsToRgb(std::string& data, const std::string& iccCmykName = "");
bool convertRgbOpsToIcc(std::string& data, const std::string& iccRgbName);
bool disableInlineImageInterpolate(std::string& data);

// ---- PDF/A (1b/1a, 2b/2u/2a, 3b/3u/3a) document structure pass (jpdfium_rewriter_pdfa.cpp) ----

struct PdfAStructureContext {
    PdfAStructureContext(QPDF& qpdfIn, int partIn, char confIn, const uint8_t* srgbIn,
                         int64_t srgbLenIn, const uint8_t* cmykIn, int64_t cmykLenIn,
                         std::vector<std::string>& transformsIn)
        : qpdf(qpdfIn),
          pdfaPart(partIn),
          pdfaConformance(confIn),
          srgbIcc(srgbIn),
          srgbIccLen(srgbLenIn),
          cmykIcc(cmykIn),
          cmykIccLen(cmykLenIn),
          transforms(transformsIn) {}

    QPDF& qpdf;
    int pdfaPart = 2;
    char pdfaConformance = 'B';
    const uint8_t* srgbIcc = nullptr;
    int64_t srgbIccLen = 0;
    const uint8_t* cmykIcc = nullptr;
    int64_t cmykIccLen = 0;
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
    PdfRewriterDocIndex docIndex;
    const std::map<QPDFObjGen, std::set<uint32_t>>* usedFontCodes = nullptr;
    FT_Library ftLib = nullptr;
    const std::set<QPDFObjGen>* donorSubstitutedType0 = nullptr;

    QPDFObjectHandle ensureIccRgbArr();
    QPDFObjectHandle ensureIccCmykArr();
};

void stripNonRootMetadataRecursive(QPDFObjectHandle obj, QPDFObjGen rootGen,
                                   std::set<QPDFObjGen>& visited, int depth = 0);
void applyPdfAXmpAndOutputIntent(PdfAStructureContext& pctx, const uint8_t* srgbIcc,
                                 int64_t srgbIccLen, const uint8_t* cmykIcc, int64_t cmykIccLen,
                                 int unembeddedFontsRemaining);
void applyPdfAObjectAndPageNormalization(PdfAStructureContext& pctx);
void applyPdfAProfileExtensions(PdfAStructureContext& pctx);
void applyPdfAStructure(QPDF& qpdf, int pdfaPart, char pdfaConformance, const uint8_t* srgbIcc,
                        int64_t srgbIccLen, const uint8_t* cmykIcc, int64_t cmykIccLen,
                        int unembeddedFontsRemaining, std::vector<std::string>& transforms,
                        const std::map<QPDFObjGen, std::set<uint32_t>>* usedFontCodes = nullptr,
                        FT_Library ftLib = nullptr,
                        const std::set<QPDFObjGen>* donorSubstitutedType0 = nullptr);

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
    FontRewriteContext(QPDF& qpdfIn, FT_Library ftLibIn, uint32_t flagsIn, const char* donorDirsIn)
        : qpdf(qpdfIn), ftLib(ftLibIn), flags(flagsIn), donorDirs(donorDirsIn) {}

    QPDF& qpdf;
    FT_Library ftLib = nullptr;
    uint32_t flags = 0;
    const char* donorDirs = nullptr;
    struct InternedStreamEntry {
        std::vector<uint8_t> bytes;
        QPDFObjectHandle stream;
    };
    std::map<std::string, QPDFObjectHandle> embeddedDonorStreams;
    std::map<std::pair<size_t, uint64_t>, std::vector<InternedStreamEntry>> internedFontStreams;
    std::map<QPDFObjGen, std::set<uint32_t>> usedFontCodes;
    std::map<QPDFObjGen, std::set<uint32_t>> usedFontCids;
    std::set<QPDFObjGen> type0FontGens;
    std::set<QPDFObjGen> donorSubstitutedType0;
    std::map<QPDFObjGen, std::map<uint32_t, uint32_t>> codeRemaps;
    int embeddedCount = 0;
    int widthsFixedCount = 0;
    int cmapFixedCount = 0;
    int notdefFixedCount = 0;
    int metaFixedCount = 0;

    QPDFObjectHandle internFontStream(const std::vector<uint8_t>& bytes, bool isCff = false);
    QPDFObjectHandle getDonorStream(const std::string& key, bool isCff);
};

FT_UInt resolveSimpleGid(FT_Face face, int code, bool isSymbolic, bool isTrueType,
                         const std::string& baseEnc, const std::map<int, std::string>& diffNames);
std::map<int, int> parseWArray(QPDFObjectHandle const& wArr);
QPDFObjectHandle serializeWMap(const std::map<int, int>& wmap);
bool validateOrStripCorruptFontStreams(QPDFObjectHandle fd);

void rewriteType0Font(FontRewriteContext& ctx, QPDFObjectHandle& f, const std::string& bf);
void rewriteSimpleFont(FontRewriteContext& ctx, QPDFObjectHandle& f, std::string& sub,
                       const std::string& bf);
void repairType3Font(FontRewriteContext& ctx, QPDFObjectHandle& f);
void reconcileSimpleFontWidths(FontRewriteContext& ctx, QPDFObjectHandle& f, QPDFObjectHandle& fd,
                               const std::string& sub, bool& isSymbolic, int& fl,
                               std::vector<uint8_t>* preloadedProg = nullptr,
                               jpdfium::font::FtFaceGuard* preloadedFace = nullptr);

void rewriteDocumentFonts(QPDF& qpdf, FT_Library ftLib, uint32_t flags, const char* donorDirs,
                          FontRewriteStats& stats, std::vector<std::string>& transforms,
                          std::map<QPDFObjGen, std::set<uint32_t>>* usedFontCodesOut = nullptr,
                          std::set<QPDFObjGen>* donorSubstitutedType0Out = nullptr);

}  // namespace jpdfium::rewriter
