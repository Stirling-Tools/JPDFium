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
#include <map>
#include <regex>
#include <set>
#include <sstream>
#include <string>
#include <type_traits>
#include <unordered_map>
#include <vector>

#include "jpdfium.h"
#include "jpdfium_internal.h"

#ifdef JPDFIUM_HAS_FREETYPE
#include <ft2build.h>
#include FT_FREETYPE_H
static FT_Library g_ft_lib = nullptr;
static void ensureFreeTypeInit() {
    if (!g_ft_lib) FT_Init_FreeType(&g_ft_lib);
}
#endif

// UTF-8 -> std::wstring (wchar_t is 32-bit on Linux/macOS - one code unit per codepoint)
static std::wstring utf8_to_wstring(const char* utf8) {
    std::wstring result;
    const auto* s = reinterpret_cast<const uint8_t*>(utf8);
    while (*s) {
        uint32_t cp;
        if (*s < 0x80) {
            cp = *s++;
        } else if (*s < 0xE0) {
            cp = (*s++ & 0x1F) << 6;
            cp |= (*s++ & 0x3F);
        } else if (*s < 0xF0) {
            cp = (*s++ & 0x0F) << 12;
            cp |= (*s++ & 0x3F) << 6;
            cp |= (*s++ & 0x3F);
        } else {
            cp = (*s++ & 0x07) << 18;
            cp |= (*s++ & 0x3F) << 12;
            cp |= (*s++ & 0x3F) << 6;
            cp |= (*s++ & 0x3F);
        }
        result += static_cast<wchar_t>(cp);
    }
    return result;
}

