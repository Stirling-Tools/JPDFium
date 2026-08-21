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

#ifdef JPDFIUM_HAS_PUGIXML
#include <pugixml.hpp>
#endif
#ifdef JPDFIUM_HAS_HARFBUZZ
#include <hb-subset.h>
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
            std::string w = t.getValue();
            if (w == "Tf") {
                // PDF syntax: <fontname> <size> Tf.
                curFont = lastNonNumber;
            }
            lastNonNumber = w;
        } else if (tt == QPDFTokenizer::tt_name) {
            // Resource dict keys are stored without the leading slash; the
            // tokenizer includes it.
            std::string nm = t.getValue();
            lastNonNumber = (!nm.empty() && nm[0] == '/') ? nm.substr(1) : nm;
        } else if (tt == QPDFTokenizer::tt_string) {
            // PDF places the STRING BEFORE its show operator (Tj/'/"),
            // and TJ arrays interleave strings and numbers. Attributing every
            // string token to the current font (when one is set) is a
            // deliberate superset: strings outside show operators only widen
            // the used set, and keeping an entry that is still used costs
            // nothing while dropping a live mapping breaks extraction.
            std::string s = t.getValue();
            if (!curFont.empty()) {
                out.fontStrings[curFont].push_back(s);
            }
        }
    }
}

std::shared_ptr<Buffer> streamData(QPDFObjectHandle obj) {
    if (!obj.isStream()) return nullptr;
    try {
        return obj.getStreamData(qpdf_dl_generalized);
    } catch (const QPDFExc&) {
        return nullptr;
    }
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

    // Line-number index: bfchar/bfrange entries are one per LINE, so an entry
    // is flushed when the token stream crosses a newline.
    std::vector<size_t> lineStarts;
    lineStarts.push_back(0);
    for (size_t i = 0; i < data.size(); i++)
        if (data[i] == '\n') lineStarts.push_back(i + 1);
    auto lineOf = [&](qpdf_offset_t pos) -> size_t {
        auto it = std::upper_bound(lineStarts.begin(), lineStarts.end(), static_cast<size_t>(pos));
        return static_cast<size_t>(it - lineStarts.begin()) - 1;
    };

    enum class CmapSection { None, BfChar, BfRange };
    CmapSection section = CmapSection::None;
    std::vector<std::string> pendingHex;
    size_t pendingLine = 0;
    bool havePending = false;

    auto flushBfChar = [&]() {
        if (pendingHex.size() >= 2) {
            const std::string& codeBytes = pendingHex[0];
            const std::string& uniBytes = pendingHex[1];
            if (!codeBytes.empty()) {
                int code = 0;
                for (unsigned char c : codeBytes) code = (code << 8) | c;
                out.push_back(CmapEntry{code, static_cast<int>(codeBytes.size()), uniBytes});
            }
        }
        pendingHex.clear();
        havePending = false;
    };
    auto flushBfRange = [&]() {
        if (pendingHex.size() >= 3) {
            const std::string& loBytes = pendingHex[0];
            const std::string& hiBytes = pendingHex[1];
            const std::string& uniBytes = pendingHex[2];
            if (!loBytes.empty() && loBytes.size() == hiBytes.size() && !uniBytes.empty()) {
                int lo = 0, hi = 0;
                for (unsigned char c : loBytes) lo = (lo << 8) | c;
                for (unsigned char c : hiBytes) hi = (hi << 8) | c;
                if (pendingHex.size() == 3) {
                    // consecutive form: lo..hi -> u..u+n
                    int unicode = 0;
                    for (unsigned char c : uniBytes) unicode = (unicode << 8) | c;
                    for (int code = lo; code <= hi; code++) {
                        std::string ub(2, '\0');
                        ub[0] = static_cast<char>((unicode >> 8) & 0xFF);
                        ub[1] = static_cast<char>(unicode & 0xFF);
                        out.push_back(CmapEntry{code, static_cast<int>(loBytes.size()), ub});
                        unicode++;
                    }
                } else {
                    // list form: pendingHex[2..] are the unicodes
                    for (int code = lo, k = 2;
                         code <= hi && k < static_cast<int>(pendingHex.size()); code++, k++) {
                        out.push_back(
                            CmapEntry{code, static_cast<int>(loBytes.size()), pendingHex[k]});
                    }
                }
            }
        }
        pendingHex.clear();
        havePending = false;
    };

    while (true) {
        QPDFTokenizer::Token t = tok.readToken(is, "tounicode", true);
        QPDFTokenizer::token_type_e tt = t.getType();
        if (tt == QPDFTokenizer::tt_eof || tt == QPDFTokenizer::tt_bad) break;
        if (tt == QPDFTokenizer::tt_space || tt == QPDFTokenizer::tt_comment) continue;
        // InputSource::getLastOffset() points at the beginning of the last
        // token read (QPDFTokenizer::readToken contract).
        size_t line = lineOf(is->getLastOffset());
        // A newline inside an entry group terminates the current entry.
        if (havePending && line != pendingLine) {
            if (section == CmapSection::BfChar)
                flushBfChar();
            else if (section == CmapSection::BfRange)
                flushBfRange();
        }
        if (tt == QPDFTokenizer::tt_word) {
            std::string w = t.getValue();
            if (w == "beginbfchar") {
                flushBfRange();
                section = CmapSection::BfChar;
            } else if (w == "beginbfrange") {
                flushBfChar();
                section = CmapSection::BfRange;
            } else if (w == "endbfchar") {
                flushBfChar();
                section = CmapSection::None;
            } else if (w == "endbfrange") {
                flushBfRange();
                section = CmapSection::None;
            } else if (w.starts_with("begin")) {
                // unrelated section: flush what we have and ignore it
                flushBfChar();
                flushBfRange();
                section = CmapSection::None;
            }
        } else if (tt == QPDFTokenizer::tt_string) {
            if (section != CmapSection::None) {
                pendingHex.push_back(t.getValue());
                pendingLine = line;
                havePending = true;
            }
        }
    }
    if (section == CmapSection::BfChar)
        flushBfChar();
    else if (section == CmapSection::BfRange)
        flushBfRange();
    return !out.empty();
}

