// jpdfium_redact_audit.cpp - audits and the crop operation.
//
// Post-pass audits proving no content survives where it must not, plus
// jpdfium_crop_remove_content, which shares their geometry helpers.

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

bool auditNoSurvivorsInRegion(FPDF_PAGE page, const std::vector<FS_RECTF>& regions,
                              const std::vector<FPDF_PAGEOBJECT>& exclude) {
    if (regions.empty()) return true;
    std::unordered_set<FPDF_PAGEOBJECT> excluded(exclude.begin(), exclude.end());

    auto checkObj = [&](auto& self, FPDF_PAGEOBJECT obj, const FS_MATRIX& toPage,
                        int depth) -> bool {
        if (!obj || depth > kMaxFormNesting || excluded.count(obj)) return true;
        int type = FPDFPageObj_GetType(obj);
        if (type != FPDF_PAGEOBJ_TEXT && type != FPDF_PAGEOBJ_IMAGE && type != FPDF_PAGEOBJ_PATH &&
            type != FPDF_PAGEOBJ_SHADING && type != FPDF_PAGEOBJ_FORM)
            return true;

        if (type == FPDF_PAGEOBJ_PATH) {
            int fillMode = 0;
            FPDF_BOOL stroke = 0;
            if (FPDFPath_GetDrawMode(obj, &fillMode, &stroke) && fillMode == FPDF_FILLMODE_NONE &&
                !stroke) {
                return true;
            }
        }

        if (type == FPDF_PAGEOBJ_FORM) {
            int childCount = FPDFFormObj_CountObjects(obj);
            FS_MATRIX childToPage = toPage;
            FS_MATRIX own;
            if (FPDFPageObj_GetMatrix(obj, &own)) childToPage = concatMatrix(own, toPage);
            for (int ci = 0; ci < childCount; ci++) {
                FPDF_PAGEOBJECT child = FPDFFormObj_GetObject(obj, ci);
                if (!child) continue;
                if (!self(self, child, childToPage, depth + 1)) return false;
            }
            return true;
        }

        float ol, ob, or_, ot;
        if (!FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) return true;
        float tol = ol, tob = ob, tor = or_, tot = ot;
        if (depth > 0) {
            const float corners[4][2] = {{ol, ob}, {or_, ob}, {or_, ot}, {ol, ot}};
            tol = std::numeric_limits<float>::max();
            tob = std::numeric_limits<float>::max();
            tor = std::numeric_limits<float>::lowest();
            tot = std::numeric_limits<float>::lowest();
            for (const auto& c : corners) {
                float tx = toPage.a * c[0] + toPage.c * c[1] + toPage.e;
                float ty = toPage.b * c[0] + toPage.d * c[1] + toPage.f;
                tol = std::min(tol, tx);
                tob = std::min(tob, ty);
                tor = std::max(tor, tx);
                tot = std::max(tot, ty);
            }
        }

        for (auto& r : regions) {
            if (isFullyContained(tol, tob, tor, tot, r.left, r.bottom, r.right, r.top)) {
                return false;
            }
            if (type == FPDF_PAGEOBJ_TEXT &&
                overlapRatio(tol, tob, tor, tot, r.left, r.bottom, r.right, r.top) > 0.50f) {
                return false;
            }
        }
        return true;
    };

    int objCount = FPDFPage_CountObjects(page);
    for (int i = 0; i < objCount; i++) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (!obj) continue;
        if (!checkObj(checkObj, obj, kIdentityMatrix, 0)) return false;
    }
    return true;
}

// Redaction