// std::wstring -> UTF-16LE (for FPDFText_SetText on new text objects)
static std::pmr::vector<uint16_t> wstring_to_utf16le(const std::pmr::wstring& ws,
                                                     std::pmr::memory_resource* mr) {
    std::pmr::vector<uint16_t> result(mr);
    for (wchar_t wc : ws) {
        uint32_t cp = static_cast<uint32_t>(static_cast<std::make_unsigned_t<wchar_t>>(wc));
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

// Decomposes standard Unicode ligatures (U+FB00-FB06) into their ASCII
// component characters. This prevents encoding round-trip failures where
// FPDFText_GetUnicode returns a ligature codepoint that can't be reverse-
// mapped back to a charcode by the font's encoding dictionary.
static std::pmr::wstring decomposeLigatures(std::pmr::wstring input) {
    std::pmr::wstring result(input.get_allocator());
    result.reserve(input.size() + 8);
    for (wchar_t wc : input) {
        switch (static_cast<uint32_t>(wc)) {
            case 0xFB00:
                result += L"ff";
                break;  // ff
            case 0xFB01:
                result += L"fi";
                break;  // fi
            case 0xFB02:
                result += L"fl";
                break;  // fl
            case 0xFB03:
                result += L"ffi";
                break;  // ffi
            case 0xFB04:
                result += L"ffl";
                break;  // ffl
            case 0xFB05:
                result += L"st";
                break;  // long-s t
            case 0xFB06:
                result += L"st";
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

// Removes text/image page objects within [x,y,x+w,y+h] (PDF coords: y up),
// paints a filled rect of the given color, commits with GenerateContent.
// argb: 0xAARRGGBB
//
// FIX: Only removes objects whose bounding box is MOSTLY (>70%) within the
// redaction rectangle. Previously ANY overlap caused removal, which deleted
// entire lines of text when only one word was targeted.
static int32_t applyRedactRect(FPDF_PAGE page, float x, float y, float w, float h, uint32_t argb,
                               bool removeContent = true) {
    unsigned int r = (argb >> 16) & 0xFF;
    unsigned int g = (argb >> 8) & 0xFF;
    unsigned int b = argb & 0xFF;

    float rx = x, ry = y, rr = x + w, rt = y + h;

    if (removeContent) {
        // Phase 1: remove objects that are FULLY or MOSTLY contained in the redact rect.
        // Objects that only partially overlap are NOT removed - the painted rectangle
        // will cover them visually, and flatten will bake it in.
        int objCount = FPDFPage_CountObjects(page);
        for (int i = objCount - 1; i >= 0; --i) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
            int type = FPDFPageObj_GetType(obj);

            // Handle all content-bearing object types
            if (type != FPDF_PAGEOBJ_TEXT && type != FPDF_PAGEOBJ_IMAGE &&
                type != FPDF_PAGEOBJ_PATH && type != FPDF_PAGEOBJ_SHADING &&
                type != FPDF_PAGEOBJ_FORM)
                continue;

            float ol, ob, or_, ot;
            if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) continue;

            if (!rectsOverlap(ol, ob, or_, ot, rx, ry, rr, rt)) continue;

            // Only remove if the object is fully contained or >70% contained
            bool fullyInside = isFullyContained(ol, ob, or_, ot, rx, ry, rr, rt);
            float ratio = overlapRatio(ol, ob, or_, ot, rx, ry, rr, rt);

            if (fullyInside || ratio > 0.70f) {
                FPDFPage_RemoveObject(page, obj);
                FPDFPageObj_Destroy(obj);
            }
        }
    }

    // Phase 2: paint filled rectangle (always - provides visual cover)
    FPDF_PAGEOBJECT rect = FPDFPageObj_CreateNewRect(x, y, w, h);
    if (!rect) return JPDFIUM_ERR_NATIVE;
    FPDFPageObj_SetFillColor(rect, r, g, b, 255);
    FPDFPath_SetDrawMode(rect, FPDF_FILLMODE_ALTERNATE, 0);
    FPDFPage_InsertObject(page, rect);

    // Phase 3: commit to content stream
    return FPDFPage_GenerateContent(page) ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
}

// Object Fission Algorithm
// True content redaction that permanently removes targeted content from the
// content stream. For text, implements character-level "Object Fission"
// that preserves surrounding text with perfect typographical fidelity.
//
// Handles ALL PDF page object types:
//
//   TEXT objects:
//   1. Map text-page character indices to their owning FPDF_PAGEOBJECT via
//      FPDFText_GetTextObject (direct char-to-object mapping).
//   2. For each page object that contains redacted characters:
//        - If ALL characters redacted -> destroy the entire object.
//        - If only SOME characters redacted -> "fission" the object:
//            a) Split into per-word fragments at word/redaction boundaries.
//            b) Each fragment gets absolute positioning from FPDFText_GetCharOrigin.
//            c) Three encoding strategies: SetText, FreeType GID, WinAnsi.
//            d) Destroy the original object.
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
//   - Recursive descent: enumerate child objects via FPDFFormObj_* APIs,
//     transform bounds through cumulative form->page matrix chain,
//     remove children that are inside redaction rects.
//
//   IMAGE objects (raster content, photos, scanned pages):
//   - Remove when fully contained or >70% overlap with a redaction rect.
//
//   3. Paint a filled rectangle at every match bbox.
//   4. Regenerate the content stream (single FPDFPage_GenerateContent call).

struct TextMatch {
    std::pmr::vector<int> charIndices;  // text-page char indices for matched chars
    float bboxL, bboxB, bboxR, bboxT;   // tight aggregate bbox (PDF coords)

    explicit TextMatch(std::pmr::memory_resource* mr) : charIndices(mr) {}
};

// A single contiguous run of surviving (non-redacted) characters within a text
// object.  Each fragment becomes its own independent FPDF_PAGEOBJECT, pinned
// to the exact absolute coordinates obtained from FPDFText_GetCharOrigin.
struct TextFragment {
    std::pmr::vector<uint16_t> utf16;  // UTF-16LE null-terminated text
    FS_MATRIX matrix;                  // hybrid: original a,b,c,d + charOrigin e,f

    explicit TextFragment(std::pmr::memory_resource* mr) : utf16(mr) {}
};

// Pre-computed fission plan for a single page object
struct FissionPlan {
    FPDF_PAGEOBJECT originalObj;

    // Form XObject holding originalObj, or null when it sits directly on the page.
    // Fragments are always re-inserted at page level, so toPage maps them back into place.
    FPDF_PAGEOBJECT parentForm;
    FS_MATRIX toPage;

    // All surviving text fragments (replaces the old prefix/suffix pair).
    // Each fragment is independently positioned via FPDFText_GetCharOrigin, so
    // multi-gap redactions (e.g. two SSNs in the same text run) are handled
    // correctly.
    std::pmr::vector<TextFragment> fragments;

    FPDF_FONT font;
    float fontSize;
    FPDF_TEXT_RENDERMODE renderMode;

    // Original text colors - copied to every new fragment
    unsigned int fillR, fillG, fillB, fillA;
    unsigned int strokeR, strokeG, strokeB, strokeA;
    bool hasStroke;

    bool removeEntirely;

    explicit FissionPlan(std::pmr::memory_resource* mr)
        : parentForm(nullptr), toPage{1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f}, fragments(mr) {}
};

static int32_t objectFissionRedact(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_TEXTPAGE textPage,
                                   const std::pmr::vector<TextMatch>& matches, uint32_t argb,
                                   bool removeContent, bool cropMode,
                                   std::pmr::memory_resource* mr) {
    if (matches.empty()) return JPDFIUM_OK;

    unsigned int red = (argb >> 16) & 0xFF;
    unsigned int grn = (argb >> 8) & 0xFF;
    unsigned int blu = argb & 0xFF;

    // Visual-only fast path
    if (!removeContent) {
        for (auto& m : matches) {
            FPDF_PAGEOBJECT rect =
                FPDFPageObj_CreateNewRect(m.bboxL, m.bboxB, m.bboxR - m.bboxL, m.bboxT - m.bboxB);
            if (!rect) continue;
            FPDFPageObj_SetFillColor(rect, red, grn, blu, 255);
            FPDFPath_SetDrawMode(rect, FPDF_FILLMODE_ALTERNATE, 0);
            FPDFPage_InsertObject(page, rect);
        }
        return FPDFPage_GenerateContent(page) ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
    }

    // Analysis phase (read-only - all text-page queries happen here)

    int totalChars = FPDFText_CountChars(textPage);

    // 1. Collect the set of all char indices targeted for redaction
    std::pmr::set<int> redactSet(mr);
    for (auto& m : matches) {
        for (int ci : m.charIndices) redactSet.insert(ci);
    }

    // 2. Build char -> page-object mapping.
    //    Use FPDFText_GetTextObject (PDFium experimental API) for direct
    //    char-to-object mapping instead of the old bounds-based spatial
    //    correlation.  This is far more reliable: the old approach matched
    //    char bounding-box centres against object bounds with a 0.5pt
    //    tolerance, which could mismap characters when text objects overlap
    //    or when chars have degenerate bboxes (spaces, control chars).
    //
    //    FPDFText_GetTextObject returns the actual owning FPDF_PAGEOBJECT
    //    for each char index, eliminating all spatial correlation errors.
    //
    //    For unmapped chars (nulls, generated chars), inherit from neighbors
    //    as before to keep spaces in the correct text flow.
    //
    //    The same pass also builds objToChars: object pointer -> char indices,
    //    covering text objects INSIDE form XObjects (their pointers are not in
    //    the page-level object list). This makes the form-descent text lookup
    //    O(chars) total instead of O(chars x children).
    int objCount = FPDFPage_CountObjects(page);

    // The text page flattens form XObjects, so FPDFText_GetTextObject can return an object
    // nested inside one. Index those too, or their chars stay unmapped and never get redacted.
    struct ObjRef {
        FPDF_PAGEOBJECT obj;
        FPDF_PAGEOBJECT parentForm;  // null when the object sits directly on the page
        FS_MATRIX toPage;            // cumulative form-to-page transform, identity at page level
    };
    std::pmr::vector<ObjRef> allObjs(mr);
    std::pmr::unordered_map<uintptr_t, int> objPtrToIndex(mr);

    const FS_MATRIX identityMatrix = {1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f};

    auto concatMatrix = [](const FS_MATRIX& m, const FS_MATRIX& t) -> FS_MATRIX {
        FS_MATRIX out;
        out.a = m.a * t.a + m.b * t.c;
        out.b = m.a * t.b + m.b * t.d;
        out.c = m.c * t.a + m.d * t.c;
        out.d = m.c * t.b + m.d * t.d;
        out.e = m.e * t.a + m.f * t.c + t.e;
        out.f = m.e * t.b + m.f * t.d + t.f;
        return out;
    };

    std::function<void(FPDF_PAGEOBJECT, const FS_MATRIX&, int)> indexFormChildren =
        [&](FPDF_PAGEOBJECT formObj, const FS_MATRIX& formToPage, int depth) {
            if (depth > 8) return;  // guard against a malformed self-referential form chain
            int childCount = FPDFFormObj_CountObjects(formObj);
            for (int ci = 0; ci < childCount; ci++) {
                FPDF_PAGEOBJECT child = FPDFFormObj_GetObject(formObj, ci);
                if (!child) continue;
                uintptr_t key = reinterpret_cast<uintptr_t>(child);
                if (objPtrToIndex.count(key)) continue;
                objPtrToIndex[key] = static_cast<int>(allObjs.size());
                allObjs.push_back({child, formObj, formToPage});

                if (FPDFPageObj_GetType(child) == FPDF_PAGEOBJ_FORM) {
                    FS_MATRIX childMatrix;
                    if (FPDFPageObj_GetMatrix(child, &childMatrix)) {
                        indexFormChildren(child, concatMatrix(childMatrix, formToPage), depth + 1);
                    }
                }
            }
        };

    for (int oi = 0; oi < objCount; oi++) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, oi);
        objPtrToIndex[reinterpret_cast<uintptr_t>(obj)] = static_cast<int>(allObjs.size());
        allObjs.push_back({obj, nullptr, identityMatrix});
    }
    // Descend only after the page level is indexed, so page objects keep indices 0..objCount-1.
    for (int oi = 0; oi < objCount; oi++) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, oi);
        if (FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_FORM) continue;
        FS_MATRIX formMatrix;
        if (FPDFPageObj_GetMatrix(obj, &formMatrix)) {
            indexFormChildren(obj, formMatrix, 0);
        }
    }

    struct CharInfo {
        int ownerObj;      // index into allObjs (-1 = unmapped)
        bool isGenerated;  // FPDFText_IsGenerated
    };
    std::pmr::vector<CharInfo> charInfo(totalChars, mr);
    std::pmr::unordered_map<uintptr_t, std::pmr::vector<int>> objToChars(mr);

    for (int ci = 0; ci < totalChars; ci++) {
        charInfo[ci] = {-1, false};

        // Generated (synthetic) characters don't correspond to real text
        // objects in the content stream and should not participate in fission
        // ownership decisions - but they may still resolve to an owning object
        // via FPDFText_GetTextObject, which the form descent relies on.
        if (FPDFText_IsGenerated(textPage, ci) == 1) {
            charInfo[ci].isGenerated = true;
        }

        FPDF_PAGEOBJECT obj = FPDFText_GetTextObject(textPage, ci);
        if (obj) {
            auto key = reinterpret_cast<uintptr_t>(obj);
            auto [it, inserted] = objToChars.try_emplace(key);
            if (inserted) it->second = std::pmr::vector<int>(mr);
            it->second.push_back(ci);

            if (!charInfo[ci].isGenerated) {
                auto pit = objPtrToIndex.find(key);
                if (pit != objPtrToIndex.end()) {
                    charInfo[ci].ownerObj = pit->second;
                }
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
    //    objChars[oi] = sorted list of text-page char indices belonging to that
    //    object (chars are visited in ascending index order, so lists are
    //    already sorted). Indexed by allObjs index (page objects then form
    //    children); a flat vector of vectors instead of a map - O(objCount)
    //    and arena-friendly.
    std::pmr::vector<std::pmr::vector<int>> objChars(allObjs.size(),
                                                     std::pmr::vector<int>(mr), mr);
    for (int ci = 0; ci < totalChars; ci++) {
        int oi = charInfo[ci].ownerObj;
        if (oi >= 0) objChars[static_cast<size_t>(oi)].push_back(ci);
    }

    // 5. Plan fission operations for ALL text objects on the page.
    //
    // KEY: FPDFPage_GenerateContent serialises text objects using flat Tj
    // strings, which drops all TJ-array kerning/positioning data.  When ANY
    // object on a content stream is modified (add/remove), GenerateContent
    // regenerates the ENTIRE stream, destroying TJ kerning even for untouched
    // text objects.
    //
    // FIX: Pre-split every multi-word text object into per-word fragments.
    // Each word gets its own absolute Tm position (from FPDFText_GetCharOrigin),
    // so inter-word spacing survives GenerateContent's flat Tj serialisation.
    // Redacted words are simply omitted; partially-redacted words are fissioned
    // at the character level as before.

    // Helper: build a TextFragment from a contiguous run of char indices.
    // Returns true if a valid fragment was produced.
    auto buildFragment = [&](const std::pmr::vector<int>& run, const FS_MATRIX& origMatrix,
                             const FS_MATRIX& toPage, TextFragment& outFrag) -> bool {
        if (run.empty()) return false;

        // Find first printable non-space character for positioning.
        size_t firstNonWS = 0;
        while (firstNonWS < run.size()) {
            unsigned int uni = FPDFText_GetUnicode(textPage, run[firstNonWS]);
            if (uni > 0x20 && uni != 0xA0) break;
            firstNonWS++;
        }
        if (firstNonWS >= run.size()) return false;

        // Collect text starting from first printable char.
        std::pmr::wstring ws(mr);
        for (size_t i = firstNonWS; i < run.size(); i++) {
            unsigned int uni = FPDFText_GetUnicode(textPage, run[i]);
            if (uni >= 0x20) ws += static_cast<wchar_t>(uni);
        }
        if (ws.empty()) return false;

        ws = decomposeLigatures(ws);

        outFrag.utf16 = wstring_to_utf16le(ws, mr);
        outFrag.matrix = origMatrix;
        double fx, fy;
        if (FPDFText_GetCharOrigin(textPage, run[firstNonWS], &fx, &fy)) {
            outFrag.matrix.e = static_cast<float>(fx);
            outFrag.matrix.f = static_cast<float>(fy);
        } else {
            // Fragments are placed at page level, so the fallback origin must be
            // page space too; origMatrix e,f are container-local for form children.
            FS_MATRIX full = concatMatrix(origMatrix, toPage);
            outFrag.matrix.e = full.e;
            outFrag.matrix.f = full.f;
        }
        return true;
    };

    std::pmr::vector<FissionPlan> plans(mr);
    std::pmr::set<FPDF_PAGEOBJECT> objsToDestroy(mr);

    // Form children handled here must be skipped by the geometric pass in step 6,
    // or the same object gets destroyed twice.
    std::pmr::set<FPDF_PAGEOBJECT> formChildrenClaimed(mr);

    for (size_t oi = 0; oi < allObjs.size(); ++oi) {
        const auto& chars = objChars[oi];
        if (chars.empty()) continue;
        const ObjRef& ref = allObjs[oi];
        if (cropMode && ref.parentForm) continue;  // crop descent (step 6) owns form children
        FPDF_PAGEOBJECT obj = ref.obj;
        if (FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) continue;

        // Check redaction status for this object
        bool anyRedacted = false;
        bool allRedacted = true;
        for (int ci : chars) {
            if (redactSet.count(ci)) {
                anyRedacted = true;
            } else {
                allRedacted = false;
            }
        }

        // Fully contained in redaction -> simple removal
        if (allRedacted) {
            objsToDestroy.insert(obj);
            if (ref.parentForm) formChildrenClaimed.insert(obj);
            continue;
        }

        // Check if this object has multiple "words" (generated-space boundaries).
        // If it does, we must split it so GenerateContent preserves word spacing.
        bool hasMultipleWords = false;
        bool inWord = false;
        int wordCount = 0;
        for (int ci : chars) {
            bool isGenSpace = false;
            if (charInfo[ci].isGenerated) {
                unsigned int uni = FPDFText_GetUnicode(textPage, ci);
                if (uni == 0x20 || uni == 0xA0 || uni == 0) isGenSpace = true;
            }
            if (isGenSpace) {
                inWord = false;
            } else {
                if (!inWord) {
                    wordCount++;
                    inWord = true;
                }
            }
        }
        hasMultipleWords = (wordCount > 1);

        // Skip single-word objects that have no redacted chars - they don't
        // need splitting and their single Tj is fine.
        // Form children with no redacted chars are also skipped even when
        // multi-word: pre-splitting them here would destroy + recreate the
        // child at page level, which the form-descent fix (main) only needs
        // for objects that actually contain redacted chars. Leaving intact
        // multi-word form children in place preserves their text exactly.
        if (!anyRedacted && (!hasMultipleWords || ref.parentForm)) continue;

        // Build per-word fragments, respecting both word boundaries AND
        // redaction boundaries.
        FissionPlan plan(mr);
        plan.originalObj = obj;
        plan.parentForm = ref.parentForm;
        plan.toPage = ref.toPage;
        plan.removeEntirely = false;
        plan.font = FPDFTextObj_GetFont(obj);
        if (!plan.font) continue;  // cannot fission without a font
        FPDFTextObj_GetFontSize(obj, &plan.fontSize);
        plan.renderMode = FPDFTextObj_GetTextRenderMode(obj);

        FPDFPageObj_GetFillColor(obj, &plan.fillR, &plan.fillG, &plan.fillB, &plan.fillA);
        plan.hasStroke = FPDFPageObj_GetStrokeColor(obj, &plan.strokeR, &plan.strokeG,
                                                    &plan.strokeB, &plan.strokeA);

        FS_MATRIX originalMatrix;
        FPDFPageObj_GetMatrix(obj, &originalMatrix);

        // Walk chars, splitting at word boundaries and redaction boundaries.
        // Each contiguous run of non-redacted, non-space chars becomes a
        // fragment (typically one word).
        std::pmr::vector<int> currentRun(mr);

        auto flushRun = [&]() {
            if (currentRun.empty()) return;
            TextFragment frag(mr);
            if (buildFragment(currentRun, originalMatrix, ref.toPage, frag)) {
                plan.fragments.push_back(std::move(frag));
            }
            currentRun.clear();
        };

        for (int ci : chars) {
            bool isRedacted = redactSet.count(ci) > 0;

            // Generated spaces/nulls -> word boundary -> flush
            bool isGenSpace = false;
            if (charInfo[ci].isGenerated) {
                unsigned int uni = FPDFText_GetUnicode(textPage, ci);
                if (uni == 0x20 || uni == 0xA0 || uni == 0) isGenSpace = true;
            }

            if (isRedacted || isGenSpace) {
                flushRun();
            } else {
                currentRun.push_back(ci);
            }
        }
        flushRun();

        // Only plan replacement if we actually produced fragments
        // (and the object needs it: redacted chars or multiple words).
        // If some chars were redacted but every surviving run is whitespace
        // (spaces between redacted words), there is no visible text left, so the
        // object can be dropped entirely rather than kept with redacted glyphs.
        if (!plan.fragments.empty()) {
            if (ref.parentForm) formChildrenClaimed.insert(obj);
            plans.push_back(std::move(plan));
        } else if (anyRedacted) {
            if (ref.parentForm) formChildrenClaimed.insert(obj);
            objsToDestroy.insert(obj);
        }
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

    auto extractSubpaths = [](FPDF_PAGEOBJECT path, int segCount,
                              std::pmr::memory_resource* mr) -> std::pmr::vector<Subpath> {
        std::pmr::vector<Subpath> subpaths(mr);
        Subpath current = {0, 0, 1e9f, 1e9f, -1e9f, -1e9f};
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
                current = {s, 0, 1e9f, 1e9f, -1e9f, -1e9f};
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
        float tMinX = 1e9f, tMinY = 1e9f, tMaxX = -1e9f, tMaxY = -1e9f;
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

    // Recursive form XObject redaction: removes child objects inside redaction
    // rects, accounting for the cumulative transform from form-local to page space.
    std::function<bool(FPDF_PAGEOBJECT formObj, const FS_MATRIX& parentToPage)> redactFormContents =
        [&](FPDF_PAGEOBJECT formObj, const FS_MATRIX& parentToPage) -> bool {
        int childCount = FPDFFormObj_CountObjects(formObj);
        if (childCount <= 0) return false;

        bool changed = false;
        // Iterate in reverse for safe removal
        for (int ci = childCount - 1; ci >= 0; ci--) {
            FPDF_PAGEOBJECT child = FPDFFormObj_GetObject(formObj, ci);
            if (!child) continue;
            if (formChildrenClaimed.count(child)) continue;  // char-level fission owns this one

            int childType = FPDFPageObj_GetType(child);

            float cl, cb, cr, ct;
            if (!FPDFPageObj_GetBounds(child, &cl, &cb, &cr, &ct)) continue;

            // Transform child bounds through the parent-to-page matrix
            float corners[4][2] = {{cl, cb}, {cr, cb}, {cr, ct}, {cl, ct}};
            float tMinX = 1e9f, tMinY = 1e9f, tMaxX = -1e9f, tMaxY = -1e9f;
            for (auto& c : corners) {
                float tx = parentToPage.a * c[0] + parentToPage.c * c[1] + parentToPage.e;
                float ty = parentToPage.b * c[0] + parentToPage.d * c[1] + parentToPage.f;
                if (tx < tMinX) tMinX = tx;
                if (ty < tMinY) tMinY = ty;
                if (tx > tMaxX) tMaxX = tx;
                if (ty > tMaxY) tMaxY = ty;
            }

            // Check overlap with any match bbox
            bool shouldRemove = false;
            for (auto& m : matches) {
                if (childType == FPDF_PAGEOBJ_TEXT || childType == FPDF_PAGEOBJ_IMAGE ||
                    childType == FPDF_PAGEOBJ_PATH || childType == FPDF_PAGEOBJ_SHADING) {
                    if (isFullyContained(tMinX, tMinY, tMaxX, tMaxY, m.bboxL, m.bboxB, m.bboxR,
                                         m.bboxT) ||
                        overlapRatio(tMinX, tMinY, tMaxX, tMaxY, m.bboxL, m.bboxB, m.bboxR,
                                     m.bboxT) > 0.70f) {
                        shouldRemove = true;
                        break;
                    }
                }
            }

            if (shouldRemove) {
                FPDFFormObj_RemoveObject(formObj, child);
                FPDFPageObj_Destroy(child);
                changed = true;
                // Re-fetch count since we removed an object
                childCount = FPDFFormObj_CountObjects(formObj);
                ci = childCount;  // will decrement to childCount-1
                continue;
            }

            // Recurse into nested form objects
            if (childType == FPDF_PAGEOBJ_FORM) {
                FS_MATRIX childMatrix;
                if (FPDFPageObj_GetMatrix(child, &childMatrix)) {
                    // Concatenate: child-to-page = childMatrix * parentToPage
                    FS_MATRIX combined;
                    combined.a = childMatrix.a * parentToPage.a + childMatrix.b * parentToPage.c;
                    combined.b = childMatrix.a * parentToPage.b + childMatrix.b * parentToPage.d;
                    combined.c = childMatrix.c * parentToPage.a + childMatrix.d * parentToPage.c;
                    combined.d = childMatrix.c * parentToPage.b + childMatrix.d * parentToPage.d;
                    combined.e = childMatrix.e * parentToPage.a + childMatrix.f * parentToPage.c +
                                 parentToPage.e;
                    combined.f = childMatrix.e * parentToPage.b + childMatrix.f * parentToPage.d +
                                 parentToPage.f;

                    if (redactFormContents(child, combined)) changed = true;
                }
            }
        }
        return changed;
    };

    // FreeType font cache: avoid re-loading font data for every fragment.
#ifdef JPDFIUM_HAS_FREETYPE
    struct FtFontCache {
        std::pmr::unordered_map<uint32_t, uint32_t> unicodeToGid;
        bool valid = false;

        explicit FtFontCache(std::pmr::memory_resource* mr) : unicodeToGid(mr) {}
    };
    std::pmr::unordered_map<uintptr_t, FtFontCache> ftCache(mr);

    auto getFtMapping = [&](FPDF_FONT font) -> const FtFontCache& {
        uintptr_t key = reinterpret_cast<uintptr_t>(font);
        auto it = ftCache.find(key);
        if (it != ftCache.end()) return it->second;

        auto [eit, inserted] = ftCache.try_emplace(key, mr);
        FtFontCache& cache = eit->second;
        size_t buflen = 0;
        if (FPDFFont_GetFontData(font, nullptr, 0, &buflen) && buflen > 0) {
            std::pmr::vector<uint8_t> fontData(buflen, mr);
            size_t actual = 0;
            if (FPDFFont_GetFontData(font, fontData.data(), buflen, &actual) && actual > 0) {
                ensureFreeTypeInit();
                FT_Face face;
                if (FT_New_Memory_Face(g_ft_lib, fontData.data(), static_cast<FT_Long>(actual), 0,
                                       &face) == 0) {
                    // Select a Unicode cmap if available
                    for (int cm = 0; cm < face->num_charmaps; cm++) {
                        if (face->charmaps[cm]->encoding == FT_ENCODING_UNICODE) {
                            FT_Set_Charmap(face, face->charmaps[cm]);
                            break;
                        }
                    }
                    FT_UInt gid;
                    FT_ULong charcode = FT_Get_First_Char(face, &gid);
                    while (gid != 0) {
                        cache.unicodeToGid[static_cast<uint32_t>(charcode)] = gid;
                        charcode = FT_Get_Next_Char(face, charcode, &gid);
                    }
                    cache.valid = !cache.unicodeToGid.empty();
                    FT_Done_Face(face);
                }
            }
        }
        return cache;
    };
#endif

    // Materialise a fission plan's text fragments as real FPDF_PAGEOBJECT text
    // objects. Shared by the top-level text path and crop-mode form text so the
    // three encoding strategies (SetText / FreeType GID / WinAnsi) stay in one
    // place. On success the created objects are appended to createdObjs and true
    // is returned; the caller inserts them and removes the original.
    auto materialiseFragments = [&](const FissionPlan& plan,
                                    std::pmr::vector<FPDF_PAGEOBJECT>& createdObjs) -> bool {
        bool allOk = true;
        for (auto& frag : plan.fragments) {
            if (frag.utf16.size() <= 1) continue;  // skip null-only

            FPDF_PAGEOBJECT fragObj = FPDFPageObj_CreateTextObj(doc, plan.font, plan.fontSize);
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

            auto resetFragObj = [&]() -> bool {
                FPDFPageObj_Destroy(fragObj);
                fragObj = FPDFPageObj_CreateTextObj(doc, plan.font, plan.fontSize);
                return fragObj != nullptr;
            };

            FPDF_BOOL textOk = false;
            bool boundsOk = false;

            // Strategy A: SetText (Unicode -> font's CharCodeFromUnicode)
            textOk =
                FPDFText_SetText(fragObj, reinterpret_cast<FPDF_WIDESTRING>(frag.utf16.data()));
            if (textOk) boundsOk = boundsValid(fragObj);

#ifdef JPDFIUM_HAS_FREETYPE
            // Strategy B: FreeType GID-based SetCharcodes
            if (!textOk || !boundsOk) {
                if (textOk && !boundsOk) {
                    if (!resetFragObj()) {
                        allOk = false;
                        break;
                    }
                }
                const auto& ftInfo = getFtMapping(plan.font);
                if (ftInfo.valid) {
                    std::pmr::vector<uint32_t> codes(mr);
                    bool allMapped = true;
                    for (size_t i = 0; i + 1 < frag.utf16.size(); i++) {
                        auto git = ftInfo.unicodeToGid.find(static_cast<uint32_t>(frag.utf16[i]));
                        if (git != ftInfo.unicodeToGid.end() && git->second != 0) {
                            codes.push_back(git->second);
                        } else {
                            allMapped = false;
                            break;
                        }
                    }
                    if (allMapped && !codes.empty()) {
                        textOk = FPDFText_SetCharcodes(fragObj, codes.data(), codes.size());
                        if (textOk) boundsOk = boundsValid(fragObj);
                    }
                }
            }
#endif

            // Strategy C: WinAnsi SetCharcodes
            if (!textOk || !boundsOk) {
                if (textOk && !boundsOk) {
                    if (!resetFragObj()) {
                        allOk = false;
                        break;
                    }
                }
                std::pmr::vector<uint32_t> codes(mr);
                bool allMappable = true;
                for (size_t i = 0; i + 1 < frag.utf16.size(); i++) {
                    uint32_t code = unicodeToWinAnsiCharcode(frag.utf16[i]);
                    if (code != 0) {
                        codes.push_back(code);
                    } else {
                        allMappable = false;
                        break;
                    }
                }
                if (allMappable && !codes.empty()) {
                    textOk = FPDFText_SetCharcodes(fragObj, codes.data(), codes.size());
                    if (textOk) boundsOk = boundsValid(fragObj);
                }
            }

            if (!textOk || !boundsOk) {
                FPDFPageObj_Destroy(fragObj);
                allOk = false;
                break;
            }

            // Fragments always go back at page level. For a form child the a,b,c,d part is
            // form-local, so fold in the form transform; e,f already come from the page-space
            // char origin and must not be transformed again.
            FS_MATRIX placed = frag.matrix;
            if (plan.parentForm) {
                FS_MATRIX linear = concatMatrix(frag.matrix, plan.toPage);
                placed.a = linear.a;
                placed.b = linear.b;
                placed.c = linear.c;
                placed.d = linear.d;
            }
            FPDFPageObj_SetMatrix(fragObj, &placed);
            FPDFTextObj_SetTextRenderMode(fragObj, plan.renderMode);

            // Restore original text colors
            FPDFPageObj_SetFillColor(fragObj, plan.fillR, plan.fillG, plan.fillB, plan.fillA);
            if (plan.hasStroke) {
                FPDFPageObj_SetStrokeColor(fragObj, plan.strokeR, plan.strokeG, plan.strokeB,
                                           plan.strokeA);
            }

            createdObjs.push_back(fragObj);
        }
        return allOk;
    };

    // Crop-mode form XObject descent (ANALYSIS ONLY - no text-object mutations
    // happen here). A form that overlaps the crop rect is kept, but its
    // children whose transformed bounds lie entirely OUTSIDE the crop rect are
    // recorded for physical removal. Straddling TEXT children are fissioned at
    // character level: the plan is recorded and executed in the mutation phase
    // below, where surviving glyphs are re-created as page-level text pinned to
    // their exact page-space origins and inserted at the form's own z-order
    // anchor (FPDFPage_InsertObjectAtIndex), so paint order is preserved.
    // Straddling non-text children are kept and clipped by the page CropBox
    // (Ghostscript crop-and-clip semantics) - the visible part of a picture is
    // never dropped.
    //
    // Deferring all text-object removal until every text-page query has run is
    // mandatory: per the PDFium contract, removing a FPDF_PAGEOBJ_TEXT object
    // invalidates all FPDF_TEXTPAGE handles for the page, and the descent still
    // needs char origins for sibling children.
    struct FormPlan {
        FPDF_PAGEOBJECT formObj;  // owning form XObject
        FPDF_PAGEOBJECT child;    // text child to fission/remove
        FissionPlan plan;         // fission plan (empty fragments = remove entirely)
        size_t pageAnchorIdx;     // z-order anchor: the page-level form's object index
        bool removeEntirely = false;

        FormPlan(FPDF_PAGEOBJECT form, FPDF_PAGEOBJECT ch, FissionPlan pl, size_t anchor)
            : formObj(form), child(ch), plan(std::move(pl)), pageAnchorIdx(anchor) {
            removeEntirely = plan.fragments.empty();
        }
    };

    std::pmr::vector<FormPlan> formPlans(mr);

    std::function<void(FPDF_PAGEOBJECT formObj, const FS_MATRIX& parentToPage,
                       size_t pageAnchorIdx)>
        cropFormContents = [&](FPDF_PAGEOBJECT formObj, const FS_MATRIX& parentToPage,
                               size_t pageAnchorIdx) {
            int childCount = FPDFFormObj_CountObjects(formObj);
            std::pmr::vector<FPDF_PAGEOBJECT> childrenToRemove(mr);
            childrenToRemove.reserve(static_cast<size_t>(childCount));

            for (int ci = 0; ci < childCount; ++ci) {
                FPDF_PAGEOBJECT child = FPDFFormObj_GetObject(formObj, ci);
                if (!child) continue;
                int childType = FPDFPageObj_GetType(child);

                float cl, cb, cr, ct;
                if (!FPDFPageObj_GetBounds(child, &cl, &cb, &cr, &ct)) continue;

                float corners[4][2] = {{cl, cb}, {cr, cb}, {cr, ct}, {cl, ct}};
                float tMinX = 1e9f, tMinY = 1e9f, tMaxX = -1e9f, tMaxY = -1e9f;
                for (auto& c : corners) {
                    float tx = parentToPage.a * c[0] + parentToPage.c * c[1] + parentToPage.e;
                    float ty = parentToPage.b * c[0] + parentToPage.d * c[1] + parentToPage.f;
                    if (tx < tMinX) tMinX = tx;
                    if (ty < tMinY) tMinY = ty;
                    if (tx > tMaxX) tMaxX = tx;
                    if (ty > tMaxY) tMaxY = ty;
                }

                const TextMatch& crop = matches[0];
                if (!rectsOverlap(tMinX, tMinY, tMaxX, tMaxY, crop.bboxL, crop.bboxB, crop.bboxR,
                                  crop.bboxT)) {
                    childrenToRemove.push_back(child);
                    continue;
                }

                // Straddling TEXT child: character-level fission. The child's
                // page-space matrix is the form-chain composed with its local
                // matrix; fragments are pinned to the exact page-space char origins
                // returned by FPDFText_GetCharOrigin, so re-created glyphs land
                // where they were even though they move out of the form.
                if (childType == FPDF_PAGEOBJ_TEXT) {
                    auto cit = objToChars.find(reinterpret_cast<uintptr_t>(child));
                    if (cit == objToChars.end()) continue;
                    const auto& childChars = cit->second;

                    bool anyRed = false;
                    for (int cj : childChars) {
                        if (redactSet.count(cj)) {
                            anyRed = true;
                            break;
                        }
                    }
                    if (!anyRed) continue;

                    FS_MATRIX childMatrix;
                    FPDFPageObj_GetMatrix(child, &childMatrix);
                    FS_MATRIX pageMatrix;
                    pageMatrix.a = parentToPage.a * childMatrix.a + parentToPage.b * childMatrix.c;
                    pageMatrix.b = parentToPage.a * childMatrix.b + parentToPage.b * childMatrix.d;
                    pageMatrix.c = parentToPage.c * childMatrix.a + parentToPage.d * childMatrix.c;
                    pageMatrix.d = parentToPage.c * childMatrix.b + parentToPage.d * childMatrix.d;
                    pageMatrix.e = parentToPage.e * childMatrix.a + parentToPage.f * childMatrix.c +
                                   childMatrix.e;
                    pageMatrix.f = parentToPage.e * childMatrix.b + parentToPage.f * childMatrix.d +
                                   childMatrix.f;

                    FissionPlan plan(mr);
                    plan.originalObj = child;
                    plan.removeEntirely = false;
                    plan.font = FPDFTextObj_GetFont(child);
                    if (!plan.font) continue;  // cannot fission without a font

                    FPDFTextObj_GetFontSize(child, &plan.fontSize);
                    plan.renderMode = FPDFTextObj_GetTextRenderMode(child);
                    FPDFPageObj_GetFillColor(child, &plan.fillR, &plan.fillG, &plan.fillB,
                                             &plan.fillA);
                    plan.hasStroke = FPDFPageObj_GetStrokeColor(child, &plan.strokeR, &plan.strokeG,
                                                                &plan.strokeB, &plan.strokeA);

                    std::pmr::vector<int> currentRun(mr);
                    auto flushRun = [&]() {
                        if (currentRun.empty()) return;
                        TextFragment frag(mr);
                        // pageMatrix is already fully composed to page space, so the
                        // fallback-origin helper treats the transform as identity.
                        if (buildFragment(currentRun, pageMatrix, identityMatrix, frag)) {
                            plan.fragments.push_back(std::move(frag));
                        }
                        currentRun.clear();
                    };
                    for (int cj : childChars) {
                        bool isRed = redactSet.count(cj) > 0;
                        bool isGenSpace = false;
                        if (charInfo[cj].isGenerated) {
                            unsigned int uni = FPDFText_GetUnicode(textPage, cj);
                            if (uni == 0x20 || uni == 0xA0 || uni == 0) isGenSpace = true;
                        }
                        if (isRed || isGenSpace) {
                            flushRun();
                        } else {
                            currentRun.push_back(cj);
                        }
                    }
                    flushRun();

                    formPlans.emplace_back(formObj, child, std::move(plan), pageAnchorIdx);
                    continue;
                }

                // Recurse into nested forms (child-local -> page-space matrix chain).
                // The z-order anchor stays the page-level form's index: nested-form
                // content paints at the outer form's position in the page.
                if (childType == FPDF_PAGEOBJ_FORM) {
                    FS_MATRIX childMatrix;
                    if (FPDFPageObj_GetMatrix(child, &childMatrix)) {
                        FS_MATRIX combined;
                        combined.a =
                            childMatrix.a * parentToPage.a + childMatrix.b * parentToPage.c;
                        combined.b =
                            childMatrix.a * parentToPage.b + childMatrix.b * parentToPage.d;
                        combined.c =
                            childMatrix.c * parentToPage.a + childMatrix.d * parentToPage.c;
                        combined.d =
                            childMatrix.c * parentToPage.b + childMatrix.d * parentToPage.d;
                        combined.e = childMatrix.e * parentToPage.a +
                                     childMatrix.f * parentToPage.c + parentToPage.e;
                        combined.f = childMatrix.e * parentToPage.b +
                                     childMatrix.f * parentToPage.d + parentToPage.f;
                        cropFormContents(child, combined, pageAnchorIdx);
                    }
                }
            }

            // Removal pass: non-text children only (text-page handles stay valid).
            for (FPDF_PAGEOBJECT child : childrenToRemove) {
                FPDFFormObj_RemoveObject(formObj, child);
                FPDFPageObj_Destroy(child);
            }
        };

    for (int i = objCount - 1; i >= 0; --i) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        int type = FPDFPageObj_GetType(obj);

        // Skip text objects - handled by fission above
        if (type == FPDF_PAGEOBJ_TEXT) continue;

        float ol, ob, or_, ot;
        if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) continue;

        // Crop mode: matches[0].bbox is the keep region (the crop rectangle).
        // Objects that overlap it at all are preserved (the page CropBox clips
        // them visually at the boundary); only objects lying ENTIRELY outside
        // are physically removed. Subpath rebuild / >70% rules do not apply -
        // the boundary clip handles partial overlaps safely. Form XObjects that
        // overlap are descended into so fully-outside children are removed too.
        if (cropMode) {
            if (!rectsOverlap(ol, ob, or_, ot, matches[0].bboxL, matches[0].bboxB, matches[0].bboxR,
                              matches[0].bboxT)) {
                objsToDestroy.insert(obj);
            } else if (type == FPDF_PAGEOBJ_FORM) {
                FS_MATRIX formMatrix;
                if (FPDFPageObj_GetMatrix(obj, &formMatrix)) {
                    cropFormContents(obj, formMatrix, static_cast<size_t>(i));
                }
            }
            continue;
        }

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
            // Image: remove if fully contained or >70% overlap
            for (auto& m : matches) {
                if (isFullyContained(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) ||
                    overlapRatio(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) > 0.70f) {
                    objsToDestroy.insert(obj);
                    break;
                }
            }
        } else if (type == FPDF_PAGEOBJ_PATH) {
            // Path: subpath-level granularity
            int segCount = FPDFPath_CountSegments(obj);
            if (segCount <= 0) continue;

            FS_MATRIX pathMatrix;
            if (!FPDFPageObj_GetMatrix(obj, &pathMatrix)) continue;

            auto subpaths = extractSubpaths(obj, segCount, mr);
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
                    int fillMode = 0;
                    FPDF_BOOL stroke = 0;
                    FPDFPath_GetDrawMode(obj, &fillMode, &stroke);
                    FPDFPath_SetDrawMode(newPath, fillMode, stroke);

                    // Insert at the original's position so paint order is
                    // preserved (the loop below iterates downward, so inserts
                    // at index i never disturb indices < i).
                    FPDFPage_InsertObjectAtIndex(page, newPath, static_cast<size_t>(i));
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
            // Form XObject: first check if entire form is inside a redaction rect
            bool formFullyInside = false;
            for (auto& m : matches) {
                if (isFullyContained(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) ||
                    overlapRatio(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) > 0.70f) {
                    formFullyInside = true;
                    break;
                }
            }

            if (formFullyInside) {
                objsToDestroy.insert(obj);
            } else {
                // Recursively redact form contents
                FS_MATRIX formMatrix;
                if (FPDFPageObj_GetMatrix(obj, &formMatrix)) {
                    // Identity as parent since form's own matrix is the first transform
                    redactFormContents(obj, formMatrix);
                }
            }
        }
    }

    // Modification phase

    // 6b. Execute form fission plans collected during the crop-mode descent.
    //     No text-page queries past this point - FPDF_TEXTPAGE handles are
    //     invalidated once any text object is removed. Fragments are inserted
    //     at the form's own page-level z-order anchor so re-created glyphs
    //     keep the form's paint position instead of jumping on top of content
    //     drawn after the form.
    std::pmr::unordered_map<size_t, size_t> formInsertOffsets(mr);
    for (auto& fp : formPlans) {
        std::pmr::vector<FPDF_PAGEOBJECT> createdObjs(mr);
        bool allOk = materialiseFragments(fp.plan, createdObjs);

        if (allOk && !createdObjs.empty()) {
            size_t& off = formInsertOffsets[fp.pageAnchorIdx];
            std::pmr::vector<FPDF_PAGEOBJECT> inserted(mr);
            bool allInserted = true;
            for (auto* fo : createdObjs) {
                if (!FPDFPage_InsertObjectAtIndex(page, fo, fp.pageAnchorIdx + off)) {
                    FPDFPageObj_Destroy(fo);
                    allInserted = false;
                    break;
                }
                inserted.push_back(fo);
                ++off;
            }
            if (allInserted) {
                FPDFFormObj_RemoveObject(fp.formObj, fp.child);
                FPDFPageObj_Destroy(fp.child);
                continue;
            }
            // Roll back the partial insert so the child can be kept whole
            // instead of being lost.
            for (auto* fo : inserted) {
                FPDFPage_RemoveObject(page, fo);
                FPDFPageObj_Destroy(fo);
                --off;
            }
        }

        for (auto* fo : createdObjs) FPDFPageObj_Destroy(fo);
        if (fp.removeEntirely) {
            // Redacted chars exist but only whitespace survives - no visible
            // glyphs, drop the whole child.
            FPDFFormObj_RemoveObject(fp.formObj, fp.child);
            FPDFPageObj_Destroy(fp.child);
        }
        // Otherwise the child is kept whole; the page CropBox clips the
        // out-of-crop part (Ghostscript-equivalent clip fallback).
    }

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
    //
    //    Fragments are inserted at the ORIGINAL object's own position via
    //    FPDFPage_InsertObjectAtIndex (never appended), so paint order is
    //    preserved for straddling text that other objects draw over.
    std::pmr::set<FPDF_PAGEOBJECT> fissionAttempted(mr);
    std::pmr::set<FPDF_PAGEOBJECT> createdFragments(mr);
    std::pmr::unordered_map<uintptr_t, std::pmr::vector<FPDF_PAGEOBJECT>> fragMap(mr);

    for (auto& plan : plans) {
        fissionAttempted.insert(plan.originalObj);
        std::pmr::vector<FPDF_PAGEOBJECT> createdObjs(mr);
        bool allOk = materialiseFragments(plan, createdObjs);

        if (allOk) {
            if (plan.parentForm) {
                // Form-child fragments always go back at page level (their matrix
                // is already folded to page space). The z-order pass below only
                // matches page-level originals, so insert them directly.
                for (auto* fo : createdObjs) {
                    FPDFPage_InsertObject(page, fo);
                    createdFragments.insert(fo);
                }
                objsToDestroy.insert(plan.originalObj);
            } else {
                // Page-level original: commit (insertion happens in the z-order
                // pass below so paint order is preserved).
                for (auto* fo : createdObjs) {
                    createdFragments.insert(fo);
                }
                auto& bucket = fragMap[reinterpret_cast<uintptr_t>(plan.originalObj)];
                bucket.insert(bucket.end(), createdObjs.begin(), createdObjs.end());
                objsToDestroy.insert(plan.originalObj);
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

    // 7b. Insert the fragments at their originals' positions. Walking the
    //     object list backward, each insert at index i only shifts objects at
    //     indices >= i, all of which this pass has already visited, so the
    //     originals of not-yet-visited objects keep their indices.
    if (!fragMap.empty()) {
        int curObjCount = FPDFPage_CountObjects(page);
        for (int i = curObjCount - 1; i >= 0; --i) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
            auto it = fragMap.find(reinterpret_cast<uintptr_t>(obj));
            if (it == fragMap.end()) continue;
            const auto& frags = it->second;
            for (size_t j = 0; j < frags.size(); ++j) {
                if (!FPDFPage_InsertObjectAtIndex(page, frags[j], static_cast<size_t>(i) + j)) {
                    // Extremely unlikely: index arithmetic guarantees validity.
                    // Roll back the whole batch and keep the original instead
                    // of losing visible text.
                    for (size_t k = 0; k < j; ++k) {
                        FPDFPage_RemoveObject(page, frags[k]);
                        FPDFPageObj_Destroy(frags[k]);
                        createdFragments.erase(frags[k]);
                    }
                    FPDFPageObj_Destroy(frags[j]);
                    createdFragments.erase(frags[j]);
                    objsToDestroy.erase(obj);
                    break;
                }
            }
        }
    }

    // 8. Fallback: remove text objects that are >70% inside a match bbox but
    //    were NOT caught by the char-to-object mapping (e.g. Form XObject text,
    //    chars with degenerate bounding boxes).
    //    Skip objects that were already handled by fission (even if fission
    //    failed - in that case the original is intentionally preserved and
    //    the black box provides visual cover) and any fragment objects the
    //    fission just created.
    //    In crop mode the redact-side fallback is inverted (it would remove
    //    text objects inside the keep region); instead remove text objects that
    //    lie entirely OUTSIDE the keep region and were unreachable by fission.
    const int liveObjCount = FPDFPage_CountObjects(page);
    if (cropMode) {
        for (int i = liveObjCount - 1; i >= 0; --i) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
            if (objsToDestroy.count(obj)) continue;     // already marked
            if (fissionAttempted.count(obj)) continue;  // fission handled it
            if (createdFragments.count(obj)) continue;  // new fragment
            if (FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) continue;

            float ol, ob, or_, ot;
            if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) continue;

            if (!rectsOverlap(ol, ob, or_, ot, matches[0].bboxL, matches[0].bboxB, matches[0].bboxR,
                              matches[0].bboxT)) {
                objsToDestroy.insert(obj);
            }
        }
    } else {
        for (int i = liveObjCount - 1; i >= 0; --i) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
            if (objsToDestroy.count(obj)) continue;     // already marked
            if (fissionAttempted.count(obj)) continue;  // fission handled it
            if (createdFragments.count(obj)) continue;  // new fragment
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
    }

    // 9. Remove all marked objects, from their own container.
    // A marked ancestor form frees its whole subtree when destroyed, so a child
    // marked alongside it must be skipped or removal touches freed memory.
    auto ancestorMarked = [&](FPDF_PAGEOBJECT obj) -> bool {
        auto it = objPtrToIndex.find(reinterpret_cast<uintptr_t>(obj));
        while (it != objPtrToIndex.end()) {
            FPDF_PAGEOBJECT parent = allObjs[it->second].parentForm;
            if (!parent) return false;
            if (objsToDestroy.count(parent)) return true;
            it = objPtrToIndex.find(reinterpret_cast<uintptr_t>(parent));
        }
        return false;
    };
    for (auto* obj : objsToDestroy) {
        if (ancestorMarked(obj)) continue;

        FPDF_PAGEOBJECT parentForm = nullptr;
        auto pit = objPtrToIndex.find(reinterpret_cast<uintptr_t>(obj));
        if (pit != objPtrToIndex.end()) parentForm = allObjs[pit->second].parentForm;

        if (parentForm) {
            FPDFFormObj_RemoveObject(parentForm, obj);
        } else {
            FPDFPage_RemoveObject(page, obj);
        }
        FPDFPageObj_Destroy(obj);
    }

    // 10. Paint filled rectangles for all match regions (visual cover).
    //     Skipped in crop mode - out-of-crop content is physically removed, so
    //     there is nothing to cover and no black boxes must appear.
    if (!cropMode) {
        for (auto& m : matches) {
            FPDF_PAGEOBJECT rect =
                FPDFPageObj_CreateNewRect(m.bboxL, m.bboxB, m.bboxR - m.bboxL, m.bboxT - m.bboxB);
            if (!rect) continue;
            FPDFPageObj_SetFillColor(rect, red, grn, blu, 255);
            FPDFPath_SetDrawMode(rect, FPDF_FILLMODE_ALTERNATE, 0);
            FPDFPage_InsertObject(page, rect);
        }
    }

    // 11. Commit to content stream (single call for all modifications)
    return FPDFPage_GenerateContent(page) ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
}

// Helper: run regex over extracted text -> produce TextMatch vector.
// wtext + idxMap must already be populated (see callers).
static void collectRegexMatches(FPDF_TEXTPAGE textPage, const std::wstring& wtext,
                                const std::pmr::vector<int>& idxMap, const std::wregex& wre,
                                float padding, std::pmr::vector<TextMatch>& out) {
    auto it = std::wsregex_iterator(wtext.begin(), wtext.end(), wre);
    auto end = std::wsregex_iterator();

    for (; it != end; ++it) {
        int start = static_cast<int>((*it).position());
        int len = static_cast<int>((*it).length());
        if (len == 0) continue;

        TextMatch tm(out.get_allocator().resource());
        double xmin = 1e9, ymin = 1e9, xmax = -1e9, ymax = -1e9;

        for (int k = start; k < start + len && k < static_cast<int>(idxMap.size()); ++k) {
            int ci = idxMap[k];
            tm.charIndices.push_back(ci);

            double l, r, b, t;
            FPDFText_GetCharBox(textPage, ci, &l, &r, &b, &t);
            if (l < xmin) xmin = l;
            if (b < ymin) ymin = b;
            if (r > xmax) xmax = r;
            if (t > ymax) ymax = t;
        }

        // Apply padding
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
}

// Lifecycle

// Redaction

int32_t jpdfium_redact_region(int64_t page, float x, float y, float w, float h, uint32_t argb,
                              int32_t remove_content) noexcept {
    return jpdfium_guarded([&] {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(w) || !std::isfinite(h) ||
            w <= 0.0f || h <= 0.0f)
            return JPDFIUM_ERR_INVALID;
        return applyRedactRect(pw->page, x, y, w, h, argb, remove_content != 0);
    });
}

// "Hard" crop: physically remove every page object (text,
// image, path, shading, form XObject) whose geometry lies entirely OUTSIDE the
// crop rectangle [x,y,x+w,y+h] (PDF coords, y up).
//
//   - Text: character-level Object Fission - characters whose centre lies
//     outside the crop rect are destroyed; a text object straddling the crop
//     boundary is split so only the glyphs inside the crop area survive, pinned
//     to their exact origins. Boundary glyphs centred inside are kept and
//     clipped visually by the page CropBox.
//   - Images/paths/shading/forms: removed when fully outside the crop rect.
//     Objects that partially overlap are preserved and clipped by the CropBox -
//     this mirrors Ghostscript's crop-and-clip behaviour and never drops the
//     visible part of a picture.
//
// The caller is responsible for setting the page MediaBox/CropBox afterwards
// (see PdfPageGeometry.cropAndRemoveContent). No paint rectangles are emitted.
// Non-finite (NaN/Inf) coordinates or non-positive sizes are rejected with
// JPDFIUM_ERR_INVALID - comparisons with NaN are silently false, which would
// otherwise make the removal logic undefined (remove everything or nothing).
int32_t jpdfium_crop_remove_content(int64_t page, float x, float y, float w, float h) noexcept {
    return jpdfium_guarded([&] {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(w) || !std::isfinite(h) ||
            w <= 0.0f || h <= 0.0f)
            return JPDFIUM_ERR_INVALID;

        ScratchArena arena;
        std::pmr::memory_resource* mr = arena.resource();

        const float cL = x, cB = y, cR = x + w, cT = y + h;

        // Fast pre-check: if EVERY page object's bounding box is fully inside the crop
        // rect, nothing can lie outside it, so return without loading the text page or
        // touching the content stream (the caller then only updates the page boxes).
        // Loading a PDFium text page is comparatively expensive, so this keeps the
        // common "crop that changes nothing" path in the single-digit-microsecond range.
        // An object whose bounds cannot be read is treated as "not proven inside" so the
        // full char-level check below still runs.
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

        // Single match whose bbox is the keep region (crop rect); charIndices are
        // the text-page characters whose placement ORIGIN lies outside it.
        // FPDFText_GetCharOrigin is used instead of FPDFText_GetCharBox because the
        // char bboxes are unreliable on pages with per-glyph Td kerning (they can
        // come back as degenerate multi-hundred-point boxes), while the origin is
        // always the true glyph placement point in page space. Origin-based testing
        // mirrors Ghostscript's boundary clip: glyphs positioned off the cropped
        // page are dropped, glyphs positioned inside are kept (their spill-over is
        // clipped by the page CropBox).
        TextMatch m(mr);
        m.bboxL = cL;
        m.bboxB = cB;
        m.bboxR = cR;
        m.bboxT = cT;

        const int count = FPDFText_CountChars(tp);
        for (int i = 0; i < count; ++i) {
            double ox, oy;
            if (!FPDFText_GetCharOrigin(tp, i, &ox, &oy)) continue;
            if (ox < cL || ox > cR || oy < cB || oy > cT) m.charIndices.push_back(i);
        }

        // Fast path: nothing outside the crop rect at all - return without
        // touching the content stream (avoids an unnecessary GenerateContent that
        // would re-serialize every text object).
        bool anythingToRemove = !m.charIndices.empty();
        if (!anythingToRemove) {
            const int objCount = FPDFPage_CountObjects(pw->page);
            for (int i = 0; i < objCount; ++i) {
                FPDF_PAGEOBJECT obj = FPDFPage_GetObject(pw->page, i);
                if (!obj) continue;
                if (FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_TEXT) continue;
                float ol, ob, or_, ot;
                if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) continue;
                if (!rectsOverlap(ol, ob, or_, ot, cL, cB, cR, cT)) {
                    anythingToRemove = true;
                    break;
                }
            }
        }
        if (!anythingToRemove) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_OK;
        }

        std::pmr::vector<TextMatch> matches(mr);
        matches.push_back(std::move(m));

        int32_t rc =
            objectFissionRedact(pw->doc, pw->page, tp, matches, 0x00000000, true, true, mr);

        FPDFText_ClosePage(tp);
        return rc;
    });
}

