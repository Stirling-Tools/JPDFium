// jpdfium_redact.cpp - public redaction API surface.
//
// The redaction engine itself lives in focused translation units (text,
// fonts, image, fission, pattern, audit) sharing jpdfium_redact_internal.h.
// This file keeps the C entry points plus the thread-local scratch they run on.

#include <epdf_annot.h>
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

#ifdef JPDFIUM_HAS_PCRE2
#define PCRE2_CODE_UNIT_WIDTH 32
#include <pcre2.h>
#endif

struct RedactScratch {
    std::vector<int> idxMap;
    std::u32string wtext;
    std::vector<TextMatch> matches;
    std::vector<uint32_t> unicodeSeq;
    std::vector<char> redactSet;
    std::vector<FS_RECTF> appliedRects;
    std::vector<TextMatch> sortedMatches;
    std::vector<int> auditIdxMap;
    std::u32string auditWtext;
    std::vector<TextMatch> auditRemaining;
    std::vector<FS_RECTF> auditRects;
    std::vector<char> auditTouched;
    std::vector<char> auditTouched2;

    static constexpr size_t kMaxRetainedCapacity = 65536;

    void clear() {
        idxMap.clear();
        wtext.clear();
        matches.clear();
        unicodeSeq.clear();
        redactSet.clear();
        appliedRects.clear();
        sortedMatches.clear();
        auditIdxMap.clear();
        auditWtext.clear();
        auditRemaining.clear();
        auditRects.clear();
        auditTouched.clear();
        auditTouched2.clear();
    }

    void trimOversized() {
        // Swap with an empty vector instead of shrink_to_fit(): this vector is
        // about to be reused for the next job, so retaining elements only pays for
        // a reallocation-and-copy. Swapping releases the pages back immediately.
        auto trimVec = [](auto& vec) {
            if (vec.capacity() > kMaxRetainedCapacity) {
                std::decay_t<decltype(vec)>().swap(vec);
            }
        };
        trimVec(idxMap);
        if (wtext.capacity() > kMaxRetainedCapacity) std::decay_t<decltype(wtext)>().swap(wtext);
        trimVec(matches);
        trimVec(unicodeSeq);
        trimVec(redactSet);
        trimVec(appliedRects);
        trimVec(sortedMatches);
        trimVec(auditIdxMap);
        if (auditWtext.capacity() > kMaxRetainedCapacity)
            std::decay_t<decltype(auditWtext)>().swap(auditWtext);
        trimVec(auditRemaining);
        trimVec(auditRects);
        trimVec(auditTouched);
        trimVec(auditTouched2);
    }
};

static thread_local RedactScratch t_redactScratch;

struct ScratchGuard {
    ~ScratchGuard() {
        t_redactScratch.trimOversized();
    }
};

// A redaction that mutates the page and then stops (JPDFIUM_ERR_REDACT_INCOMPLETE)
// leaves the surviving content extractable, so record the applied zones and the
// page whose redaction did not finish, with the areas it covered.
// DocCore::hasIncompleteRedaction then makes every save path fail closed until a
// later commit actually resolves THAT page (see resolveIncompleteRedaction); the
// record is per page, so a commit elsewhere cannot clear the failure.
static void markRedactionIncomplete(PageWrapper* pw, const std::vector<RedactArea>& applied) {
    if (!pw || !pw->core) return;
    pw->core->contentRedacted = true;
    std::vector<RedactArea>& areas = pw->core->incompleteRedactPages[pw->pageIndex];
    for (const RedactArea& a : applied) {
        pw->core->addRedactZone(pw->pageIndex, a.rect.left, a.rect.bottom, a.rect.right,
                                a.rect.top);
        areas.push_back(a);
    }
}

static void markRedactionIncomplete(PageWrapper* pw, const std::vector<FS_RECTF>& applied) {
    std::vector<RedactArea> areas;
    areas.reserve(applied.size());
    for (const FS_RECTF& r : applied) areas.push_back(RedactArea{r, {}});
    markRedactionIncomplete(pw, areas);
}

// The area a match's REDACT mark covers: its quad where it has one, since that,
// not the bbox around it, is what the redactor applies on rotated text.
static RedactArea areaOf(const TextMatch& m) {
    RedactArea area{makePageRect(m.bboxL, m.bboxB, m.bboxR, m.bboxT), {}};
    if (m.hasQuad) area.quads.push_back(m.quad);
    return area;
}

static void markRedactionIncomplete(PageWrapper* pw, const std::vector<TextMatch>& matches) {
    std::vector<RedactArea> areas;
    areas.reserve(matches.size());
    for (const TextMatch& m : matches) areas.push_back(areaOf(m));
    markRedactionIncomplete(pw, areas);
}

// Paints the cover over a redacted area in `argb` (nothing when fully
// transparent): over each quad where the area has them, so a cover on rotated
// text stops at the word as the redaction did, else over its rect.
static void paintCover(FPDF_PAGE page, const RedactArea& area, uint32_t argb) {
    unsigned int alf = (argb >> 24) & 0xFF;
    if (alf == 0) return;
    unsigned int red = (argb >> 16) & 0xFF;
    unsigned int grn = (argb >> 8) & 0xFF;
    unsigned int blu = argb & 0xFF;
    std::vector<FPDF_PAGEOBJECT> shapes;
    if (area.quads.empty()) {
        shapes.push_back(FPDFPageObj_CreateNewRect(area.rect.left, area.rect.bottom,
                                                   area.rect.right - area.rect.left,
                                                   area.rect.top - area.rect.bottom));
    } else {
        for (const FS_QUADPOINTSF& q : area.quads) {
            FPDF_PAGEOBJECT path = FPDFPageObj_CreateNewPath(q.x1, q.y1);
            if (!path) continue;
            FPDFPath_LineTo(path, q.x2, q.y2);
            FPDFPath_LineTo(path, q.x4, q.y4);
            FPDFPath_LineTo(path, q.x3, q.y3);
            FPDFPath_Close(path);
            shapes.push_back(path);
        }
    }
    for (FPDF_PAGEOBJECT shape : shapes) {
        if (!shape) continue;
        FPDFPageObj_SetFillColor(shape, red, grn, blu, alf);
        FPDFPath_SetDrawMode(shape, FPDF_FILLMODE_ALTERNATE, 0);
        FPDFPage_InsertObject(page, shape);
    }
}

