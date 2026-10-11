// engine.h - an OCR engine: a verified profile and its two sessions. Immutable once open.
#pragma once

#include <array>
#include <filesystem>
#include <memory>
#include <string>
#include <vector>

#include "ort_session.h"
#include "paddle.h"
#include "profile.h"

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
    jpdf_status recognize_crops(const std::vector<Image>& crops, std::vector<Recognition>* out,
                                std::string* detail) const;

    Profile profile_;
    std::unique_ptr<Session> detector_;
    std::unique_ptr<Session> recognizer_;
};

}  // namespace jpdf::ocr