int32_t jpdfium_redact_pattern(int64_t page, const char* pattern, uint32_t argb,
                               int32_t remove_content) noexcept {
    return jpdfium_guarded([&] {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_NATIVE;

        ScratchArena arena;
        std::pmr::memory_resource* mr = arena.resource();

        int count = FPDFText_CountChars(tp);

        // Build wide string + index map (skipping null chars)
        std::wstring wtext;
        std::pmr::vector<int> idxMap(mr);
        for (int i = 0; i < count; ++i) {
            unsigned int uni = FPDFText_GetUnicode(tp, i);
            if (uni == 0) continue;
            wtext += static_cast<wchar_t>(uni);
            idxMap.push_back(i);
        }

        // Compile the pattern as a wide regex
        std::wregex wre;
        try {
            wre.assign(utf8_to_wstring(pattern));
        } catch (const std::regex_error&) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_ERR_INVALID;
        }

        // Collect matches with character-level indices
        std::pmr::vector<TextMatch> matches(mr);
        collectRegexMatches(tp, wtext, idxMap, wre, 0.0f, matches);

        if (matches.empty()) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_OK;
        }

        // Apply Object Fission redaction
        int32_t rc = objectFissionRedact(pw->doc, pw->page, tp, matches, argb, remove_content != 0,
                                         false, mr);

        FPDFText_ClosePage(tp);
        return rc;
    });
}

