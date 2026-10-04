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
    pcre2_jit_stack* jst = pcre2_jit_stack_create(32 * 1024, 512 * 1024, nullptr);
    if (jst) pcre2_jit_stack_assign(mctx, nullptr, jst);
    out.code = code;
    out.md = md;
    out.mctx = mctx;
    out.jst = jst;
    out.ownsCode = true;
    return true;
}
#endif

// Rebuild a TextMatch bbox from its char indices (used after grapheme or
// cluster extension so the painted cover encloses everything removed).
bool recomputeMatchBbox(FPDF_TEXTPAGE textPage, TextMatch& m) {
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
void collectPcre2Matches(FPDF_TEXTPAGE textPage, const std::u32string& text,
                         const std::vector<int>& idxMap, const Pcre2Pattern& pc, float padding,
                         std::vector<TextMatch>& out) {
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
                appendMatchChars(tp, idxMap, static_cast<int>(start), static_cast<int>(end - start),
                                 padding, matches);
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
                    collectPcre2Matches(tp, wtext, idxMap, pc, padding, matches);
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
                collectPcre2Matches(tp, wtext, idxMap, pc, padding, matches);
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
