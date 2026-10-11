// engine.cpp - the PaddleOCR 3.7 general OCR pipeline: detect, sort, crop, recognize.

#include "engine.h"

#include <algorithm>
#include <numeric>

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
        std::vector<Image> crops;
        for (const Detection& d : boxes) {
            Image crop;
            if (!crop_box(image, d.box, &crop)) continue;  // empty crops are dropped, as upstream
            crops.push_back(std::move(crop));
            kept.push_back(d);
        }
        if ((st = recognize_crops(crops, &texts, detail)) != JPDF_OK) return st;
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

// Crops sorted by aspect ratio, then batched; each batch is padded to its widest crop.
jpdf_status Engine::recognize_crops(const std::vector<Image>& crops, std::vector<Recognition>* out,
                                    std::string* detail) const {
    out->assign(crops.size(), Recognition{});
    std::vector<size_t> order(crops.size());
    std::iota(order.begin(), order.end(), size_t{0});
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return crops[a].width / static_cast<double>(crops[a].height) <
               crops[b].width / static_cast<double>(crops[b].height);
    });
    const RecognizerParams& rp = profile_.rec;
    const size_t batch = static_cast<size_t>(rp.batch_size);
    std::vector<float> tensor, probs;
    std::vector<int64_t> shape;
    for (size_t start = 0; start < order.size(); start += batch) {
        const size_t n = std::min(batch, order.size() - start);
        int width = 0;
        for (size_t i = 0; i < n; ++i)
            width = std::max(width, recognizer_width(crops[order[start + i]], rp));
        const size_t per_image = static_cast<size_t>(3) * rp.height * width;
        tensor.assign(per_image * n, 0.f);
        for (size_t i = 0; i < n; ++i) {
            recognizer_input(crops[order[start + i]], rp, width, tensor.data() + per_image * i);
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
    }
    return JPDF_OK;
}

}  // namespace jpdf::ocr
