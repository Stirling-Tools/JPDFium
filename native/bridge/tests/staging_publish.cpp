#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
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

jpdfium_staging::StagedFile Stage(const std::filesystem::path& dest, const std::string& text) {
    jpdfium_staging::StagedFile staged = jpdfium_staging::OpenUniqueSibling(dest.string());
    std::fwrite(text.data(), 1, text.size(), staged.file);
    return staged;
}

}  // namespace

int main() {
#if defined(_WIN32)
    return 0;
#else
    const std::filesystem::path dir =
        std::filesystem::temp_directory_path() / ("jpdfium-staging-" + std::to_string(::getpid()));
    std::filesystem::create_directories(dir);
    const std::filesystem::path dest = dir / "out.pdf";
    std::string error;

    jpdfium_staging::StagedFile normal = Stage(dest, "generated");
    Expect(jpdfium_staging::PublishStagedFile(&normal, dest.c_str(), &error), "normal publish");
    Expect(Read(dest) == "generated", "published content");

    const std::filesystem::path victim = dir / "victim.txt";
    Write(victim, "keep me");
    Write(dest, "previous");
    jpdfium_staging::StagedFile swapped = Stage(dest, "generated");
    std::filesystem::remove(swapped.path);
    std::filesystem::create_symlink(victim, swapped.path);
    Expect(!jpdfium_staging::PublishStagedFile(&swapped, dest.c_str(), &error),
           "publish refuses a replaced staging entry");
    Expect(Read(victim) == "keep me", "replacement target untouched");
    Expect(Read(dest) == "previous", "destination untouched after refused publish");
    std::filesystem::remove(swapped.path);

    std::filesystem::permissions(dest, std::filesystem::perms::owner_read |
                                           std::filesystem::perms::owner_write |
                                           std::filesystem::perms::group_read);
    jpdfium_staging::StagedFile moded = Stage(dest, "generated");
    Expect(jpdfium_staging::ApplyPublishModeTo(moded.file, dest.c_str()), "mode applied");
    Expect(jpdfium_staging::PublishStagedFile(&moded, dest.c_str(), &error), "moded publish");
    const auto perms = std::filesystem::status(dest).permissions() & std::filesystem::perms::all;
    Expect(perms == (std::filesystem::perms::owner_read | std::filesystem::perms::owner_write |
                     std::filesystem::perms::group_read),
           "destination mode preserved");

    std::filesystem::remove_all(dir);
    if (failures == 0) std::printf("staging_publish: ok\n");
    return failures == 0 ? 0 : 1;
#endif
}
