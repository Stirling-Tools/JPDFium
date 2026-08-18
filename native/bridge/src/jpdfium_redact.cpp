// jpdfium_redact.cpp - Redaction engine: Object Fission algorithm,
// pattern/word/region redaction, annotation-based mark-commit redaction,
// and incremental save.

#include <fpdf_annot.h>
#include <fpdf_edit.h>
#include <fpdf_flatten.h>
#include <fpdf_save.h>
#include <fpdf_text.h>
#include <fpdfview.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <limits>
#include <map>
#include <mutex>
#include <optional>
#include <set>
#include <sstream>
#include <string>
#include <type_traits>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_internal.h"

#ifdef JPDFIUM_HAS_ICU
#include <unicode/normalizer2.h>
#include <unicode/utypes.h>
#endif
#ifdef JPDFIUM_HAS_UNIBREAK
#include <graphemebreak.h>
#endif

#ifdef JPDFIUM_HAS_HARFBUZZ
#include <hb.h>
#endif

#ifdef JPDFIUM_HAS_PCRE2
// 32-bit code units: the redaction text pipeline is char32_t end-to-end
// (wchar_t is platform-dependent - 32-bit on POSIX, 16-bit on Windows - and
// was silently wrong on Windows before). PCRE2 32-bit matches char32_t
// buffers directly (PCRE2_SPTR32 = const uint32_t*).
#define PCRE2_CODE_UNIT_WIDTH 32
#include <pcre2.h>
#endif

#ifdef JPDFIUM_HAS_FREETYPE
#include <ft2build.h>
#include FT_FREETYPE_H
static FT_Library g_ft_lib = nullptr;
static std::once_flag g_ft_once;
static void ensureFreeTypeInit() {
    // FreeType's FT_Library is not safe for concurrent lazy init; guard it.
    std::call_once(g_ft_once, [] {
        if (!g_ft_lib) FT_Init_FreeType(&g_ft_lib);
    });
}
#endif

// UTF-8 -> std::u32string. Decodes DEFENSIVELY: a truncated multi-byte
// sequence at the end of the buffer (FFI input from Java strings) must never
// read past the NUL terminator; invalid sequences are dropped.
static std::u32string utf8_to_u32(const char* utf8) {
    std::u32string result;
    const auto* s = reinterpret_cast<const uint8_t*>(utf8);
    while (*s) {
        uint32_t cp;
        if (*s < 0x80) {
            cp = *s++;
        } else if ((*s & 0xE0) == 0xC0) {
            if (!s[1] || (s[1] & 0xC0) != 0x80) break;  // truncated/invalid
            cp = static_cast<uint32_t>(*s++ & 0x1F) << 6;
            cp |= *s++ & 0x3F;
        } else if ((*s & 0xF0) == 0xE0) {
            if (!s[1] || !s[2] || (s[1] & 0xC0) != 0x80 || (s[2] & 0xC0) != 0x80) break;
            cp = static_cast<uint32_t>(*s++ & 0x0F) << 12;
            cp |= static_cast<uint32_t>(*s++ & 0x3F) << 6;
            cp |= *s++ & 0x3F;
        } else if ((*s & 0xF8) == 0xF0) {
            if (!s[1] || !s[2] || !s[3] || (s[1] & 0xC0) != 0x80 || (s[2] & 0xC0) != 0x80 ||
                (s[3] & 0xC0) != 0x80)
                break;
            cp = static_cast<uint32_t>(*s++ & 0x07) << 18;
            cp |= static_cast<uint32_t>(*s++ & 0x3F) << 12;
            cp |= static_cast<uint32_t>(*s++ & 0x3F) << 6;
            cp |= *s++ & 0x3F;
        } else {
            s++;  // invalid lead byte: skip
            continue;
        }
        result += static_cast<char32_t>(cp);
    }
    return result;
}

// std::u32string -> UTF-16LE (for FPDFText_SetText on new text objects)
static std::vector<uint16_t> u32_to_utf16le(const std::u32string& us) {
    std::vector<uint16_t> result;
    for (char32_t c : us) {
        uint32_t cp = static_cast<uint32_t>(c);
        if (cp <= 0xFFFF) {
            result.push_back(static_cast<uint16_t>(cp));
        } else {
            cp -= 0x10000;
            result.push_back(static_cast<uint16_t>(0xD800 | (cp >> 10)));
            result.push_back(static_cast<uint16_t>(0xDC00 | (cp & 0x3FF)));
        }
    }
    result.push_back(0);  // null terminator
    return result;
}

// FPDFTextObj_GetText returns UTF-16LE code units in FPDF_WCHAR elements
// (FPDF_WCHAR is wchar_t). Decode to char32_t regardless of the platform's
// wchar_t width (16-bit on Windows, 32-bit elsewhere).
static std::u32string fpdfWcharBufToU32(const FPDF_WCHAR* buf, size_t n) {
    std::u32string out;
    size_t i = 0;
    while (i < n) {
        if (buf[i] == 0) break;
        uint32_t u = static_cast<uint32_t>(static_cast<std::make_unsigned_t<wchar_t>>(buf[i]));
        if (u >= 0xD800 && u <= 0xDBFF && i + 1 < n) {
            uint32_t lo =
                static_cast<uint32_t>(static_cast<std::make_unsigned_t<wchar_t>>(buf[i + 1]));
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                out += static_cast<char32_t>(0x10000 + ((u - 0xD800) << 10) + (lo - 0xDC00));
                i += 2;
                continue;
            }
        }
        out += static_cast<char32_t>(u);
        i++;
    }
    return out;
}

// Shared redaction primitives

// Check if rectangle A is FULLY contained within rectangle B
// A = [al, ab, ar, at], B = [bl, bb, br, bt] (PDF coords: y up)
static bool isFullyContained(float al, float ab, float ar, float at, float bl, float bb, float br,
                             float bt) {
    return al >= bl && ab >= bb && ar <= br && at <= bt;
}

// Check if two rectangles overlap at all
static bool rectsOverlap(float al, float ab, float ar, float at, float bl, float bb, float br,
                         float bt) {
    return !(ar < bl || al > br || at < bb || ab > bt);
}

// Compute intersection area ratio (of object) for partial-overlap decisions
static float overlapRatio(float al, float ab, float ar, float at, float bl, float bb, float br,
                          float bt) {
    float ix0 = std::max(al, bl), iy0 = std::max(ab, bb);
    float ix1 = std::min(ar, br), iy1 = std::min(at, bt);
    if (ix1 <= ix0 || iy1 <= iy0) return 0.0f;
    float intersectionArea = (ix1 - ix0) * (iy1 - iy0);
    float objArea = (ar - al) * (at - ab);
    return objArea > 0.0f ? intersectionArea / objArea : 0.0f;
}

// Build a NFKC-normalized copy of the extracted page text with an index map
// back to the original character indices. NFKC decomposes ligatures
// (U+FB01 -> "fi") and compatibility characters so that literal patterns
// match text whose extraction produces ligature codepoints. Case semantics
// are intentionally NOT touched here (the regex engine's icase flag keeps
// its existing behavior).
static std::u32string buildNormalizedText(FPDF_TEXTPAGE textPage, int count,
                                          std::vector<int>& normIdxMap) {
    std::u32string norm;
    normIdxMap.clear();
#ifdef JPDFIUM_HAS_ICU
    static thread_local std::unordered_map<uint32_t, std::u32string> cache;
    static const icu::Normalizer2* nfkc = [] {
        UErrorCode err = U_ZERO_ERROR;
        const icu::Normalizer2* n = icu::Normalizer2::getNFKCInstance(err);
        return U_FAILURE(err) ? nullptr : n;
    }();
    if (!nfkc) return norm;  // fall back to identity below

    norm.reserve(static_cast<size_t>(count) + 16);
    normIdxMap.reserve(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        unsigned int uni = FPDFText_GetUnicode(textPage, i);
        if (uni == 0) continue;
        if (uni > 0x10FFFF || (uni >= 0xD800 && uni <= 0xDFFF)) {
            uni = 0xFFFD;
        }
        auto it = cache.find(uni);
        if (it == cache.end()) {
            std::u32string in(1, static_cast<char32_t>(uni));
            icu::UnicodeString us =
                icu::UnicodeString::fromUTF32(reinterpret_cast<const UChar32*>(in.data()), 1);
            icu::UnicodeString out;
            UErrorCode err = U_ZERO_ERROR;
            nfkc->normalize(us, out, err);
            std::u32string mapped;
            if (U_SUCCESS(err) && out.length() > 0) {
                std::vector<UChar32> buf(out.length());
                int32_t n32 = 0;
                UErrorCode err2 = U_ZERO_ERROR;
                out.toUTF32(buf.data(), buf.size(), err2);
                n32 = U_SUCCESS(err2) ? out.countChar32() : 0;
                for (int32_t k = 0; k < n32; k++) mapped += static_cast<char32_t>(buf[k]);
            }
            if (mapped.empty()) mapped = in;
            it = cache.emplace(uni, std::move(mapped)).first;
        }
        for (char32_t c : it->second) {
            norm += c;
            normIdxMap.push_back(i);
        }
    }
    return norm;
#else
    norm.reserve(static_cast<size_t>(count) + 16);
    normIdxMap.reserve(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        unsigned int uni = FPDFText_GetUnicode(textPage, i);
        if (uni == 0) continue;
        if (uni > 0x10FFFF || (uni >= 0xD800 && uni <= 0xDFFF)) {
            uni = 0xFFFD;
        }
        norm += static_cast<char32_t>(uni);
        normIdxMap.push_back(i);
    }
    return norm;
#endif
}

// Codepoint fingerprint of the page's printable (non-space, non-U+FFFE)
// text after removing the redacted spans. After a redaction, re-extracting
// the page and computing the same fingerprint must yield the identical
// sequence: a fission that silently drops or mangles glyphs (e.g. a
// character the font can only render as part of a ligature) changes the
// fingerprint and is reported as an incomplete redaction instead of being
// shipped as damaged text.
static std::u32string survivingFingerprint(const std::u32string& normalizedText,
                                           const std::vector<int>& normIdxMap,
                                           const std::vector<char>& redactSet) {
    std::u32string fp;
    fp.reserve(normIdxMap.size());
    for (size_t k = 0; k < normIdxMap.size(); k++) {
        int ci = normIdxMap[k];
        if (!redactSet.empty() && redactSet[ci]) continue;
        char32_t wc = normalizedText[k];
        // Printable, excluding guaranteed non-characters (U+FFFE/U+FFFF) and
        // the private-use area (U+E000-U+F8FF): PUA codepoints have no
        // portable meaning - most fonts cannot re-emit them, so fragments
        // legitimately drop them and the fingerprint must not count them.
        if (wc > 0x20 && wc < 0xFFFE && !(wc >= 0xE000 && wc <= 0xF8FF)) fp += wc;
    }
    // Order-insensitive comparison: page rotation, mirroring and overlapping
    // objects change the EXTRACTION ORDER of surviving text between the pre
    // and post passes without changing the content. Comparing sorted
    // sequences keeps the dropped-glyph detection while tolerating reordering.
    std::sort(fp.begin(), fp.end());
    return fp;
}

// Partial image redaction: rasterize the covered portion by overwriting the
// source bitmap's pixels. Returns false if the format cannot be erased (the
// caller must then remove the WHOLE image - Doctrine: pixel-true erase or
// full removal, never cover-and-keep.
static bool eraseImagePixels(FPDF_PAGEOBJECT imageObj, const FS_MATRIX& imgMatrix, float rx,
                             float ry, float rr, float rt, uint32_t argb) {
    FPDF_BITMAP bmp = FPDFImageObj_GetBitmap(imageObj);
    if (!bmp) return false;
    int w = FPDFBitmap_GetWidth(bmp);
    int h = FPDFBitmap_GetHeight(bmp);
    int fmt = FPDFBitmap_GetFormat(bmp);
    int stride = FPDFBitmap_GetStride(bmp);
    void* buf = FPDFBitmap_GetBuffer(bmp);
    if (!buf || w <= 0 || h <= 0) {
        FPDFBitmap_Destroy(bmp);
        return false;
    }
    int bpp = 4;
    if (fmt == FPDFBitmap_Gray) {
        bpp = 1;
    } else if (fmt == FPDFBitmap_BGR) {
        bpp = 3;
    } else if (fmt == FPDFBitmap_BGRx || fmt == FPDFBitmap_BGRA) {
        bpp = 4;
    } else {
        FPDFBitmap_Destroy(bmp);
        return false;
    }
    unsigned int r = (argb >> 16) & 0xFF;
    unsigned int g = (argb >> 8) & 0xFF;
    unsigned int b = argb & 0xFF;

    // Image -> page transform (row-vector convention). Iterating image
    // pixels forward avoids inverse-matrix precision issues at edges.
    double a = imgMatrix.a, b2 = imgMatrix.b, c = imgMatrix.c, d = imgMatrix.d;
    double e = imgMatrix.e, f = imgMatrix.f;

    bool changed = false;
    // PDF image space is a unit square: the object matrix maps (x/w, y/h).
    double ux = a / w, uy = c / h, vx = b2 / w, vy = d / h;
    for (int iy = 0; iy < h; iy++) {
        for (int ix = 0; ix < w; ix++) {
            double px = ux * ix + uy * iy + e;
            double py = vx * ix + vy * iy + f;
            if (px < rx || px > rr || py < ry || py > rt) continue;
            size_t offset = static_cast<size_t>(iy) * stride + static_cast<size_t>(ix) * bpp;
            if (offset + static_cast<size_t>(bpp) > static_cast<size_t>(stride * h)) continue;
            uint8_t* p = static_cast<uint8_t*>(buf) + offset;
            if (bpp == 1) {
                p[0] = static_cast<uint8_t>((r * 299 + g * 587 + b * 114) / 1000);
            } else {
                p[0] = static_cast<uint8_t>(b);
                p[1] = static_cast<uint8_t>(g);
                p[2] = static_cast<uint8_t>(r);
                if (bpp == 4) p[3] = 255;
            }
            changed = true;
        }
    }
    if (changed) {
        FPDFImageObj_SetBitmap(nullptr, 0, imageObj, bmp);
    }
    FPDFBitmap_Destroy(bmp);
    return true;
}

// Char hit test for rectangle-based redaction (region redaction, annotation
// commit). A character is redacted when at least half of its box lies inside
// the rect; degenerate boxes (spaces, control chars) fall back to a
// center-point test. Center-point-only testing leaks glyphs that straddle a
// redaction boundary, so overlap is the primary signal.
static bool charInRect(double l, double b, double r, double t, float rl, float rb, float rr,
                       float rt) {
    if (!(r > rl && l < rr && t > rb && b < rt)) return false;  // no overlap at all
    double cw = r - l, ch = t - b;
    if (cw <= 0.01 || ch <= 0.01) {
        double cx = (l + r) / 2.0, cy = (b + t) / 2.0;
        return cx >= rl && cx <= rr && cy >= rb && cy <= rt;
    }
    double ix0 = std::max(l, static_cast<double>(rl)), iy0 = std::max(b, static_cast<double>(rb));
    double ix1 = std::min(r, static_cast<double>(rr)), iy1 = std::min(t, static_cast<double>(rt));
    return (ix1 - ix0) * (iy1 - iy0) >= 0.5 * cw * ch;
}

// Decomposes standard Unicode ligatures (U+FB00-FB06) into their ASCII
// component characters. This prevents encoding round-trip failures where
// FPDFText_GetUnicode returns a ligature codepoint that can't be reverse-
// mapped back to a charcode by the font's encoding dictionary.
static std::u32string decomposeLigatures(const std::u32string& input) {
    std::u32string result;
    result.reserve(input.size() + 8);
    for (char32_t wc : input) {
        switch (static_cast<uint32_t>(wc)) {
            case 0xFB00:
                result += U"ff";
                break;  // ff
            case 0xFB01:
                result += U"fi";
                break;  // fi
            case 0xFB02:
                result += U"fl";
                break;  // fl
            case 0xFB03:
                result += U"ffi";
                break;  // ffi
            case 0xFB04:
                result += U"ffl";
                break;    // ffl
            case 0xFB05:  // long-s t
            case 0xFB06:
                result += U"st";
                break;  // st
            default:
                result += wc;
                break;
        }
    }
    return result;
}

// Unicode -> WinAnsi charcode mapping
// WinAnsi bytes 0x80-0x9F map to Unicode codepoints that differ from their
// byte value (e.g. U+20AC -> 0x80 for €). The 0x20-0x7F and 0xA0-0xFF ranges
// are identity-mapped. Returns 0 for unmappable codepoints.
static uint32_t unicodeToWinAnsiCharcode(uint32_t unicode) {
    if (unicode >= 0x20 && unicode <= 0x7F) return unicode;
    if (unicode >= 0xA0 && unicode <= 0xFF) return unicode;
    switch (unicode) {
        case 0x20AC:
            return 0x80;  // €
        case 0x201A:
            return 0x82;  // ‚
        case 0x0192:
            return 0x83;  // ƒ
        case 0x201E:
            return 0x84;  // „
        case 0x2026:
            return 0x85;  // …
        case 0x2020:
            return 0x86;  // †
        case 0x2021:
            return 0x87;  // ‡
        case 0x02C6:
            return 0x88;  // ˆ
        case 0x2030:
            return 0x89;  // ‰
        case 0x0160:
            return 0x8A;  // Š
        case 0x2039:
            return 0x8B;  // ‹
        case 0x0152:
            return 0x8C;  // Œ
        case 0x017D:
            return 0x8E;  // Ž
        case 0x2018:
            return 0x91;  // '
        case 0x2019:
            return 0x92;  // '
        case 0x201C:
            return 0x93;  // "
        case 0x201D:
            return 0x94;  // "
        case 0x2022:
            return 0x95;  // bullet
        case 0x2013:
            return 0x96;  // -
        case 0x2014:
            return 0x97;  // -
        case 0x02DC:
            return 0x98;  // ˜
        case 0x2122:
            return 0x99;  // TM
        case 0x0161:
            return 0x9A;  // š
        case 0x203A:
            return 0x9B;  // ›
        case 0x0153:
            return 0x9C;  // œ
        case 0x017E:
            return 0x9E;  // ž
        case 0x0178:
            return 0x9F;  // Ÿ
        default:
            return 0;
    }
}

