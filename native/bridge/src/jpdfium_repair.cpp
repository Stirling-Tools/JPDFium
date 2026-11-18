// jpdfium_repair.cpp - PDF structural repair pipeline.

#if !defined(_WIN32) && !defined(_FILE_OFFSET_BITS)
// NOLINTNEXTLINE(bugprone-reserved-identifier)
#define _FILE_OFFSET_BITS 64
#endif

#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <fcntl.h>
#include <io.h>
#include <sys/stat.h>
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <set>
#include <sstream>
#include <string>
#include <tuple>
#include <vector>

#include "jpdfium.h"

// File helpers shared by the in-memory and file-backed repair entry points.
// Defined outside the qpdf guard so both builds expose the file route.
namespace {

// Sibling staging file for an atomic publish: a refused repair leaves an existing destination
// byte-for-byte untouched. The pid + counter suffix keeps concurrent repairs from colliding.
std::string staging_sibling(const char* path) {
    static std::atomic<unsigned> counter{0};
    const unsigned n = counter.fetch_add(1, std::memory_order_relaxed);
#if defined(_WIN32)
    const long pid = static_cast<long>(GetCurrentProcessId());
#else
    const long pid = static_cast<long>(::getpid());
#endif
    return std::string(path) + ".jpdfium-repair-" + std::to_string(pid) + "-" + std::to_string(n) +
           ".tmp";
}

// Open a fresh sibling staging file with owner-only permissions where the
// platform supports them. Exclusive creation turns a planted entry into EEXIST,
// so retry with a fresh suffix instead of failing on the first collision.
FILE* open_staging_sibling(const char* path, std::string& stagingOut) {
    constexpr int kAttempts = 32;
    for (int attempt = 0; attempt < kAttempts; ++attempt) {
        stagingOut = staging_sibling(path);
#if defined(_WIN32)
        const int fd = _open(stagingOut.c_str(), _O_WRONLY | _O_CREAT | _O_EXCL | _O_BINARY,
                             _S_IREAD | _S_IWRITE);
        if (fd < 0) {
            if (errno == EEXIST) continue;
            return nullptr;
        }
        FILE* f = _fdopen(fd, "wb");
        if (!f) {
            _close(fd);
            std::remove(stagingOut.c_str());
            return nullptr;
        }
#else
        const int fd = ::open(stagingOut.c_str(), O_WRONLY | O_CREAT | O_EXCL, 0600);
        if (fd < 0) {
            if (errno == EEXIST) continue;
            return nullptr;
        }
        FILE* f = ::fdopen(fd, "wb");
        if (!f) {
            ::close(fd);
            std::remove(stagingOut.c_str());
            return nullptr;
        }
#endif
        return f;
    }
    return nullptr;
}

// Rename the staging sibling over the destination (POSIX rename is atomic; Windows uses
// MoveFileEx). The partial staging file is 0600; an existing destination's mode is restored
// before the rename, and with no destination the restrictive 0600 is kept.
bool publish_staged(const std::string& staging, const char* destination) {
#if defined(_WIN32)
    if (::MoveFileExA(staging.c_str(), destination,
                      MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        return true;
    }
    // Destination is left exactly as it was when MoveFileEx declines; fall back
    // to a plain rename only for older filesystems that report the target as
    // existing. Removing the destination first would destroy the previous file
    // on a path that then also fails, which is the loss this staging prevents.
    if (std::rename(staging.c_str(), destination) == 0) return true;
    std::remove(staging.c_str());
    return false;
#else
    struct stat st;
    std::memset(&st, 0, sizeof(st));
    if (::stat(destination, &st) == 0) {
        // Keep the destination's mode. chmod on our own staging file should not
        // fail; if it does, publishing would silently change the destination's
        // permissions, so drop the staging file and report failure instead.
        if (::chmod(staging.c_str(), st.st_mode & 07777) != 0) {
            std::remove(staging.c_str());
            return false;
        }
    }
    // A missing destination keeps the staging file's restrictive 0600. No probe
    // file is created, so no file is ever created world-writable. On failure the
    // staging file is removed here, matching the Windows branch above.
    if (std::rename(staging.c_str(), destination) != 0) {
        std::remove(staging.c_str());
        return false;
    }
    return true;
#endif
}

[[maybe_unused]] bool read_whole_file(const char* path, std::vector<uint8_t>& out) {
    // RAII: the handle is released even if resize() throws (bad_alloc), so an
    // allocation failure can never leak a descriptor into the C ABI boundary.
    // The size is read as a 64-bit offset on every platform: ftell returns a 32-bit long under
    // Windows' LLP64 model, so a file at or above 2 GiB would overflow and the repair would
    // silently fail. _ftelli64/_fseeki64 (and fseeko/ftello elsewhere) return 64-bit offsets.
    std::unique_ptr<FILE, int (*)(FILE*)> f(std::fopen(path, "rb"), &std::fclose);
    if (!f) return false;
#if defined(_WIN32)
    if (_fseeki64(f.get(), 0, SEEK_END) != 0) return false;
    const int64_t size = _ftelli64(f.get());
    if (size <= 0 || _fseeki64(f.get(), 0, SEEK_SET) != 0) return false;
#else
    if (fseeko(f.get(), 0, SEEK_END) != 0) return false;
    const int64_t size = static_cast<int64_t>(ftello(f.get()));
    if (size <= 0 || fseeko(f.get(), 0, SEEK_SET) != 0) return false;
#endif
    out.resize(static_cast<size_t>(size));
    const size_t read = std::fread(out.data(), 1, out.size(), f.get());
    return read == out.size();
}

// Write to a sibling staging file, then publish it over the destination only
// after a complete, flushed write, so a failed repair never truncates an
// existing output (and never damages the source when input == output).
[[maybe_unused]] bool write_whole_file(const char* path, const uint8_t* data, int64_t len) {
    if (!path || !data || len <= 0) return false;
    std::string staging;
    FILE* f = open_staging_sibling(path, staging);
    if (!f) return false;
    const size_t wrote = std::fwrite(data, 1, static_cast<size_t>(len), f);
    const bool flushed = std::fclose(f) == 0;
    if (!flushed || wrote != static_cast<size_t>(len) || !publish_staged(staging, path)) {
        std::remove(staging.c_str());
        return false;
    }
    return true;
}

}  // namespace

#ifdef JPDFIUM_HAS_QPDF

#include <qpdf/Buffer.hh>
#include <qpdf/QPDF.hh>
#include <qpdf/QPDFExc.hh>
#include <qpdf/QPDFObjectHandle.hh>
#include <qpdf/QPDFWriter.hh>
#include <qpdf/QPDFXRefEntry.hh>

#include "jpdfium_qpdf_internal.h"
#include "jpdfium_qpdf_writer_lock.h"

namespace {

bool isPdfWs(uint8_t c) {
    return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '\0';
}

bool isPdfDelim(uint8_t c) {
    return isPdfWs(c) || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}' ||
           c == '/' || c == '%' || c == '(' || c == ')';
}

// Detect a cut-off incremental update: more than 4 KiB of bytes containing at least
// one "N G obj" definition appended after the document's final startxref / %%EOF.
bool findTruncatedIncrementalStartxref(const uint8_t* data, size_t len, size_t& startxrefPos) {
    if (!data || len < 64) return false;
    static constexpr char kNeedle[] = "startxref";
    static constexpr size_t kNeedleLen = 9;
    size_t pos = len;
    bool found = false;
    for (size_t i = len - kNeedleLen + 1; i > 0; --i) {
        size_t candidate = i - 1;
        if (std::memcmp(data + candidate, kNeedle, kNeedleLen) != 0) continue;
        if (candidate > 0 && !isPdfWs(data[candidate - 1])) continue;
        size_t after = candidate + kNeedleLen;
        while (after < len && isPdfWs(data[after])) ++after;
        if (after == candidate + kNeedleLen || after >= len || data[after] < '0' ||
            data[after] > '9') {
            continue;
        }
        while (after < len && data[after] >= '0' && data[after] <= '9') ++after;
        while (after < len && isPdfWs(data[after])) ++after;
        if (after + 5 <= len && std::memcmp(data + after, "%%EOF", 5) == 0) {
            after += 5;
            while (after < len && isPdfWs(data[after])) ++after;
        }
        startxrefPos = candidate;
        pos = after;
        found = true;
        break;
    }
    if (!found || len <= pos || (len - pos) <= 4096) return false;

    size_t p = pos;
    while (p + 5 <= len) {
        if (p > pos && !isPdfWs(data[p - 1])) {
            ++p;
            continue;
        }
        if (data[p] < '0' || data[p] > '9') {
            ++p;
            continue;
        }
        size_t q = p;
        while (q < len && data[q] >= '0' && data[q] <= '9') ++q;
        if (q >= len || !isPdfWs(data[q])) {
            // Skip past the non-whitespace token so linear scans never backtrack.
            p = q + 1;
            continue;
        }
        while (q < len && isPdfWs(data[q])) ++q;
        size_t genStart = q;
        while (q < len && data[q] >= '0' && data[q] <= '9') ++q;
        if (q == genStart || q >= len || !isPdfWs(data[q])) {
            p = (genStart > p) ? genStart : (p + 1);
            continue;
        }
        while (q < len && isPdfWs(data[q])) ++q;
        if (q + 3 <= len && std::memcmp(data + q, "obj", 3) == 0 &&
            (q + 3 == len || isPdfDelim(data[q + 3]))) {
            return true;
        }
        p = (genStart > p) ? genStart : (p + 1);
    }
    return false;
}

bool fileHasTruncatedIncremental(const char* path) {
    if (!path) return false;
    std::unique_ptr<FILE, int (*)(FILE*)> f(std::fopen(path, "rb"), &std::fclose);
    if (!f) return false;
#if defined(_WIN32)
    if (_fseeki64(f.get(), 0, SEEK_END) != 0) return false;
    const int64_t size = _ftelli64(f.get());
#else
    if (fseeko(f.get(), 0, SEEK_END) != 0) return false;
    const int64_t size = static_cast<int64_t>(ftello(f.get()));
#endif
    if (size <= 4096) return false;
    static constexpr int64_t kTailWindow = 2LL * 1024 * 1024;
    const int64_t offset = size > kTailWindow ? (size - kTailWindow) : 0;
#if defined(_WIN32)
    if (_fseeki64(f.get(), offset, SEEK_SET) != 0) return false;
#else
    if (fseeko(f.get(), offset, SEEK_SET) != 0) return false;
#endif
    std::vector<uint8_t> tail(static_cast<size_t>(size - offset));
    const size_t got = std::fread(tail.data(), 1, tail.size(), f.get());
    tail.resize(got);
    size_t startxrefPos = 0;
    return findTruncatedIncrementalStartxref(tail.data(), tail.size(), startxrefPos);
}

bool isMissingOrZeroBox(QPDFObjectHandle const& box) {
    if (!box.isArray() || box.getArrayNItems() != 4) return true;
    QPDFObjectHandle x0 = box.getArrayItem(0);
    QPDFObjectHandle y0 = box.getArrayItem(1);
    QPDFObjectHandle x1 = box.getArrayItem(2);
    QPDFObjectHandle y1 = box.getArrayItem(3);
    if (!x0.isNumber() || !y0.isNumber() || !x1.isNumber() || !y1.isNumber()) return true;
    return std::abs(x0.getNumericValue() - x1.getNumericValue()) <= 1e-6 ||
           std::abs(y0.getNumericValue() - y1.getNumericValue()) <= 1e-6;
}

// Materializes inherited /MediaBox, /CropBox, /Resources, and /Rotate attributes
// directly onto recovered pages before flat tree re-parenting breaks ancestor chains.
void inheritMissingPageAttributes(QPDFObjectHandle page, QPDFObjectHandle const& oldPagesRoot) {
    static constexpr char const* kInheritable[] = {"/MediaBox", "/CropBox", "/Resources",
                                                   "/Rotate"};
    for (char const* key : kInheritable) {
        const bool isMediaBox = std::strcmp(key, "/MediaBox") == 0;
        bool needKey = isMediaBox ? isMissingOrZeroBox(page.getKey(key)) : !page.hasKey(key);
        if (!needKey) continue;

        // Guard against /Parent cycles while climbing original page-tree ancestors.
        std::set<QPDFObjGen> seen;
        if (page.isIndirect()) seen.insert(page.getObjGen());
        QPDFObjectHandle cur = page.getKey("/Parent");
        for (int depth = 0; depth < 32 && cur.isDictionary(); ++depth) {
            if (cur.isIndirect() && !seen.insert(cur.getObjGen()).second) break;
            QPDFObjectHandle val = cur.getKey(key);
            if (isMediaBox ? !isMissingOrZeroBox(val) : !val.isNull()) {
                page.replaceKey(key, val);
                needKey = false;
                break;
            }
            cur = cur.getKey("/Parent");
        }
        if (needKey && oldPagesRoot.isDictionary()) {
            QPDFObjectHandle val = oldPagesRoot.getKey(key);
            if (isMediaBox ? !isMissingOrZeroBox(val) : !val.isNull()) {
                page.replaceKey(key, val);
                needKey = false;
            }
        }
        if (needKey && isMediaBox) {
            // ISO 32000-1 requires /MediaBox; synthesize US Letter if none survived.
            page.replaceKey("/MediaBox", QPDFObjectHandle::parse("[0 0 612 792]"));
        }
    }
}

// Rebuild a missing, cyclic, or empty /Pages tree in physical file offset order
// while preserving inheritable attributes and skipping signed PDFs.
bool repairBrokenPageTree(QPDF& pdf) {
    // Never salvage signed documents; rewriting /Pages breaks cryptographic ByteRange digests.
    if (jpdfium::qpdf_detail::pdfHasDigitalSignature(pdf)) return false;

    // QPDF throws or returns empty when /Pages is missing, cyclic, or lacks leaf /Count.
    bool pagesBroken = false;
    try {
        pagesBroken = pdf.getAllPages().empty();
    } catch (...) {
        pagesBroken = true;
    }
    if (!pagesBroken) return false;

    QPDFObjectHandle root;
    try {
        root = pdf.getRoot();
    } catch (...) {
        return false;
    }
    if (!root.isDictionary()) return false;

    std::map<QPDFObjGen, QPDFXRefEntry> xref;
    try {
        xref = pdf.getXRefTable();
    } catch (...) {
        xref.clear();
    }

    // Order orphan /Page objects by byte offset (or host /ObjStm offset + index) to match reading
    // order.
    auto orderKeyFor = [&xref](QPDFObjGen const& og) {
        auto it = xref.find(og);
        if (it != xref.end()) {
            QPDFXRefEntry const& entry = it->second;
            if (entry.getType() == 1) {
                return std::tuple<qpdf_offset_t, int, int, int>{entry.getOffset(), 0, og.getObj(),
                                                                og.getGen()};
            }
            if (entry.getType() == 2) {
                auto stmIt = xref.find(QPDFObjGen(entry.getObjStreamNumber(), 0));
                qpdf_offset_t stmOffset = (stmIt != xref.end() && stmIt->second.getType() == 1)
                                              ? stmIt->second.getOffset()
                                              : std::numeric_limits<qpdf_offset_t>::max();
                return std::tuple<qpdf_offset_t, int, int, int>{
                    stmOffset, entry.getObjStreamIndex(), og.getObj(), og.getGen()};
            }
        }
        return std::tuple<qpdf_offset_t, int, int, int>{std::numeric_limits<qpdf_offset_t>::max(),
                                                        0, og.getObj(), og.getGen()};
    };

    struct RecoveredPage {
        std::tuple<qpdf_offset_t, int, int, int> order;
        QPDFObjectHandle page;
    };
    std::vector<RecoveredPage> recovered;
    for (auto const& obj : pdf.getAllObjects()) {
        if (!obj.isDictionary() || obj.isStream()) continue;
        QPDFObjectHandle type = obj.getKey("/Type");
        if (!type.isName() || type.getName() != "/Page") continue;
        recovered.push_back({orderKeyFor(obj.getObjGen()), obj});
    }
    if (recovered.empty()) return false;

    std::sort(recovered.begin(), recovered.end(),
              [](RecoveredPage const& a, RecoveredPage const& b) { return a.order < b.order; });

    QPDFObjectHandle oldPagesRoot = root.getKey("/Pages");
    QPDFObjectHandle newPages = pdf.makeIndirectObject(
        QPDFObjectHandle::newDictionary({{"/Type", QPDFObjectHandle::newName("/Pages")}}));
    std::vector<QPDFObjectHandle> kids;
    kids.reserve(recovered.size());

    for (auto& item : recovered) {
        QPDFObjectHandle page = item.page;
        inheritMissingPageAttributes(page, oldPagesRoot);
        page.replaceKey("/Parent", newPages);
        kids.push_back(page);
    }

    newPages.replaceKey("/Kids", QPDFObjectHandle::newArray(kids));
    newPages.replaceKey("/Count",
                        QPDFObjectHandle::newInteger(static_cast<long long>(kids.size())));
    root.replaceKey("/Pages", newPages);
    return true;
}

size_t safePageCount(QPDF& pdf) {
    try {
        return pdf.getAllPages().size();
    } catch (...) {
        return 0;
    }
}

std::shared_ptr<QPDF> tryRebuildTruncatedUpdate(const uint8_t* input, size_t inputLen,
                                                size_t startxrefPos, bool& treeRepaired) {
    std::vector<uint8_t> patched(input, input + inputLen);
    if (startxrefPos < patched.size()) patched[startxrefPos] = '%';

    auto tryBytes = [&](std::vector<uint8_t> const& buf) -> std::shared_ptr<QPDF> {
        try {
            auto candidate = std::make_shared<QPDF>();
            candidate->setSuppressWarnings(true);
            candidate->processMemoryFile("repair-trunc", reinterpret_cast<const char*>(buf.data()),
                                         buf.size());
            if (jpdfium::qpdf_detail::pdfHasDigitalSignature(*candidate)) return {};
            treeRepaired = repairBrokenPageTree(*candidate);
            if (safePageCount(*candidate) == 0) return {};
            return candidate;
        } catch (...) {
            return {};
        }
    };

    if (auto ok = tryBytes(patched)) return ok;

    // If the final incremental object was truncated mid-stream or mid-dict, clip back to
    // the last complete "endobj" after the neutralized startxref and retry recovery.
    static constexpr char kEndobj[] = "endobj";
    for (size_t pos = patched.size(); pos >= startxrefPos + 6; --pos) {
        if (std::memcmp(patched.data() + pos - 6, kEndobj, 6) == 0 &&
            (pos == patched.size() || isPdfDelim(patched[pos])) &&
            (pos - 6 == 0 || isPdfDelim(patched[pos - 7]))) {
            patched.resize(pos);
            if (auto ok = tryBytes(patched)) return ok;
            break;
        }
    }
    return {};
}

std::shared_ptr<QPDF> loadAndRepairMemory(const uint8_t* input, size_t inputLen, bool& fixed) {
    fixed = false;
    std::shared_ptr<QPDF> primary;
    bool primaryLoaded = false;
    try {
        primary = std::make_shared<QPDF>();
        primary->setSuppressWarnings(true);
        primary->processMemoryFile("repair", reinterpret_cast<const char*>(input), inputLen);
        primaryLoaded = true;
    } catch (...) {
        primary.reset();
    }

    size_t startxrefPos = 0;
    const bool hasTruncUpdate = findTruncatedIncrementalStartxref(input, inputLen, startxrefPos);
    if (hasTruncUpdate &&
        (!primaryLoaded || !primary || !jpdfium::qpdf_detail::pdfHasDigitalSignature(*primary))) {
        size_t basePages = primaryLoaded ? safePageCount(*primary) : 0;
        bool altTreeFixed = false;
        if (auto rebuilt = tryRebuildTruncatedUpdate(input, inputLen, startxrefPos, altTreeFixed)) {
            size_t altPages = safePageCount(*rebuilt);
            if (!primaryLoaded || altPages > basePages || (altPages > 0 && altPages >= basePages)) {
                fixed = true;
                return rebuilt;
            }
        }
    }

    if (!primaryLoaded) {
        auto direct = std::make_shared<QPDF>();
        direct->setSuppressWarnings(true);
        direct->processMemoryFile("repair", reinterpret_cast<const char*>(input), inputLen);
        return direct;
    }

    if (repairBrokenPageTree(*primary)) fixed = true;
    return primary;
}

}  // namespace

