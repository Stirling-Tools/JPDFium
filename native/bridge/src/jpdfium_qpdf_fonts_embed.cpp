// jpdfium_qpdf_fonts_embed.cpp - Font program loading, full/subset font embedding,
// simple encoding pinning, ToUnicode/widths synthesis, and stream replacement.
#include "jpdfium_qpdf_internal.h"
#include "jpdfium_qpdf_writer_lock.h"

#if defined(JPDFIUM_HAS_QPDF) && defined(JPDFIUM_HAS_FREETYPE) && defined(JPDFIUM_HAS_HARFBUZZ)

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <functional>
#include <limits>
#include <map>
#include <memory>
#include <set>
#include <span>
#include <string>
#include <tuple>
#include <vector>

namespace jpdfium::qpdf_detail {

using jpdfium_font_encoding::adobeGlyphNameForUnicode;
using jpdfium_font_encoding::BaseEncoding;
using jpdfium_font_encoding::baseEncodingUnicode;
using jpdfium_font_encoding::unicodeForGlyphName;
using jpdfium_qpdf_internal::WriterLock;

namespace {

struct FontSource {
    std::string name;
    std::span<const uint8_t> data;
};

struct EmbedFontsResult {
    std::shared_ptr<Buffer> buffer;
    std::string error;
    int32_t rc = 0;
};

bool splitType1(std::span<const uint8_t> in, FontProgram& out) {
    std::string clear, binary, tail;
    if (in.size() > 6 && in[0] == 0x80) {
        for (size_t i = 0; i + 6 <= in.size() && in[i] == 0x80 && in[i + 1] != 3;) {
            size_t len = in[i + 2] | in[i + 3] << 8 | in[i + 4] << 16 | size_t(in[i + 5]) << 24;
            if (i + 6 + len > in.size()) return false;
            (in[i + 1] == 2 ? binary : (binary.empty() ? clear : tail))
                .append(reinterpret_cast<const char*>(in.data()) + i + 6, len);
            i += 6 + len;
        }
    } else {
        std::string_view s(reinterpret_cast<const char*>(in.data()), in.size());
        size_t eexec = s.find("eexec"), mark = s.rfind("cleartomark");
        if (eexec == s.npos) return false;
        size_t start = s.find_first_not_of(" \t\r\n", eexec + 5);
        if (start == s.npos) return false;
        size_t end = mark == s.npos || mark < start ? s.size() : mark;
        while (end < s.size() && end > start &&
               (s[end - 1] == '0' || std::isspace(uint8_t(s[end - 1])))) {
            --end;
        }
        clear = s.substr(0, start);
        tail = s.substr(end);
        auto body = s.substr(start, end - start);
        if (body.size() >= 4 && std::all_of(body.begin(), body.begin() + 4,
                                            [](char c) { return std::isxdigit(uint8_t(c)); })) {
            int hi = -1;
            for (char c : body) {
                if (!std::isxdigit(uint8_t(c))) continue;
                int v = std::isdigit(uint8_t(c)) ? c - '0' : (std::tolower(c) - 'a' + 10);
                if (hi < 0) {
                    hi = v;
                } else {
                    binary.push_back(char(hi << 4 | v));
                    hi = -1;
                }
            }
        } else {
            binary = body;
        }
    }
    if (clear.empty() || binary.empty()) return false;
    out.bytes = clear + binary + tail;
    out.length1 = static_cast<long long>(clear.size());
    out.length2 = static_cast<long long>(binary.size());
    out.length3 = static_cast<long long>(tail.size());
    return true;
}

QPDFObjectHandle buildWidths(QPDFObjectHandle const& font, QPDFObjectHandle const& descriptor,
                             FT_Face face, bool symbolic) {
    auto enc = parseSimpleEncoding(font, descriptor);
    auto widths = QPDFObjectHandle::newArray();
    bool anyPositive = false;
    for (int code = 0; code < 256; ++code) {
        const FT_UInt gid = jpdfium::font::glyphForSimpleCode(face, code, symbolic, enc);
        long w = 0;
        if (gid != 0 && face->units_per_EM != 0) {
            FT_Fixed advance = 0;
            FT_Get_Advance(face, gid, FT_LOAD_NO_SCALE, &advance);
            w = advance * 1000 / face->units_per_EM;
        }
        if (w > 0) anyPositive = true;
        widths.appendItem(QPDFObjectHandle::newInteger(w));
    }
    return anyPositive ? widths : QPDFObjectHandle();
}

void applyWidths(QPDFObjectHandle& font, QPDFObjectHandle const& widths) {
    font.replaceKey("/FirstChar", QPDFObjectHandle::newInteger(0));
    font.replaceKey("/LastChar", QPDFObjectHandle::newInteger(255));
    font.replaceKey("/Widths", widths);
}

bool hasInheritedToUnicode(QPDFObjectHandle const& font);

void setToUnicode(QPDF& qpdf, QPDFObjectHandle font, std::map<uint32_t, FT_ULong> const& codes,
                  bool twoByte) {
    if (codes.empty()) return;
    if (font.hasKey("/ToUnicode") && (hasInheritedToUnicode(font) || !parseToUnicode(font).empty()))
        return;
    std::map<uint32_t, uint32_t> clean;
    for (auto const& [code, u] : codes) {
        if (u > 0 && u <= 0x10FFFFU) clean.emplace(code, static_cast<uint32_t>(u));
    }
    std::string cmap = jpdfium::font::buildToUnicodeCmap(clean, twoByte);
    if (!cmap.empty()) font.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(&qpdf, cmap));
}

void setCjkToUnicode(QPDF& qpdf, QPDFObjectHandle font, std::map<uint32_t, FT_ULong> const& codes,
                     bool rksj) {
    if (codes.empty()) return;
    if (font.hasKey("/ToUnicode") && (hasInheritedToUnicode(font) || !parseToUnicode(font).empty()))
        return;
    std::map<uint32_t, uint32_t> clean;
    for (auto const& [code, u] : codes) {
        if (code <= 0xFFFFU && u > 0 && u <= 0x10FFFFU)
            clean.emplace(code, static_cast<uint32_t>(u));
    }
    std::string cmap = jpdfium::font::buildToUnicodeCmap(clean, true, rksj, !rksj);
    if (!cmap.empty()) font.replaceKey("/ToUnicode", QPDFObjectHandle::newStream(&qpdf, cmap));
}

bool coversWidthCids(QPDFObjectHandle const& font, std::map<uint32_t, uint32_t> const& unicodeByCid,
                     std::set<uint32_t> const& usedCids, bool scanComplete) {
    if (!scanComplete) return false;
    if (!usedCids.empty()) {
        for (uint32_t cid : usedCids) {
            if (cid && !unicodeByCid.contains(cid)) return false;
        }
        return true;
    }
    for (uint32_t cid : widthCids(font)) {
        if (cid && !unicodeByCid.contains(cid)) return false;
    }
    return true;
}

bool cidGlyphOrderMatches(FT_Face face, std::map<uint32_t, uint32_t> const& unicodeByCid) {
    if (unicodeByCid.empty() || FT_Select_Charmap(face, FT_ENCODING_UNICODE)) return false;
    for (auto const& [cid, unicode] : unicodeByCid) {
        uint32_t scalar = jpdfium::font::decodeUnicodeScalar(unicode);
        if (!scalar || FT_Get_Char_Index(face, scalar) != cid) return false;
    }
    return true;
}

bool mapCidsToGlyphs(FT_Face face, std::map<uint32_t, uint32_t> const& unicodeByCid,
                     std::map<uint32_t, FT_UInt>& cidGlyph) {
    cidGlyph.clear();
    if (unicodeByCid.empty() || FT_Select_Charmap(face, FT_ENCODING_UNICODE)) return false;
    for (auto const& [cid, unicode] : unicodeByCid) {
        uint32_t scalar = jpdfium::font::decodeUnicodeScalar(unicode);
        FT_UInt gid = scalar ? FT_Get_Char_Index(face, scalar) : 0;
        if (!gid) {
            cidGlyph.clear();
            return false;
        }
        cidGlyph[cid] = gid;
    }
    return !cidGlyph.empty();
}

std::string subsetCidProgram(FontProgram const& program, std::set<uint32_t> const& usedCids,
                             std::map<uint32_t, FT_UInt> const& cidGlyph,
                             std::map<uint32_t, FT_UInt>& remappedGlyph,
                             std::set<FT_UInt>& targetGlyphs) {
    remappedGlyph.clear();
    targetGlyphs.clear();
    if ((program.kind != ProgramKind::TrueType &&
         (program.kind != ProgramKind::OpenType || FT_IS_CID_KEYED(program.face))) ||
        program.noSubsetting) {
        return {};
    }
    for (uint32_t cid : usedCids) {
        if (cid && !cidGlyph.contains(cid)) return {};
    }
    for (auto const& [cid, gid] : cidGlyph) {
        if (usedCids.empty() || usedCids.contains(cid)) remappedGlyph[cid] = gid;
    }
    if (remappedGlyph.empty()) remappedGlyph = cidGlyph;
    targetGlyphs.insert(0);
    for (auto const& [cid, gid] : remappedGlyph) {
        (void)cid;
        targetGlyphs.insert(gid);
    }
    return jpdfium::font::subsetSfntProgram(
               program.face, reinterpret_cast<const uint8_t*>(program.bytes.data()),
               program.bytes.size(), 0, targetGlyphs, /*retainGids=*/true,
               /*keepGlyphNames=*/false)
        .bytes;
}

void writeCidWidths(QPDFObjectHandle& target, FT_Face face,
                    std::map<uint32_t, FT_UInt> const& cidGlyph) {
    if (!face || face->units_per_EM == 0 || cidGlyph.empty()) return;
    std::map<uint32_t, int> widths;
    for (auto const& [cid, gid] : cidGlyph) {
        int scaled = 0;
        if (jpdfium::font::pdfWidthFromGlyph(face, gid, scaled)) widths[cid] = scaled;
    }
    QPDFObjectHandle arr = buildCidWidthsArray(widths);
    if (arr.isArray() && arr.getArrayNItems() > 0) target.replaceKey("/W", arr);
}

void pinTrueTypeMapping(QPDF& qpdf, QPDFObjectHandle font, QPDFObjectHandle const& descriptor,
                        FT_Face face, bool symbolic) {
    if (symbolic) {
        font.removeKey("/Encoding");
        auto unicodeOf = jpdfium::font::unicodeByGlyph(face, symbolic);
        std::map<uint32_t, FT_ULong> codes;
        jpdfium::font::SimpleEncoding none;
        for (int code = 0; code < 256; ++code) {
            FT_UInt gid = jpdfium::font::glyphForSimpleCode(face, code, true, none);
            if (gid) {
                if (auto it = unicodeOf.find(gid); it != unicodeOf.end()) codes[code] = it->second;
            }
        }
        setToUnicode(qpdf, font, codes, false);
        return;
    }
    auto enc = parseSimpleEncoding(font, descriptor);
    auto unicodeOf = jpdfium::font::unicodeByGlyph(face, symbolic);
    std::map<uint32_t, FT_ULong> codes;
    auto differences = QPDFObjectHandle::newArray();
    int lastCode = -2;
    for (int code = 0; code < 256; ++code) {
        FT_UInt gid = jpdfium::font::glyphForSimpleCode(face, code, symbolic, enc);
        bool differs = enc.differences.contains(code);
        FT_ULong u = differs ? 0 : baseEncodingUnicode(enc.base, code);
        if (auto it = unicodeOf.find(gid); !u && gid && it != unicodeOf.end()) u = it->second;
        if ((code >= 0x20 || differs) &&
            (differs || (u != 0 && u != baseEncodingUnicode(BaseEncoding::WinAnsi, code)))) {
            char name[64] = "";
            if (const char* agl = adobeGlyphNameForUnicode(static_cast<uint32_t>(u))) {
                std::snprintf(name, sizeof name, "%s", agl);
            } else if (differs && (unicodeForGlyphName(enc.differences.at(code)) ||
                                   (gid && FT_HAS_GLYPH_NAMES(face)))) {
                std::snprintf(name, sizeof name, "%s", enc.differences.at(code).c_str());
            } else if (u > 0 && u <= 0xFFFF) {
                std::snprintf(name, sizeof name, "uni%04lX", u);
            } else if (differs) {
                std::snprintf(name, sizeof name, ".notdef");
            }
            if (name[0]) {
                if (code != lastCode + 1)
                    differences.appendItem(QPDFObjectHandle::newInteger(code));
                differences.appendItem(QPDFObjectHandle::newName(std::string("/") + name));
                lastCode = code;
            }
        }
        if (gid && u) codes[code] = u;
    }
    if (differences.getArrayAsVector().empty()) {
        font.replaceKey("/Encoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
    } else {
        auto encoding = QPDFObjectHandle::newDictionary();
        encoding.replaceKey("/Type", QPDFObjectHandle::newName("/Encoding"));
        encoding.replaceKey("/BaseEncoding", QPDFObjectHandle::newName("/WinAnsiEncoding"));
        encoding.replaceKey("/Differences", differences);
        font.replaceKey("/Encoding", qpdf.makeIndirectObject(encoding));
    }
    setToUnicode(qpdf, font, codes, false);
}

void pinBuiltInTrueTypeMapping(QPDF& qpdf, QPDFObjectHandle font, FT_Face face) {
    font.removeKey("/Encoding");
    auto unicodeOf = jpdfium::font::unicodeByGlyph(face, true);
    jpdfium::font::SimpleEncoding builtIn;
    std::map<uint32_t, FT_ULong> codes;
    for (int code = 0; code < 256; ++code) {
        FT_UInt gid = jpdfium::font::glyphForSimpleCode(face, code, true, builtIn);
        if (!gid) continue;
        if (auto it = unicodeOf.find(gid); it != unicodeOf.end()) {
            codes[code] = it->second;
        } else if (FT_HAS_GLYPH_NAMES(face)) {
            char name[256] = "";
            if (FT_Get_Glyph_Name(face, gid, name, sizeof(name)) == 0 && name[0]) {
                if (uint32_t unicode = unicodeForGlyphName(name)) codes[code] = unicode;
            }
        }
    }
    setToUnicode(qpdf, font, codes, false);
}

QPDFObjectHandle newDescriptor(QPDF& qpdf, QPDFObjectHandle const& font, FT_Face face,
                               bool symbolic) {
    auto scaled = [&](long v) {
        return QPDFObjectHandle::newInteger(v * 1000 /
                                            (face->units_per_EM ? face->units_per_EM : 1000));
    };
    bool italic = (face->style_flags & FT_STYLE_FLAG_ITALIC) != 0;
    auto* os2 = static_cast<TT_OS2*>(FT_Get_Sfnt_Table(face, FT_SFNT_OS2));
    auto bbox = QPDFObjectHandle::newArray();
    for (long v : {face->bbox.xMin, face->bbox.yMin, face->bbox.xMax, face->bbox.yMax}) {
        bbox.appendItem(scaled(v));
    }
    auto d = QPDFObjectHandle::newDictionary();
    d.replaceKey("/Type", QPDFObjectHandle::newName("/FontDescriptor"));
    d.replaceKey("/FontName", font.getKey("/BaseFont"));
    d.replaceKey("/Flags", QPDFObjectHandle::newInteger((symbolic ? 4 : 32) | (italic ? 64 : 0) |
                                                        (FT_IS_FIXED_WIDTH(face) ? 1 : 0)));
    d.replaceKey("/FontBBox", bbox);
    d.replaceKey("/ItalicAngle", QPDFObjectHandle::newInteger(italic ? -12 : 0));
    d.replaceKey("/Ascent", scaled(face->ascender));
    d.replaceKey("/Descent", scaled(face->descender));
    d.replaceKey("/CapHeight", scaled(os2 && os2->sCapHeight ? os2->sCapHeight : face->ascender));
    d.replaceKey("/StemV",
                 QPDFObjectHandle::newInteger((face->style_flags & FT_STYLE_FLAG_BOLD) ? 120 : 80));
    return qpdf.makeIndirectObject(d);
}

bool hasSignatureDict(QPDFObjectHandle const& field) {
    if (!field.isDictionary()) return false;
    auto value = field.getKey("/V");
    return field.hasKey("/ByteRange") || (value.isDictionary() && value.hasKey("/ByteRange"));
}

void collectSignatureFieldSubtree(QPDFObjectHandle rootNode, std::set<QPDFObjGen>& out) {
    constexpr size_t kMaxFieldVisit = 4096;
    constexpr int kMaxFieldDepth = 256;
    struct StackItem {
        QPDFObjectHandle obj;
        int depth = 0;
    };
    std::vector<StackItem> stack{{rootNode, 0}};
    std::set<QPDFObjGen> visited;
    size_t steps = 0;
    while (!stack.empty() && steps < kMaxFieldVisit) {
        auto [cur, depth] = stack.back();
        stack.pop_back();
        ++steps;
        if (cur.isIndirect()) {
            out.insert(cur.getObjGen());
            if (!visited.insert(cur.getObjGen()).second) continue;
        }
        if (depth >= kMaxFieldDepth || !cur.isDictionary()) continue;
        auto kids = cur.getKey("/Kids");
        if (!kids.isArray()) continue;
        for (auto& kid : kids.getArrayAsVector()) {
            if (kid.isIndirect() && visited.count(kid.getObjGen())) continue;
            stack.push_back({kid, depth + 1});
        }
    }
}

bool isSignatureFieldDict(QPDFObjectHandle const& d) {
    if (!d.isDictionary()) return false;
    auto ft = d.getKey("/FT");
    auto type = d.getKey("/Type");
    return hasSignatureDict(d) || (ft.isName() && ft.getName() == "/Sig") ||
           (type.isName() && type.getName() == "/Sig");
}

bool isSignatureOrAncestor(QPDFObjectHandle const& obj, std::set<QPDFObjGen> const& sigObjects) {
    if (obj.isIndirect() && sigObjects.count(obj.getObjGen())) return true;
    if (isSignatureFieldDict(obj)) return true;
    // Page widgets of a signature field can omit /FT /Sig and inherit it via /Parent.
    std::set<QPDFObjGen> seenParents;
    QPDFObjectHandle cur = obj;
    for (int hop = 0; hop < 32 && cur.isDictionary(); ++hop) {
        QPDFObjectHandle parent = cur.getKey("/Parent");
        if (!parent.isDictionary()) break;
        if (parent.isIndirect()) {
            auto og = parent.getObjGen();
            if (!seenParents.insert(og).second) break;
            if (sigObjects.count(og)) return true;
        }
        if (isSignatureFieldDict(parent)) return true;
        cur = parent;
    }
    return false;
}

void stripAllSignatures(QPDF& qpdf) {
    constexpr size_t kMaxFieldVisit = 4096;
    constexpr int kMaxFieldDepth = 256;

    std::set<QPDFObjGen> sigObjects;
    std::vector<QPDFObjectHandle> initialSigs;
    for (auto& object : qpdf.getAllObjects()) {
        auto dict = object.isStream() ? object.getDict() : object;
        if (!isSignatureFieldDict(dict)) continue;
        sigObjects.insert(object.getObjGen());
        auto v = dict.getKey("/V");
        if (v.isIndirect()) sigObjects.insert(v.getObjGen());
        initialSigs.push_back(object);
    }
    for (auto& sigObj : initialSigs) collectSignatureFieldSubtree(sigObj, sigObjects);

    // Prune signature fields (with bounded cycle-safe traversal) from /AcroForm.
    auto root = qpdf.getRoot();
    if (root.hasKey("/AcroForm")) {
        auto acroForm = root.getKey("/AcroForm");
        if (acroForm.isDictionary()) {
            struct FieldFrame {
                QPDFObjectHandle node;
                int depth = 0;
            };
            std::vector<FieldFrame> worklist{{acroForm, 0}};
            std::set<QPDFObjGen> visitedNodes;
            size_t visitedCount = 0;
            while (!worklist.empty() && visitedCount < kMaxFieldVisit) {
                auto [node, depth] = worklist.back();
                worklist.pop_back();
                ++visitedCount;
                if (!node.isDictionary()) continue;
                if (node.isIndirect() && !visitedNodes.insert(node.getObjGen()).second) continue;
                if (depth >= kMaxFieldDepth) continue;
                for (char const* key : {"/Fields", "/Kids"}) {
                    if (!node.hasKey(key) || !node.getKey(key).isArray()) continue;
                    auto items = node.getKey(key).getArrayAsVector();
                    std::vector<QPDFObjectHandle> kept;
                    kept.reserve(items.size());
                    for (auto& item : items) {
                        if (isSignatureOrAncestor(item, sigObjects)) {
                            collectSignatureFieldSubtree(item, sigObjects);
                            continue;
                        }
                        if (item.isDictionary() &&
                            (!item.isIndirect() || !visitedNodes.count(item.getObjGen()))) {
                            worklist.push_back({item, depth + 1});
                        }
                        kept.push_back(item);
                    }
                    if (kept.size() != items.size()) {
                        node.replaceKey(key, QPDFObjectHandle::newArray(kept));
                    }
                }
            }
            if (acroForm.hasKey("/SigFlags")) acroForm.removeKey("/SigFlags");
        }
    }

    // Prune page widget annotations whose parent or target is a stripped signature field.
    for (auto page : qpdf.getAllPages()) {
        if (page.hasKey("/Annots") && page.getKey("/Annots").isArray()) {
            auto items = page.getKey("/Annots").getArrayAsVector();
            std::vector<QPDFObjectHandle> kept;
            kept.reserve(items.size());
            for (auto& annot : items) {
                if (!isSignatureOrAncestor(annot, sigObjects)) kept.push_back(annot);
            }
            if (kept.size() != items.size()) {
                page.replaceKey("/Annots", QPDFObjectHandle::newArray(kept));
            }
        }
    }

    // Clear catalog signature permissions (/Perms /DocMDP /UR3) and /DSS validation store.
    if (root.hasKey("/Perms")) {
        auto perms = root.getKey("/Perms");
        if (perms.isDictionary()) {
            perms.removeKey("/DocMDP");
            perms.removeKey("/UR3");
            if (perms.getKeys().empty()) root.removeKey("/Perms");
        }
    }
    if (root.hasKey("/DSS")) root.removeKey("/DSS");

    // Remove residual signature /ByteRange and /Contents entries so detectors see unsigned state.
    for (auto& object : qpdf.getAllObjects()) {
        auto dict = object.isStream() ? object.getDict() : object;
        if (!dict.isDictionary()) continue;
        if (dict.hasKey("/ByteRange")) dict.removeKey("/ByteRange");
        if (dict.hasKey("/Contents") && dict.hasKey("/Type") &&
            dict.getKey("/Type").isNameAndEquals("/Sig")) {
            dict.removeKey("/Contents");
        }
    }
}

struct FontEmbedJobState {
    std::map<uint32_t, uint32_t> cidUnicode;
    std::map<uint32_t, uint32_t> codeUnicode;
    std::map<uint32_t, FT_UInt> cidGlyph;
    std::set<uint32_t> usedCids;
    bool cidRemap = false;
    bool predefinedCjk = false;
    bool japan1Rksj = false;
    bool identityEncoding = false;
    bool identityCidMap = false;
};

void collectEmbedCandidateFonts(QPDF& qpdf, std::vector<QPDFObjectHandle>& fonts) {
    auto collectFromResources = [&](QPDFObjectHandle const& res) {
        auto fontDict = res.isDictionary() ? res.getKey("/Font") : QPDFObjectHandle();
        if (!fontDict.isDictionary()) return;
        for (auto const& [name, entry] : fontDict.getDictAsMap()) {
            (void)name;
            if (entry.isDictionary()) fonts.push_back(entry);
        }
    };
    for (auto& object : qpdf.getAllObjects()) {
        auto dict = object.isStream() ? object.getDict() : object;
        if (!dict.isDictionary()) continue;
        auto type = dict.getKey("/Type");
        auto subtypeName = dict.getKey("/Subtype");
        bool cidDescendant = subtypeName.isName() && (subtypeName.getName() == "/CIDFontType0" ||
                                                      subtypeName.getName() == "/CIDFontType2");
        if (type.isNameAndEquals("/Font") && !cidDescendant) fonts.push_back(dict);
        collectFromResources(dict.getKey("/Resources"));
    }
    auto acroForm = qpdf.getRoot().getKey("/AcroForm");
    if (acroForm.isDictionary()) collectFromResources(acroForm.getKey("/DR"));
}

bool hasInheritedToUnicode(QPDFObjectHandle const& font) {
    auto toUnicode = font.getKey("/ToUnicode");
    return toUnicode.isStream() && !toUnicode.getDict().getKey("/UseCMap").isNull();
}

bool prepareMissingCidMapping(QPDF& qpdf, QPDFObjectHandle& font, QPDFObjectHandle& target,
                              FontProgram const& program, bool indexedUsage,
                              FontUsageIndexes const& usage, FontEmbedJobState& job) {
    auto cidMap = target.getKey("/CIDToGIDMap");
    job.identityCidMap = cidMap.isNull() || cidMap.isNameAndEquals("/Identity");
    job.identityEncoding = font.getKey("/Encoding").isNameAndEquals("/Identity-H") ||
                           font.getKey("/Encoding").isNameAndEquals("/Identity-V");
    CidEncoding predefinedEnc = cidEncodingFromName(font.getKey("/Encoding"));
    job.predefinedCjk =
        predefinedEnc != CidEncoding::TwoByte && predefinedEnc != CidEncoding::SingleByte;
    job.japan1Rksj = isRksjCidEncoding(predefinedEnc);
    bool japan1Proportional = isRksjPropCidEncoding(predefinedEnc);
    bool korea1Ksc = isKscEucCidEncoding(predefinedEnc);
    bool cjkVertical = isVerticalCidEncoding(predefinedEnc);

    // An inherited ToUnicode map can describe CIDs the local stream does not list;
    // rebuilding a CIDToGIDMap from the local map alone would drop those glyphs.
    if (hasInheritedToUnicode(font)) return false;
    // Rebuilding /CIDToGIDMap is only safe for Identity CID maps; custom maps stay verbatim.
    if (!job.identityCidMap) return false;
    if (job.predefinedCjk) {
        // Predefined CJK encodings require scanning page streams to discover referenced CIDs.
        auto scan = usedCidsForFont(qpdf, font, predefinedEnc);
        job.usedCids = scan.cids;
        if (!scan.complete || job.usedCids.empty()) return false;
        for (uint32_t c : job.usedCids) {
            uint32_t unicode = 0;
            bool found = job.japan1Rksj ? japan1UnicodeForCid(c, unicode)
                         : korea1Ksc    ? korea1UnicodeForCid(c, unicode)
                                        : gb1UnicodeForCid(c, unicode);
            if (!found) {
                job.cidUnicode.clear();
                break;
            }
            job.cidUnicode[c] = unicode;
        }
        for (uint32_t code : scan.codes) {
            if (code > 0xFFFF) continue;
            uint32_t cidFromCode = 0;
            bool found =
                job.japan1Rksj ? japan1CidForCode(static_cast<uint16_t>(code), cidFromCode,
                                                  japan1Proportional, cjkVertical)
                : korea1Ksc
                    ? korea1CidForCode(static_cast<uint16_t>(code), cidFromCode, cjkVertical)
                    : gb1CidForCode(static_cast<uint16_t>(code), cidFromCode, cjkVertical);
            if (!found) continue;
            if (auto it = job.cidUnicode.find(cidFromCode); it != job.cidUnicode.end()) {
                job.codeUnicode[code] = it->second;
            }
        }
        if (job.cidUnicode.empty() ||
            !coversWidthCids(target, job.cidUnicode, job.usedCids, scan.complete)) {
            return false;
        }
    } else {
        if (!job.identityEncoding) return false;
        job.cidUnicode = parseToUnicodeSingleScalar(font);
        auto scan =
            usedCidsForFont(qpdf, font, CidEncoding::TwoByte, indexedUsage ? &usage : nullptr);
        job.usedCids = scan.cids;
        if (job.cidUnicode.empty() ||
            !coversWidthCids(target, job.cidUnicode, job.usedCids, scan.complete)) {
            return false;
        }
    }

    if (program.kind == ProgramKind::TrueType) {
        // Identity CID-to-GID only works directly when donor glyph IDs already equal CIDs.
        job.cidRemap = !cidGlyphOrderMatches(program.face, job.cidUnicode);
        if (!mapCidsToGlyphs(program.face, job.cidUnicode, job.cidGlyph)) return false;
        writeCidWidths(target, program.face, job.cidGlyph);
    } else {
        if (!cidGlyphOrderMatches(program.face, job.cidUnicode)) return false;
        for (auto const& [mappedCid, unicode] : job.cidUnicode) {
            uint32_t scalar = jpdfium::font::decodeUnicodeScalar(unicode);
            if (!scalar) {
                job.cidGlyph.clear();
                break;
            }
            job.cidGlyph[mappedCid] = FT_Get_Char_Index(program.face, scalar);
        }
        if (job.cidGlyph.empty()) return false;
        writeCidWidths(target, program.face, job.cidGlyph);
    }
    return true;
}

void populateEmbeddedFontProgram(QPDF& qpdf, FT_Library lib, QPDFObjectHandle& font,
                                 QPDFObjectHandle& target, QPDFObjectHandle& descriptor,
                                 FontProgram& program, std::string const& targetName, bool cid,
                                 bool hasProgram, bool onlyMissing, bool mapBuiltIn, bool symbolic,
                                 FontEmbedJobState const& job, bool& openType) {
    std::set<FT_UInt> glyphs;
    std::string subset;
    auto subtype = target.getKey("/Subtype");
    std::string current = subtype.isName() ? subtype.getName() : "";
    auto wrapper = descriptor.getKey("/FontFile3");
    bool wrapped =
        wrapper.isStream() && wrapper.getDict().getKey("/Subtype").isNameAndEquals("/OpenType");
    auto kind =
        program.kind == ProgramKind::TrueType && wrapped ? ProgramKind::OpenType : program.kind;
    bool glyf = program.kind == ProgramKind::TrueType;
    bool trueTypeFont = current == "/TrueType" || current == "/CIDFontType2";
    // Preserve existing simple encodings when substituting a non-TrueType or donor fallback
    // program.
    bool pinSimpleMapping = !cid && (glyf != trueTypeFont || (!hasProgram && onlyMissing));
    if (glyf != trueTypeFont) {
        target.replaceKey("/Subtype",
                          QPDFObjectHandle::newName(cid ? (glyf ? "/CIDFontType2" : "/CIDFontType0")
                                                        : (glyf ? "/TrueType" : "/Type1")));
        if (cid && glyf && !target.hasKey("/CIDToGIDMap")) {
            target.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newName("/Identity"));
        }
    }
    if (pinSimpleMapping) {
        if (mapBuiltIn) {
            pinBuiltInTrueTypeMapping(qpdf, target, program.face);
        } else {
            pinTrueTypeMapping(qpdf, target, descriptor, program.face, symbolic);
        }
    }
    // A bare CFF /Type1C stream is valid in every PDF version and is the only
    // CFF-flavoured option PDF/A-1 accepts. Prefer it over an /OpenType wrapper
    // for simple fonts.
    if (kind == ProgramKind::OpenType && !wrapped && !cid) {
        std::string cff = jpdfium::font::extractCffTableFromSfnt(lib, program.bytes);
        if (!cff.empty() && !cid) {
            auto enc = parseSimpleEncoding(target, descriptor);
            if (reachesSameGlyphs(lib, cff, program.face, enc, symbolic)) {
                kind = ProgramKind::BareCff;
                subset = std::move(cff);
                pinTrueTypeMapping(qpdf, target, descriptor, program.face, symbolic);
            }
        }
    }

    // Subsetting TrueType CID fonts renumbers retained GIDs, requiring a fresh /CIDToGIDMap.
    std::map<uint32_t, FT_UInt> remappedGlyph;
    std::set<FT_UInt> targetGlyphs;
    bool identityCidMap = job.identityCidMap || target.getKey("/CIDToGIDMap").isNull() ||
                          target.getKey("/CIDToGIDMap").isNameAndEquals("/Identity");
    bool identityEncoding = job.identityEncoding ||
                            font.getKey("/Encoding").isNameAndEquals("/Identity-H") ||
                            font.getKey("/Encoding").isNameAndEquals("/Identity-V");
    if (cid && glyf && (identityEncoding || job.predefinedCjk) && identityCidMap) {
        std::map<uint32_t, FT_ULong> codes;
        if (job.predefinedCjk && !job.codeUnicode.empty()) {
            for (auto const& [code, unicode] : job.codeUnicode) codes[code] = unicode;
        } else if (!job.cidUnicode.empty()) {
            for (auto const& [code, unicode] : job.cidUnicode) codes[code] = unicode;
        } else if (!hasProgram) {
            for (auto [gid, unicode] : jpdfium::font::unicodeByGlyph(program.face, symbolic)) {
                codes[gid] = unicode;
            }
        }
        auto writeCidToGidMap = [&](std::map<uint32_t, FT_UInt> const& mapping) {
            uint32_t maxCid = 0;
            for (auto const& [code, gid] : mapping) maxCid = std::max(maxCid, code);
            std::vector<uint8_t> map(2 * (size_t(maxCid) + 1), 0);
            for (auto const& [code, gid] : mapping) {
                const size_t mapOff = static_cast<size_t>(code) * 2;
                map[mapOff] = uint8_t(gid >> 8);
                map[mapOff + 1] = uint8_t(gid & 0xFF);
            }
            target.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newStream(
                                                  &qpdf, std::string(map.begin(), map.end())));
        };
        if (!job.cidGlyph.empty()) {
            if (!hasProgram) {
                subset = subsetCidProgram(program, job.usedCids, job.cidGlyph, remappedGlyph,
                                          targetGlyphs);
            }
            if (!subset.empty() &&
                subsetKeepsCidGlyphs(lib, subset, job.cidUnicode, remappedGlyph)) {
                glyphs = std::move(targetGlyphs);
                writeCidToGidMap(remappedGlyph);
            } else {
                subset.clear();
                glyphs.clear();
                remappedGlyph.clear();
                targetGlyphs.clear();
                if (job.cidRemap) {
                    writeCidToGidMap(job.cidGlyph);
                } else if (target.getKey("/CIDToGIDMap").isNull()) {
                    target.replaceKey("/CIDToGIDMap", QPDFObjectHandle::newName("/Identity"));
                }
            }
        }
        if (job.predefinedCjk) {
            setCjkToUnicode(qpdf, font, codes, job.japan1Rksj);
        } else {
            setToUnicode(qpdf, font, codes, true);
        }
    }

