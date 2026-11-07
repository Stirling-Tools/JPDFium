// profile.cpp - profile.txt parsing and digest checks. Unknown lines and keys are refused.

#include <algorithm>
#include <fstream>
#include <map>
#include <memory>
#include <sstream>

#include "engine.h"

namespace jpdf::ocr {

namespace {

constexpr uintmax_t kMaxDictionaryBytes = uintmax_t{16} << 20;
constexpr uintmax_t kMaxProfileBytes = uintmax_t{1} << 20;

struct KnownProfile {
    const char* id;
    const char* sha256;  // digest of the shipped profile.txt
};

// Compiled-in digests make the profile -- which carries the model digests -- tamper-evident.
// A caller that supplies its own profile must pin it explicitly through
// jpdf_ocr_options.expected_profile_sha256; an unpinned unknown id is refused.
constexpr KnownProfile kKnownProfiles[] = {
    {"pp-ocrv6-small", "a8b4fa0c32804ba22d1e5c0f66ef2dabba212d0356b7c77262000b9cd9b24e59"},
    {"pp-ocrv6-small-balanced", "a306ba3b9e2963aa32a19a9599f2a4eb71bc30ca6138dc9362f6148439f7699e"},
};

const KnownProfile* known_profile(const std::string& id) {
    for (const KnownProfile& profile : kKnownProfiles) {
        if (id == profile.id) return &profile;
    }
    return nullptr;
}

bool hex_digest(const std::string& text) {
    if (text.size() != 64) return false;
    return std::all_of(text.begin(), text.end(),
                       [](char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); });
}

jpdf_status refuse(jpdf_status status, std::string* detail, const std::string& why) {
    *detail = why;
    return status;
}

// Profile file names are plain names inside the profile directory.
bool plain_name(const std::string& name) {
    return !name.empty() && name != "." && name != ".." &&
           name.find_first_of("/\\:") == std::string::npos;
}

std::string sha256_hex(const std::vector<uint8_t>& bytes) {
    Sha256 hash;
    hash.update(bytes.data(), bytes.size());
    return hash.hex_digest();
}

bool parse_int(const std::string& s, int lo, int hi, int* out) {
    try {
        size_t used = 0;
        const long v = std::stol(s, &used);
        if (used != s.size() || v < lo || v > hi) return false;
        *out = static_cast<int>(v);
        return true;
    } catch (...) {
        return false;
    }
}

bool parse_fraction(const std::string& s, double lo, double hi, double* out) {
    std::istringstream in(s);
    in.imbue(std::locale::classic());
    double v = 0;
    if (!(in >> v) || !in.eof() || !(v >= lo && v <= hi)) return false;
    *out = v;
    return true;
}

jpdf_status apply_param(const std::string& key, const std::string& value, Profile* p,
                        std::string* detail) {
    bool ok = false;
    if (key == "det_limit_side_len") {
        ok = parse_int(value, 1, 16384, &p->det.limit_side_len);
    } else if (key == "det_limit_type") {
        ok = value == "min" || value == "max";
        p->det.limit_type_min = value == "min";
    } else if (key == "det_max_side_limit") {
        ok = parse_int(value, 32, 16384, &p->det.max_side_limit);
    } else if (key == "det_thresh") {
        ok = parse_fraction(value, 0, 1, &p->det.thresh);
    } else if (key == "det_box_thresh") {
        ok = parse_fraction(value, 0, 1, &p->det.box_thresh);
    } else if (key == "det_unclip_ratio") {
        ok = parse_fraction(value, 0, 10, &p->det.unclip_ratio);
    } else if (key == "det_max_candidates") {
        ok = parse_int(value, 1, 100000, &p->det.max_candidates);
    } else if (key == "rec_height") {
        ok = parse_int(value, 8, 256, &p->rec.height);
    } else if (key == "rec_min_width") {
        ok = parse_int(value, 8, 4096, &p->rec.min_width);
    } else if (key == "rec_max_width") {
        ok = parse_int(value, 8, 16384, &p->rec.max_width);
    } else if (key == "rec_batch_size") {
        ok = parse_int(value, 1, 256, &p->rec.batch_size);
    } else {
        return refuse(JPDF_E_UNSUPPORTED, detail, "profile has an unknown parameter: " + key);
    }
    return ok ? JPDF_OK : refuse(JPDF_E_FORMAT, detail, "profile parameter out of range: " + key);
}

jpdf_status load_dictionary(const PinnedFile& file, Profile* p, std::string* detail) {
    std::vector<uint8_t> bytes;
    jpdf_status st = read_pinned(file, kMaxDictionaryBytes, &bytes, detail);
    if (st != JPDF_OK) return st;
    const std::string text(bytes.begin(), bytes.end());
    p->characters.clear();
    p->characters.push_back("blank");
    size_t start = 0;
    while (start < text.size()) {
        const size_t end = text.find('\n', start);
        if (end == std::string::npos) {
            return refuse(JPDF_E_FORMAT, detail, "dictionary must end with a newline");
        }
        if (end == start) return refuse(JPDF_E_FORMAT, detail, "dictionary has an empty entry");
        p->characters.push_back(text.substr(start, end - start));
        start = end + 1;
    }
    p->characters.push_back(" ");  // use_space_char
    return JPDF_OK;
}

}  // namespace

