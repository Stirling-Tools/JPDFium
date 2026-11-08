// jpdfium_document.cpp - Library lifecycle, document and page management.

#include <epdf_layer.h>
#include <epdf_save.h>
#include <fpdf_doc.h>
#include <fpdf_edit.h>
#include <fpdf_ppo.h>
#include <fpdf_save.h>
#include <fpdfview.h>

#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

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

#include "jpdfium.h"
#include "jpdfium_internal.h"
#include "runtime/domain.h"

namespace {

struct LayerBaseEntry {
    EPDF_BASE_DOCUMENT base = nullptr;
    std::vector<uint8_t> bytes;

    ~LayerBaseEntry() {
        if (base) EPDF_ReleaseBaseDocument(base);
    }
};

std::mutex g_layerBaseMutex;
std::unordered_map<std::string, std::weak_ptr<LayerBaseEntry>> g_layerBases;

struct CachedBase {
    std::shared_ptr<LayerBaseEntry> entry;
    int32_t error = 0;
};

// A base is immutable for the lifetime of a layer. Cache it by the exact file
// identity so many sessions opening the same base share one parse and one copy
// of the bytes; the weak map lets the entry die once the last layer closes.
CachedBase acquireLayerBase(const char* path) {
    std::string key;
    std::error_code ec;
    auto absolute = std::filesystem::absolute(path, ec);
    if (ec) return {nullptr, JPDFIUM_ERR_IO};
    auto size = std::filesystem::file_size(absolute, ec);
    if (ec) return {nullptr, JPDFIUM_ERR_IO};
    auto mtime = std::filesystem::last_write_time(absolute, ec);
    if (ec) return {nullptr, JPDFIUM_ERR_IO};
    key = absolute.string() + "|" + std::to_string(size) + "|" +
          std::to_string(static_cast<long long>(mtime.time_since_epoch().count()));

    {
        std::lock_guard<std::mutex> lock(g_layerBaseMutex);
        auto it = g_layerBases.find(key);
        if (it != g_layerBases.end()) {
            if (auto entry = it->second.lock()) return {std::move(entry), 0};
            g_layerBases.erase(it);
        }
    }

    std::FILE* in = std::fopen(path, "rb");
    if (!in) return {nullptr, JPDFIUM_ERR_IO};
    if (std::fseek(in, 0, SEEK_END) != 0) {
        std::fclose(in);
        return {nullptr, JPDFIUM_ERR_IO};
    }
    long rawLen = std::ftell(in);
    if (rawLen <= 0) {
        std::fclose(in);
        return {nullptr, JPDFIUM_ERR_INVALID};
    }
    std::rewind(in);
    auto entry = std::make_shared<LayerBaseEntry>();
    entry->bytes.resize(static_cast<size_t>(rawLen));
    if (std::fread(entry->bytes.data(), 1, entry->bytes.size(), in) != entry->bytes.size()) {
        std::fclose(in);
        return {nullptr, JPDFIUM_ERR_IO};
    }
    std::fclose(in);
    entry->base = EPDF_LoadMemBaseDocument64(entry->bytes.data(), entry->bytes.size(), nullptr);
    if (!entry->base) return {nullptr, JPDFIUM_ERR_NATIVE};

    {
        std::lock_guard<std::mutex> lock(g_layerBaseMutex);
        g_layerBases[key] = entry;
        // Weak entries for bases no longer in use would otherwise accumulate
        // for every distinct file the process has ever opened.
        if (g_layerBases.size() > 64) {
            for (auto it = g_layerBases.begin(); it != g_layerBases.end();) {
                if (it->second.expired())
                    it = g_layerBases.erase(it);
                else
                    ++it;
            }
        }
    }
    return {std::move(entry), 0};
}

// Exclusive, no-follow creation for staging files. The sibling name carries
// only the PID plus a counter, so another local user with write access to the
// directory could pre-create it as a symlink: O_TRUNC/fopen("wb") would then
// truncate the link target. O_EXCL|O_NOFOLLOW (and _O_EXCL on Windows, where
// MSVCRT has no no-follow open flag) turns that into a failed open instead,
// and every failure here fails the save closed via JPDFIUM_ERR_IO.
FILE* safe_fopen_write(const char* path) {
#if defined(_WIN32)
    // _O_BINARY is required: the CRT default is text mode, and _fdopen's "wb"
    // does not switch an already-open descriptor, so every LF in the PDF got a
    // CR inserted. That shifted every offset past the first stream newline, so
    // readers mis-resolved objects and content streams came back empty.
    int fd = -1;
    if (::_sopen_s(&fd, path, _O_WRONLY | _O_CREAT | _O_EXCL | _O_NOINHERIT | _O_BINARY, _SH_DENYRW,
                   _S_IREAD | _S_IWRITE) != 0) {
        return nullptr;
    }
    FILE* f = ::_fdopen(fd, "wb");
    if (!f) ::_close(fd);
    return f;
#else
    int fd = ::open(path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_TRUNC, 0600);
    if (fd < 0) return nullptr;
    FILE* f = ::fdopen(fd, "wb");
    if (!f) ::close(fd);
    return f;
#endif
}

// Replacement open for a caller-owned staging file. The Java save paths create
// the staging file (and its 0600 mode) before handing it to the bridge, so an
// exclusive create would fail and a second native sibling would only duplicate
// the atomic publish Java already performs.
FILE* safe_fopen_replace(const char* path) {
#if defined(_WIN32)
    int fd = -1;
    if (::_sopen_s(&fd, path, _O_WRONLY | _O_CREAT | _O_TRUNC | _O_NOINHERIT | _O_BINARY,
                   _SH_DENYRW, _S_IREAD | _S_IWRITE) != 0) {
        return nullptr;
    }
    FILE* f = ::_fdopen(fd, "wb");
    if (!f) ::_close(fd);
    return f;
#else
    int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, 0600);
    if (fd < 0) return nullptr;
    FILE* f = ::fdopen(fd, "wb");
    if (!f) ::close(fd);
    return f;
#endif
}