namespace {
// A REDACT annotation a commit applies, with the area it covers.
struct CommitMark {
    FPDF_ANNOTATION annot = nullptr;
    RedactArea area;
};
}  // namespace

// FS_QUADPOINTSF slot order: (x1,y1)-(x2,y2) is the top edge and (x3,y3)-(x4,y4)
// the bottom one, so the polygon runs 1-2-4-3. A point on an edge counts as inside;
// a quad whose corners coincide contains nothing.
static bool pointInQuad(double x, double y, const FS_QUADPOINTSF& q) {
    const double px[4] = {q.x1, q.x2, q.x4, q.x3};
    const double py[4] = {q.y1, q.y2, q.y4, q.y3};
    int side = 0;
    for (int i = 0; i < 4; ++i) {
        int j = (i + 1) % 4;
        double cross = (px[j] - px[i]) * (y - py[i]) - (py[j] - py[i]) * (x - px[i]);
        if (cross == 0) continue;
        int s = cross > 0 ? 1 : -1;
        if (side == 0) {
            side = s;
        } else if (s != side) {
            return false;
        }
    }
    return side != 0;
}

// Same tolerance as the redactor's own quad checks (kRegionQuadTolerance).
static constexpr float kQuadTolerance = 1e-4f;

// A usable tilted quad, read as the redactor reads one: finite corners, edges of
// real length, opposite edges running the same way, and one winding at both
// ends.
static bool quadWellFormed(const FS_QUADPOINTSF& q) {
    const float xs[4] = {q.x1, q.x2, q.x3, q.x4};
    const float ys[4] = {q.y1, q.y2, q.y3, q.y4};
    for (int i = 0; i < 4; ++i) {
        if (!std::isfinite(xs[i]) || !std::isfinite(ys[i])) return false;
    }
    float ux = q.x2 - q.x1, uy = q.y2 - q.y1;  // top edge
    float lx = q.x4 - q.x3, ly = q.y4 - q.y3;  // bottom edge
    float sx = q.x3 - q.x1, sy = q.y3 - q.y1;  // start side
    float ex = q.x4 - q.x2, ey = q.y4 - q.y2;  // end side
    float lenU = std::hypot(ux, uy), lenL = std::hypot(lx, ly);
    float lenS = std::hypot(sx, sy), lenE = std::hypot(ex, ey);
    if (lenU <= kQuadTolerance || lenL <= kQuadTolerance || lenS <= kQuadTolerance ||
        lenE <= kQuadTolerance)
        return false;
    if (ux * lx + uy * ly <= 0 || sx * ex + sy * ey <= 0) return false;
    float startArea = ux * sy - uy * sx;
    float endArea = lx * ey - ly * ex;
    if (std::fabs(startArea) <= kQuadTolerance * lenU * lenS) return false;
    if (std::fabs(endArea) <= kQuadTolerance * lenL * lenE) return false;
    return std::signbit(startArea) == std::signbit(endArea);
}

static bool quadAxisAligned(const FS_QUADPOINTSF& q) {
    float scale =
        std::max({1.0f, std::hypot(q.x2 - q.x1, q.y2 - q.y1), std::hypot(q.x4 - q.x3, q.y4 - q.y3),
                  std::hypot(q.x3 - q.x1, q.y3 - q.y1), std::hypot(q.x4 - q.x2, q.y4 - q.y2)});
    float tol = kQuadTolerance * scale;
    return std::fabs(q.y1 - q.y2) <= tol && std::fabs(q.y3 - q.y4) <= tol &&
           std::fabs(q.x1 - q.x3) <= tol && std::fabs(q.x2 - q.x4) <= tol;
}

// The areas the redactor actually applies for a mark, so the audit checks what
// was redacted rather than what the mark declared: a well-formed tilted quad as
// itself, any other quad as the box around its corners, and the mark's rect
// alone when a quad has non-finite corners or no area, since the redactor then
// ignores the quads.
static void appendRedactorAreas(const RedactArea& mark, std::vector<RedactArea>& out) {
    std::vector<RedactArea> areas;
    for (const FS_QUADPOINTSF& q : mark.quads) {
        const float xs[4] = {q.x1, q.x2, q.x3, q.x4};
        const float ys[4] = {q.y1, q.y2, q.y3, q.y4};
        bool finite = true;
        for (int i = 0; i < 4; ++i) finite = finite && std::isfinite(xs[i]) && std::isfinite(ys[i]);
        float l = std::min({xs[0], xs[1], xs[2], xs[3]});
        float r = std::max({xs[0], xs[1], xs[2], xs[3]});
        float b = std::min({ys[0], ys[1], ys[2], ys[3]});
        float t = std::max({ys[0], ys[1], ys[2], ys[3]});
        if (!finite || r <= l || t <= b) {
            out.push_back(RedactArea{mark.rect, {}});
            return;
        }
        RedactArea area{makePageRect(l, b, r, t), {}};
        if (quadWellFormed(q) && !quadAxisAligned(q)) area.quads.push_back(q);
        areas.push_back(std::move(area));
    }
    if (areas.empty()) {
        out.push_back(RedactArea{mark.rect, {}});
        return;
    }
    for (RedactArea& a : areas) out.push_back(std::move(a));
}

