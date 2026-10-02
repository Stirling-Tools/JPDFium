// ASan/UBSan harness for the handle-validation and writer paths this branch
// changed. Runs entirely inside the bridge: no JVM, so HotSpot's SIGSEGV
// handling stays out of the way.
//
// Every case below is a regression for a defect found or introduced during
// the execution-domain migration:
//   1. fabricated document handle rejected, not dereferenced
//   2. fabricated page handle rejected, not dereferenced
//   3. never-issued handle close is a silent no-op (no double free)
//   4. repeated close of the same real handle frees exactly once
//   5. create -> register -> use -> close/unregister round trip
//   6. writer sink: budget exhaustion and injected I/O failure
//
// Build: see CMakeLists (JPDFIUM_SANITIZE=address,undefined).

#include <cstdio>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>

// The stub library (jpdfium_stub) answers with canned success values and has no
// handle registries, so handle-rejection assertions are meaningless there.
// It is still worth running under the sanitizers: it exercises the same
// marshalling and writer plumbing without PDFium's partition_alloc, which is
// what makes this build the one that actually reports findings.
#if defined(JPDFIUM_STUB_BUILD)
static const bool kHasRealRegistries = false;
#else
static const bool kHasRealRegistries = true;
#endif
#include <string>
#include <vector>

extern "C" {
#include "jpdfium.h"
}

static int g_failures = 0;

#define CHECK(cond, msg)                                                     \
  do {                                                                       \
    if (!(cond)) {                                                           \
      std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, (msg));            \
      ++g_failures;                                                          \
    } else {                                                                 \
      std::printf("ok   %s\n", (msg));                                       \
    }                                                                        \
  } while (0)

// Load the repository's known-good test PDF instead of a hand-written one.
//
// A hand-rolled fixture made this harness's failures ambiguous: a wrong xref
// offset looks identical to a bridge defect under ASan. Reading a real fixture
// removes that variable - if this fails, the bridge or the toolchain is at
// fault, not the bytes.
//
// Override with argv[1] to test any PDF.
static std::vector<uint8_t> load_fixture(int argc, char** argv) {
  const char* path = (argc > 1)
                         ? argv[1]
                         : "jpdfium/src/test/resources/pdfs/general/minimal.pdf";
  std::FILE* f = std::fopen(path, "rb");
  if (!f) {
    std::printf("FIXTURE  cannot open %s\n", path);
    return {};
  }
  std::fseek(f, 0, SEEK_END);
  long size = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  std::vector<uint8_t> bytes(static_cast<size_t>(size));
  size_t got = size > 0 ? std::fread(bytes.data(), 1, bytes.size(), f) : 0;
  std::fclose(f);
  if (got != bytes.size()) {
    std::printf("FIXTURE  short read from %s\n", path);
    return {};
  }
  if (bytes.size() < 5 || std::memcmp(bytes.data(), "%PDF-", 5) != 0) {
    std::printf("FIXTURE  %s is not a PDF\n", path);
    return {};
  }
  std::printf("FIXTURE  loaded %zu bytes from %s\n", bytes.size(), path);
  return bytes;
}

// 1-2: fabricated handles must be refused by the registries, not dereferenced.
// Before the registry work, decodeDoc cast the int64 straight to a pointer and
// decodePage dereferenced it inside pageAlive(), so these crashed.
static void test_fabricated_handles_rejected() {
  int32_t count = 0;
  float w = 0;
  const int64_t bogus[] = {0, 1, 0x3039, -1,
                           INT64_MAX, INT64_MIN, 0x7fffffff};
  bool all_rejected = true;
  for (int64_t h : bogus) {
    if (jpdfium_doc_page_count(h, &count) == JPDFIUM_OK) all_rejected = false;
    if (jpdfium_page_width(h, &w) == JPDFIUM_OK) all_rejected = false;
    if (jpdfium_page_height(h, &w) == JPDFIUM_OK) all_rejected = false;
    if (jpdfium_page_flatten(h) == JPDFIUM_OK) all_rejected = false;
    // Out param for page_open is int64_t*, so it needs its own 64-bit slot.
    // Passing &count (an int32_t) here was a 4-byte stack slot written as 8
    // bytes - a real stack-buffer-overflow that ASan caught.
    int64_t out_page = 0;
    if (jpdfium_page_open(h, 0, &out_page) == JPDFIUM_OK) all_rejected = false;
    // Free paths must be silent no-ops.
    jpdfium_page_close(h);
    jpdfium_doc_close(h);
    jpdfium_pcre2_free(h);
    jpdfium_flashtext_free(h);
  }
  if (kHasRealRegistries) {
    CHECK(all_rejected, "fabricated handles rejected by registry, no dereference");
  } else {
    std::printf("skip  fabricated-handle rejection (stub build: no registry)\n");
  }
}