    // Deduplicate embedded /FontFile streams across descriptors sharing identical subset payloads.
    auto& stream = program.streams[{kind, cid, glyphs}];
    if (!stream.isInitialized()) {
        stream = makeProgramStream(qpdf, program, kind, cid, subset, onlyMissing);
    }
    if (!glyphs.empty() && !jpdfium::font::hasSubsetTag(targetName)) {
        std::set<uint32_t> glyphIds(glyphs.begin(), glyphs.end());
        std::string tag = jpdfium::font::buildSubsetTagForGlyphs(targetName, glyphIds);
        std::string taggedStr;
        taggedStr.reserve(1 + tag.size() + 1 + targetName.size());
        taggedStr += '/';
        taggedStr += tag;
        taggedStr += '+';
        taggedStr += targetName;
        auto tagged = QPDFObjectHandle::newName(taggedStr);
        target.replaceKey("/BaseFont", tagged);
        descriptor.replaceKey("/FontName", tagged);
        if (cid) font.replaceKey("/BaseFont", tagged);
    }
    for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) descriptor.removeKey(key);
    descriptor.replaceKey(kind == ProgramKind::TrueType ? "/FontFile2"
                          : kind == ProgramKind::Type1  ? "/FontFile"
                                                        : "/FontFile3",
                          stream);

    if (cid) {
        std::set<uint32_t> activeCids;
        FT_Face activeFace = program.face;
        jpdfium::font::FtFaceGuard subGuard;
        if (!subset.empty()) {
            FT_Face subFace = nullptr;
            if (FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(subset.data()),
                                   FT_Long(subset.size()), 0, &subFace) == 0 &&
                subFace) {
                subGuard.reset(subFace);
                activeFace = subFace;
            } else {
                activeFace = nullptr;
            }
        }
        if (activeFace) {
            activeCids = cidsPresentInProgram(target, activeFace);
        }
        if (activeCids.empty()) {
            activeCids.insert(0);
            bool cidSetComplete = true;
            if (!remappedGlyph.empty()) {
                for (auto const& [c, gid] : remappedGlyph) {
                    (void)gid;
                    activeCids.insert(c);
                }
            } else if (!job.cidGlyph.empty()) {
                for (auto const& [c, gid] : job.cidGlyph) {
                    (void)gid;
                    activeCids.insert(c);
                }
            } else if (job.identityCidMap) {
                long maxGlyph =
                    std::min<long>(program.face->num_glyphs - 1, jpdfium::font::kMaxCid);
                for (long g = 0; g <= maxGlyph; ++g) activeCids.insert(static_cast<uint32_t>(g));
            } else {
                cidSetComplete = false;
            }
            if (!cidSetComplete) {
                activeCids.clear();
                descriptor.removeKey("/CIDSet");
            }
        }
        if (!activeCids.empty()) updateCidSet(qpdf, descriptor, activeCids);
    }
    openType |= kind == ProgramKind::OpenType;
}

