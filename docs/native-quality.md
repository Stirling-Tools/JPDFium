# Native C++ quality and security pass

This pass used the project `.clang-tidy`, cppcheck, and ASan/UBSan to harden the
C++ bridge. It targeted ownership, exception boundaries, integer conversions,
and non-deterministic output; no functional PDF behaviour was changed.

## Tools

```sh
cppcheck --project=native/build-real/compile_commands.json \
  --enable=warning,performance,portability --inline-suppr \
  --suppress=missingIncludeSystem --suppress=missingInclude -j 8

run-clang-tidy -p native/build-real -j 8 \
  -extra-arg=-isystem -extra-arg="$(xcrun --show-sdk-path)/usr/include/c++/v1" \
  -extra-arg=-isystem -extra-arg="$(xcrun --show-sdk-path)/usr/include" \
  -source-filter='.*/bridge/src/.*'

ASAN_OPTIONS=detect_container_overflow=0 ctest --test-dir native/build-asan
```

## Fixes

- **C ABI ownership under exceptions.** Document and page creation now build
  their owning `shared_ptr`/`unique_ptr` before registering the raw handle, so a
  registry allocation failure cannot leak a PDFium document or page. Page close
  frees through `unique_ptr`.
- **qpdf output stream.** `QPDFWriter` is told not to close the output `FILE*`;
  a `unique_ptr` owns it for the whole write, including exception paths.
- **`noexcept` exception escapes.** Annotation JSON serialization and the stub
  backend now convert allocation failures to `JPDFIUM_ERR_NATIVE` instead of
  terminating at the C ABI boundary.
- **Staging-file collision reporting.** `safe_fopen_write` reports `EEXIST` via
  an explicit out-parameter rather than relying on a caller-side `errno` read.
- **Integer and conversion safety.** Pixel loops use `size_t` totals, stencil
  bit writes use `std::bit_cast` (the standard-defined object-representation
  conversion, https://en.cppreference.com/w/cpp/numeric/bit_cast), size limits
  use `size_t`/`long long`, and image/pattern geometry uses explicit
  floating-point conversions.
- **Checked numeric input.** `JPDFIUM_FLATTEN_JPEG_QUALITY` and forced PDF
  version strings use `strtol` with full-consumption and range checks instead of
  `atoi`/`sscanf`.
- **Initialization.** PDF/A and font rewrite contexts have constructors and
  default member values; shape/text/SFNT records no longer depend on aggregate
  zero-initialization.
- **Deterministic redaction.** Match object positions and promotion candidates
  are visited in document order rather than pointer-hash order; destruction is
  ordered by depth and document index.
- **Dead logic.** Removed an unused `requiresFission` flag whose only remaining
  effect was to stop a scan.

## Residual diagnostics

The project checks still report performance/style suggestions (by-value
`QPDFObjectHandle` parameters, `std::move` opportunities, enum base widths) and
two known false-positive categories: throwing static initialization for
constexpr/`thread_local` defaults, and branch-clone on equivalent switch/if
bodies. There are **zero** `clang-analyzer-*`, `bugprone-exception-escape`,
`bugprone-unused-return-value`, `bugprone-narrowing-conversions`,
`bugprone-implicit-widening-of-multiplication-result`, or `bugprone-empty-catch`
findings; cppcheck reports zero warnings.

## Verification

- Integration suite: `4895` tests, `0` failures, `0` errors, `315` skipped.
- Release ctest: `6/6` passed.
- ASan+UBSan ctest: `6/6` passed with
  `ASAN_OPTIONS=detect_container_overflow=0`. Default ASan still reports the
  external Homebrew qpdf `getAllObjects()` container-overflow described in the
  earlier audit; it is not reproduced by standalone qpdf probes and is not
  claimed green here.
