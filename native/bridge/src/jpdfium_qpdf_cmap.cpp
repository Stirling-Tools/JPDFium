// jpdfium_qpdf_cmap.cpp - Content stream lexer, CJK predefined CMap lookups,
// font CID usage scanners, and subset-tag disambiguation.
#include "jpdfium_qpdf_internal.h"

#ifdef JPDFIUM_HAS_QPDF

#include <algorithm>
#include <array>
#include <cctype>
#include <cstring>
#include <functional>
#include <map>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <qpdf/QPDFPageObjectHelper.hh>
#include <set>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "gb1_cmap.inc"
#include "japan1_cmap.inc"
#include "korea1_cmap.inc"

namespace jpdfium::qpdf_detail {

struct ContentOperand {
    enum class Kind : uint8_t { Name, String, Array, Other };
    Kind kind = Kind::Other;
    std::string text;
    std::vector<std::string> strings;
};

struct ContentToken {
    bool word = false;
    std::string text;
    ContentOperand operand;
};

// PDF whitespace includes NUL, which std::isspace does not recognize. Using
// std::isspace here would split a token at NUL and can desynchronize the
// content scan that decides whether a CID substitute is safe.
inline bool isPdfWhitespace(unsigned char c) {
    return c == 0x00 || c == 0x09 || c == 0x0A || c == 0x0C || c == 0x0D || c == 0x20;
}

inline bool isPdfDelimiter(unsigned char c) {
    return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' ||
           c == '}' || c == '/' || c == '%';
}

struct ContentLexer {
    std::string_view data;
    size_t pos = 0;
    bool malformed = false;
    int array_depth = 0;

    void skipSpace() {
        while (pos < data.size()) {
            unsigned char c = static_cast<unsigned char>(data[pos]);
            if (isPdfWhitespace(c)) {
                ++pos;
            } else if (c == '%') {
                while (pos < data.size() && data[pos] != '\n' && data[pos] != '\r') ++pos;
            } else {
                break;
            }
        }
    }

    bool literal(std::string& out) {
        if (pos >= data.size() || data[pos] != '(') return false;
        ++pos;
        int depth = 1;
        while (pos < data.size() && depth > 0) {
            char c = data[pos++];
            if (c == '\\') {
                if (pos >= data.size()) break;
                char e = data[pos++];
                switch (e) {
                    case 'n':
                        out += '\n';
                        break;
                    case 'r':
                        out += '\r';
                        break;
                    case 't':
                        out += '\t';
                        break;
                    case 'b':
                        out += '\b';
                        break;
                    case 'f':
                        out += '\f';
                        break;
                    case '\n':
                        break;
                    case '\r':
                        if (pos < data.size() && data[pos] == '\n') ++pos;
                        break;
                    default:
                        if (e >= '0' && e <= '7') {
                            int value = e - '0';
                            for (int i = 0;
                                 i < 2 && pos < data.size() && data[pos] >= '0' && data[pos] <= '7';
                                 ++i) {
                                value = value * 8 + (data[pos++] - '0');
                            }
                            out += static_cast<char>(value & 0xFF);
                        } else {
                            out += e;
                        }
                }
            } else if (c == '(') {
                ++depth;
                out += c;
            } else if (c == ')') {
                --depth;
                if (depth > 0) out += c;
            } else {
                out += c;
            }
        }
        return depth == 0;
    }

    bool hex(std::string& out) {
        if (pos >= data.size() || data[pos] != '<') return false;
        ++pos;
        int first_nibble = -1;
        while (pos < data.size() && data[pos] != '>') {
            unsigned char c = static_cast<unsigned char>(data[pos++]);
            if (isPdfWhitespace(c)) continue;
            int val = -1;
            if (c >= '0' && c <= '9') {
                val = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                val = c - 'a' + 10;
            } else if (c >= 'A' && c <= 'F') {
                val = c - 'A' + 10;
            } else {
                return false;
            }
            if (first_nibble < 0) {
                first_nibble = val;
            } else {
                out += static_cast<char>((first_nibble << 4) | val);
                first_nibble = -1;
            }
        }
        if (pos >= data.size()) return false;
        ++pos;
        if (first_nibble >= 0) {
            out += static_cast<char>(first_nibble << 4);
        }
        return true;
    }

