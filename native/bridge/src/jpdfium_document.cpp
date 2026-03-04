#include "jpdfium.h"
#include "jpdfium_internal.h"

#include <fpdfview.h>
#include <fpdf_save.h>
#include <fpdf_text.h>
#include <fpdf_edit.h>
#include <fpdf_flatten.h>

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

// ── String helpers ────────────────────────────────────────────────────────────

// UTF-8 → UTF-16LE for FPDFText_FindStart (PDFium expects UTF-16LE, not wchar_t)
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

// UTF-8 → std::wstring (wchar_t is 32-bit on Linux/macOS - one code unit per codepoint)
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

// std::wstring → UTF-16LE (for FPDFText_SetText on new text objects)
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

// ── Shared redaction primitives ───────────────────────────────────────────────

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

// ── Object Fission Algorithm ──────────────────────────────────────────────────
// True text redaction that permanently removes targeted characters from the
// content stream while preserving surrounding text with perfect typographical
// fidelity.  Implements the "Object Fission" approach:
//
//   1. Map text-page character indices to their owning FPDF_PAGEOBJECT via
//      spatial correlation (bounding-box containment of char centres).
//   2. For each page object that contains redacted characters:
//        • If ALL characters redacted → destroy the entire object.
//        • If only SOME characters redacted → "fission" the object:
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

    // ── Visual-only fast path ─────────────────────────────────────────────────
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

    // ── Analysis phase (read-only - all text-page queries happen here) ────────

    int totalChars = FPDFText_CountChars(textPage);

    // 1. Collect the set of all char indices targeted for redaction
    std::set<int> redactSet;
    for (auto& m : matches) {
        for (int ci : m.charIndices) redactSet.insert(ci);
    }

    // 2. Pre-compute bounding-box centres for every character on the page
    struct CharInfo {
        double cx, cy;     // centre of char box in user space
        bool   valid;      // false if FPDFText_GetCharBox failed
        int    ownerObj;   // index into page-object array (-1 = unmapped)
    };
    std::vector<CharInfo> charInfo(totalChars);
    for (int i = 0; i < totalChars; i++) {
        double left, right, bottom, top;
        if (FPDFText_GetCharBox(textPage, i, &left, &right, &bottom, &top) &&
            (right - left) > 0.001 && (top - bottom) > 0.001) {
            charInfo[i] = {(left + right) / 2.0, (bottom + top) / 2.0, true, -1};
        } else {
            charInfo[i] = {0.0, 0.0, false, -1};
        }
    }

    // 3. Build char → page-object mapping via spatial correlation
    int objCount = FPDFPage_CountObjects(page);
    for (int oi = 0; oi < objCount; oi++) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, oi);
        if (FPDFPageObj_GetType(obj) != FPDF_PAGEOBJ_TEXT) continue;

        float ol, ob, or_, ot;
        if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) continue;

        // Slight tolerance for rounding
        float tol = 0.5f;
        for (int ci = 0; ci < totalChars; ci++) {
            auto& ci_info = charInfo[ci];
            if (!ci_info.valid || ci_info.ownerObj >= 0) continue;
            if (ci_info.cx >= ol - tol && ci_info.cx <= or_ + tol &&
                ci_info.cy >= ob - tol && ci_info.cy <= ot + tol) {
                ci_info.ownerObj = oi;
            }
        }
    }

    // 4. Group characters by their owning object
    //    objChars[oi] = sorted list of text-page char indices belonging to that object
    std::map<int, std::vector<int>> objChars;
    for (int ci = 0; ci < totalChars; ci++) {
        int oi = charInfo[ci].ownerObj;
        if (oi >= 0) objChars[oi].push_back(ci);
    }

    // 5. Plan fission operations for every affected text object.
    //
    // KEY FIX: Instead of the old prefix/suffix split (which fails when there are
    // multiple non-adjacent redacted regions in the same text object), we now
    // identify ALL contiguous runs of surviving characters.  Each run becomes an
    // independent TextFragment, pinned to the absolute page-space coordinates
    // returned by FPDFText_GetCharOrigin for its first character.  This avoids
    // ALL internal kerning/spacing issues because we never rely on the font's
    // advance widths to bridge a gap left by removed characters.
    std::vector<FissionPlan> plans;
    std::set<FPDF_PAGEOBJECT> objsToDestroy;

    for (auto& [oi, chars] : objChars) {
        // Check if any characters in this object are redacted
        bool anyRedacted = false;
        bool allRedacted = true;
        for (int ci : chars) {
            if (redactSet.count(ci)) {
                anyRedacted = true;
            } else {
                allRedacted = false;
            }
        }

        if (!anyRedacted) continue;

        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, oi);

        // Fully contained → simple removal, no fragments needed
        if (allRedacted) {
            objsToDestroy.insert(obj);
            continue;
        }

        // Partial overlap → Object Fission with multi-fragment support
        FissionPlan plan;
        plan.originalObj    = obj;
        plan.removeEntirely = false;
        plan.font           = FPDFTextObj_GetFont(obj);
        FPDFTextObj_GetFontSize(obj, &plan.fontSize);
        plan.renderMode     = FPDFTextObj_GetTextRenderMode(obj);

        // Preserve the original object's fill and stroke colors
        FPDFPageObj_GetFillColor(obj, &plan.fillR, &plan.fillG, &plan.fillB, &plan.fillA);
        plan.hasStroke = FPDFPageObj_GetStrokeColor(obj, &plan.strokeR, &plan.strokeG,
                                                     &plan.strokeB, &plan.strokeA);

        // Get original matrix (a,b,c,d for scaling/rotation/shear)
        FS_MATRIX originalMatrix;
        FPDFPageObj_GetMatrix(obj, &originalMatrix);

        // ── Build contiguous fragments of surviving text ──────────────────
        // Walk through the chars in order.  Every time we transition from
        // redacted→non-redacted, start a new fragment.  Every time we transition
        // from non-redacted→redacted, close the current fragment.
        std::vector<int> currentRun;
        bool inRedacted = false;

        auto flushFragment = [&]() {
            if (currentRun.empty()) return;

            TextFragment frag;

            // Gather unicode text for this run
            std::wstring ws;
            for (int ci : currentRun) {
                unsigned int uni = FPDFText_GetUnicode(textPage, ci);
                if (uni) ws += static_cast<wchar_t>(uni);
            }
            if (ws.empty()) { currentRun.clear(); return; }
            frag.utf16 = wstring_to_utf16le(ws);

            // Hybrid matrix: copy a,b,c,d from original (preserves size/angle)
            frag.matrix = originalMatrix;

            // Pin translation (e,f) to the absolute page-space origin of this
            // fragment's first surviving character.  This is the critical fix:
            // by deriving e,f from FPDFText_GetCharOrigin instead of from the
            // original matrix or from font advance width calculations, we
            // completely bypass kerning, character spacing, word spacing, and
            // TJ-array positioning logic.  The fragment lands exactly where the
            // original character was painted.
            double fx, fy;
            if (FPDFText_GetCharOrigin(textPage, currentRun[0], &fx, &fy)) {
                frag.matrix.e = static_cast<float>(fx);
                frag.matrix.f = static_cast<float>(fy);
            }


            plan.fragments.push_back(std::move(frag));
            currentRun.clear();
        };

        for (int ci : chars) {
            bool isRedacted = redactSet.count(ci) > 0;
            if (isRedacted) {
                // Close any open non-redacted run
                if (!inRedacted && !currentRun.empty()) {
                    flushFragment();
                }
                inRedacted = true;
            } else {
                if (inRedacted) {
                    // Transition from redacted → non-redacted: start new run
                    currentRun.clear();
                }
                inRedacted = false;
                currentRun.push_back(ci);
            }
        }
        // Flush the final run if the object ends with non-redacted chars
        flushFragment();

        plans.push_back(std::move(plan));
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

    // ── Modification phase ────────────────────────────────────────────────────

    // 7. Apply fission: create fragment objects BEFORE removing originals
    //    (font handles borrowed from originals remain valid through the document)
    for (auto& plan : plans) {
        for (auto& frag : plan.fragments) {
            if (frag.utf16.size() <= 1) continue;   // skip null-only

            FPDF_PAGEOBJECT fragObj = FPDFPageObj_CreateTextObj(doc, plan.font, plan.fontSize);
            if (!fragObj) continue;

            FPDFText_SetText(fragObj,
                reinterpret_cast<FPDF_WIDESTRING>(frag.utf16.data()));
            FPDFPageObj_SetMatrix(fragObj, &frag.matrix);
            FPDFTextObj_SetTextRenderMode(fragObj, plan.renderMode);


            // Restore original text colors
            FPDFPageObj_SetFillColor(fragObj, plan.fillR, plan.fillG, plan.fillB, plan.fillA);
            if (plan.hasStroke) {
                FPDFPageObj_SetStrokeColor(fragObj, plan.strokeR, plan.strokeG,
                                           plan.strokeB, plan.strokeA);
            }

            FPDFPage_InsertObject(page, fragObj);
        }

        // Mark original for removal
        objsToDestroy.insert(plan.originalObj);
    }

    // 8. Fallback: remove text objects that are >70% inside a match bbox but
    //    were NOT caught by the char-to-object mapping (e.g. Form XObject text,
    //    chars with degenerate bounding boxes).
    for (int i = objCount - 1; i >= 0; --i) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (objsToDestroy.count(obj)) continue; // already handled
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

