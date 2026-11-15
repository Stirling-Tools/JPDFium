// jpdfium_font_internal.h - Shared internal declarations for font inspection,
// normalization, subsetting, SFNT repair, and ToUnicode CMap handling.
#pragma once

#include <cstdint>
#include <map>
#include <set>
#include <string>
#include <string_view>
#include <vector>

#include "jpdfium_font_encoding.h"

#if defined(JPDFIUM_HAS_FREETYPE) && defined(JPDFIUM_HAS_HARFBUZZ)
#include <ft2build.h>
#include FT_ADVANCES_H
#include FT_CID_H
#include FT_FONT_FORMATS_H
#include FT_FREETYPE_H
#include FT_SFNT_NAMES_H
#include FT_TRUETYPE_IDS_H
#include FT_TRUETYPE_TABLES_H
#include <hb-subset.h>
#include <hb.h>
#endif

namespace jpdfium::font {

constexpr uint32_t kMaxCid = 0xFFFF;

std::string jsonEscape(std::string_view value);
std::string latin1ToUtf8(std::string_view raw);
std::string stripPdfNamePrefix(std::string name);
std::string normalizeFontNameKey(std::string name);
bool hasSubsetTag(std::string_view name);
std::string stripSubsetTag(std::string_view name);
std::string buildSubsetTag(std::string_view seedName, uint64_t seedHash);
std::string buildSubsetTagForGlyphs(std::string_view baseName, std::set<uint32_t> const& ids);

uint16_t readU16BE(const uint8_t* p);
uint32_t readU32BE(const uint8_t* p);
void writeU16BE(uint8_t* p, uint16_t v);
void writeU32BE(uint8_t* p, uint32_t v);
uint32_t calcSfntTableChecksum(const uint8_t* data, size_t len);
void recomputeHeadChecksumAdjustment(std::string& sfnt, uint32_t headOffset);

// Repairs known real-world malformed SFNT directory metadata before passing
// bytes into HarfBuzz/FreeType (e.g. ScanSoft little-endian post v3 tag and
// corrupt head.indexToLocFormat values).
void sanitizeSfntForSubset(std::string& sfnt);

// Removes conflicting legacy cmap subtables (e.g. MacRoman/Symbol) while
// preserving BMP (3,1) Format 4 and non-BMP (3,10) Format 12 subtables
// (promoting (0,3)/(0,4) Unicode subtables cleanly when needed).
void ensureUnicodeMicrosoftCmap(std::string& sfnt);

uint32_t decodeUnicodeScalar(uint32_t packedOrScalar);
std::vector<uint32_t> decodeUtf16BeHex(std::string_view digits);
void appendUtf16BeHex(std::string& out, uint32_t codepoint);
void appendUtf16BeHex(std::string& out, std::vector<uint32_t> const& codepoints);

std::string stripCmapComments(std::string_view in);
bool cmapHasMappings(std::string_view text);

// Emits a sorted, compressed ToUnicode CMap using beginbfrange for contiguous
// single-scalar BMP runs (within high-byte boundaries per Adobe TechNote 5411)
// and beginbfchar for remaining/ligature/supplementary entries.
std::string buildToUnicodeCmap(std::map<uint32_t, std::vector<uint32_t>> const& mapping,
                               bool twoByte, bool rksj = false, bool gbOrKsc = false);
std::string buildToUnicodeCmap(std::map<uint32_t, uint32_t> const& mapping, bool twoByte,
                               bool rksj = false, bool gbOrKsc = false);

// Parses ToUnicode bfchar/bfrange sections (including multi-codepoint ligatures
// and surrogate pairs). Optionally falls back to begincidchar/begincidrange
// when bfchar/bfrange yield zero entries on non-standard PDFs.
std::map<uint32_t, std::vector<uint32_t>> parseToUnicodeCmap(std::string_view rawCmap,
                                                             bool allowCidFallback = true);

std::string buildCidSetBits(std::set<uint32_t> const& cids);

#if defined(JPDFIUM_HAS_FREETYPE) && defined(JPDFIUM_HAS_HARFBUZZ)

// Per-operation FreeType session (FT_Library is not safe across threads).
struct FreeTypeSession {
    FT_Library lib = nullptr;
    FreeTypeSession() = default;
    FreeTypeSession(FreeTypeSession const&) = delete;
    FreeTypeSession& operator=(FreeTypeSession const&) = delete;
    ~FreeTypeSession();
    bool init();
};

struct FtFaceGuard {
    FT_Face face = nullptr;
    explicit FtFaceGuard(FT_Face f = nullptr) : face(f) {}
    FtFaceGuard(FtFaceGuard const&) = delete;
    FtFaceGuard& operator=(FtFaceGuard const&) = delete;
    ~FtFaceGuard();
    void reset(FT_Face f = nullptr);
};

struct SimpleEncoding {
    jpdfium_font_encoding::BaseEncoding base = jpdfium_font_encoding::BaseEncoding::Standard;
    bool hasExplicitEncoding = false;
    bool hasBaseEncoding = false;
    bool unsupportedEncoding = false;
    bool symbolic = false;
    bool namedBase = false;
    std::map<int, std::string> differences;
};

struct SimpleGlyph {
    FT_UInt gid = 0;
    uint32_t unicode = 0;
};

FT_UInt gidForName(FT_Face face, std::string const& name);
FT_UInt gidForUnicode(FT_Face face, uint32_t unicode, bool hasUnicodeCharmap);
FT_UInt gidFromSymbolicCharmaps(FT_Face face, uint32_t code);
FT_UInt gidFromBuiltInEncoding(FT_Face face, uint32_t code);
uint32_t unicodeFromGlyphNameOnly(FT_Face face, FT_UInt gid);
uint32_t unicodeFromGid(FT_Face face, FT_UInt gid,
                        std::map<uint32_t, uint32_t> const& reverseUnicode);
FT_UInt glyphForSimpleCode(FT_Face face, int code, bool symbolic, SimpleEncoding const& enc);
std::vector<SimpleGlyph> resolveSimpleChars(FT_Face face, bool hasUnicodeCharmap,
                                            std::map<uint32_t, uint32_t> const& gidToUnicode,
                                            SimpleEncoding const& enc, bool isType1Family);

int unicodePreference(FT_ULong u, bool symbolic);
std::map<FT_UInt, FT_ULong> unicodeByGlyph(FT_Face face, bool symbolic);

bool pdfWidthFromGlyph(FT_Face face, FT_UInt gid, int& outWidth);
bool pdfVerticalMetricsFromGlyph(FT_Face face, FT_UInt gid, int& outW1y, int& outVx, int& outVy);

bool fontForbidsSubsetting(FT_Face face);
bool fontHasSfntTable(FT_Face face, FT_ULong tag);
FT_Long selectCollectionFace(FT_Library lib, const uint8_t* data, size_t size,
                             std::set<std::string> const& desiredKeys);
FT_Face loadFontFace(FT_Library lib, std::string const& bytes, FT_Long preferredIndex = 0);

void addDefaultSubsetDropTags(hb_subset_input_t* input);

struct SubsetResult {
    std::string bytes;
    std::map<FT_UInt, FT_UInt> oldToNewGid;
};

SubsetResult subsetSfntProgram(FT_Face face, const uint8_t* data, size_t size, FT_Long faceIndex,
                               std::set<FT_UInt> const& glyphs, bool retainGids,
                               bool keepGlyphNames);

std::string extractCffTableFromSfnt(FT_Library lib, std::string const& sfntBytes);

// Wraps a bare CFF (/Type1C or /CIDFontType0C) font program inside a minimal
// valid OTTO SFNT container so hb-subset can prune unused glyphs/charstrings.
std::string wrapCffInSfnt(FT_Face face, std::string_view cffBytes,
                          std::map<FT_UInt, uint32_t> const* gidToUnicode = nullptr);

SubsetResult subsetBareCffViaSfnt(FT_Face origFace, std::string_view cffBytes,
                                  std::set<FT_UInt> const& glyphs, bool retainGids,
                                  bool keepGlyphNames,
                                  std::map<FT_UInt, uint32_t> const* gidToUnicode = nullptr);

bool subsetKeepsAdvances(FT_Library lib, std::string const& subset, FT_Long faceIndex,
                         FT_Face original, std::set<FT_UInt> const& glyphs,
                         std::map<FT_UInt, FT_UInt> const* oldToNewGid = nullptr);

#endif  // JPDFIUM_HAS_FREETYPE && JPDFIUM_HAS_HARFBUZZ

}  // namespace jpdfium::font