    bool name(std::string& out) {
        if (pos >= data.size() || data[pos] != '/') return false;
        ++pos;
        out += '/';
        auto hex_val = [](char ch) -> int {
            if (ch >= '0' && ch <= '9') return ch - '0';
            if (ch >= 'a' && ch <= 'f') return ch - 'a' + 10;
            if (ch >= 'A' && ch <= 'F') return ch - 'A' + 10;
            return 0;
        };
        while (pos < data.size()) {
            unsigned char c = static_cast<unsigned char>(data[pos]);
            if (isPdfWhitespace(c) || isPdfDelimiter(c)) {
                break;
            }
            if (c == '#' && pos + 2 < data.size() &&
                std::isxdigit(static_cast<unsigned char>(data[pos + 1])) &&
                std::isxdigit(static_cast<unsigned char>(data[pos + 2]))) {
                out += static_cast<char>((hex_val(data[pos + 1]) << 4) | hex_val(data[pos + 2]));
                pos += 3;
            } else {
                out += static_cast<char>(c);
                ++pos;
            }
        }
        return true;
    }

    bool dictionary() {
        if (pos + 1 >= data.size() || data[pos] != '<' || data[pos + 1] != '<') return false;
        pos += 2;
        int depth = 1;
        while (pos < data.size() && depth > 0) {
            if (data[pos] == '%') {
                while (pos < data.size() && data[pos] != '\n' && data[pos] != '\r') ++pos;
            } else if (data[pos] == '(') {
                std::string ignored;
                if (!literal(ignored)) return false;
            } else if (data[pos] == '<' && pos + 1 < data.size() && data[pos + 1] == '<') {
                pos += 2;
                ++depth;
            } else if (data[pos] == '>' && pos + 1 < data.size() && data[pos + 1] == '>') {
                pos += 2;
                --depth;
            } else if (data[pos] == '<') {
                std::string ignored;
                if (!hex(ignored)) return false;
            } else {
                ++pos;
            }
        }
        return depth == 0;
    }

    bool array(ContentOperand& out) {
        if (pos >= data.size() || data[pos] != '[') return false;
        ++pos;
        if (++array_depth > 32) {
            malformed = true;
            --array_depth;
            return false;
        }
        out.kind = ContentOperand::Kind::Array;
        while (pos < data.size()) {
            skipSpace();
            if (pos >= data.size()) {
                --array_depth;
                return false;
            }
            if (data[pos] == ']') {
                ++pos;
                --array_depth;
                return true;
            }
            ContentToken token;
            if (!next(token)) {
                --array_depth;
                return false;
            }
            if (!token.word) {
                if (token.operand.kind == ContentOperand::Kind::String) {
                    out.strings.push_back(token.operand.text);
                } else if (token.operand.kind == ContentOperand::Kind::Array) {
                    out.strings.insert(out.strings.end(), token.operand.strings.begin(),
                                       token.operand.strings.end());
                }
            }
        }
        --array_depth;
        return false;
    }

    bool next(ContentToken& out) {
        skipSpace();
        if (pos >= data.size()) return false;
        out = ContentToken{};
        char c = data[pos];
        if (c == '(') {
            out.operand.kind = ContentOperand::Kind::String;
            if (!literal(out.operand.text)) {
                malformed = true;
                return false;
            }
            return true;
        }
        if (c == '<') {
            if (pos + 1 < data.size() && data[pos + 1] == '<') {
                out.operand.kind = ContentOperand::Kind::Other;
                if (!dictionary()) {
                    malformed = true;
                    return false;
                }
                return true;
            }
            out.operand.kind = ContentOperand::Kind::String;
            if (!hex(out.operand.text)) {
                malformed = true;
                return false;
            }
            return true;
        }
        if (c == '[') {
            if (!array(out.operand)) {
                malformed = true;
                return false;
            }
            return true;
        }
        if (c == ']' || c == '>' || c == ')' || c == '}' || c == '{') {
            ++pos;
            out.operand.kind = ContentOperand::Kind::Other;
            return true;
        }
        if (c == '/') {
            out.operand.kind = ContentOperand::Kind::Name;
            return name(out.operand.text);
        }
        size_t start = pos;
        while (pos < data.size()) {
            unsigned char ch = static_cast<unsigned char>(data[pos]);
            if (isPdfWhitespace(ch) || isPdfDelimiter(ch)) {
                break;
            }
            ++pos;
        }
        if (pos == start) return false;
        std::string value(data.substr(start, pos - start));
        bool numeric = value.find_first_not_of("0123456789+-.") == std::string::npos;
        if (numeric) {
            out.word = false;
            out.operand.kind = ContentOperand::Kind::Other;
            out.operand.text = std::move(value);
        } else {
            out.word = true;
            out.text = std::move(value);
        }
        return true;
    }

