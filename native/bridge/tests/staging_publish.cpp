#include <cstdio>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iterator>
#include <memory>
#include <random>
#include <string>

#include "jpdfium_staging.h"

namespace {

int failures = 0;

void Expect(bool condition, const char* what) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

std::string Read(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    return std::string(std::istreambuf_iterator<char>(in), {});
}

void Write(const std::filesystem::path& path, const std::string& text) {
    std::ofstream out(path, std::ios::binary);
    out << text;
}

bool PublishText(const std::filesystem::path& dest, const std::string& text,
                 const std::function<void(const std::filesystem::path&)>& before_publish,
                 bool apply_mode) {
    const std::string destination = jpdfium_staging::Utf8(dest);
    jpdfium_staging::StagedFile staged;
    jpdfium_staging::OpenUniqueSibling(destination, &staged);
    const std::unique_ptr<jpdfium_staging::StagedFile, void (*)(jpdfium_staging::StagedFile*)>
        guard(&staged, [](jpdfium_staging::StagedFile* open) {
            if (open->file) std::fclose(open->file);
        });
    std::fwrite(text.data(), 1, text.size(), staged.file);
    if (apply_mode && !jpdfium_staging::ApplyPublishModeTo(staged.file, destination.c_str())) {
        return false;
    }
    before_publish(jpdfium_staging::NativePath(staged.path));
    std::string error;
    const bool ok = jpdfium_staging::PublishStagedFile(&staged, destination.c_str(), &error);
    if (!ok) std::filesystem::remove(jpdfium_staging::NativePath(staged.path));
    return ok;
}

bool OnlyEntry(const std::filesystem::path& dir, const std::filesystem::path& expected) {
    int count = 0;
    for (const auto& entry : std::filesystem::directory_iterator(dir)) {
        if (entry.path() != expected) return false;
        ++count;
    }
    return count == 1;
}

void PortableChecks(const std::filesystem::path& dir) {
    const auto nothing = [](const std::filesystem::path&) {};
    const std::filesystem::path dest = dir / "out.pdf";

    Expect(PublishText(dest, "generated", nothing, false), "normal publish");
    Expect(Read(dest) == "generated", "published content");

    Expect(PublishText(dest, "replaced", nothing, true), "publish over an existing file");
    Expect(Read(dest) == "replaced", "existing destination replaced");
    Expect(OnlyEntry(dir, dest), "no staging file left behind");

    const std::filesystem::path unicode = dir / std::filesystem::path(u8"résumé 文.pdf");
    std::string error;
    const bool published = jpdfium_staging::WriteAndPublish(
        jpdfium_staging::Utf8(unicode).c_str(),
        [](jpdfium_staging::StagedFile& staged) { std::fputs("unicode", staged.file); }, &error);
    Expect(published, "publish to a non-ASCII path");
    Expect(Read(unicode) == "unicode", "non-ASCII destination content");
    std::filesystem::remove(unicode);
}

#if !defined(_WIN32)
void PosixChecks(const std::filesystem::path& dir) {
    const auto nothing = [](const std::filesystem::path&) {};
    const std::filesystem::path dest = dir / "out.pdf";
    const std::filesystem::path victim = dir / "victim.txt";
    Write(victim, "keep me");
    Write(dest, "previous");
    const auto swap = [&victim](const std::filesystem::path& staging) {
        std::filesystem::remove(staging);
        std::filesystem::create_symlink(victim, staging);
    };
    Expect(!PublishText(dest, "generated", swap, false),
           "publish refuses a replaced staging entry");
    Expect(Read(victim) == "keep me", "replacement target untouched");
    Expect(Read(dest) == "previous", "destination untouched after refused publish");

    const auto mode = std::filesystem::perms::owner_read | std::filesystem::perms::owner_write |
                      std::filesystem::perms::group_read;
    std::filesystem::permissions(dest, mode);
    Expect(PublishText(dest, "generated", nothing, true), "publish with mode");
    Expect((std::filesystem::status(dest).permissions() & std::filesystem::perms::all) == mode,
           "destination mode preserved");

    const auto shared = std::filesystem::perms::owner_read | std::filesystem::perms::owner_write |
                        std::filesystem::perms::group_read | std::filesystem::perms::others_read;
    std::filesystem::permissions(dest, shared);
    mode_t during_write = 0;
    std::string error;
    Expect(jpdfium_staging::WriteAndPublish(
               dest.c_str(),
               [&during_write](jpdfium_staging::StagedFile& staged) {
                   struct stat st;
                   if (::fstat(::fileno(staged.file), &st) == 0) during_write = st.st_mode & 07777;
                   std::fputs("private", staged.file);
               },
               &error),
           "write and publish over an existing file");
    Expect(during_write == 0600, "staging file stays owner-only while it is written");
    Expect((std::filesystem::status(dest).permissions() & std::filesystem::perms::all) == shared,
           "published file takes the destination mode");

    const mode_t previous = ::umask(027);
    const std::filesystem::path fresh = dir / "fresh.pdf";
    Expect(PublishText(fresh, "generated", nothing, true), "publish to a new destination");
    ::umask(previous);
    Expect((std::filesystem::status(fresh).permissions() & std::filesystem::perms::all) ==
               (std::filesystem::perms::owner_read | std::filesystem::perms::owner_write |
                std::filesystem::perms::group_read),
           "new destination gets the umask-derived mode");
}
#endif

int Run() {
    std::random_device rd;
    const std::filesystem::path dir =
        std::filesystem::temp_directory_path() / ("jpdfium-staging-" + std::to_string(rd()));
    std::filesystem::create_directories(dir);
    PortableChecks(dir);
#if !defined(_WIN32)
    PosixChecks(dir);
#endif
    std::filesystem::remove_all(dir);
    if (failures == 0) std::printf("staging_publish: ok\n");
    return failures == 0 ? 0 : 1;
}

}  // namespace

int main() {
    try {
        return Run();
    } catch (const std::exception& e) {
        std::fprintf(stderr, "FAIL: %s\n", e.what());
        return 1;
    } catch (...) {
        std::fprintf(stderr, "FAIL: unknown exception\n");
        return 1;
    }
}