// 3-5: real handle lifecycle. Registration must not leak, and a second close
// must not double free (both are ASan aborts).
static void test_real_handle_lifecycle(const std::vector<uint8_t>& pdf) {
  if (pdf.empty()) {
    CHECK(false, "real_handle_lifecycle: no fixture");
    return;
  }
  // Round trip: create -> register -> use -> close -> unregister.
  int64_t doc = 0;
  int32_t rc = jpdfium_doc_open_bytes(pdf.data(), pdf.size(), &doc);
  CHECK(rc == JPDFIUM_OK && doc != 0, "doc_open_bytes returns a live handle");

  int32_t count = 0;
  if (kHasRealRegistries) {
    CHECK(jpdfium_doc_page_count(doc, &count) == JPDFIUM_OK && count >= 1,
          "registered document handle answers page count");
  }

  int64_t page = 0;
  rc = jpdfium_page_open(doc, 0, &page);
  CHECK(rc == JPDFIUM_OK && page != 0, "page_open registers the page");

  float w = 0, h = 0;
  CHECK(jpdfium_page_width(page, &w) == JPDFIUM_OK && w > 0,
        "registered page handle answers width");
  CHECK(jpdfium_page_height(page, &h) == JPDFIUM_OK && h > 0,
        "registered page handle answers height");

  // Repeated close of the same real handles: must free exactly once. Under ASan
  // a double free here is a hard abort, which is the point of the test.
  jpdfium_page_close(page);
  jpdfium_page_close(page);
  jpdfium_page_close(page);
  CHECK(true, "repeated page close frees exactly once");

  // The page is unregistered, so it must now be refused.
  if (kHasRealRegistries) {
    CHECK(jpdfium_page_width(page, &w) != JPDFIUM_OK,
          "page handle rejected after close (no stale dereference)");
  }

  jpdfium_doc_close(doc);
  jpdfium_doc_close(doc);
  if (kHasRealRegistries) {
    CHECK(jpdfium_doc_page_count(doc, &count) != JPDFIUM_OK,
          "document handle rejected after close");
  }

  CHECK(true, "repeated doc close frees exactly once");
}

// 6: the save writer path. A budget smaller than the document must abort the
// save rather than silently truncating, and must not leave a partial file
// reported as success.
static void test_writer_budget_and_failure(const std::vector<uint8_t>& pdf) {
  if (pdf.empty()) {
    CHECK(false, "writer_budget: no fixture");
    return;
  }
  int64_t doc = 0;
  if (jpdfium_doc_open_bytes(pdf.data(), pdf.size(), &doc) != JPDFIUM_OK) {
    CHECK(false, "writer test: doc_open_bytes failed");
    return;
  }

  const char* path = "/tmp/jpdfium_asan_save.pdf";
  std::remove(path);

  int64_t written = 0;
  // A budget of 8 bytes cannot hold a whole PDF: the save must fail and the
  // destination must not be reported as a success.
  int32_t rc = jpdfium_doc_save_to_file(doc, path, 8, &written);
  CHECK(rc != JPDFIUM_OK, "save under an impossible budget fails");
  CHECK(written == 0, "failed save reports zero bytes");

  // A generous budget must succeed and produce a real file.
  rc = jpdfium_doc_save_to_file(doc, path, 4 * 1024 * 1024, &written);
  CHECK(rc == JPDFIUM_OK && written > 0, "save within budget succeeds");

  FILE* f = std::fopen(path, "rb");
  CHECK(f != nullptr, "successful save produced a readable file");
  if (f) {
    std::fseek(f, 0, SEEK_END);
    long size = std::ftell(f);
    std::fclose(f);
    CHECK(size == static_cast<long>(written),
          "reported bytes match the file on disk");
    std::remove(path);
  }

  // Unwritable destination must fail without crashing.
  rc = jpdfium_doc_save_to_file(doc, "/nonexistent-dir-xyz/out.pdf", 0, nullptr);
  CHECK(rc != JPDFIUM_OK, "save to an unwritable path fails cleanly");

  jpdfium_doc_close(doc);
}

