// jpdfium_redact_pattern.cpp - match pipeline for redaction.
//
// PCRE2 compilation and caching, literal/pattern matching over normalized
// text, and grapheme/shaped-cluster alignment of match bboxes.

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

#ifdef JPDFIUM_HAS_UNIBREAK
#include <graphemebreak.h>
#endif

#ifdef JPDFIUM_HAS_HARFBUZZ
#include <hb.h>
#endif

#ifdef JPDFIUM_HAS_PCRE2
void deletePatternCache(void* p) {
    delete reinterpret_cast<PatternCache*>(p);
}

static std::string buildCanonicalKey(const char** words, int32_t wordCount, bool wholeWord,
                                     bool caseSensitive, bool useRegex) {
    std::string key;
    key.reserve(static_cast<size_t>(wordCount) * 16 + 16);
    key += (wholeWord ? "W1:" : "W0:");
    key += (caseSensitive ? "C1:" : "C0:");
    key += (useRegex ? "R1:" : "R0:");
    for (int32_t i = 0; i < wordCount; ++i) {
        if (words[i]) {
            key += words[i];
            key += '\0';
        }
    }
    return key;
}

static uint64_t computePatternSignature(const char** words, int32_t wordCount, bool wholeWord,
                                        bool caseSensitive, bool useRegex) {
    uint64_t h = 14695981039346656037ULL;
    auto hashU64 = [&](uint64_t val) {
        h ^= val;
        h *= 1099511628211ULL;
    };
    hashU64(static_cast<uint64_t>(wordCount));
    hashU64(wholeWord ? 1 : 0);
    hashU64(caseSensitive ? 1 : 0);
    hashU64(useRegex ? 1 : 0);
    for (int32_t i = 0; i < wordCount; ++i) {
        if (!words[i]) continue;
        for (const char* p = words[i]; *p; ++p) {
            h ^= static_cast<uint8_t>(*p);
            h *= 1099511628211ULL;
        }
    }
    return h;
}

static constexpr uint32_t kPcre2MatchLimit = 1'000'000u;
static constexpr uint32_t kPcre2DepthLimit = 1'000u;

static bool createPatternFromCode(pcre2_code* code, pcre2_jit_stack* jst, Pcre2Pattern& out) {
    if (!code) return false;
    pcre2_match_data* md = pcre2_match_data_create_from_pattern(code, nullptr);
    if (!md) return false;
    pcre2_match_context* mctx = pcre2_match_context_create(nullptr);
    if (!mctx) {
        pcre2_match_data_free(md);
        return false;
    }
    pcre2_set_match_limit(mctx, kPcre2MatchLimit);
    pcre2_set_depth_limit(mctx, kPcre2DepthLimit);
    if (jst) pcre2_jit_stack_assign(mctx, nullptr, jst);
    out.code = code;
    out.md = md;
    out.mctx = mctx;
    out.jst = jst;
    out.ownsCode = false;
    return true;
}

// Compile a u32 pattern. caseless==true adds PCRE2_CASELESS. JIT-compiles the
// complete match. Returns false (and fills err) on compile failure.
bool compilePcre2(const std::u32string& pattern, bool caseless, Pcre2Pattern& out,
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
    pcre2_jit_stack* jst = pcre2_jit_stack_create(static_cast<std::size_t>(32) * 1024,
                                                  static_cast<std::size_t>(512) * 1024, nullptr);
    if (jst) pcre2_jit_stack_assign(mctx, nullptr, jst);
    out.code = code;
    out.md = md;
    out.mctx = mctx;
    out.jst = jst;
    out.ownsCode = true;
    return true;
}
#endif

// Where printed text sits relative to its baseline, in em. Measured on
// OCR'd scans: this band holds all the ink of 97% of scanned words.
static constexpr double kInkBelowBaselineEm = 0.30;
static constexpr double kInkAboveBaselineEm = 1.05;
// Arabic marks under a letter (hamza below, kasra) hang below that band.
static constexpr double kMarkBelowBaselineEm = 0.45;
// OCR word boxes sit a few pixels inside the scanned ink, so an OCR cover
// reaches this far past each end of a word, and follows a scanned glyph it
// cuts through this far up or down.
static constexpr double kOcrSidePadEm = 0.2;
static constexpr double kOcrGrowEm = 0.35;
static constexpr double kInkDpi = 200.0;
// Upper bound on the rendered page, in pixels: 16 Mpx is 64 MB of BGRx and puts
// an A0 sheet at ~100 dpi instead of failing the cover pass outright.
static constexpr double kMaxInkPixels = 16.0e6;
static constexpr double kMinCoverWidthEm = 0.1;
static constexpr double kFallbackEm = 10.0;  // pt, for chars with no derivable size

// Font size of a character in page space, or 0 when it cannot be derived:
// generated chars (the spaces and line breaks PDFium inserts) have no text
// object to take a size from.
static double charEm(FPDF_TEXTPAGE textPage, int index, FS_MATRIX* matrix) {
    if (!FPDFText_GetTextObject(textPage, index)) return 0;
    FS_MATRIX m;
    if (!FPDFText_GetMatrix(textPage, index, &m)) return 0;
    if (matrix) *matrix = m;
    double size = FPDFText_GetFontSize(textPage, index);
    double scale = std::hypot(static_cast<double>(m.c), static_cast<double>(m.d));
    return size > 0 && scale > 0 ? size * scale : 0;
}