// Helper: serialize qpdf warnings to JSON array
static std::string warnings_to_json(const std::vector<QPDFExc>& warnings) {
    std::ostringstream os;
    os << "[";
    bool first = true;
    for (const auto& w : warnings) {
        if (!first) os << ",";
        first = false;
        // Escape the warning message for JSON
        std::string msg = w.what();
        std::string escaped;
        escaped.reserve(msg.size());
        for (char c : msg) {
            if (c == '"' || c == '\\') escaped += '\\';
            if (c == '\n') {
                escaped += "\\n";
                continue;
            }
            if (c == '\r') continue;
            escaped += c;
        }
        os << "{\"message\":\"" << escaped << "\"}";
    }
    os << "]";
    return os.str();
}

static void configureRepairWriter(QPDFWriter& writer, int32_t flags) {
    if (flags & JPDFIUM_REPAIR_NORMALIZE_XREF) {
        writer.setObjectStreamMode(qpdf_o_disable);
    }
    writer.setLinearization(false);
    writer.setCompressStreams(true);
    if (flags & JPDFIUM_REPAIR_FORCE_V14) {
        // Object streams require PDF 1.5 (ISO 32000-1 §7.5.7); keep 1.4 clean.
        writer.setObjectStreamMode(qpdf_o_disable);
        writer.forcePDFVersion("1.4");
    }
}