// Sibling staging file for an atomic publish.
//
// Writing straight to the destination destroys it the moment the save fails:
// the sink opens with O_TRUNC, and the failure path removes "the partial
// output" - which is the user's pre-existing file. Serializing into a sibling
// and renaming means a refused save leaves the destination byte-for-byte
// untouched. The sibling is what makes the publish a rename rather than a
// cross-device copy. It carries mode 0600 while partial; publish_staged
// restores the destination mode (existing or umask default) before renaming.
std::string staging_sibling(const char* path) {
    static std::atomic<unsigned> counter{0};
    const unsigned n = counter.fetch_add(1, std::memory_order_relaxed);
#if defined(_WIN32)
    const long pid = static_cast<long>(GetCurrentProcessId());
#else
    const long pid = static_cast<long>(::getpid());
#endif
    return std::string(path) + ".jpdfium-save-" + std::to_string(pid) + "-" + std::to_string(n) +
           ".tmp";
}

// Attempts to open a fresh sibling staging file. Exclusive creation turns a
// planted entry into EEXIST, but so does a stale leftover from a crashed run
// (PID reuse plus a reset counter can regenerate the name), so retry with a
// fresh counter suffix instead of failing the first collision. Persistent
// contention still fails closed after a bounded number of attempts, and
// non-collision errors fail immediately.
constexpr int kStagingOpenAttempts = 32;

FILE* open_staging_sibling(const char* path, std::string& stagingOut) {
    for (int attempt = 0; attempt < kStagingOpenAttempts; ++attempt) {
        stagingOut = staging_sibling(path);
        FILE* f = safe_fopen_write(stagingOut.c_str());
        if (f) return f;
        if (errno != EEXIST) return nullptr;
    }
    return nullptr;
}

// Rename staging over the destination. Falls back to a non-atomic replace when
// the platform refuses (a cross-device rename cannot happen for a sibling file,
// but some filesystems reject rename onto an open target).
//
// Staging carries mode 0600 from exclusive creation: right for a partial PDF and
// wrong for the published artifact when the destination exists (a rename would
// hand it owner-only permissions and drop the replaced file's mode). An existing
// destination keeps its mode (mirror applyDestinationPermissions on the Java
// side); a new one keeps 0600. Learning the umask default from a 0666 probe file
// was rejected (CodeQL cpp/world-writable-file-creation) and umask(2) would race
// process-wide; Windows inherits the directory ACL on move.
bool publish_staged(const std::string& staging, const char* destination) {
#if defined(_WIN32)
    // Windows rename fails if the target exists, so MOVEFILE_REPLACE_EXISTING is
    // the replace path. If it fails the destination is left exactly as it was:
    // removing it and retrying would destroy the previous file on a path that
    // then also fails, which is the very loss this staging exists to prevent.
    if (::MoveFileExA(staging.c_str(), destination,
                      MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        return true;
    }
    // Last resort for the "target exists" case older filesystems report. Only
    // reached when MoveFileEx declined without touching the destination.
    if (std::rename(staging.c_str(), destination) == 0) return true;
    std::remove(staging.c_str());
    return false;
#else
    // Zeroed with memset rather than brace-init: empty-brace spacing churns
    // between clang-format versions and this file must satisfy both.
    struct stat st;
    std::memset(&st, 0, sizeof(st));
    if (::stat(destination, &st) == 0) {
        ::chmod(staging.c_str(), st.st_mode & 07777);
    }
    // No destination yet: the staging keeps its 0600 creation mode. A probe
    // file would be created 0666 (CodeQL cpp/world-writable-file-creation, the
    // reason output_transaction.cpp dropped the same probe) and umask(2) races
    // process-wide. Restrictive beats permissive when the default cannot be
    // determined without either.
    return std::rename(staging.c_str(), destination) == 0;
#endif
}

// Direct-to-file FPDF_FILEWRITE sink: streams PDFium serialization blocks
// straight to a native FILE* with zero document-sized buffering. The callback
// is allocation-free, must not let exceptions escape, and enforces the byte
// budget inline so an unbounded save is aborted mid-write instead of after the
// fact.
//
// Failure model, so the caller can tell the stages apart:
//   - budget exhaustion / short write -> failed = true, write_errno untouched
//     for the budget case (no I/O error occurred), set from errno otherwise.
//   - first failure wins: once failed is set every later block short-circuits,
//     so PDFium sees the error and stops instead of writing more.
//   - fflush and fclose are checked separately by the caller, so a flush
//     failure is distinguishable from a close failure.
struct FileWriteContext : FPDF_FILEWRITE {
    FILE* file = nullptr;
    std::uint64_t bytes_written = 0;
    std::uint64_t max_bytes = 0;  // 0 = unlimited
    int write_errno = 0;
    bool failed = false;
    // True when the failure was the byte budget rather than the filesystem, so
    // callers can report "output too large" instead of "I/O error".
    bool budget_exceeded = false;

    // Named Write, not WriteBlock: a member function with the same name as the
    // inherited FPDF_FILEWRITE field would hide that field, and the
    // assignment `ctx.WriteBlock = FileWriteContext::WriteBlock` would then try
    // to assign a function to itself instead of to the inherited pointer.
    //
    // A C callback must not propagate exceptions across the ABI boundary, so
    // this is not marked noexcept: the operations below (fwrite, errno) are
    // C calls that cannot throw, and the body contains no throwing operation.
    static int Write(FPDF_FILEWRITE* writer, const void* data, unsigned long size) {
        auto* ctx = static_cast<FileWriteContext*>(writer);
        if (!ctx || ctx->failed || !ctx->file) return 0;
        // A zero-length block is legal and is not an error; PDFium may emit one
        // at the end of a stream.
        if (size == 0) return 1;
        if (!data) {
            ctx->failed = true;
            return 0;
        }
        if (ctx->max_bytes > 0) {
            // Subtraction form: never computes bytes_written + size, so the
            // cumulative total cannot overflow uint64_t.
            if (ctx->bytes_written >= ctx->max_bytes ||
                static_cast<std::uint64_t>(size) > ctx->max_bytes - ctx->bytes_written) {
                ctx->failed = true;
                ctx->budget_exceeded = true;
                return 0;
            }
        }
        const size_t written = std::fwrite(data, 1, size, ctx->file);
        if (written != size) {
            // Partial write: the destination now holds a prefix that is not a
            // valid PDF. Marking failed makes the caller discard the file
            // rather than publish it.
            ctx->failed = true;
            ctx->write_errno = errno;
            return 0;
        }
        ctx->bytes_written += size;
        return 1;
    }
};

// Streams PDFium's serialization into a fresh sibling staging file. On success
// |stagingOut| holds a complete PDF and |bytesWritten| is its size; the caller
// publishes it. On failure the staging file is removed and the save fails.
// Open the output sink for a save. In staging mode a fresh sibling is created
// and published over `path`; in direct mode the caller already owns `path` as a
// private staging file and no second sibling (or second rename) is needed.
FILE* open_output_sink(const char* path, bool staged, std::string& stagingOut) {
    if (staged) return open_staging_sibling(path, stagingOut);
    stagingOut = path;
    return safe_fopen_replace(path);
}

int32_t streamDocumentToStaging(FPDF_DOCUMENT doc, const char* path, int64_t max_bytes,
                                std::string& stagingOut, int64_t& bytesWritten, bool staged) {
    FILE* f = open_output_sink(path, staged, stagingOut);
    if (!f) return JPDFIUM_ERR_IO;

    FileWriteContext ctx;
    ctx.version = 1;
    ctx.WriteBlock = FileWriteContext::Write;
    ctx.file = f;
    ctx.max_bytes = max_bytes > 0 ? static_cast<std::uint64_t>(max_bytes) : 0;

    // PDFium's own outcome and the sink's are recorded separately: a save can
    // return true while the sink failed, or fail while everything written so
    // far was fine. Reporting only one of them would misattribute the cause.
    const int pdfium_ok = FPDF_SaveAsCopy(doc, &ctx, FPDF_NO_INCREMENTAL);
    const int flushRc = std::fflush(f);
    const int closeRc = std::fclose(f);
    ctx.file = nullptr;

    if (!pdfium_ok || ctx.failed || flushRc != 0 || closeRc != 0) {
        // Never publish a partial destination: a truncated PDF is worse than
        // no output, so the staging file is discarded and the stage that
        // failed is what gets reported. The destination is never opened, so
        // an existing file at that path survives untouched.
        std::remove(stagingOut.c_str());
        if (ctx.budget_exceeded) return JPDFIUM_ERR_TOO_LARGE;
        return JPDFIUM_ERR_IO;
    }
    // An empty result is not a valid PDF, so treat it as a failure rather than
    // reporting a zero-byte success.
    if (ctx.bytes_written == 0) {
        std::remove(stagingOut.c_str());
        return JPDFIUM_ERR_IO;
    }
    bytesWritten = static_cast<int64_t>(ctx.bytes_written);
    return JPDFIUM_OK;
}

}  // namespace