// The box a redaction must cover for one character. Invisible text (render
// mode 3) is how OCR tools lay a text layer over a scan: its glyph box comes
// from a placeholder font and is shorter than the printed word underneath.
// For upright invisible text the box spans the band printed text occupies
// around the baseline instead. The font's own ascent/descent box is not used: OCR
// fonts declare a far taller one, which reaches into the neighbouring line and
// removes its words from the text layer.
static bool hasMarkBelow(unsigned int cp) {
    return cp == 0x0625 || cp == 0x0650 || cp == 0x064D || cp == 0x0655 || cp == 0x0656;
}

namespace {
// The component walk is window-sized, so its buffers are kept per thread
// instead of re-allocated for every match - but only while they stay small.
thread_local std::vector<char> t_scratchSeen;
thread_local std::vector<int> t_scratchStack;
constexpr size_t kMaxScratchRetained = 65536;

struct InkRaster {
    FPDF_PAGE page = nullptr;
    bool rendered = false;
    int w = 0, h = 0, stride = 0;
    double scale = 0;
    // page-space origin of the rendered area
    double boxLeft = 0, boxTop = 0;
    FPDF_BITMAP bitmap = nullptr;     // rendered page, kept for the whole scope
    std::span<const uint8_t> pixels;  // its buffer, stride * h bytes
};
thread_local InkRaster t_ink;

// Drops scratch a single large match left behind: the walk window can span the
// whole raster, far beyond what the next match on this thread needs.
void trimScratch() {
    if (t_scratchSeen.capacity() > kMaxScratchRetained) std::vector<char>().swap(t_scratchSeen);
    if (t_scratchStack.capacity() > kMaxScratchRetained) std::vector<int>().swap(t_scratchStack);
}

// Releases the rendered page a scope was holding, if any.
void resetInkRaster() {
    if (t_ink.bitmap) FPDFBitmap_Destroy(t_ink.bitmap);
    t_ink = InkRaster();
    trimScratch();
}

// Ink test on the rendered page: dark enough to be print, not paper.
bool inkAt(int y, int x) {
    size_t offset = static_cast<size_t>(y) * t_ink.stride + static_cast<size_t>(x) * 4;
    const uint8_t* px = t_ink.pixels.data() + offset;
    return (px[0] + px[1] + px[2]) < 3 * 128;
}
}  // namespace

ScopedCoverPage::ScopedCoverPage(FPDF_PAGE page) {
    resetInkRaster();
    t_ink.page = page;
}

ScopedCoverPage::~ScopedCoverPage() {
    resetInkRaster();
}

// Renders the cover page once per scope, before any redaction is applied to it.
static bool ensureInkRaster() {
    if (!t_ink.page) return false;
    if (t_ink.rendered) return t_ink.bitmap != nullptr;
    t_ink.rendered = true;
    // A rotated page renders on its side, so page-space glyph boxes no longer
    // line up with raster rows; the cover keeps the glyph box in that case.
    if (FPDFPage_GetRotation(t_ink.page) != 0) return false;
    FS_RECTF box;
    if (!FPDF_GetPageBoundingBox(t_ink.page, &box)) return false;
    double pw = box.right - box.left, ph = box.top - box.bottom;
    if (pw <= 0 || ph <= 0) return false;
    t_ink.boxLeft = box.left;
    t_ink.boxTop = box.top;
    // kInkDpi decides how finely a scanned glyph is resolved; the buffer it
    // implies is what a very large page makes expensive, so the scale backs off
    // to kMaxInkPixels rather than losing the covers. Every threshold below is
    // em-relative and keeps its page-space meaning at the coarser scale.
    double base = kInkDpi / 72.0;
    double pixels = pw * ph * base * base;
    t_ink.scale = pixels > kMaxInkPixels ? std::sqrt(kMaxInkPixels / (pw * ph)) : base;
    t_ink.w = static_cast<int>(pw * t_ink.scale);
    t_ink.h = static_cast<int>(ph * t_ink.scale);
    if (t_ink.w <= 0 || t_ink.h <= 0) return false;
    FPDF_BITMAP bmp = FPDFBitmap_Create(t_ink.w, t_ink.h, 0);
    if (!bmp) return false;
    FPDFBitmap_FillRect(bmp, 0, 0, t_ink.w, t_ink.h, 0xFFFFFFFF);
    FPDF_RenderPageBitmap(bmp, t_ink.page, 0, 0, t_ink.w, t_ink.h, 0, 0);
    t_ink.bitmap = bmp;
    t_ink.stride = FPDFBitmap_GetStride(bmp);
    t_ink.pixels = {static_cast<const uint8_t*>(FPDFBitmap_GetBuffer(bmp)),
                    static_cast<size_t>(t_ink.stride) * static_cast<size_t>(t_ink.h)};
    return true;
}