// Helper: attempt qpdf recovery on raw bytes
static int try_qpdf_repair(const uint8_t* input, int64_t inputLen, uint8_t** output,
                           int64_t* outputLen, int32_t flags, std::string& errorMsg) {
    try {
        bool structurallyFixed = false;
        auto pdf = loadAndRepairMemory(input, static_cast<size_t>(inputLen), structurallyFixed);

        // Rewriting a signed document always breaks its /ByteRange digest. The
        // Java layer passes clean signed documents through untouched; a damaged
        // signed document that still parses must fail here instead of producing
        // output whose signature silently no longer verifies. pdfHasDigitalSignature
        // enumerates parsed objects, so this also catches signature dictionaries
        // hidden inside object streams that raw marker scans miss.
        if (jpdfium::qpdf_detail::pdfHasDigitalSignature(*pdf)) {
            errorMsg = "signed document cannot be repaired without invalidating its signature";
            return JPDFIUM_REPAIR_FAILED;
        }

        QPDFWriter writer(*pdf);
        writer.setOutputMemory();
        configureRepairWriter(writer, flags);

        jpdfium_qpdf_internal::WriterLock lock{-1};
        writer.write();

        auto warnings = pdf->getWarnings();

        std::shared_ptr<Buffer> buf = writer.getBufferSharedPointer();
        if (!buf || buf->getSize() == 0) {
            errorMsg = "QPDF produced an empty repaired output buffer";
            return JPDFIUM_REPAIR_FAILED;
        }
        *outputLen = static_cast<int64_t>(buf->getSize());
        // malloc (not new[]) - the caller frees this buffer with
        // jpdfium_free_buffer, which calls free().
        *output = static_cast<uint8_t*>(malloc(static_cast<size_t>(*outputLen)));
        if (!*output) return JPDFIUM_REPAIR_FAILED;
        memcpy(*output, buf->getBuffer(), static_cast<size_t>(*outputLen));

        return (structurallyFixed || !warnings.empty()) ? JPDFIUM_REPAIR_FIXED
                                                        : JPDFIUM_REPAIR_CLEAN;
    } catch (QPDFExc& e) {
        errorMsg = e.what();
        return JPDFIUM_REPAIR_FAILED;
    } catch (std::exception& e) {
        errorMsg = e.what();
        return JPDFIUM_REPAIR_FAILED;
    }
}