std::atomic<bool> g_jpdfiumUseSkia = false;

namespace {
bool g_libraryInitialized = false;
std::atomic<int> g_pdfiumAuthority{static_cast<int>(PdfiumAuthority::None)};
}  // namespace

bool jpdfium_claim_authority(PdfiumAuthority who) noexcept {
    int expected = static_cast<int>(PdfiumAuthority::None);
    if (g_pdfiumAuthority.compare_exchange_strong(expected, static_cast<int>(who))) return true;
    return expected == static_cast<int>(who);
}

int jpdfium_init_library(int renderer) {
    // The safe jpdf_* runtime serializes natively; mixing it with this bridge would mean two locks.
    if (!jpdfium_claim_authority(PdfiumAuthority::Legacy)) return JPDFIUM_ERR_INVALID;
    return jpdfium_init_pdfium_core(renderer);
}

int jpdfium_init_pdfium_core(int renderer) {
    // Re-initialising would reset g_jpdfiumUseSkia while PDFium keeps the
    // renderer it was configured with, so the first selection wins.
    if (g_libraryInitialized) return JPDFIUM_OK;
    if (renderer != JPDFIUM_RENDERER_AUTO && renderer != JPDFIUM_RENDERER_AGG &&
        renderer != JPDFIUM_RENDERER_SKIA) {
        return JPDFIUM_ERR_INVALID;
    }
#ifdef JPDFIUM_HAS_SKIA
    // AUTO (-1) and SKIA (1) both pick Skia; AGG (0) forces the legacy path.
    bool useSkia = renderer != JPDFIUM_RENDERER_AGG;
    FPDF_LIBRARY_CONFIG config{};
    config.version = 4;
    config.m_RendererType = useSkia ? FPDF_RENDERERTYPE_SKIA : FPDF_RENDERERTYPE_AGG;
    FPDF_InitLibraryWithConfig(&config);
    g_jpdfiumUseSkia = useSkia;
#else
    // Selecting Skia from a build without it crashes PDFium, so refuse.
    if (renderer == JPDFIUM_RENDERER_SKIA) return JPDFIUM_ERR_INVALID;
    FPDF_InitLibrary();
    g_jpdfiumUseSkia = false;
#endif
    g_libraryInitialized = true;
    return JPDFIUM_OK;
}

void jpdfium_ensure_library() {
    if (!g_libraryInitialized) {
        jpdfium_init_library(JPDFIUM_RENDERER_AUTO);
    }
}

int32_t jpdfium_init() noexcept {
    return jpdfium_init_library(JPDFIUM_RENDERER_AUTO);
}

uint32_t jpdfium_abi_version() JPDFIUM_NOEXCEPT {
    return JPDFIUM_ABI_VERSION;
}

int64_t jpdfium_abi_query(int32_t query) JPDFIUM_NOEXCEPT {
    switch (query) {
        case JPDFIUM_ABI_QUERY_PTR_SIZE:
            return static_cast<int64_t>(sizeof(void*));
        case JPDFIUM_ABI_QUERY_RECTF_SIZE:
            return static_cast<int64_t>(sizeof(FS_RECTF));
        case JPDFIUM_ABI_QUERY_RECTF_RIGHT_OFFSET:
            return static_cast<int64_t>(offsetof(FS_RECTF, right));
        case JPDFIUM_ABI_QUERY_ULONG_SIZE:
            return static_cast<int64_t>(sizeof(unsigned long));
        case JPDFIUM_ABI_QUERY_RECTF_LEFT_OFFSET:
            return static_cast<int64_t>(offsetof(FS_RECTF, left));
        case JPDFIUM_ABI_QUERY_RECTF_BOTTOM_OFFSET:
            return static_cast<int64_t>(offsetof(FS_RECTF, bottom));
        case JPDFIUM_ABI_QUERY_RECTF_TOP_OFFSET:
            return static_cast<int64_t>(offsetof(FS_RECTF, top));
        case JPDFIUM_ABI_QUERY_MATRIX_SIZE:
            return static_cast<int64_t>(sizeof(FS_MATRIX));
        case JPDFIUM_ABI_QUERY_FILEWRITE_SIZE:
            return static_cast<int64_t>(sizeof(FPDF_FILEWRITE));
        case JPDFIUM_ABI_QUERY_FILEWRITE_VERSION:
            // FPDF_FILEWRITE.version must be 1 per fpdf_save.h; the save path
            // sets it explicitly and the handshake pins the expected value.
            return 1;
        case JPDFIUM_ABI_QUERY_HAS_SKIA:
#ifdef JPDFIUM_HAS_SKIA
            return 1;
#else
            return 0;
#endif
        case JPDFIUM_ABI_QUERY_HAS_QPDF:
#ifdef JPDFIUM_HAS_QPDF
            return 1;
#else
            return 0;
#endif
        default:
            return -1;
    }
}

