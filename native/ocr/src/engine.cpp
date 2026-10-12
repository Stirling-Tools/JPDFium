// engine.cpp - the PaddleOCR 3.7 general OCR pipeline: detect, sort, crop, recognize.

#include "engine.h"

#include <algorithm>

namespace jpdf::ocr {

namespace {

constexpr uintmax_t kMaxModelBytes = uintmax_t{512} << 20;

}  // namespace

jpdf_status Engine::open(const std::filesystem::path& profile_dir, int intra_op_threads,
                         std::unique_ptr<Engine>* out, std::string* detail) {
    std::unique_ptr<Engine> engine(new Engine());
    jpdf_status st = load_profile(profile_dir, &engine->profile_, detail);
    if (st != JPDF_OK) return st;
    std::vector<uint8_t> model;
    if ((st = read_pinned(engine->profile_.detector, kMaxModelBytes, &model, detail)) != JPDF_OK) {
        return st;
    }
    if ((st = Session::create(model, intra_op_threads, &engine->detector_, detail)) != JPDF_OK) {
        return st;
    }
    if ((st = read_pinned(engine->profile_.recognizer, kMaxModelBytes, &model, detail)) !=
        JPDF_OK) {
        return st;
    }
    if ((st = Session::create(model, intra_op_threads, &engine->recognizer_, detail)) != JPDF_OK) {
        return st;
    }
    *out = std::move(engine);
    return JPDF_OK;
}

jpdf_status Engine::recognize(const Image& image, OcrResult* out, std::string* detail) const {
    std::vector<Detection> boxes;
    jpdf_status st = JPDF_OK;
    {
        // The detector input tensor and its output are needed only to derive the text boxes; drop
        // both before crops are materialized so the pipeline never holds the whole detector
        // activation alongside the recognizer batches.
        const DetectorInput input = prepare_detector_input(image, profile_.det);
        std::vector<float> pred;
        std::vector<int64_t> shape;
        st = detector_->run(input.tensor.data(), {1, 3, input.height, input.width}, &pred, &shape,
                            detail);
        if (st != JPDF_OK) return st;
        if (shape.size() != 4 || shape[0] != 1 || shape[1] != 1 || shape[2] != input.height ||
            shape[3] != input.width) {
            *detail = "detector output does not match its input size";
            return JPDF_E_FORMAT;
        }
        boxes = detect_boxes(pred.data(), input.width, input.height, image.width, image.height,
                             profile_.det);
    }
    sort_boxes(&boxes);

    std::vector<Detection> kept;
    std::vector<Recognition> texts;
    {
        // Planning every crop needs no pixels; the pixels are materialized one recognizer batch at
        // a time inside recognize_crops, so peak crop memory is bounded by the batch, not by the
        // detection count.
        std::vector<CropPlan> plans;
        plans.reserve(boxes.size());
        for (const Detection& d : boxes) {
            CropPlan plan;
            if (!plan_crop(d.box, &plan)) continue;  // empty crops are dropped, as upstream
            plans.push_back(plan);
            kept.push_back(d);
        }
        if ((st = recognize_crops(image, plans, &texts, detail)) != JPDF_OK) return st;
    }  // crop pixels are released before the text lines are assembled

    out->width = image.width;
    out->height = image.height;
    out->lines.clear();
    for (size_t i = 0; i < kept.size(); ++i) {
        if (!(texts[i].score >= 0.0)) continue;  // text_rec_score_thresh 0
        OcrLine line;
        line.text = texts[i].text;
        line.confidence = texts[i].score;
        line.detection_score = kept[i].score;
        for (size_t c = 0; c < 4; ++c) {
            line.polygon[c * 2] = kept[i].box[c].x;
            line.polygon[c * 2 + 1] = kept[i].box[c].y;
        }
        out->lines.push_back(std::move(line));
    }
    return JPDF_OK;
}

// Crops sorted by aspect ratio, then batched; each batch is padded to its widest crop. Only one
// batch's pixels are held at a time, and each batch is released before the next is materialized.
jpdf_status Engine::recognize_crops(const Image& image, const std::vector<CropPlan>& plans,
                                    std::vector<Recognition>* out, std::string* detail) const {
    out->assign(plans.size(), Recognition{});
    const std::vector<size_t> order = crop_order(plans);
    const RecognizerParams& rp = profile_.rec;
    const size_t batch = static_cast<size_t>(rp.batch_size);
    std::vector<float> tensor, probs;
    std::vector<int64_t> shape;
    std::vector<Image> crops;
    for (size_t start = 0; start < order.size(); start += batch) {
        const size_t n = std::min(batch, order.size() - start);
        crops.assign(n, Image{});
        int width = 0;
        for (size_t i = 0; i < n; ++i) {
            if (!materialize_crop(image, plans[order[start + i]], &crops[i])) {
                *detail = "crop geometry changed between planning and materialization";
                return JPDF_E_FORMAT;
            }
            width = std::max(width, recognizer_width(crops[i], rp));
        }
        const size_t per_image = static_cast<size_t>(3) * rp.height * width;
        tensor.assign(per_image * n, 0.f);
        for (size_t i = 0; i < n; ++i) {
            recognizer_input(crops[i], rp, width, tensor.data() + per_image * i);
        }
        jpdf_status st = recognizer_->run(
            tensor.data(), {static_cast<int64_t>(n), 3, rp.height, width}, &probs, &shape, detail);
        if (st != JPDF_OK) return st;
        if (shape.size() != 3 || shape[0] != static_cast<int64_t>(n) ||
            shape[2] != static_cast<int64_t>(profile_.characters.size())) {
            *detail = "recognizer output does not match the profile's dictionary";
            return JPDF_E_FORMAT;
        }
        const int steps = static_cast<int>(shape[1]);
        const int classes = static_cast<int>(shape[2]);
        for (size_t i = 0; i < n; ++i) {
            (*out)[order[start + i]] =
                ctc_decode(probs.data() + i * static_cast<size_t>(steps) * classes, steps, classes,
                           profile_.characters);
        }
        crops.clear();  // release this batch's pixels before the next is materialized
    }
    return JPDF_OK;
}

}  // namespace jpdf::ocr

// --- ort_session.cpp (merged) ---
// ort_session.cpp - ONNX Runtime C API calls. One environment per process, created lazily.

#include <onnxruntime_c_api.h>

#include <mutex>

#include "engine.h"

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
