// jpdfium_rewriter_streams.cpp - PDF content-stream lexing, used-code scanning,
// character code remapping, color-operator conversion, and inline-image sanitization.

#include "jpdfium_font_rewriter_internal.h"

#include <qpdf/QPDFPageDocumentHelper.hh>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <sstream>
#include <tuple>

namespace jpdfium::rewriter {

// ---- Content-stream string codec & used-code scanner/rewriter (Stage 5) ----

std::vector<uint8_t> decodePdfLiteralString(const std::string& raw) {
    std::vector<uint8_t> out;
    size_t i = 0;
    size_t n = raw.size();
    if (n >= 2 && raw.front() == '(' && raw.back() == ')') {
        i = 1;
        n -= 1;
    }
    while (i < n) {
        char c = raw[i++];
        if (c == '\\' && i < n) {
            char e = raw[i++];
            switch (e) {
                case 'n': out.push_back('\n'); break;
                case 'r': out.push_back('\r'); break;
                case 't': out.push_back('\t'); break;
                case 'b': out.push_back('\b'); break;
                case 'f': out.push_back('\f'); break;
                case '\r': if (i < n && raw[i] == '\n') ++i; break;
                case '\n': break;
                default:
                    if (e >= '0' && e <= '7') {
                        int val = e - '0';
                        for (int k = 0; k < 2 && i < n && raw[i] >= '0' && raw[i] <= '7'; ++k) {
                            val = (val << 3) | (raw[i++] - '0');
                        }
                        out.push_back(static_cast<uint8_t>(val & 0xFF));
                    } else {
                        out.push_back(static_cast<uint8_t>(e));
                    }
                    break;
            }
        } else {
            out.push_back(static_cast<uint8_t>(c));
        }
    }
    return out;
}

std::vector<uint8_t> decodePdfHexString(const std::string& raw) {
    std::vector<uint8_t> out;
    auto hexVal = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        return -1;
    };
    int hi = -1;
    for (char c : raw) {
        int v = hexVal(c);
        if (v < 0) continue;
        if (hi < 0) hi = v;
        else {
            out.push_back(static_cast<uint8_t>((hi << 4) | v));
            hi = -1;
        }
    }
    if (hi >= 0) out.push_back(static_cast<uint8_t>(hi << 4));
    return out;
}

std::string encodePdfHexString(const std::vector<uint8_t>& bytes) {
    static const char* kHex = "0123456789ABCDEF";
    std::string s;
    s.reserve(bytes.size() * 2 + 2);
    s.push_back('<');
    for (uint8_t b : bytes) {
        s.push_back(kHex[(b >> 4) & 0xF]);
        s.push_back(kHex[b & 0xF]);
    }
    s.push_back('>');
    return s;
}