// Form XObject traversal helpers.
//
// PDFium parses nested form content with a recursion limit of 40 levels
// (kMaxFormLevel in core/fpdfapi/page/cpdf_streamcontentparser.cpp), so deeper
// nesting cannot appear in the page-object model either; mirror that limit to
// stay safe against self-referential form streams.
static constexpr int kMaxFormNesting = 40;

static constexpr FS_MATRIX kIdentityMatrix{1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f};

// Compose two matrices in PDF row-vector convention ([x y 1] * M):
// the result applies |m| first, then |t|.
//
// This matches PDFium's CFX_Matrix::operator* (core/fxcrt/fx_coordinates.h)
// and CPDF_TextPage::ProcessFormObject, which composes the child form matrix
// BEFORE the parent chain: actual_form_matrix = form_matrix() * form_matrix.
// Verified empirically against the pinned PDFium build with nested rotated
// forms (see CharPositionFidelityTest / native verification harness).
static FS_MATRIX concatMatrix(const FS_MATRIX& m, const FS_MATRIX& t) {
    return FS_MATRIX{
        m.a * t.a + m.b * t.c, m.a * t.b + m.b * t.d,       m.c * t.a + m.d * t.c,
        m.c * t.b + m.d * t.d, m.e * t.a + m.f * t.c + t.e, m.e * t.b + m.f * t.d + t.f,
    };
}

static const char* getStandard14FontName(const char* baseName) {
    if (!baseName) return nullptr;
    const char* s = baseName;
    if (s[0] == '/') s++;
    if (strncmp(s, "Helvetica-BoldOblique", 21) == 0) return "Helvetica-BoldOblique";
    if (strncmp(s, "Helvetica-Bold", 14) == 0) return "Helvetica-Bold";
    if (strncmp(s, "Helvetica-Oblique", 17) == 0) return "Helvetica-Oblique";
    if (strncmp(s, "Helvetica", 9) == 0) return "Helvetica";
    if (strncmp(s, "Times-BoldItalic", 16) == 0) return "Times-BoldItalic";
    if (strncmp(s, "Times-Bold", 10) == 0) return "Times-Bold";
    if (strncmp(s, "Times-Italic", 12) == 0) return "Times-Italic";
    if (strncmp(s, "Times-Roman", 11) == 0 || strncmp(s, "TimesNewRoman", 13) == 0 ||
        strncmp(s, "Times", 5) == 0)
        return "Times-Roman";
    if (strncmp(s, "Courier-BoldOblique", 19) == 0) return "Courier-BoldOblique";
    if (strncmp(s, "Courier-Bold", 12) == 0) return "Courier-Bold";
    if (strncmp(s, "Courier-Oblique", 15) == 0) return "Courier-Oblique";
    if (strncmp(s, "Courier", 7) == 0) return "Courier";
    if (strncmp(s, "Symbol", 6) == 0) return "Symbol";
    if (strncmp(s, "ZapfDingbats", 12) == 0) return "ZapfDingbats";
    return nullptr;
}

// The 14 built-in PDF fonts: their charcode->unicode mapping is fixed and
// reliable (CharCodeFromUnicode round-trips), so fragments emitted with
// FPDFText_SetText can be trusted even when the decoded-text round-trip is
// unavailable. For every other font the emission must be width-gated.
static bool isStandard14Font(FPDF_FONT font) {
    if (!font) return false;
    size_t len = 0;
    if (FPDFFont_GetFontData(font, nullptr, 0, &len) && len > 0) return false;  // embedded
    char name[128] = {0};
    if (FPDFFont_GetBaseFontName(font, name, sizeof name) == 0) return false;
    return getStandard14FontName(name) != nullptr;
}

// Object Fission Algorithm
// True content redaction that permanently removes targeted content from the
// content stream. For text, implements character-level "Object Fission"
// that preserves surrounding text with perfect typographical fidelity.
//
// Handles ALL PDF page object types, including objects nested in Form
// XObjects (recursively indexed with cumulative transforms):
//
//   TEXT objects (page-level or form-nested):
//   1. Map text-page character indices to their owning FPDF_PAGEOBJECT via
//      FPDFText_GetTextObject (direct char-to-object mapping; form children
//      are found via a recursive FPDFFormObj_* index with cumulative
//      form->page transforms).
//   2. For each text object that contains redacted characters:
//        - If ALL characters redacted -> destroy the entire object.
//        - If only SOME characters redacted -> "fission" the object:
//            a) Split into per-word fragments at word/redaction boundaries.
//            b) Each fragment gets absolute page-space positioning from
//               FPDFText_GetMatrix (linear part: rotation, Tz, form chain)
//               + FPDFText_GetCharOrigin (translation).
//            c) Three encoding strategies: SetText, FreeType GID, WinAnsi.
//            d) Destroy the original object.
//   Objects with no redacted chars are left untouched: GenerateContent
//   preserves TJ-array kerning of unmodified text objects (verified
//   empirically), so no pre-splitting is needed.
//
//   PATH objects (vector graphics, decorations, logos):
//   - Subpath-level granularity: decompose complex paths into subpaths
//     (each starting with a MOVETO), independently test each against
//     redaction rects, rebuild the path from surviving subpaths only.
//
//   SHADING objects (gradients, blend fills):
//   - Remove when bbox is fully contained in a redaction rect.
//
//   FORM XObjects (nested content streams):
//   - Text children with mapped chars are handled by the character-level
//     fission above; everything else is handled geometrically (remove
//     children that are inside redaction rects), with removal decisions
//     deferred to a single top-down destruction pass that never touches a
//     descendant freed together with a marked ancestor.
//   - Modified forms nested inside other forms are promoted to the page
//     level: FPDFPage_GenerateContent only regenerates streams of forms
//     reachable from the page, and there is no public API to re-insert an
//     object into a form, so a dirty nested form would otherwise keep its
//     stale stream.
//
//   IMAGE objects (raster content, photos, scanned pages):
//   - Remove when fully contained or >70% overlap with a redaction rect.
//
//   3. Paint a filled rectangle at every match bbox.
//   4. Regenerate the content stream (single FPDFPage_GenerateContent call).

struct TextMatch {
    std::vector<int> charIndices;                      // text-page char indices for matched chars
    float bboxL = 0, bboxB = 0, bboxR = 0, bboxT = 0;  // tight aggregate bbox (PDF coords)
};

// A single contiguous run of surviving (non-redacted) characters within a text
// object.  Each fragment becomes its own independent FPDF_PAGEOBJECT, pinned
// to the exact absolute page-space coordinates of its first character.
struct TextFragment {
    std::vector<uint16_t> utf16;            // UTF-16LE null-terminated text (original codepoints)
    std::vector<uint16_t> utf16Ligated;     // origin-sharing pairs recombined into U+FB00-FB06
    std::vector<uint16_t> utf16Decomposed;  // ligature-decomposed variant (empty if identical)
    FS_MATRIX matrix;    // page space: linear part from FPDFText_GetMatrix (includes
                         // rotation, Tz and the form chain), e/f from FPDFText_GetCharOrigin
    float fontSize = 0;  // font size of the first surviving char
    bool unicodeUnreliable = false;  // any char in the run has a broken ToUnicode mapping
    // Page-space bbox of the run's printable characters - used by the loose
    // width gate for emissions that cannot be round-trip verified.
    float expL = 0, expB = 0, expR = 0, expT = 0;
    bool hasExpectedBox = false;
};

// Pre-computed fission plan for a single text object (page-level or nested in
// a form XObject).
struct FissionPlan {
    FPDF_PAGEOBJECT originalObj = nullptr;
    FPDF_PAGEOBJECT parentForm = nullptr;  // nullptr when the original sits directly on the page
    FPDF_PAGEOBJECT topFormObj = nullptr;  // top-most ancestor form (page-level)

    // Z-order bookkeeping for FPDFPage_InsertObjectAtIndex:
    // page-level index (page objects) or topFormPageIndex (form children)
    int pageIndex = -1;
    int topFormPageIndex = -1;
    int ordinal = 0;

    // All surviving text fragments.
    // Each fragment is independently positioned via FPDFText_GetMatrix +
    // FPDFText_GetCharOrigin, so multi-gap redactions (e.g. two SSNs in the
    // same text run) are handled correctly.
    std::vector<TextFragment> fragments;

    // NOTE: borrowed handle - FPDFTextObj_GetFont returns an unretained
    // reference ("Unretained reference in public API",
    // fpdfsdk/fpdf_edittext.cpp:916); the original object must outlive every
    // use of |font|. Never FPDFFont_Close it.
    FPDF_FONT font = nullptr;
    FPDF_TEXT_RENDERMODE renderMode = FPDF_TEXTRENDERMODE_FILL;

    // Original text colors - copied to every new fragment
    unsigned int fillR = 0, fillG = 0, fillB = 0, fillA = 0;
    unsigned int strokeR = 0, strokeG = 0, strokeB = 0, strokeA = 0;
    bool hasStroke = false;
};

// One page-reachable object: either placed directly on the page or nested
// inside a Form XObject at some depth.
struct ObjRef {
    FPDF_PAGEOBJECT obj = nullptr;
    FPDF_PAGEOBJECT parentForm = nullptr;  // nullptr when placed directly on the page
    FPDF_PAGEOBJECT topFormObj = nullptr;  // top-most ancestor form object
    int pageIndex = -1;                    // FPDFPage_GetObject index (page-level objects only)
    int topFormPageIndex = -1;             // page-level index of the top-most ancestor form
    int ordinal = 0;                       // index within parentForm's object list (paint order)
    FS_MATRIX toPage = kIdentityMatrix;    // cumulative form-local -> page transform
    int depth = 0;
};

static int32_t objectFissionRedact(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_TEXTPAGE textPage,
                                   const std::vector<TextMatch>& matches, uint32_t argb,
                                   const std::shared_ptr<DocCore>& core,
                                   std::vector<FPDF_PAGEOBJECT>* paintedCovers = nullptr) {
    if (matches.empty()) return JPDFIUM_OK;

    unsigned int alf = (argb >> 24) & 0xFF;
    unsigned int red = (argb >> 16) & 0xFF;
    unsigned int grn = (argb >> 8) & 0xFF;
    unsigned int blu = argb & 0xFF;

    // Analysis phase (read-only - all text-page queries happen here)

    int totalChars = FPDFText_CountChars(textPage);

    // 1. Collect the set of all char indices targeted for redaction
    std::vector<char> redactSet(totalChars, 0);
    for (auto& m : matches) {
        for (int ci : m.charIndices) redactSet[ci] = 1;
    }

    // 2. Index every page-reachable object: page-level objects plus all
    //    objects nested inside Form XObjects, with parent linkage and the
    //    cumulative form-local -> page transform for each descendant.
    //
    //    INVARIANT: allObjs[i].obj == FPDFPage_GetObject(page, i) for
    //    i in [0, objCount); form descendants are appended afterwards.
    //
    //    FPDFText_GetTextObject returns the very same child pointers that
    //    FPDFFormObj_GetObject exposes (verified against the pinned PDFium
    //    build), so one pointer -> index map covers page-level text and
    //    text nested in forms alike.
    //
    //    Shared form XObjects placed twice: CPDF_StreamContentParser::AddForm
    //    constructs a NEW CPDF_Form and parses its content per Do invocation
    //    (cpdf_streamcontentparser.cpp:809-834), so each placement gets its
    //    OWN child object instances - editing placement 1's children never
    //    touches placement 2's live objects. NOTE: the underlying XObject
    //    STREAM object is still shared, so regenerating a modified
    //    placement's stream rewrites what BOTH placements render; a
    //    redaction in one placement therefore redacts all placements of the
    //    same form XObject (over-redaction, never under-redaction).
    //    Pinned by FormXObjectRedactTest.redactingBothPlacementsOfSharedFormWorksIndependently.
    int objCount = FPDFPage_CountObjects(page);

    std::vector<ObjRef> allObjs;
    allObjs.reserve(static_cast<size_t>(objCount) * 2);
    std::unordered_map<uintptr_t, int> objPtrToIndex;
    objPtrToIndex.reserve(static_cast<size_t>(objCount) * 2);

    auto indexFormChildren = [&](auto& self, FPDF_PAGEOBJECT formObj, const FS_MATRIX& formToPage,
                                 FPDF_PAGEOBJECT topFormObj, int topFormPageIndex,
                                 int depth) -> void {
        // PDFium's own parser stops form recursion at 40 levels
        // (kMaxFormLevel), so deeper content cannot exist here either.
        if (depth > kMaxFormNesting) return;
        int childCount = FPDFFormObj_CountObjects(formObj);
        if (childCount <= 0) return;
        for (int ci = 0; ci < childCount; ci++) {
            FPDF_PAGEOBJECT child = FPDFFormObj_GetObject(formObj, ci);
            if (!child) continue;
            uintptr_t key = reinterpret_cast<uintptr_t>(child);
            if (objPtrToIndex.count(key)) continue;  // defensive: index each instance once

            ObjRef ref;
            ref.obj = child;
            ref.parentForm = formObj;
            ref.topFormObj = topFormObj;
            ref.topFormPageIndex = topFormPageIndex;
            ref.ordinal = ci;
            ref.depth = depth;
            ref.toPage = formToPage;

            int childType = FPDFPageObj_GetType(child);
            if (childType == FPDF_PAGEOBJ_FORM) {
                FS_MATRIX childMatrix;
                if (FPDFPageObj_GetMatrix(child, &childMatrix)) {
                    // The child form matrix applies FIRST, then the parent
                    // chain (matches CPDF_TextPage::ProcessFormObject).
                    ref.toPage = concatMatrix(childMatrix, formToPage);
                }
            }

            int idx = static_cast<int>(allObjs.size());
            objPtrToIndex[key] = idx;
            allObjs.push_back(ref);

            if (childType == FPDF_PAGEOBJ_FORM) {
                self(self, child, ref.toPage, topFormObj, topFormPageIndex, depth + 1);
            }
        }
    };

    for (int oi = 0; oi < objCount; oi++) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, oi);
        if (!obj) continue;
        ObjRef ref;
        ref.obj = obj;
        ref.pageIndex = oi;
        ref.topFormObj = obj;
        ref.topFormPageIndex = oi;
        int idx = static_cast<int>(allObjs.size());
        objPtrToIndex[reinterpret_cast<uintptr_t>(obj)] = idx;
        allObjs.push_back(ref);

        if (FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_FORM) {
            FS_MATRIX formMatrix;
            if (FPDFPageObj_GetMatrix(obj, &formMatrix)) {
                indexFormChildren(indexFormChildren, obj, formMatrix, obj, oi, 1);
            }
        }
    }

    struct CharInfo {
        int ownerObj;          // index into allObjs (-1 = unmapped)
        bool isGenerated;      // FPDFText_IsGenerated
        unsigned int unicode;  // cached FPDFText_GetUnicode result
    };
    std::vector<CharInfo> charInfo(totalChars);

    for (int ci = 0; ci < totalChars; ci++) {
        charInfo[ci] = {-1, false, 0};
        unsigned int u = FPDFText_GetUnicode(textPage, ci);
        if (u > 0x10FFFF || (u >= 0xD800 && u <= 0xDFFF)) {
            u = 0xFFFD;
        }
        charInfo[ci].unicode = u;

        // Skip generated (synthetic) characters - they don't correspond to
        // real text objects in the content stream and should not participate
        // in fission decisions.
        if (FPDFText_IsGenerated(textPage, ci) == 1) {
            charInfo[ci].isGenerated = true;
            continue;
        }

        FPDF_PAGEOBJECT obj = FPDFText_GetTextObject(textPage, ci);
        if (obj) {
            auto pit = objPtrToIndex.find(reinterpret_cast<uintptr_t>(obj));
            if (pit != objPtrToIndex.end()) {
                charInfo[ci].ownerObj = pit->second;
            }
        }
    }

    // 3. Assign unmapped characters (typically spaces with degenerate bboxes)
    //    to their neighbor's object so they stay in the text flow.
    //    Forward pass: inherit from left neighbor.
    for (int ci = 1; ci < totalChars; ci++) {
        if (charInfo[ci].ownerObj >= 0) continue;
        if (charInfo[ci - 1].ownerObj >= 0) charInfo[ci].ownerObj = charInfo[ci - 1].ownerObj;
    }
    //    Reverse pass: handle leading unmapped chars by inheriting from right.
    for (int ci = totalChars - 2; ci >= 0; ci--) {
        if (charInfo[ci].ownerObj >= 0) continue;
        if (charInfo[ci + 1].ownerObj >= 0) charInfo[ci].ownerObj = charInfo[ci + 1].ownerObj;
    }

    // 4. Group characters by their owning object
    //    objChars[oi] = sorted list of text-page char indices belonging to that object
    std::vector<std::vector<int>> objChars(allObjs.size());
    for (int ci = 0; ci < totalChars; ci++) {
        int oi = charInfo[ci].ownerObj;
        if (oi >= 0) objChars[oi].push_back(ci);
    }

    // 5. Plan fission operations for every text object that contains redacted
    //    characters - page-level ones AND those nested in Form XObjects.
    //
    //    Objects without redacted chars are left completely untouched:
    //    FPDFPage_GenerateContent preserves TJ-array kerning for unmodified
    //    text objects (verified empirically: 0.0pt char-origin drift across
    //    regenerate/save/reload), so there is no need to pre-split them.

    // Helper: build a TextFragment from a contiguous run of char indices.
    // Returns std::nullopt if no printable fragment was produced.
    auto buildFragment = [&](const std::vector<int>& run) -> std::optional<TextFragment> {
        if (run.empty()) return std::nullopt;

        // Find first printable non-space character for positioning.
        size_t firstNonWS = 0;
        while (firstNonWS < run.size()) {
            unsigned int uni = charInfo[run[firstNonWS]].unicode;
            if (uni > 0x20 && uni != 0xA0) break;
            firstNonWS++;
        }
        if (firstNonWS >= run.size()) return std::nullopt;

        // Collect text starting from first printable char and stopping at the
        // last printable char (leading AND trailing whitespace is redundant:
        // every fragment is positioned at its first character's origin, and a
        // trailing space would only make the text extractor synthesize a
        // line break after the fragment).
        size_t lastPrintable = run.size();
        while (lastPrintable > firstNonWS) {
            unsigned int uni = charInfo[run[lastPrintable - 1]].unicode;
            if (uni > 0x20 && uni != 0xA0) break;
            lastPrintable--;
        }
        if (lastPrintable <= firstNonWS) return std::nullopt;

        std::u32string ws;
        bool unicodeUnreliable = false;
        double eMinX = std::numeric_limits<double>::max();
        double eMinY = std::numeric_limits<double>::max();
        double eMaxX = std::numeric_limits<double>::lowest();
        double eMaxY = std::numeric_limits<double>::lowest();
        for (size_t i = firstNonWS; i < lastPrintable; i++) {
            unsigned int uni = charInfo[run[i]].unicode;
            // U+FFFE/U+FFFF are guaranteed non-characters (no Unicode mapping,
            // no glyph to reproduce) - they cannot be carried into a fragment
            // by any of the three encoding strategies.
            if (uni >= 0x20 && uni < 0xFFFE) ws += static_cast<char32_t>(uni);
            // A broken ToUnicode mapping makes the extracted codepoint
            // unreliable: SetText cannot round-trip it, so the fragment
            // creation skips Strategy A for this run.
            if (FPDFText_HasUnicodeMapError(textPage, run[i]) == 1) unicodeUnreliable = true;
            double l, r, b, t;
            if (FPDFText_GetCharBox(textPage, run[i], &l, &r, &b, &t)) {
                if (l < eMinX) eMinX = l;
                if (b < eMinY) eMinY = b;
                if (r > eMaxX) eMaxX = r;
                if (t > eMaxY) eMaxY = t;
            }
        }
        if (ws.empty()) return std::nullopt;
        TextFragment outFrag;
        outFrag.expL = static_cast<float>(eMinX);
        outFrag.expB = static_cast<float>(eMinY);
        outFrag.expR = static_cast<float>(eMaxX);
        outFrag.expT = static_cast<float>(eMaxY);
        outFrag.hasExpectedBox = true;

        // Keep the original codepoints (ligatures included - they render with
        // the ligature glyph and advance) and carry a decomposed variant as
        // a fallback for fonts that only map the component characters.
        std::u32string wsDecomposed = decomposeLigatures(ws);
        outFrag.utf16 = u32_to_utf16le(ws);
        if (wsDecomposed != ws) {
            outFrag.utf16Decomposed = u32_to_utf16le(wsDecomposed);
        }

        // Characters that SHARE an origin come from one glyph (a ligature
        // whose ToUnicode spells out the components). Fonts that subset only
        // the ligature glyph cannot re-emit the components - recombine such
        // pairs into their U+FB00-FB06 ligature codepoints as a candidate.
        {
            std::vector<size_t> runIdx;
            for (size_t i = firstNonWS; i < lastPrintable; i++) runIdx.push_back(i);
            std::u32string ligated = ws;
            size_t li = 0;
            while (li + 1 < ligated.size()) {
                size_t ri = runIdx[li], ri2 = runIdx[li + 1];
                double ox1, oy1, ox2, oy2;
                if (!FPDFText_GetCharOrigin(textPage, run[ri], &ox1, &oy1) ||
                    !FPDFText_GetCharOrigin(textPage, run[ri2], &ox2, &oy2))
                    break;
                if (std::abs(ox1 - ox2) > 0.01 || std::abs(oy1 - oy2) > 0.01) {
                    li++;
                    continue;
                }
                std::u32string pair = ligated.substr(li, 2);
                char32_t lig = 0;
                size_t n = 2;
                if (pair == U"ff" || pair == U"fi" || pair == U"fl" || pair == U"st") {
                    // Check for the three-char forms (ffi/ffl) first: all
                    // three components must share the origin.
                    if ((pair == U"ff") && li + 2 < ligated.size() &&
                        (ligated[li + 2] == U'i' || ligated[li + 2] == U'l')) {
                        double ox3, oy3;
                        if (FPDFText_GetCharOrigin(textPage, run[runIdx[li + 2]], &ox3, &oy3) &&
                            std::abs(ox1 - ox3) <= 0.01 && std::abs(oy1 - oy3) <= 0.01) {
                            lig = (ligated[li + 2] == U'i') ? 0xFB03 : 0xFB04;
                            n = 3;
                        }
                    }
                    if (!lig) {
                        if (pair == U"ff")
                            lig = 0xFB00;
                        else if (pair == U"fi")
                            lig = 0xFB01;
                        else if (pair == U"fl")
                            lig = 0xFB02;
                        else if (pair == U"st")
                            lig = 0xFB06;
                    }
                }
                if (lig) {
                    ligated.replace(li, n, 1, lig);
                    runIdx.erase(runIdx.begin() + li, runIdx.begin() + li + n);
                    runIdx.insert(runIdx.begin() + li, ri);
                } else {
                    li++;
                }
            }
            if (ligated != ws) {
                outFrag.utf16Ligated = u32_to_utf16le(ligated);
            }
        }
        outFrag.unicodeUnreliable = unicodeUnreliable;

        // Position the fragment in PAGE space. The linear part (a,b,c,d) of
        // the effective char matrix already includes the text matrix, Tz
        // (horizontal scaling) and the complete form chain (verified against
        // the pinned PDFium build); its e/f hold the TEXT OBJECT position,
        // not the char origin, so the origin supplies the translation. The
        // font size travels separately (it is NOT folded into the matrix).
        int anchor = run[firstNonWS];
        FS_MATRIX m;
        double fx = 0, fy = 0;
        if (!FPDFText_GetMatrix(textPage, anchor, &m)) return std::nullopt;
        if (!FPDFText_GetCharOrigin(textPage, anchor, &fx, &fy)) return std::nullopt;
        m.e = static_cast<float>(fx);
        m.f = static_cast<float>(fy);
        outFrag.matrix = m;
        outFrag.fontSize = static_cast<float>(FPDFText_GetFontSize(textPage, anchor));
        return outFrag;
    };

    // FreeType font cache: avoid re-loading font data for every fragment.