int32_t jpdfium_init_ex(int32_t renderer) noexcept {
    return jpdfium_init_library(renderer);
}

int32_t jpdfium_active_renderer() noexcept {
    return g_jpdfiumUseSkia ? JPDFIUM_RENDERER_SKIA : JPDFIUM_RENDERER_AGG;
}

void jpdfium_destroy() noexcept {
    // Only the legacy owner may destroy. Under the safe runtime the domain owns PDFium's
    // lifetime and tears it down through jpdf_runtime_shutdown, so an unguarded destroy here
    // would free PDFium while the domain still admits work.
    if (g_pdfiumAuthority.load() != static_cast<int>(PdfiumAuthority::Legacy)) return;
    jpdfium_destroy_pdfium_core();
}

bool jpdfium_core_uses_skia() noexcept {
    return g_jpdfiumUseSkia;
}

void jpdfium_destroy_pdfium_core() noexcept {
    FPDF_DestroyLibrary();
    g_libraryInitialized = false;
    g_jpdfiumUseSkia = false;
}

int32_t jpdfium_doc_create(int64_t* handle) {
    try {
        if (!handle) return JPDFIUM_ERR_INVALID;
        FPDF_DOCUMENT doc = FPDF_CreateNewDocument();
        if (!doc) return JPDFIUM_ERR_NATIVE;

        auto w = std::make_unique<DocWrapper>();
        w->core = makeDocCore(doc);
        auto* raw = w.release();
        DocHandleRegistry::instance().add(raw);
        *handle = encodeHandle(raw);
        jpdf::Domain::instance().document_opened(doc);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_open(const char* path, int64_t* handle) {
    try {
        if (!path || !handle) return JPDFIUM_ERR_INVALID;
        FPDF_DOCUMENT doc = FPDF_LoadDocument(path, nullptr);
        if (!doc) return translatePdfiumError();

        auto w = std::make_unique<DocWrapper>();
        // Record the source path so a later save can refuse to overwrite it.
        w->core = makeDocCore(doc, nullptr, 0, path);
        auto* raw = w.release();
        DocHandleRegistry::instance().add(raw);
        *handle = encodeHandle(raw);
        jpdf::Domain::instance().document_opened(doc);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_open_bytes(const uint8_t* data, int64_t len, int64_t* handle) {
    try {
        // FPDF_LoadMemDocument takes a 32-bit int length; reject negative lengths
        // and documents larger than PDFium's API can address before copying.
        if (!data || !handle || len <= 0 || len > INT32_MAX) return JPDFIUM_ERR_INVALID;
        uint8_t* copy = static_cast<uint8_t*>(malloc(static_cast<size_t>(len)));
        if (!copy) return JPDFIUM_ERR_NATIVE;
        memcpy(copy, data, static_cast<size_t>(len));

        FPDF_DOCUMENT doc = FPDF_LoadMemDocument(copy, static_cast<int>(len), nullptr);
        if (!doc) {
            free(copy);
            return translatePdfiumError();
        }

        auto w = std::make_unique<DocWrapper>();
        w->core = makeDocCore(doc, copy, len);
        auto* raw = w.release();
        DocHandleRegistry::instance().add(raw);
        *handle = encodeHandle(raw);
        jpdf::Domain::instance().document_opened(doc);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_open_bytes_protected(const uint8_t* data, int64_t len, const char* password,
                                         int64_t* handle) {
    try {
        if (!data || !handle || len <= 0 || len > INT32_MAX) return JPDFIUM_ERR_INVALID;
        uint8_t* copy = static_cast<uint8_t*>(malloc(static_cast<size_t>(len)));
        if (!copy) return JPDFIUM_ERR_NATIVE;
        memcpy(copy, data, static_cast<size_t>(len));

        FPDF_DOCUMENT doc = FPDF_LoadMemDocument(copy, static_cast<int>(len), password);
        if (!doc) {
            free(copy);
            return translatePdfiumError();
        }

        auto w = std::make_unique<DocWrapper>();
        w->core = makeDocCore(doc, copy, len);
        auto* raw = w.release();
        DocHandleRegistry::instance().add(raw);
        *handle = encodeHandle(raw);
        jpdf::Domain::instance().document_opened(doc);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_open_protected(const char* path, const char* password, int64_t* handle) {
    try {
        if (!path || !handle) return JPDFIUM_ERR_INVALID;
        FPDF_DOCUMENT doc = FPDF_LoadDocument(path, password);
        if (!doc) return translatePdfiumError();

        auto w = std::make_unique<DocWrapper>();
        // Record the source path like jpdfium_doc_open so a later save refuses
        // to overwrite its own backing file (O_TRUNC would destroy the input
        // PDFium is still reading).
        w->core = makeDocCore(doc, nullptr, 0, path);
        auto* raw = w.release();
        DocHandleRegistry::instance().add(raw);
        *handle = encodeHandle(raw);
        jpdf::Domain::instance().document_opened(doc);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_layer_open(const char* base_path, const char* delta_path, int64_t* handle) {
    if (!base_path || !*base_path || !handle) return JPDFIUM_ERR_INVALID;
    CachedBase cached = acquireLayerBase(base_path);
    if (!cached.entry) return cached.error;
    EPDF_BASE_DOCUMENT base = cached.entry->base;
    EPDFLayerOpenStatus status = EPDFLayerOpenStatus_kOpenFailed;
    FPDF_DOCUMENT layer = nullptr;
    if (delta_path && *delta_path) {
        layer = EPDFLayer_OpenLayerArtifactFromPath(base, delta_path, nullptr, &status);
    } else {
        layer = EPDFLayer_OpenLayer(base, nullptr, nullptr, &status);
    }
    if (!layer) return JPDFIUM_ERR_NATIVE;

    auto w = std::make_unique<DocWrapper>();
    w->core = makeDocCore(layer);
    w->core->layerBase = base;
    w->core->layerBaseBytes = cached.entry->bytes.data();
    w->core->layerBaseLen = cached.entry->bytes.size();
    w->core->layerBaseOwner = cached.entry;
    auto* raw = w.release();
    DocHandleRegistry::instance().add(raw);
    *handle = encodeHandle(raw);
    jpdf::Domain::instance().document_opened(layer);
    return JPDFIUM_OK;
}

namespace {
int32_t save_layer_delta(FPDF_DOCUMENT doc, const char* path, bool* changedOut) {
    std::FILE* out = std::fopen(path, "wb");
    if (!out) return JPDFIUM_ERR_IO;
    FileWriteContext ctx;
    ctx.version = 1;
    ctx.WriteBlock = FileWriteContext::Write;
    ctx.file = out;
    EPDFLayerSaveStatus status = EPDFLayerSaveStatus_kSuccess;
    FPDF_BOOL changed = 0;
    // The artifact streams its delta through the runtime's temporary file,
    // so a large delta is never held whole in memory (unlike the owned-buffer
    // variant used before).
    const bool ok = EPDFLayer_SaveLayerArtifactEx(doc, &ctx, &status, &changed);
    const bool flushed = std::fflush(out) == 0;
    const bool closed = std::fclose(out) == 0;
    if (changedOut) *changedOut = changed != 0;
    if (!ok || ctx.failed || !flushed || !closed || status != EPDFLayerSaveStatus_kSuccess) {
        return status == EPDFLayerSaveStatus_kAppendOnlyOffsetTooLarge ? JPDFIUM_ERR_TOO_LARGE
                                                                       : JPDFIUM_ERR_IO;
    }
    return JPDFIUM_OK;
}
}  // namespace

int32_t jpdfium_layer_save_delta(int64_t doc, const char* path, int32_t* changed) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core || !w->core->layerBase || !path || !*path) return JPDFIUM_ERR_INVALID;
    bool didChange = false;
    int32_t rc = save_layer_delta(w->core->doc, path, &didChange);
    if (changed) *changed = didChange ? 1 : 0;
    return rc;
}

int32_t jpdfium_layer_materialize(int64_t doc, const char* path) {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core || !w->core->layerBase || !path || !*path) return JPDFIUM_ERR_INVALID;
    std::FILE* out = std::fopen(path, "wb");
    if (!out) return JPDFIUM_ERR_IO;
    bool ok = std::fwrite(w->core->layerBaseBytes, 1, w->core->layerBaseLen, out) ==
              w->core->layerBaseLen;
    FileWriteContext ctx;
    ctx.version = 1;
    ctx.WriteBlock = FileWriteContext::Write;
    ctx.file = out;
    EPDFLayerSaveStatus status = EPDFLayerSaveStatus_kSuccess;
    FPDF_BOOL changed = 0;
    // Stream the delta straight after the base; the old path materialised the
    // whole delta in an owned buffer first only to copy it into the file.
    if (ok) {
        ok = EPDFLayer_SaveDeltaEx(w->core->doc, &ctx, &status, &changed);
    }
    if (ok) ok = !ctx.failed && std::fflush(out) == 0;
    if (std::fclose(out) != 0) ok = false;
    if (!ok) {
        return status == EPDFLayerSaveStatus_kAppendOnlyOffsetTooLarge ? JPDFIUM_ERR_TOO_LARGE
                                                                       : JPDFIUM_ERR_IO;
    }
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_page_count(int64_t doc, int32_t* count) {
    if (!count) return JPDFIUM_ERR_INVALID;
    try {
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
        *count = FPDF_GetPageCount(w->core->doc);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

namespace {

// Opt-in sanitize stage: when sanitizeOnSave is enabled on a redacted document,
// runs a qpdf pass (dead-object purge, metadata/XMP/annotation/form/outline scrub,
// ToUnicode filtering, hb-subset font erasure).
int32_t applySanitizeStage(DocWrapper* w, std::vector<uint8_t>& bytes) {
    if (!w->core->contentRedacted || !w->core->sanitizeOnSave) return JPDFIUM_OK;
    std::vector<uint8_t> sanitized;
    std::string report;
    if (sanitizeRedactedPdf(bytes.data(), bytes.size(), *w->core, sanitized, report) != 0) {
        w->core->sanitizeReport = report;
        return JPDFIUM_ERR_REDACT_UNVERIFIABLE;
    }
    w->core->sanitizeReport = report;
    bytes = std::move(sanitized);
    return JPDFIUM_OK;
}

// Every post-serialization stage, in order: erased gray images back to
// /DeviceGray (best effort: PDFium's bytes are already complete), then the
// opt-in sanitize pass. Only the buffered save path uses this; a gray-only
// file save streams through the file-backed compaction instead.
int32_t applySaveStages(DocWrapper* w, std::vector<uint8_t>& bytes) {
    compactErasedGrayImages(*w->core, bytes);
    return applySanitizeStage(w, bytes);
}

// True when a save needs the whole serialization for the in-memory sanitize
// stage. Gray compaction is not a reason: it has a file-to-file path.
bool needsBufferedSaveStages(const DocCore& core) {
    return core.contentRedacted && core.sanitizeOnSave;
}

}  // namespace
namespace {
// Alias protection helper: saving over the document's own backing file would
// truncate/replace the input PDFium is still reading from (lazy reads).
// String equality misses /a/../x.pdf, relative paths, and symlinks, so when
// both files exist compare filesystem identity; otherwise compare normalized
// absolute paths.
bool isSaveAlias(const std::string& sourcePath, const char* destPath) {
    if (sourcePath.empty() || !destPath || !*destPath) return false;
    std::error_code ec;
    // equivalent() handles symlinks, ../, and relative paths via identity
    // (st_dev/st_ino on POSIX). Only when both exist; a non-existent
    // destination cannot alias the source yet.
    if (std::filesystem::exists(sourcePath, ec) && std::filesystem::exists(destPath, ec)) {
        std::error_code ec2;
        if (std::filesystem::equivalent(sourcePath, destPath, ec2) && !ec2) return true;
        if (!ec2) return false;
        // Fall through to string compare on error.
    }
    try {
        auto srcAbs = std::filesystem::absolute(sourcePath).lexically_normal().string();
        auto dstAbs = std::filesystem::absolute(destPath).lexically_normal().string();
        return srcAbs == dstAbs;
    } catch (...) {
        return sourcePath == destPath;
    }
}
}  // namespace

// Staging-file stdio buffer. PDFium hands the saver a stream of small blocks and
// the platform default buffer turns each one into its own write(2), so a larger
// buffer batches them. Shared by both save branches.
static constexpr std::size_t kSaveIoBufferBytes = static_cast<std::size_t>(256) * 1024;

static int32_t save_to_file_impl(int64_t doc, const char* path, int64_t max_bytes,
                                 int64_t* out_bytes, bool staged) {
    try {
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
        if (!path || !*path) return JPDFIUM_ERR_INVALID;
        if (max_bytes < 0) return JPDFIUM_ERR_INVALID;
        // A redaction that stopped half-way leaves the surviving content
        // extractable (see DocCore::hasIncompleteRedaction): refuse every save
        // until the caller resolves the page. Checked before the pending-marks
        // guard so the caller sees the more specific error.
        if (w->core->hasIncompleteRedaction()) return JPDFIUM_ERR_REDACT_INCOMPLETE;
        if (w->core->hasUnappliedRedactMarks()) return JPDFIUM_ERR_UNCOMMITTED_MARKS;
        if (out_bytes) *out_bytes = 0;

        // Alias protection first: every save branch stages and renames over the
        // source, which would destroy the input PDFium is still reading from.
        // Checked before any output is opened.
        if (isSaveAlias(w->core->sourcePath, path)) {
            return JPDFIUM_ERR_INVALID;
        }

        // The sanitize stage mutates the whole serialization after PDFium
        // writes it, so it keeps the buffered path. Everything else, including
        // gray-image compaction, stays file-backed.
        if (needsBufferedSaveStages(*w->core)) {
            // The budget applies to PDFium's output too, so an oversized save
            // stops instead of first buffering the whole document. The stages
            // only shrink or scrub, so this refuses nothing the streaming
            // branch would have accepted for the same content.
            struct BufWriter : FPDF_FILEWRITE {
                std::vector<uint8_t> buf;
                std::uint64_t max_bytes = 0;
                bool budget_exceeded = false;
                static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
                    auto* bw = static_cast<BufWriter*>(self);
                    if (bw->max_bytes > 0 && bw->buf.size() + size > bw->max_bytes) {
                        bw->budget_exceeded = true;
                        return 0;
                    }
                    try {
                        auto* src = static_cast<const uint8_t*>(data);
                        bw->buf.insert(bw->buf.end(), src, src + size);
                        return 1;
                    } catch (...) {
                        return 0;  // never let exceptions cross the C callback
                    }
                }
            } bw;
            bw.version = 1;
            bw.WriteBlock = BufWriter::Write;
            bw.max_bytes = max_bytes > 0 ? static_cast<std::uint64_t>(max_bytes) : 0;

            if (!FPDF_SaveAsCopy(w->core->doc, &bw, FPDF_NO_INCREMENTAL)) {
                return bw.budget_exceeded ? JPDFIUM_ERR_TOO_LARGE : JPDFIUM_ERR_IO;
            }
            int32_t sanitizeRc = applySaveStages(w, bw.buf);
            if (sanitizeRc != JPDFIUM_OK) return sanitizeRc;
            // Same code as the streaming branch so callers see one error
            // category for "output exceeded the budget".
            if (max_bytes > 0 && static_cast<int64_t>(bw.buf.size()) > max_bytes)
                return JPDFIUM_ERR_TOO_LARGE;

            // Allocated before the file exists: a throw here leaves nothing to clean up.
            // The buffer must outlive the FILE*, so it stays in scope until after
            // fclose.
            std::vector<char> ioBuffer(kSaveIoBufferBytes);
            std::string staging;
            FILE* f = open_output_sink(path, staged, staging);
            if (!f) return JPDFIUM_ERR_IO;
            std::setvbuf(f, ioBuffer.data(), _IOFBF, ioBuffer.size());
            size_t written = bw.buf.empty() ? 0 : fwrite(bw.buf.data(), 1, bw.buf.size(), f);
            int closeRc = fclose(f);
            if (written != bw.buf.size() || closeRc != 0) {
                std::remove(staging.c_str());
                return JPDFIUM_ERR_IO;
            }
            if (staged && !publish_staged(staging, path)) {
                std::remove(staging.c_str());
                return JPDFIUM_ERR_IO;
            }
            if (out_bytes) *out_bytes = static_cast<int64_t>(written);
            return JPDFIUM_OK;
        }

        // Gray compaction rewrites the serialized PDF, but it does not need a
        // buffer: PDFium streams to one staging file and qpdf compacts that
        // file into a second. The compacted file is published only when the
        // rewrite succeeds; otherwise the first file (already a complete
        // redaction) is published. Two byte buffers never coexist.
        if (!w->core->erasedGrayImages.empty()) {
            std::string staging;
            int64_t written = 0;
            int32_t writeRc =
                streamDocumentToStaging(w->core->doc, path, max_bytes, staging, written, staged);
            if (writeRc != JPDFIUM_OK) return writeRc;

            std::string compacted;
            FILE* compactedFile = open_staging_sibling(path, compacted);
            bool useCompacted = false;
            int64_t compactedBytes = 0;
            if (compactedFile) {
                useCompacted =
                    compactErasedGrayImagesFile(*w->core, staging.c_str(), compactedFile);
                const int closeRc = std::fclose(compactedFile);
                if (useCompacted && closeRc == 0) {
                    std::error_code ec;
                    const auto size = std::filesystem::file_size(compacted, ec);
                    // The first file already fits the budget; only prefer the
                    // compacted file when it does too and really has content.
                    if (ec || size == 0 ||
                        (max_bytes > 0 && static_cast<int64_t>(size) > max_bytes)) {
                        useCompacted = false;
                    } else {
                        compactedBytes = static_cast<int64_t>(size);
                    }
                } else {
                    useCompacted = false;
                }
            }

            if (useCompacted) {
                if (!publish_staged(compacted, path)) {
                    std::remove(compacted.c_str());
                    std::remove(staging.c_str());
                    return JPDFIUM_ERR_IO;
                }
                if (staged) std::remove(staging.c_str());
                if (out_bytes) *out_bytes = compactedBytes;
                return JPDFIUM_OK;
            }
            if (!compacted.empty()) std::remove(compacted.c_str());
            if (staged && !publish_staged(staging, path)) {
                std::remove(staging.c_str());
                return JPDFIUM_ERR_IO;
            }
            if (out_bytes) *out_bytes = written;
            return JPDFIUM_OK;
        }

        std::string staging;
        int64_t written = 0;
        int32_t writeRc =
            streamDocumentToStaging(w->core->doc, path, max_bytes, staging, written, staged);
        if (writeRc != JPDFIUM_OK) return writeRc;
        if (staged && !publish_staged(staging, path)) {
            std::remove(staging.c_str());
            return JPDFIUM_ERR_IO;
        }
        if (out_bytes) *out_bytes = written;
        return JPDFIUM_OK;
    } catch (...) {
        // Never let a C++ exception cross the FFM boundary.
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_doc_save_to_file(int64_t doc, const char* path, int64_t max_bytes,
                                 int64_t* out_bytes) {
    return save_to_file_impl(doc, path, max_bytes, out_bytes, true);
}

// Direct variant for callers that already own `path` as a private staging file
// (the Java OutputTransaction save paths). It keeps alias protection, budget
// enforcement and failure cleanup, but writes to the given file instead of
// creating a second sibling and renaming twice.
int32_t jpdfium_doc_save_to_file_unstaged(int64_t doc, const char* path, int64_t max_bytes,
                                          int64_t* out_bytes) {
    return save_to_file_impl(doc, path, max_bytes, out_bytes, false);
}

int32_t jpdfium_doc_save(int64_t doc, const char* path) {
    return jpdfium_doc_save_to_file(doc, path, 0, nullptr);
}

int32_t jpdfium_doc_save_bytes(int64_t doc, uint8_t** data, int64_t* len) {
    // Both outputs are required: every success path writes through them.
    if (!data || !len) return JPDFIUM_ERR_INVALID;
    // Grows with realloc so the finished buffer IS the caller's buffer: the
    // old vector-then-malloc+memcpy tail copied every byte twice. The pointer
    // stays malloc-family owned, so jpdfium_free_buffer keeps working.
    struct MallocWriter : FPDF_FILEWRITE {
        uint8_t* buf = nullptr;
        size_t size = 0;
        size_t cap = 0;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            auto* mw = static_cast<MallocWriter*>(self);
            if (size == 0) return 1;
            // Same callback contract as FileWriteContext::Write: a null buffer
            // with a non-zero size is a PDFium violation, not a memcpy source.
            if (!data) return 0;
            const size_t need = mw->size + static_cast<size_t>(size);
            if (need < mw->size) return 0;  // size_t wraparound
            if (need > mw->cap) {
                // One realloc per write: PDFium can hand the whole document to a
                // single WriteBlock, and doubling in a loop would copy the
                // growing prefix once per step. Geometric across writes, but a
                // single large write jumps straight to what it needs.
                size_t ncap = mw->cap ? mw->cap * 2 : 65536;
                if (ncap < need) ncap = need;  // also recovers a wrapped cap*2
                uint8_t* nbuf = static_cast<uint8_t*>(std::realloc(mw->buf, ncap));
                if (!nbuf) return 0;
                mw->buf = nbuf;
                mw->cap = ncap;
            }
            std::memcpy(mw->buf + mw->size, data, static_cast<size_t>(size));
            mw->size = need;
            return 1;
        }
    } mw;
    mw.version = 1;
    mw.WriteBlock = MallocWriter::Write;

    // The guard shim for this entry point is noexcept, so decodeDoc and the
    // vector allocations below must not let an exception escape: the whole
    // body runs inside this firewall so mw.buf is always released on failure.
    try {
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;
        // Same contract as jpdfium_doc_save_to_file: a half-finished redaction is
        // not savable, and that refusal wins over the pending-marks one.
        if (w->core->hasIncompleteRedaction()) return JPDFIUM_ERR_REDACT_INCOMPLETE;
        if (w->core->hasUnappliedRedactMarks()) return JPDFIUM_ERR_UNCOMMITTED_MARKS;

        if (!FPDF_SaveAsCopy(w->core->doc, &mw, FPDF_NO_INCREMENTAL)) {
            std::free(mw.buf);
            mw.buf = nullptr;
            return JPDFIUM_ERR_IO;
        }

        // The sanitize stage works on vectors and may replace the buffer, so only
        // the sanitize and in-memory gray-compaction path funnels through one
        // vector (same copies as before).
        if (w->core->contentRedacted &&
            (w->core->sanitizeOnSave || !w->core->erasedGrayImages.empty())) {
            std::vector<uint8_t> vec(mw.buf, mw.buf + mw.size);
            std::free(mw.buf);
            mw.buf = nullptr;
            int32_t sanitizeRc = applySaveStages(w, vec);
            if (sanitizeRc != JPDFIUM_OK) return sanitizeRc;
            size_t sz = vec.size();
            // malloc(0) is permitted to return NULL, which is indistinguishable
            // from an allocation failure; an empty save result is legitimate, so
            // allocate one byte for it.
            uint8_t* out = static_cast<uint8_t*>(std::malloc(sz ? sz : 1));
            if (!out) return JPDFIUM_ERR_NATIVE;
            if (sz) std::memcpy(out, vec.data(), sz);
            *data = out;
            *len = static_cast<int64_t>(sz);
            return JPDFIUM_OK;
        }

        if (mw.size == 0) {
            std::free(mw.buf);
            mw.buf = nullptr;
            uint8_t* out = static_cast<uint8_t*>(std::malloc(1));
            if (!out) return JPDFIUM_ERR_NATIVE;
            *data = out;
            *len = 0;
            return JPDFIUM_OK;
        }
        *data = mw.buf;
        *len = static_cast<int64_t>(mw.size);
        return JPDFIUM_OK;
    } catch (...) {
        // decodeDoc, hasUnappliedRedactMarks or a vector allocation can throw;
        // the buffer from the partial save must not leak and this entry point
        // must report instead of terminating.
        std::free(mw.buf);
        mw.buf = nullptr;
        return JPDFIUM_ERR_NATIVE;
    }
}

void jpdfium_doc_close(int64_t doc) noexcept {
    // Remove from the registry before deleting, and delete only what the
    // registry vouches for. Previously this was `delete decodeDoc(doc)` with an
    // unvalidated cast, so closing a bogus or already-closed handle reached
    // operator delete on an arbitrary address.
    DocWrapper* w = decodeDoc(doc);
    if (!w) return;
    const FPDF_DOCUMENT raw_doc = w->core ? w->core->doc : nullptr;
    DocHandleRegistry::instance().remove(w);
    delete w;
    jpdf::Domain::instance().document_closed(raw_doc);
}

int32_t jpdfium_page_open(int64_t doc, int32_t idx, int64_t* handle) {
    if (!handle) return JPDFIUM_ERR_INVALID;
    try {
        DocWrapper* w = decodeDoc(doc);
        if (!w || !w->core || !w->core->doc) return JPDFIUM_ERR_INVALID;

        FPDF_PAGE page = FPDF_LoadPage(w->core->doc, idx);
        if (!page) return JPDFIUM_ERR_NOT_FOUND;

        auto* pw = new PageWrapper(page, w->core->doc, idx, w->core);
        PageHandleRegistry::instance().add(pw);
        *handle = encodeHandle(pw);
        jpdf::Domain::instance().page_opened(page);
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_page_width(int64_t page, float* width) {
    if (!width) return JPDFIUM_ERR_INVALID;
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        *width = static_cast<float>(FPDF_GetPageWidth(pw->page));
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_page_height(int64_t page, float* height) {
    if (!height) return JPDFIUM_ERR_INVALID;
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
        *height = static_cast<float>(FPDF_GetPageHeight(pw->page));
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

int32_t jpdfium_page_info(int64_t page, float* width, float* height) {
    try {
        PageWrapper* pw = decodePage(page);
        if (!pw || !pw->page || !width || !height) return JPDFIUM_ERR_INVALID;
        *width = static_cast<float>(FPDF_GetPageWidth(pw->page));
        *height = static_cast<float>(FPDF_GetPageHeight(pw->page));
        return JPDFIUM_OK;

    } catch (...) {
        return JPDFIUM_ERR_NATIVE;
    }
}

void jpdfium_page_close(int64_t page) noexcept {
    // Validate against the registry before touching the pointer. Close must
    // still free the wrapper of a page whose document was replaced, so the
    // staleness check below deliberately does not gate the delete - but
    // membership does, because an unregistered address must never be deleted.
    PageWrapper* pw = reinterpret_cast<PageWrapper*>(static_cast<uintptr_t>(page));
    if (!pw || !PageHandleRegistry::instance().contains(pw)) return;
    if (pw->page) {
        // A pending progressive render holds this raw page identity in its
        // map; retire it before the page itself is freed so a later session
        // step or close can never operate on the freed identity. The stale
        // path must not call into PDFium (its document is gone), so it only
        // forgets the map entry and destroys the caller-owned bitmap.
        if (!pw->stale()) {
            jpdfium_render_abandon_progressive(pw->page);
        } else {
            jpdfium_render_forget_progressive(pw->page);
        }
    }
    const FPDF_PAGE raw_page = pw->page;
    PageHandleRegistry::instance().remove(pw);
    delete pw;
    jpdf::Domain::instance().page_closed(raw_page);
}

// JSON report of the last sanitize stage ("" when none has run).
int32_t jpdfium_doc_sanitize_report(int64_t doc, char** json) noexcept {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core || !json) return JPDFIUM_ERR_INVALID;
    const std::string& rep = w->core->sanitizeReport;
    char* out = static_cast<char*>(malloc(rep.size() + 1));
    if (!out) return JPDFIUM_ERR_NATIVE;
    memcpy(out, rep.data(), rep.size());
    out[rep.size()] = 0;
    *json = out;
    return JPDFIUM_OK;
}

int32_t jpdfium_doc_set_sanitize_on_save(int64_t doc, int32_t enable) noexcept {
    DocWrapper* w = decodeDoc(doc);
    if (!w || !w->core) return JPDFIUM_ERR_INVALID;
    w->core->sanitizeOnSave = (enable != 0);
    return JPDFIUM_OK;
}

int64_t jpdfium_doc_raw_handle(int64_t doc) noexcept {
    DocWrapper* w = decodeDoc(doc);
    return w && w->core && w->core->doc
               ? static_cast<int64_t>(reinterpret_cast<uintptr_t>(w->core->doc))
               : 0;
}

int64_t jpdfium_page_raw_handle(int64_t page) noexcept {
    PageWrapper* pw = decodePage(page);
    return pw && pw->page ? static_cast<int64_t>(reinterpret_cast<uintptr_t>(pw->page)) : 0;
}

int64_t jpdfium_page_doc_raw_handle(int64_t page) noexcept {
    PageWrapper* pw = decodePage(page);
    return pw && pw->doc ? static_cast<int64_t>(reinterpret_cast<uintptr_t>(pw->doc)) : 0;
}

int32_t jpdfium_import_n_pages_to_one(void* srcDoc, float outputWidth, float outputHeight,
                                      int32_t cols, int32_t rows, uint8_t** output,
                                      int64_t* outputLen) JPDFIUM_NOEXCEPT {
    if (!srcDoc || !output || !outputLen || cols < 1 || rows < 1) return JPDFIUM_ERR_INVALID;

    FPDF_DOCUMENT nupDoc =
        FPDF_ImportNPagesToOne(static_cast<FPDF_DOCUMENT>(srcDoc), outputWidth, outputHeight,
                               static_cast<size_t>(cols), static_cast<size_t>(rows));

    if (!nupDoc) return JPDFIUM_ERR_NATIVE;

    // Collect the saved copy in memory. `bw.buf` can grow, so the body runs inside a catch
    // that still closes the N-up document.
    struct BufWriter : FPDF_FILEWRITE {
        std::vector<uint8_t> buf;
        static int Write(FPDF_FILEWRITE* self, const void* data, unsigned long size) {
            auto* bw = static_cast<BufWriter*>(self);
            try {
                auto* src = static_cast<const uint8_t*>(data);
                bw->buf.insert(bw->buf.end(), src, src + size);
                return 1;
            } catch (...) {
                return 0;  // never let exceptions cross the C callback
            }
        }
    };

    try {
        BufWriter bw{};
        bw.version = 1;
        bw.WriteBlock = BufWriter::Write;

        const int ok = FPDF_SaveAsCopy(nupDoc, &bw, FPDF_NO_INCREMENTAL);
        FPDF_CloseDocument(nupDoc);
        nupDoc = nullptr;

        if (!ok) return JPDFIUM_ERR_IO;

        const size_t sz = bw.buf.size();
        uint8_t* out = static_cast<uint8_t*>(malloc(sz ? sz : 1));
        if (!out) return JPDFIUM_ERR_NATIVE;
        if (sz) memcpy(out, bw.buf.data(), sz);
        *output = out;
        *outputLen = static_cast<int64_t>(sz);
        return JPDFIUM_OK;
    } catch (...) {
        FPDF_CloseDocument(nupDoc);
        return JPDFIUM_ERR_NATIVE;
    }
}