void scanStreamUsedCodes(const std::string& data, QPDFObjectHandle fontDict,
                         std::map<QPDFObjGen, std::set<uint32_t>>& usedCodes,
                         std::set<QPDFObjGen>& type0Fonts) {
    if (!fontDict.isDictionary() || data.empty()) return;
    std::map<std::string, std::pair<QPDFObjGen, bool>> resFonts;
    for (const auto& k : fontDict.getKeys()) {
        QPDFObjectHandle f = fontDict.getKey(k);
        if (f.isDictionary() && f.isIndirect()) {
            bool isT0 = f.getKey("/Subtype").isNameAndEquals("/Type0");
            resFonts[k] = {f.getObjGen(), isT0};
            if (isT0) type0Fonts.insert(f.getObjGen());
        }
    }
    if (resFonts.empty()) return;

    struct Tok {
        int kind;  // 0=word, 1=litstr, 2=hexstr, 3=lbrack, 4=rbrack
        std::string val;
    };
    std::vector<Tok> stack;
    QPDFObjGen curFont;
    bool curIsT0 = false;
    bool hasFont = false;
    std::vector<std::tuple<QPDFObjGen, bool, bool>> gsStack;

    auto recordBytes = [&](const std::vector<uint8_t>& bytes) {
        if (!hasFont) return;
        auto& s = usedCodes[curFont];
        if (curIsT0) {
            for (size_t k = 0; k + 1 < bytes.size(); k += 2) {
                uint32_t cid = (static_cast<uint32_t>(bytes[k]) << 8) | bytes[k + 1];
                s.insert(cid);
            }
        } else {
            for (uint8_t b : bytes) s.insert(b);
        }
    };

    size_t i = 0;
    size_t n = data.size();
    while (i < n) {
        char c = data[i];
        if (std::isspace(static_cast<unsigned char>(c))) { ++i; continue; }
        if (c == '%') {
            while (i < n && data[i] != '\n' && data[i] != '\r') ++i;
            continue;
        }
        if (c == '(') {
            size_t st = i++;
            int depth = 1;
            while (i < n && depth > 0) {
                char sc = data[i++];
                if (sc == '\\' && i < n) ++i;
                else if (sc == '(') ++depth;
                else if (sc == ')') --depth;
            }
            stack.push_back({1, data.substr(st, i - st)});
            if (stack.size() > 4096) stack.erase(stack.begin(), stack.begin() + 2048);
            continue;
        }
        if (c == '<') {
            if (i + 1 < n && data[i + 1] == '<') { i += 2; continue; }
            size_t st = i++;
            while (i < n && data[i] != '>') ++i;
            if (i < n) ++i;
            stack.push_back({2, data.substr(st, i - st)});
            if (stack.size() > 4096) stack.erase(stack.begin(), stack.begin() + 2048);
            continue;
        }
        if (c == '>' && i + 1 < n && data[i + 1] == '>') { i += 2; continue; }
        if (c == '[') { stack.push_back({3, "["}); ++i; continue; }
        if (c == ']') { stack.push_back({4, "]"}); ++i; continue; }
        if (c == ')' || c == '>') { ++i; continue; }
        size_t st = i;
        if (data[i] == '/') ++i;
        while (i < n && !std::isspace(static_cast<unsigned char>(data[i])) &&
               data[i] != '(' && data[i] != ')' && data[i] != '<' && data[i] != '>' &&
               data[i] != '[' && data[i] != ']' && data[i] != '%' && data[i] != '/') {
            ++i;
        }
        if (i == st) { ++i; continue; }
        std::string tok = data.substr(st, i - st);
        if (tok == "BI") {
            while (i + 2 < n) {
                if (std::isspace(static_cast<unsigned char>(data[i])) &&
                    data[i + 1] == 'I' && data[i + 2] == 'D' &&
                    (i + 3 < n && std::isspace(static_cast<unsigned char>(data[i + 3])))) {
                    i += 4;
                    while (i + 2 < n) {
                        if (std::isspace(static_cast<unsigned char>(data[i])) &&
                            data[i + 1] == 'E' && data[i + 2] == 'I' &&
                            (i + 3 == n || std::isspace(static_cast<unsigned char>(data[i + 3])))) {
                            i += 3;
                            break;
                        }
                        ++i;
                    }
                    break;
                }
                ++i;
            }
            stack.clear();
            continue;
        }
        if (tok == "q") {
            gsStack.push_back({curFont, curIsT0, hasFont});
            stack.clear();
        } else if (tok == "Q") {
            if (!gsStack.empty()) {
                std::tie(curFont, curIsT0, hasFont) = gsStack.back();
                gsStack.pop_back();
            }
            stack.clear();
        } else if (tok == "Tf" && stack.size() >= 2) {
            std::string fname = stack[stack.size() - 2].val;
            auto it = resFonts.find(fname);
            if (it != resFonts.end()) {
                curFont = it->second.first;
                curIsT0 = it->second.second;
                hasFont = true;
            } else {
                hasFont = false;
            }
            stack.clear();
        } else if ((tok == "Tj" || tok == "'" || tok == "\"") && !stack.empty()) {
            const auto& t = stack.back();
            if (t.kind == 1) recordBytes(decodePdfLiteralString(t.val));
            else if (t.kind == 2) recordBytes(decodePdfHexString(t.val));
            stack.clear();
        } else if (tok == "TJ") {
            // Walk back to matching '['
            size_t lb = stack.size();
            for (size_t k = stack.size(); k-- > 0;) {
                if (stack[k].kind == 3) { lb = k; break; }
            }
            if (lb < stack.size()) {
                for (size_t k = lb + 1; k < stack.size(); ++k) {
                    if (stack[k].kind == 1) recordBytes(decodePdfLiteralString(stack[k].val));
                    else if (stack[k].kind == 2) recordBytes(decodePdfHexString(stack[k].val));
                }
            }
            stack.clear();
        } else if (!tok.empty() && tok[0] != '/' && !std::isdigit(static_cast<unsigned char>(tok[0])) &&
                   tok[0] != '-' && tok[0] != '+' && tok[0] != '.') {
            stack.clear();
        } else {
            stack.push_back({0, std::move(tok)});
            if (stack.size() > 4096) stack.erase(stack.begin(), stack.begin() + 2048);
        }
    }
}