#ifdef JPDFIUM_HAS_FREETYPE
    struct FtFontCache {
        std::unordered_map<uint32_t, uint32_t> unicodeToGid;
        // glyph id -> advance in FONT UNITS (FT_LOAD_NO_SCALE). Used by the
        // TJ-deviation detector to decide when a surviving run must be
        // emitted per-char (observed origin deltas that deviate from the
        // predicted advances mean TJ kerning / Tc / Tw - the only way to
        // keep those positions exact is one object per character).
        std::unordered_map<uint32_t, int> advances;
        short upem = 0;
        bool isCidKeyed = false;
        bool valid = false;
    };
    std::unordered_map<uintptr_t, FtFontCache> ftCache;

    auto getFtMapping = [&](FPDF_FONT font) -> const FtFontCache& {
        uintptr_t key = reinterpret_cast<uintptr_t>(font);
        auto it = ftCache.find(key);
        if (it != ftCache.end()) return it->second;

        FtFontCache& cache = ftCache[key];
        size_t buflen = 0;
        if (FPDFFont_GetFontData(font, nullptr, 0, &buflen) && buflen > 0) {
            std::vector<uint8_t> fontData(buflen);
            size_t actual = 0;
            if (FPDFFont_GetFontData(font, fontData.data(), buflen, &actual) && actual > 0) {
                ensureFreeTypeInit();
                FT_Face face;
                if (FT_New_Memory_Face(g_ft_lib, fontData.data(), static_cast<FT_Long>(actual), 0,
                                       &face) == 0) {
                    cache.isCidKeyed = FT_IS_CID_KEYED(face) != 0;
                    // Select a Unicode cmap if available
                    for (int cm = 0; cm < face->num_charmaps; cm++) {
                        if (face->charmaps[cm]->encoding == FT_ENCODING_UNICODE) {
                            FT_Set_Charmap(face, face->charmaps[cm]);
                            break;
                        }
                    }
                    cache.upem = static_cast<short>(face->units_per_EM);
                    FT_UInt gid;
                    FT_ULong charcode = FT_Get_First_Char(face, &gid);
                    while (gid != 0) {
                        cache.unicodeToGid[static_cast<uint32_t>(charcode)] = gid;
                        charcode = FT_Get_Next_Char(face, charcode, &gid);
                    }
                    for (const auto& [uni, g] : cache.unicodeToGid) {
                        if (cache.advances.count(g)) continue;
                        if (FT_Load_Glyph(face, g, FT_LOAD_NO_SCALE) == 0) {
                            cache.advances[g] = face->glyph->advance.x;
                        }
                    }
                    cache.valid = !cache.unicodeToGid.empty();
                    FT_Done_Face(face);
                }
            }
        }
        return cache;
    };