// Helper: file-to-file qpdf recovery. Streams the document straight from disk to a sibling
// staging file renamed over the destination on success, so the file route never holds a
// whole-document native buffer and a failed repair never truncates an existing output.
static int try_qpdf_repair_file(const char* inputPath, const char* outputPath, int32_t flags,
                                std::string& errorMsg) {
    // Keep the owner-only staging handle open and hand it to qpdf so the bytes
    // are written through the exact inode we secured at 0600. Reopening it by
    // name (setOutputFilename) would let another principal in a writable
    // directory swap the entry before qpdf opens it.
    std::string staging;
    FILE* probe = open_staging_sibling(outputPath, staging);
    if (!probe) return JPDFIUM_REPAIR_FAILED;

    int result = JPDFIUM_REPAIR_FAILED;
    try {
        {
            bool structurallyFixed = false;
            std::shared_ptr<QPDF> pdf;
            std::vector<uint8_t> memInput;
            if (fileHasTruncatedIncremental(inputPath) && read_whole_file(inputPath, memInput)) {
                pdf = loadAndRepairMemory(memInput.data(), memInput.size(), structurallyFixed);
            } else {
                pdf = std::make_shared<QPDF>();
                pdf->setSuppressWarnings(true);
                pdf->processFile(inputPath);
                structurallyFixed = repairBrokenPageTree(*pdf);
            }

            // Same signed-document refusal as the memory route: never rewrite a
            // document carrying a /ByteRange signature dictionary.
            if (jpdfium::qpdf_detail::pdfHasDigitalSignature(*pdf)) {
                throw std::runtime_error(
                    "signed document cannot be repaired without invalidating its signature");
            }

            // Parity with the Java execute() route: a repair that produced no
            // pages is not a successful repair. CLEAN is deliberately skipped -
            // a document that was already valid is the caller's business.
            const size_t pages = safePageCount(*pdf);

            QPDFWriter writer(*pdf);
            // qpdf writes to our fd and leaves closure to us (close_file=false).
            writer.setOutputFile(staging.c_str(), probe, false);
            configureRepairWriter(writer, flags);

            jpdfium_qpdf_internal::WriterLock lock{-1};
            writer.write();
            const bool closed = std::fclose(probe) == 0;
            probe = nullptr;
            result = !closed                                              ? JPDFIUM_REPAIR_FAILED
                     : (structurallyFixed || !pdf->getWarnings().empty()) ? JPDFIUM_REPAIR_FIXED
                                                                          : JPDFIUM_REPAIR_CLEAN;
            if (result == JPDFIUM_REPAIR_FIXED && pages == 0) {
                result = JPDFIUM_REPAIR_FAILED;
            }
        }
    } catch (QPDFExc& e) {
        if (probe) {
            std::fclose(probe);
            probe = nullptr;
        }
        errorMsg = e.what();
        result = JPDFIUM_REPAIR_FAILED;
    } catch (std::exception& e) {
        if (probe) {
            std::fclose(probe);
            probe = nullptr;
        }
        errorMsg = e.what();
        result = JPDFIUM_REPAIR_FAILED;
    }
    if (result == JPDFIUM_REPAIR_FAILED || !publish_staged(staging, outputPath)) {
        std::remove(staging.c_str());
        return JPDFIUM_REPAIR_FAILED;
    }
    return result;
}