// Whether text survived in the areas a redaction covered: hardens commit with the
// same verified-removal check the other redaction paths run. The redactor reports
// success without saying which glyphs it removed, and it passes over glyphs it
// cannot measure (one with no outline is a point at its origin), so the page is
// re-extracted: a char at least half inside a plain area, or centred inside a
// quad of a quad area, survived it. A quad area is not judged by its rect, which
// also spans text beside rotated quads that the redactor rightly kept. Chars
// PDFium generates (separators it inserts between runs) have no content to
// remove, and an outline-less glyph is judged by the cell a cover for it spans.
static bool textLeftIn(FPDF_TEXTPAGE audit, const std::vector<RedactArea>& areas) {
    int n = FPDFText_CountChars(audit);
    for (int ci = 0; ci < n; ++ci) {
        if (FPDFText_IsGenerated(audit, ci) == 1) continue;
        double l, r, b, t;
        if (!outlineAwareCharBox(audit, ci, &l, &r, &b, &t)) continue;
        for (const RedactArea& area : areas) {
            if (area.quads.empty()) {
                if (charInRect(l, b, r, t, area.rect.left, area.rect.bottom, area.rect.right,
                               area.rect.top))
                    return true;
                continue;
            }
            for (const FS_QUADPOINTSF& q : area.quads) {
                if (pointInQuad(0.5 * (l + r), 0.5 * (b + t), q)) return true;
            }
        }
    }
    return false;
}

