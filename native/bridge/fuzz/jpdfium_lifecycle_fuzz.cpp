// jpdfium_lifecycle_fuzz - libFuzzer harness for bridge lifecycle sequences.
//
// Complements the parser-oriented jpdfium_fuzz target: inputs decode to a
// framed (magic, pdf length, pdf bytes, opcodes) sequence of bridge operations
// over slot resources. A reference model tracks document generation and slot
// states, asserting the lifecycle contract: stale operations fail with
// JPDFIUM_ERR_INVALID, undersized renders fail without writing, closes are
// idempotent where promised. Any crash, hang, sanitizer finding, or contract
// violation aborts the run.
//
// Build alongside jpdfium_fuzz:
//   cmake -DCMAKE_CXX_COMPILER=clang++ -DJPDFIUM_BUILD_FUZZERS=ON \
//         -DJPDFIUM_SANITIZE=address,undefined
//   ./jpdfium_lifecycle_fuzz -max_total_time=120 corpus/

#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "jpdfium.h"

namespace {

// Bounds keep the fuzzer exploring behavior instead of exhausting memory.
constexpr size_t kMaxInputSize = 4 * 1024 * 1024;
constexpr size_t kMaxPages = 4;
constexpr int32_t kRenderW = 32;
constexpr int32_t kRenderH = 32;
constexpr int32_t kRenderStride = kRenderW * 4;
constexpr size_t kRenderBytes = static_cast<size_t>(kRenderStride) * kRenderH;
constexpr int kMaxSteps = 64;
constexpr uint8_t kMagic[4] = {'J', 'P', 'L', 'F'};

bool g_initialized = false;

void ensureInit() {
    if (!g_initialized) {
        jpdfium_init();
        g_initialized = true;
    }
}

enum class SlotState : uint8_t { kEmpty, kOpen, kProgressive, kInvalidated, kClosed };

struct Slot {
    int64_t page = 0;
    SlotState state = SlotState::kEmpty;
};

enum class DocState : uint8_t { kOpen, kClosed };

uint8_t nextByte(const uint8_t* ops, size_t opsLen, size_t& pos) {
    if (pos >= opsLen) return 0;
    return ops[pos++];
}

uint32_t readU32LE(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

}  // namespace

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    if (size < 8 || size > kMaxInputSize) return 0;
    ensureInit();

    // Framed format: magic[4] + pdf_length u32LE + pdf bytes + opcode bytes.
    // Binary PDFs may contain NUL bytes, so length-prefix framing (not NUL
    // splitting) separates the payload from the operation tail.
    if (data[0] != kMagic[0] || data[1] != kMagic[1] || data[2] != kMagic[2] ||
        data[3] != kMagic[3]) {
        return 0;
    }
    uint32_t pdfLen = readU32LE(data + 4);
    if (pdfLen == 0 || pdfLen > size - 8) return 0;
    const uint8_t* pdf = data + 8;
    const uint8_t* ops = pdf + pdfLen;
    size_t opsLen = size - 8 - pdfLen;

    int64_t doc = 0;
    if (jpdfium_doc_open_bytes(pdf, static_cast<int64_t>(pdfLen), &doc) != JPDFIUM_OK) return 0;

    int32_t count = 0;
    if (jpdfium_doc_page_count(doc, &count) != JPDFIUM_OK || count <= 0) {
        jpdfium_doc_close(doc);
        return 0;
    }

    std::vector<uint8_t> target(kRenderBytes, 0);
    std::vector<uint8_t> tiny(16, 0);
    int32_t cancelFlag = 0;
    Slot slots[kMaxPages] = {};
    size_t pos = 0;
    DocState docState = DocState::kOpen;
    int strips = 0;

    auto rawPage = [](int64_t handle) {
        return reinterpret_cast<void*>(jpdfium_page_raw_handle(handle));
    };
    auto markInvalidated = [&slots]() {
        for (auto& s : slots) {
            if (s.state == SlotState::kOpen || s.state == SlotState::kProgressive) {
                s.state = SlotState::kInvalidated;
            }
        }
    };