// Extends [bottom, top] over the scanned glyphs the cover slices through. A glyph
// counts when its ink reaches the middle of the cover, so it belongs to this
// line and not to a neighbour poking in, and ends within kOcrGrowEm of the edge
// it crosses: a descender tip, a stacked mark, the top of a line the OCR placed
// too low.
static void growToInk(double l, double r, double em, double* bottom, double* top) {
    if (!std::isfinite(l) || !std::isfinite(r) || !std::isfinite(em) || em <= 0) return;
    if (!ensureInkRaster()) return;
    // Everything below becomes an index into the raster, and a crafted file can
    // hold any text matrix: clamp to the rendered box before narrowing.
    const double boxRight = t_ink.boxLeft + t_ink.w / t_ink.scale;
    const double boxBottom = t_ink.boxTop - t_ink.h / t_ink.scale;
    auto colOf = [&](double x) {
        double clamped = std::clamp(x, t_ink.boxLeft, boxRight) - t_ink.boxLeft;
        double px = std::clamp(clamped * t_ink.scale, 0.0, static_cast<double>(t_ink.w));
        return static_cast<int>(px);
    };
    auto rowOf = [&](double y) {
        double clamped = t_ink.boxTop - std::clamp(y, boxBottom, t_ink.boxTop);
        double px = std::clamp(clamped * t_ink.scale, 0.0, static_cast<double>(t_ink.h));
        return static_cast<int>(px);
    };
    int x0 = colOf(l);
    int x1 = std::min(t_ink.w, colOf(r) + 1);
    int rTop = rowOf(*top), rBot = rowOf(*bottom);
    if (x1 <= x0 || rBot - rTop < 4) return;
    double capPx = std::clamp(kOcrGrowEm * em * t_ink.scale, 1.0, static_cast<double>(t_ink.h));
    int cap = static_cast<int>(capPx);
    int y0 = std::max(0, rTop - cap - 1), y1 = std::min(t_ink.h, rBot + cap + 1);
    int midA = rTop + (rBot - rTop) * 3 / 10, midB = rBot - (rBot - rTop) * 3 / 10;
    int w = x1 - x0, h = y1 - y0;
    if (h <= 0) return;
    std::vector<char>& seen = t_scratchSeen;
    seen.assign(static_cast<size_t>(w) * h, 0);
    std::vector<int>& stack = t_scratchStack;
    stack.clear();
    int newTop = rTop, newBot = rBot;
    for (int sy = 0; sy < h; ++sy)
        for (int sx = 0; sx < w; ++sx) {
            size_t si = static_cast<size_t>(sy) * w + sx;
            if (seen[si] || !inkAt(sy + y0, sx + x0)) continue;
            int minY = sy + y0, maxY = minY;
            bool mid = false, clipped = false;
            seen[si] = 1;
            stack.push_back(static_cast<int>(si));
            while (!stack.empty()) {
                int i = stack.back();
                stack.pop_back();
                int cy = i / w, cx = i % w, py = cy + y0;
                minY = std::min(minY, py);
                maxY = std::max(maxY, py);
                mid |= py >= midA && py < midB;
                clipped |= cy == 0 || cy == h - 1;
                for (int dy = -1; dy <= 1; ++dy)
                    for (int dx = -1; dx <= 1; ++dx) {
                        int ny = cy + dy, nx = cx + dx;
                        if (ny < 0 || ny >= h || nx < 0 || nx >= w) continue;
                        size_t ni = static_cast<size_t>(ny) * w + nx;
                        if (seen[ni] || !inkAt(ny + y0, nx + x0)) continue;
                        seen[ni] = 1;
                        stack.push_back(static_cast<int>(ni));
                    }
            }
            if (!mid || clipped) continue;
            newTop = std::min(newTop, minY);
            newBot = std::max(newBot, maxY + 1);
        }
    *top = std::max(*top, t_ink.boxTop - newTop / t_ink.scale);
    *bottom = std::min(*bottom, t_ink.boxTop - newBot / t_ink.scale);
}

bool matchCharBox(FPDF_TEXTPAGE textPage, int index, double* l, double* r, double* b, double* t) {
    if (!FPDFText_GetCharBox(textPage, index, l, r, b, t)) return false;
    // A redaction removes a char only when the box overlaps its glyph box, and a
    // zero-width one (a combining mark, OCR text with no advance) on the edge of
    // the box only touches it. A box over it alone would have no area.
    if (*r - *l < 0.01) {  // pt
        double em = charEm(textPage, index, nullptr);
        double half = 0.5 * kMinCoverWidthEm * (em > 0 ? em : kFallbackEm);
        *l -= half;
        *r += half;
    }
    FPDF_PAGEOBJECT obj = FPDFText_GetTextObject(textPage, index);
    if (!obj || FPDFTextObj_GetTextRenderMode(obj) != FPDF_TEXTRENDERMODE_INVISIBLE) return true;
    FS_MATRIX m;
    double em = charEm(textPage, index, &m);
    double ox, oy;
    if (em <= 0 || !FPDFText_GetCharOrigin(textPage, index, &ox, &oy)) return true;
    // Rotated or mirrored layers keep the glyph box: the band is vertical.
    bool upright =
        m.d > 0 && std::fabs(m.b) <= 0.01f * std::fabs(m.a) && std::fabs(m.c) <= 0.01f * m.d;
    if (!upright) return true;
    // The placeholder glyph is narrower than the advance the OCR tool gave the
    // char, so the box also spans that advance.
    FS_RECTF loose;
    if (FPDFText_GetLooseCharBox(textPage, index, &loose) && loose.right > loose.left) {
        *l = std::min(*l, static_cast<double>(loose.left));
        *r = std::max(*r, static_cast<double>(loose.right));
    }
    *b = std::min(*b, oy - kInkBelowBaselineEm * em);
    *t = std::max(*t, oy + kInkAboveBaselineEm * em);
    if (hasMarkBelow(FPDFText_GetUnicode(textPage, index)))
        *b = std::min(*b, oy - kMarkBelowBaselineEm * em);
    return true;
}