// Rebuild a ToUnicode CMap keeping only entries whose charcode is in |used|.
std::string rebuildCmap(const std::vector<CmapEntry>& entries, const std::set<int>& used) {
    std::vector<const CmapEntry*> kept;
    for (const auto& e : entries)
        if (used.count(e.code)) kept.push_back(&e);
    std::sort(kept.begin(), kept.end(), [](const CmapEntry* a, const CmapEntry* b) {
        if (a->codeBytes != b->codeBytes) return a->codeBytes < b->codeBytes;
        return a->code < b->code;
    });
    std::ostringstream os;
    os << "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
          "/CIDSystemInfo << /Registry (JPDFium) /Ordering (Redact) /Supplement 0 >> def\n"
          "/CMapName /JPDFium-Redact def\n/CMapType 2 def\n";
    static const char* hexd = "0123456789ABCDEF";
    os << kept.size() << " beginbfchar\n";
    for (const auto* e : kept) {
        os << '<';
        if (e->codeBytes == 1) {
            os << hexd[(e->code >> 4) & 0xF] << hexd[e->code & 0xF];
        } else {
            os << hexd[(e->code >> 12) & 0xF] << hexd[(e->code >> 8) & 0xF]
               << hexd[(e->code >> 4) & 0xF] << hexd[e->code & 0xF];
        }
        os << "> <";
        for (unsigned char c : e->utf16be) os << hexd[(c >> 4) & 0xF] << hexd[c & 0xF];
        os << ">\n";
    }
    os << "endbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n";
    return os.str();
}

// ---- hb-subset font program erasure ----

#ifdef JPDFIUM_HAS_HARFBUZZ
bool subsetFontWithUnicodes(const std::vector<uint8_t>& fontData,
                            const std::set<uint32_t>& unicodes, std::vector<uint8_t>& out) {
    hb_blob_t* blob = hb_blob_create(reinterpret_cast<const char*>(fontData.data()),
                                     static_cast<unsigned>(fontData.size()),
                                     HB_MEMORY_MODE_READONLY, nullptr, nullptr);
    if (!blob) return false;
    hb_face_t* face = hb_face_create(blob, 0);
    if (!face) {
        hb_blob_destroy(blob);
        return false;
    }
    hb_set_t* uniSet = hb_set_create();
    for (uint32_t u : unicodes) hb_set_add(uniSet, u);

    hb_subset_input_t* input = hb_subset_input_create_or_fail();
    if (!input) {
        hb_set_destroy(uniSet);
        hb_face_destroy(face);
        hb_blob_destroy(blob);
        return false;
    }
    // hb_subset_input_set RETURNS the set to populate; unicodes go there.
    hb_set_t* inSet = hb_subset_input_set(input, HB_SUBSET_SETS_UNICODE);
    hb_set_union(inSet, uniSet);
    hb_subset_input_set_flags(input, HB_SUBSET_FLAGS_RETAIN_GIDS | HB_SUBSET_FLAGS_NOTDEF_OUTLINE |
                                         HB_SUBSET_FLAGS_PASSTHROUGH_UNRECOGNIZED);
    // RETAIN_GIDS keeps glyph indices stable; dropped glyphs lose their
    // outlines and render .notdef. (RETAIN_NUM_GLYPHS would keep the glyph
    // count but is HB_EXPERIMENTAL_API-only - the vendored build does not
    // enable experimental APIs.)

    bool ok = false;
    hb_face_t* sub = hb_subset_or_fail(face, input);
    if (sub) {
        hb_blob_t* outBlob = hb_face_reference_blob(sub);
        if (outBlob) {
            unsigned len = 0;
            const char* d = hb_blob_get_data(outBlob, &len);
            if (d && len > 0) {
                out.assign(d, d + len);
                ok = true;
            }
            hb_blob_destroy(outBlob);
        }
        hb_face_destroy(sub);
    }
    hb_subset_input_destroy(input);
    hb_set_destroy(uniSet);
    hb_face_destroy(face);
    hb_blob_destroy(blob);
    return ok;
}
#endif

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

