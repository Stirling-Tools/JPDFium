// ort_session.cpp - ONNX Runtime C API calls. One environment per process, created lazily.

#include "ort_session.h"

#include <onnxruntime_c_api.h>

#include <mutex>

namespace jpdf::ocr {

namespace {

// GetApi returns nullptr when the loaded runtime is older than the headers this was built with.
const OrtApi* runtime_api() noexcept {
    static const OrtApi* api = [] {
        const OrtApiBase* base = OrtGetApiBase();
        return base ? base->GetApi(ORT_API_VERSION) : nullptr;
    }();
    return api;
}

OrtEnv* runtime_env(const OrtApi* api) noexcept {
    static std::once_flag once;
    static OrtEnv* env = nullptr;
    std::call_once(once, [api] {
        if (OrtStatus* st = api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "jpdfium-ocr", &env)) {
            api->ReleaseStatus(st);
            env = nullptr;
        }
    });
    return env;
}

// Consumes an OrtStatus; true when it reported an error, with its message in detail.
bool failed(const OrtApi* api, OrtStatus* status, std::string* detail) {
    if (!status) return false;
    *detail = std::string("ONNX Runtime: ") + api->GetErrorMessage(status);
    api->ReleaseStatus(status);
    return true;
}

}  // namespace

const char* ort_version() noexcept {
    const OrtApiBase* base = OrtGetApiBase();
    return base && runtime_api() ? base->GetVersionString() : nullptr;
}

jpdf_status Session::create(const std::vector<uint8_t>& model, int intra_op_threads,
                            std::unique_ptr<Session>* out, std::string* detail) {
    const OrtApi* api = runtime_api();
    if (!api) {
        *detail = "ONNX Runtime is older than the C API this module needs";
        return JPDF_E_UNSUPPORTED;
    }
    OrtEnv* env = runtime_env(api);
    if (!env) {
        *detail = "ONNX Runtime environment could not be created";
        return JPDF_E_INTERNAL;
    }
    std::unique_ptr<Session> session(new Session());
    session->api_ = api;
    OrtSessionOptions* options = nullptr;
    if (failed(api, api->CreateSessionOptions(&options), detail)) return JPDF_E_INTERNAL;
    std::unique_ptr<OrtSessionOptions, void (*)(OrtSessionOptions*)> owned_options(
        options, [](OrtSessionOptions* o) { runtime_api()->ReleaseSessionOptions(o); });
    if (intra_op_threads > 0 &&
        failed(api, api->SetIntraOpNumThreads(options, intra_op_threads), detail)) {
        return JPDF_E_INVALID_ARGUMENT;
    }
    if (failed(api,
               api->CreateSessionFromArray(env, model.data(), model.size(), options,
                                           &session->session_),
               detail)) {
        return JPDF_E_FORMAT;
    }
    if (failed(api,
               api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &session->memory_),
               detail)) {
        return JPDF_E_INTERNAL;
    }
    OrtAllocator* allocator = nullptr;
    if (failed(api, api->GetAllocatorWithDefaultOptions(&allocator), detail))
        return JPDF_E_INTERNAL;
    size_t inputs = 0, outputs = 0;
    if (failed(api, api->SessionGetInputCount(session->session_, &inputs), detail) ||
        failed(api, api->SessionGetOutputCount(session->session_, &outputs), detail)) {
        return JPDF_E_INTERNAL;
    }
    if (inputs != 1 || outputs < 1) {
        *detail = "model must have one input and at least one output";
        return JPDF_E_FORMAT;
    }
    char* name = nullptr;
    if (failed(api, api->SessionGetInputName(session->session_, 0, allocator, &name), detail)) {
        return JPDF_E_INTERNAL;
    }
    session->input_name_ = name;
    if (OrtStatus* freed = api->AllocatorFree(allocator, name)) api->ReleaseStatus(freed);
    if (failed(api, api->SessionGetOutputName(session->session_, 0, allocator, &name), detail)) {
        return JPDF_E_INTERNAL;
    }
    session->output_name_ = name;
    if (OrtStatus* freed = api->AllocatorFree(allocator, name)) api->ReleaseStatus(freed);
    *out = std::move(session);
    return JPDF_OK;
}

Session::~Session() {
    if (memory_) api_->ReleaseMemoryInfo(memory_);
    if (session_) api_->ReleaseSession(session_);
}

jpdf_status Session::run(const float* input, const std::array<int64_t, 4>& shape,
                         std::vector<float>* output, std::vector<int64_t>* output_shape,
                         std::string* detail) const {
    size_t count = 1;
    for (int64_t d : shape) count *= static_cast<size_t>(d);
    OrtValue* in = nullptr;
    if (failed(api_,
               api_->CreateTensorWithDataAsOrtValue(
                   memory_, const_cast<float*>(input), count * sizeof(float), shape.data(),
                   shape.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in),
               detail)) {
        return JPDF_E_INTERNAL;
    }
    const char* in_name = input_name_.c_str();
    const char* out_name = output_name_.c_str();
    OrtValue* result = nullptr;
    OrtStatus* run_status = api_->Run(session_, nullptr, &in_name, &in, 1, &out_name, 1, &result);
    api_->ReleaseValue(in);
    if (failed(api_, run_status, detail)) return JPDF_E_INTERNAL;
    std::unique_ptr<OrtValue, void (*)(OrtValue*)> owned(
        result, [](OrtValue* v) { runtime_api()->ReleaseValue(v); });
    OrtTensorTypeAndShapeInfo* info = nullptr;
    if (failed(api_, api_->GetTensorTypeAndShape(result, &info), detail)) return JPDF_E_INTERNAL;
    size_t dims = 0;
    ONNXTensorElementDataType type = ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;
    OrtStatus* st = api_->GetDimensionsCount(info, &dims);
    if (!st) {
        output_shape->assign(dims, 0);
        st = api_->GetDimensions(info, output_shape->data(), dims);
    }
    if (!st) st = api_->GetTensorElementType(info, &type);
    api_->ReleaseTensorTypeAndShapeInfo(info);
    if (failed(api_, st, detail)) return JPDF_E_INTERNAL;
    if (type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
        *detail = "model output is not float";
        return JPDF_E_FORMAT;
    }
    size_t elements = 1;
    for (int64_t d : *output_shape) elements *= static_cast<size_t>(d < 0 ? 0 : d);
    float* data = nullptr;
    if (failed(api_, api_->GetTensorMutableData(result, reinterpret_cast<void**>(&data)), detail)) {
        return JPDF_E_INTERNAL;
    }
    output->assign(data, data + elements);
    return JPDF_OK;
}

}  // namespace jpdf::ocr
