// profile.cpp - profile.txt parsing and digest checks. Unknown lines and keys are refused.

#include "engine.h"

#include <fstream>
#include <map>
#include <memory>
#include <sstream>

namespace jpdf::ocr {

namespace {

constexpr uintmax_t kMaxDictionaryBytes = uintmax_t{16} << 20;

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

jpdf_status load_profile(const std::filesystem::path& dir, Profile* out, std::string* detail) {
    std::ifstream in(dir / "profile.txt", std::ios::binary);
    if (!in)
        return refuse(JPDF_E_NOT_FOUND, detail, "profile.txt not found in the profile directory");
    Profile p;
    bool format_ok = false;
    std::map<std::string, std::pair<std::string, std::string>> files;  // role -> name, digest
    std::string line;
    while (std::getline(in, line)) {
        std::istringstream fields(line);
        std::vector<std::string> f;
        for (std::string token; fields >> token;) f.push_back(token);
        if (f.empty() || f[0][0] == '#') continue;
        if (f[0] == "format" && f.size() == 2) {
            if (f[1] != "1")
                return refuse(JPDF_E_UNSUPPORTED, detail, "unsupported profile format");
            format_ok = true;
        } else if (f[0] == "id" && f.size() == 2) {
            p.id = f[1];
        } else if ((f[0] == "file" && f.size() == 5) || (f[0] == "derived" && f.size() == 4)) {
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