// Crop fast-path test: every content-bearing leaf must lie inside the crop.
// Form objects are never bound-tested (FPDFPageObj_GetBounds is unreliable
// for them); their children are checked recursively instead.
static bool pageContentInsideCrop(FPDF_PAGEOBJECT obj, const FS_MATRIX& toPage, float l, float b,
                                  float r, float t, int depth) {
    if (!obj || depth > kMaxFormNesting) return false;
    if (FPDFPageObj_GetType(obj) == FPDF_PAGEOBJ_FORM) {
        int children = FPDFFormObj_CountObjects(obj);
        if (children <= 0) return false;  // empty or unparsed: cannot verify
        FS_MATRIX own;
        if (!FPDFPageObj_GetMatrix(obj, &own)) return false;
        FS_MATRIX childToPage = concatMatrix(own, toPage);
        for (int i = 0; i < children; i++) {
            if (!pageContentInsideCrop(FPDFFormObj_GetObject(obj, i), childToPage, l, b, r, t,
                                       depth + 1)) {
                return false;
            }
        }
        return true;
    }
    float cl, cb, cr, ct;
    if (!FPDFPageObj_GetBounds(obj, &cl, &cb, &cr, &ct)) return false;
    const float corners[4][2] = {{cl, cb}, {cr, cb}, {cr, ct}, {cl, ct}};
    float tl = std::numeric_limits<float>::max();
    float tb = std::numeric_limits<float>::max();
    float tr = std::numeric_limits<float>::lowest();
    float tt = std::numeric_limits<float>::lowest();
    for (const auto& c : corners) {
        float tx = toPage.a * c[0] + toPage.c * c[1] + toPage.e;
        float ty = toPage.b * c[0] + toPage.d * c[1] + toPage.f;
        tl = std::min(tl, tx);
        tb = std::min(tb, ty);
        tr = std::max(tr, tx);
        tt = std::max(tt, ty);
    }
    return isFullyContained(tl, tb, tr, tt, l, b, r, t);
}

// After the fission pass nothing may remain fully outside the crop: checks
// every non-generated char origin and every content-bearing object (clip
// paths exempt). A false result is a loud REDACT_INCOMPLETE at the call site.
static bool auditNoContentOutsideCrop(FPDF_PAGE page, const FS_RECTF& crop,
                                      FPDF_TEXTPAGE textPage) {
    // 1. Characters: every real (non-generated) char origin must be inside the crop.
    if (textPage) {
        int n = FPDFText_CountChars(textPage);
        for (int ci = 0; ci < n; ci++) {
            if (FPDFText_IsGenerated(textPage, ci) == 1) continue;
            double ox, oy;
            if (!FPDFText_GetCharOrigin(textPage, ci, &ox, &oy)) continue;
            if (ox < crop.left || ox > crop.right || oy < crop.bottom || oy > crop.top) {
                return false;
            }
        }
    }

    // 2. Objects: every content-bearing object must intersect the crop
    //    (pixel-erased images keep their bounds).
    //    A generic recursive lambda (not std::function) keeps this allocation
    //    free, matching the rest of the crop hot path.
    auto walk = [&](auto& self, FPDF_PAGEOBJECT obj, const FS_MATRIX& toPage, int depth) -> bool {
        if (depth > kMaxFormNesting) return true;
        int type = FPDFPageObj_GetType(obj);
        if (type != FPDF_PAGEOBJ_TEXT && type != FPDF_PAGEOBJ_IMAGE && type != FPDF_PAGEOBJ_PATH &&
            type != FPDF_PAGEOBJ_SHADING && type != FPDF_PAGEOBJ_FORM) {
            return true;
        }
        if (type == FPDF_PAGEOBJ_PATH) {
            int fillMode = 0;
            FPDF_BOOL stroke = 0;
            if (FPDFPath_GetDrawMode(obj, &fillMode, &stroke) && fillMode == FPDF_FILLMODE_NONE &&
                !stroke) {
                return true;  // clip path: paints nothing
            }
        }
        if (type == FPDF_PAGEOBJ_FORM) {
            // FPDFPageObj_GetBounds is unreliable for form objects (it can
            // return a degenerate box), so the form's own bbox is not
            // audited; every content-bearing child is checked recursively
            // with its reliable bounds instead.
            int childCount = FPDFFormObj_CountObjects(obj);
            FS_MATRIX childToPage = toPage;
            FS_MATRIX own;
            if (FPDFPageObj_GetMatrix(obj, &own)) childToPage = concatMatrix(own, toPage);
            for (int ci = 0; ci < childCount; ci++) {
                FPDF_PAGEOBJECT child = FPDFFormObj_GetObject(obj, ci);
                if (!child) continue;
                if (!self(self, child, childToPage, depth + 1)) return false;
            }
            return true;
        }
        float l, b, r, t;
        if (FPDFPageObj_GetBounds(obj, &l, &b, &r, &t) && !((r - l) <= 0.01f && (t - b) <= 0.01f)) {
            const float corners[4][2] = {{l, b}, {r, b}, {r, t}, {l, t}};
            float tMinX = std::numeric_limits<float>::max();
            float tMinY = std::numeric_limits<float>::max();
            float tMaxX = std::numeric_limits<float>::lowest();
            float tMaxY = std::numeric_limits<float>::lowest();
            for (const auto& c : corners) {
                float tx = toPage.a * c[0] + toPage.c * c[1] + toPage.e;
                float ty = toPage.b * c[0] + toPage.d * c[1] + toPage.f;
                if (tx < tMinX) tMinX = tx;
                if (ty < tMinY) tMinY = ty;
                if (tx > tMaxX) tMaxX = tx;
                if (ty > tMaxY) tMaxY = ty;
            }
            if (!rectsOverlap(tMinX, tMinY, tMaxX, tMaxY, crop.left, crop.bottom, crop.right,
                              crop.top)) {
                return false;
            }
        }
        return true;
    };

    int objCount = FPDFPage_CountObjects(page);
    for (int i = 0; i < objCount; i++) {
        FPDF_PAGEOBJECT obj = FPDFPage_GetObject(page, i);
        if (!obj) continue;
        if (!walk(walk, obj, kIdentityMatrix, 0)) return false;
    }
    return true;
}