    // Contract assertions use fuzzer-abort semantics: a violated invariant is a
    // finding, exactly like a crash. Only contractual outcomes are asserted
    // (invalid input -> JPDFIUM_ERR_INVALID); content-dependent success is
    // never asserted, since exotic PDFs may legitimately fail an operation.
    // Raw-pointer render paths carry no generation, so they run only on OPEN
    // slots; handle-based paths additionally prove stale rejection. Raw page
    // handles are never closed twice: idempotent close holds only through the
    // Java CAS wrapper, not for raw int64 values.
    for (int step = 0; step < kMaxSteps && pos <= opsLen; ++step) {
        uint8_t op = nextByte(ops, opsLen, pos);
        uint8_t arg = nextByte(ops, opsLen, pos);
        size_t slot = static_cast<size_t>(arg) % kMaxPages;
        bool docOpen = (docState == DocState::kOpen);

        switch (op % 12) {
            case 0: {  // open page into slot
                if (!docOpen || slots[slot].state != SlotState::kEmpty) break;
                int32_t idx = static_cast<int32_t>(arg) % (count > 0 ? count : 1);
                if (idx < 0) idx = 0;
                int64_t page = 0;
                if (jpdfium_page_open(doc, idx, &page) == JPDFIUM_OK) {
                    slots[slot].page = page;
                    slots[slot].state = SlotState::kOpen;
                }
                break;
            }
            case 1: {  // close page slot exactly once
                if (slots[slot].state != SlotState::kOpen &&
                    slots[slot].state != SlotState::kInvalidated) {
                    break;
                }
                jpdfium_page_close(slots[slot].page);
                slots[slot].state = SlotState::kClosed;
                break;
            }
            case 2: {  // render_into with valid capacity
                if (slots[slot].state != SlotState::kOpen) break;
                jpdfium_render_page_into(rawPage(slots[slot].page), target.data(), target.size(),
                                         kRenderW, kRenderH, kRenderStride, 0);
                break;
            }
            case 3: {  // undersized capacity must fail loudly, never write OOB
                if (slots[slot].state != SlotState::kOpen) break;
                int32_t rc =
                    jpdfium_render_page_into(rawPage(slots[slot].page), tiny.data(), tiny.size(),
                                             kRenderW, kRenderH, kRenderStride, 0);
                if (rc != JPDFIUM_ERR_INVALID) abort();
                break;
            }
            case 4: {  // progressive start, then continue or cancel-close
                if (slots[slot].state != SlotState::kOpen) break;
                cancelFlag = (arg & 1);
                int32_t rc = jpdfium_render_page_progressive_start(
                    rawPage(slots[slot].page), target.data(), target.size(), kRenderW, kRenderH,
                    kRenderStride, 0, &cancelFlag);
                if (rc == JPDFIUM_RENDER_TOBECONTINUED) {
                    slots[slot].state = SlotState::kProgressive;
                }
                break;
            }
            case 5: {  // progressive continue + close
                if (slots[slot].state != SlotState::kProgressive) break;
                cancelFlag = (arg & 1);
                jpdfium_render_page_progressive_continue(rawPage(slots[slot].page), &cancelFlag);
                jpdfium_render_page_progressive_close(rawPage(slots[slot].page));
                slots[slot].state = SlotState::kOpen;
                break;
            }
            case 6: {  // structural mutation invalidates open slots
                if (!docOpen || strips >= 2) break;
                ++strips;
                for (size_t i = 0; i < kMaxPages; ++i) {
                    if (slots[i].state == SlotState::kProgressive) {
                        jpdfium_render_page_progressive_close(rawPage(slots[i].page));
                    }
                }
                // Only invalidate when the call actually committed: the native
                // side bumps the generation only on the success path.
                if (jpdfium_metadata_strip_all(doc) == 0) {
                    markInvalidated();
                }
                break;
            }
            case 7: {  // page_to_image invalidates open slots
                if (!docOpen) break;
                // Drop any pending progressive render first: page_to_image frees
                // and replaces the page, so a live session would resume into a
                // freed FPDF_PAGE. Same teardown as case 6.
                for (size_t i = 0; i < kMaxPages; ++i) {
                    if (slots[i].state == SlotState::kProgressive) {
                        jpdfium_render_page_progressive_close(rawPage(slots[i].page));
                    }
                }
                if (jpdfium_page_to_image(doc, 0, 36) == 0) {
                    markInvalidated();
                }
                break;
            }
            case 8: {  // text extraction proves stale rejection
                if (slots[slot].state == SlotState::kInvalidated) {
                    char* json = nullptr;
                    int32_t rc = jpdfium_text_get_chars(slots[slot].page, &json);
                    if (rc != JPDFIUM_ERR_INVALID) abort();
                    if (json) jpdfium_free_string(json);
                    break;
                }
                if (slots[slot].state != SlotState::kOpen) break;
                char* json = nullptr;
                if (jpdfium_text_get_chars(slots[slot].page, &json) == JPDFIUM_OK) {
                    jpdfium_free_string(json);
                }
                break;
            }
            case 9: {  // mark + commit redaction on open slots only
                if (slots[slot].state != SlotState::kOpen) break;
                int32_t n = 0;
                jpdfium_annot_create_redact(slots[slot].page, 5.0f, 5.0f, 20.0f, 20.0f, 0, nullptr);
                jpdfium_redact_commit(slots[slot].page, 0xFF000000, 1, &n);
                break;
            }
            case 10: {  // save variants
                if (!docOpen) break;
                uint8_t* out = nullptr;
                int64_t outLen = 0;
                if (jpdfium_doc_save_bytes(doc, &out, &outLen) == JPDFIUM_OK) {
                    jpdfium_free_buffer(out);
                }
                out = nullptr;
                outLen = 0;
                if (jpdfium_doc_save_incremental(doc, &out, &outLen) == JPDFIUM_OK) {
                    jpdfium_free_buffer(out);
                }
                break;
            }
            default: {  // close document once; pages are closed first, in order
                if (!docOpen) break;
                for (auto& s : slots) {
                    if (s.state == SlotState::kOpen || s.state == SlotState::kInvalidated) {
                        jpdfium_page_close(s.page);
                        s.state = SlotState::kClosed;
                    } else if (s.state == SlotState::kProgressive) {
                        jpdfium_render_page_progressive_close(rawPage(s.page));
                        jpdfium_page_close(s.page);
                        s.state = SlotState::kClosed;
                    }
                }
                jpdfium_doc_close(doc);
                docState = DocState::kClosed;
                break;
            }
        }
    }

    for (auto& s : slots) {
        if (s.state == SlotState::kOpen || s.state == SlotState::kInvalidated) {
            jpdfium_page_close(s.page);
        } else if (s.state == SlotState::kProgressive) {
            jpdfium_render_page_progressive_close(rawPage(s.page));
            jpdfium_page_close(s.page);
        }
    }
    if (docState == DocState::kOpen) jpdfium_doc_close(doc);
    return 0;
}