namespace {
// Glyph boxes of every char on one text page, read on first use so that covering
// many matches reads each box once. Valid only while that text page is open and
// the page is unchanged.
class CharBoxCache {
   public:
    struct Box {
        double l = 0, r = 0, b = 0, t = 0;
        bool ok = false;  // has a box with height
    };

    explicit CharBoxCache(FPDF_TEXTPAGE textPage) : textPage_(textPage) {}

    FPDF_TEXTPAGE textPage() const {
        return textPage_;
    }

    const std::vector<Box>& boxes() {
        if (loaded_) return boxes_;
        loaded_ = true;
        int n = FPDFText_CountChars(textPage_);
        boxes_.resize(n > 0 ? static_cast<size_t>(n) : 0);
        for (int i = 0; i < n; ++i) {
            Box& box = boxes_[static_cast<size_t>(i)];
            box.ok =
                FPDFText_GetCharBox(textPage_, i, &box.l, &box.r, &box.b, &box.t) && box.t > box.b;
        }
        return boxes_;
    }

   private:
    FPDF_TEXTPAGE textPage_;
    bool loaded_ = false;
    std::vector<Box> boxes_;
};
}  // namespace

// Union of character boxes: `cover` from matchCharBox, `glyph` from the glyph
// boxes alone. They differ only where invisible text was extended.
struct CoverBounds {
    double l = 0, r = 0, coverB = 0, coverT = 0, glyphB = 0, glyphT = 0;
    bool any = false;
    double ocrEm = 0;  // largest em of the invisible (OCR) chars; 0 when there are none

    void add(double cl, double cr, double cb, double ct, double gb, double gt) {
        if (!any) {
            l = cl;
            r = cr;
            coverB = cb;
            coverT = ct;
            glyphB = gb;
            glyphT = gt;
            any = true;
            return;
        }
        l = std::min(l, cl);
        r = std::max(r, cr);
        coverB = std::min(coverB, cb);
        coverT = std::max(coverT, ct);
        glyphB = std::min(glyphB, gb);
        glyphT = std::max(glyphT, gt);
    }
};

// Sets the match box from `bounds`. The part of the cover that extends past the
// glyph boxes stops short of any other character above, below or beside it:
// small print can sit closer to the neighbouring line than the invisible-text
// band is tall, and the redaction removes every character its box touches.
static void fitCover(CharBoxCache& chars, TextMatch& m, const CoverBounds& bounds, float padding) {
    double bottom = bounds.coverB;
    double top = bounds.coverT;
    double left = bounds.l, right = bounds.r;
    bool ocr = bounds.ocrEm > 0;
    if (ocr || bottom < bounds.glyphB || top > bounds.glyphT) {
        std::vector<int> own = m.charIndices;
        std::sort(own.begin(), own.end());
        constexpr double kClearance = 0.1;
        double topLimit = 1e9, bottomLimit = -1e9, leftLimit = -1e9, rightLimit = 1e9;
        FPDF_TEXTPAGE textPage = chars.textPage();
        const auto& boxes = chars.boxes();
        for (int i = 0; i < static_cast<int>(boxes.size()); ++i) {
            const CharBoxCache::Box& box = boxes[static_cast<size_t>(i)];
            if (!box.ok || std::binary_search(own.begin(), own.end(), i)) continue;
            double l = box.l, r = box.r, b = box.b, t = box.t;
            if (r > bounds.l && l < bounds.r) {
                if (b >= bounds.glyphT) {
                    topLimit = std::min(topLimit, b - kClearance);
                } else if (t <= bounds.glyphB) {
                    bottomLimit = std::max(bottomLimit, t + kClearance);
                }
                continue;
            }
            if (!ocr || std::min(t, bounds.glyphT) <= std::max(b, bounds.glyphB)) continue;
            unsigned int u = FPDFText_GetUnicode(textPage, i);
            if (u == ' ' || u == 0xA0 || u == '\t' || u == '\r' || u == '\n') continue;
            FS_RECTF loose;
            if (FPDFText_GetLooseCharBox(textPage, i, &loose) && loose.right > loose.left) {
                l = std::min(l, static_cast<double>(loose.left));
                r = std::max(r, static_cast<double>(loose.right));
            }
            if (r <= bounds.l) {
                leftLimit = std::max(leftLimit, std::min(r + kClearance, bounds.l));
            } else if (l >= bounds.r) {
                rightLimit = std::min(rightLimit, std::max(l - kClearance, bounds.r));
            } else if (r < 0.5 * (bounds.l + bounds.r)) {
                leftLimit = std::max(leftLimit, bounds.l);
            } else {
                rightLimit = std::min(rightLimit, bounds.r);
            }
        }
        if (ocr) {
            growToInk(bounds.l, bounds.r, bounds.ocrEm, &bottom, &top);
            left = std::max(bounds.l - kOcrSidePadEm * bounds.ocrEm, leftLimit);
            right = std::min(bounds.r + kOcrSidePadEm * bounds.ocrEm, rightLimit);
        }
        top = std::max(std::min(top, topLimit), bounds.glyphT);
        bottom = std::min(std::max(bottom, bottomLimit), bounds.glyphB);
    }
    m.bboxL = static_cast<float>(left - padding);
    m.bboxB = static_cast<float>(bottom - padding);
    m.bboxR = static_cast<float>(right + padding);
    m.bboxT = static_cast<float>(top + padding);
}