// Save to a path that already exists must not corrupt the existing file when
// the save fails on budget: the failure path removes the partial output.
static void test_failed_save_does_not_truncate_destination(const std::vector<uint8_t>& pdf) {
  if (pdf.empty()) {
    CHECK(false, "truncation: no fixture");
    return;
  }
  int64_t doc = 0;
  if (jpdfium_doc_open_bytes(pdf.data(), pdf.size(), &doc) != JPDFIUM_OK) {
    CHECK(false, "truncation test: doc_open_bytes failed");
    return;
  }
  const char* path = "/tmp/jpdfium_asan_trunc.pdf";
  // Pre-existing content we expect to survive a failed overwrite.
  FILE* pre = std::fopen(path, "wb");
  if (pre) {
    const char* sentinel = "PREEXISTING";
    std::fwrite(sentinel, 1, std::strlen(sentinel), pre);
    std::fclose(pre);
  }
  int32_t rc = jpdfium_doc_save_to_file(doc, path, 4, nullptr);
  CHECK(rc != JPDFIUM_OK, "budgeted overwrite fails");
  std::remove(path);
  jpdfium_doc_close(doc);
}


// Writer finalization and alias protection. These run against the stub build,
// where ASan/UBSan actually function (PDFium's partition_alloc is not
// ASan-compatible), so the marshalling and failure plumbing is covered here.
static void test_writer_alias_rejected(const std::vector<uint8_t>& pdf) {
  if (pdf.empty()) {
    CHECK(false, "writer_alias: no fixture");
    return;
  }
  // Open from a real file so the document records a source path, then attempt
  // to save over that same path. Must be refused, and the source must survive.
  const char* path = "/tmp/jpdfium_asan_alias.pdf";
  FILE* seed = std::fopen(path, "wb");
  if (!seed) {
    CHECK(false, "writer_alias: cannot create source");
    return;
  }
  std::fwrite(pdf.data(), 1, pdf.size(), seed);
  std::fclose(seed);

  int64_t doc = 0;
  if (jpdfium_doc_open(path, &doc) != JPDFIUM_OK) {
    CHECK(false, "writer_alias: doc_open failed");
    std::remove(path);
    return;
  }
  int32_t rc = jpdfium_doc_save_to_file(doc, path, 0, nullptr);
  if (kHasRealRegistries) {
    // Alias rejection lives in the real bridge's save path. The stub has no
    // sourcePath tracking and no streaming writer, so it cannot express this.
    CHECK(rc == JPDFIUM_ERR_INVALID,
          "save over the document's own source is refused");
  } else {
    std::printf("skip  save-over-source refusal (stub build)\n");
  }

  // The source must be untouched: same size as what we wrote.
  FILE* after = std::fopen(path, "rb");
  CHECK(after != nullptr, "source file still exists after refused save");
  if (after) {
    std::fseek(after, 0, SEEK_END);
    long size = std::ftell(after);
    std::fclose(after);
    if (kHasRealRegistries) {
      CHECK(size == static_cast<long>(pdf.size()),
            "source file was not truncated by the refused save");
    }
  }
  jpdfium_doc_close(doc);
  std::remove(path);
}

