#include "jpdfium.h"
#include "jpdfium_internal.h"

#include <fpdfview.h>
#include <fpdf_save.h>
#include <fpdf_text.h>
#include <fpdf_edit.h>
#include <fpdf_flatten.h>
#include <fpdf_annot.h>

#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <string>
#include <sstream>
#include <vector>
#include <regex>
#include <set>
#include <map>
#include <algorithm>
#include <unordered_map>

// FreeType integration for font-aware charcode mapping.
// When available, provides a third encoding strategy for Object Fission
// that uses the font's actual cmap table to produce correct charcodes
// for CID/Identity-H fonts where PDFium's SetText fails.
#ifdef JPDFIUM_HAS_FREETYPE
#include <ft2build.h>
#include FT_FREETYPE_H
static FT_Library g_ft_lib = nullptr;
static void ensureFreeTypeInit() {
    if (!g_ft_lib) FT_Init_FreeType(&g_ft_lib);
}
#endif

// String helpers

// UTF-8 -> UTF-16LE for FPDFText_FindStart (PDFium expects UTF-16LE, not wchar_t)
static std::vector<uint16_t> utf8_to_utf16le(const char* utf8) {
    std::vector<uint16_t> result;
    const auto* s = reinterpret_cast<const uint8_t*>(utf8);
    while (*s) {
        uint32_t cp;
        if      (*s < 0x80) { cp = *s++; }
        else if (*s < 0xE0) { cp  = (*s++ & 0x1F) << 6;  cp |= (*s++ & 0x3F); }
        else if (*s < 0xF0) { cp  = (*s++ & 0x0F) << 12; cp |= (*s++ & 0x3F) << 6;
                               cp |= (*s++ & 0x3F); }
        else                { cp  = (*s++ & 0x07) << 18; cp |= (*s++ & 0x3F) << 12;
                               cp |= (*s++ & 0x3F) << 6; cp |= (*s++ & 0x3F); }
        if (cp <= 0xFFFF) {
            result.push_back(static_cast<uint16_t>(cp));
        } else {                              // surrogate pair
            cp -= 0x10000;
            result.push_back(static_cast<uint16_t>(0xD800 | (cp >> 10)));
            result.push_back(static_cast<uint16_t>(0xDC00 | (cp & 0x3FF)));
        }
    }
    result.push_back(0);  // null terminator
    return result;
}

// UTF-8 -> std::wstring (wchar_t is 32-bit on Linux/macOS - one code unit per codepoint)
static std::wstring utf8_to_wstring(const char* utf8) {
    std::wstring result;
    const auto* s = reinterpret_cast<const uint8_t*>(utf8);
    while (*s) {
        uint32_t cp;
        if      (*s < 0x80) { cp = *s++; }
        else if (*s < 0xE0) { cp  = (*s++ & 0x1F) << 6;  cp |= (*s++ & 0x3F); }
        else if (*s < 0xF0) { cp  = (*s++ & 0x0F) << 12; cp |= (*s++ & 0x3F) << 6;
                               cp |= (*s++ & 0x3F); }
        else                { cp  = (*s++ & 0x07) << 18; cp |= (*s++ & 0x3F) << 12;
                               cp |= (*s++ & 0x3F) << 6; cp |= (*s++ & 0x3F); }
        result += static_cast<wchar_t>(cp);
    }
    return result;
}

// std::wstring -> UTF-16LE (for FPDFText_SetText on new text objects)
static std::vector<uint16_t> wstring_to_utf16le(const std::wstring& ws) {
    std::vector<uint16_t> result;
    for (wchar_t wc : ws) {
        uint32_t cp = static_cast<uint32_t>(wc);
        if (cp <= 0xFFFF) {
            result.push_back(static_cast<uint16_t>(cp));
        } else {
            cp -= 0x10000;
            result.push_back(static_cast<uint16_t>(0xD800 | (cp >> 10)));
            result.push_back(static_cast<uint16_t>(0xDC00 | (cp & 0x3FF)));
        }
    }
    result.push_back(0); // null terminator
    return result;
}

// Shared redaction primitives

// Check if rectangle A is FULLY contained within rectangle B
// A = [al, ab, ar, at], B = [bl, bb, br, bt] (PDF coords: y up)
static bool isFullyContained(float al, float ab, float ar, float at,
                              float bl, float bb, float br, float bt) {
    return al >= bl && ab >= bb && ar <= br && at <= bt;
}

// Check if two rectangles overlap at all
static bool rectsOverlap(float al, float ab, float ar, float at,
                          float bl, float bb, float br, float bt) {
    return !(ar < bl || al > br || at < bb || ab > bt);
}