bool rewriteStreamFontCodes(std::string& data, QPDFObjectHandle fontDict,
                            const std::map<QPDFObjGen, std::map<uint32_t, uint32_t>>& codeRemaps,
                            const std::set<QPDFObjGen>& type0Fonts) {
    if (!fontDict.isDictionary() || data.empty() || codeRemaps.empty()) return false;
    std::map<std::string, std::pair<QPDFObjGen, bool>> resFonts;
    bool anyTarget = false;
    for (const auto& k : fontDict.getKeys()) {
        QPDFObjectHandle f = fontDict.getKey(k);
        if (f.isDictionary() && f.isIndirect()) {
            QPDFObjGen fg = f.getObjGen();
            bool isT0 = type0Fonts.count(fg) > 0 || f.getKey("/Subtype").isNameAndEquals("/Type0");
            resFonts[k] = {fg, isT0};
            if (codeRemaps.count(fg)) anyTarget = true;
        }
    }
    if (!anyTarget) return false;

    struct Span {
        size_t start;
        size_t end;
        int kind;  // 1=litstr, 2=hexstr
    };
    struct Tok {
        int kind;  // 0=word, 1=litstr, 2=hexstr, 3=lbrack, 4=rbrack
        size_t start;
        size_t end;
        std::string val;
    };
    std::vector<Tok> stack;
    QPDFObjGen curFont;
    bool curIsT0 = false;
    bool hasFont = false;
    std::vector<std::tuple<QPDFObjGen, bool, bool>> gsStack;
    std::vector<std::pair<Span, std::string>> replacements;

    auto maybeRewriteSpan = [&](const Tok& t) {
        if (!hasFont) return;
        auto mit = codeRemaps.find(curFont);
        if (mit == codeRemaps.end()) return;
        const auto& rmap = mit->second;
        std::vector<uint8_t> bytes = (t.kind == 1) ? decodePdfLiteralString(t.val) : decodePdfHexString(t.val);
        bool mod = false;
        if (curIsT0) {
            for (size_t k = 0; k + 1 < bytes.size(); k += 2) {
                uint32_t cid = (static_cast<uint32_t>(bytes[k]) << 8) | bytes[k + 1];
                auto rit = rmap.find(cid);
                if (rit != rmap.end()) {
                    bytes[k] = static_cast<uint8_t>((rit->second >> 8) & 0xFF);
                    bytes[k + 1] = static_cast<uint8_t>(rit->second & 0xFF);
                    mod = true;
                }
            }
        } else {
            for (size_t k = 0; k < bytes.size(); ++k) {
                auto rit = rmap.find(bytes[k]);
                if (rit != rmap.end()) {
                    bytes[k] = static_cast<uint8_t>(rit->second & 0xFF);
                    mod = true;
                }
            }
        }
        if (mod) {
            replacements.push_back({{t.start, t.end, t.kind}, encodePdfHexString(bytes)});
        }
    };

    size_t i = 0;
    size_t n = data.size();
    while (i < n) {
        char c = data[i];
        if (std::isspace(static_cast<unsigned char>(c))) { ++i; continue; }
        if (c == '%') {
            while (i < n && data[i] != '\n' && data[i] != '\r') ++i;
            continue;
        }
        if (c == '(') {
            size_t st = i++;
            int depth = 1;
            while (i < n && depth > 0) {
                char sc = data[i++];
                if (sc == '\\' && i < n) ++i;
                else if (sc == '(') ++depth;
                else if (sc == ')') --depth;
            }
            stack.push_back({1, st, i, data.substr(st, i - st)});
            if (stack.size() > 4096) stack.erase(stack.begin(), stack.begin() + 2048);
            continue;
        }
        if (c == '<') {
            if (i + 1 < n && data[i + 1] == '<') { i += 2; continue; }
            size_t st = i++;
            while (i < n && data[i] != '>') ++i;
            if (i < n) ++i;
            stack.push_back({2, st, i, data.substr(st, i - st)});
            if (stack.size() > 4096) stack.erase(stack.begin(), stack.begin() + 2048);
            continue;
        }
        if (c == '>' && i + 1 < n && data[i + 1] == '>') { i += 2; continue; }
        if (c == '[') { stack.push_back({3, i, i + 1, "["}); ++i; continue; }
        if (c == ']') { stack.push_back({4, i, i + 1, "]"}); ++i; continue; }
        if (c == ')' || c == '>') { ++i; continue; }
        size_t st = i;
        if (data[i] == '/') ++i;
        while (i < n && !std::isspace(static_cast<unsigned char>(data[i])) &&
               data[i] != '(' && data[i] != ')' && data[i] != '<' && data[i] != '>' &&
               data[i] != '[' && data[i] != ']' && data[i] != '%' && data[i] != '/') {
            ++i;
        }
        if (i == st) { ++i; continue; }
        std::string tok = data.substr(st, i - st);
        if (tok == "BI") {
            while (i + 2 < n) {
                if (std::isspace(static_cast<unsigned char>(data[i])) &&
                    data[i + 1] == 'I' && data[i + 2] == 'D' &&
                    (i + 3 < n && std::isspace(static_cast<unsigned char>(data[i + 3])))) {
                    i += 4;
                    while (i + 2 < n) {
                        if (std::isspace(static_cast<unsigned char>(data[i])) &&
                            data[i + 1] == 'E' && data[i + 2] == 'I' &&
                            (i + 3 == n || std::isspace(static_cast<unsigned char>(data[i + 3])))) {
                            i += 3;
                            break;
                        }
                        ++i;
                    }
                    break;
                }
                ++i;
            }
            stack.clear();
            continue;
        }
        if (tok == "q") {
            gsStack.push_back({curFont, curIsT0, hasFont});
            stack.clear();
        } else if (tok == "Q") {
            if (!gsStack.empty()) {
                std::tie(curFont, curIsT0, hasFont) = gsStack.back();
                gsStack.pop_back();
            }
            stack.clear();
        } else if (tok == "Tf" && stack.size() >= 2) {
            std::string fname = stack[stack.size() - 2].val;
            auto it = resFonts.find(fname);
            if (it != resFonts.end()) {
                curFont = it->second.first;
                curIsT0 = it->second.second;
                hasFont = true;
            } else {
                hasFont = false;
            }
            stack.clear();
        } else if ((tok == "Tj" || tok == "'" || tok == "\"") && !stack.empty()) {
            const auto& t = stack.back();
            if (t.kind == 1 || t.kind == 2) maybeRewriteSpan(t);
            stack.clear();
        } else if (tok == "TJ") {
            size_t lb = stack.size();
            for (size_t k = stack.size(); k-- > 0;) {
                if (stack[k].kind == 3) { lb = k; break; }
            }
            if (lb < stack.size()) {
                for (size_t k = lb + 1; k < stack.size(); ++k) {
                    if (stack[k].kind == 1 || stack[k].kind == 2) maybeRewriteSpan(stack[k]);
                }
            }
            stack.clear();
        } else if (!tok.empty() && tok[0] != '/' && !std::isdigit(static_cast<unsigned char>(tok[0])) &&
                   tok[0] != '-' && tok[0] != '+' && tok[0] != '.') {
            stack.clear();
        } else {
            stack.push_back({0, st, i, std::move(tok)});
            if (stack.size() > 4096) stack.erase(stack.begin(), stack.begin() + 2048);
        }
    }

    if (replacements.empty()) return false;
    std::string out;
    out.reserve(data.size() + replacements.size() * 8);
    size_t pos = 0;
    for (const auto& [sp, rep] : replacements) {
        if (sp.start < pos) continue;
        out.append(data, pos, sp.start - pos);
        out += rep;
        pos = sp.end;
    }
    out.append(data, pos, data.size() - pos);
    data = std::move(out);
    return true;
}

