// profile.h - a model profile: pinned ONNX models, their dictionary and pipeline parameters.
#pragma once

#include <filesystem>
#include <string>
#include <vector>

#include "jpdfium_api.h"
#include "paddle.h"

namespace jpdf::ocr {

struct PinnedFile {
    std::filesystem::path path;
    std::string sha256;
};

struct Profile {
    std::string id;
    PinnedFile detector;
    PinnedFile recognizer;
    std::vector<std::string> characters;  // [0] is the CTC blank, the last is a space
    DetectorParams det;
    RecognizerParams rec;
};

// Reads DIR/profile.txt, checks the small files against their pinned SHA-256 and loads the
// dictionary from the same bytes it hashed. Models are checked when read, by read_pinned.
jpdf_status load_profile(const std::filesystem::path& dir, Profile* out, std::string* detail);

// Reads a whole file and refuses it unless it matches its digest, so the bytes checked are the
// bytes used.
jpdf_status read_pinned(const PinnedFile& file, uintmax_t max_bytes, std::vector<uint8_t>* out,
                        std::string* detail);

}  // namespace jpdf::ocr