// Compute intersection area ratio (of object) for partial-overlap decisions
static float overlapRatio(float al, float ab, float ar, float at,
                           float bl, float bb, float br, float bt) {
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
static std::wstring decomposeLigatures(const std::wstring& input) {
    std::wstring result;
    result.reserve(input.size() + 8);
    for (wchar_t wc : input) {
        switch (static_cast<uint32_t>(wc)) {
            case 0xFB00: result += L"ff";  break;  // ff
            case 0xFB01: result += L"fi";  break;  // fi
            case 0xFB02: result += L"fl";  break;  // fl
            case 0xFB03: result += L"ffi"; break;  // ffi
            case 0xFB04: result += L"ffl"; break;  // ffl
            case 0xFB05: result += L"st";  break;  // long-s t
            case 0xFB06: result += L"st";  break;  // st
            default:     result += wc;     break;
        }
    }
    return result;
}

// Unicode → WinAnsi charcode mapping
// WinAnsi bytes 0x80-0x9F map to Unicode codepoints that differ from their
// byte value (e.g. U+20AC → 0x80 for €). The 0x20-0x7F and 0xA0-0xFF ranges
// are identity-mapped. Returns 0 for unmappable codepoints.
static uint32_t unicodeToWinAnsiCharcode(uint32_t unicode) {
    if (unicode >= 0x20 && unicode <= 0x7F) return unicode;
    if (unicode >= 0xA0 && unicode <= 0xFF) return unicode;
    switch (unicode) {
        case 0x20AC: return 0x80;  // €
        case 0x201A: return 0x82;  // ‚
        case 0x0192: return 0x83;  // ƒ
        case 0x201E: return 0x84;  // „
        case 0x2026: return 0x85;  // …
        case 0x2020: return 0x86;  // †
        case 0x2021: return 0x87;  // ‡
        case 0x02C6: return 0x88;  // ˆ
        case 0x2030: return 0x89;  // ‰
        case 0x0160: return 0x8A;  // Š
        case 0x2039: return 0x8B;  // ‹
        case 0x0152: return 0x8C;  // Œ
        case 0x017D: return 0x8E;  // Ž
        case 0x2018: return 0x91;  // '
        case 0x2019: return 0x92;  // '
        case 0x201C: return 0x93;  // "
        case 0x201D: return 0x94;  // "
        case 0x2022: return 0x95;  // •
        case 0x2013: return 0x96;  // –
        case 0x2014: return 0x97;  // —
        case 0x02DC: return 0x98;  // ˜
        case 0x2122: return 0x99;  // ™
        case 0x0161: return 0x9A;  // š
        case 0x203A: return 0x9B;  // ›
        case 0x0153: return 0x9C;  // œ
        case 0x017E: return 0x9E;  // ž
        case 0x0178: return 0x9F;  // Ÿ
        default:     return 0;
    }
}

// Paints a filled rectangle (no object removal). Used for visual-only redaction.
static int32_t paintRedactRect(FPDF_PAGE page, float x, float y, float w, float h, uint32_t argb) {
    unsigned int r = (argb >> 16) & 0xFF;
    unsigned int g = (argb >>  8) & 0xFF;
    unsigned int b =  argb        & 0xFF;

    FPDF_PAGEOBJECT rect = FPDFPageObj_CreateNewRect(x, y, w, h);
    if (!rect) return JPDFIUM_ERR_NATIVE;
    FPDFPageObj_SetFillColor(rect, r, g, b, 255);
    FPDFPath_SetDrawMode(rect, FPDF_FILLMODE_ALTERNATE, 0);
    FPDFPage_InsertObject(page, rect);
    return JPDFIUM_OK;
}

// Removes text/image page objects within [x,y,x+w,y+h] (PDF coords: y up),
// paints a filled rect of the given color, commits with GenerateContent.
// argb: 0xAARRGGBB
//
// FIX: Only removes objects whose bounding box is MOSTLY (>70%) within the
// redaction rectangle. Previously ANY overlap caused removal, which deleted
// entire lines of text when only one word was targeted.
static int32_t applyRedactRect(FPDF_PAGE page, float x, float y, float w, float h,
                                uint32_t argb, bool removeContent = true) {
    unsigned int r = (argb >> 16) & 0xFF;
    unsigned int g = (argb >>  8) & 0xFF;
    unsigned int b =  argb        & 0xFF;

    float rx = x, ry = y, rr = x + w, rt = y + h;

    if (removeContent) {
        // Phase 1: remove objects that are FULLY or MOSTLY contained in the redact rect.
        // Objects that only partially overlap are NOT removed - the painted rectangle
        // will cover them visually, and flatten will bake it in.
        int objCount = FPDFPage_CountObjects(page);
        for (int i = objCount - 1; i >= 0; --i) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
            int type = FPDFPageObj_GetType(obj);
            if (type != FPDF_PAGEOBJ_TEXT && type != FPDF_PAGEOBJ_IMAGE) continue;

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
// True text redaction that permanently removes targeted characters from the
// content stream while preserving surrounding text with perfect typographical
// fidelity.  Implements the "Object Fission" approach:
//
//   1. Map text-page character indices to their owning FPDF_PAGEOBJECT via
//      spatial correlation (bounding-box containment of char centres).
//   2. For each page object that contains redacted characters:
//        - If ALL characters redacted -> destroy the entire object.
//        - If only SOME characters redacted -> "fission" the object:
//            a) Create a Prefix text object (chars before redaction) pinned
//               to the original transformation matrix.
//            b) Create a Suffix text object (chars after redaction) pinned
//               to a hybridised matrix: original scale/rotation (a,b,c,d) +
//               new translation (e,f) from FPDFText_GetCharOrigin.
//            c) Destroy the original object.
//   3. Paint a filled rectangle at every match bbox.
//   4. Regenerate the content stream (single FPDFPage_GenerateContent call).

struct TextMatch {
    std::vector<int> charIndices;   // text-page char indices for matched chars
    float bboxL, bboxB, bboxR, bboxT;  // tight aggregate bbox (PDF coords)
};

// A single contiguous run of surviving (non-redacted) characters within a text
// object.  Each fragment becomes its own independent FPDF_PAGEOBJECT, pinned
// to the exact absolute coordinates obtained from FPDFText_GetCharOrigin.
struct TextFragment {
    std::vector<uint16_t> utf16;   // UTF-16LE null-terminated text
    FS_MATRIX             matrix;  // hybrid: original a,b,c,d + charOrigin e,f
};

// Pre-computed fission plan for a single page object
struct FissionPlan {
    FPDF_PAGEOBJECT originalObj;

    // All surviving text fragments (replaces the old prefix/suffix pair).
    // Each fragment is independently positioned via FPDFText_GetCharOrigin, so
    // multi-gap redactions (e.g. two SSNs in the same text run) are handled
    // correctly.
    std::vector<TextFragment> fragments;

    FPDF_FONT              font;
    float                  fontSize;
    FPDF_TEXT_RENDERMODE    renderMode;

    // Original text colors - copied to every new fragment
    unsigned int fillR, fillG, fillB, fillA;
    unsigned int strokeR, strokeG, strokeB, strokeA;
    bool         hasStroke;

    bool                   removeEntirely;
};

static int32_t objectFissionRedact(
    FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_TEXTPAGE textPage,
    const std::vector<TextMatch>& matches,
    uint32_t argb, bool removeContent)
{
    if (matches.empty()) return JPDFIUM_OK;

    unsigned int red = (argb >> 16) & 0xFF;
    unsigned int grn = (argb >>  8) & 0xFF;
    unsigned int blu =  argb        & 0xFF;

    // Visual-only fast path
    if (!removeContent) {
        for (auto& m : matches) {
            FPDF_PAGEOBJECT rect = FPDFPageObj_CreateNewRect(
                m.bboxL, m.bboxB, m.bboxR - m.bboxL, m.bboxT - m.bboxB);
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
    std::set<int> redactSet;
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
    int objCount = FPDFPage_CountObjects(page);

    // Build a reverse map: FPDF_PAGEOBJECT pointer → object index
    std::unordered_map<uintptr_t, int> objPtrToIndex;
    for (int oi = 0; oi < objCount; oi++) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, oi);
        objPtrToIndex[reinterpret_cast<uintptr_t>(obj)] = oi;
    }

    struct CharInfo {
        int    ownerObj;   // index into page-object array (-1 = unmapped)
        bool   isGenerated; // FPDFText_IsGenerated
    };
    std::vector<CharInfo> charInfo(totalChars);

    for (int ci = 0; ci < totalChars; ci++) {
        charInfo[ci] = {-1, false};

        // Skip generated (synthetic) characters — they don't correspond to
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
        if (charInfo[ci - 1].ownerObj >= 0)
            charInfo[ci].ownerObj = charInfo[ci - 1].ownerObj;
    }
    //    Reverse pass: handle leading unmapped chars by inheriting from right.
    for (int ci = totalChars - 2; ci >= 0; ci--) {
        if (charInfo[ci].ownerObj >= 0) continue;
        if (charInfo[ci + 1].ownerObj >= 0)
            charInfo[ci].ownerObj = charInfo[ci + 1].ownerObj;
    }

    // 4. Group characters by their owning object
    //    objChars[oi] = sorted list of text-page char indices belonging to that object
    std::map<int, std::vector<int>> objChars;
    for (int ci = 0; ci < totalChars; ci++) {
        int oi = charInfo[ci].ownerObj;
        if (oi >= 0) objChars[oi].push_back(ci);
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
    auto buildFragment = [&](const std::vector<int>& run,
                             const FS_MATRIX& origMatrix,
                             TextFragment& outFrag) -> bool {
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
        std::wstring ws;
        for (size_t i = firstNonWS; i < run.size(); i++) {
            unsigned int uni = FPDFText_GetUnicode(textPage, run[i]);
            if (uni >= 0x20) ws += static_cast<wchar_t>(uni);
        }
        if (ws.empty()) return false;

        ws = decomposeLigatures(ws);

        outFrag.utf16 = wstring_to_utf16le(ws);
        outFrag.matrix = origMatrix;
        double fx, fy;
        if (FPDFText_GetCharOrigin(textPage, run[firstNonWS], &fx, &fy)) {
            outFrag.matrix.e = static_cast<float>(fx);
            outFrag.matrix.f = static_cast<float>(fy);
        }
        return true;
    };

    std::vector<FissionPlan> plans;
    std::set<FPDF_PAGEOBJECT> objsToDestroy;

    for (auto& [oi, chars] : objChars) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, oi);
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
                if (!inWord) { wordCount++; inWord = true; }
            }
        }
        hasMultipleWords = (wordCount > 1);

        // Skip single-word objects that have no redacted chars — they don't
        // need splitting and their single Tj is fine.
        if (!anyRedacted && !hasMultipleWords) continue;

        // Build per-word fragments, respecting both word boundaries AND
        // redaction boundaries.
        FissionPlan plan;
        plan.originalObj    = obj;
        plan.removeEntirely = false;
        plan.font           = FPDFTextObj_GetFont(obj);
        FPDFTextObj_GetFontSize(obj, &plan.fontSize);
        plan.renderMode     = FPDFTextObj_GetTextRenderMode(obj);

        FPDFPageObj_GetFillColor(obj, &plan.fillR, &plan.fillG, &plan.fillB, &plan.fillA);
        plan.hasStroke = FPDFPageObj_GetStrokeColor(obj, &plan.strokeR, &plan.strokeG,
                                                     &plan.strokeB, &plan.strokeA);

        FS_MATRIX originalMatrix;
        FPDFPageObj_GetMatrix(obj, &originalMatrix);

        // Walk chars, splitting at word boundaries and redaction boundaries.
        // Each contiguous run of non-redacted, non-space chars becomes a
        // fragment (typically one word).
        std::vector<int> currentRun;

        auto flushRun = [&]() {
            if (currentRun.empty()) return;
            TextFragment frag;
            if (buildFragment(currentRun, originalMatrix, frag)) {
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
        // (and the object needs it: redacted chars or multiple words)
        if (!plan.fragments.empty()) {
            plans.push_back(std::move(plan));
        }
    }

    // 6. Also remove image objects that are mostly inside any match bbox
    for (int i = objCount - 1; i >= 0; --i) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_IMAGE) continue;

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

    // Modification phase

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
    std::set<FPDF_PAGEOBJECT> fissionAttempted;

    // FreeType font cache: avoid re-loading font data for every fragment.
#ifdef JPDFIUM_HAS_FREETYPE
    struct FtFontCache {
        std::unordered_map<uint32_t, uint32_t> unicodeToGid;
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
                if (FT_New_Memory_Face(g_ft_lib, fontData.data(),
                                       static_cast<FT_Long>(actual), 0, &face) == 0) {
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

    for (auto& plan : plans) {
        fissionAttempted.insert(plan.originalObj);
        std::vector<FPDF_PAGEOBJECT> createdObjs;
        bool allOk = true;

        for (auto& frag : plan.fragments) {
            if (frag.utf16.size() <= 1) continue;   // skip null-only

            FPDF_PAGEOBJECT fragObj = FPDFPageObj_CreateTextObj(doc, plan.font, plan.fontSize);
            if (!fragObj) { allOk = false; break; }

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
            textOk = FPDFText_SetText(fragObj,
                reinterpret_cast<FPDF_WIDESTRING>(frag.utf16.data()));
            if (textOk) boundsOk = boundsValid(fragObj);

#ifdef JPDFIUM_HAS_FREETYPE
            // Strategy B: FreeType GID-based SetCharcodes
            if (!textOk || !boundsOk) {
                if (textOk && !boundsOk) {
                    if (!resetFragObj()) { allOk = false; break; }
                }
                const auto& ftInfo = getFtMapping(plan.font);
                if (ftInfo.valid) {
                    std::vector<uint32_t> codes;
                    bool allMapped = true;
                    for (size_t i = 0; i + 1 < frag.utf16.size(); i++) {
                        auto git = ftInfo.unicodeToGid.find(
                            static_cast<uint32_t>(frag.utf16[i]));
                        if (git != ftInfo.unicodeToGid.end() && git->second != 0) {
                            codes.push_back(git->second);
                        } else {
                            allMapped = false;
                            break;
                        }
                    }
                    if (allMapped && !codes.empty()) {
                        textOk = FPDFText_SetCharcodes(
                            fragObj, codes.data(), codes.size());
                        if (textOk) boundsOk = boundsValid(fragObj);
                    }
                }
            }
#endif

            // Strategy C: WinAnsi SetCharcodes
            if (!textOk || !boundsOk) {
                if (textOk && !boundsOk) {
                    if (!resetFragObj()) { allOk = false; break; }
                }
                std::vector<uint32_t> codes;
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
                    textOk = FPDFText_SetCharcodes(
                        fragObj, codes.data(), codes.size());
                    if (textOk) boundsOk = boundsValid(fragObj);
                }
            }

            if (!textOk || !boundsOk) {
                FPDFPageObj_Destroy(fragObj);
                allOk = false;
                break;
            }

            FPDFPageObj_SetMatrix(fragObj, &frag.matrix);
            FPDFTextObj_SetTextRenderMode(fragObj, plan.renderMode);

            // Restore original text colors
            FPDFPageObj_SetFillColor(fragObj, plan.fillR, plan.fillG, plan.fillB, plan.fillA);
            if (plan.hasStroke) {
                FPDFPageObj_SetStrokeColor(fragObj, plan.strokeR, plan.strokeG,
                                           plan.strokeB, plan.strokeA);
            }

            createdObjs.push_back(fragObj);
        }

        if (allOk) {
            // All fragments created successfully -> commit
            for (auto* fo : createdObjs) {
                FPDFPage_InsertObject(page, fo);
            }
            objsToDestroy.insert(plan.originalObj);
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
    //    were NOT caught by the char-to-object mapping (e.g. Form XObject text,
    //    chars with degenerate bounding boxes).
    //    Skip objects that were already handled by fission (even if fission
    //    failed — in that case the original is intentionally preserved and
    //    the black box provides visual cover).
    for (int i = objCount - 1; i >= 0; --i) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (objsToDestroy.count(obj)) continue;       // already marked
        if (fissionAttempted.count(obj)) continue;     // fission handled it
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

    // 9. Remove all marked objects
    for (auto* obj : objsToDestroy) {
        FPDFPage_RemoveObject(page, obj);
        FPDFPageObj_Destroy(obj);
    }

    // 10. Paint black rectangles for all match regions
    for (auto& m : matches) {
        FPDF_PAGEOBJECT rect = FPDFPageObj_CreateNewRect(
            m.bboxL, m.bboxB, m.bboxR - m.bboxL, m.bboxT - m.bboxB);
        if (!rect) continue;
        FPDFPageObj_SetFillColor(rect, red, grn, blu, 255);
        FPDFPath_SetDrawMode(rect, FPDF_FILLMODE_ALTERNATE, 0);
        FPDFPage_InsertObject(page, rect);
    }

    // 11. Commit to content stream (single call for all modifications)
    return FPDFPage_GenerateContent(page) ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
}

// Helper: run regex over extracted text -> produce TextMatch vector.
// wtext + idxMap must already be populated (see callers).
static void collectRegexMatches(
    FPDF_TEXTPAGE textPage,
    const std::wstring& wtext,
    const std::vector<int>& idxMap,
    const std::wregex& wre,
    float padding,
    std::vector<TextMatch>& out)
{
    auto it  = std::wsregex_iterator(wtext.begin(), wtext.end(), wre);
    auto end = std::wsregex_iterator();

    for (; it != end; ++it) {
        int start = static_cast<int>((*it).position());
        int len   = static_cast<int>((*it).length());
        if (len == 0) continue;

        TextMatch tm;
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

int32_t jpdfium_init() {
    FPDF_LIBRARY_CONFIG cfg;
    cfg.version          = 2;
    cfg.m_pUserFontPaths = nullptr;
    cfg.m_pIsolate       = nullptr;
    cfg.m_v8EmbedderSlot = 0;
    FPDF_InitLibraryWithConfig(&cfg);
    return JPDFIUM_OK;
}

void jpdfium_destroy() {
    FPDF_DestroyLibrary();
}

// Document open

int32_t jpdfium_doc_open(const char* path, int64_t* handle) {
    FPDF_DOCUMENT doc = FPDF_LoadDocument(path, nullptr);
    if (!doc) return translatePdfiumError();

    auto* w = new DocWrapper();
    w->doc  = doc;
    *handle = encodeHandle(w);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open_bytes(const uint8_t* data, int64_t len, int64_t* handle) {
    // PDFium requires the buffer to remain valid for the document lifetime.
    uint8_t* copy = static_cast<uint8_t*>(malloc(static_cast<size_t>(len)));
    if (!copy) return JPDFIUM_ERR_NATIVE;
    memcpy(copy, data, static_cast<size_t>(len));

    FPDF_DOCUMENT doc = FPDF_LoadMemDocument(copy, static_cast<int>(len), nullptr);
    if (!doc) { free(copy); return translatePdfiumError(); }

    auto* w  = new DocWrapper();
    w->doc   = doc;
    w->buf   = copy;
    w->blen  = len;
    *handle  = encodeHandle(w);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_open_protected(const char* path, const char* password, int64_t* handle) {
    FPDF_DOCUMENT doc = FPDF_LoadDocument(path, password);
    if (!doc) return translatePdfiumError();

    auto* w = new DocWrapper();
    w->doc  = doc;
    *handle = encodeHandle(w);
    return JPDFIUM_OK;
}

// Document info / save

int32_t jpdfium_doc_page_count(int64_t doc, int32_t* count) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->doc) return JPDFIUM_ERR_INVALID;
    *count = FPDF_GetPageCount(w->doc);
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_save(int64_t doc, const char* path) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->doc) return JPDFIUM_ERR_INVALID;

    FILE* f = fopen(path, "wb");
    if (!f) return JPDFIUM_ERR_IO;

    struct FileWriter : FPDF_FILEWRITE {
        FILE* fp;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            return fwrite(data, 1, size, static_cast<FileWriter*>(self)->fp) == size ? 1 : 0;
        }
    } fw;
    fw.version    = 1;
    fw.WriteBlock = FileWriter::Write;
    fw.fp         = f;

    int ok = FPDF_SaveAsCopy(w->doc, &fw, FPDF_NO_INCREMENTAL);
    fclose(f);
    return ok ? JPDFIUM_OK : JPDFIUM_ERR_IO;
}

int32_t jpdfium_doc_save_bytes(int64_t doc, uint8_t** data, int64_t* len) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->doc) return JPDFIUM_ERR_INVALID;

    struct BufWriter : FPDF_FILEWRITE {
        std::vector<uint8_t> buf;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            auto* bw  = static_cast<BufWriter*>(self);
            auto* src = static_cast<const uint8_t*>(data);
            bw->buf.insert(bw->buf.end(), src, src + size);
            return 1;
        }
    } bw;
    bw.version    = 1;
    bw.WriteBlock = BufWriter::Write;

    if (!FPDF_SaveAsCopy(w->doc, &bw, FPDF_NO_INCREMENTAL)) return JPDFIUM_ERR_IO;

    size_t   sz  = bw.buf.size();
    uint8_t* out = static_cast<uint8_t*>(malloc(sz));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, bw.buf.data(), sz);
    *data = out;
    *len  = static_cast<int64_t>(sz);
    return JPDFIUM_OK;
}