EmbedFontsResult embedFonts(std::function<void(QPDF&)> const& load,
                            std::vector<FontSource> const& sources, bool onlyMissing,
                            FILE* out = nullptr, bool existingOnly = false,
                            bool stripSignatures = false) {
    struct Programs : std::vector<FontProgram> {
        FT_Library lib = nullptr;
        ~Programs() {
            for (auto& p : *this) {
                if (p.face) FT_Done_Face(p.face);
            }
            if (lib) FT_Done_FreeType(lib);
        }
    } programs;
    programs.resize(sources.size());
    FT_Library& lib = programs.lib;
    try {
        if (FT_Init_FreeType(&lib)) return {nullptr, "FreeType init failed", -1};
        auto qpdf = QPDF::create();
        load(*qpdf);

        // Signatures cryptographically seal byte ranges; abort unless caller explicitly strips
        // them.
        if (stripSignatures) stripAllSignatures(*qpdf);
        if (!stripSignatures && pdfHasDigitalSignature(*qpdf)) {
            return {nullptr, "document is signed; refusing to rewrite", -3};
        }

        // Snapshot candidate fonts before rewriting so descriptor mutations never skew traversal.
        std::vector<QPDFObjectHandle> fonts;
        collectEmbedCandidateFonts(*qpdf, fonts);

        for (size_t i = 0; i < sources.size(); ++i) {
            programs[i].usable = loadFontProgram(lib, sources[i].data, programs[i]);
            if (!programs[i].usable && !onlyMissing) {
                return {nullptr, "unusable font program for /BaseFont " + sources[i].name, -4};
            }
        }

        auto nameOf = [](QPDFObjectHandle const& font) {
            auto base = font.getKey("/BaseFont");
            return base.isName() ? jpdfium::font::stripPdfNamePrefix(base.getName())
                                 : std::string();
        };

        // Pre-scan stream usage once across multi-font documents to avoid repeated content parses.
        FontUsageIndexes usage;
        bool indexedUsage = onlyMissing && fonts.size() > 1;
        if (indexedUsage) usage = buildFontUsageIndexes(*qpdf);

        int embedded = 0;
        bool openType = false;
        std::set<QPDFObjGen> seen;
        for (auto& font : fonts) {
            if (font.isIndirect() && !seen.insert(font.getObjGen()).second) continue;
            auto descendants = font.getKey("/DescendantFonts");
            bool cid = descendants.isArray() && descendants.getArrayNItems() > 0 &&
                       descendants.getArrayItem(0).isDictionary();
            auto target = cid ? descendants.getArrayItem(0) : font;
            std::string fontName = nameOf(font), targetName = nameOf(target);
            auto source = std::ranges::find_if(sources, [&](FontSource const& s) {
                auto name = jpdfium::font::stripPdfNamePrefix(s.name);
                return name == fontName || name == targetName ||
                       name == jpdfium::font::latin1ToUtf8(fontName) ||
                       name == jpdfium::font::latin1ToUtf8(targetName);
            });
            if (source == sources.end()) continue;
            if (target.getKey("/Subtype").isNameAndEquals("/Type3")) continue;
            auto& program = programs[source - sources.begin()];
            if (!program.usable) {
                if (onlyMissing) continue;
                return {nullptr, "unusable font program for /BaseFont " + fontName, -4};
            }
            if (targetName.empty()) targetName = jpdfium::font::stripPdfNamePrefix(source->name);
            auto descriptor = target.getKey("/FontDescriptor");
            bool hasProgram = descriptor.isDictionary() &&
                              (descriptor.hasKey("/FontFile") || descriptor.hasKey("/FontFile2") ||
                               descriptor.hasKey("/FontFile3"));
            if (existingOnly && !hasProgram) continue;
            if (hasProgram && onlyMissing) continue;
            if (cid && program.kind == ProgramKind::Type1) {
                return {nullptr, "a Type 1 program cannot back a CIDFont: " + fontName, -4};
            }
            if (font.getKey("/Encoding").isDictionary()
                    ? font.getKey("/Encoding")
                          .getKey("/BaseEncoding")
                          .isNameAndEquals("/MacExpertEncoding")
                    : font.getKey("/Encoding").isNameAndEquals("/MacExpertEncoding")) {
                if (onlyMissing) continue;
                return {nullptr,
                        "MacExpertEncoding fonts cannot be substituted safely: " + fontName, -4};
            }

            FontEmbedJobState job;
            if (cid && !hasProgram && onlyMissing) {
                if (!prepareMissingCidMapping(*qpdf, font, target, program, indexedUsage, usage,
                                              job)) {
                    continue;
                }
            } else {
                auto cidMap = target.getKey("/CIDToGIDMap");
                job.identityCidMap = cidMap.isNull() || cidMap.isNameAndEquals("/Identity");
                job.identityEncoding = font.getKey("/Encoding").isNameAndEquals("/Identity-H") ||
                                       font.getKey("/Encoding").isNameAndEquals("/Identity-V");
            }

            auto flags =
                descriptor.isDictionary() ? descriptor.getKey("/Flags") : QPDFObjectHandle();
            bool hasEncoding = font.hasKey("/Encoding") || target.hasKey("/Encoding");
            bool builtInMapping = !cid && !hasEncoding;
            bool isSymbolName = (targetName == "Symbol" || targetName == "ZapfDingbats" ||
                                 targetName.find("Symbol") != std::string::npos ||
                                 targetName.find("Dingbats") != std::string::npos);
            bool symbolic = builtInMapping &&
                            (isSymbolName || (flags.isInteger() && (flags.getIntValue() & 4) != 0));
            bool mapBuiltIn = builtInMapping && symbolic;
            QPDFObjectHandle pendingWidths;
            if (!cid && (onlyMissing || !target.getKey("/Widths").isArray())) {
                pendingWidths = buildWidths(target, descriptor, program.face, mapBuiltIn);
                if (!pendingWidths.isInitialized()) continue;
            }
            // Verify simple-font character coverage BEFORE mutating target or cloning its
            // descriptor so direct-font usage lookup keys stay intact and rejected fonts remain
            // untouched.
            if (!cid && !hasProgram && onlyMissing) {
                auto scan = usedCidsForFont(*qpdf, font, CidEncoding::SingleByte,
                                            indexedUsage ? &usage : nullptr);
                if (!scan.complete) continue;
                auto enc = parseSimpleEncoding(target, descriptor);
                bool covered = true;
                for (uint32_t code : scan.cids) {
                    if (code >= 256) continue;
                    if (jpdfium::font::glyphForSimpleCode(program.face, static_cast<int>(code),
                                                          symbolic, enc) == 0) {
                        covered = false;
                        break;
                    }
                }
                if (!covered) continue;
            }

            if (descriptor.isIndirect()) {
                descriptor = qpdf->makeIndirectObject(
                    QPDFObjectHandle::newDictionary(descriptor.getDictAsMap()));
                for (char const* key : {"/FontFile", "/FontFile2", "/FontFile3"}) {
                    descriptor.removeKey(key);
                }
                descriptor.replaceKey("/FontName", target.getKey("/BaseFont"));
                target.replaceKey("/FontDescriptor", descriptor);
            }
            if (pendingWidths.isInitialized()) applyWidths(target, pendingWidths);
            target.replaceKey("/Type", QPDFObjectHandle::newName("/Font"));
            if (cid) font.replaceKey("/Type", QPDFObjectHandle::newName("/Font"));
            if (!descriptor.isDictionary()) {
                descriptor = newDescriptor(*qpdf, target, program.face, symbolic);
                target.replaceKey("/FontDescriptor", descriptor);
            }
            if (onlyMissing && descriptor.isDictionary() && descriptor.hasKey("/Flags") &&
                descriptor.getKey("/Flags").isInteger()) {
                long long f = descriptor.getKey("/Flags").getIntValue();
                f = symbolic ? ((f | 4) & ~32) : ((f & ~4) | 32);
                descriptor.replaceKey("/Flags", QPDFObjectHandle::newInteger(f));
            }
            populateEmbeddedFontProgram(*qpdf, lib, font, target, descriptor, program, targetName,
                                        cid, hasProgram, onlyMissing, mapBuiltIn, symbolic, job,
                                        openType);
            ++embedded;
        }
        if (embedded == 0) return {nullptr, "no matching font", 1};

        QPDFWriter writer{*qpdf};
        writer.setCompressStreams(true);
        writer.setDecodeLevel(qpdf_dl_none);
        writer.setNewlineBeforeEndstream(true);
        if (openType) writer.setMinimumPDFVersion("1.6");
        if (out) {
            writer.setOutputFile("font-out", out, false);
        } else {
            writer.setOutputMemory();
        }
        WriterLock lock{9};
        writer.write();
        return {out ? nullptr : writer.getBufferSharedPointer(), "", 0};
    } catch (QPDFExc const& e) {
        if (e.getErrorCode() == qpdf_e_password) return {nullptr, "password protected", -2};
        return {nullptr, e.what(), -1};
    } catch (std::exception const& e) {
        return {nullptr, e.what(), -1};
    }
}