// Flatten

int32_t jpdfium_page_flatten(int64_t page) noexcept {
    return jpdfium_guarded([&] {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        int rc = FPDFPage_Flatten(pw->page, FLAT_NORMALDISPLAY);
        return (rc == FLATTEN_SUCCESS || rc == FLATTEN_NOTHINGTODO) ? JPDFIUM_OK
                                                                    : JPDFIUM_ERR_NATIVE;
    });
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
    return jpdfium_guarded([&] {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        if (!words || wordCount <= 0) {
            if (matchCount) *matchCount = 0;
            return JPDFIUM_OK;
        }

        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_NATIVE;

        ScratchArena arena;
        std::pmr::memory_resource* mr = arena.resource();

        int count = FPDFText_CountChars(tp);

        // Build wide text + index-map (skipping null unicode chars)
        std::wstring wtext;
        std::pmr::vector<int> idxMap(mr);
        for (int i = 0; i < count; ++i) {
            unsigned int uni = FPDFText_GetUnicode(tp, i);
            if (uni == 0) continue;
            wtext += static_cast<wchar_t>(uni);
            idxMap.push_back(i);
        }

        std::pmr::vector<TextMatch> matches(mr);

        // Regex flags: case-insensitive unless explicitly requested
        auto rxFlags = std::regex_constants::ECMAScript;
        if (!caseSensitive) rxFlags |= std::regex_constants::icase;

        for (int wi = 0; wi < wordCount; ++wi) {
            if (!words[wi]) continue;
            std::wstring wpattern;

            if (useRegex) {
                wpattern = utf8_to_wstring(words[wi]);
            } else {
                // Escape regex special characters for literal matching
                std::wstring raw = utf8_to_wstring(words[wi]);
                for (wchar_t ch : raw) {
                    if (ch == L'\\' || ch == L'^' || ch == L'$' || ch == L'.' || ch == L'|' ||
                        ch == L'?' || ch == L'*' || ch == L'+' || ch == L'(' || ch == L')' ||
                        ch == L'[' || ch == L']' || ch == L'{' || ch == L'}') {
                        wpattern += L'\\';
                    }
                    wpattern += ch;
                }
            }

            if (wholeWord) {
                wpattern = L"\\b" + wpattern + L"\\b";
            }

            std::wregex wre;
            try {
                wre.assign(wpattern, rxFlags);
            } catch (const std::regex_error&) {
                continue;  // skip invalid patterns
            }

            collectRegexMatches(tp, wtext, idxMap, wre, padding, matches);
        }

        if (matchCount) *matchCount = static_cast<int32_t>(matches.size());

        if (matches.empty()) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_OK;
        }

        // Apply Object Fission redaction (all matches in one pass)
        int32_t rc = objectFissionRedact(pw->doc, pw->page, tp, matches, argb, remove_content != 0,
                                         false, mr);

        FPDFText_ClosePage(tp);
        return rc;
    });
}