void jpdfium_doc_close(int64_t doc) {
    delete decodeDoc(doc);
}

// Page

int32_t jpdfium_page_open(int64_t doc, int32_t idx, int64_t* handle) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->doc) return JPDFIUM_ERR_INVALID;

    FPDF_PAGE page = FPDF_LoadPage(w->doc, idx);
    if (!page) return JPDFIUM_ERR_NOT_FOUND;

    *handle = encodeHandle(new PageWrapper(page, w->doc));
    return JPDFIUM_OK;
}

int32_t jpdfium_page_width(int64_t page, float* width) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    *width = static_cast<float>(FPDF_GetPageWidth(pw->page));
    return JPDFIUM_OK;
}

int32_t jpdfium_page_height(int64_t page, float* height) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    *height = static_cast<float>(FPDF_GetPageHeight(pw->page));
    return JPDFIUM_OK;
}

void jpdfium_page_close(int64_t page) {
    delete decodePage(page);
}

// Render

int32_t jpdfium_render_page(int64_t page, int32_t dpi, uint8_t** rgba, int32_t* width, int32_t* height) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    double w_pt = FPDF_GetPageWidth(pw->page);
    double h_pt = FPDF_GetPageHeight(pw->page);
    int    w_px = static_cast<int>(w_pt * dpi / 72.0 + 0.5);
    int    h_px = static_cast<int>(h_pt * dpi / 72.0 + 0.5);
    if (w_px <= 0 || h_px <= 0) return JPDFIUM_ERR_INVALID;

    FPDF_BITMAP bmp = FPDFBitmap_Create(w_px, h_px, 1 /*alpha*/);
    if (!bmp) return JPDFIUM_ERR_NATIVE;

    FPDFBitmap_FillRect(bmp, 0, 0, w_px, h_px, 0xFFFFFFFF);
    FPDF_RenderPageBitmap(bmp, pw->page, 0, 0, w_px, h_px, 0, FPDF_ANNOT);

    // PDFium returns BGRA; swap B↔R to produce RGBA
    const uint8_t* src    = static_cast<const uint8_t*>(FPDFBitmap_GetBuffer(bmp));
    int            stride = FPDFBitmap_GetStride(bmp);
    size_t         out_sz = static_cast<size_t>(w_px) * h_px * 4;
    uint8_t*       out    = static_cast<uint8_t*>(malloc(out_sz));
    if (!out) { FPDFBitmap_Destroy(bmp); return JPDFIUM_ERR_NATIVE; }

    for (int row = 0; row < h_px; ++row) {
        const uint8_t* s = src + row * stride;
        uint8_t*       d = out + row * w_px * 4;
        for (int col = 0; col < w_px; ++col, s += 4, d += 4) {
            d[0] = s[2];  // R <- B
            d[1] = s[1];  // G
            d[2] = s[0];  // B <- R
            d[3] = s[3];  // A
        }
    }

    FPDFBitmap_Destroy(bmp);
    *rgba   = out;
    *width  = w_px;
    *height = h_px;
    return JPDFIUM_OK;
}