    void skipInlineImage() {
        // ISO 32000-1 7.2.2: EI is a token, so it must be separated from the
        // image bytes by whitespace and from the next token by whitespace or a
        // delimiter. Requiring both boundaries avoids treating a binary "EI"
        // sequence inside the image data as the end marker. A stream that
        // violates the delimiter rule is reported malformed so callers fail
        // closed instead of trusting a partial scan.
        ContentToken token;
        bool wantLen = false;
        int64_t declaredLen = -1;
        while (next(token)) {
            if (!token.word && token.operand.kind == ContentOperand::Kind::Name &&
                (token.operand.text == "/L" || token.operand.text == "/Length")) {
                wantLen = true;
                continue;
            }
            if (wantLen && !token.word && token.operand.kind == ContentOperand::Kind::Other) {
                char* endPtr = nullptr;
                long long parsed = std::strtoll(token.operand.text.c_str(), &endPtr, 10);
                if (endPtr && *endPtr == '\0' && parsed >= 0) {
                    declaredLen = parsed;
                }
                wantLen = false;
                continue;
            }
            wantLen = false;
            if (token.word && token.text == "ID") {
                if (pos < data.size() && isPdfWhitespace(static_cast<unsigned char>(data[pos])))
                    ++pos;
                if (declaredLen >= 0 && static_cast<uint64_t>(declaredLen) <= data.size() - pos) {
                    size_t probe = pos + static_cast<size_t>(declaredLen);
                    while (probe < data.size() &&
                           isPdfWhitespace(static_cast<unsigned char>(data[probe]))) {
                        ++probe;
                    }
                    if (probe + 1 < data.size() && data[probe] == 'E' && data[probe + 1] == 'I') {
                        const size_t after = probe + 2;
                        if (after >= data.size() ||
                            isPdfWhitespace(static_cast<unsigned char>(data[after])) ||
                            isPdfDelimiter(static_cast<unsigned char>(data[after]))) {
                            pos = after;
                            return;
                        }
                    }
                }
                while (pos + 1 < data.size()) {
                    if (data[pos] == 'E' && data[pos + 1] == 'I' &&
                        (pos == 0 || isPdfWhitespace(static_cast<unsigned char>(data[pos - 1])))) {
                        const size_t after = pos + 2;
                        if (after >= data.size() ||
                            isPdfWhitespace(static_cast<unsigned char>(data[after])) ||
                            isPdfDelimiter(static_cast<unsigned char>(data[after]))) {
                            pos = after;
                            return;
                        }
                    }
                    ++pos;
                }
                malformed = true;
                return;
            }
        }
        malformed = true;
    }
};

bool sameFont(QPDFObjectHandle const& a, QPDFObjectHandle const& b) {
    if (!a.isDictionary() || !b.isDictionary()) return false;
    if (a.isIndirect() && b.isIndirect()) return a.getObjGen() == b.getObjGen();
    try {
        return a.unparse() == b.unparse();
    } catch (...) {
        return false;
    }
}

bool isRksjCidEncoding(CidEncoding enc) {
    return enc == CidEncoding::Rksj || enc == CidEncoding::RksjProp ||
           enc == CidEncoding::RksjVert || enc == CidEncoding::RksjPropVert;
}

bool isRksjPropCidEncoding(CidEncoding enc) {
    return enc == CidEncoding::RksjProp || enc == CidEncoding::RksjPropVert;
}

bool isKscEucCidEncoding(CidEncoding enc) {
    return enc == CidEncoding::KscEuc || enc == CidEncoding::KscEucVert;
}

bool isGbEucCidEncoding(CidEncoding enc) {
    return enc == CidEncoding::GbEuc || enc == CidEncoding::GbEucVert;
}

bool isVerticalCidEncoding(CidEncoding enc) {
    return enc == CidEncoding::RksjVert || enc == CidEncoding::RksjPropVert ||
           enc == CidEncoding::KscEucVert || enc == CidEncoding::GbEucVert;
}

CidEncoding cidEncodingFromName(QPDFObjectHandle const& encoding) {
    if (!encoding.isName()) return CidEncoding::TwoByte;
    std::string name = encoding.getName();
    if (name == "/90ms-RKSJ-H") return CidEncoding::Rksj;
    if (name == "/90msp-RKSJ-H") return CidEncoding::RksjProp;
    if (name == "/90ms-RKSJ-V") return CidEncoding::RksjVert;
    if (name == "/90msp-RKSJ-V") return CidEncoding::RksjPropVert;
    if (name == "/KSC-EUC-H") return CidEncoding::KscEuc;
    if (name == "/KSC-EUC-V") return CidEncoding::KscEucVert;
    if (name == "/GB-EUC-H") return CidEncoding::GbEuc;
    if (name == "/GB-EUC-V") return CidEncoding::GbEucVert;
    return CidEncoding::TwoByte;
}

namespace {

template <typename Range, size_t N>
bool lookupCodeRange(Range const (&ranges)[N], uint16_t code, uint32_t& out) {
    auto it = std::lower_bound(std::begin(ranges), std::end(ranges), code,
                               [](Range const& r, uint16_t v) { return r.hi < v; });
    if (it == std::end(ranges) || code < it->lo) return false;
    out = it->cid + (code - it->lo);
    return true;
}

template <typename Range, size_t N>
bool lookupCachedRange(Range const (&ranges)[N], uint16_t key, Range const*& cache,
                       uint16_t Range::* lo, uint16_t Range::* hi, uint32_t Range::* val,
                       uint32_t& out) {
    // Sequential characters in a CJK text run almost always land in the same range.
    if (cache && key >= (*cache).*lo && key <= (*cache).*hi) {
        out = (*cache).*val + (key - (*cache).*lo);
        return true;
    }
    auto it = std::lower_bound(std::begin(ranges), std::end(ranges), key,
                               [hi](Range const& r, uint16_t v) { return r.*hi < v; });
    if (it == std::end(ranges) || key < (*it).*lo) return false;
    cache = &*it;
    out = (*it).*val + (key - (*it).*lo);
    return true;
}

}  // namespace

bool japan1CidForCode(uint16_t code, uint32_t& cid, bool proportional, bool vertical) {
    if (vertical && lookupCodeRange(kJapan1VertCodeRanges, code, cid)) return true;
    if (proportional && code >= 0x0020 && code <= 0x007E) {
        cid = 1 + (code - 0x0020);
        return true;
    }
    if (code >= 0x0020 && code <= 0x007D) {
        cid = 231 + (code - 0x0020);
        return true;
    }
    if (code < 0x0020) return false;
    static thread_local Japan1CodeRange const* last = nullptr;
    return lookupCachedRange(kJapan1CodeRanges, code, last, &Japan1CodeRange::lo,
                             &Japan1CodeRange::hi, &Japan1CodeRange::cid, cid);
}

bool japan1UnicodeForCid(uint32_t cid, uint32_t& unicode) {
    if (cid > 0xFFFF) return false;
    static thread_local Japan1UnicodeRange const* last = nullptr;
    return lookupCachedRange(kJapan1UnicodeRanges, static_cast<uint16_t>(cid), last,
                             &Japan1UnicodeRange::cidLo, &Japan1UnicodeRange::cidHi,
                             &Japan1UnicodeRange::unicode, unicode);
}

bool korea1CidForCode(uint16_t code, uint32_t& cid, bool vertical) {
    if (vertical && lookupCodeRange(kKorea1VertCodeRanges, code, cid)) return true;
    if (code >= 0x0020 && code <= 0x007E) {
        cid = 8094 + (code - 0x0020);
        return true;
    }
    if (code < 0x0020) return false;
    static thread_local Korea1CodeRange const* last = nullptr;
    return lookupCachedRange(kKorea1CodeRanges, code, last, &Korea1CodeRange::lo,
                             &Korea1CodeRange::hi, &Korea1CodeRange::cid, cid);
}

bool korea1UnicodeForCid(uint32_t cid, uint32_t& unicode) {
    if (cid > 0xFFFF) return false;
    static thread_local Korea1UnicodeRange const* last = nullptr;
    return lookupCachedRange(kKorea1UnicodeRanges, static_cast<uint16_t>(cid), last,
                             &Korea1UnicodeRange::cidLo, &Korea1UnicodeRange::cidHi,
                             &Korea1UnicodeRange::unicode, unicode);
}

bool gb1CidForCode(uint16_t code, uint32_t& cid, bool vertical) {
    if (vertical && lookupCodeRange(kGb1VertCodeRanges, code, cid)) return true;
    if (code == 0x0020) {
        cid = 7716;
        return true;
    }
    if (code >= 0x0021 && code <= 0x007E) {
        cid = 814 + (code - 0x0021);
        return true;
    }
    if (code < 0x0020) return false;
    static thread_local Gb1CodeRange const* last = nullptr;
    return lookupCachedRange(kGb1CodeRanges, code, last, &Gb1CodeRange::lo, &Gb1CodeRange::hi,
                             &Gb1CodeRange::cid, cid);
}

bool gb1UnicodeForCid(uint32_t cid, uint32_t& unicode) {
    if (cid > 0xFFFF) return false;
    static thread_local Gb1UnicodeRange const* last = nullptr;
    return lookupCachedRange(kGb1UnicodeRanges, static_cast<uint16_t>(cid), last,
                             &Gb1UnicodeRange::cidLo, &Gb1UnicodeRange::cidHi,
                             &Gb1UnicodeRange::unicode, unicode);
}

bool adobeCollectionUnicodeForCid(std::string_view ordering, uint32_t cid, uint32_t& unicode) {
    if (ordering == "Japan1") return japan1UnicodeForCid(cid, unicode);
    if (ordering == "Korea1") return korea1UnicodeForCid(cid, unicode);
    if (ordering == "GB1") return gb1UnicodeForCid(cid, unicode);
    return false;
}

void collectStringCids(std::string_view bytes, std::set<uint32_t>& cids, std::set<uint32_t>* codes,
                       bool& complete, CidEncoding encoding) {
    if (encoding == CidEncoding::SingleByte) {
        for (unsigned char ch : bytes) {
            cids.insert(static_cast<uint32_t>(ch));
            if (codes) codes->insert(static_cast<uint32_t>(ch));
        }
        return;
    }
    if (isRksjCidEncoding(encoding)) {
        bool proportional = isRksjPropCidEncoding(encoding);
        bool vertical = isVerticalCidEncoding(encoding);
        for (size_t i = 0; i < bytes.size();) {
            unsigned char lead = static_cast<unsigned char>(bytes[i]);
            uint16_t code = lead;
            if ((lead >= 0x81 && lead <= 0x9F) || (lead >= 0xE0 && lead <= 0xFC)) {
                if (i + 1 >= bytes.size()) {
                    complete = false;
                    return;
                }
                code =
                    static_cast<uint16_t>((lead << 8) | static_cast<unsigned char>(bytes[i + 1]));
                i += 2;
            } else {
                ++i;
            }
            uint32_t cid = 0;
            if (!japan1CidForCode(code, cid, proportional, vertical)) {
                complete = false;
                return;
            }
            if (codes) codes->insert(code);
            if (cid) cids.insert(cid);
        }
        return;
    }
    if (isKscEucCidEncoding(encoding) || isGbEucCidEncoding(encoding)) {
        bool ksc = isKscEucCidEncoding(encoding);
        bool vertical = isVerticalCidEncoding(encoding);
        for (size_t i = 0; i < bytes.size();) {
            unsigned char lead = static_cast<unsigned char>(bytes[i]);
            uint16_t code = lead;
            if (lead >= 0x80) {
                if (i + 1 >= bytes.size()) {
                    complete = false;
                    return;
                }
                code =
                    static_cast<uint16_t>((lead << 8) | static_cast<unsigned char>(bytes[i + 1]));
                i += 2;
            } else {
                ++i;
            }
            uint32_t cid = 0;
            bool ok =
                ksc ? korea1CidForCode(code, cid, vertical) : gb1CidForCode(code, cid, vertical);
            if (!ok) {
                complete = false;
                return;
            }
            if (codes) codes->insert(code);
            if (cid) cids.insert(cid);
        }
        return;
    }
    if ((bytes.size() % 2) != 0) complete = false;
    for (size_t i = 0; i + 1 < bytes.size(); i += 2) {
        uint32_t cid = (static_cast<uint32_t>(static_cast<unsigned char>(bytes[i])) << 8) |
                       static_cast<unsigned char>(bytes[i + 1]);
        if (codes) codes->insert(cid);
        if (cid) cids.insert(cid);
    }
}

std::string FontUsageIndex::objectKey(QPDFObjectHandle const& object) {
    if (!object.isInitialized()) return {};
    if (object.isIndirect()) {
        auto id = object.getObjGen();
        return std::to_string(id.getObj()) + ":" + std::to_string(id.getGen());
    }
    try {
        return object.unparse();
    } catch (...) {
        return {};
    }
}

bool FontUsageIndex::isCompositeFont(QPDFObjectHandle const& font) {
    return font.isDictionary() && font.getKey("/Subtype").isNameAndEquals("/Type0");
}

CidScanResult* FontUsageIndex::slot(QPDFObjectHandle const& font) {
    if (!font.isDictionary()) return nullptr;
    return font.isIndirect() ? &byFont[font.getObjGen()] : &byDirect[objectKey(font)];
}

void FontUsageIndex::collectString(QPDFObjectHandle const& font, std::string_view bytes, bool& ok,
                                   CidEncoding encoding) {
    CidScanResult* usage = slot(font);
    if (!usage) return;
    bool localOk = true;
    collectStringCids(bytes, usage->cids, nullptr, localOk, encoding);
    usage->complete = usage->complete && localOk;
    complete = complete && localOk;
    ok = localOk;
}

CidScanResult const* FontUsageIndex::find(QPDFObjectHandle const& font) const {
    if (!font.isDictionary()) return nullptr;
    if (font.isIndirect()) {
        auto it = byFont.find(font.getObjGen());
        return it == byFont.end() ? nullptr : &it->second;
    }
    auto it = byDirect.find(objectKey(font));
    return it == byDirect.end() ? nullptr : &it->second;
}

namespace {

struct StreamVisitKey {
    QPDFObjGen fontGen;
    QPDFObjGen streamGen;
    bool fontIsDirect = false;
    std::string directFontKey;
    bool operator<(StreamVisitKey const& o) const {
        if (streamGen != o.streamGen) return streamGen < o.streamGen;
        if (fontIsDirect != o.fontIsDirect) return fontIsDirect < o.fontIsDirect;
        if (fontIsDirect) return directFontKey < o.directFontKey;
        return fontGen < o.fontGen;
    }
};

struct FontScanState {
    QPDFObjectHandle currentFont;
    bool selected = false;
};

struct FontScanContext {
    bool allFonts = true;
    QPDFObjectHandle target;
    CidEncoding singleEncoding = CidEncoding::TwoByte;
    CidScanResult* singleResult = nullptr;
    FontUsageIndex* cidIndex = nullptr;
    FontUsageIndex* simpleIndex = nullptr;
    int budget = 2048;
    bool complete = true;
    std::set<StreamVisitKey> seenStreams;