// Adds one character to `bounds`; false when it has no box.
static bool addCharBox(FPDF_TEXTPAGE textPage, int index, CoverBounds& bounds, double* l, double* r,
                       double* b, double* t) {
    if (!matchCharBox(textPage, index, l, r, b, t)) return false;
    double gl, gr, gb, gt;
    if (!FPDFText_GetCharBox(textPage, index, &gl, &gr, &gb, &gt)) return false;
    bounds.add(*l, *r, *b, *t, gb, gt);
    FPDF_PAGEOBJECT obj = FPDFText_GetTextObject(textPage, index);
    if (obj && FPDFTextObj_GetTextRenderMode(obj) == FPDF_TEXTRENDERMODE_INVISIBLE)
        bounds.ocrEm = std::max(bounds.ocrEm, charEm(textPage, index, nullptr));
    return true;
}

int32_t logicalMatchCount(const std::vector<TextMatch>& matches) {
    std::set<int> groups;
    int32_t ungrouped = 0;
    for (const auto& m : matches) {
        if (m.group < 0) {
            ++ungrouped;
        } else {
            groups.insert(m.group);
        }
    }
    return ungrouped + static_cast<int32_t>(groups.size());
}

// Rebuild a TextMatch bbox from its char indices (used after grapheme or
// cluster extension so the painted cover encloses everything removed).
static bool recomputeMatchBbox(CharBoxCache& chars, TextMatch& m) {
    CoverBounds bounds;
    for (int ci : m.charIndices) {
        double l, r, b, t;
        addCharBox(chars.textPage(), ci, bounds, &l, &r, &b, &t);
    }
    if (!bounds.any) return false;
    fitCover(chars, m, bounds, 0.0f);
    return true;
}

bool recomputeMatchBbox(FPDF_TEXTPAGE textPage, TextMatch& m) {
    CharBoxCache chars(textPage);
    return recomputeMatchBbox(chars, m);
}

static bool isLineBreak(unsigned int uni) {
    return uni == '\r' || uni == '\n' || uni == 0x2028 || uni == 0x2029;
}

// True when a char box shares a text line with the box built so far: the
// vertical overlap covers at least half of the shorter of the two.
static bool onSameLine(double ymin, double ymax, double b, double t) {
    double overlap = std::min(ymax, t) - std::max(ymin, b);
    double shorter = std::min(ymax - ymin, t - b);
    return shorter <= 0 ? overlap >= 0 : overlap >= 0.5 * shorter;
}

// A gap wider than this between neighbouring characters on one baseline is a
// column gutter or a table cell boundary, not a word space (0.25-0.35em).
static constexpr double kRunBreakGapEm = 1.5;

// Append the chars covered by a PCRE2 match [start,start+len) of the search
// buffer, one TextMatch per run of text. A single box over a match that wraps
// to the next line, column or page spans every glyph in between, and two
// side-by-side columns can share a baseline, so a box also ends at a gutter.
// The pieces share a group.
static void appendMatchChars(CharBoxCache& chars, const std::vector<int>& idxMap, int start,
                             int len, float padding, std::vector<TextMatch>& out) {
    FPDF_TEXTPAGE textPage = chars.textPage();
    const size_t firstPiece = out.size();
    const int group = static_cast<int>(firstPiece);
    TextMatch tm;
    CoverBounds bounds;
    double lastRight = 0;
    int lastCi = -1;
    auto flush = [&]() {
        if (bounds.any) {
            fitCover(chars, tm, bounds, padding);
            tm.group = group;
            out.push_back(std::move(tm));
        }
        tm = TextMatch();
        bounds = CoverBounds();
    };
    for (int k = start; k < start + len && k < static_cast<int>(idxMap.size()); ++k) {
        int ci = idxMap[k];
        if (ci == lastCi) continue;  // NFKC expansion maps several buffer units to one char
        lastCi = ci;
        if (isLineBreak(FPDFText_GetUnicode(textPage, ci))) {
            flush();
            continue;
        }
        double l, r, b, t;
        if (!matchCharBox(textPage, ci, &l, &r, &b, &t)) {  // unmapped: stays with its line
            tm.charIndices.push_back(ci);
            continue;
        }
        if (bounds.any) {
            double em = charEm(textPage, ci, nullptr);
            bool gutter = em > 0 && l - lastRight > kRunBreakGapEm * em;
            if (gutter || !onSameLine(bounds.coverB, bounds.coverT, b, t)) flush();
        }
        lastRight = r;
        tm.charIndices.push_back(ci);
        addCharBox(textPage, ci, bounds, &l, &r, &b, &t);
    }
    flush();
    if (out.size() == firstPiece + 1) out.back().group = -1;
}

