// sha256.h - SHA-256 for verifying model profile files.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace jpdf::ocr {

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