void jpdfium_free_buffer(uint8_t* buffer) {
    free(buffer);
}

// Text extraction

// Returns JSON array with char-origin (ox,oy) in addition to bounding box:
// [{"i":0,"u":72,"ox":10.1,"oy":20.2,"l":10.0,"r":18.0,"b":15.0,"t":27.0}, ...]
int32_t jpdfium_text_get_char_positions(int64_t page, char** json) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
    if (!tp) return JPDFIUM_ERR_NATIVE;

    int count = FPDFText_CountChars(tp);
    std::ostringstream os;
    os << '[';
    bool first = true;

    for (int i = 0; i < count; ++i) {
        unsigned int uni = FPDFText_GetUnicode(tp, i);
        if (uni == 0) continue;

        double l, r, b, t;
        if (!FPDFText_GetCharBox(tp, i, &l, &r, &b, &t)) {
            l = r = b = t = 0.0;
        }

        double ox, oy;
        if (!FPDFText_GetCharOrigin(tp, i, &ox, &oy)) {
            ox = l;
            oy = b;
        }

        if (!first) os << ',';
        first = false;
        os << "{\"i\":" << i
           << ",\"u\":" << uni
           << ",\"ox\":" << ox
           << ",\"oy\":" << oy
           << ",\"l\":" << l
           << ",\"r\":" << r
           << ",\"b\":" << b
           << ",\"t\":" << t << '}';
    }
    os << ']';

    FPDFText_ClosePage(tp);

    std::string s = os.str();
    char* out = static_cast<char*>(malloc(s.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, s.c_str(), s.size() + 1);
    *json = out;
    return JPDFIUM_OK;
}