int32_t jpdfium_crop_remove_content(int64_t page, float x, float y, float w, float h) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    if (!std::isfinite(x) || !std::isfinite(y) || !std::isfinite(w) || !std::isfinite(h) ||
        w <= 0.0f || h <= 0.0f)
        return JPDFIUM_ERR_INVALID;

    try {
        const float cL = x, cB = y, cR = x + w, cT = y + h;
        const FS_RECTF cropRect = makePageRect(cL, cB, cR, cT);

        const int fastObjCount = FPDFPage_CountObjects(pw->page);
        bool allInside = true;
        for (int i = 0; i < fastObjCount; ++i) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(pw->page, i);
            if (!obj) continue;
            if (!pageContentInsideCrop(obj, kIdentityMatrix, cL, cB, cR, cT, 0)) {
                allInside = false;
                break;
            }
        }
        if (allInside) return JPDFIUM_OK;
        // Wrap tp in a unique_ptr so it is closed on every exit path, including
        // exceptions from objectFissionRedact or vector growth (std::bad_alloc).
        struct TextPageDeleter {
            void operator()(FPDF_TEXTPAGE p) const noexcept {
                if (p) FPDFText_ClosePage(p);
            }
        };
        using TextPagePtr =
            std::unique_ptr<std::remove_pointer<FPDF_TEXTPAGE>::type, TextPageDeleter>;
        TextPagePtr tp(FPDFText_LoadPage(pw->page));
        if (!tp) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;

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

        // Define the 4 outer margin bounding boxes around the crop rect. They
        // partition everything outside the crop, so they also double as the
        // erase regions for partially visible images.
        struct MarginBox {
            float l, b, r, t;
        };
        MarginBox margins[4];
        int marginCount = 0;
        if (cL > pageMinX) margins[marginCount++] = {pageMinX, pageMinY, cL, pageMaxY};
        if (cR < pageMaxX) margins[marginCount++] = {cR, pageMinY, pageMaxX, pageMaxY};
        if (cB > pageMinY) margins[marginCount++] = {cL, pageMinY, cR, cB};
        if (cT < pageMaxY) margins[marginCount++] = {cL, cT, cR, pageMaxY};

        // One pass: a char is removed when its origin is outside the crop.
        const int count = FPDFText_CountChars(tp.get());
        std::vector<TextMatch> matches;
        matches.reserve(static_cast<size_t>(marginCount));
        for (int mi = 0; mi < marginCount; mi++) {
            TextMatch m;
            m.bboxL = margins[mi].l;
            m.bboxB = margins[mi].b;
            m.bboxR = margins[mi].r;
            m.bboxT = margins[mi].t;
            matches.push_back(std::move(m));
        }
        for (int i = 0; i < count; ++i) {
            double ox, oy;
            if (!FPDFText_GetCharOrigin(tp.get(), i, &ox, &oy)) continue;
            for (int mi = 0; mi < marginCount; mi++) {
                const MarginBox& mb = margins[mi];
                if (ox >= mb.l && ox <= mb.r && oy >= mb.b && oy <= mb.t) {
                    matches[mi].charIndices.push_back(i);
                    break;
                }
            }
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
                if (!pageContentInsideCrop(obj, kIdentityMatrix, cL, cB, cR, cT, 0)) {
                    anythingToRemove = true;
                    break;
                }
            }
        }
        if (!anythingToRemove) {
            return JPDFIUM_OK;
        }

        // Detect whether the page contains Form XObjects or straddling non-text objects (images,
        // paths). EmbedPDF's redact annotation application destroys whole objects whose bounding
        // box intersects the annotation rect. For straddling images (which must be pixel-erased
        // outside while surviving inside) or straddling paths (which must be subpath-clipped) or
        // Form XObjects (which must be recursively searched), Object Fission must be used.
        // For text (and fully contained/outside elements), EPDF redaction provides pristine font
        // preservation.
        bool requiresFission = false;
        for (int i = 0; i < fastObjCount; ++i) {
            FPDF_PAGEOBJECT obj = FPDFPage_GetObject(pw->page, i);
            if (!obj) continue;
            int type = FPDFPageObj_GetType(obj);
            if (type == FPDF_PAGEOBJ_FORM) {
                requiresFission = true;
                break;
            }
            if (type == FPDF_PAGEOBJ_IMAGE || type == FPDF_PAGEOBJ_PATH) {
                float ol, ob, or_, ot;
                if (FPDFPageObj_GetBounds(obj, &ol, &ob, &or_, &ot)) {
                    // Check if it straddles the crop: intersects both inside and outside
                    bool overlapsCrop = rectsOverlap(ol, ob, or_, ot, cL, cB, cR, cT);
                    bool fullyInside = isFullyContained(ol, ob, or_, ot, cL, cB, cR, cT);
                    if (overlapsCrop && !fullyInside) {
                        requiresFission = true;
                        break;
                    }
                }
            }
        }

        // Apply EPDF redactions on the 4 margin rectangles when there are no straddling
        // images/forms. This uses EmbedPDF's native in-place TJ kerning and glyph removal to
        // eliminate text outside the crop without font reconstruction degradation.
        bool epdfOk = !requiresFission;
        bool epdfMutated = false;
        if (epdfOk) {
            for (int mi = 0; mi < marginCount; mi++) {
                const MarginBox& mb = margins[mi];
                if (mb.r <= mb.l || mb.t <= mb.b) continue;

                FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(pw->page, FPDF_ANNOT_REDACT);
                if (!annot) {
                    epdfOk = false;
                    break;
                }
                FS_RECTF rect;
                rect.left = mb.l;
                rect.bottom = mb.b;
                rect.right = mb.r;
                rect.top = mb.t;
                FPDFAnnot_SetRect(annot, &rect);

                uint32_t rem = 0;
                FPDF_BOOL ok = EPDFAnnot_ApplyRedaction(pw->page, annot, &rem);
                if (!ok) {
                    int idx = FPDFPage_GetAnnotIndex(pw->page, annot);
                    if (idx >= 0) FPDFPage_RemoveAnnot(pw->page, idx);
                }
                FPDFPage_CloseAnnot(annot);
                if (!ok) {
                    epdfOk = false;
                    break;
                }
                epdfMutated = true;
            }
        }

        if (!epdfOk && epdfMutated) {
            // tp and matches describe the pre-EPDF page; page is partially mutated so never reuse
            // them.
            return JPDFIUM_ERR_REDACT_INCOMPLETE;
        }

        if (epdfOk) {
            if (marginCount > 0 && pw->core) pw->core->contentRedacted = true;
            FPDFPage_GenerateContent(pw->page);
            // Refresh text page handle after EPDF content mutation; reset() closes the old one.
            tp.reset(FPDFText_LoadPage(pw->page));
            if (!tp) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;

            // Run fission with skipTextObjects = true to handle non-text objects
            // (pixel-erasing images outside crop, clipping path vectors)
            int32_t rc =
                objectFissionRedact(pw->doc, pw->page, tp.get(), matches, 0x00000000, pw->core,
                                    nullptr, &cropRect, /*skipTextObjects=*/true);
            if (rc != JPDFIUM_OK) return rc;
        } else {
            // Fallback: full Object Fission if EPDF redactions were not available
            int32_t rc =
                objectFissionRedact(pw->doc, pw->page, tp.get(), matches, 0x00000000, pw->core,
                                    nullptr, &cropRect, /*skipTextObjects=*/false);
            if (rc != JPDFIUM_OK) return rc;
        }

        // Audit that nothing content-bearing remains fully outside the crop.
        FPDF_TEXTPAGE audit = FPDFText_LoadPage(pw->page);
        if (!audit) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;
        bool auditOk = auditNoContentOutsideCrop(pw->page, cropRect, audit);
        FPDFText_ClosePage(audit);
        if (!auditOk) return JPDFIUM_ERR_REDACT_INCOMPLETE;
        return JPDFIUM_OK;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}