#ifdef JPDFIUM_HAS_PCRE2
// Run a compiled PCRE2 pattern over the search buffer -> TextMatch vector.
// Non-overlapping matches (same iteration model as wsregex_iterator).
static void collectPcre2Matches(CharBoxCache& chars, const std::u32string& text,
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
        appendMatchChars(chars, idxMap, static_cast<int>(start), static_cast<int>(end - start),
                         padding, out);
        offset = end;
    }
}

void collectPcre2Matches(FPDF_TEXTPAGE textPage, const std::u32string& text,
                         const std::vector<int>& idxMap, const Pcre2Pattern& pc, float padding,
                         std::vector<TextMatch>& out) {
    CharBoxCache chars(textPage);
    collectPcre2Matches(chars, text, idxMap, pc, padding, out);
}

// Must stay in sync with FastKeywordIndex.isKeywordSpace(), the prefilter that
// decides which pages reach this matcher.
static bool isLiteralSpace(char32_t ch) {
    return ch == U' ' || ch == U'\t' || ch == U'\r' || ch == U'\n' || ch == U'\u00A0' ||
           ch == U'\u2007' || ch == U'\u202F' || ch == U'\u2028' || ch == U'\u2029';
}

// Escape regex metacharacters for literal matching. A whitespace run matches
// any whitespace run, so a phrase still matches where the page wraps it onto
// the next line (the text page puts a line break there, not a space).
static std::u32string escapeLiteral(const std::u32string& raw) {
    std::u32string out;
    bool inSpace = false;
    for (char32_t ch : raw) {
        if (isLiteralSpace(ch)) {
            if (!inSpace) out += U"\\s+";
            inSpace = true;
            continue;
        }
        inSpace = false;
        if (ch == U'\\' || ch == U'^' || ch == U'$' || ch == U'.' || ch == U'|' || ch == U'?' ||
            ch == U'*' || ch == U'+' || ch == U'(' || ch == U')' || ch == U'[' || ch == U']' ||
            ch == U'{' || ch == U'}') {
            out += U'\\';
        }
        out += ch;
    }
    return out;
}

static bool buildLiteralAlternation(const char** words, int32_t wordCount, bool wholeWord,
                                    std::u32string& pattern) {
    // Collect, deduplicate, then sort by descending length so that PCRE2 leftmost-first
    // alternation tries longer literals first (e.g. "foobar" before "foo").
    // Lexicographic ascending order breaks ties deterministically.
    std::vector<std::u32string> escaped;
    escaped.reserve(static_cast<std::size_t>(wordCount));
    for (int32_t wi = 0; wi < wordCount; wi++) {
        if (!words[wi]) continue;
        std::u32string esc = escapeLiteral(utf8_to_u32(words[wi]));
        if (esc.empty()) continue;
        escaped.push_back(std::move(esc));
    }
    if (escaped.empty()) return false;
    // Deduplicate
    std::sort(escaped.begin(), escaped.end());
    escaped.erase(std::unique(escaped.begin(), escaped.end()), escaped.end());
    // Sort descending by length, ascending lexicographically for equal lengths.
    std::stable_sort(escaped.begin(), escaped.end(),
                     [](const std::u32string& a, const std::u32string& b) {
                         if (a.size() != b.size()) return a.size() > b.size();
                         return a < b;
                     });
    std::u32string body;
    for (const auto& esc : escaped) {
        body += esc;
        body += U"|";
    }
    body.pop_back();  // trailing '|'
    pattern.clear();
    if (wholeWord) pattern += U"\\b";
    pattern += U"(?:";
    pattern += body;
    pattern += U")";
    if (wholeWord) pattern += U"\\b";
    return true;
}

