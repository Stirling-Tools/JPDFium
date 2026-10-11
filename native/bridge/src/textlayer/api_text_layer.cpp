// api_text_layer.cpp - jpdf_text_layer_write. Builds the PDF in memory, then publishes it.

#include <string>

#include "jpdfium_api.h"
#include "output/image_writer.h"
#include "runtime/api_status.h"
#include "text_layer.h"

extern "C" {

jpdf_status jpdf_text_layer_write(const jpdf_text_layer_options* options,
                                  const jpdf_text_line* lines, int32_t line_count,
                                  const jpdf_text_word* words, int32_t word_count,
                                  const char* output_path_utf8,
                                  const jpdf_write_options* write) noexcept {
    return jpdf::guarded([&] {
        if (!output_path_utf8 || !*output_path_utf8) {
            return jpdf::fail(JPDF_E_INVALID_ARGUMENT, "output path is required");
        }
        jpdf::WriteRequest request;
        jpdf_status st = jpdf::parse_document_write_options(write, &request);
        if (st != JPDF_OK) return st;
        jpdf::TextLayer layer;
        st = jpdf::parse_text_layer(options, lines, line_count, words, word_count, &layer);
        if (st != JPDF_OK) return st;
        std::string pdf;
        if ((st = jpdf::build_text_layer_pdf(layer, &pdf)) != JPDF_OK) return st;
        return jpdf::write_bytes_file(reinterpret_cast<const uint8_t*>(pdf.data()), pdf.size(),
                                      output_path_utf8, request);
    });
}

}  // extern "C"