#endif

    std::vector<FissionPlan> plans;
    std::unordered_set<FPDF_PAGEOBJECT> objsToDestroy;

    for (int oi = 0; oi < static_cast<int>(objChars.size()); oi++) {
        const std::vector<int>& chars = objChars[oi];
        if (chars.empty()) continue;
        const ObjRef& ref = allObjs[oi];
        FPDF_PAGEOBJECT obj = ref.obj;
        if (FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) continue;

        // Check redaction status for this object
        bool anyRedacted = false;
        bool allRedacted = true;
        for (int ci : chars) {
            if (redactSet[ci]) {
                anyRedacted = true;
            } else {
                allRedacted = false;
            }
        }

        // Every redaction-touched font is recorded for the sanitize stage:
        // its font program is re-subset on save so the redacted glyph
        // outlines are unrecoverable (ACSC font-subset remnant class).
        // This includes fully-destroyed objects, which never create a plan.
        if (anyRedacted && core) {
            FPDF_FONT f = FPDFTextObj_GetFont(obj);
            if (f) {
                char fname[256] = {0};
                if (FPDFFont_GetBaseFontName(f, fname, sizeof fname) > 0) {
                    core->addTouchedFont(fname);
                }
            }
        }

        // Fully contained in redaction -> simple removal
        if (allRedacted) {
            objsToDestroy.insert(obj);
            continue;
        }
        if (!anyRedacted) continue;

        FissionPlan plan;
        plan.originalObj = obj;
        plan.parentForm = ref.parentForm;
        plan.topFormObj = ref.topFormObj;
        plan.pageIndex = ref.pageIndex;
        plan.topFormPageIndex = ref.topFormPageIndex;
        plan.ordinal = ref.ordinal;
        plan.font = FPDFTextObj_GetFont(obj);
        if (!plan.font) {
            continue;  // cannot fission without a font
        }
        plan.renderMode = FPDFTextObj_GetTextRenderMode(obj);

        FPDFPageObj_GetFillColor(obj, &plan.fillR, &plan.fillG, &plan.fillB, &plan.fillA);
        plan.hasStroke = FPDFPageObj_GetStrokeColor(obj, &plan.strokeR, &plan.strokeG,
                                                    &plan.strokeB, &plan.strokeA);

        // Walk chars, splitting at word boundaries and redaction boundaries.
        // Each contiguous run of non-redacted, non-space chars becomes a
        // fragment (typically one word).
        std::vector<int> currentRun;
        bool allFragsOk = true;

#ifdef JPDFIUM_HAS_FREETYPE
        // TJ-deviation detector. Per pair, the observed origin delta is
        // pulled back through the inverse of the run's linear matrix (Tm,
        // Tz, form chain) into raw text space and compared against the
        // FreeType predicted advance (font units -> points). A UNIFORM
        // deviation (Tc/Tw character/word spacing) is tolerated: the
        // multi-char fragment keeps the same gaps the original had, and
        // the existing width gate accepts the drift. NON-UNIFORM deviations
        // (TJ kerning arrays) split the run into segments so every
        // survivor keeps its exact position. Segments are multi-char
        // wherever possible (per-char objects would make the text
        // extractor synthesize spaces between objects).
        auto computeDeviations = [&](const std::vector<int>& run,
                                     std::vector<double>& devs) -> bool {
            devs.clear();
            if (run.size() < 2) return true;
            const FtFontCache& ft = getFtMapping(plan.font);
            if (!ft.valid || ft.upem <= 0) return false;
            FS_MATRIX m;
            if (!FPDFText_GetMatrix(textPage, run[0], &m)) return false;
            double det = static_cast<double>(m.a) * m.d - static_cast<double>(m.b) * m.c;
            if (std::abs(det) < 1e-9) return false;
            double ia = m.d / det, ib = -m.b / det;
            double fontSize = FPDFText_GetFontSize(textPage, run[0]);
            double scale = fontSize / ft.upem;
            for (size_t i = 1; i < run.size(); i++) {
                double ox1, oy1, ox2, oy2;
                if (!FPDFText_GetCharOrigin(textPage, run[i - 1], &ox1, &oy1) ||
                    !FPDFText_GetCharOrigin(textPage, run[i], &ox2, &oy2))
                    continue;
                double tx = ia * (ox2 - ox1) + ib * (oy2 - oy1);
                unsigned int uni = charInfo[run[i - 1]].unicode;
                auto git = ft.unicodeToGid.find(uni);
                if (git == ft.unicodeToGid.end()) continue;
                auto ait = ft.advances.find(git->second);
                if (ait == ft.advances.end()) continue;
                double predicted = ait->second * scale;
                devs.push_back(tx - predicted);
            }
            return true;
        };
#endif

        auto flushRun = [&]() {
            if (currentRun.empty()) return;
#ifdef JPDFIUM_HAS_FREETYPE
            {
                std::vector<double> devs;
                if (computeDeviations(currentRun, devs) && devs.size() >= 2) {
                    double sum = 0;
                    for (double d : devs) sum += d;
                    double mean = sum / devs.size();
                    // 0.25pt: above matrix-chain noise, below any real TJ
                    // adjustment (TJ numbers are integer /1000 em - at 12pt
                    // even -25 units = 0.3pt).
                    double tol = 0.25;
                    bool uniform = true;
                    for (double d : devs)
                        if (std::abs(d - mean) > tol) {
                            uniform = false;
                            break;
                        }
                    if (!uniform) {
                        // Split the run at the deviating pairs into segments;
                        // each segment is emitted as its own fragment
                        // (multi-char where possible).
                        std::vector<std::vector<int>> segments;
                        segments.push_back({});
                        for (size_t i = 0; i < currentRun.size(); i++) {
                            segments.back().push_back(currentRun[i]);
                            bool boundary = i < devs.size() && std::abs(devs[i] - mean) > tol;
                            if (boundary && i + 1 < currentRun.size()) segments.push_back({});
                        }
                        for (const auto& seg : segments) {
                            auto frag = buildFragment(seg);
                            if (frag.has_value()) {
                                plan.fragments.push_back(std::move(*frag));
                            } else {
                                for (int ci : seg) {
                                    unsigned int uni = charInfo[ci].unicode;
                                    if (uni > 0x20 && uni != 0xA0) allFragsOk = false;
                                }
                            }
                        }
                        currentRun.clear();
                        return;
                    }
                }
            }
#endif
            auto frag = buildFragment(currentRun);
            if (frag.has_value()) {
                plan.fragments.push_back(std::move(*frag));
            } else {
                // Runs without printable characters (real whitespace)
                // contribute nothing and are skipped; only treat failures
                // on runs that DID contain printable chars as fatal.
                for (int ci : currentRun) {
                    unsigned int uni = charInfo[ci].unicode;
                    if (uni > 0x20 && uni != 0xA0) {
                        allFragsOk = false;
                        break;
                    }
                }
            }
            currentRun.clear();
        };

        for (int ci : chars) {
            // Generated (synthetic) characters - spaces, line breaks, etc. -
            // never correspond to real content: they always act as run
            // boundaries and never become survivors.
            if (charInfo[ci].isGenerated) {
                flushRun();
                continue;
            }

            if (redactSet[ci]) {
                flushRun();
            } else {
                currentRun.push_back(ci);
            }
        }
        flushRun();

        if (!allFragsOk) {
            // Fragment construction failed (e.g. matrix queries returned
            // nothing). Keep the original intact; the painted rectangle still
            // provides visual cover.
            continue;
        }
        if (plan.fragments.empty()) {
            // No printable survivors (everything redacted, or only
            // whitespace left). The object must still be dropped - keeping
            // it would leave the redacted characters extractable.
            objsToDestroy.insert(obj);
            continue;
        }
        plans.push_back(std::move(plan));
    }

    // 6. Remove non-text page objects that overlap redaction regions.
    //    This handles image, path, shading, and form XObject content.
    //
    //    Path objects: subpath-level granularity - extract individual subpaths
    //    (delimited by MOVETO segments), check each against redaction rects,
    //    and rebuild the path with only surviving subpaths.
    //
    //    Shading objects: bbox-based removal when fully inside any redaction rect.
    //
    //    Form XObjects: recursive descent into form object content, removing
    //    child objects that are inside redaction rects. Uses FPDFFormObj_*
    //    APIs with coordinate transform from form-local to page space.
    //
    //    Image objects: overlap-based removal (>70% threshold).

    // Subpath extraction and per-subpath redaction for path objects.
    // Each subpath starts with a MOVETO segment and ends before the next MOVETO.
    struct Subpath {
        int startIdx;                  // index of first segment (MOVETO)
        int endIdx;                    // index past last segment (exclusive)
        float minX, minY, maxX, maxY;  // bounding box
    };

    auto extractSubpaths = [](FPDF_PAGEOBJECT path, int segCount) -> std::vector<Subpath> {
        std::vector<Subpath> subpaths;
        Subpath current = {0,
                           0,
                           std::numeric_limits<float>::max(),
                           std::numeric_limits<float>::max(),
                           std::numeric_limits<float>::lowest(),
                           std::numeric_limits<float>::lowest()};
        bool started = false;

        for (int s = 0; s < segCount; s++) {
            FPDF_PATHSEGMENT seg = FPDFPath_GetPathSegment(path, s);
            if (!seg) continue;

            int segType = FPDFPathSegment_GetType(seg);
            float sx, sy;
            FPDFPathSegment_GetPoint(seg, &sx, &sy);

            if (segType == FPDF_SEGMENT_MOVETO && started) {
                // Finish previous subpath
                current.endIdx = s;
                subpaths.push_back(current);
                current = {s,
                           0,
                           std::numeric_limits<float>::max(),
                           std::numeric_limits<float>::max(),
                           std::numeric_limits<float>::lowest(),
                           std::numeric_limits<float>::lowest()};
            }

            started = true;
            if (sx < current.minX) current.minX = sx;
            if (sy < current.minY) current.minY = sy;
            if (sx > current.maxX) current.maxX = sx;
            if (sy > current.maxY) current.maxY = sy;
        }

        if (started) {
            current.endIdx = segCount;
            subpaths.push_back(current);
        }
        return subpaths;
    };

    // Check if a subpath's bbox (transformed to page space) is fully inside
    // any redaction rect.
    auto isSubpathRedacted = [&](const Subpath& sp, const FS_MATRIX& objMatrix) -> bool {
        // Transform subpath bbox corners through the object's matrix
        float corners[4][2] = {
            {sp.minX, sp.minY}, {sp.maxX, sp.minY}, {sp.maxX, sp.maxY}, {sp.minX, sp.maxY}};
        float tMinX = std::numeric_limits<float>::max();
        float tMinY = std::numeric_limits<float>::max();
        float tMaxX = std::numeric_limits<float>::lowest();
        float tMaxY = std::numeric_limits<float>::lowest();
        for (auto& c : corners) {
            float tx = objMatrix.a * c[0] + objMatrix.c * c[1] + objMatrix.e;
            float ty = objMatrix.b * c[0] + objMatrix.d * c[1] + objMatrix.f;
            if (tx < tMinX) tMinX = tx;
            if (ty < tMinY) tMinY = ty;
            if (tx > tMaxX) tMaxX = tx;
            if (ty > tMaxY) tMaxY = ty;
        }

        for (auto& m : matches) {
            if (isFullyContained(tMinX, tMinY, tMaxX, tMaxY, m.bboxL, m.bboxB, m.bboxR, m.bboxT)) {
                return true;
            }
        }
        return false;
    };

    // Recursive form XObject marking: collects child objects covered by
    // redaction rects, accounting for the cumulative transform from
    // form-local to page space. Removal is DEFERRED into objsToDestroy so
    // that object lists are never mutated mid-traversal and so a unified
    // top-down destruction pass can skip subtrees freed by an ancestor.
    //
    // Text children with mapped characters are handled precisely by fission
    // (planned, destroyed, or deliberately left alone above) and are skipped
    // here; only text children the character mapping could not see fall back
    // to the geometric rule.
    auto markFormContents = [&](auto& self, FPDF_PAGEOBJECT formObj, const FS_MATRIX& parentToPage,
                                int depth) -> void {
        if (depth > kMaxFormNesting) return;
        int childCount = FPDFFormObj_CountObjects(formObj);
        if (childCount <= 0) return;

        for (int ci = childCount - 1; ci >= 0; ci--) {
            FPDF_PAGEOBJECT child = FPDFFormObj_GetObject(formObj, ci);
            if (!child) continue;

            int childType = FPDFPageObj_GetType(child);

            if (childType == FPDF_PAGEOBJ_FORM) {
                FS_MATRIX childMatrix;
                if (FPDFPageObj_GetMatrix(child, &childMatrix)) {
                    // The child form matrix applies FIRST, then the parent
                    // chain (matches CPDF_TextPage::ProcessFormObject).
                    self(self, child, concatMatrix(childMatrix, parentToPage), depth + 1);
                }
            }

            // Text children with mapped chars are fission's responsibility.
            if (childType == FPDF_PAGEOBJ_TEXT) {
                auto pit = objPtrToIndex.find(reinterpret_cast<uintptr_t>(child));
                if (pit != objPtrToIndex.end() && !objChars[pit->second].empty()) continue;
            }

            float cl, cb, cr, ct;
            if (!FPDFPageObj_GetBounds(child, &cl, &cb, &cr, &ct)) continue;

            // Transform child bounds through the parent-to-page matrix
            float corners[4][2] = {{cl, cb}, {cr, cb}, {cr, ct}, {cl, ct}};
            float tMinX = std::numeric_limits<float>::max();
            float tMinY = std::numeric_limits<float>::max();
            float tMaxX = std::numeric_limits<float>::lowest();
            float tMaxY = std::numeric_limits<float>::lowest();
            for (auto& c : corners) {
                float tx = parentToPage.a * c[0] + parentToPage.c * c[1] + parentToPage.e;
                float ty = parentToPage.b * c[0] + parentToPage.d * c[1] + parentToPage.f;
                if (tx < tMinX) tMinX = tx;
                if (ty < tMinY) tMinY = ty;
                if (tx > tMaxX) tMaxX = tx;
                if (ty > tMaxY) tMaxY = ty;
            }

            // Check overlap with any match bbox
            for (auto& m : matches) {
                if (isFullyContained(tMinX, tMinY, tMaxX, tMaxY, m.bboxL, m.bboxB, m.bboxR,
                                     m.bboxT) ||
                    overlapRatio(tMinX, tMinY, tMaxX, tMaxY, m.bboxL, m.bboxB, m.bboxR, m.bboxT) >
                        0.70f) {
                    objsToDestroy.insert(child);
                    break;
                }
            }
        }
    };

    // All page-level insertions (fission fragments, rebuilt paths) are
    // collected here and applied later in one pass, ordered by the original
    // page indices captured BEFORE any modification, so survivors keep their
    // original paint order (FPDFPage_InsertObjectAtIndex).
    struct Insertion {
        FPDF_PAGEOBJECT obj = nullptr;
        int insertIndex = 0;  // page index captured before any modification
        int ordinal = 0;      // paint-order tie-breaker (child index in parent form)
        int runIndex = 0;     // fragment order within its plan
    };
    std::vector<Insertion> insertions;

    for (int i = objCount - 1; i >= 0; --i) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (!obj) continue;
        int type = FPDFPageObj_GetType(obj);

        // Skip text objects - handled by fission above
        if (type == FPDF_PAGEOBJ_TEXT) continue;

        float ol, ob, or_, ot;
        if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) continue;

        // Quick reject: no overlap with any match bbox
        bool anyOverlap = false;
        for (auto& m : matches) {
            if (rectsOverlap(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT)) {
                anyOverlap = true;
                break;
            }
        }
        if (!anyOverlap) continue;

        if (type == FPDF_PAGEOBJ_IMAGE) {
            // Image: remove if fully contained or >70% overlap; otherwise
            // PIXEL-TRUE erase the region under the redaction rectangles so
            // the full-resolution content is not recoverable from the file.
            // If the bitmap cannot be decoded the WHOLE image is removed -
            // Doctrine: pixel-true erase or full removal, never cover-and-keep.
            bool remove = false;
            for (auto& m : matches) {
                if (isFullyContained(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) ||
                    overlapRatio(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) > 0.70f) {
                    remove = true;
                    break;
                }
            }
            if (remove) {
                objsToDestroy.insert(obj);
            } else {
                bool erased = true;
                FS_MATRIX imgMatrix;
                if (!FPDFPageObj_GetMatrix(obj, &imgMatrix)) {
                    erased = false;
                } else {
                    for (auto& m : matches) {
                        if (!rectsOverlap(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT))
                            continue;
                        if (!eraseImagePixels(obj, imgMatrix, m.bboxL, m.bboxB, m.bboxR, m.bboxT,
                                              argb)) {
                            erased = false;
                            break;
                        }
                    }
                }
                if (!erased) {
                    // Bitmap access failed: the redaction region would be a
                    // visual cover over recoverable pixels. Remove the whole
                    // image instead.
                    objsToDestroy.insert(obj);
                }
            }
        } else if (type == FPDF_PAGEOBJ_PATH) {
            // Path: subpath-level granularity.
            int segCount = FPDFPath_CountSegments(obj);
            if (segCount <= 0) continue;

            // Clipping paths: a path with draw mode none and no stroke
            // paints nothing itself; it clips. Dropping subpaths from a
            // clip path would UNHIDE content the clip was hiding - a visual
            // leak. Leave such paths intact (they carry no extractable
            // content themselves).
            int drawFillMode = 0;
            FPDF_BOOL drawStroke = 0;
            if (FPDFPath_GetDrawMode(obj, &drawFillMode, &drawStroke) &&
                drawFillMode == FPDF_FILLMODE_NONE && !drawStroke) {
                continue;
            }

            FS_MATRIX pathMatrix;
            if (!FPDFPageObj_GetMatrix(obj, &pathMatrix)) continue;

            auto subpaths = extractSubpaths(obj, segCount);
            if (subpaths.empty()) continue;

            // Check each subpath independently
            bool anyRemoved = false;
            bool allRemoved = true;

            for (auto& sp : subpaths) {
                if (isSubpathRedacted(sp, pathMatrix)) {
                    anyRemoved = true;
                } else {
                    allRemoved = false;
                }
            }

            if (allRemoved) {
                // All subpaths redacted -> remove entire path object
                objsToDestroy.insert(obj);
            } else if (anyRemoved) {
                // Partial: rebuild path with only surviving subpaths.
                // We rebuild using PDFium's path APIs: create a new path object,
                // copy surviving segments, replace the original.
                FPDF_PAGEOBJECT newPath = FPDFPageObj_CreateNewPath(0, 0);
                if (!newPath) continue;

                bool hasContent = false;
                for (auto& sp : subpaths) {
                    if (isSubpathRedacted(sp, pathMatrix)) continue;

                    int s = sp.startIdx;
                    while (s < sp.endIdx) {
                        FPDF_PATHSEGMENT seg = FPDFPath_GetPathSegment(obj, s);
                        if (!seg) {
                            s++;
                            continue;
                        }

                        int segType = FPDFPathSegment_GetType(seg);
                        // Each bezier control point is its own path segment
                        // (FPDFPathSegment_GetPoint returns the stored
                        // CFX_Path::Point::point_ - fpdf_editpath.cpp:205-214),
                        // so GetPoint on successive BEZIERTO segments yields
                        // c1, c2, end - the rebuilt curve is exact, and the
                        // subpath bboxes computed in extractSubpaths already
                        // include every control point.
                        float sx, sy;
                        FPDFPathSegment_GetPoint(seg, &sx, &sy);
                        FPDF_BOOL isClose = FPDFPathSegment_GetClose(seg);

                        if (segType == FPDF_SEGMENT_MOVETO) {
                            FPDFPath_MoveTo(newPath, sx, sy);
                            s++;
                        } else if (segType == FPDF_SEGMENT_LINETO) {
                            FPDFPath_LineTo(newPath, sx, sy);
                            if (isClose) FPDFPath_Close(newPath);
                            s++;
                        } else if (segType == FPDF_SEGMENT_BEZIERTO) {
                            if (s + 2 < sp.endIdx) {
                                float c1x = sx, c1y = sy, c2x = 0.0f, c2y = 0.0f, ex = 0.0f,
                                      ey = 0.0f;
                                FPDF_PATHSEGMENT seg2 = FPDFPath_GetPathSegment(obj, s + 1);
                                FPDF_PATHSEGMENT seg3 = FPDFPath_GetPathSegment(obj, s + 2);
                                if (seg2 && seg3) {
                                    FPDFPathSegment_GetPoint(seg2, &c2x, &c2y);
                                    FPDFPathSegment_GetPoint(seg3, &ex, &ey);
                                    FPDFPath_BezierTo(newPath, c1x, c1y, c2x, c2y, ex, ey);
                                    FPDF_BOOL close3 = FPDFPathSegment_GetClose(seg3);
                                    if (close3) FPDFPath_Close(newPath);
                                    s += 3;
                                } else {
                                    s++;
                                }
                            } else {
                                s++;
                            }
                        } else {
                            s++;
                        }
                        hasContent = true;
                    }
                }

                if (hasContent) {
                    // Copy visual properties from original
                    FPDFPageObj_SetMatrix(newPath, &pathMatrix);
                    unsigned int fr, fg, fb, fa;
                    if (FPDFPageObj_GetFillColor(obj, &fr, &fg, &fb, &fa))
                        FPDFPageObj_SetFillColor(newPath, fr, fg, fb, fa);
                    unsigned int sr, sg, sb, sa;
                    if (FPDFPageObj_GetStrokeColor(obj, &sr, &sg, &sb, &sa))
                        FPDFPageObj_SetStrokeColor(newPath, sr, sg, sb, sa);
                    float sw;
                    if (FPDFPageObj_GetStrokeWidth(obj, &sw))
                        FPDFPageObj_SetStrokeWidth(newPath, sw);

                    // Copy draw mode (fill/stroke)
                    FPDFPath_SetDrawMode(newPath, drawFillMode, drawStroke);

                    // Line join / cap / dash: fork extensions
                    // (fpdf_edit.h:1085-1201). Miter limit has no public API
                    // in the pin; it falls back to the viewer default.
                    int lineJoin = FPDFPageObj_GetLineJoin(obj);
                    if (lineJoin >= 0) FPDFPageObj_SetLineJoin(newPath, lineJoin);
                    int lineCap = FPDFPageObj_GetLineCap(obj);
                    if (lineCap >= 0) FPDFPageObj_SetLineCap(newPath, lineCap);
                    float dashPhase = 0;
                    if (FPDFPageObj_GetDashPhase(obj, &dashPhase)) {
                        int dashCount = FPDFPageObj_GetDashCount(obj);
                        if (dashCount > 0) {
                            std::vector<float> dash(dashCount);
                            if (FPDFPageObj_GetDashArray(obj, dash.data(), dash.size())) {
                                FPDFPageObj_SetDashArray(newPath, dash.data(), dash.size(),
                                                         dashPhase);
                            }
                        }
                    }

                    // Deferred insertion at the original's index keeps paint
                    // order (applied together with fission fragments below).
                    insertions.push_back({newPath, i, 0, 0});
                    objsToDestroy.insert(obj);
                } else {
                    FPDFPageObj_Destroy(newPath);
                }
            }
        } else if (type == FPDF_PAGEOBJ_SHADING) {
            // Shading: remove if fully contained in any redaction rect
            for (auto& m : matches) {
                if (isFullyContained(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT)) {
                    objsToDestroy.insert(obj);
                    break;
                }
            }
        } else if (type == FPDF_PAGEOBJ_FORM) {
            // Form XObject: check if entire form is inside a redaction rect.
            // When a form contains mapped text objects with surviving fragments,
            // do not destroy the form wholesale on a partial (>70%) overlap;
            // fission will emit the survivor fragments and markFormContents will
            // clean up non-text children.
            bool hasSurvivingText = false;
            for (const auto& plan : plans) {
                FPDF_PAGEOBJECT p = plan.originalObj;
                while (p) {
                    auto it = objPtrToIndex.find(reinterpret_cast<uintptr_t>(p));
                    if (it == objPtrToIndex.end()) break;
                    if (allObjs[it->second].parentForm == obj) {
                        hasSurvivingText = true;
                        break;
                    }
                    p = allObjs[it->second].parentForm;
                }
                if (hasSurvivingText) break;
            }

            bool formFullyInside = false;
            for (auto& m : matches) {
                if (isFullyContained(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) ||
                    (!hasSurvivingText &&
                     overlapRatio(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) > 0.70f)) {
                    formFullyInside = true;
                    break;
                }
            }

            if (formFullyInside) {
                objsToDestroy.insert(obj);
            } else {
                // Mark covered form children (deferred; text children with
                // mapped chars are handled by fission instead).
                FS_MATRIX formMatrix;
                if (FPDFPageObj_GetMatrix(obj, &formMatrix)) {
                    // The form's own matrix is the first transform toward the
                    // page.
                    markFormContents(markFormContents, obj, formMatrix, 0);
                }
            }
        }
    }

    // Modification phase

    // Helper: determine whether an object has an ancestor form marked for destruction.
    auto hasMarkedAncestor = [&](FPDF_PAGEOBJECT obj) -> bool {
        auto it = objPtrToIndex.find(reinterpret_cast<uintptr_t>(obj));
        while (it != objPtrToIndex.end()) {
            const ObjRef& ref = allObjs[it->second];
            if (!ref.parentForm) return false;
            if (objsToDestroy.count(ref.parentForm)) return true;
            it = objPtrToIndex.find(reinterpret_cast<uintptr_t>(ref.parentForm));
        }
        return false;
    };

    // Wholesale-marked ancestor suppression (Bug A2):
    // If an ancestor form was marked for destruction, erase any fission plans
    // for text within it so fragments of "surviving" text are not emitted on the page.
    std::erase_if(plans,
                  [&](const FissionPlan& plan) { return hasMarkedAncestor(plan.originalObj); });

    // 7. Apply fission: create fragment objects BEFORE removing originals.
    //
    //    Three encoding strategies are tried in order:
    //
    //    Strategy A: SetText (Unicode -> font's CharCodeFromUnicode).
    //      Works for most fonts including CID/Type0 with proper ToUnicode.
    //
    //    Strategy B: FreeType GID-based SetCharcodes (when JPDFIUM_HAS_FREETYPE).
    //      Extracts embedded font data via FPDFFont_GetFontData, loads into
    //      FreeType, uses FT_Get_Char_Index for each Unicode char to get GIDs.
    //      For CID Identity-H fonts (charcode=CID=GID), produces correct codes.
    //
    //    Strategy C: WinAnsi SetCharcodes (no external libraries needed).
    //      Maps Unicode -> WinAnsi byte codes for Standard 14 / non-embedded fonts.
    //
    //    If all strategies fail, fragment is skipped and original preserved.
    std::unordered_set<FPDF_PAGEOBJECT> fissionAttempted;

    for (auto& plan : plans) {
        fissionAttempted.insert(plan.originalObj);
        std::vector<FPDF_PAGEOBJECT> createdObjs;
        bool allOk = true;

        FPDF_FONT fragFont = plan.font;
        if (plan.parentForm) {
            char baseName[128] = {0};
            if (FPDFFont_GetBaseFontName(plan.font, baseName, sizeof(baseName)) > 0) {
                const char* stdName = getStandard14FontName(baseName);
                if (stdName) {
                    FPDF_FONT lf = FPDFText_LoadStandardFont(doc, stdName);
                    if (lf) {
                        if (core) core->loadedFonts.push_back(lf);
                        fragFont = lf;
                    }
                } else {
                    size_t fontDataLen = 0;
                    if (FPDFFont_GetFontData(plan.font, nullptr, 0, &fontDataLen) &&
                        fontDataLen > 0) {
                        std::vector<uint8_t> fontData(fontDataLen);
                        size_t actual = 0;
                        if (FPDFFont_GetFontData(plan.font, fontData.data(), fontDataLen,
                                                 &actual) &&
                            actual > 0) {
                            FPDF_FONT lf = FPDFText_LoadFont(doc, fontData.data(),
                                                             static_cast<uint32_t>(actual),
                                                             FPDF_FONT_TRUETYPE, 1);
                            if (lf) {
                                if (core) core->loadedFonts.push_back(lf);
                                fragFont = lf;
                            }
                        }
                    }
                }
            }
        }

        for (size_t fi = 0; fi < plan.fragments.size(); fi++) {
            TextFragment& frag = plan.fragments[fi];
            if (frag.utf16.size() <= 1) continue;  // skip null-only

            FPDF_PAGEOBJECT fragObj = FPDFPageObj_CreateTextObj(doc, fragFont, frag.fontSize);
            if (!fragObj) {
                allOk = false;
                break;
            }

            auto boundsValid = [](FPDF_PAGEOBJECT obj) -> bool {
                float fl, fb, fr, ft;
                if (!FPDFPageObj_GetBounds(obj, &fl, &fb, &fr, &ft)) return false;
                float w = fr - fl, h = ft - fb;
                return w >= 0.01f || h >= 0.01f;
            };

            // Round-trip the new object's DECODED text and compare it against
            // the expected string (ligature spelling normalized). This catches
            // silent glyph drops / .notdef substitutions that a bounds check
            // alone would miss - including wrong GIDs from Strategy B. The
            // text page is still valid here (all removals happen later).
            //
            // Returns: 1 = verified match, 0 = verified mismatch,
            // -1 = cannot decode (inline fonts without a usable encoding) -
            // in that case only the bounds check validates the fragment.
            auto fragmentTextStatus = [&](FPDF_PAGEOBJECT obj,
                                          const std::vector<uint16_t>& expected) -> int {
                unsigned long needBytes = FPDFTextObj_GetText(obj, textPage, nullptr, 0);
                if (needBytes <= sizeof(FPDF_WCHAR)) return -1;
                size_t numChars = needBytes / sizeof(FPDF_WCHAR);
                std::vector<FPDF_WCHAR> buf(numChars, 0);
                if (FPDFTextObj_GetText(obj, textPage, buf.data(), needBytes) != needBytes)
                    return -1;
                std::u32string gotW = fpdfWcharBufToU32(buf.data(), numChars);
                if (gotW.empty()) return -1;  // cannot decode (inline fonts w/o encoding)
                std::u32string expW;
                size_t i = 0;
                while (i < expected.size()) {
                    if (expected[i] == 0) break;
                    if (expected[i] >= 0xD800 && expected[i] <= 0xDBFF && i + 1 < expected.size()) {
                        uint16_t lo = expected[i + 1];
                        if (lo >= 0xDC00 && lo <= 0xDFFF) {
                            expW += static_cast<char32_t>(0x10000 + ((expected[i] - 0xD800) << 10) +
                                                          (lo - 0xDC00));
                            i += 2;
                            continue;
                        }
                    }
                    expW += static_cast<char32_t>(expected[i]);
                    i++;
                }
                return decomposeLigatures(gotW) == decomposeLigatures(expW) ? 1 : 0;
            };

            // The codepoints to lay out: original (ligatures intact) first,
            // decomposed variant as fallback. The round-trip expectation is
            // always the original codepoints (ligature spelling normalized).
            const std::vector<uint16_t>& expectedText = frag.utf16;

            FPDF_BOOL textOk = false;
            bool boundsOk = false;
            // 1 = emission verified by decoded-text round-trip, -1 = could not
            // be decoded (the width gate below then validates it instead).
            int emissionStatus = -1;

            // Strategy A: SetText (Unicode -> font's CharCodeFromUnicode).
            // Skipped when the source characters have broken ToUnicode
            // mappings - the extracted codepoints cannot round-trip.
            // Candidates are tried in order: ligature-recombined form first
            // (fonts that subset only ligature glyphs cannot re-emit the
            // components), then the original codepoints, then the decomposed
            // variant.
            if (!frag.unicodeUnreliable) {
                for (const auto* cand : {&frag.utf16Ligated, &frag.utf16, &frag.utf16Decomposed}) {
                    if (cand->empty()) continue;
                    textOk =
                        FPDFText_SetText(fragObj, reinterpret_cast<FPDF_WIDESTRING>(cand->data()));
                    if (!textOk) continue;
                    emissionStatus = fragmentTextStatus(fragObj, *cand);
                    if (emissionStatus != 0) break;  // 1 verified, -1 width-gated
                    textOk = false;
                }
                if (textOk) boundsOk = boundsValid(fragObj);
            }

#ifdef JPDFIUM_HAS_FREETYPE
            // Strategy B: FreeType GID-based SetCharcodes.
            // For simple (non-CID) fonts, charcodes serialize as 1 byte in the content
            // stream. Reject GIDs > 0xFF for non-CID fonts up front to prevent truncation.
            if (!textOk || !boundsOk) {
                const auto& ftInfo = getFtMapping(plan.font);
                if (ftInfo.valid) {
                    for (const auto* cand : {&frag.utf16, &frag.utf16Decomposed}) {
                        if (cand->empty()) continue;
                        std::vector<uint32_t> codes;
                        bool allMapped = true;
                        for (size_t i = 0; i + 1 < cand->size(); i++) {
                            auto git = ftInfo.unicodeToGid.find(static_cast<uint32_t>((*cand)[i]));
                            if (git != ftInfo.unicodeToGid.end() && git->second != 0) {
                                if (!ftInfo.isCidKeyed && git->second > 0xFF) {
                                    allMapped = false;
                                    break;
                                }
                                codes.push_back(git->second);
                            } else {
                                allMapped = false;
                                break;
                            }
                        }
                        if (allMapped && !codes.empty()) {
                            FPDFText_SetCharcodes(fragObj, codes.data(), codes.size());
                            emissionStatus = fragmentTextStatus(fragObj, expectedText);
                            if (emissionStatus != 0) {
                                textOk = true;
                                boundsOk = boundsValid(fragObj);
                                break;
                            }
                        }
                    }
                }
            }
#endif

            // Strategy C: WinAnsi SetCharcodes (Standard 14 / non-embedded).
            if (!textOk || !boundsOk) {
                for (const auto* cand : {&frag.utf16, &frag.utf16Decomposed}) {
                    if (cand->empty()) continue;
                    std::vector<uint32_t> codes;
                    bool allMappable = true;
                    for (size_t i = 0; i + 1 < cand->size(); i++) {
                        uint32_t code = unicodeToWinAnsiCharcode((*cand)[i]);
                        if (code != 0) {
                            codes.push_back(code);
                        } else {
                            allMappable = false;
                            break;
                        }
                    }
                    if (allMappable && !codes.empty()) {
                        FPDFText_SetCharcodes(fragObj, codes.data(), codes.size());
                        emissionStatus = fragmentTextStatus(fragObj, expectedText);
                        if (emissionStatus != 0) {
                            textOk = true;
                            boundsOk = boundsValid(fragObj);
                            break;
                        }
                    }
                }
            }

            if (!textOk || !boundsOk) {
                FPDFPageObj_Destroy(fragObj);
                allOk = false;
                break;
            }

            FPDFPageObj_SetMatrix(fragObj, &frag.matrix);

            // Loose width gate for emissions that could not be verified by
            // decoded-text round-trip and do not use a standard-14 font:
            // wrong glyphs from custom encodings change the glyph sequence
            // and therefore the page-space bbox. Standard-14 fonts are
            // trusted (fixed encoding); the tolerance is loose enough for
            // Tc/Tw spacing drift while still rejecting wholesale garbage.
            if (emissionStatus == -1 && !isStandard14Font(plan.font) && frag.hasExpectedBox) {
                float fl, fb, fr, ft;
                if (!FPDFPageObj_GetBounds(fragObj, &fl, &fb, &fr, &ft)) {
                    FPDFPageObj_Destroy(fragObj);
                    allOk = false;
                    break;
                }
                // Wrong-glyph emissions (custom encodings) almost always
                // change the glyph HEIGHTS, so the height check is tight.
                // The width check is deliberately loose: character spacing
                // (Tc) is not re-emitted by the fission, so correctly mapped
                // fragments may be narrower than the source run.
                float tolH = std::max(2.0f, (frag.expT - frag.expB) * 0.10f);
                float tolWL = std::max(2.0f, (frag.expR - frag.expL) * 0.10f);
                float tolWR = std::max(3.0f, (frag.expR - frag.expL) * 0.40f);
                if (std::abs(fl - frag.expL) > tolWL || std::abs(fr - frag.expR) > tolWR ||
                    std::abs(fb - frag.expB) > tolH || std::abs(ft - frag.expT) > tolH) {
                    FPDFPageObj_Destroy(fragObj);
                    allOk = false;
                    break;
                }
            }

            FPDFTextObj_SetTextRenderMode(fragObj, plan.renderMode);

            // Restore original text colors
            FPDFPageObj_SetFillColor(fragObj, plan.fillR, plan.fillG, plan.fillB, plan.fillA);
            if (plan.hasStroke) {
                FPDFPageObj_SetStrokeColor(fragObj, plan.strokeR, plan.strokeG, plan.strokeB,
                                           plan.strokeA);
            }

            createdObjs.push_back(fragObj);
        }

        if (allOk) {
            // All fragments created successfully -> commit later, in paint
            // order, together with any rebuilt paths. The original text
            // object is destroyed in the unified pass below; until then the
            // borrowed font handle stays valid.
            objsToDestroy.insert(plan.originalObj);

            // Resolve insertion index dynamically (Bug A1):
            // If the fragment belongs to a form child:
            // If the top form survives, insert AFTER the top form (topFormPageIndex + 1)
            // so the fragments render ON TOP of any opaque form background.
            // If the top form is marked for destruction, insert at topFormPageIndex.
            int targetInsertIndex;
            if (plan.parentForm) {
                bool topFormDestroyed = (plan.topFormObj && objsToDestroy.count(plan.topFormObj));
                targetInsertIndex =
                    topFormDestroyed ? plan.topFormPageIndex : (plan.topFormPageIndex + 1);
            } else {
                targetInsertIndex = plan.pageIndex;
            }
            for (size_t k = 0; k < createdObjs.size(); k++) {
                insertions.push_back(
                    {createdObjs[k], targetInsertIndex, plan.ordinal, static_cast<int>(k)});
            }
        } else {
            // Fission failed -> destroy created fragments, keep original.
            // The original is NOT added to objsToDestroy, and step 8 will
            // also skip it (fissionAttempted set).  The black box painted in
            // step 10 still provides visual cover.
            for (auto* fo : createdObjs) {
                FPDFPageObj_Destroy(fo);
            }
        }
    }

    // 8. Fallback: remove text objects that are >70% inside a match bbox but
    //    were NOT caught by the char-to-object mapping (e.g. chars with
    //    degenerate bounding boxes, content the text extractor skipped).
    //    Skip objects that were already handled by fission (even if fission
    //    failed - in that case the original is intentionally preserved and
    //    the black box provides visual cover).
    //
    //    Runs BEFORE the survivor insertions below: the page object list is
    //    still unmodified, so index i still addresses the original objects.
    for (int i = objCount - 1; i >= 0; --i) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (!obj) continue;
        if (objsToDestroy.count(obj)) continue;     // already marked
        if (fissionAttempted.count(obj)) continue;  // fission handled it
        int type = FPDFPageObj_GetType(obj);
        if (type != FPDF_PAGEOBJ_TEXT) continue;

        float ol, ob, or_, ot;
        if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) continue;

        for (auto& m : matches) {
            if (isFullyContained(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) ||
                overlapRatio(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) > 0.70f) {
                objsToDestroy.insert(obj);
                break;
            }
        }
    }

    // 9. Destroy all marked objects, top-down, so that a marked ancestor
    //    frees its subtree and descendants are never touched again
    //    (avoiding use-after-free / double-free when a form and its children
    //    are both marked).
    //
    //    IMPORTANT: after the first FPDFPage_RemoveObject of a TEXT object
    //    every FPDF_TEXTPAGE handle is invalid - all text-page reads happened
    //    in the analysis phase above, nothing below queries the text page.
    std::vector<FPDF_PAGEOBJECT> destroyList(objsToDestroy.begin(), objsToDestroy.end());

    // Shallowest first: page-level objects (depth 0) before form children;
    // tie-break by global index so destruction order (and thus output bytes)
    // is deterministic across runs.
    std::sort(destroyList.begin(), destroyList.end(), [&](FPDF_PAGEOBJECT a, FPDF_PAGEOBJECT b) {
        auto ia = objPtrToIndex.find(reinterpret_cast<uintptr_t>(a));
        auto ib = objPtrToIndex.find(reinterpret_cast<uintptr_t>(b));
        int da = ia != objPtrToIndex.end() ? allObjs[ia->second].depth : 0;
        int db = ib != objPtrToIndex.end() ? allObjs[ib->second].depth : 0;
        if (da != db) return da < db;
        int ga = ia != objPtrToIndex.end() ? ia->second : 0;
        int gb = ib != objPtrToIndex.end() ? ib->second : 0;
        return ga < gb;
    });

    // Pre-compute the skip set while every pointer in the parent chain is
    // still alive (ancestors are destroyed first, so the chain cannot be
    // re-walked during the destruction loop).
    std::vector<bool> skip(destroyList.size(), false);
    for (size_t i = 0; i < destroyList.size(); i++) {
        if (hasMarkedAncestor(destroyList[i])) skip[i] = true;
    }

    // 9b. Nested-form content stream propagation.
    //
    //    FPDFPage_GenerateContent only regenerates a Form XObject's stream
    //    when the form is reachable from the page: a dirty form nested
    //    inside another form keeps its stale stream (its parent is only
    //    regenerated when the parent itself is dirty, and there is no public
    //    API to dirty an ancestor or to re-insert an object into a form).
    //
    //    Fix: promote every dirty non-page-level form to the page - detach
    //    it from its parent form (which marks the parent dirty), re-base its
    //    matrix to page space and insert it at the top-level form's index.
    //    The parent's regenerated stream then no longer invokes the stale
    //    nested copy, and the promoted copy regenerates on the page.
    std::set<FPDF_PAGEOBJECT> dirtyForms;
    std::set<FPDF_PAGEOBJECT> reParentForms;
    for (FPDF_PAGEOBJECT obj : destroyList) {
        if (hasMarkedAncestor(obj)) continue;
        auto it = objPtrToIndex.find(reinterpret_cast<uintptr_t>(obj));
        if (it != objPtrToIndex.end() && allObjs[it->second].parentForm) {
            FPDF_PAGEOBJECT pf = allObjs[it->second].parentForm;
            if (!objsToDestroy.count(pf) && !hasMarkedAncestor(pf)) {
                dirtyForms.insert(pf);
            }
        }
    }
    bool progressed = true;
    while (progressed) {
        progressed = false;
        for (FPDF_PAGEOBJECT f : dirtyForms) {
            if (objsToDestroy.count(f) || hasMarkedAncestor(f) || reParentForms.count(f)) continue;
            auto it = objPtrToIndex.find(reinterpret_cast<uintptr_t>(f));
            if (it == objPtrToIndex.end()) continue;
            const ObjRef& ref = allObjs[it->second];
            if (!ref.parentForm) continue;  // page-level: regenerated by the page pass
            reParentForms.insert(f);
            if (!dirtyForms.count(ref.parentForm) && !objsToDestroy.count(ref.parentForm) &&
                !hasMarkedAncestor(ref.parentForm)) {
                dirtyForms.insert(ref.parentForm);
                progressed = true;
            }
        }
    }
    for (FPDF_PAGEOBJECT f : reParentForms) {
        if (objsToDestroy.count(f) || hasMarkedAncestor(f)) continue;
        auto it = objPtrToIndex.find(reinterpret_cast<uintptr_t>(f));
        if (it == objPtrToIndex.end()) continue;
        const ObjRef& ref = allObjs[it->second];
        // Ownership transfers to the caller; the insertion pass below hands
        // it to the page. toPage is the cumulative transform (own matrix
        // already included), so the re-based object renders identically.
        FPDFFormObj_RemoveObject(ref.parentForm, f);
        FPDFPageObj_SetMatrix(f, &ref.toPage);
        bool topFormDestroyed = (ref.topFormObj && objsToDestroy.count(ref.topFormObj));
        int targetIdx = topFormDestroyed ? ref.topFormPageIndex : (ref.topFormPageIndex + 1);
        insertions.push_back({f, targetIdx, ref.ordinal, -1});
    }

    // 9c. Insert every survivor (fragments, rebuilt paths and promoted
    //     forms) at its original position. Processing in descending
    //     (insertIndex, ordinal, runIndex) order means an insertion at a
    //     lower index can never shift the position of an earlier
    //     (higher-index) insertion, and within one index the last-inserted
    //     object paints first - which restores the original order once the
    //     marked originals are removed.
    std::stable_sort(insertions.begin(), insertions.end(),
                     [](const Insertion& a, const Insertion& b) {
                         if (a.insertIndex != b.insertIndex) return a.insertIndex > b.insertIndex;
                         if (a.ordinal != b.ordinal) return a.ordinal > b.ordinal;
                         return a.runIndex > b.runIndex;
                     });
    for (auto& ins : insertions) {
        // FPDFPage_InsertObjectAtIndex takes ownership of the object across
        // the C API even when it returns false: the object is wrapped in a
        // unique_ptr BEFORE the page validity check, so a failure destroys it
        // (fpdfsdk/fpdf_editpage.cpp:315-316 "Take ownership back from the
        // embedder across the C API"). Nothing left to clean up here.
        FPDFPage_InsertObjectAtIndex(page, ins.obj, ins.insertIndex);
    }

    for (size_t i = 0; i < destroyList.size(); i++) {
        FPDF_PAGEOBJECT obj = destroyList[i];
        if (skip[i]) continue;  // freed together with its marked ancestor

        FPDF_PAGEOBJECT parentForm = nullptr;
        auto it = objPtrToIndex.find(reinterpret_cast<uintptr_t>(obj));
        if (it != objPtrToIndex.end()) parentForm = allObjs[it->second].parentForm;

        if (parentForm) {
            // Nested object in a Form XObject: detach from its parent form.
            // FPDFFormObj_RemoveObject transfers ownership of the removed
            // child to the caller (fpdfsdk/fpdf_editpage.cpp:1217) and marks
            // the form object dirty (fpdf_editpage.cpp:1215), which makes
            // FPDFPage_GenerateContent regenerate the form stream
            // (CPDF_PageContentGenerator::ProcessForm regenerates holders
            // with dirty streams - cpdf_pagecontentgenerator.cpp:963-970).
            FPDFFormObj_RemoveObject(parentForm, obj);
        } else {
            // Page-level object.
            FPDFPage_RemoveObject(page, obj);
        }
        FPDFPageObj_Destroy(obj);
    }

    // 10. Paint cover rectangles for all match regions (if alpha > 0)
    if (alf > 0) {
        for (auto& m : matches) {
            FPDF_PAGEOBJECT rect =
                FPDFPageObj_CreateNewRect(m.bboxL, m.bboxB, m.bboxR - m.bboxL, m.bboxT - m.bboxB);
            if (!rect) continue;
            FPDFPageObj_SetFillColor(rect, red, grn, blu, alf);
            FPDFPath_SetDrawMode(rect, FPDF_FILLMODE_ALTERNATE, 0);
            FPDFPage_InsertObject(page, rect);
            if (paintedCovers) paintedCovers->push_back(rect);
        }
    }

    // 11. Commit to content stream (single call for all modifications)
    if (!FPDFPage_GenerateContent(page)) return JPDFIUM_ERR_NATIVE;

    // Any content REMOVAL means an incremental save would keep the original,
    // un-redacted revision recoverable in the file body - record that so the
    // save APIs can refuse.
    if ((!objsToDestroy.empty() || !reParentForms.empty()) && core) {
        core->contentRedacted = true;
    }
    return JPDFIUM_OK;
}

