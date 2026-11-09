#ifndef JPDFIUM_STAGING_H
#define JPDFIUM_STAGING_H

#include <fcntl.h>
#include <sys/stat.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <random>
#include <stdexcept>
#include <string>
#include <system_error>
#if defined(_WIN32)
#include <io.h>
#include <share.h>
#include <windows.h>
#else
#include <unistd.h>
#endif

namespace jpdfium_staging {

inline std::filesystem::path NativePath(const std::string& utf8) {
    return std::filesystem::path(std::u8string(utf8.begin(), utf8.end()));
}

inline std::string Utf8(const std::filesystem::path& path) {
    const std::u8string text = path.u8string();
    return std::string(text.begin(), text.end());
}

struct StagedFile {
    std::string path;
    FILE* file = nullptr;
};

// Creates a unique sibling of out_path with an exclusive, owner-only create that
// refuses a symlink, and keeps it open so it is never reopened by name.
inline void OpenUniqueSibling(const std::string& out_path, StagedFile* staged) {
    const std::filesystem::path target = NativePath(out_path);
    const std::filesystem::path dir =
        target.has_parent_path() ? target.parent_path() : std::filesystem::path(".");
    const std::u8string stem = target.filename().u8string();
    std::random_device rd;
    for (int attempt = 0; attempt < 64; ++attempt) {
        const uint64_t token = (static_cast<uint64_t>(rd()) << 32) | rd();
        const std::string suffix = "." + std::to_string(token) + ".jpdfium-tmp";
        const std::filesystem::path candidate =
            dir / (stem + std::u8string(suffix.begin(), suffix.end()));
#if defined(_WIN32)
        int fd = -1;
        if (::_wsopen_s(&fd, candidate.c_str(),
                        _O_WRONLY | _O_CREAT | _O_EXCL | _O_NOINHERIT | _O_BINARY, _SH_DENYRW,
                        _S_IREAD | _S_IWRITE) != 0) {
            continue;
        }
        FILE* file = ::_fdopen(fd, "wb");
        if (!file) {
            ::_close(fd);
            std::error_code ec;
            std::filesystem::remove(candidate, ec);
            continue;
        }
#else
        const int fd = ::open(candidate.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0600);
        if (fd < 0) continue;
        FILE* file = ::fdopen(fd, "wb");
        if (!file) {
            ::close(fd);
            std::error_code ec;
            std::filesystem::remove(candidate, ec);
            continue;
        }
#endif
        staged->path = Utf8(candidate);
        staged->file = file;
        return;
    }
    throw std::runtime_error("cannot create a temporary output file");
}

inline void DiscardStaged(StagedFile* staged) {
    if (staged->file) {
        std::fclose(staged->file);
        staged->file = nullptr;
    }
    if (!staged->path.empty()) {
        std::error_code ec;
        std::filesystem::remove(NativePath(staged->path), ec);
    }
}

#ifndef _WIN32
// A new destination gets the mode a plain create in its directory would give it,
// less world write.
// That is measured on a throwaway file rather than by reading the umask, because
// reading the umask means setting it process-wide while other threads create files.
inline mode_t NewFileMode(const std::filesystem::path& dir) {
    std::random_device rd;
    for (int attempt = 0; attempt < 64; ++attempt) {
        const uint64_t token = (static_cast<uint64_t>(rd()) << 32) | rd();
        const std::filesystem::path probe = dir / (".jpdfium-mode." + std::to_string(token));
        const int fd = ::open(probe.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, 0664);
        if (fd < 0) {
            if (errno == EEXIST) continue;
            break;
        }
        struct stat st;
        std::memset(&st, 0, sizeof(st));
        const bool measured = ::fstat(fd, &st) == 0;
        ::close(fd);
        ::unlink(probe.c_str());
        if (measured) return st.st_mode & 07777;
        break;
    }
    return 0600;
}

inline mode_t PublishMode(const char* destination) {
    struct stat st;
    std::memset(&st, 0, sizeof(st));
    if (::stat(destination, &st) == 0) return st.st_mode & 07777;
    const std::filesystem::path target = NativePath(destination);
    return NewFileMode(target.has_parent_path() ? target.parent_path()
                                                : std::filesystem::path("."));
}
#endif

inline bool ApplyPublishModeTo(FILE* file, const char* destination) {
#ifndef _WIN32
    return ::fchmod(::fileno(file), PublishMode(destination)) == 0;
#else
    (void)file;
    (void)destination;
    return true;
#endif
}

inline bool PublishStagedFile(StagedFile* staged, const char* destination, std::string* error) {
    FILE* file = staged->file;
    staged->file = nullptr;
    if (std::fflush(file) != 0) {
        std::fclose(file);
        *error = "cannot flush the staging file";
        return false;
    }
#if defined(_WIN32)
    struct _stat64 own;
    const bool sized = ::_fstat64(::_fileno(file), &own) == 0 && own.st_size > 0;
    std::fclose(file);
    if (!sized) {
        *error = "the staging file is empty";
        return false;
    }
    // MoveFileEx replaces an existing destination atomically; std::rename cannot.
    if (!::MoveFileExW(NativePath(staged->path).c_str(), NativePath(destination).c_str(),
                       MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        *error = "cannot publish the staging file";
        return false;
    }
    return true;
#else
    struct stat own;
    struct stat entry;
    struct stat after;
    const int fd = ::fileno(file);
    if (::fstat(fd, &own) != 0 || own.st_size == 0) {
        std::fclose(file);
        *error = "the staging file is empty";
        return false;
    }
    if (::lstat(staged->path.c_str(), &entry) != 0 || !S_ISREG(entry.st_mode) ||
        entry.st_dev != own.st_dev || entry.st_ino != own.st_ino) {
        std::fclose(file);
        *error = "the staging file was replaced before publication";
        return false;
    }
    if (std::rename(staged->path.c_str(), destination) != 0) {
        std::fclose(file);
        *error = "cannot publish the staging file";
        return false;
    }
    const bool same = ::lstat(destination, &after) == 0 && after.st_dev == own.st_dev &&
                      after.st_ino == own.st_ino;
    std::fclose(file);
    if (!same) {
        *error = "the destination was replaced during publication";
        return false;
    }
    return true;
#endif
}

template <typename Write>
bool WriteAndPublish(const char* destination, Write&& write, std::string* error) {
    StagedFile staged;
    try {
        OpenUniqueSibling(destination, &staged);
        write(staged);
        if (std::fflush(staged.file) != 0) {
            throw std::runtime_error("cannot flush the staging file");
        }
        if (!ApplyPublishModeTo(staged.file, destination)) {
            throw std::runtime_error("cannot set the permissions of the staging file");
        }
    } catch (...) {
        DiscardStaged(&staged);
        throw;
    }
    if (!PublishStagedFile(&staged, destination, error)) {
        DiscardStaged(&staged);
        return false;
    }
    return true;
}

}  // namespace jpdfium_staging

#endif  // JPDFIUM_STAGING_H