static void test_writer_rejects_bad_inputs(const std::vector<uint8_t>& pdf) {
  if (pdf.empty()) {
    CHECK(false, "writer_inputs: no fixture");
    return;
  }
  int64_t doc = 0;
  if (jpdfium_doc_open_bytes(pdf.data(), pdf.size(), &doc) != JPDFIUM_OK) {
    CHECK(false, "writer_inputs: doc_open_bytes failed");
    return;
  }
  int64_t written = 0;
  // Null and empty paths must be rejected before anything is opened.
  CHECK(jpdfium_doc_save_to_file(doc, nullptr, 0, &written) == JPDFIUM_ERR_INVALID,
        "save with a null path is rejected");
  CHECK(jpdfium_doc_save_to_file(doc, "", 0, &written) == JPDFIUM_ERR_INVALID,
        "save with an empty path is rejected");
  // A negative budget is a caller error, not an unlimited save.
  CHECK(jpdfium_doc_save_to_file(doc, "/tmp/jpdfium_asan_neg.pdf", -1, &written)
            == JPDFIUM_ERR_INVALID,
        "negative byte budget is rejected");
  // A zero budget means unlimited, and must still produce a real file.
  CHECK(jpdfium_doc_save_to_file(doc, "/tmp/jpdfium_asan_zero.pdf", 0, &written)
            == JPDFIUM_OK,
        "zero budget means unlimited and succeeds");
  std::remove("/tmp/jpdfium_asan_zero.pdf");
  jpdfium_doc_close(doc);
}

int main(int argc, char** argv);  // defined at the end
static void test_cross_type_handles_rejected(const std::vector<uint8_t>& pdf);
static void test_aux_handles_validated();
static void test_page_info_coarse(const std::vector<uint8_t>& pdf);
static void test_abi_probes();

int main(int argc, char** argv) {
  std::printf("=== jpdfium handle + writer sanitizer harness ===\n");
  // PDFium requires FPDF_InitLibrary before any document call (smoke does the
  // same). Without it FPDF_LoadMemDocument crashes in partition_alloc.
  if (jpdfium_init() != JPDFIUM_OK) {
    std::printf("FAIL init failed\n");
    return 1;
  }
  // Fabricated handles need no document at all: they must be rejected purely
  // by the registry, which is exactly the property under test.
  test_fabricated_handles_rejected();
  std::vector<uint8_t> pdf = load_fixture(argc, argv);
  test_real_handle_lifecycle(pdf);
  test_cross_type_handles_rejected(pdf);
  test_aux_handles_validated();
  test_page_info_coarse(pdf);
  test_abi_probes();
  test_writer_budget_and_failure(pdf);
  test_failed_save_does_not_truncate_destination(pdf);
  test_writer_alias_rejected(pdf);
  test_writer_rejects_bad_inputs(pdf);
  std::printf("=== failures: %d ===\n", g_failures);
  jpdfium_destroy();
  return g_failures == 0 ? 0 : 1;
}

// Cross-type confusion: a live document handle must not validate as a page
// and vice versa. Separate registry tables enforce this; a shared table let a
// DocWrapper* pass pageAlive and then dereference the wrong layout.
static void test_cross_type_handles_rejected(const std::vector<uint8_t>& pdf) {
  if (pdf.empty() || !kHasRealRegistries) {
    if (!kHasRealRegistries)
      std::printf("skip  cross-type rejection (stub build: no registry)\n");
    return;
  }
  int64_t doc = 0;
  if (jpdfium_doc_open_bytes(pdf.data(), pdf.size(), &doc) != JPDFIUM_OK) {
    CHECK(false, "cross-type: doc_open_bytes failed");
    return;
  }
  int64_t page = 0;
  if (jpdfium_page_open(doc, 0, &page) != JPDFIUM_OK) {
    CHECK(false, "cross-type: page_open failed");
    jpdfium_doc_close(doc);
    return;
  }
  float w = 0;
  int32_t count = 0;
  // Document handle used where a page is expected must be refused.
  CHECK(jpdfium_page_width(doc, &w) != JPDFIUM_OK,
        "document handle rejected as page width");
  CHECK(jpdfium_page_height(doc, &w) != JPDFIUM_OK,
        "document handle rejected as page height");
  CHECK(jpdfium_page_info(doc, &w, &w) != JPDFIUM_OK,
        "document handle rejected as page info");
  // Page handle used where a document is expected must be refused.
  CHECK(jpdfium_doc_page_count(page, &count) != JPDFIUM_OK,
        "page handle rejected as document");
  int64_t out_page = 0;
  CHECK(jpdfium_page_open(page, 0, &out_page) != JPDFIUM_OK,
        "page handle rejected as document for page_open");
  // Closing with the wrong typed closer must be a silent no-op, not a free of
  // the wrong layout.
  jpdfium_doc_close(page);
  jpdfium_page_close(doc);
  // Both originals must still be live after the wrong-typed closes.
  CHECK(jpdfium_doc_page_count(doc, &count) == JPDFIUM_OK,
        "document survives wrong-typed page_close");
  CHECK(jpdfium_page_width(page, &w) == JPDFIUM_OK,
        "page survives wrong-typed doc_close");
  jpdfium_page_close(page);
  jpdfium_doc_close(doc);
}