// PCRE2 matching layer (replaces std::wregex entirely).
//
// ReDoS hardening: match_limit caps backtracking work (default 1M), depth_limit
// caps recursion depth (default 1000), and JIT matching enforces the limits
// via the match context. PCRE2_UTF|PCRE2_UCP make \w/\d/\b Unicode-correct
// (fixes e.g. "Müller" whole-word false-positives).
#ifdef JPDFIUM_HAS_PCRE2
struct Pcre2Pattern {
    pcre2_code* code = nullptr;
    pcre2_match_data* md = nullptr;
    pcre2_match_context* mctx = nullptr;
    pcre2_jit_stack* jst = nullptr;

    Pcre2Pattern() = default;
    Pcre2Pattern(const Pcre2Pattern&) = delete;
    Pcre2Pattern& operator=(const Pcre2Pattern&) = delete;
    Pcre2Pattern(Pcre2Pattern&& o) noexcept : code(o.code), md(o.md), mctx(o.mctx), jst(o.jst) {
        o.code = nullptr;
        o.md = nullptr;
        o.mctx = nullptr;
        o.jst = nullptr;
    }
    ~Pcre2Pattern() {
        if (jst) pcre2_jit_stack_free(jst);
        if (mctx) pcre2_match_context_free(mctx);
        if (md) pcre2_match_data_free(md);
        if (code) pcre2_code_free(code);
    }
    bool valid() const {
        return code && md && mctx;
    }
};