void scanPagePatterns(DocCore* core, FPDF_TEXTPAGE tp, const std::u32string& wtext,
                      const std::vector<int>& idxMap, const char** words, int32_t wordCount,
                      bool wholeWord, bool caseSensitive, bool useRegex, float padding,
                      std::vector<TextMatch>& matches,
                      std::vector<Pcre2Pattern>* outCompiledPatterns, int& rejectedPatterns,
                      int& compiledCount) {
    CharBoxCache chars(tp);
    uint64_t sig = computePatternSignature(words, wordCount, wholeWord, caseSensitive, useRegex);
    std::string key = buildCanonicalKey(words, wordCount, wholeWord, caseSensitive, useRegex);
    if (core && !core->redactPatternCache) {
        core->redactPatternCache = new PatternCache();
        core->redactPatternDeleter = deletePatternCache;
    }
    auto* cache = core ? reinterpret_cast<PatternCache*>(core->redactPatternCache) : nullptr;
    CachedPcre2Pattern* cached = cache ? cache->find(sig, key) : nullptr;
    if (!useRegex) {
        Pcre2Pattern pc;
        bool patternReady = false;
        if (cached && !cached->codes.empty()) {
            patternReady = createPatternFromCode(cached->codes[0], cached->jst, pc);
        }
        if (!patternReady) {
            std::u32string combined;
            if (buildLiteralAlternation(words, wordCount, wholeWord, combined)) {
                std::string err;
                if (compilePcre2(combined, !caseSensitive, pc, err)) {
                    patternReady = true;
                    if (cache) {
                        auto nc = std::make_unique<CachedPcre2Pattern>();
                        nc->signature = sig;
                        nc->canonicalKey = std::move(key);
                        nc->codes.push_back(pc.code);
                        nc->jst = pc.jst;
                        cache->put(std::move(nc));
                        pc.ownsCode = false;
                    }
                } else {
                    rejectedPatterns += wordCount;
                }
            } else {
                rejectedPatterns += wordCount;
            }
        }
        if (patternReady) {
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
                appendMatchChars(chars, idxMap, static_cast<int>(start),
                                 static_cast<int>(end - start), padding, matches);
                offset = end;
            }
            if (outCompiledPatterns) outCompiledPatterns->push_back(std::move(pc));
        }
    } else {
        bool cacheHit = (cached && cached->codes.size() == static_cast<size_t>(wordCount));
        if (cacheHit) {
            for (size_t wi = 0; wi < cached->codes.size(); ++wi) {
                if (!cached->codes[wi]) continue;
                Pcre2Pattern pc;
                if (createPatternFromCode(cached->codes[wi], cached->jst, pc)) {
                    compiledCount++;
                    collectPcre2Matches(chars, wtext, idxMap, pc, padding, matches);
                    if (outCompiledPatterns) outCompiledPatterns->push_back(std::move(pc));
                } else {
                    // Wrapper allocation (match data/context) failed under
                    // memory pressure: record it so an entirely failed search
                    // is reported loudly instead of succeeding with no matches.
                    ++rejectedPatterns;
                }
            }
        } else {
            std::vector<pcre2_code*> newCodes;
            pcre2_jit_stack* sharedJst = nullptr;
            for (int32_t wi = 0; wi < wordCount; ++wi) {
                if (!words[wi]) {
                    newCodes.push_back(nullptr);
                    continue;
                }
                std::u32string wpattern = utf8_to_u32(words[wi]);
                if (wholeWord) {
                    wpattern.insert(0, U"\\b");
                    wpattern += U"\\b";
                }
                Pcre2Pattern pc;
                std::string err;
                if (!compilePcre2(wpattern, !caseSensitive, pc, err)) {
                    ++rejectedPatterns;
                    newCodes.push_back(nullptr);
                    continue;
                }
                compiledCount++;
                if (sharedJst && pc.jst && pc.jst != sharedJst) {
                    pcre2_jit_stack_free(pc.jst);
                    pc.jst = sharedJst;
                    pcre2_jit_stack_assign(pc.mctx, nullptr, sharedJst);
                }
                collectPcre2Matches(chars, wtext, idxMap, pc, padding, matches);
                if (cache) {
                    newCodes.push_back(pc.code);
                    if (!sharedJst && pc.jst) sharedJst = pc.jst;
                    pc.ownsCode = false;
                }
                if (outCompiledPatterns) outCompiledPatterns->push_back(std::move(pc));
            }
            if (cache && compiledCount > 0) {
                auto nc = std::make_unique<CachedPcre2Pattern>();
                nc->signature = sig;
                nc->canonicalKey = std::move(key);
                nc->codes = std::move(newCodes);
                nc->jst = sharedJst;
                cache->put(std::move(nc));
            }
        }
    }
}
#endif