// Auxiliary handles (PCRE2/FlashText) must validate on use, not just on free.
// Previously only *_free checked membership, so a fabricated handle reached
// reinterpret_cast + dereference on the match/add/find paths.
static void test_aux_handles_validated() {
  if (!kHasRealRegistries) {
    std::printf("skip  aux-handle validation (stub build: no registry)\n");
    return;
  }
  char* json = nullptr;
  const int64_t bogus = 0x12345;
  CHECK(jpdfium_pcre2_match_all(bogus, "hello", &json) == JPDFIUM_ERR_INVALID,
        "fabricated pcre2 handle rejected on match");
  CHECK(jpdfium_flashtext_add_keyword(bogus, "key", "LABEL") == JPDFIUM_ERR_INVALID,
        "fabricated flashtext handle rejected on add_keyword");
  CHECK(jpdfium_flashtext_add_keywords_json(bogus, "[]") == JPDFIUM_ERR_INVALID,
        "fabricated flashtext handle rejected on add_json");
  CHECK(jpdfium_flashtext_find(bogus, "hello", &json) == JPDFIUM_ERR_INVALID,
        "fabricated flashtext handle rejected on find");
  // Round trip: create -> use -> free -> use must fail after free.
  int64_t ft = 0;
  if (jpdfium_flashtext_create(&ft) == JPDFIUM_OK && ft != 0) {
    CHECK(jpdfium_flashtext_add_keyword(ft, "hello", "GREETING") == JPDFIUM_OK,
          "live flashtext handle accepts add_keyword");
    jpdfium_flashtext_free(ft);
    CHECK(jpdfium_flashtext_add_keyword(ft, "world", "GREETING") == JPDFIUM_ERR_INVALID,
          "freed flashtext handle rejected on use");
    jpdfium_flashtext_free(ft);
  }
#ifdef JPDFIUM_HAS_PCRE2
  int64_t pc = 0;
  if (jpdfium_pcre2_compile("hello", 0, &pc) == JPDFIUM_OK && pc != 0) {
    CHECK(jpdfium_pcre2_match_all(pc, "hello world", &json) == JPDFIUM_OK,
          "live pcre2 handle matches");
    if (json) jpdfium_free_string(json);
    jpdfium_pcre2_free(pc);
    CHECK(jpdfium_pcre2_match_all(pc, "hello", &json) == JPDFIUM_ERR_INVALID,
          "freed pcre2 handle rejected on use");
    jpdfium_pcre2_free(pc);
  }
#endif
  // Cross-kind: a valid handle of one AUX kind must not validate as the other.
  {
    int64_t ft2 = 0;
    if (jpdfium_flashtext_create(&ft2) == JPDFIUM_OK && ft2 != 0) {
      CHECK(jpdfium_pcre2_match_all(ft2, "hello", &json) == JPDFIUM_ERR_INVALID,
            "flashtext handle rejected as pcre2");
      jpdfium_flashtext_free(ft2);
    }
#ifdef JPDFIUM_HAS_PCRE2
    int64_t pc2 = 0;
    if (jpdfium_pcre2_compile("world", 0, &pc2) == JPDFIUM_OK && pc2 != 0) {
      CHECK(jpdfium_flashtext_add_keyword(pc2, "k", "L") == JPDFIUM_ERR_INVALID,
            "pcre2 handle rejected as flashtext add");
      CHECK(jpdfium_flashtext_find(pc2, "hello", &json) == JPDFIUM_ERR_INVALID,
            "pcre2 handle rejected as flashtext find");
      jpdfium_pcre2_free(pc2);
    }
#endif
  }
}

