#ifndef JPDFIUM_STAGING_H
#define JPDFIUM_STAGING_H

#include <fcntl.h>
#include <sys/stat.h>

#include <cstdio>
#include <cstring>
#include <filesystem>
#include <random>
#include <stdexcept>
#include <string>
#if defined(_WIN32)
#include <io.h>
#include <share.h>
#include <windows.h>
#else
#include <unistd.h>
#endif

namespace jpdfium_staging {

// Creates a unique sibling of out_path with an exclusive, owner-only create, so a
// planted or aliased path cannot truncate anything while qpdf streams the input.
inline std::string CreateUniqueSibling(const std::string& out_path) {
    const std::filesystem::path target(out_path);
    const std::filesystem::path dir =
        target.has_parent_path() ? target.parent_path() : std::filesystem::path(".");
    const std::string stem = target.filename().string();
    std::random_device rd;
    std::mt19937_64 rng(rd());
    for (int attempt = 0; attempt < 64; ++attempt) {
        const std::string candidate =
            (dir / (stem + "." + std::to_string(rng()) + ".jpdfium-tmp")).string();
#if defined(_WIN32)
        int fd = -1;
        if (::_sopen_s(&fd, candidate.c_str(), _O_WRONLY | _O_CREAT | _O_EXCL | _O_NOINHERIT,
                       _SH_DENYRW, _S_IREAD | _S_IWRITE) != 0) {
            continue;
        }
        ::_close(fd);
#else
        const int fd = ::open(candidate.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
        if (fd < 0) continue;
        ::close(fd);
#endif
        return candidate;
    }
    throw std::runtime_error("cannot create a temporary output file");
}

// Preserves an existing destination's mode; a brand-new file keeps the mode
// created by the normal umask-aware 0666 staging file.
inline void ApplyPublishMode(const std::string& staging, const char* destination) {
#ifndef _WIN32
    struct stat st;
    std::memset(&st, 0, sizeof(st));
    if (::stat(destination, &st) == 0) {
        ::chmod(staging.c_str(), st.st_mode & 07777);
    } else {
        const mode_t mask = ::umask(0);
        ::umask(mask);
        ::chmod(staging.c_str(), static_cast<mode_t>(0666 & ~mask));
    }
#else
    (void)staging;
    (void)destination;
#endif
}

// Publishes the staging file over the destination. The staging file survives a
// failed publish so a written document is never lost.
inline bool PublishStaged(const std::string& staging, const char* destination) {
#if defined(_WIN32)
    // MoveFileEx replaces an existing destination atomically. std::rename
    // cannot replace on Windows, so a fallback would only hide the failure.
    if (::MoveFileExA(staging.c_str(), destination,
                      MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        return true;
    }
#else
    if (std::rename(staging.c_str(), destination) == 0) return true;
#endif
    // Keep the staging file so a fully written document is never lost, and
    // make the retained path visible to the caller's logs.
    std::fprintf(stderr, "jpdfium: failed to publish %s over %s; staging file retained\n",
                 staging.c_str(), destination);
    return false;
}

}  // namespace jpdfium_staging

#endif  // JPDFIUM_STAGING_H
