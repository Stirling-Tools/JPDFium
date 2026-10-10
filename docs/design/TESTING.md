# Test evidence

Each claim about the safe API names the level of evidence behind it.

| Level | What runs | Where |
|---|---|---|
| Stub | Java suite against `jpdfium_stub` (no PDFium) | Default CI |
| Unit | `api_object_table`: handle table alone, no PDFium | Every CMake build |
| Real native | `api_safety` plus the legacy native suites, against real PDFium | Corpus CI job, local container |
| Sanitized | Real native under ASan+UBSan, and TSan | Local only so far |
| Binding | PyJPDFium unit and native tests | PyJPDFium CI |
| Packaged | Installed wheel with bundled natives | PyJPDFium, manual so far |

Sanitizers instrument the bridge only. PDFium ships as uninstrumented prebuilt libraries, so a clean
run says nothing about PDFium internals.

## `api_safety` coverage

Lifecycle before and after init, the legacy authority latch, struct-size and flag validation,
stale, fabricated and wrong-type handles, slot reuse, `BUSY` parent close, bitmap and view
ownership across page, document and runtime shutdown, transactional PNG output (budget failure
leaves the destination intact with no staging leftovers), 8-thread renders of independent and
shared documents compared byte-for-byte, a 100-round close race, and shutdown refusal and
finality.

A mutation that replaced the domain mutex with a per-thread lock failed 11 checks (render
mismatches, close-race failures, corrupted accounting), so these tests detect a missing
serializer.

## Running real-native tests locally

```bash
bash native/fetch-prebuilt-pdfium.sh linux-x64
bash native/build-real.sh
ctest --test-dir native/build-real --output-on-failure
```

Run this inside a disposable Linux container. The fetch script does not verify a checksum;
compare the asset with the release's published digest
(`gh api repos/Stirling-Tools/JPDFium/releases/tags/<tag>`) before trusting it.

Sanitized builds need clang (the CMake flag `-shared-libasan` is clang-only) and executables
linked against the same shared runtime:

```bash
CC=clang CXX=clang++ cmake -B build-asan -S native -DJPDFIUM_USE_PDFIUM=ON \
  -DPDFIUM_DIR=$PWD/native/pdfium -DJPDFIUM_SANITIZE=address,undefined \
  -DCMAKE_EXE_LINKER_FLAGS=-shared-libsan
```

TSan needs the `personality` syscall, which Docker's default seccomp profile blocks.

## Not yet covered

- Windows (MSVC) and macOS builds of the safe API: written for both, compiled on neither yet.
- Render and save performance baselines on real-world PDFs.
- The rest of the Java suite against real natives.