static constexpr uint32_t kPcre2MatchLimit = 1'000'000u;
static constexpr uint32_t kPcre2DepthLimit = 1'000u;

// Compile a u32 pattern. caseless==true adds PCRE2_CASELESS. JIT-compiles the
// complete match. Returns false (and fills err) on compile failure.
static bool compilePcre2(const std::u32string& pattern, bool caseless, Pcre2Pattern& out,
                         std::string& err) {
    int errcode = 0;
    PCRE2_SIZE erroffset = 0;
    uint32_t flags = PCRE2_UTF | PCRE2_UCP;
    if (caseless) flags |= PCRE2_CASELESS;
    pcre2_code* code = pcre2_compile(reinterpret_cast<PCRE2_SPTR>(pattern.data()), pattern.size(),
                                     flags, &errcode, &erroffset, nullptr);
    if (!code) {
        PCRE2_UCHAR msg[256];
        pcre2_get_error_message(errcode, msg, sizeof(msg) / sizeof(msg[0]));
        err.assign(reinterpret_cast<char*>(msg));
        return false;
    }
    if (pcre2_jit_compile(code, PCRE2_JIT_COMPLETE) < 0) {
        // JIT failure is not fatal: the interpreter fallback still respects
        // the match limits, just slower.
        err = "JIT unavailable";
    }
    pcre2_match_data* md = pcre2_match_data_create_from_pattern(code, nullptr);
    if (!md) {
        pcre2_code_free(code);
        return false;
    }
    pcre2_match_context* mctx = pcre2_match_context_create(nullptr);
    if (!mctx) {
        pcre2_match_data_free(md);
        pcre2_code_free(code);
        return false;
    }
    pcre2_set_match_limit(mctx, kPcre2MatchLimit);
    pcre2_set_depth_limit(mctx, kPcre2DepthLimit);
    // JIT stack 32KB initial / 512KB max; freed by the Pcre2Pattern destructor.
    pcre2_jit_stack* jst = pcre2_jit_stack_create(32 * 1024, 512 * 1024, nullptr);
    if (jst) pcre2_jit_stack_assign(mctx, nullptr, jst);
    out.code = code;
    out.md = md;
    out.mctx = mctx;
    out.jst = jst;
    return true;
}
#endif

// Rebuild a TextMatch bbox from its char indices (used after grapheme or
// cluster extension so the painted cover encloses everything removed).
static bool recomputeMatchBbox(FPDF_TEXTPAGE textPage, TextMatch& m) {
    double xmin = std::numeric_limits<double>::max();
    double ymin = std::numeric_limits<double>::max();
    double xmax = std::numeric_limits<double>::lowest();
    double ymax = std::numeric_limits<double>::lowest();
    bool any = false;
    for (int ci : m.charIndices) {
        double l, r, b, t;
        if (!FPDFText_GetCharBox(textPage, ci, &l, &r, &b, &t)) continue;
        any = true;
        if (l < xmin) xmin = l;
        if (b < ymin) ymin = b;
        if (r > xmax) xmax = r;
        if (t > ymax) ymax = t;
    }
    if (!any) return false;
    m.bboxL = static_cast<float>(xmin);
    m.bboxB = static_cast<float>(ymin);
    m.bboxR = static_cast<float>(xmax);
    m.bboxT = static_cast<float>(ymax);
    return true;
}

// Append the chars covered by a PCRE2 match [start,start+len) of the search
// buffer into a TextMatch, computing the tight bbox.
static void appendMatchChars(FPDF_TEXTPAGE textPage, const std::vector<int>& idxMap, int start,
                             int len, float padding, std::vector<TextMatch>& out) {
    TextMatch tm;
    double xmin = std::numeric_limits<double>::max();
    double ymin = std::numeric_limits<double>::max();
    double xmax = std::numeric_limits<double>::lowest();
    double ymax = std::numeric_limits<double>::lowest();
    bool anyBox = false;
    for (int k = start; k < start + len && k < static_cast<int>(idxMap.size()); ++k) {
        int ci = idxMap[k];
        tm.charIndices.push_back(ci);
        double l, r, b, t;
        if (!FPDFText_GetCharBox(textPage, ci, &l, &r, &b, &t)) continue;  // skip unmapped
        anyBox = true;
        if (l < xmin) xmin = l;
        if (b < ymin) ymin = b;
        if (r > xmax) xmax = r;
        if (t > ymax) ymax = t;
    }
    if (!anyBox) return;
    xmin -= padding;
    ymin -= padding;
    xmax += padding;
    ymax += padding;
    tm.bboxL = static_cast<float>(xmin);
    tm.bboxB = static_cast<float>(ymin);
    tm.bboxR = static_cast<float>(xmax);
    tm.bboxT = static_cast<float>(ymax);
    out.push_back(std::move(tm));
}

#ifdef JPDFIUM_HAS_PCRE2
// Run a compiled PCRE2 pattern over the search buffer -> TextMatch vector.
// Non-overlapping matches (same iteration model as wsregex_iterator).
static void collectPcre2Matches(FPDF_TEXTPAGE textPage, const std::u32string& text,
                                const std::vector<int>& idxMap, const Pcre2Pattern& pc,
                                float padding, std::vector<TextMatch>& out) {
    size_t offset = 0;
    while (offset <= text.size()) {
        int rc = pcre2_match(pc.code, reinterpret_cast<PCRE2_SPTR>(text.data()), text.size(),
                             offset, 0, pc.md, pc.mctx);
        if (rc == PCRE2_ERROR_NOMATCH) break;
        if (rc < 0) break;  // error (limit exceeded): report what matched so far
        PCRE2_SIZE* ov = pcre2_get_ovector_pointer(pc.md);
        PCRE2_SIZE start = ov[0];
        PCRE2_SIZE end = ov[1];
        if (end <= start) {
            // Zero-length match: advance one code unit (regex_iterator model).
            offset = start + 1;
            continue;
        }
        appendMatchChars(textPage, idxMap, static_cast<int>(start), static_cast<int>(end - start),
                         padding, out);
        offset = end;
    }
}

// Escape regex metacharacters for literal matching.
static std::u32string escapeLiteral(const std::u32string& raw) {
    std::u32string out;
    for (char32_t ch : raw) {
        if (ch == U'\\' || ch == U'^' || ch == U'$' || ch == U'.' || ch == U'|' || ch == U'?' ||
            ch == U'*' || ch == U'+' || ch == U'(' || ch == U')' || ch == U'[' || ch == U']' ||
            ch == U'{' || ch == U'}') {
            out += U'\\';
        }
        out += ch;
    }
    return out;
}

// Build ONE combined alternation for a literal word list, using capturing
// groups so the matched alternative is recoverable from the ovector.
// groupToWord maps group numbers (1-based) to word indices. Returns false if
// no literal could be embedded.
// Decimal digits of |v| as u32 chars (std::to_wstring is wchar_t-based and
// platform-width dependent).
static std::u32string u32Digits(uint32_t v) {
    if (v == 0) return U"0";
    std::u32string out;
    while (v > 0) {
        out.insert(out.begin(), static_cast<char32_t>(U'0' + (v % 10)));
        v /= 10;
    }
    return out;
}

static bool buildLiteralAlternation(const char** words, int32_t wordCount, bool wholeWord,
                                    std::u32string& pattern, std::vector<int>& groupToWord) {
    std::u32string body;
    groupToWord.clear();
    for (int32_t wi = 0; wi < wordCount; wi++) {
        if (!words[wi]) continue;
        std::u32string esc = escapeLiteral(utf8_to_u32(words[wi]));
        if (esc.empty()) continue;
        body += U"(?<w";
        body += u32Digits(static_cast<uint32_t>(wi));
        body += U">";
        body += esc;
        body += U")|";
        groupToWord.push_back(wi);
    }
    if (groupToWord.empty()) return false;
    body.pop_back();  // trailing '|'
    pattern.clear();
    if (wholeWord) pattern += U"\\b";
    pattern += U"(?:";
    pattern += body;
    pattern += U")";
    if (wholeWord) pattern += U"\\b";
    return true;
}
#endif

// Snap each match span to grapheme-cluster boundaries: a redaction boundary
// that splits a grapheme (base + combining mark, emoji ZWJ sequences) leaves
// dangling marks in the surviving fragments. Expanding to the full cluster is
// the secure default. The match bbox is recomputed so the painted cover
// encloses everything removed.
static void alignMatchesToGraphemes(FPDF_TEXTPAGE textPage, const std::vector<uint32_t>& unicodeSeq,
                                    std::vector<TextMatch>& matches) {
#ifdef JPDFIUM_HAS_UNIBREAK
    if (unicodeSeq.empty() || matches.empty()) return;
    std::vector<char> brks(unicodeSeq.size(), 0);
    set_graphemebreaks_utf32(reinterpret_cast<const utf32_t*>(unicodeSeq.data()), unicodeSeq.size(),
                             "", brks.data());
    // brks[i] = whether a break exists BEFORE char i.
    for (auto& m : matches) {
        int first = -1, last = -1;
        for (int ci : m.charIndices) {
            if (first < 0 || ci < first) first = ci;
            if (ci > last) last = ci;
        }
        if (first < 0) continue;
        // libunibreak fills brks[i] with the status of the boundary AFTER
        // character i (same convention as its line/word breakers).
        while (first > 0 && brks[first - 1] == GRAPHEMEBREAK_NOBREAK) first--;
        while (last + 1 < static_cast<int>(unicodeSeq.size()) &&
               brks[last] == GRAPHEMEBREAK_NOBREAK)
            last++;
        // Rebuild the (possibly extended) char index list.
        m.charIndices.clear();
        for (int ci = first; ci <= last; ci++) m.charIndices.push_back(ci);
        recomputeMatchBbox(textPage, m);
    }
#else
    (void)textPage;
    (void)unicodeSeq;
    (void)matches;
#endif
}

#ifdef JPDFIUM_HAS_HARFBUZZ
// Snap redaction spans to SHAPED cluster boundaries. Grapheme alignment
// (libunibreak) protects base+combining-mark sequences; HarfBuzz goes
// further and protects LIGATURE clusters: a cut inside a shaped cluster
// (e.g. the 'fi' ligature's two characters forming ONE glyph) leaves the
// surviving part unrenderable. The span is extended to the whole cluster -
// the secure default. Fonts without embedded data are skipped (the
// grapheme layer already ran).
static void alignMatchesToShapedClusters(FPDF_TEXTPAGE tp, std::vector<TextMatch>& matches) {
    if (matches.empty()) return;
    int totalChars = FPDFText_CountChars(tp);

    struct ShapeRun {
        FPDF_PAGEOBJECT obj = nullptr;
        std::vector<int> chars;      // text-page char indices, in order
        std::vector<int> clusterOf;  // position -> first position of cluster
    };
    std::unordered_map<FPDF_PAGEOBJECT, ShapeRun> runs;
    std::vector<FPDF_PAGEOBJECT> ordered;

    // Shape font cache: FPDF_FONT pointer -> hb_font (owns its face).
    struct HbFont {
        hb_font_t* font = nullptr;
        hb_face_t* face = nullptr;
    };
    std::unordered_map<uintptr_t, HbFont> hbCache;

    auto shapeFont = [&](FPDF_PAGEOBJECT textObj) -> hb_font_t* {
        FPDF_FONT f = FPDFTextObj_GetFont(textObj);
        if (!f) return nullptr;
        auto it = hbCache.find(reinterpret_cast<uintptr_t>(f));
        if (it != hbCache.end()) return it->second.font;
        size_t buflen = 0;
        if (!FPDFFont_GetFontData(f, nullptr, 0, &buflen) || buflen == 0) {
            hbCache[reinterpret_cast<uintptr_t>(f)] = {nullptr, nullptr};
            return nullptr;
        }
        std::vector<uint8_t> fontData(buflen);
        size_t actual = 0;
        if (!FPDFFont_GetFontData(f, fontData.data(), buflen, &actual) || actual == 0) {
            hbCache[reinterpret_cast<uintptr_t>(f)] = {nullptr, nullptr};
            return nullptr;
        }
        hb_blob_t* blob = hb_blob_create(reinterpret_cast<const char*>(fontData.data()),
                                         static_cast<unsigned>(actual), HB_MEMORY_MODE_READONLY,
                                         nullptr, nullptr);
        if (!blob) return nullptr;
        hb_face_t* face = hb_face_create(blob, 0);
        hb_blob_destroy(blob);  // face holds its own reference
        if (!face) return nullptr;
        hb_font_t* hf = hb_font_create(face);
        hbCache[reinterpret_cast<uintptr_t>(f)] = HbFont{hf, face};
        return hf;
    };

    // Which objects do the matches touch?
    std::unordered_set<FPDF_PAGEOBJECT> touched;
    for (const auto& m : matches)
        for (int ci : m.charIndices) {
            FPDF_PAGEOBJECT o = FPDFText_GetTextObject(tp, ci);
            if (o) touched.insert(o);
        }

    for (int ci = 0; ci < totalChars; ci++) {
        FPDF_PAGEOBJECT o = FPDFText_GetTextObject(tp, ci);
        if (!o || !touched.count(o)) continue;
        auto& run = runs[o];
        if (run.obj == nullptr) {
            run.obj = o;
            ordered.push_back(o);
        }
        run.chars.push_back(ci);
    }

    for (FPDF_PAGEOBJECT o : ordered) {
        ShapeRun& run = runs[o];
        hb_font_t* hf = shapeFont(o);
        if (!hf || run.chars.empty()) continue;

        hb_buffer_t* buf = hb_buffer_create();
        hb_buffer_set_direction(buf, HB_DIRECTION_LTR);
        hb_buffer_set_cluster_level(buf, HB_BUFFER_CLUSTER_LEVEL_MONOTONE_GRAPHEMES);
        std::vector<uint32_t> cps;
        cps.reserve(run.chars.size());
        for (int ci : run.chars) cps.push_back(FPDFText_GetUnicode(tp, ci));
        hb_buffer_add_utf32(buf, cps.data(), static_cast<int>(cps.size()), 0,
                            static_cast<int>(cps.size()));
        hb_shape(hf, buf, nullptr, 0);

        unsigned nGlyphs = 0;
        hb_glyph_info_t* infos = hb_buffer_get_glyph_infos(buf, &nGlyphs);
        // For each buffer position, the start position of its cluster.
        std::vector<int> clusterStart(cps.size(), 0);
        for (int p = 0; p < static_cast<int>(cps.size()); p++) clusterStart[p] = p;
        for (unsigned g = 0; g < nGlyphs; g++) {
            unsigned cluster = infos[g].cluster;  // first codepoint of the cluster
            unsigned next =
                (g + 1 < nGlyphs) ? infos[g + 1].cluster : static_cast<unsigned>(cps.size());
            if (cluster >= cps.size()) continue;
            for (unsigned p = cluster; p < next && p < cps.size(); p++)
                clusterStart[p] = static_cast<int>(cluster);
        }
        run.clusterOf = std::move(clusterStart);
        hb_buffer_destroy(buf);
    }

    // Extend every match span to full clusters within each object.
    for (auto& m : matches) {
        std::unordered_map<FPDF_PAGEOBJECT, int> firstPos, lastPos;
        for (int ci : m.charIndices) {
            FPDF_PAGEOBJECT o = FPDFText_GetTextObject(tp, ci);
            auto it = runs.find(o);
            if (it == runs.end() || it->second.clusterOf.empty()) continue;
            auto pit = std::lower_bound(it->second.chars.begin(), it->second.chars.end(), ci);
            if (pit == it->second.chars.end() || *pit != ci) continue;
            int pos = static_cast<int>(pit - it->second.chars.begin());
            if (!firstPos.count(o) || pos < firstPos[o]) firstPos[o] = pos;
            if (!lastPos.count(o) || pos > lastPos[o]) lastPos[o] = pos;
        }
        bool extended = false;
        for (auto& [o, fp] : firstPos) {
            const ShapeRun& run = runs[o];
            int lp = lastPos[o];
            int cs = run.clusterOf[fp];
            int ce = lp;
            for (int p = lp + 1; p < static_cast<int>(run.clusterOf.size()) &&
                                 run.clusterOf[p] == run.clusterOf[lp];
                 p++)
                ce = p;
            if (cs == fp && ce == lp) continue;
            for (int p = cs; p <= ce; p++) m.charIndices.push_back(run.chars[p]);
            extended = true;
        }
        if (extended) {
            std::sort(m.charIndices.begin(), m.charIndices.end());
            m.charIndices.erase(std::unique(m.charIndices.begin(), m.charIndices.end()),
                                m.charIndices.end());
            recomputeMatchBbox(tp, m);
        }
    }

    for (auto& [k, e] : hbCache) {
        if (e.font) hb_font_destroy(e.font);
        if (e.face) hb_face_destroy(e.face);
    }
}
#endif