// Returns JSON array: [{"i":0,"u":65,"x":10.1,"y":20.2,"w":8.3,"h":12.4,"font":"Arial","size":12.0}, ...]
int32_t jpdfium_text_get_chars(int64_t page, char** json) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
    if (!tp) return JPDFIUM_ERR_NATIVE;

    int count = FPDFText_CountChars(tp);
    std::ostringstream os;
    os << '[';
    bool first = true;

    char fontbuf[256];
    for (int i = 0; i < count; ++i) {
        unsigned int uni = FPDFText_GetUnicode(tp, i);
        if (uni == 0) continue;

        double l, r, b, t;
        FPDFText_GetCharBox(tp, i, &l, &r, &b, &t);

        FPDFText_GetFontInfo(tp, i, fontbuf, sizeof(fontbuf), nullptr);
        float size = FPDFText_GetFontSize(tp, i);

        if (!first) os << ',';
        first = false;
        os << "{\"i\":" << i
           << ",\"u\":" << uni
           << ",\"x\":" << l
           << ",\"y\":" << b
           << ",\"w\":" << (r - l)
           << ",\"h\":" << (t - b)
           << ",\"font\":\"";
        for (char c : std::string(fontbuf)) {
            if (c == '"' || c == '\\') os << '\\';
            os << c;
        }
        os << "\",\"size\":" << size << '}';
    }
    os << ']';

    FPDFText_ClosePage(tp);

    std::string s = os.str();
    char* out = static_cast<char*>(malloc(s.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, s.c_str(), s.size() + 1);
    *json = out;
    return JPDFIUM_OK;
}