void collectAllUsedFontCodes(QPDF& qpdf, std::map<QPDFObjGen, std::set<uint32_t>>& usedCodes,
                             std::set<QPDFObjGen>& type0Fonts) {
    std::set<QPDFObjGen> scannedStreams;
    std::function<void(QPDFObjectHandle, QPDFObjectHandle, int)> scanFormRec =
        [&](QPDFObjectHandle res, QPDFObjectHandle fallbackFonts, int depth) {
            if (!res.isDictionary() || depth > 8) return;
            QPDFObjectHandle curFonts = res.getKey("/Font").isDictionary() ? res.getKey("/Font") : fallbackFonts;
            QPDFObjectHandle xobjs = res.getKey("/XObject");
            if (!xobjs.isDictionary()) return;
            for (const auto& xk : xobjs.getKeys()) {
                QPDFObjectHandle xo = xobjs.getKey(xk);
                if (!xo.isStream()) continue;
                if (xo.isIndirect() && !scannedStreams.insert(xo.getObjGen()).second) continue;
                QPDFObjectHandle xd = xo.getDict();
                if (!xd.getKey("/Subtype").isNameAndEquals("/Form")) continue;
                QPDFObjectHandle xRes = xd.getKey("/Resources");
                QPDFObjectHandle xFonts = (xRes.isDictionary() && xRes.getKey("/Font").isDictionary())
                                              ? xRes.getKey("/Font") : curFonts;
                if (xFonts.isDictionary()) {
                    try {
                        auto buf = xo.getStreamData(qpdf_dl_generalized);
                        if (buf && buf->getSize() > 0) {
                            std::string s(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                            scanStreamUsedCodes(s, xFonts, usedCodes, type0Fonts);
                        }
                    } catch (...) {}
                }
                if (xRes.isDictionary()) scanFormRec(xRes, xFonts, depth + 1);
            }
        };

    QPDFPageDocumentHelper pdh(qpdf);
    for (auto& page : pdh.getAllPages()) {
        QPDFObjectHandle po = page.getObjectHandle();
        QPDFObjectHandle res = po.getKey("/Resources");
        QPDFObjectHandle fdict = res.isDictionary() ? res.getKey("/Font") : QPDFObjectHandle::newNull();
        auto scanSt = [&](QPDFObjectHandle st) {
            if (!st.isStream()) return;
            try {
                auto buf = st.getStreamData(qpdf_dl_generalized);
                if (buf && buf->getSize() > 0) {
                    std::string s(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                    scanStreamUsedCodes(s, fdict, usedCodes, type0Fonts);
                }
            } catch (...) {}
        };
        QPDFObjectHandle contents = po.getKey("/Contents");
        if (contents.isStream()) {
            scanSt(contents);
        } else if (contents.isArray()) {
            constexpr size_t kMaxCombinedBytes = 64U * 1024U * 1024U;
            std::string combined;
            std::set<QPDFObjGen> seenSubStreams;
            int nItems = std::min(contents.getArrayNItems(), 4096);
            for (int i = 0; i < nItems; ++i) {
                QPDFObjectHandle st = contents.getArrayItem(i);
                if (!st.isStream()) continue;
                if (st.isIndirect() && !seenSubStreams.insert(st.getObjGen()).second) continue;
                try {
                    auto buf = st.getStreamData(qpdf_dl_generalized);
                    if (buf && buf->getSize() > 0) {
                        if (combined.size() + buf->getSize() > kMaxCombinedBytes) break;
                        if (!combined.empty()) combined.push_back('\n');
                        combined.append(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                    }
                } catch (...) {}
            }
            if (!combined.empty()) {
                scanStreamUsedCodes(combined, fdict, usedCodes, type0Fonts);
            }
        }
        scanFormRec(res, fdict, 0);
        // Also scan Annotation /AP appearance streams (fixes 6.2.11.8-1 / 6.2.11.4.1-2 inside form widgets)
        QPDFObjectHandle annots = po.getKey("/Annots");
        if (annots.isArray()) {
            auto scanAp = [&](QPDFObjectHandle apSt) {
                if (!apSt.isStream()) return;
                QPDFObjectHandle aRes = apSt.getDict().getKey("/Resources");
                QPDFObjectHandle aFd = aRes.isDictionary() ? aRes.getKey("/Font") : fdict;
                if (!aFd.isDictionary()) return;
                try {
                    auto buf = apSt.getStreamData(qpdf_dl_generalized);
                    if (buf && buf->getSize() > 0) {
                        std::string s(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                        scanStreamUsedCodes(s, aFd, usedCodes, type0Fonts);
                    }
                } catch (...) {}
                if (aRes.isDictionary()) scanFormRec(aRes, aFd, 0);
            };
            for (int ai = 0; ai < annots.getArrayNItems(); ++ai) {
                QPDFObjectHandle a = annots.getArrayItem(ai);
                if (!a.isDictionary() || !a.hasKey("/AP") || !a.getKey("/AP").isDictionary()) continue;
                QPDFObjectHandle ap = a.getKey("/AP");
                for (const char* apk : {"/N", "/R", "/D"}) {
                    QPDFObjectHandle v = ap.getKey(apk);
                    if (v.isStream()) scanAp(v);
                    else if (v.isDictionary()) {
                        for (const auto& sk : v.getKeys()) scanAp(v.getKey(sk));
                    }
                }
            }
        }
    }
    for (auto& obj : qpdf.getAllObjects()) {
        if (!obj.isStream()) continue;
        if (obj.isIndirect() && scannedStreams.count(obj.getObjGen())) continue;
        QPDFObjectHandle d = obj.getDict();
        if (d.getKey("/Subtype").isNameAndEquals("/Form") ||
            (d.hasKey("/PatternType") && d.getKey("/PatternType").isInteger() &&
             d.getKey("/PatternType").getIntValue() == 1)) {
            QPDFObjectHandle res = d.getKey("/Resources");
            QPDFObjectHandle fdict = res.isDictionary() ? res.getKey("/Font") : QPDFObjectHandle::newNull();
            if (!fdict.isDictionary()) continue;
            try {
                auto buf = obj.getStreamData(qpdf_dl_generalized);
                if (buf && buf->getSize() > 0) {
                    std::string s(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                    scanStreamUsedCodes(s, fdict, usedCodes, type0Fonts);
                }
            } catch (...) {}
        }
    }
}

void rewriteAllContentStreamCodes(QPDF& qpdf,
                                  const std::map<QPDFObjGen, std::map<uint32_t, uint32_t>>& codeRemaps,
                                  const std::set<QPDFObjGen>& type0Fonts) {
    if (codeRemaps.empty()) return;
    std::set<QPDFObjGen> rewrittenStreams;
    std::function<void(QPDFObjectHandle, QPDFObjectHandle, int)> rwFormRec =
        [&](QPDFObjectHandle res, QPDFObjectHandle fallbackFonts, int depth) {
            if (!res.isDictionary() || depth > 8) return;
            QPDFObjectHandle curFonts = res.getKey("/Font").isDictionary() ? res.getKey("/Font") : fallbackFonts;
            QPDFObjectHandle xobjs = res.getKey("/XObject");
            if (!xobjs.isDictionary()) return;
            for (const auto& xk : xobjs.getKeys()) {
                QPDFObjectHandle xo = xobjs.getKey(xk);
                if (!xo.isStream()) continue;
                if (xo.isIndirect() && !rewrittenStreams.insert(xo.getObjGen()).second) continue;
                QPDFObjectHandle xd = xo.getDict();
                if (!xd.getKey("/Subtype").isNameAndEquals("/Form")) continue;
                QPDFObjectHandle xRes = xd.getKey("/Resources");
                QPDFObjectHandle xFonts = (xRes.isDictionary() && xRes.getKey("/Font").isDictionary())
                                              ? xRes.getKey("/Font") : curFonts;
                if (xFonts.isDictionary()) {
                    try {
                        auto buf = xo.getStreamData(qpdf_dl_generalized);
                        if (buf && buf->getSize() > 0) {
                            std::string s(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                            if (rewriteStreamFontCodes(s, xFonts, codeRemaps, type0Fonts)) {
                                xo.replaceStreamData(s, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
                            }
                        }
                    } catch (...) {}
                }
                if (xRes.isDictionary()) rwFormRec(xRes, xFonts, depth + 1);
            }
        };

    QPDFPageDocumentHelper pdh(qpdf);
    for (auto& page : pdh.getAllPages()) {
        QPDFObjectHandle po = page.getObjectHandle();
        QPDFObjectHandle res = po.getKey("/Resources");
        QPDFObjectHandle fdict = res.isDictionary() ? res.getKey("/Font") : QPDFObjectHandle::newNull();
        auto rwSt = [&](QPDFObjectHandle st) {
            if (!st.isStream()) return;
            try {
                auto buf = st.getStreamData(qpdf_dl_generalized);
                if (buf && buf->getSize() > 0) {
                    std::string s(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                    if (rewriteStreamFontCodes(s, fdict, codeRemaps, type0Fonts)) {
                        st.replaceStreamData(s, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
                    }
                }
            } catch (...) {}
        };
        QPDFObjectHandle contents = po.getKey("/Contents");
        if (contents.isStream()) {
            rwSt(contents);
        } else if (contents.isArray()) {
            constexpr size_t kMaxCombinedBytes = 64U * 1024U * 1024U;
            std::string combined;
            bool incomplete = false;
            int nItems = contents.getArrayNItems();
            if (nItems > 4096) {
                incomplete = true;
            } else {
                for (int i = 0; i < nItems; ++i) {
                    QPDFObjectHandle st = contents.getArrayItem(i);
                    if (!st.isStream()) continue;
                    try {
                        auto buf = st.getStreamData(qpdf_dl_generalized);
                        if (buf && buf->getSize() > 0) {
                            if (combined.size() + buf->getSize() > kMaxCombinedBytes) {
                                incomplete = true;
                                break;
                            }
                            if (!combined.empty()) combined.push_back('\n');
                            combined.append(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                        }
                    } catch (...) {
                        incomplete = true;
                        break;
                    }
                }
            }
            if (!incomplete && !combined.empty() && rewriteStreamFontCodes(combined, fdict, codeRemaps, type0Fonts)) {
                po.replaceKey("/Contents", QPDFObjectHandle::newStream(&qpdf, combined));
            }
        }
        rwFormRec(res, fdict, 0);
        // Also rewrite Annotation /AP appearance streams
        QPDFObjectHandle annots = po.getKey("/Annots");
        if (annots.isArray()) {
            auto rwAp = [&](QPDFObjectHandle apSt) {
                if (!apSt.isStream()) return;
                QPDFObjectHandle aRes = apSt.getDict().getKey("/Resources");
                QPDFObjectHandle aFd = aRes.isDictionary() ? aRes.getKey("/Font") : fdict;
                if (!aFd.isDictionary()) return;
                try {
                    auto buf = apSt.getStreamData(qpdf_dl_generalized);
                    if (buf && buf->getSize() > 0) {
                        std::string s(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                        if (rewriteStreamFontCodes(s, aFd, codeRemaps, type0Fonts)) {
                            apSt.replaceStreamData(s, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
                        }
                    }
                } catch (...) {}
                if (aRes.isDictionary()) rwFormRec(aRes, aFd, 0);
            };
            for (int ai = 0; ai < annots.getArrayNItems(); ++ai) {
                QPDFObjectHandle a = annots.getArrayItem(ai);
                if (!a.isDictionary() || !a.hasKey("/AP") || !a.getKey("/AP").isDictionary()) continue;
                QPDFObjectHandle ap = a.getKey("/AP");
                for (const char* apk : {"/N", "/R", "/D"}) {
                    QPDFObjectHandle v = ap.getKey(apk);
                    if (v.isStream()) rwAp(v);
                    else if (v.isDictionary()) {
                        for (const auto& sk : v.getKeys()) rwAp(v.getKey(sk));
                    }
                }
            }
        }
    }
    for (auto& obj : qpdf.getAllObjects()) {
        if (!obj.isStream()) continue;
        if (obj.isIndirect() && rewrittenStreams.count(obj.getObjGen())) continue;
        QPDFObjectHandle d = obj.getDict();
        if (d.getKey("/Subtype").isNameAndEquals("/Form") ||
            (d.hasKey("/PatternType") && d.getKey("/PatternType").isInteger() &&
             d.getKey("/PatternType").getIntValue() == 1)) {
            QPDFObjectHandle res = d.getKey("/Resources");
            QPDFObjectHandle fdict = res.isDictionary() ? res.getKey("/Font") : QPDFObjectHandle::newNull();
            if (!fdict.isDictionary()) continue;
            try {
                auto buf = obj.getStreamData(qpdf_dl_generalized);
                if (buf && buf->getSize() > 0) {
                    std::string s(reinterpret_cast<const char*>(buf->getBuffer()), buf->getSize());
                    if (rewriteStreamFontCodes(s, fdict, codeRemaps, type0Fonts)) {
                        obj.replaceStreamData(s, QPDFObjectHandle::newNull(), QPDFObjectHandle::newNull());
                    }
                }
            } catch (...) {}
        }
    }
}


}  // namespace jpdfium::rewriter