// Lifecycle

// Audit helper shared by region and commit redaction. A silent
// "keep original + painted cover" survivor is the banned Pattern-1 failure,
// so any of the following is a loud JPDFIUM_ERR_REDACT_INCOMPLETE:
//   - any content-bearing object FULLY inside a redaction region (covers
//     geometric-only failures a text audit cannot see, e.g. a path whose
//     subpath rebuild failed or an image whose pixel erase was impossible);
//   - any TEXT object with >50% of its bbox inside a region: its content is
//     only verified through character extraction, so a text object that
//     stays in the region without the extraction seeing it is unverified
//     (step 8 removes >70% ones; this closes the 50-70% silent band).
// Objects created by the redaction itself (painted covers) are excluded by
// identity.
static bool auditNoSurvivorsInRegion(FPDF_PAGE page, const std::vector<FS_RECTF>& regions,
                                     const std::vector<FPDF_PAGEOBJECT>& exclude) {
    std::unordered_set<FPDF_PAGEOBJECT> excluded(exclude.begin(), exclude.end());
    int objCount = FPDFPage_CountObjects(page);
    for (int i = 0; i < objCount; i++) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (!obj || excluded.count(obj)) continue;
        int type = FPDFPageObj_GetType(obj);
        if (type != FPDF_PAGEOBJ_TEXT && type != FPDF_PAGEOBJ_IMAGE && type != FPDF_PAGEOBJ_PATH &&
            type != FPDF_PAGEOBJ_SHADING && type != FPDF_PAGEOBJ_FORM)
            continue;

        // Clipping paths (draw mode none, no stroke) paint nothing and carry
        // no extractable content: fission deliberately leaves them intact
        // (removing a clip subpath would UNHIDE clipped content), so they are
        // exempt from the survivor audit.
        if (type == FPDF_PAGEOBJ_PATH) {
            int fillMode = 0;
            FPDF_BOOL stroke = 0;
            if (FPDFPath_GetDrawMode(obj, &fillMode, &stroke) && fillMode == FPDF_FILLMODE_NONE &&
                !stroke) {
                continue;
            }
        }

        float ol, ob, or_, ot;
        if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) continue;
        for (auto& r : regions) {
            if (isFullyContained(ol, ob, or_, ot, r.left, r.bottom, r.right, r.top)) {
                return false;
            }
            if (type == FPDF_PAGEOBJ_TEXT &&
                overlapRatio(ol, ob, or_, ot, r.left, r.bottom, r.right, r.top) > 0.50f) {
                return false;
            }
        }
    }
    return true;
}

// Redaction

int32_t jpdfium_redact_region(int64_t page, float x, float y, float w, float h, uint32_t argb,
                              int32_t remove_content) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(w) || !std::isfinite(h) ||
        w <= 0.0f || h <= 0.0f) {
        return JPDFIUM_ERR_INVALID;
    }
    if (remove_content == 0) {
        // Doctrine: visual-only "cover" redaction is banned in the public
        // API. Content must be verified-gone, never painted over.
        return JPDFIUM_ERR_INVALID;
    }

    try {
        // Char-level fission is the ONLY region redaction path. When the
        // text page cannot be built there is no way to verify what survived:
        // a geometric bbox fallback for text is the banned degrade path, so
        // this is a loud unverifiable error instead.
        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;

        int charCount = FPDFText_CountChars(tp);

        // One match per region: its bbox IS the region rect (so the
        // painted cover matches the requested area exactly), plus
        // every character whose glyph box overlaps it significantly.
        TextMatch tm;
        tm.bboxL = x;
        tm.bboxB = y;
        tm.bboxR = x + w;
        tm.bboxT = y + h;
        for (int ci = 0; ci < charCount; ++ci) {
            double l, r, b, t;
            if (!FPDFText_GetCharBox(tp, ci, &l, &r, &b, &t)) continue;
            if (charInRect(l, b, r, t, x, y, x + w, y + h)) {
                tm.charIndices.push_back(ci);
            }
        }
        std::vector<TextMatch> matches;
        matches.push_back(std::move(tm));

        std::vector<FPDF_PAGEOBJECT> paintedCovers;
        int32_t rc =
            objectFissionRedact(pw->doc, pw->page, tp, matches, argb, pw->core, &paintedCovers);
        FPDFText_ClosePage(tp);
        if (rc != JPDFIUM_OK) return rc;

        // Audit 1 (text): reload the text page and confirm no glyph box
        // still intersects the redaction region. An audit that cannot even
        // run is a loud unverifiable error - never a silent pass.
        FPDF_TEXTPAGE audit = FPDFText_LoadPage(pw->page);
        if (!audit) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;
        bool remaining = false;
        int n = FPDFText_CountChars(audit);
        for (int ci = 0; ci < n; ++ci) {
            double l, r, b, t;
            if (!FPDFText_GetCharBox(audit, ci, &l, &r, &b, &t)) continue;
            if (charInRect(l, b, r, t, x, y, x + w, y + h)) {
                remaining = true;
                break;
            }
        }
        FPDFText_ClosePage(audit);
        if (remaining) return JPDFIUM_ERR_REDACT_INCOMPLETE;

        // Audit 2 (objects): see auditNoSurvivorsInRegion.
        std::vector<FS_RECTF> regions;
        FS_RECTF r;
        r.left = x;
        r.bottom = y;
        r.right = x + w;
        r.top = y + h;
        regions.push_back(r);
        if (!auditNoSurvivorsInRegion(pw->page, regions, paintedCovers)) {
            return JPDFIUM_ERR_REDACT_INCOMPLETE;
        }
        // Sanitize-stage bookkeeping: annotations intersecting this zone are
        // removed on every redacted save.
        if (pw->core) pw->core->addRedactZone(pw->pageIndex, x, y, x + w, y + h);
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;  // never let exceptions cross the FFI boundary
    }
}

int32_t jpdfium_crop_remove_content(int64_t page, float x, float y, float w, float h) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(w) || !std::isfinite(h) ||
        w <= 0.0f || h <= 0.0f)
        return JPDFIUM_ERR_INVALID;

    try {
        const float cL = x, cB = y, cR = x + w, cT = y + h;

        const int fastObjCount = FPDFPage_CountObjects(pw->page);
        bool allInside = true;
        for (int i = 0; i < fastObjCount; ++i) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(pw->page, i);
            if (!obj) continue;
            float ol, ob, or_, ot;
            if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot) ||
                !isFullyContained(ol, ob, or_, ot, cL, cB, cR, cT)) {
                allInside = false;
                break;
            }
        }
        if (allInside) return JPDFIUM_OK;

        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_NATIVE;

        // Compute aggregate bounding box covering all page objects and standard page size
        float pageMinX = 0.0f, pageMinY = 0.0f;
        float pageMaxX = static_cast<float>(FPDF_GetPageWidth(pw->page));
        float pageMaxY = static_cast<float>(FPDF_GetPageHeight(pw->page));
        if (pageMaxX <= 0.0f) pageMaxX = cR + 1000.0f;
        if (pageMaxY <= 0.0f) pageMaxY = cT + 1000.0f;

        for (int i = 0; i < fastObjCount; ++i) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(pw->page, i);
            if (!obj) continue;
            float ol, ob, or_, ot;
            if (FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) {
                pageMinX = std::min(pageMinX, ol);
                pageMinY = std::min(pageMinY, ob);
                pageMaxX = std::max(pageMaxX, or_);
                pageMaxY = std::max(pageMaxY, ot);
            }
        }

        // Expand bounds to enclose any outer content
        pageMinX -= 100.0f;
        pageMinY -= 100.0f;
        pageMaxX += 100.0f;
        pageMaxY += 100.0f;

        // Define 4 outer margin bounding boxes around the crop rect
        struct MarginBox {
            float l, b, r, t;
        };
        std::vector<MarginBox> margins;
        if (cL > pageMinX) margins.push_back({pageMinX, pageMinY, cL, pageMaxY});
        if (cR < pageMaxX) margins.push_back({cR, pageMinY, pageMaxX, pageMaxY});
        if (cB > pageMinY) margins.push_back({cL, pageMinY, cR, cB});
        if (cT < pageMaxY) margins.push_back({cL, cT, cR, pageMaxY});

        std::vector<TextMatch> matches;
        const int count = FPDFText_CountChars(tp);

        for (const auto& mb : margins) {
            TextMatch m;
            m.bboxL = mb.l;
            m.bboxB = mb.b;
            m.bboxR = mb.r;
            m.bboxT = mb.t;
            for (int i = 0; i < count; ++i) {
                double ox, oy;
                if (!FPDFText_GetCharOrigin(tp, i, &ox, &oy)) continue;
                if (ox >= mb.l && ox <= mb.r && oy >= mb.b && oy <= mb.t) {
                    m.charIndices.push_back(i);
                }
            }
            matches.push_back(std::move(m));
        }

        bool anythingToRemove = false;
        for (const auto& m : matches) {
            if (!m.charIndices.empty()) {
                anythingToRemove = true;
                break;
            }
        }
        if (!anythingToRemove) {
            const int objCount = FPDFPage_CountObjects(pw->page);
            for (int i = 0; i < objCount; ++i) {
                FPDF_PAGEOBJECT obj = FPDFPage_GetObject(pw->page, i);
                if (!obj) continue;
                if (FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_TEXT) continue;
                float ol, ob, or_, ot;
                if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) continue;
                if (!isFullyContained(ol, ob, or_, ot, cL, cB, cR, cT)) {
                    anythingToRemove = true;
                    break;
                }
            }
        }
        if (!anythingToRemove) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_OK;
        }

        int32_t rc = objectFissionRedact(pw->doc, pw->page, tp, matches, 0x00000000, pw->core);

        FPDFText_ClosePage(tp);
        return rc;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_redact_pattern(int64_t page, const char* pattern, uint32_t argb,
                               int32_t remove_content) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    if (remove_content == 0) {
        // Doctrine: visual-only "cover" redaction is banned in the public API.
        return JPDFIUM_ERR_INVALID;
    }

    try {
        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;

        int count = FPDFText_CountChars(tp);

        // Build the search buffer: NFKC-normalized text (ligature and
        // compatibility characters decomposed) with an index map back to the
        // original character indices, plus the raw unicode sequence used for
        // grapheme-cluster boundary alignment.
        std::vector<int> idxMap;
        std::u32string wtext = buildNormalizedText(tp, count, idxMap);
        std::vector<uint32_t> unicodeSeq;
        unicodeSeq.reserve(static_cast<size_t>(count));
        for (int i = 0; i < count; ++i) {
            unicodeSeq.push_back(FPDFText_GetUnicode(tp, i));
        }

        // Compile the pattern (PCRE2 UTF/UCP, JIT, hardened limits).
        // Sanitize-stage bookkeeping: metadata/outlines/form values containing
        // the pattern are scrubbed on every redacted save.
        if (pw->core) pw->core->addRedactLiteral(pattern);
#ifdef JPDFIUM_HAS_PCRE2
        Pcre2Pattern pc;
        std::string compileErr;
        if (!pattern || !compilePcre2(utf8_to_u32(pattern), false, pc, compileErr)) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_ERR_INVALID;
        }
#else
        if (!pattern) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_ERR_INVALID;
        }
#endif

        // Collect matches with character-level indices
        std::vector<TextMatch> matches;
#ifdef JPDFIUM_HAS_PCRE2
        collectPcre2Matches(tp, wtext, idxMap, pc, 0.0f, matches);
#else
        (void)wtext;
#endif
        alignMatchesToGraphemes(tp, unicodeSeq, matches);
#ifdef JPDFIUM_HAS_HARFBUZZ
        // Snap every span to shaped-cluster boundaries (ligature safety):
        // a cut inside a shaped cluster would leave the survivor
        // unrenderable, so the span grows to the whole cluster.
        alignMatchesToShapedClusters(tp, matches);
#endif

        if (matches.empty()) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_OK;
        }

        // Expected surviving-text fingerprint (pre-redaction).
        std::vector<char> redactSet(count, 0);
        for (auto& m : matches)
            for (int ci : m.charIndices) redactSet[ci] = 1;
        std::u32string expectedFp = survivingFingerprint(wtext, idxMap, redactSet);

        // Apply Object Fission redaction
        int32_t rc = objectFissionRedact(pw->doc, pw->page, tp, matches, argb, pw->core);
        FPDFText_ClosePage(tp);
        if (rc != JPDFIUM_OK) return rc;

        // Audit loop: re-extract the page text and verify the pattern no
        // longer matches and the surviving fingerprint is bit-identical.
        // An audit that cannot run is a loud unverifiable error.
        FPDF_TEXTPAGE audit = FPDFText_LoadPage(pw->page);
        if (!audit) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;
        std::vector<int> idxMap2;
        int n2 = FPDFText_CountChars(audit);
        std::u32string wtext2 = buildNormalizedText(audit, n2, idxMap2);
        std::vector<TextMatch> remaining;
        // Padding 0: the audit re-matches the RAW pattern (the pre-redaction
        // padded bboxes were only for cover painting / geometric rules).
#ifdef JPDFIUM_HAS_PCRE2
        collectPcre2Matches(audit, wtext2, idxMap2, pc, 0.0f, remaining);
#endif
        std::u32string actualFp = survivingFingerprint(wtext2, idxMap2, {});
        FPDFText_ClosePage(audit);
        if (!remaining.empty() || actualFp != expectedFp) return JPDFIUM_ERR_REDACT_INCOMPLETE;
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;  // never let exceptions cross the FFI boundary
    }
}

// Flatten

int32_t jpdfium_page_flatten(int64_t page) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    int rc = FPDFPage_Flatten(pw->page, FLAT_NORMALDISPLAY);
    return (rc == FLATTEN_SUCCESS || rc == FLATTEN_NOTHINGTODO) ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
}

// Word-list redaction with padding
// words: null-terminated array of null-terminated UTF-8 strings
// padding: extra points added around each match bounding box
// wholeWord: if non-zero, only match when surrounded by non-alphanumeric characters
// useRegex: if non-zero, each word is treated as a regex pattern

int32_t jpdfium_redact_words(int64_t page, const char** words, int32_t wordCount, uint32_t argb,
                             float padding, int32_t wholeWord, int32_t useRegex,
                             int32_t remove_content) noexcept {
    return jpdfium_redact_words_ex(page, words, wordCount, argb, padding, wholeWord, useRegex,
                                   remove_content, 0, nullptr);
}

// Extended version that reports match count back to the caller.
int32_t jpdfium_redact_words_ex(int64_t page, const char** words, int32_t wordCount, uint32_t argb,
                                float padding, int32_t wholeWord, int32_t useRegex,
                                int32_t remove_content, int32_t caseSensitive,
                                int32_t* matchCount) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    if (remove_content == 0) {
        // Doctrine: visual-only "cover" redaction is banned in the public API.
        return JPDFIUM_ERR_INVALID;
    }
    if (!words || wordCount <= 0) {
        if (matchCount) *matchCount = 0;
        return JPDFIUM_OK;
    }

    try {
        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;

        int count = FPDFText_CountChars(tp);

        // Build the search buffer: NFKC-normalized text with an index map,
        // plus the raw unicode sequence for grapheme boundary alignment.
        std::vector<int> idxMap;
        std::u32string wtext = buildNormalizedText(tp, count, idxMap);
        std::vector<uint32_t> unicodeSeq;
        unicodeSeq.reserve(static_cast<size_t>(count));
        for (int i = 0; i < count; ++i) {
            unicodeSeq.push_back(FPDFText_GetUnicode(tp, i));
        }

        std::vector<TextMatch> matches;
#ifdef JPDFIUM_HAS_PCRE2
        std::vector<Pcre2Pattern> compiledPatterns;
#endif
        int rejectedPatterns = 0;
        int compiledCount = 0;

        // Literal word lists compile into ONE combined alternation
        // (group->word map) so the page text is scanned once; regex mode
        // patterns compile individually (combining arbitrary regexes would
        // change anchor semantics).
        for (int32_t wi = 0; wi < wordCount; ++wi) {
            if (words[wi] && pw->core) pw->core->addRedactLiteral(words[wi]);
        }
        if (!useRegex) {
#ifdef JPDFIUM_HAS_PCRE2
            std::u32string combined;
            std::vector<int> groupToWord;
            if (buildLiteralAlternation(words, wordCount, wholeWord != 0, combined, groupToWord)) {
                Pcre2Pattern pc;
                std::string err;
                if (compilePcre2(combined, caseSensitive == 0, pc, err)) {
                    compiledCount = 1;
                    size_t offset = 0;
                    while (offset <= wtext.size()) {
                        int rc = pcre2_match(pc.code, reinterpret_cast<PCRE2_SPTR>(wtext.data()),
                                             wtext.size(), offset, 0, pc.md, pc.mctx);
                        if (rc == PCRE2_ERROR_NOMATCH) break;
                        if (rc < 0) break;
                        PCRE2_SIZE* ov = pcre2_get_ovector_pointer(pc.md);
                        PCRE2_SIZE start = ov[0];
                        PCRE2_SIZE end = ov[1];
                        if (end <= start) {
                            offset = start + 1;
                            continue;
                        }
                        appendMatchChars(tp, idxMap, static_cast<int>(start),
                                         static_cast<int>(end - start), padding, matches);
                        offset = end;
                    }
                    compiledPatterns.push_back(std::move(pc));
                } else {
                    rejectedPatterns += wordCount;
                }
            } else {
                rejectedPatterns += wordCount;
            }
#endif
        } else {
            for (int32_t wi = 0; wi < wordCount; ++wi) {
                if (!words[wi]) continue;
                std::u32string wpattern = utf8_to_u32(words[wi]);
                if (wholeWord) {
                    wpattern.insert(0, U"\\b");
                    wpattern += U"\\b";
                }
#ifdef JPDFIUM_HAS_PCRE2
                Pcre2Pattern pc;
                std::string err;
                if (!compilePcre2(wpattern, caseSensitive == 0, pc, err)) {
                    ++rejectedPatterns;
                    continue;
                }
                compiledCount++;
                collectPcre2Matches(tp, wtext, idxMap, pc, padding, matches);
                compiledPatterns.push_back(std::move(pc));
#endif
            }
        }
        alignMatchesToGraphemes(tp, unicodeSeq, matches);
#ifdef JPDFIUM_HAS_HARFBUZZ
        // Snap every span to shaped-cluster boundaries (ligature safety):
        // a cut inside a shaped cluster would leave the survivor
        // unrenderable, so the span grows to the whole cluster.
        alignMatchesToShapedClusters(tp, matches);
#endif
        std::vector<char> redactSet(count, 0);
        for (auto& m : matches)
            for (int ci : m.charIndices) redactSet[ci] = 1;
        std::u32string expectedFp = survivingFingerprint(wtext, idxMap, redactSet);

        if (matchCount) *matchCount = static_cast<int32_t>(matches.size());

        // Every supplied pattern failed to compile: the caller believes the
        // redaction ran, but nothing was even searched for.
        // cppcheck-suppress incorrectLogicOperator
        if (rejectedPatterns > 0 && compiledCount == 0) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_ERR_INVALID;
        }

        if (matches.empty()) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_OK;
        }

        // Apply Object Fission redaction (all matches in one pass)
        int32_t rc = objectFissionRedact(pw->doc, pw->page, tp, matches, argb, pw->core);
        FPDFText_ClosePage(tp);
        if (rc != JPDFIUM_OK) return rc;

        // Audit loop: after content removal, re-extract the page text and
        // verify none of the patterns still match (strategy-fallback-preserved
        // originals, missed form text and offset OCR overlays all surface
        // here as a distinct error instead of a silent leak). An audit that
        // cannot run is a loud unverifiable error. Re-matching uses padding 0
        // (the padded bboxes only governed cover painting / geometric rules).
        FPDF_TEXTPAGE audit = FPDFText_LoadPage(pw->page);
        if (!audit) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;
        std::vector<int> idxMap2;
        int n2 = FPDFText_CountChars(audit);
        std::u32string wtext2 = buildNormalizedText(audit, n2, idxMap2);
        std::u32string actualFp = survivingFingerprint(wtext2, idxMap2, {});
        if (actualFp != expectedFp) {
            FPDFText_ClosePage(audit);
            return JPDFIUM_ERR_REDACT_INCOMPLETE;
        }