// Returns JSON array of match positions: [{"start":0,"len":3}, ...]
int32_t jpdfium_text_find(int64_t page, const char* query, char** json) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
    if (!tp) return JPDFIUM_ERR_NATIVE;

    // PDFium expects UTF-16LE (2 bytes/unit), NOT wchar_t (4 bytes on Linux)
    auto wq = utf8_to_utf16le(query);
    FPDF_SCHHANDLE sch = FPDFText_FindStart(
        tp, reinterpret_cast<FPDF_WIDESTRING>(wq.data()), 0, 0);

    std::ostringstream os;
    os << '[';
    bool first = true;
    while (sch && FPDFText_FindNext(sch)) {
        int start = FPDFText_GetSchResultIndex(sch);
        int cnt   = FPDFText_GetSchCount(sch);
        if (!first) os << ',';
        first = false;
        os << "{\"start\":" << start << ",\"len\":" << cnt << '}';
    }
    os << ']';

    if (sch) FPDFText_FindClose(sch);
    FPDFText_ClosePage(tp);

    std::string s = os.str();
    char* out = static_cast<char*>(malloc(s.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, s.c_str(), s.size() + 1);
    *json = out;
    return JPDFIUM_OK;
}

void jpdfium_free_string(char* str) {
    free(str);
}

// Redaction

int32_t jpdfium_redact_region(int64_t page, float x, float y, float w, float h,
                              uint32_t argb, int32_t remove_content) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    return applyRedactRect(pw->page, x, y, w, h, argb, remove_content != 0);
}

int32_t jpdfium_redact_pattern(int64_t page, const char* pattern, uint32_t argb,
                               int32_t remove_content) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
    if (!tp) return JPDFIUM_ERR_NATIVE;

    int count = FPDFText_CountChars(tp);

    // Build wide string + index map (skipping null chars)
    std::wstring wtext;
    std::vector<int> idxMap;
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
    std::vector<TextMatch> matches;
    collectRegexMatches(tp, wtext, idxMap, wre, 0.0f, matches);

    if (matches.empty()) {
        FPDFText_ClosePage(tp);
        return JPDFIUM_OK;
    }

    // Apply Object Fission redaction
    int32_t rc = objectFissionRedact(
        pw->doc, pw->page, tp, matches, argb, remove_content != 0);

    FPDFText_ClosePage(tp);
    return rc;
}