// Helper: run regex over extracted text → produce TextMatch vector.
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

// ── Lifecycle ─────────────────────────────────────────────────────────────────

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

// ── Document open ─────────────────────────────────────────────────────────────

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

// ── Document info / save ──────────────────────────────────────────────────────

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

// ── Page ──────────────────────────────────────────────────────────────────────

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

// ── Render ────────────────────────────────────────────────────────────────────

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
            d[0] = s[2];  // R ← B
            d[1] = s[1];  // G
            d[2] = s[0];  // B ← R
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

// ── Text extraction ───────────────────────────────────────────────────────────

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

// ── Redaction ─────────────────────────────────────────────────────────────────

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

// ── Flatten ───────────────────────────────────────────────────────────────────

int32_t jpdfium_page_flatten(int64_t page) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    int rc = FPDFPage_Flatten(pw->page, FLAT_NORMALDISPLAY);
    return (rc == FLATTEN_SUCCESS || rc == FLATTEN_NOTHINGTODO) ? JPDFIUM_OK : JPDFIUM_ERR_NATIVE;
}

// ── Word-list redaction with padding ──────────────────────────────────────────
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

// ── Convert page to image-based PDF page (strips ALL underlying content) ──────
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

// ── Character position extraction (for testing) ───────────────────────────────
// Returns JSON array with character origins + bounding boxes.
// Used by automated tests to verify that non-redacted text positions are
// preserved exactly after redaction.

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

        double ox, oy;
        if (!FPDFText_GetCharOrigin(tp, i, &ox, &oy)) continue;

        double l, r, b, t;
        FPDFText_GetCharBox(tp, i, &l, &r, &b, &t);

        if (!first) os << ',';
        first = false;
        os << "{\"i\":" << i
           << ",\"u\":" << uni
           << ",\"ox\":" << ox
           << ",\"oy\":" << oy
           << ",\"l\":" << l
           << ",\"r\":" << r
           << ",\"b\":" << b
           << ",\"t\":" << t
           << '}';
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
