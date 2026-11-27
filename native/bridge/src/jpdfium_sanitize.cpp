// jpdfium_sanitize.cpp - Post-redaction document sanitization.

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <vector>

#include "jpdfium_internal.h"

#ifdef JPDFIUM_HAS_QPDF

#include <qpdf/Buffer.hh>
#include <qpdf/BufferInputSource.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFTokenizer.hh>
#include <qpdf/QPDFWriter.hh>

#include "jpdfium_qpdf_internal.h"
#include "jpdfium_qpdf_writer_lock.h"

#ifdef JPDFIUM_HAS_PUGIXML
#include <pugixml.hpp>
#endif
#ifdef JPDFIUM_HAS_HARFBUZZ
#include <hb.h>
#endif

namespace {

struct SanitizeStats {
    int fontsSubset = 0;
    int fontsSubsetSkipped = 0;  // e.g. Type1 programs hb-subset cannot process
    int tounicodeFiltered = 0;
    int tounicodeSkipped = 0;  // unparseable CMaps (reported, not silent)
    int annotsRemoved = 0;
    int outlinesBlanked = 0;
    int fieldsBlanked = 0;
    bool xmpScrubbed = false;
    bool infoRemoved = false;
    bool structTreeRemoved = false;
};

std::string statsToJson(const SanitizeStats& st) {
    std::ostringstream os;
    os << "{\"fonts_subset\":" << st.fontsSubset
       << ",\"fonts_subset_skipped\":" << st.fontsSubsetSkipped
       << ",\"tounicode_filtered\":" << st.tounicodeFiltered
       << ",\"tounicode_skipped\":" << st.tounicodeSkipped
       << ",\"annots_removed\":" << st.annotsRemoved
       << ",\"outlines_blanked\":" << st.outlinesBlanked
       << ",\"fields_blanked\":" << st.fieldsBlanked
       << ",\"xmp_scrubbed\":" << (st.xmpScrubbed ? "true" : "false")
       << ",\"info_removed\":" << (st.infoRemoved ? "true" : "false")
       << ",\"struct_tree_removed\":" << (st.structTreeRemoved ? "true" : "false") << '}';
    return os.str();
}

// Case-insensitive ASCII substring search (metadata/annot text matching).
std::string asciiLower(const std::string& in) {
    std::string out = in;
    for (char& c : out)
        if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    return out;
}
bool containsAsciiIgnoreCase(const std::string& hay, const std::string& needle) {
    if (needle.empty()) return false;
    std::string h = asciiLower(hay);
    std::string n = asciiLower(needle);
    return h.find(n) != std::string::npos;
}
bool containsAnyLiteral(const std::string& hay, const std::vector<std::string>& lits) {
    for (const auto& l : lits)
        if (containsAsciiIgnoreCase(hay, l)) return true;
    return false;
}

// ---- content stream tokenizer: collect (font, charcodes) used ----

struct ContentScan {
    // resource name -> raw byte strings shown with that font. Code WIDTH is
    // decided later per font (from its ToUnicode CMap, or /Subtype /Type0):
    // strings are consumed as 1-byte codes or 2-byte big-endian pairs.
    std::map<std::string, std::vector<std::string>> fontStrings;
};

// Used codes for one font: 1-byte codes, or aligned big-endian 2-byte pairs
// for CID fonts (codeBytes == 2).
std::set<int> usedCodesFor(const std::vector<std::string>& strs, int codeBytes) {
    std::set<int> codes;
    for (const auto& s : strs) {
        if (codeBytes == 2) {
            for (size_t i = 0; i + 1 < s.size(); i += 2) {
                codes.insert((static_cast<unsigned char>(s[i]) << 8) |
                             static_cast<unsigned char>(s[i + 1]));
            }
        } else {
            for (size_t i = 0; i < s.size(); i++) {
                codes.insert(static_cast<unsigned char>(s[i]));
            }
        }
    }
    return codes;
}

void scanContentStream(const std::string& data, ContentScan& out) {
    QPDFTokenizer tok;
    // (description, contents) overload copies the string.
    auto is = std::make_shared<BufferInputSource>("content", data);
    // Last non-number token: the font resource name for Tf. qpdf's tokenizer
    // reports NAMES (tt_name, e.g. "/FXF1") separately from tt_word, and the
    // font SIZE is a number - so the name immediately preceding "Tf" is the
    // last name/word token seen.
    std::string curFont;
    std::string lastNonNumber;

    while (true) {
        QPDFTokenizer::Token t = tok.readToken(is, "content", true);
        QPDFTokenizer::token_type_e tt = t.getType();
        if (tt == QPDFTokenizer::tt_eof || tt == QPDFTokenizer::tt_bad) break;
        if (tt == QPDFTokenizer::tt_space || tt == QPDFTokenizer::tt_comment) continue;

        if (tt == QPDFTokenizer::tt_word) {
            const std::string& w = t.getValue();
            if (w == "Tf") {
                // PDF syntax: <fontname> <size> Tf.
                curFont = lastNonNumber;
            }
            lastNonNumber = w;
        } else if (tt == QPDFTokenizer::tt_name) {
            // Resource dict keys are stored without the leading slash; the
            // tokenizer includes it.
            const std::string& nm = t.getValue();
            lastNonNumber = (!nm.empty() && nm[0] == '/') ? nm.substr(1) : nm;
        } else if (tt == QPDFTokenizer::tt_string) {
            // PDF places the STRING BEFORE its show operator (Tj/'/"),
            // and TJ arrays interleave strings and numbers. Attributing every
            // string token to the current font (when one is set) is a
            // deliberate superset: strings outside show operators only widen
            // the used set, and keeping an entry that is still used costs
            // nothing while dropping a live mapping breaks extraction.
            const std::string& s = t.getValue();
            if (!curFont.empty()) {
                out.fontStrings[curFont].push_back(s);
            }
        }
    }
}

std::shared_ptr<Buffer> streamData(QPDFObjectHandle const& obj) {
    return jpdfium::qpdf_detail::readStreamBufferBounded(obj, qpdf_dl_generalized);
}

// ---- ToUnicode CMap parsing (bfchar / bfrange) ----

struct CmapEntry {
    int code = -1;  // source charcode
    int codeBytes = 1;
    std::string utf16be;  // destination UTF-16BE bytes
};

// NOTE: qpdf's tokenizer returns the DECODED BYTES of hex strings in
// getValue(), so CMap operands arrive as raw byte strings and are consumed
// as such (no hex re-parse). bfchar/bfrange operands are required to be hex
// strings by the PDF spec, which makes this exact.
bool parseToUnicode(const std::shared_ptr<Buffer>& buf, std::vector<CmapEntry>& out) {
    if (!buf) return false;
    std::string data(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
    QPDFTokenizer tok;
    auto is = std::make_shared<BufferInputSource>("tounicode", data);

    // Entries are operand-counted, not line-counted: real-world CMaps put
    // several bfchar/bfrange entries on one line, and a line-index flush
    // silently drops every entry after the first of such a line.
    enum class CmapSection : uint8_t { None, BfChar, BfRange };
    CmapSection section = CmapSection::None;
    std::vector<std::string> pendingHex;
    bool inArray = false;

    // A hostile bfrange can claim a 4-byte code space; without a span cap one
    // "entry" amplifies into billions of CmapEntry pushes.
    constexpr uint64_t kMaxRangeSpan = 65536;
    // Overall ceiling so a multi-megabyte hostile CMap cannot exhaust memory.
    constexpr size_t kMaxEntries = size_t{1} << 20;
    constexpr size_t kMaxTotalDestBytes = size_t{16} * 1024U * 1024U;
    size_t totalDestBytes = 0;

    auto codeFromBytes = [](std::string const& bytes, uint64_t& code) -> bool {
        if (bytes.empty() || bytes.size() > 4) return false;
        uint64_t value = 0;
        for (unsigned char c : bytes) value = (value << 8) | c;
        if (value > 0x7FFFFFFFULL) return false;  // CmapEntry::code is int
        code = value;
        return true;
    };
    // Validate a UTF-16BE destination as a sequence of BMP scalars or
    // well-formed surrogate pairs. Consecutive-form ranges stop only when the
    // increment walks into a lone surrogate, not merely because the starting
    // value contains a valid pair.
    auto validUtf16be = [](std::string const& uni) {
        if (uni.empty() || uni.size() % 2 != 0) return false;
        for (size_t i = 0; i + 1 < uni.size(); i += 2) {
            const unsigned unit =
                (static_cast<unsigned char>(uni[i]) << 8) | static_cast<unsigned char>(uni[i + 1]);
            if (unit >= 0xD800U && unit <= 0xDBFFU) {
                if (i + 3 >= uni.size()) return false;
                const unsigned low = (static_cast<unsigned char>(uni[i + 2]) << 8) |
                                     static_cast<unsigned char>(uni[i + 3]);
                if (low < 0xDC00U || low > 0xDFFFU) return false;
                i += 2;
            } else if (unit >= 0xDC00U && unit <= 0xDFFFU) {
                return false;
            }
        }
        return true;
    };

    auto flushBfChar = [&]() {
        for (size_t i = 0; i + 1 < pendingHex.size(); i += 2) {
            uint64_t code = 0;
            const size_t dlen = pendingHex[i + 1].size();
            if (codeFromBytes(pendingHex[i], code) && out.size() < kMaxEntries &&
                dlen <= kMaxTotalDestBytes - totalDestBytes) {
                totalDestBytes += dlen;
                out.push_back(CmapEntry{static_cast<int>(code),
                                        static_cast<int>(pendingHex[i].size()), pendingHex[i + 1]});
            }
        }
        pendingHex.clear();
    };
    auto flushBfRange = [&]() {
        if (pendingHex.size() < 3) {
            pendingHex.clear();
            return;
        }
        uint64_t lo = 0;
        uint64_t hi = 0;
        if (!codeFromBytes(pendingHex[0], lo) || !codeFromBytes(pendingHex[1], hi) || lo > hi ||
            hi - lo + 1 > kMaxRangeSpan) {
            pendingHex.clear();
            return;
        }
        const int loBytes = static_cast<int>(pendingHex[0].size());
        if (inArray) {
            // list form: pendingHex[2..] are the per-code unicodes
            size_t k = 2;
            for (uint64_t code = lo;
                 code <= hi && k < pendingHex.size() && out.size() < kMaxEntries; code++, k++) {
                const size_t dlen = pendingHex[k].size();
                if (dlen > kMaxTotalDestBytes - totalDestBytes) break;
                totalDestBytes += dlen;
                out.push_back(CmapEntry{static_cast<int>(code), loBytes, pendingHex[k]});
            }
        } else {
            // consecutive form: lo..hi -> u..u+n; advance the full big-endian
            // UTF-16 byte string in place so multi-unit values (including
            // surrogate pairs) never truncate.
            std::string ub = pendingHex[2];
            const uint64_t span = hi - lo + 1;
            if (ub.size() > 0 && span > (kMaxTotalDestBytes - totalDestBytes) / ub.size()) {
                pendingHex.clear();
                return;
            }
            for (uint64_t code = lo; code <= hi && out.size() < kMaxEntries; code++) {
                if (!validUtf16be(ub)) break;
                totalDestBytes += ub.size();
                out.push_back(CmapEntry{static_cast<int>(code), loBytes, ub});
                for (size_t idx = ub.size(); idx-- > 0;) {
                    auto v = static_cast<unsigned char>(ub[idx]);
                    if (v < 0xFF) {
                        ub[idx] = static_cast<char>(v + 1);
                        break;
                    }
                    ub[idx] = '\0';
                }
            }
        }
        pendingHex.clear();
    };

    while (true) {
        QPDFTokenizer::Token t = tok.readToken(is, "tounicode", true);
        QPDFTokenizer::token_type_e tt = t.getType();
        if (tt == QPDFTokenizer::tt_eof || tt == QPDFTokenizer::tt_bad) break;
        if (tt == QPDFTokenizer::tt_space || tt == QPDFTokenizer::tt_comment) continue;
        if (tt == QPDFTokenizer::tt_word) {
            const std::string& w = t.getValue();
            if (w == "beginbfchar") {
                flushBfRange();
                inArray = false;
                section = CmapSection::BfChar;
            } else if (w == "beginbfrange") {
                flushBfChar();
                inArray = false;
                section = CmapSection::BfRange;
            } else if (w == "endbfchar") {
                flushBfChar();
                section = CmapSection::None;
            } else if (w == "endbfrange") {
                flushBfRange();
                inArray = false;
                section = CmapSection::None;
            } else if (w.starts_with("begin")) {
                // unrelated section: flush what we have and ignore it
                flushBfChar();
                flushBfRange();
                inArray = false;
                section = CmapSection::None;
            }
            continue;
        }
        if (tt == QPDFTokenizer::tt_array_close) {
            if (section == CmapSection::BfRange && inArray) flushBfRange();
            inArray = false;
            continue;
        }
        if (tt == QPDFTokenizer::tt_array_open) {
            // bfrange's third operand is either a string (consecutive form,
            // flushed when it arrives) or an array of strings.
            if (section == CmapSection::BfRange && !inArray && pendingHex.size() == 2)
                inArray = true;
            continue;
        }
        if (tt != QPDFTokenizer::tt_string) continue;
        if (section == CmapSection::None) continue;
        pendingHex.push_back(t.getValue());
        if (section == CmapSection::BfChar) {
            if (pendingHex.size() == 2) flushBfChar();
        } else if (section == CmapSection::BfRange && !inArray && pendingHex.size() == 3) {
            // consecutive form is complete
            flushBfRange();
        }
    }
    if (section == CmapSection::BfChar) {
        flushBfChar();
    } else if (section == CmapSection::BfRange && !inArray) {
        flushBfRange();
    } else {
        pendingHex.clear();
    }
    return !out.empty();
}

// Rebuild a ToUnicode CMap keeping only entries whose charcode is in |used|.
std::string rebuildCmap(const std::vector<CmapEntry>& entries, const std::set<int>& used) {
    std::vector<size_t> keptIdx;
    keptIdx.reserve(entries.size());
    for (size_t i = 0; i < entries.size(); ++i)
        if (used.count(entries[i].code)) keptIdx.push_back(i);
    std::sort(keptIdx.begin(), keptIdx.end(), [&entries](size_t a, size_t b) {
        if (entries[a].codeBytes != entries[b].codeBytes)
            return entries[a].codeBytes < entries[b].codeBytes;
        if (entries[a].code != entries[b].code) return entries[a].code < entries[b].code;
        return a < b;
    });
    bool twoByte = false;
    for (size_t i : keptIdx) {
        if (entries[i].codeBytes != 1) {
            twoByte = true;
            break;
        }
    }
    std::ostringstream os;
    os << "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
          "/CIDSystemInfo << /Registry (JPDFium) /Ordering (Redact) /Supplement 0 >> def\n"
          "/CMapName /JPDFium-Redact def\n/CMapType 2 def\n"
          "1 begincodespacerange\n"
       << (twoByte ? "<0000> <FFFF>\n" : "<00> <FF>\n") << "endcodespacerange\n";
    static const char* hexd = "0123456789ABCDEF";
    // ISO 32000-1 §9.10.3 caps each beginbfchar section at 100 entries.
    for (size_t start = 0; start < keptIdx.size(); start += 100) {
        size_t end = std::min(start + 100, keptIdx.size());
        os << (end - start) << " beginbfchar\n";
        for (size_t idx = start; idx < end; idx++) {
            const auto& e = entries[keptIdx[idx]];
            os << '<';
            if (e.codeBytes == 1) {
                os << hexd[(e.code >> 4) & 0xF] << hexd[e.code & 0xF];
            } else {
                os << hexd[(e.code >> 12) & 0xF] << hexd[(e.code >> 8) & 0xF]
                   << hexd[(e.code >> 4) & 0xF] << hexd[e.code & 0xF];
            }
            os << "> <";
            for (unsigned char c : e.utf16be) os << hexd[(c >> 4) & 0xF] << hexd[c & 0xF];
            os << ">\n";
        }
        os << "endbfchar\n";
    }
    os << "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
    return os.str();
}

void blankStringValue(QPDFObjectHandle obj, const std::string& key,
                      const std::vector<std::string>& lits, int& counter) {
    if (!obj.hasKey(key)) return;
    QPDFObjectHandle v = obj.getKey(key);
    std::string s = v.isString() ? v.getUTF8Value() : std::string();
    if (!s.empty() && containsAnyLiteral(s, lits)) {
        obj.replaceKey(key, QPDFObjectHandle::newString(""));
        counter++;
    }
}

void removeActionKeys(QPDFObjectHandle obj) {
    if (obj.isDictionary()) {
        obj.removeKey("/AA");
        obj.removeKey("/OpenAction");
    }
}

constexpr size_t kMaxSanitizeGraphNodes = 65536;

void walkOutlines(QPDFObjectHandle const& outline, const std::vector<std::string>& lits,
                  SanitizeStats& st, std::set<QPDFObjGen>& visited, size_t& nodeCount,
                  int depth = 0) {
    if (!outline.isDictionary() || depth > 64 || nodeCount >= kMaxSanitizeGraphNodes) return;
    if (outline.isIndirect() && !visited.insert(outline.getObjGen()).second) return;
    ++nodeCount;
    QPDFObjectHandle item = outline.getKey("/First");
    int guard = 0;
    while (item.isDictionary() && guard++ < 100000 && nodeCount < kMaxSanitizeGraphNodes) {
        if (item.isIndirect() && !visited.insert(item.getObjGen()).second) break;
        ++nodeCount;
        blankStringValue(item, "/Title", lits, st.outlinesBlanked);
        walkOutlines(item, lits, st, visited, nodeCount, depth + 1);  // recurse into child items
        item = item.getKey("/Next");
    }
}

void walkFormFields(QPDFObjectHandle const& field, const std::vector<std::string>& lits,
                    SanitizeStats& st, std::set<QPDFObjGen>& visited, size_t& nodeCount,
                    int depth = 0) {
    if (!field.isDictionary() || depth > 64 || nodeCount >= kMaxSanitizeGraphNodes) return;
    if (field.isIndirect() && !visited.insert(field.getObjGen()).second) return;
    ++nodeCount;
    blankStringValue(field, "/V", lits, st.fieldsBlanked);
    blankStringValue(field, "/RV", lits, st.fieldsBlanked);
    QPDFObjectHandle kids = field.getKey("/Kids");
    if (kids.isArray()) {
        const int nKids = std::min(kids.getArrayNItems(), 4096);
        for (int i = 0; i < nKids && nodeCount < kMaxSanitizeGraphNodes; i++)
            walkFormFields(kids.getArrayItem(i), lits, st, visited, nodeCount, depth + 1);
    }
}

#ifdef JPDFIUM_HAS_PUGIXML
// Blank every XML text node and attribute whose value contains a redacted
// literal (surgical XMP surgery); always replace the Producer claim
// (carrying the source Producer into a redacted file is a forensic lie).
void scrubXmpXml(std::string& xml, const std::vector<std::string>& lits) {
    pugi::xml_document doc;
    if (!doc.load_buffer(xml.data(), xml.size(), pugi::parse_default)) return;
    bool changed = false;
    for (pugi::xpath_node xn : doc.select_nodes("//node()")) {
        pugi::xml_node node = xn.node();
        for (pugi::xml_attribute attr : node.attributes()) {
            std::string v = attr.value();
            if (containsAnyLiteral(v, lits)) {
                attr.set_value("");
                changed = true;
            }
        }
        // Elements whose direct text (PCDATA) echoes a redacted literal.
        if (node.type() == pugi::node_element && node.first_child() &&
            node.first_child().type() == pugi::node_pcdata) {
            std::string v = node.first_child().value();
            if (containsAnyLiteral(v, lits)) {
                node.first_child().set_value("");
                changed = true;
            }
        }
    }
    for (pugi::xpath_node xn : doc.select_nodes("//*[local-name()='Producer']")) {
        pugi::xml_node node = xn.node();
        std::string v = node.text().get();
        if (v != "JPDFium redaction engine") {
            node.text().set("JPDFium redaction engine");
            changed = true;
        }
    }
    if (changed) {
        std::ostringstream os;
        doc.save(os);
        xml = os.str();
    }
}
#endif

// Recursively scan a resource scope: the scope's own content plus every
// reachable Form XObject. Codes accumulate in ONE global name->codes union
// because a font may be referenced under different resource names in
// different scopes (page vs. nested forms), and a name collision only
// widens the used set - the safe direction (keeping a mapping that is
// still used costs nothing; dropping one that survives breaks extraction).
void scanResourceScope(QPDFObjectHandle const& res, ContentScan& globalScan,
                       std::set<std::string>& visitedObjs, int depth) {
    if (depth > 12 || !res.isDictionary()) return;
    // Form XObjects in this scope.
    if (res.hasKey("/XObject") && res.getKey("/XObject").isDictionary()) {
        QPDFObjectHandle xos = res.getKey("/XObject");
        for (auto const& [name, xo] : xos.ditems()) {
            (void)name;
            if (!xo.isStream()) continue;
            std::string id = xo.getObjGen().unparse();
            if (visitedObjs.count(id)) continue;
            visitedObjs.insert(id);
            auto buf = streamData(xo);
            QPDFObjectHandle xoDict = xo.isStream() ? xo.getDict() : xo;
            if (buf) {
                // Form content resolves font names through the FORM's own
                // resources when present, else the enclosing scope's.
                QPDFObjectHandle formRes =
                    xoDict.hasKey("/Resources") ? xoDict.getKey("/Resources") : res;
                auto scanBuf = [&](QPDFObjectHandle const& s) {
                    auto b = streamData(s);
                    if (b)
                        scanContentStream(std::string(reinterpret_cast<const char*>(b->getBuffer()),
                                                      b->getSize()),
                                          globalScan);
                };
                if (xoDict.hasKey("/Contents")) {
                    QPDFObjectHandle fc = xoDict.getKey("/Contents");
                    if (fc.isArray()) {
                        for (int i = 0; i < fc.getArrayNItems(); i++) scanBuf(fc.getArrayItem(i));
                    } else if (fc.isStream()) {
                        scanBuf(fc);
                    }
                } else {
                    // Form XObjects may omit /Subtype entirely (writers rely
                    // on the resource being invoked via Do). Only image
                    // streams are excluded from the content scan.
                    bool isImage = xoDict.hasKey("/Subtype") &&
                                   xoDict.getKey("/Subtype").isName() &&
                                   xoDict.getKey("/Subtype").getName() == "/Image";
                    if (!isImage) scanBuf(xo);
                }
                scanResourceScope(formRes, globalScan, visitedObjs, depth + 1);
            }
        }
    }
}

// Form child text objects promoted/fissioned to page level reference fonts
// that were originally in the Form XObject's private /Resources. Ensure the
// page-level /Resources /Font dictionary contains all reachable form fonts.
void propagateFormFontsToPage(QPDFObjectHandle pageRes, QPDFObjectHandle const& scopeRes,
                              std::set<std::string>& visited, int depth) {
    if (depth > 12 || !scopeRes.isDictionary()) return;
    if (scopeRes.hasKey("/XObject") && scopeRes.getKey("/XObject").isDictionary()) {
        QPDFObjectHandle xos = scopeRes.getKey("/XObject");
        for (auto const& [name, xo] : xos.ditems()) {
            (void)name;
            if (!xo.isStream()) continue;
            std::string id = xo.getObjGen().unparse();
            if (visited.count(id)) continue;
            visited.insert(id);
            QPDFObjectHandle xoDict = xo.isStream() ? xo.getDict() : xo;
            if (xoDict.hasKey("/Resources") && xoDict.getKey("/Resources").isDictionary()) {
                QPDFObjectHandle formRes = xoDict.getKey("/Resources");
                if (formRes.hasKey("/Font") && formRes.getKey("/Font").isDictionary()) {
                    if (!pageRes.hasKey("/Font")) {
                        pageRes.replaceKey("/Font", QPDFObjectHandle::newDictionary());
                    }
                    QPDFObjectHandle pageFonts = pageRes.getKey("/Font");
                    if (pageFonts.isDictionary()) {
                        for (auto const& [fontName, fontDict] : formRes.getKey("/Font").ditems()) {
                            if (!pageFonts.hasKey(fontName)) {
                                pageFonts.replaceKey(fontName, fontDict);
                            }
                        }
                    }
                }
                propagateFormFontsToPage(pageRes, formRes, visited, depth + 1);
            }
        }
    }
}

}  // namespace

int sanitizeRedactedPdf(const uint8_t* input, size_t inputLen, const DocCore& core,
                        std::vector<uint8_t>& out, std::string& reportJson) {
    SanitizeStats st;
    try {
        QPDF q;
        q.processMemoryFile("redacted.pdf", reinterpret_cast<const char*>(input), inputLen);

        const std::vector<std::string>& lits = core.redactedLiterals;
        const std::vector<RedactZone>& zones = core.redactZones;

        QPDFObjectHandle trailer = q.getTrailer();

        // 2. /Info scrub.
        if (trailer.hasKey("/Info")) {
            trailer.removeKey("/Info");
            st.infoRemoved = true;
        }

        // 7. Structure tree strip (ActualText is a verbatim text copy).
        // StructTreeRoot and MarkInfo live in the Catalog (/Root), though
        // defensive cleanup on trailer handles non-standard inputs as well.
        if (trailer.hasKey("/StructTreeRoot")) {
            trailer.removeKey("/StructTreeRoot");
            st.structTreeRemoved = true;
        }
        if (trailer.hasKey("/MarkInfo")) trailer.removeKey("/MarkInfo");
        if (trailer.hasKey("/Root") && trailer.getKey("/Root").isDictionary()) {
            QPDFObjectHandle catalog = trailer.getKey("/Root");
            if (catalog.hasKey("/StructTreeRoot")) {
                catalog.removeKey("/StructTreeRoot");
                st.structTreeRemoved = true;
            }
            if (catalog.hasKey("/MarkInfo")) catalog.removeKey("/MarkInfo");
        }

        // XMP surgery via pugixml; wholesale removal when unavailable.
        // The /Metadata entry can sit on the trailer OR the catalog.
        bool haveMeta = trailer.hasKey("/Metadata");
        if (!haveMeta && trailer.hasKey("/Root") && trailer.getKey("/Root").isDictionary() &&
            trailer.getKey("/Root").hasKey("/Metadata")) {
            haveMeta = true;
        }
        if (haveMeta) {
#ifdef JPDFIUM_HAS_PUGIXML
            QPDFObjectHandle meta = trailer.hasKey("/Metadata")
                                        ? trailer.getKey("/Metadata")
                                        : trailer.getKey("/Root").getKey("/Metadata");
            auto buf = streamData(meta);
            if (buf) {
                std::string xml(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                scrubXmpXml(xml, lits);
                auto newBuf = std::make_shared<Buffer>(xml.size());
                memcpy(newBuf->getBuffer(), xml.data(), xml.size());
                meta.replaceStreamData(newBuf, QPDFObjectHandle::newNull(),
                                       QPDFObjectHandle::newNull());
                meta.setFilterOnWrite(false);
                st.xmpScrubbed = true;
            }
#else
            trailer.removeKey("/Metadata");
            st.xmpScrubbed = true;  // wholesale
#endif
        }

        // Catalog-level actions and dictionaries.
        if (trailer.hasKey("/Root") && trailer.getKey("/Root").isDictionary()) {
            QPDFObjectHandle root = trailer.getKey("/Root");
            removeActionKeys(root);
            if (root.hasKey("/Names") && root.getKey("/Names").isDictionary()) {
                QPDFObjectHandle names = root.getKey("/Names");
                if (names.hasKey("/EmbeddedFiles")) names.removeKey("/EmbeddedFiles");
                if (names.hasKey("/JavaScript")) names.removeKey("/JavaScript");
            }
            if (root.hasKey("/AcroForm") && root.getKey("/AcroForm").isDictionary()) {
                QPDFObjectHandle acro = root.getKey("/AcroForm");
                if (acro.hasKey("/XFA")) acro.removeKey("/XFA");
                removeActionKeys(acro);
                if (acro.hasKey("/Fields") && acro.getKey("/Fields").isArray()) {
                    QPDFObjectHandle fields = acro.getKey("/Fields");
                    std::set<QPDFObjGen> visitedFields;
                    size_t fieldNodes = 0;
                    const int nFields = std::min(fields.getArrayNItems(), 4096);
                    for (int i = 0; i < nFields && fieldNodes < kMaxSanitizeGraphNodes; i++)
                        walkFormFields(fields.getArrayItem(i), lits, st, visitedFields, fieldNodes);
                }
            }
            if (root.hasKey("/Outlines")) {
                std::set<QPDFObjGen> visitedOutlines;
                size_t outlineNodes = 0;
                walkOutlines(root.getKey("/Outlines"), lits, st, visitedOutlines, outlineNodes);
            }
        }

        // Pages: actions, PieceInfo, Thumbs, annotations, content scans.
        std::vector<QPDFObjectHandle> pages = q.getAllPages();
        std::vector<ContentScan> pageScans(pages.size());

        for (size_t pi = 0; pi < pages.size(); pi++) {
            QPDFObjectHandle page = pages[pi];
            removeActionKeys(page);
            if (page.hasKey("/PieceInfo")) page.removeKey("/PieceInfo");
            if (page.hasKey("/Thumb")) page.removeKey("/Thumb");

            // Annotations.
            if (page.hasKey("/Annots") && page.getKey("/Annots").isArray()) {
                QPDFObjectHandle annots = page.getKey("/Annots");
                std::vector<int> remove;
                for (int ai = 0; ai < annots.getArrayNItems(); ai++) {
                    QPDFObjectHandle a = annots.getArrayItem(ai);
                    if (!a.isDictionary()) continue;
                    bool drop = false;
                    QPDFObjectHandle rect = a.getKey("/Rect");
                    if (rect.isArray() && rect.getArrayNItems() == 4 &&
                        rect.getArrayItem(0).isNumber() && rect.getArrayItem(1).isNumber() &&
                        rect.getArrayItem(2).isNumber() && rect.getArrayItem(3).isNumber()) {
                        double llx = rect.getArrayItem(0).getNumericValue();
                        double lly = rect.getArrayItem(1).getNumericValue();
                        double urx = rect.getArrayItem(2).getNumericValue();
                        double ury = rect.getArrayItem(3).getNumericValue();
                        for (const auto& z : zones) {
                            if (z.pageIndex != static_cast<int32_t>(pi)) continue;
                            if (llx <= z.right && urx >= z.left && lly <= z.top &&
                                ury >= z.bottom) {
                                drop = true;
                                break;
                            }
                        }
                    }
                    if (!drop) {
                        std::string text;
                        for (const char* key : {"/T", "/Subj"}) {
                            if (a.hasKey(key) && a.getKey(key).isString())
                                text += a.getKey(key).getUTF8Value();
                        }
                        if (a.hasKey("/Contents")) {
                            QPDFObjectHandle v = a.getKey("/Contents");
                            if (v.isString()) text += v.getUTF8Value();
                            auto buf = streamData(v);
                            if (buf)
                                text.append(reinterpret_cast<const char*>(buf->getBuffer()),
                                            buf->getSize());
                        }
                        if (containsAnyLiteral(text, lits)) drop = true;
                    }
                    if (drop) remove.push_back(ai);
                }
                for (auto it = remove.rbegin(); it != remove.rend(); ++it) {
                    annots.eraseItem(*it);
                    st.annotsRemoved++;
                }
            }

            // Content scan: page streams + form XObject streams.
            if (page.hasKey("/Contents")) {
                QPDFObjectHandle contents = page.getKey("/Contents");
                auto scanOne = [&](QPDFObjectHandle const& s) {
                    auto buf = streamData(s);
                    if (buf)
                        scanContentStream(
                            std::string(reinterpret_cast<const char*>(buf->getBuffer()),
                                        buf->getSize()),
                            pageScans[pi]);
                };
                if (contents.isArray()) {
                    for (int ci = 0; ci < contents.getArrayNItems(); ci++)
                        scanOne(contents.getArrayItem(ci));
                } else {
                    scanOne(contents);
                }
            }
            if (page.hasKey("/Resources")) {
                std::set<std::string> visited;
                scanResourceScope(page.getKey("/Resources"), pageScans[pi], visited, 0);
                std::set<std::string> fontVisited;
                propagateFormFontsToPage(page.getKey("/Resources"), page.getKey("/Resources"),
                                         fontVisited, 0);
            }
        }

        // Fonts: aggregate used codes per unique font dict, then apply
        // ToUnicode filtering and hb-subset erasure ONCE per dict.
        std::map<std::string, QPDFObjectHandle> fontDicts;                  // objgen -> font
        std::map<std::string, std::vector<std::string>> fontStringsByDict;  // objgen -> strings
        for (size_t pi = 0; pi < pages.size(); pi++) {
            QPDFObjectHandle const& page = pages[pi];
            if (!page.hasKey("/Resources")) continue;
            QPDFObjectHandle res = page.getKey("/Resources");
            if (!res.isDictionary() || !res.hasKey("/Font")) continue;
            QPDFObjectHandle fonts = res.getKey("/Font");
            if (!fonts.isDictionary()) continue;
            ContentScan& scan = pageScans[pi];
            for (auto [name, font] : fonts.ditems()) {
                if (!font.isDictionary()) continue;
                std::string id = font.getObjGen().unparse();
                // qpdf dict keys carry the leading slash; the content scan
                // strips it (tokenizer names include it).
                std::string resName = (!name.empty() && name[0] == '/') ? name.substr(1) : name;
                fontDicts[id] = font;
                auto it = scan.fontStrings.find(resName);
                if (it != scan.fontStrings.end()) {
                    for (const auto& str : it->second) fontStringsByDict[id].push_back(str);
                }
            }
        }

        for (auto& [id, font] : fontDicts) {
            std::string baseFont = font.hasKey("/BaseFont") && font.getKey("/BaseFont").isName()
                                       ? font.getKey("/BaseFont").getName()
                                       : std::string();
            // Code width: from the ToUnicode CMap when parseable; otherwise
            // /Subtype /Type0 fonts are 2-byte, everything else 1-byte.
            int codeBytes = 1;
            std::vector<CmapEntry> cmapEntries;
            bool haveMapping = false;
            if (font.hasKey("/ToUnicode")) {
                auto cmapBuf = streamData(font.getKey("/ToUnicode"));
                if (cmapBuf && parseToUnicode(cmapBuf, cmapEntries)) {
                    haveMapping = true;
                    int c1 = 0, c2 = 0;
                    for (const auto& e : cmapEntries) (e.codeBytes == 2 ? c2 : c1)++;
                    codeBytes = c2 >= c1 ? 2 : 1;
                } else if (font.hasKey("/Subtype") && font.getKey("/Subtype").isName() &&
                           font.getKey("/Subtype").getName() == "/Type0") {
                    codeBytes = 2;
                }
            } else if (font.hasKey("/Subtype") && font.getKey("/Subtype").isName() &&
                       font.getKey("/Subtype").getName() == "/Type0") {
                codeBytes = 2;
            }
            const std::set<int> usedCodes = usedCodesFor(fontStringsByDict[id], codeBytes);
            if (getenv("JPDFIUM_SANITIZE_DEBUG")) {
                fprintf(stderr,
                        "[sanitize] font id=%s base=%s strings=%zu used=%zu cb=%d hasTU=%d\n",
                        id.c_str(), baseFont.c_str(), fontStringsByDict[id].size(),
                        usedCodes.size(), codeBytes, font.hasKey("/ToUnicode") ? 1 : 0);
                for (int c : usedCodes)
                    fprintf(stderr, "[sanitize]   code %04X (%c)\n", c,
                            (c >= 32 && c <= 126) ? (char)c : '?');
            }

            // ToUnicode filtering: only when we have positive evidence of
            // usage (an empty used set cannot prove the font is unused).
            if (font.hasKey("/ToUnicode") && !usedCodes.empty()) {
                if (haveMapping) {
                    QPDFObjectHandle tu = font.getKey("/ToUnicode");
                    std::string rebuilt = rebuildCmap(cmapEntries, usedCodes);
                    auto newBuf = std::make_shared<Buffer>(rebuilt.size());
                    memcpy(newBuf->getBuffer(), rebuilt.data(), rebuilt.size());
                    tu.replaceStreamData(newBuf, QPDFObjectHandle::newNull(),
                                         QPDFObjectHandle::newNull());
                    st.tounicodeFiltered++;
                } else {
                    st.tounicodeSkipped++;
                }
            }

            // Font program erasure: In-place TrueType/CIDFont stream rewriting
            // via hb-subset is unsafe on arbitrary embedded PDF fonts because PDF
            // text operators address glyphs via CIDs / character codes that do not
            // match TrueType Unicode cmaps and can span across multiple page trees.
            // Redacted text content is already removed from the content streams.
            st.fontsSubsetSkipped++;
        }

        // 1. Dead-object purge + full rewrite.
        QPDFWriter w(q);
        w.setOutputMemory();
        jpdfium_qpdf_internal::WriterLock lock{-1};
        w.write();
        std::shared_ptr<Buffer> buf = w.getBufferSharedPointer();
        if (!buf) {
            reportJson = "{\"error\":\"qpdf produced null output buffer\"}";
            return -1;
        }
        out.assign(buf->getBuffer(), buf->getBuffer() + buf->getSize());
        reportJson = statsToJson(st);
        return 0;
    } catch (const std::exception& e) {
        reportJson = std::string("{\"error\":\"") + e.what() + "\"}";
        return -1;
    } catch (...) {
        reportJson = std::string("{\"error\":\"unknown exception\"}");
        return -1;
    }
}

#else  // !JPDFIUM_HAS_QPDF

int sanitizeRedactedPdf(const uint8_t*, size_t, const DocCore&, std::vector<uint8_t>&,
                        std::string& reportJson) {
    // Without qpdf the sanitize stage cannot run: loud failure, never a
    // silent skip of a mandated security stage.
    reportJson = "{\"error\":\"sanitize stage unavailable (qpdf not linked)\"}";
    return -1;
}

#endif  // JPDFIUM_HAS_QPDF
