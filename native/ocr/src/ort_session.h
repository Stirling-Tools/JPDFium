// ort_session.h - one ONNX Runtime session over the C API, CPU execution provider only.
#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "jpdfium_api.h"

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