int32_t jpdfium_annot_create_redact(int64_t page, float x, float y, float w, float h, uint32_t argb,
                                    int32_t* annot_index) noexcept {
    return jpdfium_guarded([&] {
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
        return JPDFIUM_OK;
    });
}

int32_t jpdfium_annot_count_redacts(int64_t page, int32_t* count) noexcept {
    return jpdfium_guarded([&] {
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
    });
}

int32_t jpdfium_annot_get_redacts_json(int64_t page, char** json) noexcept {
    return jpdfium_guarded([&] {
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
                       << ",\"w\":" << (rect.right - rect.left)
                       << ",\"h\":" << (rect.top - rect.bottom) << '}';
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
    });
}

int32_t jpdfium_annot_remove_redact(int64_t page, int32_t annot_index) noexcept {
    return jpdfium_guarded([&] {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

        int total = FPDFPage_GetAnnotCount(pw->page);
        if (annot_index < 0 || annot_index >= total) return JPDFIUM_ERR_NOT_FOUND;

        FPDF_ANNOTATION a = FPDFPage_GetAnnot(pw->page, annot_index);
        if (!a) return JPDFIUM_ERR_NOT_FOUND;

        bool isRedact = FPDFAnnot_GetSubtype(a) == FPDF_ANNOT_REDACT;
        FPDFPage_CloseAnnot(a);

        if (!isRedact) return JPDFIUM_ERR_INVALID;

        return FPDFPage_RemoveAnnot(pw->page, annot_index) ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
    });
}

