// jpdfium_rewriter_streams.cpp - PDF content-stream lexing, used-code scanning,
// character code remapping, color-operator conversion, and inline-image sanitization.

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <qpdf/QPDFPageDocumentHelper.hh>
#include <sstream>
#include <tuple>

#include "jpdfium_font_rewriter_internal.h"

namespace jpdfium::rewriter {

// ---- Content-stream string codec & used-code scanner/rewriter (Stage 5) ----

bool convertCmykOpsToRgb(std::string& data, const std::string& iccCmykName) {
    size_t n = data.size();
    bool hasCmyk = false;
    for (size_t j = 0; j < n; ++j) {
        if ((data[j] == 'k' || data[j] == 'K') &&
            (j == 0 || std::isspace(static_cast<unsigned char>(data[j - 1]))) &&
            (j + 1 == n || std::isspace(static_cast<unsigned char>(data[j + 1])))) {
            hasCmyk = true;
            break;
        }
    }
    if (!hasCmyk && data.find("/DeviceCMYK") != std::string::npos) {
        hasCmyk = true;
    }
    if (!hasCmyk) return false;

    std::string out;
    out.reserve(n + 256);
    bool changed = false;
    std::vector<std::string> toks;
    auto flushToks = [&]() {
        for (const auto& t : toks) {
            out += t;
            out.push_back(' ');
        }
        toks.clear();
    };
    auto isNum = [](const std::string& s, double& v) -> bool {
        if (s.empty()) return false;
        char* end = nullptr;
        v = std::strtod(s.c_str(), &end);
        return end && *end == '\0' && std::isfinite(v);
    };

    size_t i = 0;
    while (i < n) {
        char c = data[i];
        if (std::isspace(static_cast<unsigned char>(c))) {
            ++i;
            continue;
        }
        if (c == '%') {
            flushToks();
            while (i < n && data[i] != '\n' && data[i] != '\r') out.push_back(data[i++]);
            out.push_back('\n');
            continue;
        }
        if (c == '(') {
            flushToks();
            int depth = 1;
            out.push_back(data[i++]);
            while (i < n && depth > 0) {
                char sc = data[i++];
                out.push_back(sc);
                if (sc == '\\' && i < n)
                    out.push_back(data[i++]);
                else if (sc == '(')
                    ++depth;
                else if (sc == ')')
                    --depth;
            }
            out.push_back(' ');
            continue;
        }
        if (c == '<') {
            flushToks();
            if (i + 1 < n && data[i + 1] == '<') {
                out += "<< ";
                i += 2;
            } else {
                out.push_back(data[i++]);
                while (i < n && data[i] != '>') out.push_back(data[i++]);
                if (i < n) out.push_back(data[i++]);
                out.push_back(' ');
            }
            continue;
        }
        if (c == '>' && i + 1 < n && data[i + 1] == '>') {
            flushToks();
            out += ">> ";
            i += 2;
            continue;
        }
        if (c == '[' || c == ']' || c == ')' || c == '>') {
            flushToks();
            out.push_back(data[i++]);
            out.push_back(' ');
            continue;
        }
        size_t start = i;
        if (data[i] == '/') ++i;
        while (i < n && !std::isspace(static_cast<unsigned char>(data[i])) && data[i] != '(' &&
               data[i] != ')' && data[i] != '<' && data[i] != '>' && data[i] != '[' &&
               data[i] != ']' && data[i] != '%' && data[i] != '/') {
            ++i;
        }
        if (i == start) {
            out.push_back(data[i++]);
            continue;
        }
        std::string tok = data.substr(start, i - start);
        // Inline image: copy BI ... ID <binary> EI verbatim without tokenizing binary payload
        if (tok == "BI") {
            flushToks();
            out += "BI";
            size_t biEnd = i;
            bool foundId = false;
            bool foundEi = false;
            // Find ID followed by single whitespace, then EI preceded and followed by whitespace
            while (biEnd + 2 < n) {
                if (std::isspace(static_cast<unsigned char>(data[biEnd])) &&
                    data[biEnd + 1] == 'I' && data[biEnd + 2] == 'D' &&
                    (biEnd + 3 < n && std::isspace(static_cast<unsigned char>(data[biEnd + 3])))) {
                    foundId = true;
                    biEnd += 4;
                    while (biEnd + 2 < n) {
                        if (std::isspace(static_cast<unsigned char>(data[biEnd])) &&
                            data[biEnd + 1] == 'E' && data[biEnd + 2] == 'I' &&
                            (biEnd + 3 == n ||
                             std::isspace(static_cast<unsigned char>(data[biEnd + 3])))) {
                            biEnd += 3;
                            foundEi = true;
                            break;
                        }
                        ++biEnd;
                    }
                    break;
                }
                ++biEnd;
            }
            if (!foundId || !foundEi) biEnd = n;
            out.append(data, i, biEnd - i);
            out.push_back('\n');
            i = biEnd;
            continue;
        }
        if (!iccCmykName.empty() && (tok == "cs" || tok == "CS") && !toks.empty() &&
            toks.back() == "/DeviceCMYK") {
            toks.back() = iccCmykName;
            toks.push_back(std::move(tok));
            changed = true;
            continue;
        }
        if ((tok == "k" || tok == "K") && toks.size() >= 4) {
            double cVal = 0, mVal = 0, yVal = 0, kVal = 0;
            size_t tsz = toks.size();
            if (isNum(toks[tsz - 4], cVal) && isNum(toks[tsz - 3], mVal) &&
                isNum(toks[tsz - 2], yVal) && isNum(toks[tsz - 1], kVal)) {
                for (size_t j = 0; j + 4 < tsz; ++j) {
                    out += toks[j];
                    out.push_back(' ');
                }
                if (!iccCmykName.empty()) {
                    out += iccCmykName;
                    out += (tok == "k" ? " cs " : " CS ");
                    out += toks[tsz - 4] + " " + toks[tsz - 3] + " " + toks[tsz - 2] + " " +
                           toks[tsz - 1] + (tok == "k" ? " scn\n" : " SCN\n");
                } else {
                    double r = std::clamp((1.0 - cVal) * (1.0 - kVal), 0.0, 1.0);
                    double g = std::clamp((1.0 - mVal) * (1.0 - kVal), 0.0, 1.0);
                    double b = std::clamp((1.0 - yVal) * (1.0 - kVal), 0.0, 1.0);
                    char buf[96];
                    std::snprintf(buf, sizeof(buf), "%.4f %.4f %.4f %s\n", r, g, b,
                                  tok == "k" ? "rg" : "RG");
                    out += buf;
                }
                toks.clear();
                changed = true;
                continue;
            }
        }
        toks.push_back(std::move(tok));
        if (toks.size() > 8) {
            out += toks.front();
            out.push_back(' ');
            toks.erase(toks.begin());
        }
    }
    flushToks();
    if (changed) data = std::move(out);
    return changed;
}

bool disableInlineImageInterpolate(std::string& data) {
    if (data.find("BI") == std::string::npos || data.find("true") == std::string::npos)
        return false;
    bool changed = false;
    std::string out;
    size_t lastCopied = 0;
    const size_t n = data.size();
    auto isDelimiter = [](char ch) {
        return std::isspace(static_cast<unsigned char>(ch)) || ch == '(' || ch == ')' ||
               ch == '<' || ch == '>' || ch == '[' || ch == ']' || ch == '{' || ch == '}' ||
               ch == '/' || ch == '%';
    };
    size_t i = 0;
    while (i + 2 < n) {
        if (data[i] == 'B' && data[i + 1] == 'I' &&
            (i == 0 || (isDelimiter(data[i - 1]) && data[i - 1] != '/')) &&
            isDelimiter(data[i + 2])) {
            size_t hdrStart = i + 2;
            size_t idPos = std::string::npos;
            size_t scanLim = std::min<size_t>(n, hdrStart + 2048);
            for (size_t j = hdrStart; j + 2 < scanLim; ++j) {
                if (std::isspace(static_cast<unsigned char>(data[j])) && data[j + 1] == 'I' &&
                    data[j + 2] == 'D' &&
                    (j + 3 < n && std::isspace(static_cast<unsigned char>(data[j + 3])))) {
                    idPos = j;
                    break;
                }
            }
            if (idPos != std::string::npos) {
                size_t p = hdrStart;
                while (p < idPos) {
                    if (data[p] == '/' &&
                        ((p + 2 < idPos && data[p + 1] == 'I' &&
                          std::isspace(static_cast<unsigned char>(data[p + 2]))) ||
                         (p + 12 < idPos && data.compare(p, 12, "/Interpolate") == 0 &&
                          std::isspace(static_cast<unsigned char>(data[p + 12]))))) {
                        size_t k = (data[p + 2] == 'n') ? (p + 12) : (p + 2);
                        while (k < idPos && std::isspace(static_cast<unsigned char>(data[k]))) ++k;
                        if (k + 4 <= idPos && data.compare(k, 4, "true") == 0 &&
                            (k + 4 == idPos ||
                             std::isspace(static_cast<unsigned char>(data[k + 4])) ||
                             data[k + 4] == '/')) {
                            if (!changed) {
                                out.reserve(n + 64);
                                changed = true;
                            }
                            out.append(data, lastCopied, k - lastCopied);
                            out.append("false");
                            lastCopied = k + 4;
                            p = k + 4;
                            continue;
                        }
                    }
                    ++p;
                }
                size_t eiEnd = idPos + 4;
                bool foundEi = false;
                while (eiEnd + 2 < n) {
                    if (std::isspace(static_cast<unsigned char>(data[eiEnd])) &&
                        data[eiEnd + 1] == 'E' && data[eiEnd + 2] == 'I' &&
                        (eiEnd + 3 == n ||
                         std::isspace(static_cast<unsigned char>(data[eiEnd + 3])))) {
                        eiEnd += 3;
                        foundEi = true;
                        break;
                    }
                    ++eiEnd;
                }
                i = foundEi ? eiEnd : n;
                continue;
            }
        }
        ++i;
    }
    if (changed) {
        out.append(data, lastCopied, n - lastCopied);
        data = std::move(out);
    }
    return changed;
}

// Rewrite DeviceRGB rg/RG and /DeviceRGB cs/CS operators to ICCBased sRGB (<iccRgbName> cs r g b
// scn)
bool convertRgbOpsToIcc(std::string& data, const std::string& iccRgbName) {
    if (iccRgbName.empty()) return false;
    size_t n = data.size();
    bool hasRgb = false;
    for (size_t j = 0; j + 1 < n; ++j) {
        if (((data[j] == 'r' && data[j + 1] == 'g') || (data[j] == 'R' && data[j + 1] == 'G')) &&
            (j == 0 || std::isspace(static_cast<unsigned char>(data[j - 1]))) &&
            (j + 2 == n || std::isspace(static_cast<unsigned char>(data[j + 2])))) {
            hasRgb = true;
            break;
        }
    }
    if (!hasRgb && data.find("/DeviceRGB") != std::string::npos) hasRgb = true;
    if (!hasRgb) return false;

    std::string out;
    out.reserve(n + 256);
    bool changed = false;
    std::vector<std::string> toks;
    auto flushToks = [&]() {
        for (const auto& t : toks) {
            out += t;
            out.push_back(' ');
        }
        toks.clear();
    };
    auto isNum = [](const std::string& s) -> bool {
        if (s.empty()) return false;
        char* end = nullptr;
        double v = std::strtod(s.c_str(), &end);
        return end && *end == '\0' && std::isfinite(v);
    };
    size_t i = 0;
    while (i < n) {
        char c = data[i];
        if (std::isspace(static_cast<unsigned char>(c))) {
            ++i;
            continue;
        }
        if (c == '%') {
            flushToks();
            while (i < n && data[i] != '\n' && data[i] != '\r') out.push_back(data[i++]);
            out.push_back('\n');
            continue;
        }
        if (c == '(') {
            flushToks();
            int depth = 1;
            out.push_back(data[i++]);
            while (i < n && depth > 0) {
                char sc = data[i++];
                out.push_back(sc);
                if (sc == '\\' && i < n)
                    out.push_back(data[i++]);
                else if (sc == '(')
                    ++depth;
                else if (sc == ')')
                    --depth;
            }
            out.push_back(' ');
            continue;
        }
        if (c == '<') {
            flushToks();
            if (i + 1 < n && data[i + 1] == '<') {
                out += "<< ";
                i += 2;
            } else {
                out.push_back(data[i++]);
                while (i < n && data[i] != '>') out.push_back(data[i++]);
                if (i < n) out.push_back(data[i++]);
                out.push_back(' ');
            }
            continue;
        }
        if (c == '>' && i + 1 < n && data[i + 1] == '>') {
            flushToks();
            out += ">> ";
            i += 2;
            continue;
        }
        if (c == '[' || c == ']' || c == ')' || c == '>') {
            flushToks();
            out.push_back(data[i++]);
            out.push_back(' ');
            continue;
        }
        size_t start = i;
        if (data[i] == '/') ++i;
        while (i < n && !std::isspace(static_cast<unsigned char>(data[i])) && data[i] != '(' &&
               data[i] != ')' && data[i] != '<' && data[i] != '>' && data[i] != '[' &&
               data[i] != ']' && data[i] != '%' && data[i] != '/') {
            ++i;
        }
        if (i == start) {
            out.push_back(data[i++]);
            continue;
        }
        std::string tok = data.substr(start, i - start);
        if (tok == "BI") {
            flushToks();
            out += "BI";
            size_t biEnd = i;
            bool foundId = false;
            bool foundEi = false;
            while (biEnd + 2 < n) {
                if (std::isspace(static_cast<unsigned char>(data[biEnd])) &&
                    data[biEnd + 1] == 'I' && data[biEnd + 2] == 'D' &&
                    (biEnd + 3 < n && std::isspace(static_cast<unsigned char>(data[biEnd + 3])))) {
                    foundId = true;
                    biEnd += 4;
                    while (biEnd + 2 < n) {
                        if (std::isspace(static_cast<unsigned char>(data[biEnd])) &&
                            data[biEnd + 1] == 'E' && data[biEnd + 2] == 'I' &&
                            (biEnd + 3 == n ||
                             std::isspace(static_cast<unsigned char>(data[biEnd + 3])))) {
                            biEnd += 3;
                            foundEi = true;
                            break;
                        }
                        ++biEnd;
                    }
                    break;
                }
                ++biEnd;
            }
            if (!foundId || !foundEi) biEnd = n;
            out.append(data, i, biEnd - i);
            out.push_back('\n');
            i = biEnd;
            continue;
        }
        if ((tok == "cs" || tok == "CS") && !toks.empty() && toks.back() == "/DeviceRGB") {
            toks.back() = iccRgbName;
            toks.push_back(std::move(tok));
            changed = true;
            continue;
        }
        if ((tok == "rg" || tok == "RG") && toks.size() >= 3) {
            size_t tsz = toks.size();
            if (isNum(toks[tsz - 3]) && isNum(toks[tsz - 2]) && isNum(toks[tsz - 1])) {
                for (size_t j = 0; j + 3 < tsz; ++j) {
                    out += toks[j];
                    out.push_back(' ');
                }
                out += iccRgbName + (tok == "rg" ? " cs " : " CS ");
                out += toks[tsz - 3] + " " + toks[tsz - 2] + " " + toks[tsz - 1] +
                       (tok == "rg" ? " scn\n" : " SCN\n");
                toks.clear();
                changed = true;
                continue;
            }
        }
        toks.push_back(std::move(tok));
        if (toks.size() > 8) {
            out += toks.front();
            out.push_back(' ');
            toks.erase(toks.begin());
        }
    }
    flushToks();
    if (changed) data = std::move(out);
    return changed;
}

}  // namespace jpdfium::rewriter