    void collectText(FontScanState const& state, std::string_view text) {
        if (!allFonts) {
            if (state.selected) {
                collectStringCids(text, singleResult->cids, &singleResult->codes, complete,
                                  singleEncoding);
            }
            return;
        }
        bool composite = FontUsageIndex::isCompositeFont(state.currentFont);
        FontUsageIndex& index = composite ? *cidIndex : *simpleIndex;
        bool ok = true;
        index.collectString(state.currentFont, text, ok,
                            composite ? CidEncoding::TwoByte : CidEncoding::SingleByte);
        complete = complete && ok;
    }

    bool tryEnterStream(FontScanState const& state, QPDFObjectHandle const& stream,
                        StreamVisitKey& key, bool& tracked) {
        tracked = false;
        if (!allFonts) {
            if (!stream.isIndirect()) return true;
            key.streamGen = stream.getObjGen();
            key.fontIsDirect = true;
            key.directFontKey = state.selected ? "1" : "0";
            tracked = true;
            return seenStreams.insert(key).second;
        }
        if (stream.isIndirect()) {
            key.streamGen = stream.getObjGen();
        } else if (FontUsageIndex::objectKey(stream).empty()) {
            complete = false;
            return false;
        }
        if (state.currentFont.isIndirect()) {
            key.fontGen = state.currentFont.getObjGen();
        } else {
            key.fontIsDirect = true;
            key.directFontKey = FontUsageIndex::objectKey(state.currentFont);
        }
        tracked = true;
        return seenStreams.insert(key).second;
    }
};

void scanResourceStreamsImpl(QPDFObjectHandle const& resources, FontScanContext& ctx, int depth);

void applyFontSelection(QPDFObjectHandle const& font, FontScanContext const& ctx,
                        FontScanState& state) {
    if (ctx.allFonts) {
        if (font.isDictionary()) state.currentFont = font;
    } else {
        state.selected = sameFont(font, ctx.target);
    }
}

void scanContentStreamImpl(std::string_view data, QPDFObjectHandle const& resources,
                           FontScanState initialState, FontScanContext& ctx, int depth) {
    // Cap Form XObject depth and total visited streams so cyclic or huge trees fail closed.
    if (depth > 16 || ctx.budget-- <= 0) {
        ctx.complete = false;
        return;
    }
    ContentLexer lexer{data};
    std::vector<ContentOperand> operands;
    std::vector<FontScanState> stack;
    operands.reserve(64);
    stack.reserve(16);
    QPDFObjectHandle fonts;
    QPDFObjectHandle extgstate;
    QPDFObjectHandle xobjects;
    if (resources.isDictionary()) {
        fonts = resources.getKey("/Font");
        extgstate = resources.getKey("/ExtGState");
        xobjects = resources.getKey("/XObject");
    }
    FontScanState state = std::move(initialState);
    ContentToken token;
    while (lexer.next(token)) {
        if (!token.word) {
            operands.push_back(std::move(token.operand));
            // Bound operand stack across malformed streams with missing operators.
            if (operands.size() > 64) operands.erase(operands.begin(), operands.begin() + 32);
            continue;
        }
        std::string const& op = token.text;
        if (op == "q") {
            stack.push_back(state);
        } else if (op == "Q") {
            if (!stack.empty()) {
                state = std::move(stack.back());
                stack.pop_back();
            }
        } else if (op == "Tf" && operands.size() >= 2) {
            auto const& fontName = operands[operands.size() - 2];
            if (!ctx.allFonts) state.selected = false;
            if (fontName.kind == ContentOperand::Kind::Name && fonts.isDictionary()) {
                applyFontSelection(fonts.getKey(fontName.text), ctx, state);
            }
        } else if (op == "gs" && !operands.empty() &&
                   operands.back().kind == ContentOperand::Kind::Name) {
            // /ExtGState /Font can switch active text font without an explicit Tf operator.
            if (extgstate.isDictionary()) {
                auto gsDict = extgstate.getKey(operands.back().text);
                if (gsDict.isDictionary() && gsDict.hasKey("/Font")) {
                    auto fontEntry = gsDict.getKey("/Font");
                    if (fontEntry.isArray() && fontEntry.getArrayNItems() > 0) {
                        applyFontSelection(fontEntry.getArrayItem(0), ctx, state);
                    } else {
                        ctx.complete = false;
                    }
                }
            }
        } else if ((op == "Tj" || op == "'" || op == "\"") && !operands.empty() &&
                   operands.back().kind == ContentOperand::Kind::String) {
            ctx.collectText(state, operands.back().text);
        } else if (op == "TJ" && !operands.empty() &&
                   operands.back().kind == ContentOperand::Kind::Array) {
            for (auto const& text : operands.back().strings) ctx.collectText(state, text);
        } else if (op == "Do" && !operands.empty() &&
                   operands.back().kind == ContentOperand::Kind::Name && xobjects.isDictionary()) {
            auto form = xobjects.getKey(operands.back().text);
            if (form.isStream() && form.getDict().getKey("/Subtype").isNameAndEquals("/Form")) {
                StreamVisitKey visitKey;
                bool tracked = false;
                if (ctx.tryEnterStream(state, form, visitKey, tracked)) {
                    // Inherit caller resources when Form XObject omits its own /Resources.
                    auto formResources = form.getDict().getKey("/Resources");
                    if (!formResources.isDictionary()) formResources = resources;
                    try {
                        auto formData = form.getStreamData(qpdf_dl_generalized);
                        if (formData && formData->getSize()) {
                            std::string_view content(
                                reinterpret_cast<char const*>(formData->getBuffer()),
                                formData->getSize());
                            scanContentStreamImpl(content, formResources, state, ctx, depth + 1);
                            scanResourceStreamsImpl(formResources, ctx, depth + 1);
                        }
                    } catch (...) {
                        ctx.complete = false;
                    }
                    if (tracked) ctx.seenStreams.erase(visitKey);
                }
            }
        } else if (op == "BI") {
            lexer.skipInlineImage();
        }
        operands.clear();
    }
    // Unconsumed or malformed content means we cannot prove which glyph codes are unused.
    lexer.skipSpace();
    if (lexer.malformed || lexer.pos < lexer.data.size()) ctx.complete = false;
}

void scanStreamWithResources(QPDFObjectHandle stream, QPDFObjectHandle const& fallbackResources,
                             FontScanContext& ctx, int depth) {
    if (!stream.isStream() || ctx.budget <= 0) {
        if (ctx.budget <= 0) ctx.complete = false;
        return;
    }
    FontScanState emptyState;
    StreamVisitKey visitKey;
    bool tracked = false;
    if (!ctx.tryEnterStream(emptyState, stream, visitKey, tracked)) return;
    auto streamResources = stream.getDict().getKey("/Resources");
    if (!streamResources.isDictionary()) streamResources = fallbackResources;
    try {
        auto data = stream.getStreamData(qpdf_dl_generalized);
        if (data && data->getSize()) {
            std::string_view content(reinterpret_cast<char const*>(data->getBuffer()),
                                     data->getSize());
            scanContentStreamImpl(content, streamResources, emptyState, ctx, depth);
            scanResourceStreamsImpl(streamResources, ctx, depth);
        }
    } catch (...) {
        ctx.complete = false;
    }
    if (tracked) ctx.seenStreams.erase(visitKey);
}

void scanResourceStreamsImpl(QPDFObjectHandle const& resources, FontScanContext& ctx, int depth) {
    if (depth > 16 || ctx.budget <= 0 || !resources.isDictionary()) {
        if (ctx.budget <= 0) ctx.complete = false;
        return;
    }
    auto patterns = resources.getKey("/Pattern");
    if (patterns.isDictionary()) {
        for (auto const& entry : patterns.getDictAsMap()) {
            scanStreamWithResources(entry.second, resources, ctx, depth + 1);
        }
    }

    auto extGStates = resources.getKey("/ExtGState");
    if (extGStates.isDictionary()) {
        for (auto const& entry : extGStates.getDictAsMap()) {
            auto gs = entry.second;
            if (!gs.isDictionary()) continue;
            auto smask = gs.getKey("/SMask");
            if (!smask.isDictionary()) continue;
            auto g = smask.getKey("/G");
            if (!g.isStream()) continue;
            auto gResources = g.getDict().getKey("/Resources");
            if (!gResources.isDictionary()) gResources = resources;
            scanStreamWithResources(g, gResources, ctx, depth + 1);
        }
    }

    auto fonts = resources.getKey("/Font");
    if (fonts.isDictionary()) {
        for (auto const& entry : fonts.getDictAsMap()) {
            auto font = entry.second;
            if (!font.isDictionary() || !font.getKey("/Subtype").isNameAndEquals("/Type3")) {
                continue;
            }
            auto charProcs = font.getKey("/CharProcs");
            if (!charProcs.isDictionary()) continue;
            auto fontResources = font.getKey("/Resources");
            if (!fontResources.isDictionary()) fontResources = resources;
            for (auto const& glyph : charProcs.getDictAsMap()) {
                scanStreamWithResources(glyph.second, fontResources, ctx, depth + 1);
            }
        }
    }
}

void scanDocumentStreams(QPDF& qpdf, FontScanContext& ctx) {
    try {
        for (auto& page : QPDFPageDocumentHelper{qpdf}.getAllPages()) {
            auto pageObj = page.getObjectHandle();
            auto resources = pageObj.getKey("/Resources");
            if (!resources.isDictionary()) resources = page.getAttribute("/Resources", false);
            auto contents = pageObj.getKey("/Contents");
            std::vector<QPDFObjectHandle> streams;
            if (contents.isArray()) {
                for (auto& item : contents.getArrayAsVector()) streams.push_back(item);
            } else if (contents.isStream()) {
                streams.push_back(contents);
            }

            constexpr size_t kMaxPageContent = size_t{64} * 1024u * 1024u;
            if (streams.size() == 1 && streams[0].isStream()) {
                try {
                    auto data = streams[0].getStreamData(qpdf_dl_generalized);
                    if (data && data->getSize()) {
                        std::string_view content(reinterpret_cast<char const*>(data->getBuffer()),
                                                 data->getSize());
                        scanContentStreamImpl(content, resources, FontScanState{}, ctx, 0);
                    }
                } catch (...) {
                    ctx.complete = false;
                }
            } else {
                std::string pageContent;
                size_t bytes = 0;
                for (auto& stream : streams) {
                    if (!stream.isStream()) {
                        ctx.complete = false;
                        break;
                    }
                    try {
                        auto data = stream.getStreamData(qpdf_dl_generalized);
                        if (data && data->getSize()) {
                            size_t separator = pageContent.empty() ? 0 : 1;
                            if (bytes > kMaxPageContent || separator > kMaxPageContent - bytes ||
                                data->getSize() > kMaxPageContent - bytes - separator) {
                                ctx.complete = false;
                                break;
                            }
                            if (separator) pageContent.push_back('\n');
                            pageContent.append(reinterpret_cast<char const*>(data->getBuffer()),
                                               data->getSize());
                            bytes += data->getSize() + separator;
                        }
                    } catch (...) {
                        ctx.complete = false;
                        break;
                    }
                }
                if (!pageContent.empty()) {
                    scanContentStreamImpl(pageContent, resources, FontScanState{}, ctx, 0);
                }
            }

            auto annots = pageObj.getKey("/Annots");
            if (annots.isArray()) {
                for (auto& annot : annots.getArrayAsVector()) {
                    if (!annot.isDictionary()) continue;
                    auto ap = annot.getKey("/AP");
                    if (!ap.isDictionary()) continue;
                    for (char const* key : {"/N", "/R", "/D"}) {
                        auto appearance = ap.getKey(key);
                        if (appearance.isStream()) {
                            scanStreamWithResources(appearance, resources, ctx, 0);
                        } else if (appearance.isDictionary()) {
                            for (auto const& entry : appearance.getDictAsMap()) {
                                scanStreamWithResources(entry.second, resources, ctx, 0);
                            }
                        }
                    }
                }
            }

            if (resources.isDictionary()) scanResourceStreamsImpl(resources, ctx, 0);
        }
    } catch (...) {
        ctx.complete = false;
    }
}

}  // namespace

FontUsageIndexes buildFontUsageIndexes(QPDF& qpdf) {
    FontUsageIndexes indexes;
    FontScanContext ctx;
    ctx.allFonts = true;
    ctx.cidIndex = &indexes.cid;
    ctx.simpleIndex = &indexes.simple;
    scanDocumentStreams(qpdf, ctx);
    if (!ctx.complete) {
        indexes.cid.complete = false;
        indexes.simple.complete = false;
    }
    return indexes;
}

CidScanResult usedCidsForFont(QPDF& qpdf, QPDFObjectHandle const& target, CidEncoding encoding,
                              FontUsageIndexes const* prebuilt) {
    FontUsageIndex const* index =
        prebuilt ? (encoding == CidEncoding::SingleByte ? &prebuilt->simple : &prebuilt->cid)
                 : nullptr;
    if (index && index->complete) {
        if (auto const* found = index->find(target)) return *found;
        if (target.isIndirect()) return {};
    }
    CidScanResult result;
    FontScanContext ctx;
    ctx.allFonts = false;
    ctx.target = target;
    ctx.singleEncoding = encoding;
    ctx.singleResult = &result;
    scanDocumentStreams(qpdf, ctx);
    result.complete = ctx.complete;
    return result;
}

}  // namespace jpdfium::qpdf_detail

#endif  // JPDFIUM_HAS_QPDF