int32_t jpdfium_annot_clear_redacts(int64_t page) noexcept {
    return jpdfium_guarded([&] {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

        // Remove in reverse order to avoid index shifting
        for (int i = FPDFPage_GetAnnotCount(pw->page) - 1; i >= 0; --i) {
            FPDF_ANNOTATION a = FPDFPage_GetAnnot(pw->page, i);
            if (!a) continue;
            bool isRedact = FPDFAnnot_GetSubtype(a) == FPDF_ANNOT_REDACT;
            FPDFPage_CloseAnnot(a);
            if (isRedact) FPDFPage_RemoveAnnot(pw->page, i);
        }
        return JPDFIUM_OK;
    });
}

// Mark phase: find text matches and create REDACT annotations (no content mutation)
int32_t jpdfium_redact_mark_words(int64_t page, const char** words, int32_t wordCount,
                                  float padding, int32_t wholeWord, int32_t useRegex,
                                  int32_t caseSensitive, uint32_t argb,
                                  int32_t* matchCount) noexcept {
    return jpdfium_guarded([&] {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        if (!words || wordCount <= 0) {
            if (matchCount) *matchCount = 0;
            return JPDFIUM_OK;
        }

        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_NATIVE;

        ScratchArena arena;
        std::pmr::memory_resource* mr = arena.resource();

        int count = FPDFText_CountChars(tp);

        // Build wide text + index-map
        std::wstring wtext;
        std::pmr::vector<int> idxMap(mr);
        for (int i = 0; i < count; ++i) {
            unsigned int uni = FPDFText_GetUnicode(tp, i);
            if (uni == 0) continue;
            wtext += static_cast<wchar_t>(uni);
            idxMap.push_back(i);
        }

        std::pmr::vector<TextMatch> matches(mr);
        auto rxFlags = std::regex_constants::ECMAScript;
        if (!caseSensitive) rxFlags |= std::regex_constants::icase;

        for (int wi = 0; wi < wordCount; ++wi) {
            if (!words[wi]) continue;
            std::wstring wpattern;
            if (useRegex) {
                wpattern = utf8_to_wstring(words[wi]);
            } else {
                std::wstring raw = utf8_to_wstring(words[wi]);
                for (wchar_t ch : raw) {
                    if (ch == L'\\' || ch == L'^' || ch == L'$' || ch == L'.' || ch == L'|' ||
                        ch == L'?' || ch == L'*' || ch == L'+' || ch == L'(' || ch == L')' ||
                        ch == L'[' || ch == L']' || ch == L'{' || ch == L'}') {
                        wpattern += L'\\';
                    }
                    wpattern += ch;
                }
            }
            if (wholeWord) wpattern = L"\\b" + wpattern + L"\\b";

            std::wregex wre;
            try {
                wre.assign(wpattern, rxFlags);
            } catch (...) {
                continue;
            }

            collectRegexMatches(tp, wtext, idxMap, wre, padding, matches);
        }

        FPDFText_ClosePage(tp);

        // Create REDACT annotations from matches (zero content mutation)
        unsigned int r = (argb >> 16) & 0xFF;
        unsigned int g = (argb >> 8) & 0xFF;
        unsigned int b = argb & 0xFF;

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
        }

        if (matchCount) *matchCount = static_cast<int32_t>(matches.size());
        return JPDFIUM_OK;
    });
}

