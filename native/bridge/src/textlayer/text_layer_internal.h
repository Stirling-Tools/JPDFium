// text_layer_internal.h - the surface shared by the split text layer translation units.
// Split out of the former text_layer.cpp.
#pragma once

#include "text_layer.h"

namespace jpdf {

// Compile-time limits for the caller arrays and the serialized page. Kept whole here so the
// parser and the serializer build from one definition; the trailing comments are a single
// clang-format alignment unit and would reflow if the block were split across the units.
constexpr int32_t kMaxLines = 1 << 20;
constexpr int32_t kMaxWords = 1 << 21;
constexpr uint64_t kMaxTextBytes = uint64_t{16} << 20;
constexpr int32_t kMaxImageSide = 1 << 20;
constexpr double kMinDpi = 1.0;
constexpr double kMaxDpi = 100000.0;
constexpr double kMaxCoordinate = 1e7;  // pixels
constexpr double kMinFontSize = 1e-4;   // points
constexpr double kMaxTz = 1e9;
constexpr size_t kMaxCids = 0xFFFF;  // two-byte Identity-H codes; CID 0 stays .notdef
constexpr int32_t kDeflateLevel = 6;
constexpr long long kScale = 10000;        // numbers are written with up to four decimals
constexpr long long kUnitScale = 1000000;  // matrix directions get six decimals
constexpr double kMinQuadLength = 1e-4;    // points
constexpr double kMinShear = 0.1;          // sine of the angle between a quad's baseline and sides

// text_layer.h declares TextLayer and the three entry points below; every unit includes this
// header so the parser, the serializer and the font builder agree on one definition. A helper
// used by more than one unit belongs here as an inline function; as split, each helper moved
// out of text_layer.cpp is used by a single unit and stays in that unit's anonymous namespace.

// Defined in text_layer_parse.cpp.
jpdf_status parse_text_layer(const jpdf_text_layer_options* options, const jpdf_text_line* lines,
                             int32_t line_count, const jpdf_text_word* words, int32_t word_count,
                             TextLayer* out);

// Defined in text_layer_pdf.cpp.
jpdf_status build_text_layer_pdf(const TextLayer& layer, std::string* out);

// Defined in text_layer_font.cpp; built once behind a function-local static.
const std::vector<uint8_t>& glyphless_font();

}  // namespace jpdf
