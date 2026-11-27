// jpdfium_pattern.cpp - PCRE2 JIT regex matching, Luhn validation, and FlashText NER.

#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <string>
#include <unordered_map>

#include "jpdfium.h"
#include "jpdfium_internal.h"

namespace {

void appendJsonString(std::string& out, const char* data, size_t len) {
    out.push_back('"');
    for (size_t i = 0; i < len; ++i) {
        auto c = static_cast<unsigned char>(data[i]);
        switch (c) {
            case '"':
                out += "\\\"";
                break;
            case '\\':
                out += "\\\\";
                break;
            case '\b':
                out += "\\b";
                break;
            case '\f':
                out += "\\f";
                break;
            case '\n':
                out += "\\n";
                break;
            case '\r':
                out += "\\r";
                break;
            case '\t':
                out += "\\t";
                break;
            default:
                if (c < 0x20) {
                    char u[7];
                    std::snprintf(u, sizeof(u), "\\u%04x", static_cast<unsigned int>(c));
                    out += u;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    out.push_back('"');
}

}  // namespace

// PCRE2 JIT Pattern Engine

#ifdef JPDFIUM_HAS_PCRE2
#define PCRE2_CODE_UNIT_WIDTH 8
#include <pcre2.h>

struct Pcre2Pattern8 {
    pcre2_code* code;
    pcre2_match_data* match_data;
};

int32_t jpdfium_pcre2_compile(const char* pattern, uint32_t flags, int64_t* handle) {
    try {
        if (!pattern || !handle) return JPDFIUM_ERR_INVALID;

        uint32_t pcre2_opts = 0;
        if (flags & JPDFIUM_PCRE2_CASELESS) pcre2_opts |= PCRE2_CASELESS;
        if (flags & JPDFIUM_PCRE2_MULTILINE) pcre2_opts |= PCRE2_MULTILINE;
        if (flags & JPDFIUM_PCRE2_DOTALL) pcre2_opts |= PCRE2_DOTALL;
        if (flags & JPDFIUM_PCRE2_UTF) pcre2_opts |= PCRE2_UTF;
        if (flags & JPDFIUM_PCRE2_UCP) pcre2_opts |= PCRE2_UCP;

        int errcode = 0;
        PCRE2_SIZE erroffset = 0;
        pcre2_code* code =
            pcre2_compile(reinterpret_cast<PCRE2_SPTR>(pattern), PCRE2_ZERO_TERMINATED, pcre2_opts,
                          &errcode, &erroffset, nullptr);

        if (!code) return JPDFIUM_ERR_INVALID;

        // JIT compile for speed - failure is non-fatal (falls back to interpreter)
        pcre2_jit_compile(code, PCRE2_JIT_COMPLETE);

        auto* pw = new Pcre2Pattern8();
        pw->code = code;
        pw->match_data = pcre2_match_data_create_from_pattern(code, nullptr);

        Pcre2HandleRegistry::instance().add(pw);
        *handle = reinterpret_cast<int64_t>(pw);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_pcre2_match_all(int64_t pattern_handle, const char* text, char** json_result) {
    try {
        if (!text || !json_result) return JPDFIUM_ERR_INVALID;
        *json_result = nullptr;
        auto* pw = reinterpret_cast<Pcre2Pattern8*>(pattern_handle);
        if (!pw || !Pcre2HandleRegistry::instance().contains(pw)) return JPDFIUM_ERR_INVALID;
        if (!pw->code) return JPDFIUM_ERR_INVALID;

        PCRE2_SIZE subject_len = strlen(text);
        std::string json = "[";
        bool first = true;
        PCRE2_SIZE offset = 0;

        while (offset < subject_len) {
            int rc = pcre2_match(pw->code, reinterpret_cast<PCRE2_SPTR>(text), subject_len, offset,
                                 0, pw->match_data, nullptr);
            if (rc < 0) break;

            PCRE2_SIZE* ovector = pcre2_get_ovector_pointer(pw->match_data);
            PCRE2_SIZE start = ovector[0];
            PCRE2_SIZE end = ovector[1];

            if (!first) json += ',';
            first = false;

            json += "{\"start\":";
            json += std::to_string(static_cast<size_t>(start));
            json += ",\"end\":";
            json += std::to_string(static_cast<size_t>(end));
            json += ",\"match\":";
            appendJsonString(json, text + start, static_cast<size_t>(end - start));
            json.push_back('}');

            offset = (end > start) ? end : end + 1;
        }

        json += ']';
        char* dup = strdup(json.c_str());
        if (!dup) return JPDFIUM_ERR_NATIVE;
        *json_result = dup;
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

void jpdfium_pcre2_free(int64_t pattern_handle) noexcept {
    // Membership is checked before any dereference, so a fabricated handle is
    // rejected rather than read. See HandleRegistry for the lifetime contract.
    auto* pw = reinterpret_cast<Pcre2Pattern8*>(pattern_handle);
    if (!pw || !Pcre2HandleRegistry::instance().contains(pw)) return;
    Pcre2HandleRegistry::instance().remove(pw);
    if (pw->match_data) pcre2_match_data_free(pw->match_data);
    if (pw->code) pcre2_code_free(pw->code);
    delete pw;
}

#else  // !JPDFIUM_HAS_PCRE2

int32_t jpdfium_pcre2_compile(const char*, uint32_t, int64_t* handle) {
    if (handle) *handle = 0;
    return JPDFIUM_ERR_NOT_FOUND;
}
int32_t jpdfium_pcre2_match_all(int64_t, const char*, char** json_result) {
    if (json_result) *json_result = strdup("[]");
    return JPDFIUM_ERR_NOT_FOUND;
}
void jpdfium_pcre2_free(int64_t) noexcept {}

#endif  // JPDFIUM_HAS_PCRE2

// Luhn Algorithm - pure C, no dependencies

int32_t jpdfium_luhn_validate(const char* number) {
    try {
        if (!number) return 0;

        int digits[32];
        int n = 0;
        for (const char* p = number; *p && n < 32; ++p) {
            if (*p >= '0' && *p <= '9')
                digits[n++] = *p - '0';
            else if (*p != ' ' && *p != '-')
                return 0;  // invalid character
        }

        // Credit cards: 13-19 digits (Visa 13/16/19, MC/Amex/Discover 15-16)
        if (n < 13 || n > 19) return 0;

        int sum = 0;
        for (int i = n - 1, alt = 0; i >= 0; --i, alt ^= 1) {
            int d = digits[i];
            if (alt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return (sum % 10 == 0) ? 1 : 0;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

// FlashText Trie-based Keyword NER - O(n) matching

namespace {

struct TrieNode {
    std::unordered_map<char, std::unique_ptr<TrieNode>> children;
    std::string keyword;  // non-empty at terminal nodes
    std::string label;    // entity label at terminal nodes
};

struct FlashTextProcessor {
    std::unique_ptr<TrieNode> root;
    bool case_sensitive;

    FlashTextProcessor() : root(std::make_unique<TrieNode>()), case_sensitive(false) {}

    void addKeyword(const char* keyword, const char* label) {
        if (!keyword) return;
        TrieNode* node = root.get();
        for (const char* p = keyword; *p; ++p) {
            char c = case_sensitive
                         ? *p
                         : static_cast<char>(std::tolower(static_cast<unsigned char>(*p)));
            auto it = node->children.find(c);
            if (it == node->children.end()) {
                node->children[c] = std::make_unique<TrieNode>();
            }
            node = node->children[c].get();
        }
        node->keyword = keyword;
        node->label = label ? label : "";
    }

    std::string findAll(const char* text) const {
        if (!text) return "[]";
        std::string result = "[";
        bool first = true;
        size_t textLen = strlen(text);

        for (size_t i = 0; i < textLen;) {
            TrieNode* node = root.get();
            size_t matchEnd = 0;
            std::string matchKeyword, matchLabel;
            bool found = false;

            for (size_t j = i; j < textLen; ++j) {
                char c = case_sensitive
                             ? text[j]
                             : static_cast<char>(std::tolower(static_cast<unsigned char>(text[j])));
                auto it = node->children.find(c);
                if (it == node->children.end()) break;
                node = it->second.get();
                if (!node->keyword.empty()) {
                    bool leftBound =
                        (i == 0) || !std::isalnum(static_cast<unsigned char>(text[i - 1]));
                    bool rightBound = (j + 1 >= textLen) ||
                                      !std::isalnum(static_cast<unsigned char>(text[j + 1]));
                    if (leftBound && rightBound) {
                        matchEnd = j + 1;
                        matchKeyword = node->keyword;
                        matchLabel = node->label;
                        found = true;
                    }
                }
            }

            if (found) {
                if (!first) result += ',';
                first = false;
                result += "{\"start\":";
                result += std::to_string(i);
                result += ",\"end\":";
                result += std::to_string(matchEnd);
                result += ",\"keyword\":";
                appendJsonString(result, matchKeyword.data(), matchKeyword.size());
                result += ",\"label\":";
                appendJsonString(result, matchLabel.data(), matchLabel.size());
                result.push_back('}');
                i = matchEnd;
            } else {
                ++i;
            }
        }

        result += ']';
        return result;
    }
};

}  // anonymous namespace

int32_t jpdfium_flashtext_create(int64_t* handle) {
    try {
        if (!handle) return JPDFIUM_ERR_INVALID;
        auto ft = std::make_unique<FlashTextProcessor>();
        FlashTextHandleRegistry::instance().add(ft.get());
        *handle = reinterpret_cast<int64_t>(ft.release());
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_flashtext_add_keyword(int64_t handle, const char* keyword, const char* label) {
    try {
        auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
        if (!ft || !FlashTextHandleRegistry::instance().contains(ft)) return JPDFIUM_ERR_INVALID;
        if (!keyword) return JPDFIUM_ERR_INVALID;
        ft->addKeyword(keyword, label);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_flashtext_add_keywords_json(int64_t handle, const char* json) {
    try {
        auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
        if (!ft || !FlashTextHandleRegistry::instance().contains(ft)) return JPDFIUM_ERR_INVALID;
        if (!json) return JPDFIUM_ERR_INVALID;

        // Simple JSON parser for: [{"keyword":"...","label":"..."}, ...]
        // Scope each object strictly inside '{' .. '}' so optional labels never
        // bleed across adjacent array elements, and allow whitespace after ':'.
        auto findJsonStr = [](const char* from, const char* limit, const char* key,
                              std::string& value, const char** nextOut) -> bool {
            const char* kpos = nullptr;
            const char* search = from;
            while ((search = strstr(search, key))) {
                if (limit && search >= limit) break;
                const char* before = search;
                while (before > from && (before[-1] == ' ' || before[-1] == '\t' ||
                                         before[-1] == '\r' || before[-1] == '\n')) {
                    --before;
                }
                const char* colon = search + strlen(key);
                while (colon < limit &&
                       (*colon == ' ' || *colon == '\t' || *colon == '\r' || *colon == '\n')) {
                    ++colon;
                }
                if (*colon == ':' && (before == from || before[-1] == '{' || before[-1] == ',')) {
                    kpos = search;
                    break;
                }
                search += strlen(key);
            }
            if (!kpos || (limit && kpos >= limit)) return false;
            const char* p = kpos + strlen(key);
            while (p && (!limit || p < limit) &&
                   (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) {
                ++p;
            }
            if (!p || (limit && p >= limit) || *p != ':') return false;
            ++p;
            while (p && (!limit || p < limit) &&
                   (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) {
                ++p;
            }
            if (!p || (limit && p >= limit) || *p != '"') return false;
            ++p;
            value.clear();
            while (*p && (!limit || p < limit) && *p != '"') {
                if (*p == '\\' && *(p + 1) && (!limit || p + 1 < limit)) {
                    ++p;
                    char escaped = *p++;
                    switch (escaped) {
                        case 'n':
                            value.push_back('\n');
                            break;
                        case 'r':
                            value.push_back('\r');
                            break;
                        case 't':
                            value.push_back('\t');
                            break;
                        case 'b':
                            value.push_back('\b');
                            break;
                        case 'f':
                            value.push_back('\f');
                            break;
                        case 'u': {
                            if (limit && p + 4 > limit) return false;
                            unsigned code = 0;
                            for (int i = 0; i < 4; ++i) {
                                char c = *p++;
                                code <<= 4;
                                if (c >= '0' && c <= '9')
                                    code |= unsigned(c - '0');
                                else if (c >= 'a' && c <= 'f')
                                    code |= unsigned(c - 'a' + 10);
                                else if (c >= 'A' && c <= 'F')
                                    code |= unsigned(c - 'A' + 10);
                                else
                                    return false;
                            }
                            auto appendUtf8 = [&](uint32_t cp) {
                                if (cp < 0x80) {
                                    value.push_back(char(cp));
                                } else if (cp < 0x800) {
                                    value.push_back(char(0xC0 | (cp >> 6)));
                                    value.push_back(char(0x80 | (cp & 0x3F)));
                                } else if (cp < 0x10000) {
                                    value.push_back(char(0xE0 | (cp >> 12)));
                                    value.push_back(char(0x80 | ((cp >> 6) & 0x3F)));
                                    value.push_back(char(0x80 | (cp & 0x3F)));
                                } else {
                                    value.push_back(char(0xF0 | (cp >> 18)));
                                    value.push_back(char(0x80 | ((cp >> 12) & 0x3F)));
                                    value.push_back(char(0x80 | ((cp >> 6) & 0x3F)));
                                    value.push_back(char(0x80 | (cp & 0x3F)));
                                }
                            };
                            if (code >= 0xD800 && code <= 0xDBFF) {
                                uint32_t low = 0;
                                bool paired = false;
                                if ((!limit || p + 6 <= limit) && p[0] == '\\' && p[1] == 'u') {
                                    const char* q = p + 2;
                                    for (int i = 0; i < 4; ++i) {
                                        low <<= 4;
                                        char c = *q++;
                                        if (c >= '0' && c <= '9')
                                            low |= uint32_t(c - '0');
                                        else if (c >= 'a' && c <= 'f')
                                            low |= uint32_t(c - 'a' + 10);
                                        else if (c >= 'A' && c <= 'F')
                                            low |= uint32_t(c - 'A' + 10);
                                        else
                                            break;
                                        if (i == 3) paired = true;
                                    }
                                }
                                if (paired && low >= 0xDC00 && low <= 0xDFFF) {
                                    p += 6;
                                    uint32_t cp =
                                        0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
                                    appendUtf8(cp);
                                } else {
                                    appendUtf8(0xFFFD);
                                }
                            } else if (code >= 0xDC00 && code <= 0xDFFF) {
                                appendUtf8(0xFFFD);
                            } else {
                                appendUtf8(code);
                            }
                            break;
                        }
                        default:
                            value.push_back(escaped);
                    }
                } else {
                    value.push_back(*p++);
                }
            }
            if (*p != '"') return false;
            if (nextOut) *nextOut = p + 1;
            return true;
        };

        const char* p = json;
        while (*p) {
            const char* objOpen = strchr(p, '{');
            if (!objOpen) break;
            // Find the matching closing brace outside string literals.
            const char* objEnd = objOpen + 1;
            bool inStr = false;
            while (*objEnd) {
                if (*objEnd == '\\' && inStr && *(objEnd + 1)) {
                    objEnd += 2;
                    continue;
                }
                if (*objEnd == '"') inStr = !inStr;
                if (!inStr && *objEnd == '}') break;
                ++objEnd;
            }
            if (*objEnd != '}') break;

            std::string keyword;
            std::string label;
            const char* afterKw = nullptr;
            if (findJsonStr(objOpen, objEnd, "\"keyword\"", keyword, &afterKw)) {
                findJsonStr(objOpen, objEnd, "\"label\"", label, nullptr);
                ft->addKeyword(keyword.c_str(), label.c_str());
            }
            p = objEnd + 1;
        }
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_flashtext_find(int64_t handle, const char* text, char** json_result) {
    try {
        auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
        if (!ft || !FlashTextHandleRegistry::instance().contains(ft)) return JPDFIUM_ERR_INVALID;
        if (!json_result) return JPDFIUM_ERR_INVALID;
        *json_result = nullptr;
        std::string result = ft->findAll(text);
        char* dup = strdup(result.c_str());
        if (!dup) return JPDFIUM_ERR_NATIVE;
        *json_result = dup;
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

void jpdfium_flashtext_free(int64_t handle) noexcept {
    // Identity checked before the delete; a fabricated handle is a no-op.
    auto* ft = reinterpret_cast<FlashTextProcessor*>(handle);
    if (!ft || reinterpret_cast<uintptr_t>(ft) == 1 ||
        !FlashTextHandleRegistry::instance().contains(ft))
        return;
    FlashTextHandleRegistry::instance().remove(ft);
    delete ft;
}