// Commit phase: burn all REDACT annotations using Object Fission
int32_t jpdfium_redact_commit(int64_t page, uint32_t argb, int32_t remove_content,
                              int32_t* commitCount) noexcept {
    return jpdfium_guarded([&] {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

        ScratchArena arena;
        std::pmr::memory_resource* mr = arena.resource();

        // Collect all REDACT annotation rects
        int total = FPDFPage_GetAnnotCount(pw->page);
        std::pmr::vector<FS_RECTF> redactRects(mr);
        std::pmr::vector<int> redactIndices(mr);

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

        if (redactRects.empty()) return JPDFIUM_OK;

        // Remove the REDACT annotations in reverse order (before modifying content)
        for (int i = static_cast<int>(redactIndices.size()) - 1; i >= 0; --i) {
            FPDFPage_RemoveAnnot(pw->page, redactIndices[i]);
        }

        // Build TextMatch objects from annotation rects and run Object Fission.
        // Load text page for char-level hit testing.
        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) {
            // Fallback to rect-based redaction if text page fails
            for (auto& r : redactRects) {
                applyRedactRect(pw->page, r.left, r.bottom, r.right - r.left, r.top - r.bottom,
                                argb, remove_content != 0);
            }
            return JPDFIUM_OK;
        }

        int charCount = FPDFText_CountChars(tp);

        // Build TextMatch for each annotation rect by finding intersecting characters
        std::pmr::vector<TextMatch> matches(mr);
        for (auto& ar : redactRects) {
            TextMatch tm(mr);
            tm.bboxL = ar.left;
            tm.bboxB = ar.bottom;
            tm.bboxR = ar.right;
            tm.bboxT = ar.top;

            // Find all characters whose center falls within this rect
            for (int ci = 0; ci < charCount; ++ci) {
                double l, r, b, t;
                if (!FPDFText_GetCharBox(tp, ci, &l, &r, &b, &t)) continue;

                double cx = (l + r) / 2.0;
                double cy = (b + t) / 2.0;

                if (cx >= ar.left && cx <= ar.right && cy >= ar.bottom && cy <= ar.top) {
                    tm.charIndices.push_back(ci);
                }
            }

            matches.push_back(std::move(tm));
        }

        int32_t rc = objectFissionRedact(pw->doc, pw->page, tp, matches, argb, remove_content != 0,
                                         false, mr);

        FPDFText_ClosePage(tp);
        return rc;
    });
}

// Incremental save: writes only changed objects, document stays live
int32_t jpdfium_doc_save_incremental(int64_t doc, uint8_t** data, int64_t* len) noexcept {
    return jpdfium_guarded([&] {
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->doc) return JPDFIUM_ERR_INVALID;

        struct BufWriter : FPDF_FILEWRITE {
            std::vector<uint8_t> buf;
            static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
                auto* bw = static_cast<BufWriter*>(self);
                auto* src = static_cast<const uint8_t*>(data);
                bw->buf.insert(bw->buf.end(), src, src + size);
                return 1;
            }
        } bw;
        bw.version = 1;
        bw.WriteBlock = BufWriter::Write;

        if (!FPDF_SaveAsCopy(w->doc, &bw, FPDF_INCREMENTAL)) return JPDFIUM_ERR_IO;

        size_t sz = bw.buf.size();
        uint8_t* out = static_cast<uint8_t*>(malloc(sz));
        if (!out) return JPDFIUM_ERR_NATIVE;
        memcpy(out, bw.buf.data(), sz);
        *data = out;
        *len = static_cast<int64_t>(sz);
        return JPDFIUM_OK;
    });
}

// Character position extraction (for testing)