jpdf_status read_pinned(const PinnedFile& file, uintmax_t max_bytes, std::vector<uint8_t>* out,
                        std::string* detail) {
    std::error_code ec;
    const uintmax_t size = std::filesystem::file_size(file.path, ec);
    if (ec)
        return refuse(JPDF_E_NOT_FOUND, detail,
                      "profile file is missing: " + file.path.filename().string());
    if (size > max_bytes) return refuse(JPDF_E_LIMIT, detail, "profile file is too large");
    std::ifstream in(file.path, std::ios::binary);
    out->resize(static_cast<size_t>(size));
    if (!in || !in.read(reinterpret_cast<char*>(out->data()), static_cast<std::streamsize>(size))) {
        return refuse(JPDF_E_IO, detail, "cannot read profile file");
    }
    if (sha256_hex(*out) != file.sha256) {
        return refuse(JPDF_E_FORMAT, detail,
                      "profile file does not match its digest: " + file.path.filename().string());
    }
    return JPDF_OK;
}

jpdf_status load_profile(const std::filesystem::path& dir, const std::string& expected_sha256,
                         Profile* out, std::string* detail) {
    std::ifstream in(dir / "profile.txt", std::ios::binary);
    if (!in)
        return refuse(JPDF_E_NOT_FOUND, detail, "profile.txt not found in the profile directory");
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    if (text.size() > kMaxProfileBytes)
        return refuse(JPDF_E_LIMIT, detail, "profile.txt is too large");
    const std::string profile_digest = sha256_hex(std::vector<uint8_t>(text.begin(), text.end()));
    Profile p;
    bool format_ok = false;
    std::map<std::string, std::pair<std::string, std::string>> files;  // role -> name, digest
    std::istringstream lines(text);
    std::string line;
    while (std::getline(lines, line)) {
        std::istringstream fields(line);
        std::vector<std::string> f;
        for (std::string token; fields >> token;) f.push_back(token);
        if (f.empty() || f[0][0] == '#') continue;
        if (f[0] == "format" && f.size() == 2) {
            if (f[1] != "1")
                return refuse(JPDF_E_UNSUPPORTED, detail, "unsupported profile format");
            format_ok = true;
        } else if (f[0] == "id" && f.size() == 2) {
            if (!p.id.empty()) return refuse(JPDF_E_FORMAT, detail, "profile repeats its id");
            p.id = f[1];
        } else if ((f[0] == "file" && f.size() == 5) || (f[0] == "derived" && f.size() == 4)) {
            if (files.count(f[1])) {
                return refuse(JPDF_E_FORMAT, detail, "profile repeats the role " + f[1]);
            }
            files[f[1]] = {f[2], f[3]};
        } else if (f[0] == "param" && f.size() == 3) {
            jpdf_status st = apply_param(f[1], f[2], &p, detail);
            if (st != JPDF_OK) return st;
        } else {
            return refuse(JPDF_E_UNSUPPORTED, detail, "profile has an unknown line: " + f[0]);
        }
    }
    if (!format_ok || p.id.empty())
        return refuse(JPDF_E_FORMAT, detail, "profile lacks format or id");
    // The profile pins its model digests, so trusting it without an external anchor only catches
    // corruption. Accept a caller-supplied digest, or one of the digests compiled in for the
    // shipped profiles; refuse anything else rather than silently trust a swapped profile.
    if (!expected_sha256.empty()) {
        if (!hex_digest(expected_sha256))
            return refuse(JPDF_E_INVALID_ARGUMENT, detail,
                          "expected_profile_sha256 must be 64 lowercase hex characters");
        if (profile_digest != expected_sha256) {
            return refuse(JPDF_E_FORMAT, detail,
                          "profile.txt does not match expected_profile_sha256");
        }
    } else {
        const KnownProfile* known = known_profile(p.id);
        if (!known) {
            return refuse(JPDF_E_UNSUPPORTED, detail,
                          "unknown profile id; pin it with expected_profile_sha256");
        }
        if (profile_digest != known->sha256) {
            return refuse(JPDF_E_FORMAT, detail, "profile.txt does not match its compiled digest");
        }
    }
    for (const char* role : {"detector", "recognizer", "dictionary"}) {
        if (!files.count(role)) {
            return refuse(JPDF_E_FORMAT, detail, std::string("profile lacks the ") + role);
        }
    }
    for (const auto& [role, entry] : files) {
        if (!plain_name(entry.first)) {
            return refuse(JPDF_E_FORMAT, detail, "profile file names must be plain names");
        }
        if (role == "detector" || role == "recognizer" || role == "dictionary") continue;
        std::vector<uint8_t> bytes;  // provenance files: checked, not otherwise used
        jpdf_status st =
            read_pinned({dir / entry.first, entry.second}, kMaxDictionaryBytes, &bytes, detail);
        if (st != JPDF_OK) return st;
    }
    p.detector = {dir / files["detector"].first, files["detector"].second};
    p.recognizer = {dir / files["recognizer"].first, files["recognizer"].second};
    jpdf_status st =
        load_dictionary({dir / files["dictionary"].first, files["dictionary"].second}, &p, detail);
    if (st != JPDF_OK) return st;
    *out = std::move(p);
    return JPDF_OK;
}

}  // namespace jpdf::ocr