#ifdef JPDFIUM_HAS_PCRE2
        for (const auto& pc : compiledPatterns) {
            std::vector<TextMatch> remaining;
            collectPcre2Matches(audit, wtext2, idxMap2, pc, 0.0f, remaining);
            if (!remaining.empty()) {
                FPDFText_ClosePage(audit);
                return JPDFIUM_ERR_REDACT_INCOMPLETE;
            }
        }
#endif
        FPDFText_ClosePage(audit);
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;  // never let exceptions cross the FFI boundary
    }
}

int32_t jpdfium_annot_create_redact(int64_t page, float x, float y, float w, float h, uint32_t argb,
                                    int32_t* annot_index) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(pw->page, FPDF_ANNOT_REDACT);
    if (!annot) return JPDFIUM_ERR_NATIVE;

    FS_RECTF rect;
    rect.left = x;
    rect.bottom = y;
    rect.right = x + w;
    rect.top = y + h;
    if (!FPDFAnnot_SetRect(annot, &rect)) {
        FPDFPage_CloseAnnot(annot);
        return JPDFIUM_ERR_NATIVE;
    }

    unsigned int r = (argb >> 16) & 0xFF;
    unsigned int g = (argb >> 8) & 0xFF;
    unsigned int b = argb & 0xFF;
    FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_InteriorColor, r, g, b, 255);

    // Return the annotation index (it's appended at the end)
    int idx = FPDFPage_GetAnnotCount(pw->page) - 1;
    if (annot_index) *annot_index = idx;

    FPDFPage_CloseAnnot(annot);

    // Saving with uncommitted marks would ship intact text under red marks.
    if (pw->core) pw->core->unappliedRedactMarksCount++;
    return JPDFIUM_OK;
}

int32_t jpdfium_annot_count_redacts(int64_t page, int32_t* count) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page || !count) return JPDFIUM_ERR_INVALID;

    int total = FPDFPage_GetAnnotCount(pw->page);
    int redacts = 0;
    for (int i = 0; i < total; ++i) {
        FPDF_ANNOTATION a = FPDFPage_GetAnnot(pw->page, i);
        if (a) {
            if (FPDFAnnot_GetSubtype(a) == FPDF_ANNOT_REDACT) ++redacts;
            FPDFPage_CloseAnnot(a);
        }
    }
    *count = redacts;
    return JPDFIUM_OK;
}

int32_t jpdfium_annot_get_redacts_json(int64_t page, char** json) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page || !json) return JPDFIUM_ERR_INVALID;

    int total = FPDFPage_GetAnnotCount(pw->page);
    std::ostringstream os;
    os << '[';
    bool first = true;

    for (int i = 0; i < total; ++i) {
        FPDF_ANNOTATION a = FPDFPage_GetAnnot(pw->page, i);
        if (!a) continue;

        if (FPDFAnnot_GetSubtype(a) == FPDF_ANNOT_REDACT) {
            FS_RECTF rect;
            if (FPDFAnnot_GetRect(a, &rect)) {
                if (!first) os << ',';
                first = false;
                os << "{\"idx\":" << i << ",\"x\":" << rect.left << ",\"y\":" << rect.bottom
                   << ",\"w\":" << (rect.right - rect.left) << ",\"h\":" << (rect.top - rect.bottom)
                   << '}';
            }
        }
        FPDFPage_CloseAnnot(a);
    }
    os << ']';

    std::string s = os.str();
    char* out = static_cast<char*>(malloc(s.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, s.c_str(), s.size() + 1);
    *json = out;
    return JPDFIUM_OK;
}

int32_t jpdfium_annot_remove_redact(int64_t page, int32_t annot_index) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    int total = FPDFPage_GetAnnotCount(pw->page);
    if (annot_index < 0 || annot_index >= total) return JPDFIUM_ERR_NOT_FOUND;

    FPDF_ANNOTATION a = FPDFPage_GetAnnot(pw->page, annot_index);
    if (!a) return JPDFIUM_ERR_NOT_FOUND;

    bool isRedact = FPDFAnnot_GetSubtype(a) == FPDF_ANNOT_REDACT;
    FPDFPage_CloseAnnot(a);

    if (!isRedact) return JPDFIUM_ERR_INVALID;

    bool ok = FPDFPage_RemoveAnnot(pw->page, annot_index);
    if (ok && pw->core && pw->core->unappliedRedactMarksCount > 0) {
        pw->core->unappliedRedactMarksCount--;
    }
    return ok ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
}

int32_t jpdfium_annot_clear_redacts(int64_t page) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    // Remove in reverse order to avoid index shifting
    int removedCount = 0;
    for (int i = FPDFPage_GetAnnotCount(pw->page) - 1; i >= 0; --i) {
        FPDF_ANNOTATION a = FPDFPage_GetAnnot(pw->page, i);
        if (!a) continue;
        bool isRedact = FPDFAnnot_GetSubtype(a) == FPDF_ANNOT_REDACT;
        FPDFPage_CloseAnnot(a);
        if (isRedact) {
            if (FPDFPage_RemoveAnnot(pw->page, i)) {
                removedCount++;
            }
        }
    }
    if (pw->core) {
        pw->core->unappliedRedactMarksCount =
            std::max(0, pw->core->unappliedRedactMarksCount - removedCount);
    }
    return JPDFIUM_OK;
}

// Mark phase: find text matches and create REDACT annotations (no content mutation)
int32_t jpdfium_redact_mark_words(int64_t page, const char** words, int32_t wordCount,
                                  float padding, int32_t wholeWord, int32_t useRegex,
                                  int32_t caseSensitive, uint32_t argb,
                                  int32_t* matchCount) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    if (!words || wordCount <= 0) {
        if (matchCount) *matchCount = 0;
        return JPDFIUM_OK;
    }

    try {
        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_NATIVE;

        int count = FPDFText_CountChars(tp);

        // Build the search buffer: NFKC-normalized text with an index map,
        // plus the raw unicode sequence for grapheme boundary alignment.
        std::vector<int> idxMap;
        std::u32string wtext = buildNormalizedText(tp, count, idxMap);
        std::vector<uint32_t> unicodeSeq;
        unicodeSeq.reserve(static_cast<size_t>(count));
        for (int i = 0; i < count; ++i) {
            unicodeSeq.push_back(FPDFText_GetUnicode(tp, i));
        }

        std::vector<TextMatch> matches;
        // Mark-phase bookkeeping: the literals only become sanitize-relevant
        // once committed, but recording now keeps one code path.
        for (int32_t wi = 0; wi < wordCount; ++wi) {
            if (words[wi] && pw->core) pw->core->addRedactLiteral(words[wi]);
        }
#ifdef JPDFIUM_HAS_PCRE2
        if (!useRegex) {
            std::u32string combined;
            std::vector<int> groupToWord;
            if (buildLiteralAlternation(words, wordCount, wholeWord != 0, combined, groupToWord)) {
                Pcre2Pattern pc;
                std::string err;
                if (compilePcre2(combined, caseSensitive == 0, pc, err)) {
                    size_t offset = 0;
                    while (offset <= wtext.size()) {
                        int rc = pcre2_match(pc.code, reinterpret_cast<PCRE2_SPTR>(wtext.data()),
                                             wtext.size(), offset, 0, pc.md, pc.mctx);
                        if (rc == PCRE2_ERROR_NOMATCH) break;
                        if (rc < 0) break;
                        PCRE2_SIZE* ov = pcre2_get_ovector_pointer(pc.md);
                        PCRE2_SIZE start = ov[0];
                        PCRE2_SIZE end = ov[1];
                        if (end <= start) {
                            offset = start + 1;
                            continue;
                        }
                        appendMatchChars(tp, idxMap, static_cast<int>(start),
                                         static_cast<int>(end - start), padding, matches);
                        offset = end;
                    }
                }
            }
        } else {
            for (int32_t wi = 0; wi < wordCount; ++wi) {
                if (!words[wi]) continue;
                std::u32string wpattern = utf8_to_u32(words[wi]);
                if (wholeWord) {
                    wpattern.insert(0, U"\\b");
                    wpattern += U"\\b";
                }
                Pcre2Pattern pc;
                std::string err;
                if (!compilePcre2(wpattern, caseSensitive == 0, pc, err)) continue;
                collectPcre2Matches(tp, wtext, idxMap, pc, padding, matches);
            }
        }
#else
        (void)wtext;
#endif
        alignMatchesToGraphemes(tp, unicodeSeq, matches);
#ifdef JPDFIUM_HAS_HARFBUZZ
        // Snap every span to shaped-cluster boundaries (ligature safety):
        // a cut inside a shaped cluster would leave the survivor
        // unrenderable, so the span grows to the whole cluster.
        alignMatchesToShapedClusters(tp, matches);
#endif

        FPDFText_ClosePage(tp);

        // Create REDACT annotations from matches (zero content mutation)
        unsigned int r = (argb >> 16) & 0xFF;
        unsigned int g = (argb >> 8) & 0xFF;
        unsigned int b = argb & 0xFF;

        int createdCount = 0;
        for (auto& m : matches) {
            FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(pw->page, FPDF_ANNOT_REDACT);
            if (!annot) continue;

            FS_RECTF rect;
            rect.left = m.bboxL;
            rect.bottom = m.bboxB;
            rect.right = m.bboxR;
            rect.top = m.bboxT;
            FPDFAnnot_SetRect(annot, &rect);
            FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_InteriorColor, r, g, b, 255);
            FPDFPage_CloseAnnot(annot);
            createdCount++;
        }

        if (matchCount) *matchCount = static_cast<int32_t>(matches.size());

        if (createdCount > 0 && pw->core) {
            // Saving with uncommitted marks would ship intact text under red marks.
            pw->core->unappliedRedactMarksCount += createdCount;
        }
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;  // never let exceptions cross the FFI boundary
    }
}

// Commit phase: burn all REDACT annotations using Object Fission
int32_t jpdfium_redact_commit(int64_t page, uint32_t argb, int32_t remove_content,
                              int32_t* commitCount) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    if (remove_content == 0) {
        // Doctrine: visual-only "cover" redaction is banned in the public API.
        return JPDFIUM_ERR_INVALID;
    }

    try {
        // Collect all REDACT annotation rects
        int total = FPDFPage_GetAnnotCount(pw->page);
        std::vector<FS_RECTF> redactRects;
        std::vector<int> redactIndices;

        for (int i = 0; i < total; ++i) {
            FPDF_ANNOTATION a = FPDFPage_GetAnnot(pw->page, i);
            if (!a) continue;
            if (FPDFAnnot_GetSubtype(a) == FPDF_ANNOT_REDACT) {
                FS_RECTF rect;
                if (FPDFAnnot_GetRect(a, &rect)) {
                    redactRects.push_back(rect);
                    redactIndices.push_back(i);
                }
            }
            FPDFPage_CloseAnnot(a);
        }

        if (commitCount) *commitCount = static_cast<int32_t>(redactRects.size());

        if (redactRects.empty()) {
            return JPDFIUM_OK;
        }

        // Build TextMatch objects from annotation rects and run Object Fission.
        // Load text page for char-level hit testing. NOTE: the annotations are
        // removed only AFTER the content mutation succeeded - a failed fission
        // keeps the marks so the caller can retry instead of silently losing
        // both the marks and the content.
        //
        // No geometric fallback: committing marks without a verifiable text
        // page would silently redact by bbox - the banned degrade path.
        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;

        int charCount = FPDFText_CountChars(tp);

        // Build TextMatch for each annotation rect by finding intersecting
        // characters. A character is redacted when at least half of its glyph
        // box overlaps the rect (center-point-only testing leaks straddling
        // glyphs - for redaction, err on the side of removing more).
        std::vector<TextMatch> matches;
        for (auto& ar : redactRects) {
            TextMatch tm;
            tm.bboxL = ar.left;
            tm.bboxB = ar.bottom;
            tm.bboxR = ar.right;
            tm.bboxT = ar.top;

            for (int ci = 0; ci < charCount; ++ci) {
                double l, r, b, t;
                if (!FPDFText_GetCharBox(tp, ci, &l, &r, &b, &t)) continue;
                if (charInRect(l, b, r, t, ar.left, ar.bottom, ar.right, ar.top)) {
                    tm.charIndices.push_back(ci);
                }
            }

            matches.push_back(std::move(tm));
        }

        std::vector<FPDF_PAGEOBJECT> paintedCovers;
        int32_t rc =
            objectFissionRedact(pw->doc, pw->page, tp, matches, argb, pw->core, &paintedCovers);
        FPDFText_ClosePage(tp);

        if (rc != JPDFIUM_OK) return rc;  // keep the marks; content unchanged

        // Audit loop: no character glyph may still intersect a committed
        // redaction rect after content removal, and no unverified content
        // object may remain in one. An audit that cannot run is a loud
        // unverifiable error.
        FPDF_TEXTPAGE audit = FPDFText_LoadPage(pw->page);
        if (!audit) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;
        int n = FPDFText_CountChars(audit);
        for (auto& ar : redactRects) {
            for (int ci = 0; ci < n; ++ci) {
                double l, r, b, t;
                if (!FPDFText_GetCharBox(audit, ci, &l, &r, &b, &t)) continue;
                if (charInRect(l, b, r, t, ar.left, ar.bottom, ar.right, ar.top)) {
                    FPDFText_ClosePage(audit);
                    return JPDFIUM_ERR_REDACT_INCOMPLETE;
                }
            }
        }
        FPDFText_ClosePage(audit);

        if (!auditNoSurvivorsInRegion(pw->page, redactRects, paintedCovers)) {
            return JPDFIUM_ERR_REDACT_INCOMPLETE;
        }

        // Sanitize-stage bookkeeping: annotations intersecting these zones
        // are removed on every redacted save.
        if (pw->core) {
            for (auto& ar : redactRects) {
                pw->core->addRedactZone(pw->pageIndex, ar.left, ar.bottom, ar.right, ar.top);
            }
        }

        // Verified complete: remove the REDACT annotations now.
        for (int i = static_cast<int>(redactIndices.size()) - 1; i >= 0; --i) {
            FPDFPage_RemoveAnnot(pw->page, redactIndices[i]);
        }
        if (pw->core) {
            pw->core->unappliedRedactMarksCount =
                std::max(0, pw->core->unappliedRedactMarksCount -
                                static_cast<int32_t>(redactIndices.size()));
        }
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;  // never let exceptions cross the FFI boundary
    }
}

// Incremental save: writes only changed objects, document stays live.
//
// SECURITY: an incremental save APPENDS a new revision - the original,
// un-redacted content streams remain intact in the file body and are
// trivially recoverable. This function refuses to run after any content
// redaction; use jpdfium_doc_save / jpdfium_doc_save_bytes (full save,
// which drops orphaned revisions) instead.
int32_t jpdfium_doc_save_incremental(int64_t doc, uint8_t** data, int64_t* len) noexcept {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
    if (w->core->contentRedacted) return JPDFIUM_ERR_REDACTED_SAVE;
    if (w->core->hasUnappliedRedactMarks()) return JPDFIUM_ERR_UNCOMMITTED_MARKS;

    struct BufWriter : FPDF_FILEWRITE {
        std::vector<uint8_t> buf;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            auto* bw = static_cast<BufWriter*>(self);
            try {
                auto* src = static_cast<const uint8_t*>(data);
                bw->buf.insert(bw->buf.end(), src, src + size);
                return 1;
            } catch (...) {
                return 0;  // never let exceptions cross the C callback
            }
        }
    } bw;
    bw.version = 1;
    bw.WriteBlock = BufWriter::Write;

    if (!FPDF_SaveAsCopy(w->core->doc, &bw, FPDF_INCREMENTAL)) return JPDFIUM_ERR_IO;

    size_t sz = bw.buf.size();
    uint8_t* out = static_cast<uint8_t*>(malloc(sz));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, bw.buf.data(), sz);
    *data = out;
    *len = static_cast<int64_t>(sz);
    return JPDFIUM_OK;
}
