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

    // 2) Sanitize with cyclic Outlines & AcroForm
    uint8_t* out = nullptr;
    int64_t outLen = 0;
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

void testDeepXmlInColorPdfx() {
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
}

}  // namespace

int main() {
    try {
        if (jpdfium_init() != JPDFIUM_OK) {
            std::fprintf(stderr, "hardening_regression: jpdfium_init failed\n");
            return 1;
        }
        testCyclicGraphsDifferencesOverflowAndXmp();
        testPdfxAndColorFlateBombsBounded();
        testRustResizePixelsDimensionCaps();
        testDeepXmlInColorPdfx();
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
