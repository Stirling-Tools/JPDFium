// paddle.h - PaddleOCR 3.7's general OCR pipeline steps around the two ONNX models.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "cv_compat.h"

namespace jpdf::ocr {

struct DetectorParams {
    int limit_side_len = 64;
    bool limit_type_min = true;
    int max_side_limit = 4000;
    double thresh = 0.3;
    double box_thresh = 0.6;
    double unclip_ratio = 1.5;
    int max_candidates = 3000;
};

struct RecognizerParams {
    int height = 48;
    int min_width = 320;
    int max_width = 3200;
    int batch_size = 6;
};

// An interleaved 3-channel 8-bit image in BGR order, as cv2.imread returns it.
struct Image {
    std::vector<uint8_t> pixels;
    int width = 0;
    int height = 0;
};

// The float CHW tensor the detector reads, and how to map its output back to the image.
struct DetectorInput {
    std::vector<float> tensor;
    int width = 0;
    int height = 0;
};

// Integer corners, top-left, top-right, bottom-right, bottom-left.
using Quad = std::array<Point, 4>;

struct Detection {
    Quad box;
    float score;
};

// DetResizeForTest (limit_type min/max, multiples of 32) then NormalizeImage and ToCHW.
DetectorInput prepare_detector_input(const Image& image, const DetectorParams& params);

// DBPostProcess(box_type="quad") on the probability map, boxes in source-image pixels.
std::vector<Detection> detect_boxes(const float* pred, int width, int height, int dest_width,
                                    int dest_height, const DetectorParams& params);

// SortQuadBoxes: top to bottom, then left to right within 10 px rows.
void sort_boxes(std::vector<Detection>* boxes);

// CropByPolys(quad): the min-area rectangle warped upright, turned 90 degrees if tall.
bool crop_box(const Image& image, const Quad& box, Image* out);

// The crop crop_box would produce, precomputed from the box alone so crops can be ordered before
// any pixel buffer exists. width/height are the materialized crop's, after the tall quarter turn.
struct CropPlan {
    std::array<Point2f, 4> quad;
    int width = 0;
    int height = 0;
    bool rotated = false;
};

// Fills PLAN from BOX without materializing pixels; false when crop_box would drop the crop.
bool plan_crop(const Quad& box, CropPlan* plan);

// Materializes one planned crop exactly as crop_box would.
bool materialize_crop(const Image& image, const CropPlan& plan, Image* out);

// The order crops are recognized in: ascending width/height, stable in the input order.
std::vector<size_t> crop_order(const std::vector<CropPlan>& plans);

// OCRReisizeNormImg for one crop, padded with zeros to batch_width columns.
void recognizer_input(const Image& crop, const RecognizerParams& params, int batch_width,
                      float* out);
// The width a crop occupies before batch padding.
int recognizer_width(const Image& crop, const RecognizerParams& params);

struct Recognition {
    std::string text;
    double score = 0;
};

// CTCLabelDecode: greedy, repeats merged, blanks dropped; score is the mean kept probability.
Recognition ctc_decode(const float* probs, int steps, int classes,
                       const std::vector<std::string>& characters);

// SHA-256 for verifying model profile files.
class Sha256 {
   public:
    Sha256();
    void update(const uint8_t* data, size_t len);
    std::string hex_digest();  // finishes the hash; call once

   private:
    void block(const uint8_t* p);
    uint32_t state_[8];
    uint8_t buffer_[64];
    size_t buffered_ = 0;
    uint64_t length_ = 0;
};

}  // namespace jpdf::ocr
