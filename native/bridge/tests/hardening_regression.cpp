// hardening_regression - Native ASan/UBSan regression suite for M1 security,
// memory safety, cycle bounding, CMap preservation, PDF/A-3b preservation,
// stream decompression budgets, and multi-threaded rewriter safety.

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <sstream>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_rust.h"

namespace {

int g_failures = 0;

#define CHECK(cond, what)                                                                         \
    do {                                                                                          \
        if (!(cond)) {                                                                            \
            std::fprintf(stderr, "hardening_regression: FAIL %s (line %d): %s\n", what, __LINE__, \
                         #cond);                                                                  \
            ++g_failures;                                                                         \
        }                                                                                         \
    } while (0)

void pushBe16(std::vector<uint8_t>& out, uint16_t v) {
    out.push_back(static_cast<uint8_t>((v >> 8U) & 0xFFU));
    out.push_back(static_cast<uint8_t>(v & 0xFFU));
}

void pushBe32(std::vector<uint8_t>& out, uint32_t v) {
    out.push_back(static_cast<uint8_t>((v >> 24U) & 0xFFU));
    out.push_back(static_cast<uint8_t>((v >> 16U) & 0xFFU));
    out.push_back(static_cast<uint8_t>((v >> 8U) & 0xFFU));
    out.push_back(static_cast<uint8_t>(v & 0xFFU));
}

std::vector<uint8_t> buildMinimalTrueTypeFont(bool includeUnicodeCmap) {
    struct Table {
        const char* tag;
        std::vector<uint8_t> data;
    };

    // Simple 1-contour glyph (18 bytes -> padded to 20 bytes)
    std::vector<uint8_t> glyf;
    pushBe16(glyf, 1);    // numberOfContours
    pushBe16(glyf, 0);    // xMin
    pushBe16(glyf, 0);    // yMin
    pushBe16(glyf, 500);  // xMax
    pushBe16(glyf, 700);  // yMax
    pushBe16(glyf, 2);    // endPtsOfContours[0]
    pushBe16(glyf, 0);    // instructionLength
    // flags: 3 on-curve points with 1-byte unsigned deltas
    glyf.push_back(0x33);
    glyf.push_back(0x33);
    glyf.push_back(0x33);
    // x-coords (dx): 0, 200, 100
    glyf.push_back(0);
    glyf.push_back(200);
    glyf.push_back(100);
    // y-coords (dy): 0, 200, 100
    glyf.push_back(0);
    glyf.push_back(200);
    glyf.push_back(100);
    while (glyf.size() % 4 != 0) glyf.push_back(0);
    const uint16_t glyfEndWords = static_cast<uint16_t>(glyf.size() / 2);

    std::vector<uint8_t> loca;
    pushBe16(loca, 0);             // glyph 0 offset = 0
    pushBe16(loca, 0);             // glyph 1 offset = 0
    pushBe16(loca, glyfEndWords);  // glyph 2 offset
    pushBe16(loca, glyfEndWords);  // end

    std::vector<uint8_t> head(54, 0);
    // version = 1.0
    head[0] = 0;
    head[1] = 1;
    // magicNumber = 0x5F0F3CF5 at offset 12
    head[12] = 0x5F;
    head[13] = 0x0F;
    head[14] = 0x3C;
    head[15] = 0xF5;
    // flags = 3, unitsPerEm = 1000 at offset 18
    head[17] = 3;
    head[18] = static_cast<uint8_t>((1000U >> 8U) & 0xFFU);
    head[19] = static_cast<uint8_t>(1000U & 0xFFU);
    // xMax=500, yMax=700
    head[40] = 0x01;
    head[41] = 0xF4;
    head[42] = 0x02;
    head[43] = 0xBC;
    // indexToLocFormat = 0 (offset 50)

    std::vector<uint8_t> hhea(36, 0);
    hhea[1] = 1;  // version 1.0
    hhea[4] = 0x03;
    hhea[5] = 0x20;  // ascender = 800
    hhea[6] = 0xFF;
    hhea[7] = 0x38;  // descender = -200
    hhea[34] = 0;
    hhea[35] = 3;  // numberOfHMetrics = 3

    std::vector<uint8_t> maxp(32, 0);
    maxp[1] = 1;  // version 1.0
    maxp[4] = 0;
    maxp[5] = 3;  // numGlyphs = 3
    maxp[6] = 0;
    maxp[7] = 3;  // maxPoints = 3
    maxp[8] = 0;
    maxp[9] = 1;  // maxContours = 1

    std::vector<uint8_t> hmtx;
    pushBe16(hmtx, 500);
    pushBe16(hmtx, 0);
    pushBe16(hmtx, 600);
    pushBe16(hmtx, 0);
    pushBe16(hmtx, 250);
    pushBe16(hmtx, 0);

    std::vector<uint8_t> cmap;
    // Subtable format 4 for space (0x0020 -> GID 2) and 'A' (0x0041 -> GID 1)
    std::vector<uint8_t> fmt4;
    pushBe16(fmt4, 4);   // format
    pushBe16(fmt4, 38);  // length
    pushBe16(fmt4, 0);   // language
    pushBe16(fmt4, 6);   // segCountX2 (3 segs: 0x20, 0x41, 0xFFFF)
    pushBe16(fmt4, 4);   // searchRange
    pushBe16(fmt4, 1);   // entrySelector
    pushBe16(fmt4, 2);   // rangeShift
    // endCode
    pushBe16(fmt4, 0x0020);
    pushBe16(fmt4, 0x0041);
    pushBe16(fmt4, 0xFFFF);
    pushBe16(fmt4, 0);  // reservedPad
    // startCode
    pushBe16(fmt4, 0x0020);
    pushBe16(fmt4, 0x0041);
    pushBe16(fmt4, 0xFFFF);
    // idDelta (signed mod 65536): 0x20 + delta = 2 -> -30; 0x41 + delta = 1 -> -64; 0xFFFF + 1 = 0
    pushBe16(fmt4, static_cast<uint16_t>(-30));
    pushBe16(fmt4, static_cast<uint16_t>(-64));
    pushBe16(fmt4, 1);
    // idRangeOffset
    pushBe16(fmt4, 0);
    pushBe16(fmt4, 0);
    pushBe16(fmt4, 0);

    // cmap header: 1 subtable (3,1) or (1,0)
    pushBe16(cmap, 0);
    pushBe16(cmap, 1);
    pushBe16(cmap, includeUnicodeCmap ? 3 : 1);
    pushBe16(cmap, includeUnicodeCmap ? 1 : 0);
    pushBe32(cmap, 12);
    cmap.insert(cmap.end(), fmt4.begin(), fmt4.end());

    std::vector<uint8_t> nameTbl(6, 0);
    std::vector<uint8_t> postTbl(32, 0);
    postTbl[1] = 3;  // format 3.0

    std::vector<Table> tables = {
        {"cmap", std::move(cmap)}, {"glyf", std::move(glyf)},    {"head", std::move(head)},
        {"hhea", std::move(hhea)}, {"hmtx", std::move(hmtx)},    {"loca", std::move(loca)},
        {"maxp", std::move(maxp)}, {"name", std::move(nameTbl)}, {"post", std::move(postTbl)},
    };

    const uint16_t numTables = static_cast<uint16_t>(tables.size());
    std::vector<uint8_t> out;
    pushBe32(out, 0x00010000U);
    pushBe16(out, numTables);
    pushBe16(out, 128);
    pushBe16(out, 3);
    pushBe16(out, 16);

    const size_t dirStart = out.size();
    out.resize(dirStart + static_cast<size_t>(numTables) * 16, 0);

    for (size_t i = 0; i < numTables; ++i) {
        while (out.size() % 4 != 0) out.push_back(0);
        const uint32_t offset = static_cast<uint32_t>(out.size());
        const uint32_t len = static_cast<uint32_t>(tables[i].data.size());
        out.insert(out.end(), tables[i].data.begin(), tables[i].data.end());

        const size_t rec = dirStart + i * 16;
        std::memcpy(out.data() + rec, tables[i].tag, 4);
        out[rec + 8] = static_cast<uint8_t>((offset >> 24U) & 0xFFU);
        out[rec + 9] = static_cast<uint8_t>((offset >> 16U) & 0xFFU);
        out[rec + 10] = static_cast<uint8_t>((offset >> 8U) & 0xFFU);
        out[rec + 11] = static_cast<uint8_t>(offset & 0xFFU);
        out[rec + 12] = static_cast<uint8_t>((len >> 24U) & 0xFFU);
        out[rec + 13] = static_cast<uint8_t>((len >> 16U) & 0xFFU);
        out[rec + 14] = static_cast<uint8_t>((len >> 8U) & 0xFFU);
        out[rec + 15] = static_cast<uint8_t>(len & 0xFFU);
    }
    return out;
}

std::vector<uint8_t> buildMinimalCmykIcc() {
    std::vector<uint8_t> icc(148, 0);
    // Profile size = 148
    icc[3] = 148;
    // Data colour space = "CMYK" at offset 16
    std::memcpy(icc.data() + 16, "CMYK", 4);
    // PCS = "Lab " at offset 20
    std::memcpy(icc.data() + 20, "Lab ", 4);
    // Signature = "acsp" at offset 36
    std::memcpy(icc.data() + 36, "acsp", 4);
    // Tag count = 1 at offset 128
    icc[131] = 1;
    // Tag 0: signature "desc", offset = 144, size = 4
    std::memcpy(icc.data() + 132, "desc", 4);
    icc[139] = 144;
    icc[143] = 4;
    return icc;
}

std::vector<uint8_t> assemblePdf(const std::vector<std::string>& bodies, int rootObjNum) {
    std::string pdf = "%PDF-1.4\n";
    std::vector<size_t> offsets(bodies.size() + 1, 0);
    for (size_t i = 0; i < bodies.size(); ++i) {
        offsets[i + 1] = pdf.size();
        pdf += std::to_string(i + 1) + " 0 obj\n" + bodies[i] + "\nendobj\n";
    }
    const size_t xrefOff = pdf.size();
    pdf += "xref\n0 " + std::to_string(bodies.size() + 1) + "\n0000000000 65535 f \n";
    char line[64];
    for (size_t i = 1; i <= bodies.size(); ++i) {
        std::snprintf(line, sizeof(line), "%010zu 00000 n \n", offsets[i]);
        pdf += line;
    }
    pdf += "trailer\n<< /Size " + std::to_string(bodies.size() + 1) + " /Root " +
           std::to_string(rootObjNum) + " 0 R >>\nstartxref\n" + std::to_string(xrefOff) +
           "\n%%EOF\n";
    return std::vector<uint8_t>(pdf.begin(), pdf.end());
}

std::string makeStreamObj(const std::string& dictExtra, const std::string& payload) {
    std::string s = "<< /Length " + std::to_string(payload.size());
    if (!dictExtra.empty()) s += " " + dictExtra;
    s += " >>\nstream\n" + payload + "\nendstream";
    return s;
}

std::string bytesToString(const std::vector<uint8_t>& v) {
    return std::string(reinterpret_cast<const char*>(v.data()), v.size());
}

std::string uncompressedPdfFromOutput(const uint8_t* data, int64_t len) {
    uint8_t* out = nullptr;
    int64_t outLen = 0;
    const int32_t rc = jpdfium_qpdf_optimize(data, len, &out, &outLen, 0, -1, 0, 0, 1);
    std::string res;
    if ((rc == 0 || rc == 3) && out && outLen > 0) {
        res.assign(reinterpret_cast<const char*>(out), static_cast<size_t>(outLen));
    }
    if (out) jpdfium_free_buffer(out);
    return res;
}

void testSimpleTrueTypeUafPaths() {
    // Path 1 & 3: Symbolic TrueType font without (3,0) cmap + mismatched /Widths ->
    // triggers sfntEnsureCmap30 and sfntPatchHmtx on FontFile2 while FT_Face is open.
    const std::vector<uint8_t> ttf = buildMinimalTrueTypeFont(false);
    const std::string ttfStr = bytesToString(ttf);
    const std::string ffDict = "/Length1 " + std::to_string(ttf.size());

    std::vector<std::string> objs;
    objs.push_back("<< /Type /Catalog /Pages 2 0 R >>");
    objs.push_back("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    objs.push_back(
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        "/Resources << /Font << /F1 4 0 R >> >> /Contents 7 0 R >>");
    objs.push_back(
        "<< /Type /Font /Subtype /TrueType /BaseFont /TestSym "
        "/FirstChar 65 /LastChar 65 /Widths [777] /FontDescriptor 5 0 R >>");
    objs.push_back(
        "<< /Type /FontDescriptor /FontName /TestSym /Flags 4 "
        "/FontBBox [0 -200 500 700] /ItalicAngle 0 /Ascent 800 /Descent -200 "
        "/CapHeight 700 /StemV 80 /FontFile2 6 0 R >>");
    objs.push_back(makeStreamObj(ffDict, ttfStr));
    objs.push_back(makeStreamObj("", "BT /F1 12 Tf 72 720 Td (A) Tj ET"));

    const std::vector<uint8_t> pdf1 = assemblePdf(objs, 1);
    uint8_t* out = nullptr;
    int64_t outLen = 0;
    char* rep = nullptr;
    const uint32_t flags = (1U << 1) | (1U << 2) | (1U << 3) | (1U << 4) | (1U << 5);
    const int32_t rc =
        jpdfium_qpdf_rewrite_fonts(pdf1.data(), static_cast<int64_t>(pdf1.size()), flags, nullptr,
                                   nullptr, 0, nullptr, 0, &out, &outLen, &rep);
    CHECK(rc == 0 && out && outLen > 0, "symbolic TrueType cmap30 + hmtx rewrite succeeds");
    if (out) jpdfium_free_buffer(out);
    if (rep) jpdfium_free_string(rep);

    // Path 2: Non-symbolic TrueType font with non-AGL /Differences (/uni0041) ->
    // triggers sf.serialize() conversion to symbolic (3,0) in reconcileSimpleFontWidths.
    const std::vector<uint8_t> ttfUni = buildMinimalTrueTypeFont(true);
    objs[3] =
        "<< /Type /Font /Subtype /TrueType /BaseFont /TestNonSym "
        "/FirstChar 65 /LastChar 65 /Widths [600] "
        "/Encoding << /Type /Encoding /BaseEncoding /WinAnsiEncoding /Differences [65 /uni0041] >> "
        "/FontDescriptor 5 0 R >>";
    objs[4] =
        "<< /Type /FontDescriptor /FontName /TestNonSym /Flags 32 "
        "/FontBBox [0 -200 500 700] /ItalicAngle 0 /Ascent 800 /Descent -200 "
        "/CapHeight 700 /StemV 80 /FontFile2 6 0 R >>";
    objs[5] = makeStreamObj("/Length1 " + std::to_string(ttfUni.size()), bytesToString(ttfUni));
    const std::vector<uint8_t> pdf2 = assemblePdf(objs, 1);

    out = nullptr;
    outLen = 0;
    rep = nullptr;
    const int32_t rc2 =
        jpdfium_qpdf_rewrite_fonts(pdf2.data(), static_cast<int64_t>(pdf2.size()), flags, nullptr,
                                   nullptr, 0, nullptr, 0, &out, &outLen, &rep);
    CHECK(rc2 == 0 && out && outLen > 0, "non-symbolic TrueType /Differences rewrite succeeds");
    if (out) jpdfium_free_buffer(out);
    if (rep) jpdfium_free_string(rep);
}

void testCidFontUafPaths() {
    // Path 4 & 5: CIDFontType2 using CID 0 (<0000><0001>) + mismatched /W [1 [777]] ->
    // triggers sfntAppendBlankGlyph and sfntPatchHmtx in rewriteType0Font.
    const std::vector<uint8_t> ttfUni = buildMinimalTrueTypeFont(true);
    std::vector<std::string> objs;
    objs.push_back("<< /Type /Catalog /Pages 2 0 R >>");
    objs.push_back("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    objs.push_back(
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        "/Resources << /Font << /F1 4 0 R >> >> /Contents 8 0 R >>");
    objs.push_back(
        "<< /Type /Font /Subtype /Type0 /BaseFont /TestCID /Encoding /Identity-H "
        "/DescendantFonts [5 0 R] >>");
    objs.push_back(
        "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /TestCID "
        "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
        "/DW 1000 /W [1 [777]] /CIDToGIDMap /Identity /FontDescriptor 6 0 R >>");
    objs.push_back(
        "<< /Type /FontDescriptor /FontName /TestCID /Flags 4 "
        "/FontBBox [0 -200 500 700] /ItalicAngle 0 /Ascent 800 /Descent -200 "
        "/CapHeight 700 /StemV 80 /FontFile2 7 0 R >>");
    objs.push_back(
        makeStreamObj("/Length1 " + std::to_string(ttfUni.size()), bytesToString(ttfUni)));
    objs.push_back(makeStreamObj("", "BT /F1 12 Tf 72 720 Td <00000001> Tj ET"));

    const std::vector<uint8_t> pdf = assemblePdf(objs, 1);
    uint8_t* out = nullptr;
    int64_t outLen = 0;
    char* rep = nullptr;
    const uint32_t flags = (1U << 1) | (1U << 2) | (1U << 3) | (1U << 4) | (1U << 5);
    const int32_t rc =
        jpdfium_qpdf_rewrite_fonts(pdf.data(), static_cast<int64_t>(pdf.size()), flags, nullptr,
                                   nullptr, 0, nullptr, 0, &out, &outLen, &rep);
    CHECK(rc == 0 && out && outLen > 0, "CIDFontType2 blank glyph + hmtx rewrite succeeds");
    if (out) jpdfium_free_buffer(out);
    if (rep) jpdfium_free_string(rep);
}

void testToUnicodeCmapPreservesRangesAndSanitizesPua() {
    const std::vector<uint8_t> ttfUni = buildMinimalTrueTypeFont(true);
    const std::string tuBody =
        "/CIDInit /ProcSet findresource begin\n"
        "12 dict begin\n"
        "begincmap\n"
        "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
        "/CMapName /TestToUni def\n"
        "/CMapType 2 def\n"
        "1 begincodespacerange\n"
        "<0000> <FFFF>\n"
        "endcodespacerange\n"
        "5 beginbfchar\n"
        "<0041> <E000>\n"
        "<0042> < E000 >\n"
        "<0043> <DB80DC00>\n"
        "<0044> <D800>\n"
        "<0045> <D800DC00>\n"
        "endbfchar\n"
        "1 beginbfrange\n"
        "<0000> <FFFF> <0020>\n"
        "endbfrange\n"
        "endcmap\n"
        "CMapName currentdict /CMap defineresource pop\nend\nend\n";

    std::vector<std::string> objs;
    objs.push_back("<< /Type /Catalog /Pages 2 0 R >>");
    objs.push_back("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    objs.push_back(
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        "/Resources << /Font << /F1 4 0 R >> >> /Contents 7 0 R >>");
    objs.push_back(
        "<< /Type /Font /Subtype /TrueType /BaseFont /TestUni /Encoding /WinAnsiEncoding "
        "/FirstChar 65 /LastChar 65 /Widths [600] /ToUnicode 8 0 R /FontDescriptor 5 0 R >>");
    objs.push_back(
        "<< /Type /FontDescriptor /FontName /TestUni /Flags 32 "
        "/FontBBox [0 -200 500 700] /ItalicAngle 0 /Ascent 800 /Descent -200 "
        "/CapHeight 700 /StemV 80 /FontFile2 6 0 R >>");
    objs.push_back(
        makeStreamObj("/Length1 " + std::to_string(ttfUni.size()), bytesToString(ttfUni)));
    objs.push_back(makeStreamObj("", "BT /F1 12 Tf 72 720 Td (A) Tj ET"));
    objs.push_back(makeStreamObj("", tuBody));

    const std::vector<uint8_t> pdf = assemblePdf(objs, 1);
    uint8_t* out = nullptr;
    int64_t outLen = 0;
    char* rep = nullptr;
    // PDF/A-2u: kFlagConvertPdfA2b (1<<5) | kFlagPdfALevelU (1<<9) | clean metadata
    const uint32_t flags = (1U << 4) | (1U << 5) | (1U << 9);
    const int32_t rc =
        jpdfium_qpdf_rewrite_fonts(pdf.data(), static_cast<int64_t>(pdf.size()), flags, nullptr,
                                   nullptr, 0, nullptr, 0, &out, &outLen, &rep);
    CHECK(rc == 0 && out && outLen > 0, "PDF/A-2u ToUnicode rewrite succeeds");
    if (out && outLen > 0) {
        const std::string plain = uncompressedPdfFromOutput(out, outLen);
        CHECK(plain.find("<0000> <FFFF>") != std::string::npos,
              "ToUnicode begincodespacerange/bfrange <0000> <FFFF> preserved");
        CHECK(plain.find("<0041> <0020>") != std::string::npos,
              "ToUnicode PUA <E000> destination replaced with <0020>");
        CHECK(plain.find("<0041> <E000>") == std::string::npos,
              "ToUnicode PUA <E000> no longer present");
        CHECK(plain.find("<0042> <0020>") != std::string::npos,
              "ToUnicode whitespace-padded PUA < E000 > replaced with <0020>");
        CHECK(plain.find("< E000 >") == std::string::npos,
              "ToUnicode whitespace-padded PUA < E000 > no longer present");
        CHECK(plain.find("<0043> <0020>") != std::string::npos,
              "ToUnicode supplementary PUA <DB80DC00> replaced with <0020>");
        CHECK(plain.find("<DB80DC00>") == std::string::npos,
              "ToUnicode supplementary PUA <DB80DC00> no longer present");
        CHECK(plain.find("<0044> <0020>") != std::string::npos,
              "ToUnicode lone surrogate <D800> replaced with <0020>");
        CHECK(plain.find("<0044> <D800>") == std::string::npos,
              "ToUnicode lone surrogate <D800> no longer present");
        CHECK(plain.find("<0045> <D800DC00>") != std::string::npos,
              "ToUnicode valid non-PUA supplementary <D800DC00> preserved");
    }
    if (out) jpdfium_free_buffer(out);
    if (rep) jpdfium_free_string(rep);
}

void testPdfA3bPreservesAfAndAttachments() {
    const std::string payload = "HELLO_ATTACHMENT_DATA_12345";
    std::vector<std::string> objs;
    objs.push_back(
        "<< /Type /Catalog /Pages 2 0 R /Names << /EmbeddedFiles << /Names [(test.txt) 4 0 R] >> "
        ">> /AF [4 0 R] >>");
    objs.push_back("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    objs.push_back("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>");
    objs.push_back(
        "<< /Type /Filespec /F (test.txt) /UF (test.txt) /AFRelationship /Data "
        "/EF << /F 5 0 R >> >>");
    objs.push_back(makeStreamObj("/Type /EmbeddedFile /Subtype /text#2Fplain", payload));

    const std::vector<uint8_t> pdf = assemblePdf(objs, 1);
    uint8_t* out = nullptr;
    int64_t outLen = 0;
    char* rep = nullptr;
    // PDF/A-3b: kFlagConvertPdfA3b (1<<8) | kFlagCleanMetadata (1<<4)
    const uint32_t flags = (1U << 4) | (1U << 8);
    const int32_t rc =
        jpdfium_qpdf_rewrite_fonts(pdf.data(), static_cast<int64_t>(pdf.size()), flags, nullptr,
                                   nullptr, 0, nullptr, 0, &out, &outLen, &rep);
    CHECK(rc == 0 && out && outLen > 0, "PDF/A-3b attachment rewrite succeeds");
    if (out && outLen > 0) {
        const std::string plain = uncompressedPdfFromOutput(out, outLen);
        CHECK(plain.find("/AF ") != std::string::npos || plain.find("/AF[") != std::string::npos,
              "PDF/A-3b preserves /Catalog /AF");
        CHECK(plain.find("/EmbeddedFiles") != std::string::npos,
              "PDF/A-3b preserves /Names /EmbeddedFiles");
        CHECK(plain.find(payload) != std::string::npos,
              "PDF/A-3b preserves attachment payload bytes");
    }
    if (out) jpdfium_free_buffer(out);
    if (rep) jpdfium_free_string(rep);
}

void testCyclicGraphsDifferencesOverflowAndXmp() {
    // Cyclic /Pages /Parent, cyclic /Resources /XObject /Form, cyclic /OCProperties /Order,
    // cyclic /Outlines, cyclic /AcroForm /Fields, /Differences 2147483647 + empty name,
    // and malformed XMP CreateDate injection attempt.
    const std::string evilXmp =
        "<?xpacket begin=\"\xEF\xBB\xBF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
        "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
        "<rdf:Description rdf:about=\"\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\">"
        "<xmp:CreateDate>2024-01-01T&amp;lt;/xmp:CreateDate&amp;gt;</xmp:CreateDate>"
        "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";

    std::vector<std::string> objs;
    // 1: Catalog
    objs.push_back(
        "<< /Type /Catalog /Pages 2 0 R /Metadata 9 0 R "
        "/OCProperties << /OCGs [10 0 R] /D << /Name (Def) /Order 6 0 R >> >> "
        "/Outlines 7 0 R /AcroForm << /Fields [8 0 R] >> >>");
    // 2: Pages (parent cycles to 3? Wait: put cycle via self-parent on Pages node 2)
    objs.push_back("<< /Type /Pages /Parent 2 0 R /Kids [3 0 R] /Count 1 >>");
    // 3: Page
    objs.push_back(
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        "/Resources << /Font << /F1 4 0 R >> /XObject << /Fm 5 0 R >> >> >>");
    // 4: Font with overflow /Differences
    objs.push_back(
        "<< /Type /Font /Subtype /TrueType /BaseFont /BadDiff "
        "/Encoding << /Type /Encoding /Differences [2147483647 /A / -5 /space] >> >>");
    // 5: Cyclic Form XObject referencing itself in /Resources /XObject
    objs.push_back(
        makeStreamObj("/Type /XObject /Subtype /Form /BBox [0 0 10 10] "
                      "/Resources << /XObject << /Self 5 0 R >> >>",
                      "/Self Do"));
    // 6: Cyclic /Order array referencing itself
    objs.push_back("[10 0 R 6 0 R]");
    // 7: Cyclic Outline item
    objs.push_back("<< /Title (Root) /First 7 0 R /Next 7 0 R >>");
    // 8: Cyclic AcroForm Field
    objs.push_back("<< /T (Fld) /Kids [8 0 R] /Parent 8 0 R >>");
    // 9: Evil XMP stream
    objs.push_back(makeStreamObj("/Type /Metadata /Subtype /XML", evilXmp));
    // 10: OCG
    objs.push_back("<< /Type /OCG /Name (Layer1) >>");

    const std::vector<uint8_t> pdf = assemblePdf(objs, 1);

    // 1) Font rewriter + PDF/A-2b
    uint8_t* out = nullptr;
    int64_t outLen = 0;
    char* rep = nullptr;
    const uint32_t flags = (1U << 1) | (1U << 2) | (1U << 3) | (1U << 4) | (1U << 5);
    const int32_t rc =
        jpdfium_qpdf_rewrite_fonts(pdf.data(), static_cast<int64_t>(pdf.size()), flags, nullptr,
                                   nullptr, 0, nullptr, 0, &out, &outLen, &rep);
    CHECK(rc == 0 && out && outLen > 0, "cyclic graph + Differences overflow rewrite terminates");
    if (out && outLen > 0) {
        const std::string plain = uncompressedPdfFromOutput(out, outLen);
        CHECK(plain.find("&amp;lt;") == std::string::npos,
              "invalid XMP CreateDate rejected without corrupting XMP");
    }
    if (out) jpdfium_free_buffer(out);
    if (rep) jpdfium_free_string(rep);

    // 2) Sanitize with cyclic Outlines & AcroForm
    out = nullptr;
    outLen = 0;
    const int32_t rcSan = jpdfium_qpdf_sanitize(
        pdf.data(), static_cast<int64_t>(pdf.size()), &out, &outLen,
        JPDFIUM_SANITIZE_METADATA | JPDFIUM_SANITIZE_JAVASCRIPT | JPDFIUM_SANITIZE_ACROFORM);
    CHECK(rcSan == 0 && out && outLen > 0, "sanitize handles cyclic Outlines/AcroForm cleanly");
    if (out) jpdfium_free_buffer(out);

    // 3) PDF/X prepare on cyclic/inline-image/NaN content
    std::vector<std::string> pdfxObjs;
    pdfxObjs.push_back("<< /Type /Catalog /Pages 2 0 R /OpenAction 4 0 R >>");
    pdfxObjs.push_back("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    pdfxObjs.push_back("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R >>");
    // Cyclic action
    pdfxObjs.push_back("<< /S /GoTo /Next 4 0 R >>");
    // Inline image missing EI + NaN color values
    pdfxObjs.push_back(makeStreamObj("", "0 0 0 rg BI /W 2 /H 2 /BPC 8 /CS /G ID ABCD"));
    const std::vector<uint8_t> pdfx = assemblePdf(pdfxObjs, 1);

    out = nullptr;
    outLen = 0;
    char msg[256] = {};
    const int32_t rcPdfx =
        jpdfium_pdfx_prepare_bytes(pdfx.data(), static_cast<int64_t>(pdfx.size()), 4, nullptr, 0,
                                   JPDFIUM_PDFX_CONVERT_COLOURS | JPDFIUM_PDFX_CONVERT_IMAGES, 80,
                                   &out, &outLen, msg, sizeof(msg));
    CHECK(rcPdfx == 0 || rcPdfx == JPDFIUM_ERR_PDFX_NONCONFORMANT,
          "pdfx_prepare_bytes on cyclic actions + truncated BI..ID..EI completes safely");
    if (out) jpdfium_free_buffer(out);
}

void testStreamSizeCapRejectsFlateBomb() {
    // Build a 66 MiB uncompressed zero stream, compress it via jpdfium_qpdf_optimize into a small
    // FlateDecode PDF, then feed it into jpdfium_qpdf_rewrite_fonts to verify 64 MiB stream cap.
    std::string bigPayload(size_t{66} * 1024U * 1024U, ' ');
    std::vector<std::string> objs;
    objs.push_back("<< /Type /Catalog /Pages 2 0 R >>");
    objs.push_back("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    objs.push_back("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>");
    objs.push_back(makeStreamObj("", bigPayload));
    bigPayload.clear();
    bigPayload.shrink_to_fit();

    const std::vector<uint8_t> rawPdf = assemblePdf(objs, 1);
    objs.clear();
    uint8_t* comp = nullptr;
    int64_t compLen = 0;
    const int32_t orc =
        jpdfium_qpdf_optimize(rawPdf.data(), static_cast<int64_t>(rawPdf.size()), &comp, &compLen,
                              JPDFIUM_QPDF_COMPRESS_STREAMS, 6, 0, 2, 1);
    CHECK((orc == 0 || orc == 3) && comp && compLen > 0 && compLen < int64_t{1024} * 1024,
          "66 MiB zero stream compresses to small Flate bomb PDF");
    if (comp && compLen > 0) {
        uint8_t* out = nullptr;
        int64_t outLen = 0;
        char* rep = nullptr;
        const int32_t rc = jpdfium_qpdf_rewrite_fonts(comp, compLen, (1U << 2) | (1U << 5), nullptr,
                                                      nullptr, 0, nullptr, 0, &out, &outLen, &rep);
        CHECK(rc == 0, "rewrite_fonts safely caps 66 MiB Flate bomb without crash or OOM");
        if (out) jpdfium_free_buffer(out);
        if (rep) jpdfium_free_string(rep);
    }
    if (comp) jpdfium_free_buffer(comp);
}

void testPdfxAndColorFlateBombsBounded() {
    // 1) 66 MiB Flate bomb inside FunctionType 4 PS calculator in /Separation colourspace.
    std::string psPayload = "{ pop 1 0 0 ";
    psPayload.append(size_t{66} * 1024U * 1024U, ' ');
    psPayload += '}';
    std::vector<std::string> psObjs;
    psObjs.push_back("<< /Type /Catalog /Pages 2 0 R >>");
    psObjs.push_back("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    psObjs.push_back(
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        "/Resources << /ColorSpace << /CS1 [/Separation /Spot /DeviceRGB 5 0 R] >> >> "
        "/Contents 4 0 R >>");
    psObjs.push_back(makeStreamObj("", "/CS1 cs 1 scn 0 0 10 10 re f"));
    psObjs.push_back(
        makeStreamObj("/FunctionType 4 /Domain [0 1] /Range [0 1 0 1 0 1]", psPayload));
    psPayload.clear();
    psPayload.shrink_to_fit();

    const std::vector<uint8_t> rawPsPdf = assemblePdf(psObjs, 1);
    psObjs.clear();
    uint8_t* compPs = nullptr;
    int64_t compPsLen = 0;
    const int32_t orcPs =
        jpdfium_qpdf_optimize(rawPsPdf.data(), static_cast<int64_t>(rawPsPdf.size()), &compPs,
                              &compPsLen, JPDFIUM_QPDF_COMPRESS_STREAMS, 6, 0, 2, 1);
    CHECK((orcPs == 0 || orcPs == 3) && compPs && compPsLen > 0 && compPsLen < int64_t{1024} * 1024,
          "66 MiB FunctionType 4 PS stream compresses to small Flate bomb PDF");
    if (compPs && compPsLen > 0) {
        uint8_t* out = nullptr;
        int64_t outLen = 0;
        char msg[256] = {};
        const int32_t rc =
            jpdfium_pdfx_prepare_bytes(compPs, compPsLen, 4, nullptr, 0,
                                       JPDFIUM_PDFX_CONVERT_COLOURS | JPDFIUM_PDFX_CONVERT_SPECIAL,
                                       80, &out, &outLen, msg, sizeof(msg));
        CHECK((rc == 0 || rc == JPDFIUM_ERR_PDFX_NONCONFORMANT) && outLen < int64_t{1024} * 1024,
              "pdfx_prepare_bytes safely bounds 66 MiB FunctionType 4 PS Flate bomb");
        if (out) jpdfium_free_buffer(out);
    }
    if (compPs) jpdfium_free_buffer(compPs);

    // 2) 66 MiB Flate bomb inside Type 3 /CharProcs glyph stream.
    std::string t3Payload = "500 0 d0 1 0 0 rg ";
    t3Payload.append(size_t{66} * 1024U * 1024U, ' ');
    std::vector<std::string> t3Objs;
    t3Objs.push_back("<< /Type /Catalog /Pages 2 0 R >>");
    t3Objs.push_back("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    t3Objs.push_back(
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        "/Resources << /Font << /T3 4 0 R >> >> /Contents 6 0 R >>");
    t3Objs.push_back(
        "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 500 500] "
        "/FontMatrix [0.001 0 0 0.001 0 0] /CharProcs << /A 5 0 R >> "
        "/Encoding << /Type /Encoding /Differences [65 /A] >> "
        "/FirstChar 65 /LastChar 65 /Widths [500] >>");
    t3Objs.push_back(makeStreamObj("", t3Payload));
    t3Objs.push_back(makeStreamObj("", "BT /T3 12 Tf 72 720 Td (A) Tj ET"));
    t3Payload.clear();
    t3Payload.shrink_to_fit();

    const std::vector<uint8_t> rawT3Pdf = assemblePdf(t3Objs, 1);
    t3Objs.clear();
    uint8_t* compT3 = nullptr;
    int64_t compT3Len = 0;
    const int32_t orcT3 =
        jpdfium_qpdf_optimize(rawT3Pdf.data(), static_cast<int64_t>(rawT3Pdf.size()), &compT3,
                              &compT3Len, JPDFIUM_QPDF_COMPRESS_STREAMS, 6, 0, 2, 1);
    CHECK((orcT3 == 0 || orcT3 == 3) && compT3 && compT3Len > 0 && compT3Len < int64_t{1024} * 1024,
          "66 MiB Type 3 CharProcs stream compresses to small Flate bomb PDF");
    if (compT3 && compT3Len > 0) {
        uint8_t* out = nullptr;
        int64_t outLen = 0;
        char msg[256] = {};
        const int32_t rc =
            jpdfium_pdfx_prepare_bytes(compT3, compT3Len, 4, nullptr, 0,
                                       JPDFIUM_PDFX_CONVERT_COLOURS | JPDFIUM_PDFX_CONVERT_SPECIAL,
                                       80, &out, &outLen, msg, sizeof(msg));
        CHECK((rc == 0 || rc == JPDFIUM_ERR_PDFX_NONCONFORMANT) && outLen < int64_t{1024} * 1024,
              "pdfx_prepare_bytes safely bounds 66 MiB Type 3 CharProcs Flate bomb");
        if (out) jpdfium_free_buffer(out);
    }
    if (compT3) jpdfium_free_buffer(compT3);

    // 3) 10 MiB Flate bomb inside /Metadata XMP stream passed through jpdfium_color_pdfx_bytes.
    const std::vector<uint8_t> icc = buildMinimalCmykIcc();
    std::string xmpBomb = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">";
    xmpBomb.append(size_t{10} * 1024U * 1024U, ' ');
    xmpBomb += "</x:xmpmeta>";
    std::vector<std::string> xmpBombObjs;
    xmpBombObjs.push_back("<< /Type /Catalog /Pages 2 0 R /Metadata 4 0 R >>");
    xmpBombObjs.push_back("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    xmpBombObjs.push_back("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>");
    // Omit /Type /Metadata before optimization so qpdf compresses the 10 MiB payload into a Flate
    // bomb.
    xmpBombObjs.push_back(makeStreamObj("", xmpBomb));
    xmpBomb.clear();
    xmpBomb.shrink_to_fit();

    const std::vector<uint8_t> rawXmpBombPdf = assemblePdf(xmpBombObjs, 1);
    xmpBombObjs.clear();
    uint8_t* compXmp = nullptr;
    int64_t compXmpLen = 0;
    const int32_t orcXmp =
        jpdfium_qpdf_optimize(rawXmpBombPdf.data(), static_cast<int64_t>(rawXmpBombPdf.size()),
                              &compXmp, &compXmpLen, JPDFIUM_QPDF_COMPRESS_STREAMS, 6, 0, 2, 1);
    CHECK((orcXmp == 0 || orcXmp == 3) && compXmp && compXmpLen > 0 &&
              compXmpLen < int64_t{1024} * 1024,
          "10 MiB XMP stream compresses to small Flate bomb PDF");
    if (compXmp && compXmpLen > 0) {
        uint8_t* out = nullptr;
        int64_t outLen = 0;
        const int32_t rc = jpdfium_color_pdfx_bytes(compXmp, compXmpLen, icc.data(),
                                                    static_cast<int64_t>(icc.size()), "PDF/X-4",
                                                    nullptr, "CGATS TR 001", "Test", &out, &outLen);
        CHECK(rc == 0 && out && outLen > 0 && outLen < int64_t{1024} * 1024,
              "jpdfium_color_pdfx_bytes bounds 10 MiB XMP Flate bomb");
        if (out) jpdfium_free_buffer(out);
    }
    if (compXmp) jpdfium_free_buffer(compXmp);
}

void testRustResizePixelsDimensionCaps() {
    if (jpdfium_has_rust() == 0) return;
    const std::array<uint8_t, 4> px = {255, 0, 0, 255};
    uint8_t* out = nullptr;
    int64_t outLen = -1;
    const int32_t rc = jpdfium_rust_resize_pixels(px.data(), static_cast<int64_t>(px.size()), 1, 1,
                                                  4, 1 << 20, 1 << 20, &out, &outLen);
    CHECK(rc < 0 && out == nullptr && outLen == 0,
          "jpdfium_rust_resize_pixels rejects 1<<20 x 1<<20 cleanly without abort");
    if (out) jpdfium_rust_free(out);
}

void testDeepXmlAndConcurrentRewriter() {
    // Deeply nested XML in XMP helper via jpdfium_color_pdfx_bytes + 8-thread concurrency stress.
    const std::vector<uint8_t> ttfUni = buildMinimalTrueTypeFont(true);
    std::vector<std::string> objs;
    objs.push_back("<< /Type /Catalog /Pages 2 0 R >>");
    objs.push_back("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    objs.push_back(
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        "/Resources << /Font << /F1 4 0 R >> >> /Contents 7 0 R >>");
    objs.push_back(
        "<< /Type /Font /Subtype /TrueType /BaseFont /ThreadFont /Encoding /WinAnsiEncoding "
        "/FirstChar 65 /LastChar 65 /Widths [600] /FontDescriptor 5 0 R >>");
    objs.push_back(
        "<< /Type /FontDescriptor /FontName /ThreadFont /Flags 32 "
        "/FontBBox [0 -200 500 700] /ItalicAngle 0 /Ascent 800 /Descent -200 "
        "/CapHeight 700 /StemV 80 /FontFile2 6 0 R >>");
    objs.push_back(
        makeStreamObj("/Length1 " + std::to_string(ttfUni.size()), bytesToString(ttfUni)));
    objs.push_back(makeStreamObj("", "BT /F1 12 Tf 72 720 Td (A) Tj ET"));

    const std::vector<uint8_t> pdf = assemblePdf(objs, 1);

    // Deeply nested XML (>100 tags) in /Metadata routed through jpdfium_color_pdfx_bytes.
    std::string deepXmp =
        "<?xpacket begin=\"\xEF\xBB\xBF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
        "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
        "<rdf:Description rdf:about=\"\">";
    for (int d = 0; d < 200; ++d) deepXmp += "<n>";
    deepXmp += "leaf";
    for (int d = 0; d < 200; ++d) deepXmp += "</n>";
    deepXmp += "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";

    std::vector<std::string> xmpObjs;
    xmpObjs.push_back("<< /Type /Catalog /Pages 2 0 R /Metadata 4 0 R >>");
    xmpObjs.push_back("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
    xmpObjs.push_back("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>");
    xmpObjs.push_back(makeStreamObj("/Type /Metadata /Subtype /XML", deepXmp));
    const std::vector<uint8_t> xmpPdf = assemblePdf(xmpObjs, 1);
    const std::vector<uint8_t> icc = buildMinimalCmykIcc();
    uint8_t* xmpOut = nullptr;
    int64_t xmpOutLen = 0;
    const int32_t xmpRc =
        jpdfium_color_pdfx_bytes(xmpPdf.data(), static_cast<int64_t>(xmpPdf.size()), icc.data(),
                                 static_cast<int64_t>(icc.size()), "PDF/X-4", nullptr,
                                 "CGATS TR 001", "Test", &xmpOut, &xmpOutLen);
    CHECK(xmpRc == 0 && xmpOut && xmpOutLen > 0,
          "jpdfium_color_pdfx_bytes handles 200-deep nested XMP XML safely");
    if (xmpOut) jpdfium_free_buffer(xmpOut);

    std::vector<std::thread> workers;
    workers.reserve(8);
    std::array<int, 8> threadFailures{};
    for (size_t tid = 0; tid < 8; ++tid) {
        workers.emplace_back([&, tid]() {
            for (int iter = 0; iter < 3; ++iter) {
                uint8_t* out = nullptr;
                int64_t outLen = 0;
                char* rep = nullptr;
                const uint32_t flags = (1U << 1) | (1U << 2) | (1U << 3) | (1U << 4) | (1U << 5);
                const int32_t rc = jpdfium_qpdf_rewrite_fonts(
                    pdf.data(), static_cast<int64_t>(pdf.size()), flags, nullptr, nullptr, 0,
                    nullptr, 0, &out, &outLen, &rep);
                if (rc != 0 || !out || outLen <= 0) {
                    ++threadFailures[tid];
                }
                if (out) jpdfium_free_buffer(out);
                if (rep) jpdfium_free_string(rep);
            }
        });
    }
    for (auto& th : workers) th.join();
    int totalThreadFailures = 0;
    for (int f : threadFailures) totalThreadFailures += f;
    CHECK(totalThreadFailures == 0, "8-thread concurrent jpdfium_qpdf_rewrite_fonts succeeds");
}

}  // namespace

int main() {
    try {
        if (jpdfium_init() != JPDFIUM_OK) {
            std::fprintf(stderr, "hardening_regression: jpdfium_init failed\n");
            return 1;
        }
        testSimpleTrueTypeUafPaths();
        testCidFontUafPaths();
        testToUnicodeCmapPreservesRangesAndSanitizesPua();
        testPdfA3bPreservesAfAndAttachments();
        testCyclicGraphsDifferencesOverflowAndXmp();
        testStreamSizeCapRejectsFlateBomb();
        testPdfxAndColorFlateBombsBounded();
        testRustResizePixelsDimensionCaps();
        testDeepXmlAndConcurrentRewriter();
        jpdfium_destroy();
        if (g_failures > 0) {
            std::fprintf(stderr, "hardening_regression: %d failure(s)\n", g_failures);
            return 1;
        }
        std::printf("hardening_regression: all tests passed\n");
        return 0;
    } catch (...) {
        std::fprintf(stderr, "hardening_regression: unexpected exception\n");
        return 1;
    }
}
