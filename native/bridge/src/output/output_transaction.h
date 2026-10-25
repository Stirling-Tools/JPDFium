// output_transaction.h - staged, bounded, atomically published file output.
#pragma once

#include <cstdint>
#include <filesystem>
#include <memory>

#include "jpdfium_api.h"

namespace jpdf {

// created -> writing -> finalized -> validated -> published; any failure before publish aborts
// and removes the staging file, leaving the destination byte-for-byte untouched.
//
// Publication replaces the destination directory entry. If the destination is a hard link, has
// extra ACLs/xattrs, or is owned by another user/group, those properties do not transfer to the
// new inode; only the permission bits of an existing regular file are mirrored. On macOS,
// durable mode uses F_FULLFSYNC so the file data reaches stable storage, not just fsync's cache.
class OutputTransaction {
   public:
    enum class Stage : std::uint8_t { Created, Writing, Finalized, Validated, Published, Aborted };

    // Stages beside the symlink-resolved destination so publication is a same-directory rename.
    static jpdf_status begin(const char* destination_utf8, bool durable, uint64_t max_bytes,
                             std::unique_ptr<OutputTransaction>* out) noexcept;

    jpdf_status write(const uint8_t* data, size_t size) noexcept;
    jpdf_status finalize() noexcept;
    jpdf_status validate() noexcept;
    // Atomic replace only: rename(2) or MoveFileExW. There is no non-atomic fallback. When the
    // replacement lands but the durability sync of the directory cannot be confirmed the
    // destination already holds the new bytes; publish() then returns JPDF_E_UNSYNCED, not a
    // pre-publication failure, so callers must not treat every non-zero status as "unchanged".
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
    // Resolved once at begin(); publish() must not allocate a path inside a noexcept function.
    std::filesystem::path parent_;
    std::filesystem::path staging_;
    int fd_ = -1;
    bool durable_ = false;
    uint64_t max_bytes_ = 0;
    uint64_t bytes_written_ = 0;
    Stage stage_ = Stage::Created;
};

std::filesystem::path path_from_utf8(const char* utf8);

}  // namespace jpdf