int32_t embedFontsExport(const uint8_t* input, int64_t inputLen, int32_t count,
                         const char* const* names, const uint8_t* const* fonts,
                         const int64_t* fontLens, bool onlyMissing, bool existingOnly,
                         uint8_t** output, int64_t* outputLen, bool stripSignatures = false) {
    try {
        if (!input || inputLen <= 0 || count <= 0 || !names || !fonts || !fontLens || !output ||
            !outputLen) {
            return -1;
        }
        *output = nullptr;
        *outputLen = 0;
        std::vector<FontSource> sources;
        for (int32_t i = 0; i < count; ++i) {
            if (!names[i] || !*names[i] || !fonts[i] || fontLens[i] <= 0) return -1;
            sources.push_back({names[i], {fonts[i], static_cast<size_t>(fontLens[i])}});
        }
        auto result = embedFonts(
            [&](QPDF& qpdf) {
                qpdf.processMemoryFile("font-in", reinterpret_cast<const char*>(input),
                                       static_cast<size_t>(inputLen));
            },
            sources, onlyMissing, nullptr, existingOnly, stripSignatures);
        if (result.rc != 0) {
            std::fprintf(stderr, "jpdfium qpdf embed fonts: %s\n", result.error.c_str());
            return result.rc;
        }
        if (!result.buffer || result.buffer->getSize() == 0) return -1;
        *outputLen = static_cast<int64_t>(result.buffer->getSize());
        *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
        if (!*output) {
            *outputLen = 0;
            return -1;
        }
        std::memcpy(*output, result.buffer->getBuffer(), static_cast<size_t>(*outputLen));
        return 0;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

}  // namespace

bool loadFontProgram(FT_Library lib, std::span<const uint8_t> data, FontProgram& p) {
    if (data.size() < 4 || std::memcmp(data.data(), "ttcf", 4) == 0 ||
        FT_New_Memory_Face(lib, data.data(), FT_Long(data.size()), 0, &p.face)) {
        return false;
    }
    std::string_view format = FT_Get_Font_Format(p.face);
    FT_ULong len = 0;
    if (FT_IS_SFNT(p.face)) {
        if (!FT_Load_Sfnt_Table(p.face, FT_MAKE_TAG('C', 'F', 'F', '2'), 0, nullptr, &len)) {
            return false;
        }
        bool glyf = !FT_Load_Sfnt_Table(p.face, FT_MAKE_TAG('g', 'l', 'y', 'f'), 0, nullptr, &len);
        p.kind = glyf ? ProgramKind::TrueType : ProgramKind::OpenType;
        p.noSubsetting = jpdfium::font::fontForbidsSubsetting(p.face);
    } else if (format == "CFF") {
        p.kind = ProgramKind::BareCff;
    } else if (format == "Type 1") {
        p.kind = ProgramKind::Type1;
        return splitType1(data, p);
    } else {
        return false;
    }
    p.bytes.assign(reinterpret_cast<const char*>(data.data()), data.size());
    return true;
}

QPDFObjectHandle makeProgramStream(QPDF& qpdf, FontProgram const& p, ProgramKind kind, bool cid,
                                   std::string const& subset, bool normalizeCmap) {
    std::string bytes = subset.empty() ? p.bytes : subset;
    if (normalizeCmap && kind == ProgramKind::TrueType) {
        jpdfium::font::ensureUnicodeMicrosoftCmap(bytes);
    }
    auto stream = QPDFObjectHandle::newStream(&qpdf, bytes);
    auto dict = stream.getDict();
    if (kind == ProgramKind::TrueType) {
        dict.replaceKey("/Length1",
                        QPDFObjectHandle::newInteger(static_cast<long long>(bytes.size())));
    }
    if (kind == ProgramKind::Type1) {
        dict.replaceKey("/Length1", QPDFObjectHandle::newInteger(p.length1));
        dict.replaceKey("/Length2", QPDFObjectHandle::newInteger(p.length2));
        dict.replaceKey("/Length3", QPDFObjectHandle::newInteger(p.length3));
    }
    char const* subtype = kind == ProgramKind::OpenType  ? "/OpenType"
                          : kind != ProgramKind::BareCff ? nullptr
                          : cid                          ? "/CIDFontType0C"
                                                         : "/Type1C";
    if (subtype) dict.replaceKey("/Subtype", QPDFObjectHandle::newName(subtype));
    return stream;
}

bool reachesSameGlyphs(FT_Library lib, std::string const& subset, FT_Face original,
                       jpdfium::font::SimpleEncoding const& enc, bool symbolic) {
    FT_Face face = nullptr;
    if (subset.empty() || FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(subset.data()),
                                             FT_Long(subset.size()), 0, &face)) {
        return false;
    }
    jpdfium::font::FtFaceGuard guard(face);
    for (int code = 0; code < 256; ++code) {
        if (jpdfium::font::glyphForSimpleCode(face, code, symbolic, enc) !=
            jpdfium::font::glyphForSimpleCode(original, code, symbolic, enc)) {
            return false;
        }
    }
    return true;
}