// Helper: attempt startxref offset correction
static bool try_fix_startxref(std::vector<uint8_t>& data, int delta) {
    // Find last "startxref" in the file
    const char* needle = "startxref";
    size_t needleLen = 9;

    int64_t pos = -1;
    // NOLINTNEXTLINE(bugprone-narrowing-conversions) - bounded by input size
    for (int64_t i = static_cast<int64_t>(data.size()) - static_cast<int64_t>(needleLen) - 1;
         i >= 0; --i) {
        if (memcmp(data.data() + i, needle, needleLen) == 0) {
            pos = i;
            break;
        }
    }
    if (pos < 0) return false;

    // Parse the offset value after "startxref\n"
    size_t numStart = pos + needleLen;
    while (numStart < data.size() &&
           (data[numStart] == '\n' || data[numStart] == '\r' || data[numStart] == ' '))
        numStart++;

    size_t numEnd = numStart;
    while (numEnd < data.size() && data[numEnd] >= '0' && data[numEnd] <= '9') numEnd++;

    if (numEnd == numStart) return false;

    std::string offsetStr(data.begin() + static_cast<std::ptrdiff_t>(numStart),
                          data.begin() + static_cast<std::ptrdiff_t>(numEnd));
    long long offset = std::stoll(offsetStr);
    long long newOffset = offset + delta;
    if (newOffset < 0) return false;

    // Replace the offset in the byte stream
    std::string newOffsetStr = std::to_string(newOffset);

    // Pad with spaces if shorter, or expand if longer
    std::vector<uint8_t> result;
    result.insert(result.end(), data.begin(), data.begin() + static_cast<std::ptrdiff_t>(numStart));
    result.insert(result.end(), newOffsetStr.begin(), newOffsetStr.end());
    // Keep the same trailing content
    result.insert(result.end(), data.begin() + static_cast<std::ptrdiff_t>(numEnd), data.end());

    data = std::move(result);
    return true;
}

