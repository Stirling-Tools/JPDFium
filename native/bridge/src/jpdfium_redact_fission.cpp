// jpdfium_redact_fission.cpp - object-fission redaction.
//
// Splits straddling text per character, promotes survivors, rebuilds
// content streams and audits the result. The largest stage; kept whole
// because its phases share one traversal state.

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

#ifdef JPDFIUM_HAS_FREETYPE
#include <ft2build.h>
#include FT_FREETYPE_H
#endif

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

// Crop mode (|cropRect| non-null): |matches| are the out-of-crop margins.
// Fully outside objects are removed, the visible part of a straddling object
// is never destroyed (images are pixel-erased outside, vector art is clipped
// by the page boxes).
int32_t objectFissionRedact(FPDF_DOCUMENT doc, FPDF_PAGE page, FPDF_TEXTPAGE textPage,
                            const std::vector<TextMatch>& matches, uint32_t argb,
                            const std::shared_ptr<DocCore>& core,
                            std::vector<FPDF_PAGEOBJECT>* paintedCovers, const FS_RECTF* cropRect,
                            bool skipTextObjects) {
    if (matches.empty()) return JPDFIUM_OK;

    unsigned int alf = (argb >> 24) & 0xFF;
    unsigned int red = (argb >> 16) & 0xFF;
    unsigned int grn = (argb >> 8) & 0xFF;
    unsigned int blu = argb & 0xFF;

    // Only objects whose page-space bbox misses the crop entirely may be destroyed.
    auto fullyOutsideCrop = [&](float l, float b, float r, float t) -> bool {
        return cropRect != nullptr && !rectsOverlap(l, b, r, t, cropRect->left, cropRect->bottom,
                                                    cropRect->right, cropRect->top);
    };

    // Analysis phase (read-only - all text-page queries happen here)

    int totalChars = textPage ? FPDFText_CountChars(textPage) : 0;

    // 1. Collect the set of all char indices targeted for redaction
    std::vector<char> redactSet;
    if (!skipTextObjects && totalChars > 0) {
        redactSet.assign(totalChars, 0);
        for (auto& m : matches) {
            for (int ci : m.charIndices) {
                if (ci >= 0 && ci < totalChars) redactSet[ci] = 1;
            }
        }
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
    std::vector<CharInfo> charInfo;
    std::vector<std::vector<int>> objChars(allObjs.size());
    if (!skipTextObjects) {
        charInfo.resize(totalChars);
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
        objChars.assign(allObjs.size(), {});
        for (int ci = 0; ci < totalChars; ci++) {
            int oi = charInfo[ci].ownerObj;
            if (oi >= 0) objChars[oi].push_back(ci);
        }
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
        FT_Face face = nullptr;         // owned; freed after the plans are built
        std::vector<uint8_t> fontData;  // backing store for |face|, must outlive it
        std::unordered_map<uint32_t, uint32_t> unicodeToGid;
        // glyph id -> advance (font units, FT_LOAD_NO_SCALE), loaded on first use.
        std::unordered_map<uint32_t, int> advances;
        short upem = 0;
        bool isCidKeyed = false;
        bool valid = false;
    };
    std::unordered_map<uintptr_t, FtFontCache> ftCache;

    auto getFtMapping = [&](FPDF_FONT font) -> FtFontCache& {
        uintptr_t key = reinterpret_cast<uintptr_t>(font);
        auto it = ftCache.find(key);
        if (it != ftCache.end()) return it->second;

        FtFontCache& cache = ftCache[key];
        if (loadFontDataWithFallback(font, cache.fontData) && !cache.fontData.empty()) {
            ensureFreeTypeInit();
            FT_Face face;
            if (FT_New_Memory_Face(g_ft_lib, cache.fontData.data(),
                                   static_cast<FT_Long>(cache.fontData.size()), 0, &face) == 0) {
                cache.face = face;
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
                cache.valid = !cache.unicodeToGid.empty();
            }
        }
        return cache;
    };
#endif

    std::vector<FissionPlan> plans;
    std::unordered_set<FPDF_PAGEOBJECT> objsToDestroy;

    if (!skipTextObjects)
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
                FtFontCache& ft = getFtMapping(plan.font);
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
                    if (ait == ft.advances.end()) {
                        // Lazy advance load: only glyphs that appear in a survivor
                        // run are loaded (a full-cmap sweep costs thousands of
                        // FT_Load_Glyph calls per crop).
                        if (!ft.face || FT_Load_Glyph(ft.face, git->second, FT_LOAD_NO_SCALE) != 0)
                            continue;
                        ait = ft.advances.emplace(git->second, ft.face->glyph->advance.x).first;
                    }
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

#ifdef JPDFIUM_HAS_FREETYPE
    // The deviation detector is done with the faces once every plan is built.
    for (auto& [key, cache] : ftCache) {
        if (cache.face) FT_Done_Face(cache.face);
    }
    ftCache.clear();
#endif

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

    // All page-level insertions (fission fragments, rebuilt paths,
    // re-parented nested objects) are collected here and applied later in one
    // pass, ordered by the original page indices captured BEFORE any
    // modification, so survivors keep their original paint order
    // (FPDFPage_InsertObjectAtIndex).
    struct Insertion {
        FPDF_PAGEOBJECT obj = nullptr;
        int insertIndex = 0;  // page index captured before any modification
        int ordinal = 0;      // paint-order tie-breaker (child index in parent form)
        int runIndex = 0;     // fragment order within its plan
    };
    std::vector<Insertion> insertions;
    // Reused scratch for image erase rectangles (one allocation per pass).
    std::vector<FS_RECTF> eraseRectsBuffer;
    // Parents of children detached in markFormContents: their stale stream
    // must be regenerated by the step 9b promotion or the moved child renders twice.
    std::set<FPDF_PAGEOBJECT> detachedFromForms;
    // Nested images the crop will promote (decided by the pre-pass below).
    std::unordered_set<FPDF_PAGEOBJECT> promotionCandidates;
    // True when a bitmap was rewritten: incremental save must be refused even
    // when no object was destroyed or edited.
    bool pixelsErased = false;
    // Crop mode: visible content was dropped because it could not be handled
    // safely (failed replacement or un-erasable straddling image).
    bool cropReplacementFailed = false;

    // Bounds of |obj| (parent space) transformed into page space.
    auto transformedBounds = [](FPDF_PAGEOBJECT obj, const FS_MATRIX& m, float& l, float& b,
                                float& r, float& t) -> bool {
        float cl, cb, cr, ct;
        if (!FPDFPageObj_GetBounds(obj, &cl, &cb, &cr, &ct)) return false;
        const float corners[4][2] = {{cl, cb}, {cr, cb}, {cr, ct}, {cl, ct}};
        l = std::numeric_limits<float>::max();
        b = std::numeric_limits<float>::max();
        r = std::numeric_limits<float>::lowest();
        t = std::numeric_limits<float>::lowest();
        for (const auto& c : corners) {
            float tx = m.a * c[0] + m.c * c[1] + m.e;
            float ty = m.b * c[0] + m.d * c[1] + m.f;
            l = std::min(l, tx);
            b = std::min(b, ty);
            r = std::max(r, tx);
            t = std::max(t, ty);
        }
        return true;
    };

    // Paint position of a nested object within its form chain. |leaving|
    // candidates (promoted out of their form) and fully outside siblings are
    // skipped: neither paints. Sibling bounds live in the PARENT form's
    // children space. A nested parent's ObjRef.toPage already maps that space
    // to the page; a page-level parent has identity toPage, so its own matrix
    // is the map. Using the child's toPage would apply the parent matrix twice.
    auto classifyPaintPosition = [&](const ObjRef& start, bool& hasEarlier, bool& hasLater) {
        hasEarlier = false;
        hasLater = false;
        int ordinal = start.ordinal;
        FPDF_PAGEOBJECT parent = start.parentForm;
        while (parent) {
            auto pit = objPtrToIndex.find(reinterpret_cast<uintptr_t>(parent));
            if (pit == objPtrToIndex.end()) break;
            const ObjRef& pref = allObjs[pit->second];
            FS_MATRIX levelToPage;
            if (pref.parentForm) {
                levelToPage = pref.toPage;
            } else if (!FPDFPageObj_GetMatrix(parent, &levelToPage)) {
                break;
            }
            int siblings = FPDFFormObj_CountObjects(parent);
            for (int si = 0; si < siblings; si++) {
                if (si == ordinal) continue;
                FPDF_PAGEOBJECT sib = FPDFFormObj_GetObject(parent, si);
                if (!sib || promotionCandidates.count(sib)) continue;
                float l, b, r, t;
                // Form bounds are unreliable (often degenerate): a form
                // sibling painting inside the crop must never be skipped on
                // bounds evidence, or a sandwiched image is misclassified.
                if (FPDFPageObj_GetType(sib) != FPDF_PAGEOBJ_FORM &&
                    transformedBounds(sib, levelToPage, l, b, r, t) &&
                    fullyOutsideCrop(l, b, r, t)) {
                    continue;  // destroyed, never paints
                }
                if (si < ordinal) {
                    hasEarlier = true;
                } else {
                    hasLater = true;
                }
            }
            ordinal = pref.ordinal;
            parent = pref.parentForm;
        }
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
                    // Matrix order matches CPDF_TextPage::ProcessFormObject.
                    self(self, child, concatMatrix(childMatrix, parentToPage), depth + 1);
                }
                // Form bounds are unreliable; in crop mode its children decide.
                if (cropRect) continue;
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

            if (cropRect) {
                if (fullyOutsideCrop(tMinX, tMinY, tMaxX, tMaxY)) {
                    objsToDestroy.insert(child);
                    continue;
                }
                // A modified nested image is promoted to the page: SetBitmap
                // does not dirty the form, so an in-place change would never
                // reach the saved file (step 9b handles the stream).
                if (childType == FPDF_PAGEOBJ_IMAGE) {
                    FS_MATRIX childMatrix;
                    if (!FPDFPageObj_GetMatrix(child, &childMatrix)) {
                        objsToDestroy.insert(child);
                        cropReplacementFailed = true;
                        continue;
                    }
                    FS_MATRIX childToPage = concatMatrix(childMatrix, parentToPage);
                    eraseRectsBuffer.clear();
                    for (auto& m : matches) {
                        if (rectsOverlap(tMinX, tMinY, tMaxX, tMaxY, m.bboxL, m.bboxB, m.bboxR,
                                         m.bboxT)) {
                            eraseRectsBuffer.push_back(
                                makePageRect(m.bboxL, m.bboxB, m.bboxR, m.bboxT));
                        }
                    }
                    if (eraseRectsBuffer.empty()) continue;  // fully inside: leave it
                    if (!eraseImagePixels(doc, page, child, childToPage,
                                          std::span<const FS_RECTF>(eraseRectsBuffer), argb,
                                          &pixelsErased)) {
                        objsToDestroy.insert(child);
                        cropReplacementFailed = true;
                        continue;
                    }
                    auto pit = objPtrToIndex.find(reinterpret_cast<uintptr_t>(child));
                    if (pit == objPtrToIndex.end()) continue;
                    const ObjRef& ref = allObjs[pit->second];
                    if (ref.topFormObj && objsToDestroy.count(ref.topFormObj)) {
                        continue;  // freed with the destroyed top form
                    }
                    // A page-level object sits before or after the WHOLE top
                    // form. The pre-pass already rejected sandwiched images,
                    // so only the "paints later" side decides placement.
                    bool hasEarlier = false;
                    bool hasLater = false;
                    classifyPaintPosition(ref, hasEarlier, hasLater);
                    // Detach before promoting, or the form still renders the child.
                    // A failed detach keeps the child owned by the form: queuing
                    // it for destruction would free memory the form still
                    // references, and the in-place erase never reaches the
                    // file, so fail loudly instead.
                    if (!FPDFFormObj_RemoveObject(formObj, child)) {
                        cropReplacementFailed = true;
                        continue;
                    }
                    detachedFromForms.insert(formObj);
                    FPDFPageObj_SetMatrix(child, &childToPage);
                    int targetIdx = hasLater ? ref.topFormPageIndex : (ref.topFormPageIndex + 1);
                    insertions.push_back({child, targetIdx, ref.ordinal, -1});
                }
                continue;
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

    if (cropRect) {
        // Decide every promotion before mutating anything. A promoted image
        // can only sit before or after the whole top form, so it must be the
        // first or last painted content of its form chain; a sandwiched image
        // fails loudly here, with nothing erased or detached yet.
        for (const ObjRef& ref : allObjs) {
            if (!ref.parentForm || FPDFPageObj_GetType(ref.obj) != FPDF_PAGEOBJ_IMAGE) continue;
            float l, b, r, t;
            if (!transformedBounds(ref.obj, ref.toPage, l, b, r, t)) continue;
            if (fullyOutsideCrop(l, b, r, t)) continue;  // destroyed, not promoted
            for (const auto& m : matches) {
                if (rectsOverlap(l, b, r, t, m.bboxL, m.bboxB, m.bboxR, m.bboxT)) {
                    promotionCandidates.insert(ref.obj);
                    break;
                }
            }
        }
        for (FPDF_PAGEOBJECT cand : promotionCandidates) {
            auto cit = objPtrToIndex.find(reinterpret_cast<uintptr_t>(cand));
            if (cit == objPtrToIndex.end()) continue;
            bool hasEarlier = false;
            bool hasLater = false;
            classifyPaintPosition(allObjs[cit->second], hasEarlier, hasLater);
            if (hasEarlier && hasLater) return JPDFIUM_ERR_REDACT_INCOMPLETE;
        }
    }

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
        // Forms are not bound-tested in crop mode: descend regardless.
        if (!anyOverlap && !(cropRect && type == FPDF_PAGEOBJ_FORM)) continue;

        if (type == FPDF_PAGEOBJ_IMAGE) {
            // Redaction: fully contained or >70% covered images are removed.
            // Crop: only fully outside images are; a straddling image keeps
            // its visible part and is pixel-erased outside.
            bool remove = false;
            if (cropRect) {
                remove = fullyOutsideCrop(ol, ob, or_, ot);
            } else {
                for (auto& m : matches) {
                    if (isFullyContained(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) ||
                        overlapRatio(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT) > 0.70f) {
                        remove = true;
                        break;
                    }
                }
            }
            if (remove) {
                objsToDestroy.insert(obj);
            } else {
                eraseRectsBuffer.clear();
                for (auto& m : matches) {
                    if (rectsOverlap(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT)) {
                        eraseRectsBuffer.push_back(
                            makePageRect(m.bboxL, m.bboxB, m.bboxR, m.bboxT));
                    }
                }
                bool erased = true;
                FS_MATRIX imgMatrix;
                if (!FPDFPageObj_GetMatrix(obj, &imgMatrix)) {
                    erased = false;
                } else {
                    erased = eraseImagePixels(doc, page, obj, imgMatrix,
                                              std::span<const FS_RECTF>(eraseRectsBuffer), argb,
                                              &pixelsErased);
                }
                if (!erased) {
                    // Doctrine: pixel-true erase or full removal, never cover-and-keep.
                    objsToDestroy.insert(obj);
                    // Crop-only signal: in redaction mode the same outcome
                    // (removal) has always meant success, and flagging it
                    // would throw RedactIncompleteException for a fully
                    // redacted page.
                    if (cropRect) cropReplacementFailed = true;
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

            // Crop: remove fully outside paths, keep partial ones (dropping
            // subpaths can change fill-rule holes and corrupt visible pixels).
            if (cropRect) {
                if (fullyOutsideCrop(ol, ob, or_, ot)) {
                    objsToDestroy.insert(obj);
                }
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
            // Shading: fully outside (crop) or fully covered (redaction).
            if (cropRect) {
                if (fullyOutsideCrop(ol, ob, or_, ot)) objsToDestroy.insert(obj);
            } else {
                for (auto& m : matches) {
                    if (isFullyContained(ol, ob, or_, ot, m.bboxL, m.bboxB, m.bboxR, m.bboxT)) {
                        objsToDestroy.insert(obj);
                        break;
                    }
                }
            }
        } else if (type == FPDF_PAGEOBJ_FORM) {
            // Crop: always descend (form bounds are unreliable); outside
            // children are removed. Redaction: remove when fully covered or
            // >70% covered with no surviving text.
            if (cropRect) {
                FS_MATRIX cropFormMatrix;
                if (FPDFPageObj_GetMatrix(obj, &cropFormMatrix)) {
                    markFormContents(markFormContents, obj, cropFormMatrix, 0);
                }
                continue;
            }

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

    auto boundsValid = [](FPDF_PAGEOBJECT obj) -> bool {
        float fl, fb, fr, ft;
        if (!FPDFPageObj_GetBounds(obj, &fl, &fb, &fr, &ft)) return false;
        float w = fr - fl, h = ft - fb;
        return w >= 0.01f || h >= 0.01f;
    };

    // Round-trip the object's DECODED text and compare it against the
    // expected string (ligature spelling normalized). This catches silent
    // glyph drops / .notdef substitutions that a bounds check alone would
    // miss. The text page is still valid here (all removals happen later).
    //
    // Returns: 1 = verified match, 0 = verified mismatch, -1 = cannot decode
    // (inline fonts without a usable encoding) - in that case only the
    // bounds/width checks validate the emission.
    auto fragmentTextStatus = [&](FPDF_PAGEOBJECT obj,
                                  const std::vector<uint16_t>& expected) -> int {
        unsigned long needBytes = FPDFTextObj_GetText(obj, textPage, nullptr, 0);
        if (needBytes <= sizeof(FPDF_WCHAR)) return -1;
        size_t numChars = needBytes / sizeof(FPDF_WCHAR);
        std::vector<FPDF_WCHAR> buf(numChars, 0);
        if (FPDFTextObj_GetText(obj, textPage, buf.data(), needBytes) != needBytes) return -1;
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

    // Encode |frag| into |obj|, place it with |matrix|, copy mode and colors.
    // |verifyWithTextPage| is false for in-place edits (the text page is stale
    // after SetText, so the round trip would compare against the old text).
    // |invChainForGate| pulls the expected box into form-local space.
    // |mutatedOut| reports whether the object's text was replaced.
    auto emitFragment = [&](FPDF_PAGEOBJECT obj, const TextFragment& frag, const FissionPlan& plan,
                            const FS_MATRIX& matrix, bool verifyWithTextPage,
                            const FS_MATRIX* invChainForGate, bool* mutatedOut = nullptr) -> bool {
        if (mutatedOut) *mutatedOut = false;
        if (frag.utf16.size() <= 1) return false;  // null-only
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
                textOk = FPDFText_SetText(obj, reinterpret_cast<FPDF_WIDESTRING>(cand->data()));
                if (!textOk) continue;
                if (mutatedOut) *mutatedOut = true;
                if (!verifyWithTextPage) {
                    break;  // first successful candidate; the width gate validates it
                }
                emissionStatus = fragmentTextStatus(obj, *cand);
                if (emissionStatus != 0) break;  // 1 verified, -1 width-gated
                textOk = false;
            }
            if (textOk) boundsOk = boundsValid(obj);
        }

        // Strategy B (legacy FreeType GID injection) was removed because
        // FreeType GIDs are glyph IDs inside the font program and do not
        // correspond to PDF content stream character codes for subset CID and
        // custom-encoded fonts. Passing raw GIDs to FPDFText_SetCharcodes
        // causes glyph misalignment and missing lowercase characters.
        // Strategy A (Unicode) and Strategy C (WinAnsi) ensure valid PDF encoding.
        // If encoding is impossible, fission safely keeps original under the redaction box.

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
                    FPDFText_SetCharcodes(obj, codes.data(), codes.size());
                    if (mutatedOut) *mutatedOut = true;
                    emissionStatus = fragmentTextStatus(obj, expectedText);
                    if (emissionStatus != 0) {
                        textOk = true;
                        boundsOk = boundsValid(obj);
                        break;
                    }
                }
            }
        }

        if (!textOk || !boundsOk) return false;

        FPDFPageObj_SetMatrix(obj, &matrix);

        // Loose width gate for emissions that could not be verified by
        // decoded-text round-trip and do not use a standard-14 font:
        // wrong glyphs from custom encodings change the glyph sequence
        // and therefore the bounding box. Standard-14 fonts are trusted
        // (fixed encoding); Strategy A is trusted (direct Unicode encoding
        // via PDFium font map); the tolerance is loose enough for Tc/Tw
        // spacing drift while still rejecting wholesale garbage.
        if (emissionStatus == -1 && !isStandard14Font(plan.font) && frag.hasExpectedBox) {
            // Expected box in the object's own coordinate space.
            float eL = frag.expL, eB = frag.expB, eR = frag.expR, eT = frag.expT;
            if (invChainForGate) {
                const float corners[4][2] = {{frag.expL, frag.expB},
                                             {frag.expR, frag.expB},
                                             {frag.expR, frag.expT},
                                             {frag.expL, frag.expT}};
                eL = std::numeric_limits<float>::max();
                eB = std::numeric_limits<float>::max();
                eR = std::numeric_limits<float>::lowest();
                eT = std::numeric_limits<float>::lowest();
                for (const auto& c : corners) {
                    float tx =
                        invChainForGate->a * c[0] + invChainForGate->c * c[1] + invChainForGate->e;
                    float ty =
                        invChainForGate->b * c[0] + invChainForGate->d * c[1] + invChainForGate->f;
                    if (tx < eL) eL = tx;
                    if (ty < eB) eB = ty;
                    if (tx > eR) eR = tx;
                    if (ty > eT) eT = ty;
                }
            }
            float fl, fb, fr, ft;
            if (!FPDFPageObj_GetBounds(obj, &fl, &fb, &fr, &ft)) return false;
            // Wrong-glyph emissions (custom encodings) almost always
            // change the glyph HEIGHTS, so the height check is tight.
            // The width check is deliberately loose: character spacing
            // (Tc) is not re-emitted by the fission, so correctly mapped
            // fragments may be narrower than the source run.
            float tolH = std::max(2.0f, (eT - eB) * 0.10f);
            float tolWL = std::max(2.0f, (eR - eL) * 0.10f);
            float tolWR = std::max(3.0f, (eR - eL) * 0.40f);
            if (std::abs(fl - eL) > tolWL || std::abs(fr - eR) > tolWR ||
                std::abs(fb - eB) > tolH || std::abs(ft - eT) > tolH) {
                return false;
            }
        }

        FPDFTextObj_SetTextRenderMode(obj, plan.renderMode);

        // Restore original text colors
        FPDFPageObj_SetFillColor(obj, plan.fillR, plan.fillG, plan.fillB, plan.fillA);
        if (plan.hasStroke) {
            FPDFPageObj_SetStrokeColor(obj, plan.strokeR, plan.strokeG, plan.strokeB, plan.strokeA);
        }
        return true;
    };

    bool inPlaceEdited = false;

    for (auto& plan : plans) {
        fissionAttempted.insert(plan.originalObj);
        bool originalDropped = false;

        // One surviving run edits the original object in place. New objects
        // land in a stream appended after all existing content, so they would
        // paint above later page content. A form child is first detached from
        // its form (which marks the form stream dirty; that is the only
        // dirty-stream hook) and promoted to the page: the existing object
        // then serializes at its page list position, keeping paint order.
        if (plan.fragments.size() == 1) {
            const TextFragment& frag = plan.fragments[0];
            bool canEdit = true;
            bool promotedFromForm = false;
            if (plan.parentForm) {
                auto pit = objPtrToIndex.find(reinterpret_cast<uintptr_t>(plan.originalObj));
                if (pit == objPtrToIndex.end()) {
                    canEdit = false;
                } else {
                    const ObjRef& ref = allObjs[pit->second];
                    if (ref.topFormObj && objsToDestroy.count(ref.topFormObj)) {
                        canEdit = false;  // freed with the destroyed top form
                    } else if (!FPDFFormObj_RemoveObject(plan.parentForm, plan.originalObj)) {
                        canEdit = false;
                    } else {
                        detachedFromForms.insert(plan.parentForm);
                        promotedFromForm = true;
                    }
                }
            }
            if (canEdit) {
                bool mutated = false;
                if (emitFragment(plan.originalObj, frag, plan, frag.matrix,
                                 /*verifyWithTextPage=*/false, nullptr, &mutated)) {
                    inPlaceEdited = true;
                    if (promotedFromForm) {
                        auto pit =
                            objPtrToIndex.find(reinterpret_cast<uintptr_t>(plan.originalObj));
                        if (pit != objPtrToIndex.end()) {
                            const ObjRef& ref = allObjs[pit->second];
                            insertions.push_back(
                                {plan.originalObj, ref.topFormPageIndex + 1, ref.ordinal, -1});
                        }
                    }
                    continue;  // edited in place: nothing to destroy
                }
                // A mutated object cannot be trusted; never rebuild it from
                // decoded Unicode (that drops TJ adjustments). Drop it instead.
                // A detached object is already off the page and out of its
                // form, so it must be dropped even when the edit changed
                // nothing: leaving it orphaned would silently lose the text.
                if (mutated || promotedFromForm) {
                    objsToDestroy.insert(plan.originalObj);
                    originalDropped = true;
                }
            }
            // In-place edit failed (or cannot be verified): fall through to
            // the create-new-object path below.
        }

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
                    std::vector<uint8_t> fontData;
                    if (loadFontDataWithFallback(plan.font, fontData) && !fontData.empty()) {
                        FPDF_FONT lf = FPDFText_LoadFont(doc, fontData.data(),
                                                         static_cast<uint32_t>(fontData.size()),
                                                         FPDF_FONT_TRUETYPE, 1);
                        if (lf) {
                            if (core) core->loadedFonts.push_back(lf);
                            fragFont = lf;
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
            if (!emitFragment(fragObj, frag, plan, frag.matrix, /*verifyWithTextPage=*/true,
                              nullptr)) {
                FPDFPageObj_Destroy(fragObj);
                allOk = false;
                break;
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
            // Crop mode: a dropped original is visible content lost INSIDE the
            // crop, which the outside-only audit cannot see; fail loudly.
            if (cropRect && originalDropped) cropReplacementFailed = true;
        }
    }

    const int32_t pendingError = cropReplacementFailed ? JPDFIUM_ERR_REDACT_INCOMPLETE : JPDFIUM_OK;

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
        if (skipTextObjects) break;
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (!obj) continue;
        if (objsToDestroy.count(obj)) continue;     // already marked
        if (fissionAttempted.count(obj)) continue;  // fission handled it
        int type = FPDFPageObj_GetType(obj);
        if (type != FPDF_PAGEOBJ_TEXT) continue;

        float ol, ob, or_, ot;
        if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) continue;

        if (cropRect) {
            // Crop: unmapped TEXT fully outside the crop is unreachable
            // content - remove it. Partial text objects are handled by
            // fission; one that fission could not split is left in place
            // (its visible part survives, the post-pass audit flags any
            // glyph that still sticks out).
            if (fullyOutsideCrop(ol, ob, or_, ot)) objsToDestroy.insert(obj);
            continue;
        }

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
    std::set<FPDF_PAGEOBJECT> dirtyForms(detachedFromForms.begin(), detachedFromForms.end());
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
    if ((!objsToDestroy.empty() || !reParentForms.empty() || inPlaceEdited || pixelsErased) &&
        core) {
        core->contentRedacted = true;
    }
    return pendingError;
}
