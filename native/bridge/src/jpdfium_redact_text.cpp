// jpdfium_redact_text.cpp - text utilities for redaction.
//
// Encoding conversions, rectangle geometry, normalized-text extraction,
// ligature handling and font-name helpers shared by matching and fission.

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

std::u32string utf8_to_u32(const char* utf8) {
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
std::vector<uint16_t> u32_to_utf16le(const std::u32string& us) {
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
std::u32string fpdfWcharBufToU32(const FPDF_WCHAR* buf, size_t n) {
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
bool isFullyContained(float al, float ab, float ar, float at, float bl, float bb, float br,
                      float bt) {
    return al >= bl && ab >= bb && ar <= br && at <= bt;
}

// FS_RECTF stores left, top, right, bottom (screen order), so a bottom-up
// PDF rectangle must be re-ordered here or the vertical edges swap.
FS_RECTF makePageRect(float l, float b, float r, float t) {
    FS_RECTF out{};
    out.left = l;
    out.top = t;
    out.right = r;
    out.bottom = b;
    return out;
}

// Check if two rectangles overlap at all
bool rectsOverlap(float al, float ab, float ar, float at, float bl, float bb, float br, float bt) {
    return !(ar < bl || al > br || at < bb || ab > bt);
}

// Mark every character of `textPage` whose page-space box touches any rectangle
// in `rects`. FPDFText_GetCharBox reports PDF user-space points with y up - the
// same space the redaction rectangles use. Used by the redaction survivor audit
// to separate the redaction footprint from text that must survive untouched.
void markTouchedByRects(FPDF_TEXTPAGE textPage, int count, const std::vector<FS_RECTF>& rects,
                        std::vector<char>& out) {
    out.assign(static_cast<size_t>(count), 0);
    if (rects.empty()) return;
    for (int ci = 0; ci < count; ++ci) {
        double l = 0, r = 0, b = 0, t = 0;
        if (!FPDFText_GetCharBox(textPage, ci, &l, &r, &b, &t)) continue;
        for (const FS_RECTF& rc : rects) {
            if (rectsOverlap(static_cast<float>(l), static_cast<float>(b), static_cast<float>(r),
                             static_cast<float>(t), rc.left, rc.bottom, rc.right, rc.top)) {
                out[static_cast<size_t>(ci)] = 1;
                break;
            }
        }
    }
}

// Compute intersection area ratio (of object) for partial-overlap decisions
float overlapRatio(float al, float ab, float ar, float at, float bl, float bb, float br, float bt) {
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
void buildNormalizedText(FPDF_TEXTPAGE textPage, int count, std::vector<int>& normIdxMap,
                         std::u32string& norm) {
    norm.clear();
    normIdxMap.clear();
#ifdef JPDFIUM_HAS_ICU
    static thread_local std::unordered_map<uint32_t, std::u32string> cache;
    static const icu::Normalizer2* nfkc = [] {
        ensureIcuInit();
        UErrorCode err = U_ZERO_ERROR;
        const icu::Normalizer2* n = icu::Normalizer2::getNFKCInstance(err);
        return U_FAILURE(err) ? nullptr : n;
    }();
    if (!nfkc) {
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
        return;
    }

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
                out.toUTF32(buf.data(), static_cast<int32_t>(buf.size()), err2);
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
    return;
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
    return;
#endif
}

std::u32string buildNormalizedText(FPDF_TEXTPAGE textPage, int count,
                                   std::vector<int>& normIdxMap) {
    std::u32string norm;
    buildNormalizedText(textPage, count, normIdxMap, norm);
    return norm;
}

// Codepoint fingerprint of the page's printable (non-space, non-U+FFFE)
// text after removing the redacted spans. After a redaction, re-extracting
// the page and computing the same fingerprint must yield the identical
// sequence: a fission that silently drops or mangles glyphs (e.g. a
// character the font can only render as part of a ligature) changes the
// fingerprint and is reported as an incomplete redaction instead of being
// shipped as damaged text.
std::u32string survivingFingerprint(const std::u32string& normalizedText,
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

bool charInRect(double l, double b, double r, double t, float rl, float rb, float rr, float rt) {
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
std::u32string decomposeLigatures(const std::u32string& input) {
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
uint32_t unicodeToWinAnsiCharcode(uint32_t unicode) {
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

// Compose two matrices in PDF row-vector convention ([x y 1] * M):
// the result applies |m| first, then |t|.
//
// This matches PDFium's CFX_Matrix::operator* (core/fxcrt/fx_coordinates.h)
// and CPDF_TextPage::ProcessFormObject, which composes the child form matrix
// BEFORE the parent chain: actual_form_matrix = form_matrix() * form_matrix.
// Verified empirically against the pinned PDFium build with nested rotated
// forms (see CharPositionFidelityTest / native verification harness).
FS_MATRIX concatMatrix(const FS_MATRIX& m, const FS_MATRIX& t) {
    return FS_MATRIX{
        m.a * t.a + m.b * t.c, m.a * t.b + m.b * t.d,       m.c * t.a + m.d * t.c,
        m.c * t.b + m.d * t.d, m.e * t.a + m.f * t.c + t.e, m.e * t.b + m.f * t.d + t.f,
    };
}

const char* getStandard14FontName(const char* baseName) {
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
bool isStandard14Font(FPDF_FONT font) {
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