// Core repair pipeline over in-memory bytes, shared by the array and file
// entry points. On success *output is a malloc'd buffer the caller releases
// with jpdfium_free_buffer.
static int32_t repair_pipeline(const uint8_t* input, int64_t inputLen, uint8_t** output,
                               int64_t* outputLen, int32_t flags) {
    try {
        if (!input || inputLen <= 0 || !output || !outputLen) return JPDFIUM_REPAIR_FAILED;

        // Stage 1: Direct qpdf recovery (handles most xref, trailer, stream issues)
        std::string errorMsg;
        int result = try_qpdf_repair(input, inputLen, output, outputLen, flags, errorMsg);
        if (result != JPDFIUM_REPAIR_FAILED) return result;

        // Stage 2: startxref offset brute-force (if enabled)
        if (flags & JPDFIUM_REPAIR_FIX_STARTXREF) {
            static const int deltas[] = {1, -1, 2, -2, 3, -3, 4, -4, 8, -8, 16, -16};
            for (int delta : deltas) {
                std::vector<uint8_t> patched(input, input + inputLen);
                if (try_fix_startxref(patched, delta)) {
                    result = try_qpdf_repair(patched.data(), static_cast<int64_t>(patched.size()),
                                             output, outputLen, flags, errorMsg);
                    if (result != JPDFIUM_REPAIR_FAILED) return result;
                }
            }
        }

        return JPDFIUM_REPAIR_FAILED;

    } catch (...) {
        return JPDFIUM_REPAIR_FAILED;
    }
}

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_repair_pdf(const uint8_t* input, int64_t inputLen, uint8_t** output,
                                          int64_t* outputLen, int32_t flags) {
    return repair_pipeline(input, inputLen, output, outputLen, flags);
}

