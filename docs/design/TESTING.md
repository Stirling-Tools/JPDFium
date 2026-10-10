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

## `api_guards` coverage

Guarded legacy calls refused before init with the refusal reported, one admission per guarded
call, a guarded call from inside a PDFium callback refused as re-entry, ordinary calls refused but
closes admitted while quiescing, resume, legacy and safe-API callers rendering byte-identically
on 8 threads in one process (the Java plus Python case), and bounded shutdown timing out behind a
busy domain.

## Java suite against real natives

With the Java lock removed, the full `:jpdfium:test` suite runs against real linux-x64 natives
with the same two failures as unchanged `main` (`FileBackedOptimizeTest`,
`SaveTransportTest`, both environment-dependent).

## Running real-native tests locally

```bash
bash native/fetch-prebuilt-pdfium.sh linux-x64
bash native/build-real.sh
ctest --test-dir native/build-real --output-on-failure
```

These steps need a Linux environment; a container works well. The release's asset digests are
listed by `gh api repos/Stirling-Tools/JPDFium/releases/tags/<tag>`.

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