// Snap each match span to grapheme-cluster boundaries: a redaction boundary
// that splits a grapheme (base + combining mark, emoji ZWJ sequences) leaves
// dangling marks in the surviving fragments. Expanding to the full cluster is
// the secure default. The match bbox is recomputed so the painted cover
// encloses everything removed.
void alignMatchesToGraphemes(FPDF_TEXTPAGE textPage, const std::vector<uint32_t>& unicodeSeq,
                             std::vector<TextMatch>& matches) {
#ifdef JPDFIUM_HAS_UNIBREAK
    if (unicodeSeq.empty() || matches.empty()) return;
    std::vector<char> brks(unicodeSeq.size(), 0);
    set_graphemebreaks_utf32(reinterpret_cast<const utf32_t*>(unicodeSeq.data()), unicodeSeq.size(),
                             "", brks.data());
    // brks[i] = whether a break exists BEFORE char i.
    CharBoxCache chars(textPage);
    for (auto& m : matches) {
        int first = -1, last = -1;
        for (int ci : m.charIndices) {
            if (first < 0 || ci < first) first = ci;
            if (ci > last) last = ci;
        }
        if (first < 0) continue;
        int origFirst = first;
        int origLast = last;
        // libunibreak fills brks[i] with the status of the boundary AFTER
        // character i (same convention as its line/word breakers).
        // A grapheme cluster MUST NOT cross whitespace boundaries or text object boundaries,
        // and cannot span more than 3 combining marks (max 4 chars total).
        while (first > 0 && (origFirst - first < 3) && brks[first - 1] == GRAPHEMEBREAK_NOBREAK) {
            uint32_t u = unicodeSeq[first - 1];
            if (u <= 0x20 || u == 0xA0) break;
            if (textPage) {
                FPDF_PAGEOBJECT oCurr = FPDFText_GetTextObject(textPage, first);
                FPDF_PAGEOBJECT oPrev = FPDFText_GetTextObject(textPage, first - 1);
                if (oCurr != oPrev || oCurr == nullptr) break;
            }
            first--;
        }
        while (last + 1 < static_cast<int>(unicodeSeq.size()) && (last - origLast < 3) &&
               brks[last] == GRAPHEMEBREAK_NOBREAK) {
            uint32_t u = unicodeSeq[last + 1];
            if (u <= 0x20 || u == 0xA0) break;
            if (textPage) {
                FPDF_PAGEOBJECT oCurr = FPDFText_GetTextObject(textPage, last);
                FPDF_PAGEOBJECT oNext = FPDFText_GetTextObject(textPage, last + 1);
                if (oCurr != oNext || oCurr == nullptr) break;
            }
            last++;
        }
        // Rebuild the (possibly extended) char index list.
        m.charIndices.clear();
        for (int ci = first; ci <= last; ci++) m.charIndices.push_back(ci);
        recomputeMatchBbox(chars, m);
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
void alignMatchesToShapedClusters(FPDF_TEXTPAGE tp, std::vector<TextMatch>& matches) {
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
    CharBoxCache chars(tp);
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
                                 run.clusterOf[p] == run.clusterOf[lp] && (p - lp <= 3);
                 p++)
                ce = p;

            // A ligature cluster (e.g. fi, ffi) spans at most 3-4 codepoints.
            // If the cluster spans across a large range or expands by more than 3 chars,
            // the font shaping failed or assigned a fallback/unshaped cluster ID.
            if (fp - cs > 3) cs = fp;
            if (ce - lp > 3) ce = lp;
            if (ce - cs + 1 > 4) {
                cs = fp;
                ce = lp;
            }

            // Never expand backwards across whitespace or kerning gaps
            for (int p = fp - 1; p >= cs; p--) {
                uint32_t u = FPDFText_GetUnicode(tp, run.chars[p]);
                if (u <= 0x20 || u == 0xA0) {
                    cs = p + 1;
                    break;
                }
                double l1 = 0, r1 = 0, b1 = 0, t1 = 0;
                double l2 = 0, r2 = 0, b2 = 0, t2 = 0;
                if (FPDFText_GetCharBox(tp, run.chars[p], &l1, &r1, &b1, &t1) &&
                    FPDFText_GetCharBox(tp, run.chars[p + 1], &l2, &r2, &b2, &t2)) {
                    double w1 = std::abs(r1 - l1);
                    double h1 = std::abs(t1 - b1);
                    if (w1 >= h1) {
                        double gap = std::abs(l2 - r1);
                        if ((w1 > 0.01 && gap > w1 * 0.25) || std::abs(b2 - b1) > h1 * 0.5) {
                            cs = p + 1;
                            break;
                        }
                    } else {
                        double gap = std::abs(b1 - t2);
                        if ((h1 > 0.01 && gap > h1 * 0.25) || std::abs(l2 - l1) > w1 * 0.5) {
                            cs = p + 1;
                            break;
                        }
                    }
                }
            }
            // Never expand forwards across whitespace or kerning gaps
            for (int p = lp + 1; p <= ce; p++) {
                uint32_t u = FPDFText_GetUnicode(tp, run.chars[p]);
                if (u <= 0x20 || u == 0xA0) {
                    ce = p - 1;
                    break;
                }
                double l1 = 0, r1 = 0, b1 = 0, t1 = 0;
                double l2 = 0, r2 = 0, b2 = 0, t2 = 0;
                if (FPDFText_GetCharBox(tp, run.chars[p - 1], &l1, &r1, &b1, &t1) &&
                    FPDFText_GetCharBox(tp, run.chars[p], &l2, &r2, &b2, &t2)) {
                    double w1 = std::abs(r1 - l1);
                    double h1 = std::abs(t1 - b1);
                    if (w1 >= h1) {
                        double gap = std::abs(l2 - r1);
                        if ((w1 > 0.01 && gap > w1 * 0.25) || std::abs(b2 - b1) > h1 * 0.5) {
                            ce = p - 1;
                            break;
                        }
                    } else {
                        double gap = std::abs(b1 - t2);
                        if ((h1 > 0.01 && gap > h1 * 0.25) || std::abs(l2 - l1) > w1 * 0.5) {
                            ce = p - 1;
                            break;
                        }
                    }
                }
            }

            if (cs > fp) cs = fp;
            if (ce < lp) ce = lp;

            if (cs == fp && ce == lp) continue;
            for (int p = cs; p <= ce; p++) m.charIndices.push_back(run.chars[p]);
            extended = true;
        }
        if (extended) {
            std::sort(m.charIndices.begin(), m.charIndices.end());
            m.charIndices.erase(std::unique(m.charIndices.begin(), m.charIndices.end()),
                                m.charIndices.end());
            recomputeMatchBbox(chars, m);
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