// File-backed repair: repairs inputPath straight to outputPath. The normal
// route streams the document from disk with qpdf and stages the output, so no
// document-sized buffer is created on the Java heap and no whole-document
// native buffer is held. Returns a JPDFIUM_REPAIR_* status.
JPDFIUM_EXPORT int32_t jpdfium_repair_pdf_file(const char* inputPath, const char* outputPath,
                                               int32_t flags) {
    // Never let a C++ exception unwind through native code into the VM.
    try {
        if (!inputPath || !outputPath) return JPDFIUM_REPAIR_FAILED;

        // Stage 1: file-backed qpdf recovery (staged publication included).
        std::string errorMsg;
        int result = try_qpdf_repair_file(inputPath, outputPath, flags, errorMsg);
        if (result != JPDFIUM_REPAIR_FAILED) return result;

        // Stage 2: startxref brute-force needs the bytes, so only read them
        // when that fallback is explicitly requested.
        if (!(flags & JPDFIUM_REPAIR_FIX_STARTXREF)) return JPDFIUM_REPAIR_FAILED;

        std::vector<uint8_t> input;
        if (!read_whole_file(inputPath, input)) return JPDFIUM_REPAIR_FAILED;

        uint8_t* output = nullptr;
        int64_t outputLen = 0;
        result = repair_pipeline(input.data(), static_cast<int64_t>(input.size()), &output,
                                 &outputLen, flags);
        if (result == JPDFIUM_REPAIR_FAILED || !output || outputLen <= 0) {
            free(output);
            return JPDFIUM_REPAIR_FAILED;
        }

        const bool wrote = write_whole_file(outputPath, output, outputLen);
        free(output);
        return wrote ? result : JPDFIUM_REPAIR_FAILED;
    } catch (...) {
        return JPDFIUM_REPAIR_FAILED;
    }
}

