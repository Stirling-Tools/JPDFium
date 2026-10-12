// engine.h - an OCR engine: a verified profile and its two sessions. Immutable once open.
#pragma once

#include <array>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>

#include "jpdfium_api.h"
#include "paddle.h"

// ort_session.h merged in: one ONNX Runtime session over the C API, CPU execution provider only.
struct OrtApi;
struct OrtSession;
struct OrtMemoryInfo;

namespace jpdf::ocr {

// The loaded runtime's version, or nullptr when it lacks the API this module was built for.
const char* ort_version() noexcept;

class Session {
   public:
    // Creates the session from model bytes already checked against their digest.
    static jpdf_status create(const std::vector<uint8_t>& model, int intra_op_threads,
                              std::unique_ptr<Session>* out, std::string* detail);
    ~Session();
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;

    // Runs the model's single float input to its first float output. Thread-safe.
    jpdf_status run(const float* input, const std::array<int64_t, 4>& shape,
                    std::vector<float>* output, std::vector<int64_t>* output_shape,
                    std::string* detail) const;

   private:
    Session() = default;
    const OrtApi* api_ = nullptr;
    OrtSession* session_ = nullptr;
    OrtMemoryInfo* memory_ = nullptr;
    std::string input_name_;
    std::string output_name_;
};

}  // namespace jpdf::ocr

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

namespace jpdf::ocr {

struct OcrLine {
    std::string text;
    std::array<double, 8> polygon{};  // corners clockwise from top-left, source pixels
    double confidence = 0;
    double detection_score = 0;
};

struct OcrResult {
    int32_t width = 0;
    int32_t height = 0;
    double dpi_x = 0;
    double dpi_y = 0;
    std::vector<OcrLine> lines;  // reading order
};

class Engine {
   public:
    static jpdf_status open(const std::filesystem::path& profile_dir, int intra_op_threads,
                            std::unique_ptr<Engine>* out, std::string* detail);

    // Runs detection, cropping and recognition on a BGR image. Safe to call concurrently.
    jpdf_status recognize(const Image& image, OcrResult* out, std::string* detail) const;

    const Profile& profile() const {
        return profile_;
    }

   private:
    jpdf_status recognize_crops(const Image& image, const std::vector<CropPlan>& plans,
                                std::vector<Recognition>* out, std::string* detail) const;

    Profile profile_;
    std::unique_ptr<Session> detector_;
    std::unique_ptr<Session> recognizer_;
};

}  // namespace jpdf::ocr
