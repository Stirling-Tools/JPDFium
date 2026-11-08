#include <cstdio>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iterator>
#include <memory>
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
                 const std::function<void(const std::string&)>& before_publish, bool apply_mode) {
    jpdfium_staging::StagedFile staged;
    jpdfium_staging::OpenUniqueSibling(dest.string(), &staged);
    const std::unique_ptr<jpdfium_staging::StagedFile, void (*)(jpdfium_staging::StagedFile*)>
        guard(&staged, [](jpdfium_staging::StagedFile* open) {
            if (open->file) std::fclose(open->file);
        });
    std::fwrite(text.data(), 1, text.size(), staged.file);
    if (apply_mode && !jpdfium_staging::ApplyPublishModeTo(staged.file, dest.c_str())) {
        return false;
    }
    before_publish(staged.path);
    std::string error;
    const bool ok = jpdfium_staging::PublishStagedFile(&staged, dest.c_str(), &error);
    if (!ok) std::filesystem::remove(staged.path);
    return ok;
}

#if !defined(_WIN32)
int Run() {
    const std::filesystem::path dir =
        std::filesystem::temp_directory_path() / ("jpdfium-staging-" + std::to_string(::getpid()));
    std::filesystem::create_directories(dir);
    const std::filesystem::path dest = dir / "out.pdf";
    const auto nothing = [](const std::string&) {};

    Expect(PublishText(dest, "generated", nothing, false), "normal publish");
    Expect(Read(dest) == "generated", "published content");

    const std::filesystem::path victim = dir / "victim.txt";
    Write(victim, "keep me");
    Write(dest, "previous");
    const auto swap = [&victim](const std::string& staging) {
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

    std::filesystem::remove_all(dir);
    if (failures == 0) std::printf("staging_publish: ok\n");
    return failures == 0 ? 0 : 1;
}
#endif

}  // namespace

int main() {
#if defined(_WIN32)
    return 0;
#else
    try {
        return Run();
    } catch (const std::exception& e) {
        std::fprintf(stderr, "FAIL: %s\n", e.what());
        return 1;
    } catch (...) {
        std::fprintf(stderr, "FAIL: unknown exception\n");
        return 1;
    }
#endif
}