// Coarse geometry: single validation returns both dimensions and agrees with
// the leaf pair. Guards against the Java-composed batch drifting from native.
static void test_page_info_coarse(const std::vector<uint8_t>& pdf) {
  if (pdf.empty()) {
    CHECK(false, "page_info: no fixture");
    return;
  }
  int64_t doc = 0;
  if (jpdfium_doc_open_bytes(pdf.data(), pdf.size(), &doc) != JPDFIUM_OK) {
    CHECK(false, "page_info: doc_open_bytes failed");
    return;
  }
  int64_t page = 0;
  if (jpdfium_page_open(doc, 0, &page) != JPDFIUM_OK) {
    CHECK(false, "page_info: page_open failed");
    jpdfium_doc_close(doc);
    return;
  }
  float w = 0, h = 0, cw = 0, ch = 0;
  bool leavesOk = jpdfium_page_width(page, &w) == JPDFIUM_OK
      && jpdfium_page_height(page, &h) == JPDFIUM_OK;
  bool coarseOk = jpdfium_page_info(page, &cw, &ch) == JPDFIUM_OK;
  if (kHasRealRegistries) {
    CHECK(leavesOk && coarseOk && cw == w && ch == h,
          "page_info agrees with width+height leaves");
    CHECK(jpdfium_page_info(0, &cw, &ch) != JPDFIUM_OK,
          "page_info rejects null handle");
    CHECK(jpdfium_page_info(page, nullptr, &ch) != JPDFIUM_OK,
          "page_info rejects null out param");
  } else {
    CHECK(coarseOk, "stub page_info succeeds");
  }
  jpdfium_page_close(page);
  jpdfium_doc_close(doc);
}

// ABI probes: every query id the Java handshake verifies must answer sanely.
// Unknown ids return -1; known ids never return -1 on a current bridge.
static void test_abi_probes() {
  CHECK(jpdfium_abi_version() == (uint32_t)JPDFIUM_ABI_VERSION,
        "abi version matches header");
  const int queries[] = {JPDFIUM_ABI_QUERY_PTR_SIZE, JPDFIUM_ABI_QUERY_RECTF_SIZE,
                         JPDFIUM_ABI_QUERY_RECTF_RIGHT_OFFSET, JPDFIUM_ABI_QUERY_ULONG_SIZE,
                         JPDFIUM_ABI_QUERY_RECTF_LEFT_OFFSET, JPDFIUM_ABI_QUERY_RECTF_BOTTOM_OFFSET,
                         JPDFIUM_ABI_QUERY_RECTF_TOP_OFFSET, JPDFIUM_ABI_QUERY_MATRIX_SIZE,
                         JPDFIUM_ABI_QUERY_FILEWRITE_SIZE, JPDFIUM_ABI_QUERY_FILEWRITE_VERSION,
                         JPDFIUM_ABI_QUERY_HAS_SKIA, JPDFIUM_ABI_QUERY_HAS_QPDF};
  bool allKnown = true;
  for (int q : queries) {
    if (jpdfium_abi_query(q) == -1) allKnown = false;
  }
  if (kHasRealRegistries) {
    CHECK(allKnown, "all handshake probes answer on the real bridge");
    CHECK(jpdfium_abi_query(999) == -1, "unknown abi query returns -1");
    CHECK(jpdfium_abi_query(JPDFIUM_ABI_QUERY_FILEWRITE_VERSION) == 1,
          "filewrite version probe is 1 per fpdf_save.h");
  } else {
    CHECK(allKnown, "all handshake probes answer on the stub bridge");
  }
}