// Flatten

int32_t jpdfium_page_flatten(int64_t page) {
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

int32_t jpdfium_redact_words(int64_t page, const char** words, int32_t wordCount,
                              uint32_t argb, float padding, int32_t wholeWord,
                              int32_t useRegex, int32_t remove_content) {
    return jpdfium_redact_words_ex(page, words, wordCount, argb, padding,
                                   wholeWord, useRegex, remove_content, 0, nullptr);
}

// Extended version that reports match count back to the caller.
int32_t jpdfium_redact_words_ex(int64_t page, const char** words, int32_t wordCount,
                                 uint32_t argb, float padding, int32_t wholeWord,
                                 int32_t useRegex, int32_t remove_content,
                                 int32_t caseSensitive, int32_t* matchCount) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    if (!words || wordCount <= 0) {
        if (matchCount) *matchCount = 0;
        return JPDFIUM_OK;
    }

    FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
    if (!tp) return JPDFIUM_ERR_NATIVE;

    int count = FPDFText_CountChars(tp);

    // Build wide text + index-map (skipping null unicode chars)
    std::wstring wtext;
    std::vector<int> idxMap;
    for (int i = 0; i < count; ++i) {
        unsigned int uni = FPDFText_GetUnicode(tp, i);
        if (uni == 0) continue;
        wtext += static_cast<wchar_t>(uni);
        idxMap.push_back(i);
    }

    std::vector<TextMatch> matches;

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
                if (ch == L'\\' || ch == L'^' || ch == L'$' || ch == L'.' ||
                    ch == L'|' || ch == L'?' || ch == L'*' || ch == L'+' ||
                    ch == L'(' || ch == L')' || ch == L'[' || ch == L']' ||
                    ch == L'{' || ch == L'}') {
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
    int32_t rc = objectFissionRedact(
        pw->doc, pw->page, tp, matches, argb, remove_content != 0);

    FPDFText_ClosePage(tp);
    return rc;
}

// Convert page to image-based PDF page (strips ALL underlying content)
// Renders the page at the given DPI, creates a new image-only page.
// This is the most secure redaction: visually identical but no extractable text.

int32_t jpdfium_page_to_image(int64_t docHandle, int32_t pageIndex, int32_t dpi) {
    DocWrapper* dw = decodeDoc(docHandle);
    if (!dw || !dw->doc) return JPDFIUM_ERR_INVALID;

    FPDF_PAGE page = FPDF_LoadPage(dw->doc, pageIndex);
    if (!page) return JPDFIUM_ERR_NOT_FOUND;

    double w_pt = FPDF_GetPageWidth(page);
    double h_pt = FPDF_GetPageHeight(page);
    int w_px = static_cast<int>(w_pt * dpi / 72.0 + 0.5);
    int h_px = static_cast<int>(h_pt * dpi / 72.0 + 0.5);
    if (w_px <= 0 || h_px <= 0) { FPDF_ClosePage(page); return JPDFIUM_ERR_INVALID; }

    // Render current page to bitmap
    FPDF_BITMAP bmp = FPDFBitmap_Create(w_px, h_px, 0 /*no alpha*/);
    if (!bmp) { FPDF_ClosePage(page); return JPDFIUM_ERR_NATIVE; }
    FPDFBitmap_FillRect(bmp, 0, 0, w_px, h_px, 0xFFFFFFFF);
    FPDF_RenderPageBitmap(bmp, page, 0, 0, w_px, h_px, 0, FPDF_ANNOT | FPDF_PRINTING);

    FPDF_ClosePage(page);

    // Delete the old page and insert a new blank page at the same position
    FPDFPage_Delete(dw->doc, pageIndex);
    FPDF_PAGE newPage = FPDFPage_New(dw->doc, pageIndex, w_pt, h_pt);
    if (!newPage) { FPDFBitmap_Destroy(bmp); return JPDFIUM_ERR_NATIVE; }

    // Create an image object from the rendered bitmap
    FPDF_PAGEOBJECT imgObj = FPDFPageObj_NewImageObj(dw->doc);
    if (!imgObj) { FPDFBitmap_Destroy(bmp); FPDF_ClosePage(newPage); return JPDFIUM_ERR_NATIVE; }

    // Set the bitmap as the image content
    // PDFium API: FPDFImageObj_SetBitmap sets the image data from a bitmap
    FPDF_BOOL ok = FPDFImageObj_SetBitmap(nullptr, 0, imgObj, bmp);
    FPDFBitmap_Destroy(bmp);
    if (!ok) { FPDF_ClosePage(newPage); return JPDFIUM_ERR_NATIVE; }

    // Position the image to cover the full page: translate + scale matrix
    // The image is 1x1 by default; we scale it to page size in points
    FS_MATRIX matrix = {static_cast<float>(w_pt), 0, 0, static_cast<float>(h_pt), 0, 0};
    FPDFPageObj_SetMatrix(imgObj, &matrix);

    FPDFPage_InsertObject(newPage, imgObj);
    if (!FPDFPage_GenerateContent(newPage)) {
        FPDF_ClosePage(newPage);
        return JPDFIUM_ERR_NATIVE;
    }

    FPDF_ClosePage(newPage);
    return JPDFIUM_OK;
}

int32_t jpdfium_annot_create_redact(int64_t page,
                                     float x, float y, float w, float h,
                                     uint32_t argb, int32_t* annot_index) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(pw->page, FPDF_ANNOT_REDACT);
    if (!annot) return JPDFIUM_ERR_NATIVE;

    FS_RECTF rect;
    rect.left   = x;
    rect.bottom = y;
    rect.right  = x + w;
    rect.top    = y + h;
    if (!FPDFAnnot_SetRect(annot, &rect)) {
        FPDFPage_CloseAnnot(annot);
        return JPDFIUM_ERR_NATIVE;
    }

    unsigned int r = (argb >> 16) & 0xFF;
    unsigned int g = (argb >>  8) & 0xFF;
    unsigned int b =  argb        & 0xFF;
    FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_InteriorColor, r, g, b, 255);

    // Return the annotation index (it's appended at the end)
    int idx = FPDFPage_GetAnnotCount(pw->page) - 1;
    if (annot_index) *annot_index = idx;

    FPDFPage_CloseAnnot(annot);
    return JPDFIUM_OK;
}

