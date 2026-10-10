// output_transaction.h - staged, bounded, atomically published file output.
#pragma once

#include <cstdint>
#include <filesystem>
#include <memory>

#include "jpdfium_api.h"

namespace jpdf {

// created -> writing -> finalized -> validated -> published; any failure before publish aborts
// and removes the staging file, leaving the destination byte-for-byte untouched.
class OutputTransaction {
   public:
    enum class Stage { Created, Writing, Finalized, Validated, Published, Aborted };

    // Stages beside the symlink-resolved destination so publication is a same-directory rename.
    static jpdf_status begin(const char* destination_utf8, bool durable, uint64_t max_bytes,
                             std::unique_ptr<OutputTransaction>* out) noexcept;

    jpdf_status write(const uint8_t* data, size_t size) noexcept;
    jpdf_status finalize() noexcept;
    jpdf_status validate() noexcept;
    // Atomic replace only: rename(2) or MoveFileExW. There is no non-atomic fallback.
    jpdf_status publish() noexcept;
    void abort() noexcept;

    ~OutputTransaction();
    OutputTransaction(const OutputTransaction&) = delete;
    OutputTransaction& operator=(const OutputTransaction&) = delete;

    Stage stage() const noexcept {
        return stage_;
    }
    uint64_t bytes_written() const noexcept {
        return bytes_written_;
    }
    const std::filesystem::path& staging_path() const noexcept {
        return staging_;
    }

   private:
    OutputTransaction() = default;

    std::filesystem::path destination_;
    std::filesystem::path staging_;
    int fd_ = -1;
    bool durable_ = false;
    uint64_t max_bytes_ = 0;
    uint64_t bytes_written_ = 0;
    Stage stage_ = Stage::Created;
};

std::filesystem::path path_from_utf8(const char* utf8);

}  // namespace jpdf