void walkOutlines(QPDFObjectHandle outline, const std::vector<std::string>& lits,
                  SanitizeStats& st) {
    if (!outline.isDictionary()) return;
    QPDFObjectHandle item = outline.getKey("/First");
    int guard = 0;
    while (item.isDictionary() && guard++ < 100000) {
        blankStringValue(item, "/Title", lits, st.outlinesBlanked);
        walkOutlines(item, lits, st);  // recurse into child item lists
        item = item.getKey("/Next");
    }
}

void walkFormFields(QPDFObjectHandle field, const std::vector<std::string>& lits,
                    SanitizeStats& st) {
    if (!field.isDictionary()) return;
    blankStringValue(field, "/V", lits, st.fieldsBlanked);
    blankStringValue(field, "/RV", lits, st.fieldsBlanked);
    QPDFObjectHandle kids = field.getKey("/Kids");
    if (kids.isArray()) {
        for (int i = 0; i < kids.getArrayNItems(); i++)
            walkFormFields(kids.getArrayItem(i), lits, st);
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
void scanResourceScope(QPDFObjectHandle res, ContentScan& globalScan,
                       std::set<std::string>& visitedObjs, int depth) {
    if (depth > 12 || !res.isDictionary()) return;
    // Form XObjects in this scope.
    if (res.hasKey("/XObject") && res.getKey("/XObject").isDictionary()) {
        QPDFObjectHandle xos = res.getKey("/XObject");
        for (auto [name, xo] : xos.ditems()) {
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
                auto scanBuf = [&](QPDFObjectHandle s) {
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
void propagateFormFontsToPage(QPDFObjectHandle pageRes, QPDFObjectHandle scopeRes,
                              std::set<std::string>& visited, int depth) {
    if (depth > 12 || !scopeRes.isDictionary()) return;
    if (scopeRes.hasKey("/XObject") && scopeRes.getKey("/XObject").isDictionary()) {
        QPDFObjectHandle xos = scopeRes.getKey("/XObject");
        for (auto [name, xo] : xos.ditems()) {
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
                        for (auto [fontName, fontDict] : formRes.getKey("/Font").ditems()) {
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
        if (trailer.hasKey("/StructTreeRoot")) {
            trailer.removeKey("/StructTreeRoot");
            st.structTreeRemoved = true;
        }
        if (trailer.hasKey("/MarkInfo")) trailer.removeKey("/MarkInfo");

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
                    for (int i = 0; i < fields.getArrayNItems(); i++)
                        walkFormFields(fields.getArrayItem(i), lits, st);
                }
            }
            if (root.hasKey("/Outlines")) walkOutlines(root.getKey("/Outlines"), lits, st);
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
                    if (a.hasKey("/Rect") && a.getKey("/Rect").isArray() &&
                        a.getKey("/Rect").getArrayNItems() == 4) {
                        double llx = a.getKey("/Rect").getArrayItem(0).getNumericValue();
                        double lly = a.getKey("/Rect").getArrayItem(1).getNumericValue();
                        double urx = a.getKey("/Rect").getArrayItem(2).getNumericValue();
                        double ury = a.getKey("/Rect").getArrayItem(3).getNumericValue();
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
                auto scanOne = [&](QPDFObjectHandle s) {
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
            QPDFObjectHandle page = pages[pi];
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
            std::string baseFont =
                font.hasKey("/BaseFont") ? font.getKey("/BaseFont").getName() : std::string();
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

            // Font program erasure for redaction-touched fonts.
            bool touched = false;
            for (const auto& t : core.touchedFontNames) {
                if (!t.empty() && baseFont.find(t) != std::string::npos) {
                    touched = true;
                    break;
                }
            }
            if (!touched) continue;

#ifdef JPDFIUM_HAS_HARFBUZZ
            // Surviving unicodes: (a) used codes mapped through the font's
            // ToUnicode, plus (b) ASCII codes that appear in the content as
            // plain bytes (fission re-emits text with identity codes when
            // the font has no usable mapping - those decode as their own
            // ASCII values). When a used code is neither mapped nor ASCII
            // (e.g. a WinAnsi high byte), the survival set cannot be
            // determined reliably and the font is NOT subset (skipped and
            // reported - erasing to a wrong set would corrupt survivors).
            std::set<uint32_t> survivingUnicodes;
            bool fullyAccounted = true;
            std::set<int> accounted;
            for (const auto& e : cmapEntries) {
                if (!usedCodes.count(e.code)) continue;
                uint32_t u = 0;
                for (unsigned char c : e.utf16be) u = (u << 8) | c;
                if (u > 0 && u != 0xFFFF) {
                    survivingUnicodes.insert(u);
                    accounted.insert(e.code);
                }
            }
            for (int c : usedCodes) {
                if (accounted.count(c)) continue;
                if (c >= 0x20 && c <= 0x7E) {
                    survivingUnicodes.insert(static_cast<uint32_t>(c));
                    accounted.insert(c);
                } else {
                    fullyAccounted = false;
                }
            }
            const char* fontFileKeys[] = {"/FontFile", "/FontFile2", "/FontFile3"};
            QPDFObjectHandle fileStream = QPDFObjectHandle::newNull();
            auto findFile = [&](QPDFObjectHandle dict) -> QPDFObjectHandle {
                for (const char* k : fontFileKeys) {
                    if (dict.hasKey(k)) return dict.getKey(k);
                }
                return QPDFObjectHandle::newNull();
            };
            // FontFile(2/3) lives either directly on the font dict, on the
            // DESCENDANT CIDFont of a Type0 font, or inside the
            // /FontDescriptor referenced from either.
            auto followFontDescriptor = [&](QPDFObjectHandle dict) -> QPDFObjectHandle {
                if (dict.isDictionary() && dict.hasKey("/FontDescriptor") &&
                    dict.getKey("/FontDescriptor").isDictionary()) {
                    return findFile(dict.getKey("/FontDescriptor"));
                }
                return QPDFObjectHandle::newNull();
            };
            fileStream = findFile(font);
            if (!fileStream.isStream() && font.hasKey("/DescendantFonts") &&
                font.getKey("/DescendantFonts").isArray() &&
                font.getKey("/DescendantFonts").getArrayNItems() > 0) {
                QPDFObjectHandle desc = font.getKey("/DescendantFonts").getArrayItem(0);
                if (desc.isDictionary()) {
                    fileStream = findFile(desc);
                    if (!fileStream.isStream()) fileStream = followFontDescriptor(desc);
                }
            }
            bool isType0 = (font.hasKey("/Subtype") && font.getKey("/Subtype").isName() &&
                            font.getKey("/Subtype").getName() == "/Type0");
            bool subsetted = false;
            if (fileStream.isStream() && isType0 && fullyAccounted) {
                auto fb = streamData(fileStream);
                if (fb) {
                    std::vector<uint8_t> fontData(fb->getBuffer(), fb->getBuffer() + fb->getSize());
                    std::vector<uint8_t> sub;
                    if (subsetFontWithUnicodes(fontData, survivingUnicodes, sub)) {
                        auto newBuf = std::make_shared<Buffer>(sub.size());
                        memcpy(newBuf->getBuffer(), sub.data(), sub.size());
                        fileStream.replaceStreamData(newBuf, QPDFObjectHandle::newNull(),
                                                     QPDFObjectHandle::newNull());
                        st.fontsSubset++;
                        subsetted = true;
                    }
                }
            }
            if (!subsetted) st.fontsSubsetSkipped++;
#else
            (void)baseFont;
            st.fontsSubsetSkipped++;
#endif
        }

        // 1. Dead-object purge + full rewrite.
        QPDFWriter w(q);
        w.setOutputMemory();
        w.write();
        std::shared_ptr<Buffer> buf = w.getBufferSharedPointer();
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