// Clears this page's unfinished-redaction record once none of the areas recorded
// for it still holds text. A commit that verified only its own marks cannot
// clear it: an earlier failure elsewhere on the page may still have survivors.
// False while survivors remain, or when the page text cannot be read to tell.
static bool resolveIncompleteRedaction(PageWrapper* pw) {
    if (!pw->core) return true;
    auto it = pw->core->incompleteRedactPages.find(pw->pageIndex);
    if (it == pw->core->incompleteRedactPages.end()) return true;
    FPDF_TEXTPAGE audit = FPDFText_LoadPage(pw->page);
    if (!audit) return false;
    bool survivors = textLeftIn(audit, it->second);
    FPDFText_ClosePage(audit);
    if (survivors) return false;
    pw->core->incompleteRedactPages.erase(it);
    return true;
}

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
        bool refusedUnmodified = false;
        int32_t rc = objectFissionRedact(pw->doc, pw->page, tp, matches, argb, pw->core,
                                         &paintedCovers, nullptr, false, &refusedUnmodified);
        FPDFText_ClosePage(tp);
        if (rc != JPDFIUM_OK) {
            // objectFissionRedact mutates the page before it audits, so a
            // half-finished region redaction has already destroyed content. A
            // refusal before any change leaves nothing half-done to protect.
            if (rc == JPDFIUM_ERR_REDACT_INCOMPLETE && !refusedUnmodified) {
                markRedactionIncomplete(pw, matches);
            }
            return rc;
        }

        // Audit 1 (text): reload the text page and confirm no glyph box
        // still intersects the redaction region. An audit that cannot even
        // run is a loud unverifiable error - never a silent pass.
        FPDF_TEXTPAGE audit = FPDFText_LoadPage(pw->page);
        if (!audit) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;
        std::vector<FS_RECTF> regions;
        FS_RECTF r;
        r.left = x;
        r.bottom = y;
        r.right = x + w;
        r.top = y + h;
        regions.push_back(r);

        bool remaining = false;
        int n = FPDFText_CountChars(audit);
        for (int ci = 0; ci < n; ++ci) {
            double l, r2, b, t;
            if (!FPDFText_GetCharBox(audit, ci, &l, &r2, &b, &t)) continue;
            if (charInRect(l, b, r2, t, x, y, x + w, y + h)) {
                remaining = true;
                break;
            }
        }
        FPDFText_ClosePage(audit);
        if (remaining) {
            // The fission engine already removed content in this region while
            // glyphs that must be gone are still extractable, so record the page
            // as half-redacted: every save fails until a later commit resolves it.
            markRedactionIncomplete(pw, regions);
            return JPDFIUM_ERR_REDACT_INCOMPLETE;
        }

        // Audit 2 (objects): see auditNoSurvivorsInRegion.
        if (!auditNoSurvivorsInRegion(pw->page, regions, paintedCovers)) {
            markRedactionIncomplete(pw, regions);
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

// (markRedactionIncomplete* live above jpdfium_redact_region so every
// incomplete-redaction return path can record the page it half-finished.)

int32_t jpdfium_redact_pattern(int64_t page, const char* pattern, uint32_t argb,
                               int32_t remove_content) noexcept {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    if (remove_content == 0) {
        // Doctrine: visual-only "cover" redaction is banned in the public API.
        return JPDFIUM_ERR_INVALID;
    }

    try {
        ScopedCoverPage coverPage(pw->page);
        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;

        int count = FPDFText_CountChars(tp);

        // Build the search buffer: NFKC-normalized text (ligature and
        // compatibility characters decomposed) with an index map back to the
        // original character indices.
        std::vector<int> idxMap;
        std::u32string wtext = buildNormalizedText(tp, count, idxMap);

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

        if (matches.empty()) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_OK;
        }

        // Grapheme alignment needs the raw char sequence. Build it only when
        // matches exist; zero-match pages skip this second per-char pass.
        std::vector<uint32_t> unicodeSeq;
        unicodeSeq.reserve(static_cast<size_t>(count));
        for (int i = 0; i < count; ++i) {
            unicodeSeq.push_back(FPDFText_GetUnicode(tp, i));
        }
        alignMatchesToGraphemes(tp, unicodeSeq, matches);
#ifdef JPDFIUM_HAS_HARFBUZZ
        // Snap every span to shaped-cluster boundaries (ligature safety):
        // a cut inside a shaped cluster would leave the survivor
        // unrenderable, so the span grows to the whole cluster.
        alignMatchesToShapedClusters(tp, matches);
#endif
        orientMatchQuads(tp, matches);

        // Expected surviving-text fingerprint (pre-redaction).
        std::vector<char> redactSet(count, 0);
        for (auto& m : matches)
            for (int ci : m.charIndices) redactSet[ci] = 1;
        std::u32string expectedFp = survivingFingerprint(tp, count, wtext, idxMap, redactSet);

        // Best case scenario: Wrap EmbedPDF core engine (EPDFPage_ApplyRedactions)
        // High-fidelity native in-place redaction via EmbedPDF core engine.
        std::vector<TextMatch> sortedMatches = matches;
        std::sort(sortedMatches.begin(), sortedMatches.end(),
                  [](const TextMatch& a, const TextMatch& b) {
                      if (std::abs(a.bboxB - b.bboxB) > 2.0f) {
                          return a.bboxB < b.bboxB;
                      }
                      return a.bboxL > b.bboxL;
                  });

        std::vector<FS_RECTF> appliedRects;
        appliedRects.reserve(sortedMatches.size());
        bool epdfOk = true;
        for (const auto& m : sortedMatches) {
            FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(pw->page, FPDF_ANNOT_REDACT);
            if (!annot) {
                epdfOk = false;
                break;
            }
            FS_RECTF rect;
            rect.left = m.bboxL;
            rect.bottom = m.bboxB;
            rect.right = m.bboxR;
            rect.top = m.bboxT;
            FPDFAnnot_SetRect(annot, &rect);
            if (m.hasQuad) FPDFAnnot_AppendAttachmentPoints(annot, &m.quad);

            uint32_t rem = 0;
            FPDF_BOOL ok = EPDFPage_ApplyRedactions(pw->page, &annot, 1, &rem);
            FPDFPage_CloseAnnot(annot);
            if (!ok) {
                epdfOk = false;
                break;
            }
            appliedRects.push_back(rect);
        }
        // Once one box is applied, tp indexes objects that redaction freed, so the
        // fission fallback cannot finish the page. The post-redaction check cannot
        // stand in for the unapplied boxes either: it re-matches whole patterns, and
        // a match split across lines no longer matches once one piece is gone.
        if (!epdfOk && !appliedRects.empty()) {
            FPDFText_ClosePage(tp);
            // The applied boxes already removed content while the surviving half
            // of the match is still extractable: a full save would ship a
            // half-redacted document, so keep the redacted-save guards (no
            // incremental save, sanitize on save) AND fail every save until the
            // caller resolves the page.
            markRedactionIncomplete(pw, sortedMatches);
            return JPDFIUM_ERR_REDACT_INCOMPLETE;
        }
        if (epdfOk) {
            FPDFText_ClosePage(tp);
            tp = nullptr;

            for (const TextMatch& m : sortedMatches) paintCover(pw->page, areaOf(m), argb);
            FPDFPage_GenerateContent(pw->page);

            if (pw->core) {
                pw->core->contentRedacted = true;
                for (const auto& ar : appliedRects) {
                    pw->core->addRedactZone(pw->pageIndex, ar.left, ar.bottom, ar.right, ar.top);
                }
            }
        } else {
            // Fallback: Object Fission engine if EPDFPage_ApplyRedactions is unavailable
            int32_t rc = objectFissionRedact(pw->doc, pw->page, tp, matches, argb, pw->core);
            FPDFText_ClosePage(tp);
            tp = nullptr;
            if (rc != JPDFIUM_OK) {
                // The fission engine mutates the page before it audits, and the
                // failed EPDF pass may have left its REDACT annotations behind:
                // a half-finished fallback redaction must fail every save closed.
                if (rc == JPDFIUM_ERR_REDACT_INCOMPLETE) markRedactionIncomplete(pw, matches);
                return rc;
            }
        }

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
        std::u32string actualFp = survivingFingerprint(audit, n2, wtext2, idxMap2, {});
        FPDFText_ClosePage(audit);
        if (!remaining.empty() || actualFp != expectedFp) {
            // Content was removed but the audit still finds survivors: the page is
            // half-redacted, so no save may serialize it until a commit resolves it.
            if (epdfOk) {
                markRedactionIncomplete(pw, sortedMatches);
            } else {
                markRedactionIncomplete(pw, matches);
            }
            return JPDFIUM_ERR_REDACT_INCOMPLETE;
        }
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

int32_t jpdfium_doc_flatten_annotations(int64_t docHandle) noexcept {
    bool mutated = false;
    try {
        DocWrapper* dw = decodeDoc(docHandle);
        if (!dw || !dw->core->doc) return JPDFIUM_ERR_INVALID;
        const int count = FPDF_GetPageCount(dw->core->doc);
        for (int i = 0; i < count; ++i) {
            UniquePage page(FPDF_LoadPage(dw->core->doc, i));
            if (!page) return mutated ? JPDFIUM_ERR_CONTENT_MUTATED : JPDFIUM_ERR_NOT_FOUND;
            const int rc = FPDFPage_Flatten(page.get(), FLAT_NORMALDISPLAY);
            if (rc != FLATTEN_SUCCESS && rc != FLATTEN_NOTHINGTODO) {
                return mutated ? JPDFIUM_ERR_CONTENT_MUTATED : JPDFIUM_ERR_NATIVE;
            }
            if (rc == FLATTEN_SUCCESS) mutated = true;
        }
        // Flatten rewrites content in place and keeps every FPDF_PAGE valid, so
        // the structural generation must not move; Java tracks the edit itself.
        return JPDFIUM_OK;
    } catch (...) {
        return mutated ? JPDFIUM_ERR_CONTENT_MUTATED : JPDFIUM_ERR_NATIVE;
    }
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
namespace {

FS_RECTF rectOf(const TextMatch& m) {
    FS_RECTF rect;
    rect.left = m.bboxL;
    rect.bottom = m.bboxB;
    rect.right = m.bboxR;
    rect.top = m.bboxT;
    return rect;
}

constexpr size_t kRedactApplyChunk = 16;

enum class BatchApply { Applied, NothingApplied, Partial };

BatchApply applyMatchesTogether(FPDF_PAGE page, const std::vector<TextMatch>& matches,
                                std::vector<FS_RECTF>& appliedRects) {
    if (matches.empty()) return BatchApply::Applied;
    const int before = FPDFPage_GetAnnotCount(page);
    std::vector<FPDF_ANNOTATION> annots;
    annots.reserve(matches.size());
    bool created = true;
    for (const auto& m : matches) {
        FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(page, FPDF_ANNOT_REDACT);
        if (!annot) {
            created = false;
            break;
        }
        FS_RECTF rect = rectOf(m);
        FPDFAnnot_SetRect(annot, &rect);
        if (m.hasQuad) FPDFAnnot_AppendAttachmentPoints(annot, &m.quad);
        annots.push_back(annot);
    }
    uint32_t removed = 0;
    const bool ok = created && EPDFPage_ApplyRedactions(page, annots.data(),
                                                        static_cast<int>(annots.size()), &removed);
    for (FPDF_ANNOTATION annot : annots) FPDFPage_CloseAnnot(annot);
    if (ok) {
        for (const auto& m : matches) appliedRects.push_back(rectOf(m));
        return BatchApply::Applied;
    }
    const int after = FPDFPage_GetAnnotCount(page);
    if (after != before + static_cast<int>(annots.size())) return BatchApply::Partial;
    for (int i = after - 1; i >= before; --i) FPDFPage_RemoveAnnot(page, i);
    return BatchApply::NothingApplied;
}

BatchApply applyMatchesInChunks(FPDF_PAGE page, const std::vector<TextMatch>& matches,
                                std::vector<FS_RECTF>& appliedRects) {
    std::vector<TextMatch> part;
    for (size_t start = 0; start < matches.size(); start += kRedactApplyChunk) {
        const size_t end = std::min(matches.size(), start + kRedactApplyChunk);
        part.assign(matches.begin() + start, matches.begin() + end);
        BatchApply r = applyMatchesTogether(page, part, appliedRects);
        if (r == BatchApply::Applied) continue;
        if (r == BatchApply::NothingApplied && start == 0) return r;
        return BatchApply::Partial;
    }
    return BatchApply::Applied;
}

}  // namespace

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
        ScratchGuard scratchGuard;
        ScopedCoverPage coverPage(pw->page);
        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;

        int count = FPDFText_CountChars(tp);

        t_redactScratch.clear();
        std::vector<int>& idxMap = t_redactScratch.idxMap;
        std::u32string& wtext = t_redactScratch.wtext;
        buildNormalizedText(tp, count, idxMap, wtext);

        std::vector<TextMatch>& matches = t_redactScratch.matches;
#ifdef JPDFIUM_HAS_PCRE2
        std::vector<Pcre2Pattern> compiledPatterns;
#endif
        int rejectedPatterns = 0;
        int compiledCount = 0;

        for (int32_t wi = 0; wi < wordCount; ++wi) {
            if (words[wi] && pw->core) pw->core->addRedactLiteral(words[wi]);
        }
#ifdef JPDFIUM_HAS_PCRE2
        scanPagePatterns(pw->core.get(), tp, wtext, idxMap, words, wordCount, wholeWord != 0,
                         caseSensitive != 0, useRegex != 0, padding, matches, &compiledPatterns,
                         rejectedPatterns, compiledCount);
#endif
        if (matchCount) *matchCount = logicalMatchCount(matches);

        if (rejectedPatterns > 0 && compiledCount == 0) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_ERR_INVALID;
        }

        if (matches.empty()) {
            FPDFText_ClosePage(tp);
            return JPDFIUM_OK;
        }

        std::vector<uint32_t>& unicodeSeq = t_redactScratch.unicodeSeq;
        unicodeSeq.reserve(static_cast<size_t>(count));
        for (int i = 0; i < count; ++i) {
            unicodeSeq.push_back(FPDFText_GetUnicode(tp, i));
        }
        alignMatchesToGraphemes(tp, unicodeSeq, matches);
#ifdef JPDFIUM_HAS_HARFBUZZ
        alignMatchesToShapedClusters(tp, matches);
#endif
        orientMatchQuads(tp, matches);
        std::vector<char>& redactSet = t_redactScratch.redactSet;
        redactSet.assign(count, 0);
        for (auto& m : matches)
            for (int ci : m.charIndices) redactSet[ci] = 1;

        std::vector<FS_RECTF>& auditRects = t_redactScratch.auditRects;
        auditRects.clear();
        auditRects.reserve(matches.size());
        for (const auto& m : matches) {
            FS_RECTF r;
            r.left = m.bboxL;
            r.bottom = m.bboxB;
            r.right = m.bboxR;
            r.top = m.bboxT;
            auditRects.push_back(r);
        }
        // Damage check baseline: fingerprint only the text that lies OUTSIDE
        // every redaction box. A box is axis-aligned while the text under it may
        // be rotated, skewed or overlapping, so which exact neighbours get
        // clipped is implementation detail; glyphs the box touches are excluded
        // from both sides of the comparison. Text outside every box must
        // survive untouched, so fission damage there is still detected.
        std::vector<char>& touched = t_redactScratch.auditTouched;
        markTouchedByRects(tp, count, auditRects, touched);
        std::u32string expectedFp = survivingFingerprint(tp, count, wtext, idxMap, touched);

        std::vector<TextMatch>& sortedMatches = t_redactScratch.sortedMatches;
        sortedMatches = matches;
        std::sort(sortedMatches.begin(), sortedMatches.end(),
                  [](const TextMatch& a, const TextMatch& b) {
                      if (std::abs(a.bboxB - b.bboxB) > 2.0f) {
                          return a.bboxB < b.bboxB;
                      }
                      return a.bboxL > b.bboxL;
                  });

        std::vector<FS_RECTF>& appliedRects = t_redactScratch.appliedRects;
        appliedRects.reserve(sortedMatches.size());
        bool epdfOk = true;
        switch (applyMatchesInChunks(pw->page, sortedMatches, appliedRects)) {
            case BatchApply::Applied:
                break;
            case BatchApply::Partial:
                FPDFText_ClosePage(tp);
                markRedactionIncomplete(pw, sortedMatches);
                return JPDFIUM_ERR_REDACT_INCOMPLETE;
            case BatchApply::NothingApplied:
                for (const auto& m : sortedMatches) {
                    FPDF_ANNOTATION annot = FPDFPage_CreateAnnot(pw->page, FPDF_ANNOT_REDACT);
                    if (!annot) {
                        epdfOk = false;
                        break;
                    }
                    FS_RECTF rect = rectOf(m);
                    FPDFAnnot_SetRect(annot, &rect);
                    if (m.hasQuad) FPDFAnnot_AppendAttachmentPoints(annot, &m.quad);

                    uint32_t rem = 0;
                    FPDF_BOOL ok = EPDFPage_ApplyRedactions(pw->page, &annot, 1, &rem);
                    FPDFPage_CloseAnnot(annot);
                    if (!ok) {
                        epdfOk = false;
                        break;
                    }
                    appliedRects.push_back(rect);
                }
                break;
        }
        // Once one box is applied, tp indexes objects that redaction freed, so the
        // fission fallback cannot finish the page. The post-redaction check cannot
        // stand in for the unapplied boxes either: it re-matches whole patterns, and
        // a match split across lines no longer matches once one piece is gone.
        if (!epdfOk && !appliedRects.empty()) {
            FPDFText_ClosePage(tp);
            // The applied boxes already removed content while the surviving half
            // of the match is still extractable: a full save would ship a
            // half-redacted document, so keep the redacted-save guards (no
            // incremental save, sanitize on save) AND fail every save until the
            // caller resolves the page.
            markRedactionIncomplete(pw, sortedMatches);
            return JPDFIUM_ERR_REDACT_INCOMPLETE;
        }
        if (epdfOk) {
            FPDFText_ClosePage(tp);
            tp = nullptr;

            for (const TextMatch& m : sortedMatches) paintCover(pw->page, areaOf(m), argb);
            FPDFPage_GenerateContent(pw->page);

            if (pw->core) {
                pw->core->contentRedacted = true;
                for (const auto& ar : appliedRects) {
                    pw->core->addRedactZone(pw->pageIndex, ar.left, ar.bottom, ar.right, ar.top);
                }
            }
        } else {
            int32_t rc = objectFissionRedact(pw->doc, pw->page, tp, matches, argb, pw->core);
            FPDFText_ClosePage(tp);
            tp = nullptr;
            if (rc != JPDFIUM_OK) {
                // Same contract as jpdfium_redact_pattern: the engine already
                // mutated the page, so a half-finished redaction is not savable.
                if (rc == JPDFIUM_ERR_REDACT_INCOMPLETE) markRedactionIncomplete(pw, matches);
                return rc;
            }
        }

        FPDF_TEXTPAGE audit = FPDFText_LoadPage(pw->page);
        if (!audit) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;
        std::vector<int>& idxMap2 = t_redactScratch.auditIdxMap;
        int n2 = FPDFText_CountChars(audit);
        std::u32string& wtext2 = t_redactScratch.auditWtext;
        buildNormalizedText(audit, n2, idxMap2, wtext2);
        // Survivor audit. Two orthogonal checks, each exactly as strong as it
        // can be without false alarms:
        //   1. (below) no redaction pattern still matches the extracted text -
        //      checks that no target pattern remains in the audited extraction;
        //      it does not prove absence from every representation in the file;
        //   2. text outside every redaction box is unchanged - catches fission
        //      damage to surviving fragments, which is silent data loss.
        std::vector<char>& touched2 = t_redactScratch.auditTouched2;
        markTouchedByRects(audit, n2, auditRects, touched2);
        std::u32string actualFp = survivingFingerprint(audit, n2, wtext2, idxMap2, touched2);
        if (actualFp != expectedFp) {
            FPDFText_ClosePage(audit);
            // Survivors after a mutation: fail every save until a commit resolves
            // this page (same contract as jpdfium_redact_pattern).
            if (epdfOk) {
                markRedactionIncomplete(pw, sortedMatches);
            } else {
                markRedactionIncomplete(pw, matches);
            }
            return JPDFIUM_ERR_REDACT_INCOMPLETE;
        }
#ifdef JPDFIUM_HAS_PCRE2
        std::vector<TextMatch>& remaining = t_redactScratch.auditRemaining;
        for (const auto& pc : compiledPatterns) {
            remaining.clear();
            collectPcre2Matches(audit, wtext2, idxMap2, pc, 0.0f, remaining);
            if (!remaining.empty()) {
                FPDFText_ClosePage(audit);
                if (epdfOk) {
                    markRedactionIncomplete(pw, sortedMatches);
                } else {
                    markRedactionIncomplete(pw, matches);
                }
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
        ScratchGuard scratchGuard;
        ScopedCoverPage coverPage(pw->page);
        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_NATIVE;

        int count = FPDFText_CountChars(tp);

        t_redactScratch.clear();
        std::vector<int>& idxMap = t_redactScratch.idxMap;
        std::u32string& wtext = t_redactScratch.wtext;
        buildNormalizedText(tp, count, idxMap, wtext);

        std::vector<uint32_t>& unicodeSeq = t_redactScratch.unicodeSeq;
        unicodeSeq.reserve(static_cast<size_t>(count));
        for (int i = 0; i < count; ++i) {
            unicodeSeq.push_back(FPDFText_GetUnicode(tp, i));
        }

        std::vector<TextMatch>& matches = t_redactScratch.matches;
        for (int32_t wi = 0; wi < wordCount; ++wi) {
            if (words[wi] && pw->core) pw->core->addRedactLiteral(words[wi]);
        }
#ifdef JPDFIUM_HAS_PCRE2
        int rejectedPatterns = 0;
        int compiledCount = 0;
        scanPagePatterns(pw->core.get(), tp, wtext, idxMap, words, wordCount, wholeWord != 0,
                         caseSensitive != 0, useRegex != 0, padding, matches, nullptr,
                         rejectedPatterns, compiledCount);
#endif
        alignMatchesToGraphemes(tp, unicodeSeq, matches);
#ifdef JPDFIUM_HAS_HARFBUZZ
        // Snap every span to shaped-cluster boundaries (ligature safety):
        // a cut inside a shaped cluster would leave the survivor
        // unrenderable, so the span grows to the whole cluster.
        alignMatchesToShapedClusters(tp, matches);
#endif
        orientMatchQuads(tp, matches);

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
            if (m.hasQuad) FPDFAnnot_AppendAttachmentPoints(annot, &m.quad);
            FPDFAnnot_SetColor(annot, FPDFANNOT_COLORTYPE_InteriorColor, r, g, b, 255);
            FPDFPage_CloseAnnot(annot);
            createdCount++;
        }

        FPDFText_ClosePage(tp);

        // The documented contract of this entry point is the number of REDACT
        // annotations created, and a match wrapped across lines or columns creates
        // one annotation per box, so report the boxes actually created instead of
        // the logical match count.
        if (matchCount) *matchCount = createdCount;

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
        // Collect all REDACT annotations and rects
        int total = FPDFPage_GetAnnotCount(pw->page);
        std::vector<CommitMark> redactAnnots;
        std::vector<int> redactIndices;

        for (int i = 0; i < total; ++i) {
            FPDF_ANNOTATION a = FPDFPage_GetAnnot(pw->page, i);
            if (!a) continue;
            if (FPDFAnnot_GetSubtype(a) == FPDF_ANNOT_REDACT) {
                FS_RECTF rect;
                if (FPDFAnnot_GetRect(a, &rect)) {
                    CommitMark item;
                    item.annot = a;
                    item.area.rect = rect;
                    size_t qc = FPDFAnnot_CountAttachmentPoints(a);
                    for (size_t qi = 0; qi < qc; ++qi) {
                        FS_QUADPOINTSF qp;
                        if (FPDFAnnot_GetAttachmentPoints(a, qi, &qp)) {
                            item.area.quads.push_back(qp);
                        }
                    }
                    redactAnnots.push_back(std::move(item));
                    redactIndices.push_back(i);
                    continue;
                }
            }
            FPDFPage_CloseAnnot(a);
        }

        if (commitCount) *commitCount = static_cast<int32_t>(redactAnnots.size());

        if (redactAnnots.empty()) {
            return JPDFIUM_OK;
        }

        // Apply in reverse reading order (bottom-to-top, right-to-left) to avoid
        // text matrix shifting artifacts across multiple regions in the same text object.
        std::sort(redactAnnots.begin(), redactAnnots.end(),
                  [](const CommitMark& a, const CommitMark& b) {
                      if (std::abs(a.area.rect.bottom - b.area.rect.bottom) > 2.0f) {
                          return a.area.rect.bottom < b.area.rect.bottom;
                      }
                      return a.area.rect.left > b.area.rect.left;
                  });

        std::vector<FS_RECTF> redactRects;
        std::vector<RedactArea> redactAreas;
        redactRects.reserve(redactAnnots.size());
        redactAreas.reserve(redactAnnots.size());
        for (const auto& item : redactAnnots) {
            redactRects.push_back(item.area.rect);
            appendRedactorAreas(item.area, redactAreas);
        }

        bool epdfOk = true;
        std::vector<FPDF_ANNOTATION> marks;
        marks.reserve(redactAnnots.size());
        for (auto& item : redactAnnots) {
            // Drop Acrobat's pre-baked /RO overlay: EmbedPDF ignores its /Matrix and
            // repaints it near the origin, while we draw our own cover below.
            EPDFAnnot_RemoveKey(item.annot, "RO");
            marks.push_back(item.annot);
        }
        for (size_t start = 0; start < marks.size(); start += kRedactApplyChunk) {
            const int n = static_cast<int>(std::min(kRedactApplyChunk, marks.size() - start));
            uint32_t rem = 0;
            if (!EPDFPage_ApplyRedactions(pw->page, marks.data() + start, n, &rem)) {
                epdfOk = false;
                break;
            }
        }
        for (FPDF_ANNOTATION mark : marks) FPDFPage_CloseAnnot(mark);
        if (epdfOk) {
            for (const RedactArea& area : redactAreas) paintCover(pw->page, area, argb);
            FPDFPage_GenerateContent(pw->page);

            // The redactor removed the marks it applied, so they no longer hold a
            // save back as unapplied, whatever the audit below finds.
            if (pw->core) {
                pw->core->unappliedRedactMarksCount =
                    std::max(0, pw->core->unappliedRedactMarksCount -
                                    static_cast<int32_t>(redactRects.size()));
            }

            FPDF_TEXTPAGE audit = FPDFText_LoadPage(pw->page);
            if (!audit) {
                markRedactionIncomplete(pw, redactAreas);
                return JPDFIUM_ERR_REDACT_UNVERIFIABLE;
            }
            bool survivors = textLeftIn(audit, redactAreas);
            FPDFText_ClosePage(audit);
            if (survivors) {
                markRedactionIncomplete(pw, redactAreas);
                return JPDFIUM_ERR_REDACT_INCOMPLETE;
            }

            if (pw->core) {
                pw->core->contentRedacted = true;
                for (const auto& ar : redactRects) {
                    pw->core->addRedactZone(pw->pageIndex, ar.left, ar.bottom, ar.right, ar.top);
                }
            }
            if (commitCount) *commitCount = static_cast<int32_t>(redactRects.size());
            return resolveIncompleteRedaction(pw) ? JPDFIUM_OK : JPDFIUM_ERR_REDACT_INCOMPLETE;
        }

        // Fallback: Object Fission engine if EPDFPage_ApplyRedactions is unavailable
        FPDF_TEXTPAGE tp = FPDFText_LoadPage(pw->page);
        if (!tp) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;

        int charCount = FPDFText_CountChars(tp);
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

        if (rc != JPDFIUM_OK) {
            // The commit removed content before auditing: a half-finished commit
            // leaves this page unsavable until a later commit resolves it.
            if (rc == JPDFIUM_ERR_REDACT_INCOMPLETE) markRedactionIncomplete(pw, redactRects);
            return rc;
        }

        FPDF_TEXTPAGE audit = FPDFText_LoadPage(pw->page);
        if (!audit) return JPDFIUM_ERR_REDACT_UNVERIFIABLE;
        int n = FPDFText_CountChars(audit);
        for (auto& ar : redactRects) {
            for (int ci = 0; ci < n; ++ci) {
                double l, r, b, t;
                if (!FPDFText_GetCharBox(audit, ci, &l, &r, &b, &t)) continue;
                if (charInRect(l, b, r, t, ar.left, ar.bottom, ar.right, ar.top)) {
                    fprintf(stderr,
                            "FALLBACK AUDIT FAILED on char ci=%d cx=%.3f in rect [%.3f, %.3f, "
                            "%.3f, %.3f]\n",
                            ci, (l + r) / 2.0, ar.left, ar.bottom, ar.right, ar.top);
                    fflush(stderr);
                    FPDFText_ClosePage(audit);
                    markRedactionIncomplete(pw, redactRects);
                    return JPDFIUM_ERR_REDACT_INCOMPLETE;
                }
            }
        }
        FPDFText_ClosePage(audit);

        if (!auditNoSurvivorsInRegion(pw->page, redactRects, paintedCovers)) {
            markRedactionIncomplete(pw, redactRects);
            return JPDFIUM_ERR_REDACT_INCOMPLETE;
        }

        if (pw->core) {
            for (auto& ar : redactRects) {
                pw->core->addRedactZone(pw->pageIndex, ar.left, ar.bottom, ar.right, ar.top);
            }
        }

        for (int i = static_cast<int>(redactIndices.size()) - 1; i >= 0; --i) {
            FPDFPage_RemoveAnnot(pw->page, redactIndices[i]);
        }
        if (pw->core) {
            pw->core->unappliedRedactMarksCount =
                std::max(0, pw->core->unappliedRedactMarksCount -
                                static_cast<int32_t>(redactIndices.size()));
        }
        return resolveIncompleteRedaction(pw) ? JPDFIUM_OK : JPDFIUM_ERR_REDACT_INCOMPLETE;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
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
    // A redaction that stopped half-way is not savable at all (see
    // DocCore::hasIncompleteRedaction). Checked first so the caller sees the
    // actionable error instead of the broader redacted-save refusal.
    if (w->core->hasIncompleteRedaction()) return JPDFIUM_ERR_REDACT_INCOMPLETE;
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

JPDFIUM_EXPORT int32_t jpdfium_page_mark_redacted(int64_t page) JPDFIUM_NOEXCEPT {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->core) return JPDFIUM_ERR_INVALID;
    pw->core->contentRedacted = true;
    return JPDFIUM_OK;
}

JPDFIUM_EXPORT int32_t jpdfium_page_mark_redaction_incomplete(int64_t page) JPDFIUM_NOEXCEPT {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->core) return JPDFIUM_ERR_INVALID;
    try {
        pw->core->contentRedacted = true;
        pw->core->strictCropIncompletePages.insert(pw->pageIndex);
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;  // never let exceptions cross the FFI boundary
    }
    return JPDFIUM_OK;
}

JPDFIUM_EXPORT int32_t jpdfium_page_clear_strict_crop_incomplete(int64_t page) JPDFIUM_NOEXCEPT {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->core) return JPDFIUM_ERR_INVALID;
    pw->core->strictCropIncompletePages.erase(pw->pageIndex);
    return JPDFIUM_OK;
}