int32_t jpdfium_annot_count_redacts(int64_t page, int32_t* count) {
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

int32_t jpdfium_annot_get_redacts_json(int64_t page, char** json) {
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
                os << "{\"idx\":" << i
                   << ",\"x\":" << rect.left
                   << ",\"y\":" << rect.bottom
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
}

int32_t jpdfium_annot_remove_redact(int64_t page, int32_t annot_index) {
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
}

int32_t jpdfium_annot_clear_redacts(int64_t page) {
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
}

// Mark phase: find text matches and create REDACT annotations (no content mutation)
int32_t jpdfium_redact_mark_words(int64_t page,
                                   const char** words, int32_t wordCount,
                                   float padding, int32_t wholeWord,
                                   int32_t useRegex, int32_t caseSensitive,
                                   uint32_t argb, int32_t* matchCount) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    if (!words || wordCount <= 0) {
        if (matchCount) *matchCount = 0;
        return JPDFIUM_OK;
    }

    FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
    if (!tp) return JPDFIUM_ERR_NATIVE;

    int count = FPDFText_CountChars(tp);

    // Build wide text + index-map
    std::wstring wtext;
    std::vector<int> idxMap;
    for (int i = 0; i < count; ++i) {
        unsigned int uni = FPDFText_GetUnicode(tp, i);
        if (uni == 0) continue;
        wtext += static_cast<wchar_t>(uni);
        idxMap.push_back(i);
    }

    std::vector<TextMatch> matches;
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
                if (ch == L'\\' || ch == L'^' || ch == L'$' || ch == L'.' ||
                    ch == L'|' || ch == L'?' || ch == L'*' || ch == L'+' ||
                    ch == L'(' || ch == L')' || ch == L'[' || ch == L']' ||
                    ch == L'{' || ch == L'}') {
                    wpattern += L'\\';
                }
                wpattern += ch;
            }
        }
        if (wholeWord) wpattern = L"\\b" + wpattern + L"\\b";

        std::wregex wre;
        try { wre.assign(wpattern, rxFlags); } catch (...) { continue; }

        collectRegexMatches(tp, wtext, idxMap, wre, padding, matches);
    }

    FPDFText_ClosePage(tp);

    // Create REDACT annotations from matches (zero content mutation)
    unsigned int r = (argb >> 16) & 0xFF;
    unsigned int g = (argb >>  8) & 0xFF;
    unsigned int b =  argb        & 0xFF;

    for (auto& m : matches) {
        FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(pw->page, FPDF_ANNOT_REDACT);
        if (!annot) continue;

        FS_RECTF rect;
        rect.left   = m.bboxL;
        rect.bottom = m.bboxB;
        rect.right  = m.bboxR;
        rect.top    = m.bboxT;
        FPDFAnnot_SetRect(annot, &rect);
        FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_InteriorColor, r, g, b, 255);
        FPDFPage_CloseAnnot(annot);
    }

    if (matchCount) *matchCount = static_cast<int32_t>(matches.size());
    return JPDFIUM_OK;
}

// Commit phase: burn all REDACT annotations using Object Fission
int32_t jpdfium_redact_commit(int64_t page, uint32_t argb,
                               int32_t remove_content, int32_t* commitCount) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;

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
            applyRedactRect(pw->page, r.left, r.bottom, r.right - r.left,
                            r.top - r.bottom, argb, remove_content != 0);
        }
        return JPDFIUM_OK;
    }

    int charCount = FPDFText_CountChars(tp);

    // Build TextMatch for each annotation rect by finding intersecting characters
    std::vector<TextMatch> matches;
    for (auto& ar : redactRects) {
        TextMatch tm;
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

    int32_t rc = objectFissionRedact(
        pw->doc, pw->page, tp, matches, argb, remove_content != 0);

    FPDFText_ClosePage(tp);
    return rc;
}

// Incremental save: writes only changed objects, document stays live
int32_t jpdfium_doc_save_incremental(int64_t doc, uint8_t** data, int64_t* len) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->doc) return JPDFIUM_ERR_INVALID;

    struct BufWriter : FPDF_FILEWRITE {
        std::vector<uint8_t> buf;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            auto* bw  = static_cast<BufWriter*>(self);
            auto* src = static_cast<const uint8_t*>(data);
            bw->buf.insert(bw->buf.end(), src, src + size);
            return 1;
        }
    } bw;
    bw.version    = 1;
    bw.WriteBlock = BufWriter::Write;

    if (!FPDF_SaveAsCopy(w->doc, &bw, FPDF_INCREMENTAL)) return JPDFIUM_ERR_IO;

    size_t   sz  = bw.buf.size();
    uint8_t* out = static_cast<uint8_t*>(malloc(sz));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, bw.buf.data(), sz);
    *data = out;
    *len  = static_cast<int64_t>(sz);
    return JPDFIUM_OK;
}

// Character position extraction (for testing)
