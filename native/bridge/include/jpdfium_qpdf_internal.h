// jpdfium_qpdf_internal.h - Shared QPDF structural, font, and resource helpers
// connecting modular QPDF translation units.
#pragma once

#include <cstdint>
#include <cstdio>
#include <map>
#include <memory>
#include <set>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_font_internal.h"

struct DocWrapper;

#ifdef JPDFIUM_HAS_QPDF
#include <qpdf/Buffer.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFWriter.hh>
#endif

namespace jpdfium::qpdf_detail {

#ifdef JPDFIUM_HAS_QPDF

struct QpdfResult {
    std::shared_ptr<Buffer> buffer;
    std::string error;
    int exitCode = 0;
    bool ok() const {
        return buffer != nullptr;
    }
};

struct OptimizeOptions {
    int32_t flags = 0;
    int32_t compressionLevel = -1;
    int32_t objectStreamMode = -1;
    int32_t streamDataMode = -1;
    int32_t decodeLevel = -1;
    int32_t jpegQuality = -1;
};

// Low-level file/PDF/signature/warning helpers (defined in jpdfium_qpdf.cpp)
FILE* createOutputFile(const char* path);
std::string warningsJson(std::vector<QPDFExc> const& warnings);
const char* boolJson(bool value);
bool pdfHasDigitalSignature(QPDF& pdf);
int32_t writeBufferToC(std::shared_ptr<Buffer> const& buf, uint8_t** output, int64_t* outputLen);
void clampPatternSteps(QPDF& pdf);
void optimizeImages(QPDF& pdf, int32_t jpegQuality);

// Shared QPDF + Font helpers (defined in jpdfium_font_shared.cpp)
jpdfium::font::SimpleEncoding parseSimpleEncoding(QPDFObjectHandle const& font,
                                                  QPDFObjectHandle const& descriptor);
bool hasKnownSimpleEncoding(QPDFObjectHandle const& font);
std::map<uint32_t, std::vector<uint32_t>> parseToUnicode(QPDFObjectHandle const& font);
std::map<uint32_t, uint32_t> parseToUnicodeSingleScalar(QPDFObjectHandle const& font);
bool visitStreamDepth(std::map<QPDFObjGen, int>& seenStreams, QPDFObjectHandle const& stream,
                      int depth);
void collectResourceFonts(QPDFObjectHandle const& resources, int depth,
                          std::set<QPDFObjGen>& seenResources, std::set<QPDFObjGen>& seenFonts,
                          std::map<QPDFObjGen, int>& seenStreams,
                          std::vector<QPDFObjectHandle>& fonts);
std::vector<QPDFObjectHandle> collectPageFonts(QPDFObjectHandle const& page,
                                               std::set<QPDFObjGen>& seenResources,
                                               std::set<QPDFObjGen>& seenFonts);
QPDFObjectHandle buildCidWidthsArray(std::map<uint32_t, int> const& widths, int omitDefaultDw = -1);
struct VerticalMetric {
    int w1y = -1000;
    int vx = 500;
    int vy = 880;
};
QPDFObjectHandle buildCidVerticalWidthsArray(std::map<uint32_t, VerticalMetric> const& metrics,
                                             int defaultW1y = -1000, int defaultVx = 500,
                                             int defaultVy = 880);
std::set<uint32_t> widthCids(QPDFObjectHandle const& font);

// Content stream lexer + CJK CMaps + subset-tag disambiguation (jpdfium_qpdf_cmap.cpp)
enum class CidEncoding : uint8_t {
    TwoByte,
    SingleByte,
    Rksj,
    RksjProp,
    RksjVert,
    RksjPropVert,
    KscEuc,
    KscEucVert,
    GbEuc,
    GbEucVert,
};

struct CidScanResult {
    bool complete = true;
    std::set<uint32_t> cids;
    std::set<uint32_t> codes;
};

struct FontUsageIndex {
    std::map<QPDFObjGen, CidScanResult> byFont;
    std::map<std::string, CidScanResult> byDirect;
    bool complete = true;