bool subsetKeepsCidGlyphs(FT_Library lib, std::string const& subset,
                          std::map<uint32_t, uint32_t> const& unicodeByCid,
                          std::map<uint32_t, FT_UInt> const& cidGlyph) {
    FT_Face face = nullptr;
    if (subset.empty() || FT_New_Memory_Face(lib, reinterpret_cast<const FT_Byte*>(subset.data()),
                                             FT_Long(subset.size()), 0, &face)) {
        return false;
    }
    jpdfium::font::FtFaceGuard guard(face);
    if (FT_Select_Charmap(face, FT_ENCODING_UNICODE)) return false;
    for (auto const& [cid, gid] : cidGlyph) {
        auto it = unicodeByCid.find(cid);
        uint32_t scalar =
            it == unicodeByCid.end() ? 0 : jpdfium::font::decodeUnicodeScalar(it->second);
        if (!scalar || FT_Get_Char_Index(face, scalar) != gid) return false;
    }
    return true;
}

}  // namespace jpdfium::qpdf_detail

extern "C" {

using jpdfium::qpdf_detail::embedFonts;
using jpdfium::qpdf_detail::embedFontsExport;
using jpdfium::qpdf_detail::FontSource;

JPDFIUM_EXPORT int32_t jpdfium_qpdf_embed_fonts(const uint8_t* input, int64_t inputLen,
                                                int32_t count, const char* const* names,
                                                const uint8_t* const* fonts,
                                                const int64_t* fontLens, int32_t flags,
                                                uint8_t** output, int64_t* outputLen) {
    return embedFontsExport(input, inputLen, count, names, fonts, fontLens, (flags & 1) != 0, false,
                            output, outputLen, (flags & 2) != 0);
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_embed_fonts_file(const char* in_path, const char* out_path,
                                                     int32_t count, const char* const* names,
                                                     const uint8_t* const* fonts,
                                                     const int64_t* fontLens, int32_t flags) {
    try {
        if (!in_path || !out_path || count <= 0 || !names || !fonts || !fontLens) return -1;
        std::vector<FontSource> sources;
        for (int32_t i = 0; i < count; ++i) {
            if (!names[i] || !*names[i] || !fonts[i] || fontLens[i] <= 0) return -1;
            sources.push_back({names[i], {fonts[i], static_cast<size_t>(fontLens[i])}});
        }
        std::error_code ec;
        if (std::filesystem::exists(in_path, ec) && std::filesystem::exists(out_path, ec) &&
            std::filesystem::equivalent(in_path, out_path, ec)) {
            return -1;
        }
        FILE* raw = jpdfium::qpdf_detail::createOutputFile(out_path);
        if (!raw) return -1;
        struct FileCloser {
            void operator()(FILE* f) const noexcept {
                if (f) std::fclose(f);
            }
        };
        std::unique_ptr<FILE, FileCloser> out(raw);
        const bool onlyMissing = (flags & 1) != 0;
        const bool stripSignatures = (flags & 2) != 0;
        auto result = embedFonts([&](QPDF& qpdf) { qpdf.processFile(in_path); }, sources,
                                 onlyMissing, out.get(), false, stripSignatures);
        if (result.rc != 0) {
            std::fprintf(stderr, "jpdfium qpdf embed fonts file: %s\n", result.error.c_str());
            return result.rc;
        }
        return std::fclose(out.release()) == 0 ? 0 : -1;
    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_qpdf_replace_font_stream(const uint8_t* input, int64_t inputLen,
                                                        const char* baseFont,
                                                        const uint8_t* fontData, int64_t fontLen,
                                                        uint8_t** output, int64_t* outputLen) {
    if (!baseFont || !*baseFont) return -1;
    const char* names[1] = {baseFont};
    const uint8_t* fonts[1] = {fontData};
    const int64_t lens[1] = {fontLen};
    return embedFontsExport(input, inputLen, 1, names, fonts, lens, false, true, output, outputLen);
}

}  // extern "C"

#endif  // JPDFIUM_HAS_QPDF && JPDFIUM_HAS_FREETYPE && JPDFIUM_HAS_HARFBUZZ
