// output_transaction.cpp - native port of the Java OutputTransaction contract.

#include "output_transaction.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <csignal>
#include <cstddef>
#include <limits>
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
    const errno_t rc =
        ::_wsopen_s(&fd, path.c_str(), _O_WRONLY | _O_CREAT | _O_EXCL | _O_BINARY | _O_NOINHERIT,
                    _SH_DENYRW, _S_IREAD | _S_IWRITE);
    if (rc != 0) {
        // _wsopen_s reports through its return value and does not set errno.
        errno = rc;
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
#elif defined(__APPLE__)
    // fsync on macOS does not guarantee the platter cache is flushed; F_FULLFSYNC does. Fall
    // back to fsync on filesystems that do not implement the fcntl.
    if (::fcntl(fd, F_FULLFSYNC) == 0) return 0;
    return ::fsync(fd);
#else
    return ::fsync(fd);
#endif
}

bool process_alive(uint64_t pid) noexcept {
    if (pid == 0) return false;
#if defined(_WIN32)
    HANDLE process =
        ::OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, static_cast<DWORD>(pid));
    if (!process) return ::GetLastError() != ERROR_INVALID_PARAMETER;
    DWORD code = 0;
    const bool alive = ::GetExitCodeProcess(process, &code) && code == STILL_ACTIVE;
    ::CloseHandle(process);
    return alive;
#else
    if (pid > static_cast<uint64_t>(std::numeric_limits<pid_t>::max())) return true;
    if (::kill(static_cast<pid_t>(pid), 0) == 0) return true;
    return errno == EPERM;
#endif
}

// Best-effort removal of staging files left by a process that crashed before abort() could run.
// Only names belonging to this destination are touched, and a live owner is always left alone.
void remove_stale_staging(const fs::path& parent, const fs::path& destination) noexcept {
    try {
        std::error_code ec;
        const fs::path::string_type name = destination.filename().native();
        const fs::path::string_type marker = fs::path(".jpdf-").native();
        const fs::path::string_type suffix = fs::path(".tmp").native();
        for (fs::directory_iterator it(parent, ec), end; !ec && it != end; it.increment(ec)) {
            const fs::path::string_type candidate = it->path().filename().native();
            const size_t start = 1 + name.size() + marker.size();
            if (candidate.size() <= start + suffix.size() || candidate[0] != '.' ||
                candidate.compare(1, name.size(), name) != 0 ||
                candidate.compare(1 + name.size(), marker.size(), marker) != 0 ||
                candidate.compare(candidate.size() - suffix.size(), suffix.size(), suffix) != 0) {
                continue;
            }
            uint64_t pid = 0;
            size_t pos = start;
            bool digits = false;
            while (pos < candidate.size() && candidate[pos] >= '0' && candidate[pos] <= '9') {
                pid = pid * 10 + static_cast<uint64_t>(candidate[pos] - '0');
                ++pos;
                digits = true;
            }
            if (!digits || pos >= candidate.size() || candidate[pos] != '-') continue;
            if (process_alive(pid)) continue;
            fs::remove(it->path(), ec);
            ec.clear();
        }
    } catch (...) {
        // Cleanup is opportunistic; never fail the transaction because of a stale file.
    }
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
    struct stat st;
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
        remove_stale_staging(parent, destination);

        static std::atomic<unsigned> counter{0};
        auto tx = std::unique_ptr<OutputTransaction>(new OutputTransaction());
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
    } catch (const std::exception& e) {
        // Best effort: a stricter mode is safer than failing the output.
        (void)e;
    } catch (...) {
        (void)0;
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
        const DWORD error = ::GetLastError();
        try {
            return fail(JPDF_E_IO, "atomic replace of the destination failed",
                        ": " + std::system_category().message(static_cast<int>(error)));
        } catch (...) {
            return fail(JPDF_E_IO, "atomic replace of the destination failed");
        }
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