    static std::string objectKey(QPDFObjectHandle const& obj);
    static bool isCompositeFont(QPDFObjectHandle const& font);
    CidScanResult* slot(QPDFObjectHandle const& font);
    void collectString(QPDFObjectHandle const& font, std::string_view bytes, bool& ok,
                       CidEncoding encoding);
    CidScanResult const* find(QPDFObjectHandle const& font) const;
};

struct FontUsageIndexes {
    FontUsageIndex cid;
    FontUsageIndex simple;
};

bool isRksjCidEncoding(CidEncoding enc);
bool isRksjPropCidEncoding(CidEncoding enc);
bool isKscEucCidEncoding(CidEncoding enc);
bool isGbEucCidEncoding(CidEncoding enc);
bool isVerticalCidEncoding(CidEncoding enc);

bool japan1CidForCode(uint16_t code, uint32_t& cid, bool proportional = false,
                      bool vertical = false);
bool japan1UnicodeForCid(uint32_t cid, uint32_t& unicode);
bool korea1CidForCode(uint16_t code, uint32_t& cid, bool vertical = false);
bool korea1UnicodeForCid(uint32_t cid, uint32_t& unicode);
bool gb1CidForCode(uint16_t code, uint32_t& cid, bool vertical = false);
bool gb1UnicodeForCid(uint32_t cid, uint32_t& unicode);
bool adobeCollectionUnicodeForCid(std::string_view ordering, uint32_t cid, uint32_t& unicode);

CidEncoding cidEncodingFromName(QPDFObjectHandle const& encoding);
void collectStringCids(std::string_view bytes, std::set<uint32_t>& cids, std::set<uint32_t>* codes,
                       bool& complete, CidEncoding encoding = CidEncoding::TwoByte);
FontUsageIndexes buildFontUsageIndexes(QPDF& qpdf);
CidScanResult usedCidsForFont(QPDF& qpdf, QPDFObjectHandle const& target,
                              CidEncoding encoding = CidEncoding::TwoByte,
                              FontUsageIndexes const* prebuilt = nullptr);

// Disambiguates duplicate ABCDEF+FontName prefixes across distinct indirect
// font objects (common after PDF merges and conflicting Form vs Page subsets).
int disambiguateSubsetFontTags(QPDF& qpdf);

// Structural Resource & Scalar Deduplication (jpdfium_qpdf_dedupe.cpp)
int dedupeResources(QPDF& qpdf);

// Font Embedding & Subsetting (jpdfium_qpdf_fonts_embed.cpp / _subset.cpp)
#if defined(JPDFIUM_HAS_FREETYPE) && defined(JPDFIUM_HAS_HARFBUZZ)
enum class ProgramKind : uint8_t { TrueType, OpenType, BareCff, Type1 };

struct FontProgram {
    FT_Face face = nullptr;
    ProgramKind kind{};
    bool usable = false;
    bool noSubsetting = false;
    std::string bytes;
    long long length1 = 0, length2 = 0, length3 = 0;
    std::map<std::tuple<ProgramKind, bool, std::set<FT_UInt>>, QPDFObjectHandle> streams;
};

bool loadFontProgram(FT_Library lib, std::span<const uint8_t> data, FontProgram& p);
QPDFObjectHandle makeProgramStream(QPDF& qpdf, FontProgram const& p, ProgramKind kind, bool cid,
                                   std::string const& subset, bool normalizeCmap = false);
bool reachesSameGlyphs(FT_Library lib, std::string const& subset, FT_Face original,
                       jpdfium::font::SimpleEncoding const& enc, bool symbolic = false);
bool subsetKeepsCidGlyphs(FT_Library lib, std::string const& subset,
                          std::map<uint32_t, uint32_t> const& unicodeByCid,
                          std::map<uint32_t, FT_UInt> const& cidGlyph);
bool subsetKeepsSimpleGlyphs(FT_Library lib, std::string const& subsetBytes, FT_Face original,
                             bool symbolic, jpdfium::font::SimpleEncoding const& enc,
                             std::set<uint32_t> const& codes, bool retainGids);
std::set<uint32_t> cidsPresentInProgram(QPDFObjectHandle const& target, FT_Face face);
void updateCidSet(QPDF& qpdf, QPDFObjectHandle descriptor, std::set<uint32_t> const& activeCids);
#endif
int subsetEmbeddedFonts(QPDF& qpdf, bool subsetTagged = false);
std::string inspectFonts(QPDF& qpdf);

// Page assembly & document-level helpers (jpdfium_qpdf_pages.cpp)
void applyDocumentOptions(QPDF& pdf, OptimizeOptions const& options);

// PDFium-QPDF Reload & Font Normalizer (jpdfium_advanced.cpp / jpdfium_font_normalizer.cpp)
bool qpdfSaveFpdf(DocWrapper* w, std::vector<uint8_t>& out);
std::vector<uint8_t> qpdfWriteBytes(QPDF& pdf);
int32_t docwrapperReload(DocWrapper* w, const std::vector<uint8_t>& newBytes);
int32_t fontNormalizeQpdf(DocWrapper* w, int32_t pageIndex, bool doFixToUnicode,
                          bool doRepairWidths, int32_t* fontsProcessed, int32_t* tucFixed,
                          int32_t* wFixed, int32_t* type1Converted, int32_t* resubset);

#endif  // JPDFIUM_HAS_QPDF

}  // namespace jpdfium::qpdf_detail
