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

            std::string match_text(text + start, end - start);
            std::string escaped;
            for (char c : match_text) {
                switch (c) {
                    case '"':
                        escaped += "\\\"";
                        break;
                    case '\\':
                        escaped += "\\\\";
                        break;
                    case '\n':
                        escaped += "\\n";
                        break;
                    case '\r':
                        escaped += "\\r";
                        break;
                    case '\t':
                        escaped += "\\t";
                        break;
                    default:
                        escaped += c;
                }
            }

            char buf[256];
            snprintf(buf, sizeof(buf), "{\"start\":%zu,\"end\":%zu,\"match\":\"%s\"}",
                     static_cast<size_t>(start), static_cast<size_t>(end), escaped.c_str());
            json += buf;

            offset = (end > start) ? end : end + 1;
        }

        json += ']';
        *json_result = strdup(json.c_str());
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
                char buf[512];
                snprintf(buf, sizeof(buf),
                         "{\"start\":%zu,\"end\":%zu,\"keyword\":\"%s\",\"label\":\"%s\"}", i,
                         matchEnd, matchKeyword.c_str(), matchLabel.c_str());
                result += buf;
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
        const char* p = json;
        while (*p) {
            const char* kw_start = strstr(p, "\"keyword\":\"");
            if (!kw_start) break;
            kw_start += 11;
            const char* kw_end = strchr(kw_start, '"');
            if (!kw_end) break;
            std::string keyword(kw_start, static_cast<size_t>(kw_end - kw_start));

            std::string label;
            const char* lb_start = strstr(kw_end, "\"label\":\"");
            if (lb_start) {
                lb_start += 9;
                const char* lb_end = strchr(lb_start, '"');
                if (lb_end) label.assign(lb_start, static_cast<size_t>(lb_end - lb_start));
            }

            ft->addKeyword(keyword.c_str(), label.c_str());
            p = kw_end + 1;
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
        std::string result = ft->findAll(text);
        *json_result = strdup(result.c_str());
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