JPDFIUM_EXPORT int32_t jpdfium_repair_inspect(const uint8_t* input, int64_t inputLen,
                                              char** diagnosticJson) {
    if (!input || inputLen <= 0 || !diagnosticJson) return JPDFIUM_ERR_INVALID;

    std::ostringstream os;
    os << "{";

    try {
        QPDF pdf;
        pdf.setSuppressWarnings(true);
        pdf.processMemoryFile("inspect", reinterpret_cast<const char*>(input),
                              static_cast<size_t>(inputLen));

        auto warnings = pdf.getWarnings();
        int pageCount = 0;
        try {
            QPDFObjectHandle root = pdf.getRoot();
            if (root.hasKey("/Pages")) {
                QPDFObjectHandle pages = root.getKey("/Pages");
                if (pages.isDictionary() && pages.hasKey("/Count")) {
                    QPDFObjectHandle cnt = pages.getKey("/Count");
                    if (cnt.isInteger()) {
                        pageCount = static_cast<int>(cnt.getIntValue());
                    }
                }
            }
        } catch (...) {
            pageCount = 0;  // Page tree may be broken
        }

        os << "\"status\":\"loaded\",";
        os << "\"warning_count\":" << warnings.size() << ",";
        os << "\"page_count\":" << pageCount << ",";
        os << "\"xref_valid\":true,";
        os << "\"trailer_valid\":true,";
        os << "\"issues\":" << warnings_to_json(warnings);

    } catch (std::exception& e) {
        std::string msg = e.what();
        std::string escaped;
        escaped.reserve(msg.size());
        for (char c : msg) {
            if (c == '"' || c == '\\') escaped += '\\';
            if (c == '\n') {
                escaped += "\\n";
                continue;
            }
            if (c == '\r') continue;
            escaped += c;
        }
        os << "\"status\":\"fatal\",";
        os << "\"warning_count\":0,";
        os << "\"page_count\":0,";
        os << "\"xref_valid\":false,";
        os << "\"trailer_valid\":false,";
        os << "\"issues\":[{\"message\":\"" << escaped << "\"}]";
    }

    os << "}";

    *diagnosticJson = strdup(os.str().c_str());
    return 0;
}

}  // extern "C"

#else  // !JPDFIUM_HAS_QPDF

// Stub implementations when qpdf is not available

extern "C" {

JPDFIUM_EXPORT int32_t jpdfium_repair_pdf(const uint8_t* input, int64_t inputLen, uint8_t** output,
                                          int64_t* outputLen, int32_t) {
    if (!input || inputLen <= 0 || !output || !outputLen) return JPDFIUM_REPAIR_FAILED;
    // Without qpdf, just pass through the bytes unchanged
    *outputLen = inputLen;
    *output = (uint8_t*)malloc(static_cast<size_t>(inputLen));
    if (!*output) {
        *outputLen = 0;
        return JPDFIUM_REPAIR_FAILED;
    }
    memcpy(*output, input, static_cast<size_t>(inputLen));
    return JPDFIUM_REPAIR_CLEAN;
}

JPDFIUM_EXPORT int32_t jpdfium_repair_pdf_file(const char* inputPath, const char* outputPath,
                                               int32_t) {
    // Without qpdf there is no repair engine. Do not copy a damaged PDF through
    // and report it as clean: signal that repair is unavailable instead.
    (void)inputPath;
    (void)outputPath;
    return JPDFIUM_REPAIR_FAILED;
}

JPDFIUM_EXPORT int32_t jpdfium_repair_inspect(const uint8_t* input, int64_t inputLen,
                                              char** diagnosticJson) {
    if (!input || inputLen <= 0 || !diagnosticJson) return JPDFIUM_ERR_INVALID;
    *diagnosticJson = strdup("{\"status\":\"unavailable\",\"message\":\"qpdf not linked\"}");
    return 0;
}

}  // extern "C"

#endif  // JPDFIUM_HAS_QPDF
