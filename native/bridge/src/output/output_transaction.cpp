// output_transaction.cpp - native port of the Java OutputTransaction contract.

#include "output_transaction.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <memory>
#include <string>
#include <string_view>
#include <system_error>

#if defined(_WIN32)
#include <fcntl.h>
#include <io.h>
#include <share.h>
#include <sys/stat.h>
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#include "image_writer.h"
#include "runtime/api_status.h"

namespace jpdf {

namespace fs = std::filesystem;

namespace {

constexpr int kMaxSymlinkHops = 32;
constexpr int kStagingAttempts = 32;

jpdf_status io_fail(jpdf_status status, std::string_view what, int err) noexcept {
    try {
        return fail(status, what, ": " + std::error_code(err, std::generic_category()).message());
    } catch (...) {
        return fail(status, what);
    }
}

jpdf_status io_fail(std::string_view what, int err) noexcept {
    return io_fail(JPDF_E_IO, what, err);
}

long process_id() noexcept {
#if defined(_WIN32)
    return static_cast<long>(GetCurrentProcessId());
#else
    return static_cast<long>(::getpid());
#endif
}

// Exclusive, no-follow creation: a planted file or symlink makes the open fail, never truncate.
int open_exclusive(const fs::path& path) noexcept {
#if defined(_WIN32)
    int fd = -1;
    if (::_wsopen_s(&fd, path.c_str(), _O_WRONLY | _O_CREAT | _O_EXCL | _O_BINARY | _O_NOINHERIT,
                    _SH_DENYRW, _S_IREAD | _S_IWRITE) != 0) {
        return -1;
    }
    return fd;
#else
    return ::open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
#endif
}

int close_fd(int fd) noexcept {
#if defined(_WIN32)
    return ::_close(fd);
#else
    return ::close(fd);
#endif
}

int sync_fd(int fd) noexcept {
#if defined(_WIN32)
    return ::_commit(fd);
#else
    return ::fsync(fd);
#endif
}

fs::path resolve_symlinks(const fs::path& destination, std::error_code& ec) {
    ec.clear();
    fs::path current = destination;
    for (int hop = 0; hop < kMaxSymlinkHops; ++hop) {
        // A missing destination is normal (new file), so its lstat error is not a failure.
        std::error_code probe;
        if (!fs::is_symlink(fs::symlink_status(current, probe))) return current;
        fs::path link = fs::read_symlink(current, ec);
        if (ec) return current;
        current = link.is_absolute() ? link : current.parent_path() / link;
    }
    ec = std::make_error_code(std::errc::too_many_symbolic_link_levels);
    return current;
}

#if !defined(_WIN32)
// Publish with the mode a direct write would have produced when the destination
// exists; a new destination keeps the restrictive 0600 staging mode. Learning
// the umask default from a probe file would need a 0666 creation, which is a
// world-writable-file finding, so no probe is used.
void mirror_destination_mode(int fd, const fs::path& destination) {
    struct stat st {};
    if (::stat(destination.c_str(), &st) == 0) {
        // A direct write clears setuid/setgid; do not carry them onto new content.
        ::fchmod(fd, st.st_mode & 01777);
    }
}
#endif

}  // namespace

fs::path path_from_utf8(const char* utf8) {
    const auto* bytes = reinterpret_cast<const char8_t*>(utf8);
    return fs::path(std::u8string_view(bytes));
}

jpdf_status OutputTransaction::begin(const char* destination_utf8, bool durable, uint64_t max_bytes,
                                     std::unique_ptr<OutputTransaction>* out) noexcept {
    try {
        if (!destination_utf8 || !*destination_utf8 || !out) {
            return fail(JPDF_E_INVALID_ARGUMENT, "output path is empty");
        }
        std::error_code ec;
        fs::path requested = fs::absolute(path_from_utf8(destination_utf8), ec);
        if (ec) return io_fail("cannot resolve output path", ec.value());
        fs::path destination = resolve_symlinks(requested, ec);
        if (ec) return io_fail("cannot resolve output symlinks", ec.value());
        if (fs::is_directory(destination, ec)) {
            return fail(JPDF_E_INVALID_ARGUMENT, "output path is a directory");
        }
        fs::path parent = destination.parent_path();
        if (!fs::is_directory(parent, ec)) {
            return fail(JPDF_E_IO, "output directory does not exist");
        }

        static std::atomic<unsigned> counter{0};
        std::unique_ptr<OutputTransaction> tx(new OutputTransaction());
        tx->destination_ = destination;
        tx->parent_ = parent;
        tx->durable_ = durable;
        tx->max_bytes_ = max_bytes;
        for (int attempt = 0; attempt < kStagingAttempts; ++attempt) {
            // A stale leftover from a crashed run can collide; retry with a fresh name.
            const unsigned n = counter.fetch_add(1, std::memory_order_relaxed);
            fs::path name = destination.filename();
            name += ".jpdf-" + std::to_string(process_id()) + "-" + std::to_string(n) + ".tmp";
            fs::path staging = parent / fs::path(".").concat(name.native());
            int fd = open_exclusive(staging);
            if (fd >= 0) {
                tx->staging_ = std::move(staging);
                tx->fd_ = fd;
                tx->stage_ = Stage::Writing;
                *out = std::move(tx);
                return JPDF_OK;
            }
            if (errno != EEXIST) return io_fail("cannot create staging file", errno);
        }
        return fail(JPDF_E_IO, "could not create a unique staging file");
    } catch (const std::bad_alloc&) {
        return fail(JPDF_E_NO_MEMORY, "output transaction allocation failed");
    } catch (...) {
        return fail(JPDF_E_INTERNAL, "output transaction setup failed");
    }
}

jpdf_status OutputTransaction::write(const uint8_t* data, size_t size) noexcept {
    if (stage_ != Stage::Writing) return fail(JPDF_E_INTERNAL, "write outside the writing stage");
    if (size == 0) return JPDF_OK;
    if (!data) return fail(JPDF_E_INVALID_ARGUMENT, "null output block");
    // Subtraction form so the running total cannot overflow.
    if (max_bytes_ > 0 && (bytes_written_ >= max_bytes_ ||
                           static_cast<uint64_t>(size) > max_bytes_ - bytes_written_)) {
        return fail(JPDF_E_LIMIT, "output exceeded max_bytes");
    }
    size_t done = 0;
    while (done < size) {
        const size_t chunk = std::min<size_t>(size - done, 1u << 30);
#if defined(_WIN32)
        int n = ::_write(fd_, data + done, static_cast<unsigned>(chunk));
#else
        ssize_t n = ::write(fd_, data + done, chunk);
        if (n < 0 && errno == EINTR) continue;
#endif
        if (n <= 0) return io_fail("write to staging file failed", n < 0 ? errno : EIO);
        done += static_cast<size_t>(n);
    }
    bytes_written_ += size;
    return JPDF_OK;
}

jpdf_status OutputTransaction::finalize() noexcept {
    if (stage_ != Stage::Writing) return fail(JPDF_E_INTERNAL, "finalize outside writing");
#if !defined(_WIN32)
    try {
        mirror_destination_mode(fd_, destination_);
    } catch (...) {
        // Best effort: a stricter mode is safer than failing the output.
    }
#endif
    // Sync after the mode change so the durable fsync also covers the fchmod metadata.
    if (durable_ && sync_fd(fd_) != 0) return io_fail("flushing staging file failed", errno);
    const int fd = fd_;
    fd_ = -1;
    if (close_fd(fd) != 0) return io_fail("closing staging file failed", errno);
    stage_ = Stage::Finalized;
    return JPDF_OK;
}

jpdf_status OutputTransaction::validate() noexcept {
    if (stage_ != Stage::Finalized) return fail(JPDF_E_INTERNAL, "validate before finalize");
    if (bytes_written_ == 0) return fail(JPDF_E_IO, "output is empty");
    std::error_code ec;
    const uintmax_t on_disk = fs::file_size(staging_, ec);
    if (ec) return io_fail("cannot stat staging file", ec.value());
    if (on_disk != bytes_written_) return fail(JPDF_E_IO, "staging file size does not match");
    stage_ = Stage::Validated;
    return JPDF_OK;
}

jpdf_status OutputTransaction::publish() noexcept {
    if (stage_ != Stage::Validated) return fail(JPDF_E_INTERNAL, "publish before validate");
#if defined(_WIN32)
    DWORD flags = MOVEFILE_REPLACE_EXISTING | (durable_ ? MOVEFILE_WRITE_THROUGH : 0);
    if (!::MoveFileExW(staging_.c_str(), destination_.c_str(), flags)) {
        return fail(JPDF_E_IO, "atomic replace of the destination failed");
    }
#else
    if (::rename(staging_.c_str(), destination_.c_str()) != 0) {
        return io_fail("atomic rename onto the destination failed", errno);
    }
    if (durable_) {
        // Persist the directory entry so the rename itself survives a crash.
        const int dfd = ::open(parent_.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        const int err = dfd < 0 ? errno : (::fsync(dfd) != 0 ? errno : 0);
        if (dfd >= 0) ::close(dfd);
        if (err != 0) {
            // The rename already landed, so the destination holds the new bytes; only the
            // guarantee that the entry survives a crash is missing. Report a distinct status so
            // callers do not read it as a pre-publication failure.
            stage_ = Stage::Published;
            return io_fail(JPDF_E_UNSYNCED, "published, but syncing the directory failed", err);
        }
    }
#endif
    stage_ = Stage::Published;
    return JPDF_OK;
}

void OutputTransaction::abort() noexcept {
    if (stage_ == Stage::Published || stage_ == Stage::Aborted) return;
    if (fd_ >= 0) {
        close_fd(fd_);
        fd_ = -1;
    }
    if (!staging_.empty()) {
        std::error_code ec;
        fs::remove(staging_, ec);
    }
    stage_ = Stage::Aborted;
}

OutputTransaction::~OutputTransaction() {
    abort();
}

}  // namespace jpdf

// --- image_writer.cpp (merged) ---
// image_writer.cpp - PNG and document output through an OutputTransaction.

namespace jpdf {

namespace {

struct SinkContext {
    OutputTransaction* tx;
    jpdf_status status;
};

int32_t transaction_sink(void* ctx, const uint8_t* data, size_t len) noexcept {
    auto* sink = static_cast<SinkContext*>(ctx);
    sink->status = sink->tx->write(data, len);
    return sink->status == JPDF_OK ? 0 : 1;
}

// Pillow's formula, int(dpi / 0.0254 + 0.5), so pHYs matches the OCRmyPDF reference exactly.
uint32_t pixels_per_meter(double dpi) noexcept {
    if (!std::isfinite(dpi) || dpi <= 0 || dpi > 1e6) return 0;
    return static_cast<uint32_t>(dpi / 0.0254 + 0.5);
}

}  // namespace

jpdf_status parse_write_options(const jpdf_write_options* options, WriteRequest* out) noexcept {
    *out = WriteRequest{};
    if (!options) return JPDF_OK;
    jpdf_status st = check_input_struct(options, options->struct_size, sizeof(jpdf_write_options),
                                        sizeof(jpdf_write_options), "jpdf_write_options");
    if (st != JPDF_OK) return st;
    if ((options->flags & ~JPDF_WRITE_DURABLE) != 0) {
        return fail(JPDF_E_UNSUPPORTED, "unknown jpdf_write_options.flags bits");
    }
    if (options->image_format != JPDF_IMAGE_PNG) {
        return fail(JPDF_E_UNSUPPORTED, "only JPDF_IMAGE_PNG output is implemented");
    }
    if (options->png_compression < -1 || options->png_compression > 9) {
        return fail(JPDF_E_INVALID_ARGUMENT, "png_compression must be -1 or 0-9");
    }
    out->durable = (options->flags & JPDF_WRITE_DURABLE) != 0;
    out->image_format = options->image_format;
    out->png_compression = options->png_compression < 0 ? 6 : options->png_compression;
    out->max_bytes = options->max_bytes;
    return JPDF_OK;
}

jpdf_status parse_document_write_options(const jpdf_write_options* options,
                                         WriteRequest* out) noexcept {
    *out = WriteRequest{};
    if (!options) return JPDF_OK;
    jpdf_status st = check_input_struct(options, options->struct_size, sizeof(jpdf_write_options),
                                        sizeof(jpdf_write_options), "jpdf_write_options");
    if (st != JPDF_OK) return st;
    if ((options->flags & ~JPDF_WRITE_DURABLE) != 0) {
        return fail(JPDF_E_UNSUPPORTED, "unknown jpdf_write_options.flags bits");
    }
    if (options->image_format != 0 || options->png_compression != 0) {
        return fail(JPDF_E_UNSUPPORTED, "image_format and png_compression must be 0 here");
    }
    out->durable = (options->flags & JPDF_WRITE_DURABLE) != 0;
    out->max_bytes = options->max_bytes;
    return JPDF_OK;
}

jpdf_status write_bytes_file(const uint8_t* data, size_t size, const char* path_utf8,
                             const WriteRequest& request) noexcept {
    std::unique_ptr<OutputTransaction> tx;
    jpdf_status st = OutputTransaction::begin(path_utf8, request.durable, request.max_bytes, &tx);
    if (st != JPDF_OK) return st;
    if ((st = tx->write(data, size)) != JPDF_OK) return st;
    if ((st = tx->finalize()) != JPDF_OK) return st;
    if ((st = tx->validate()) != JPDF_OK) return st;
    return tx->publish();
}

jpdf_status write_image_file(const PixelStorage& pixels, const char* path_utf8,
                             const WriteRequest& request) noexcept {
    const int32_t channels = channels_for(pixels.pixel_format);
    if (channels == 0 || !pixels.data) return fail(JPDF_E_INTERNAL, "bitmap has no pixels");
    return write_png_file(PngImage{pixels.data.get(), pixels.width, pixels.height, pixels.stride,
                                   channels, pixels.dpi_x, pixels.dpi_y},
                          path_utf8, request);
}

jpdf_status write_png_file(const PngImage& image, const char* path_utf8,
                           const WriteRequest& request) noexcept {
    if (!image.data) return fail(JPDF_E_INTERNAL, "image has no pixels");
    std::unique_ptr<OutputTransaction> tx;
    jpdf_status st = OutputTransaction::begin(path_utf8, request.durable, request.max_bytes, &tx);
    if (st != JPDF_OK) return st;

    SinkContext sink{tx.get(), JPDF_OK};
    const int32_t rc =
        jpdfium_rust_write_png(image.data, image.width, image.height, image.stride, image.channels,
                               pixels_per_meter(image.dpi_x), pixels_per_meter(image.dpi_y),
                               request.png_compression, transaction_sink, &sink);
    if (rc != 0) {
        // The sink already recorded why it stopped (I/O or max_bytes); keep that cause.
        if (sink.status != JPDF_OK) return sink.status;
        return fail(JPDF_E_INTERNAL, "PNG encoder failed");
    }
    if ((st = tx->finalize()) != JPDF_OK) return st;
    if ((st = tx->validate()) != JPDF_OK) return st;
    return tx->publish();
}

}  // namespace jpdf
