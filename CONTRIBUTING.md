# Contributing to JPDFium

Thank you for contributing to JPDFium. This guide covers local setup, architecture, and coding standards.

## Development Setup

### Prerequisites

- Java 25 JDK (Temurin, Oracle, or OpenJDK) with Foreign Function and Memory API support.
- C++23 compiler (`clang++`, `g++`, or MSVC 2022+).
- CMake 3.20 or newer.
- Rust toolchain (`cargo` and `rustc` 1.80+) for the native Rust acceleration crate.
- Optional: `libvips` development headers for `jpdfium-vips`.

### Quick Tryout (Stub Mode)

To work exclusively on Java-layer APIs without building PDFium:

```bash
./gradlew buildStubBridge
./gradlew test
```

The stub bridge exposes a lightweight C++ mock that validates Java FFM downcalls and object mapping without requiring a PDFium binary.

### Full Build with Real PDFium

To build the complete native bridge linked against PDFium:

```bash
bash native/setup-pdfium.sh
bash native/build-real.sh
./gradlew test :jpdfium:integrationTest
```

Subsequent native builds only need `bash native/build-real.sh`.

## Architecture Overview

JPDFium consists of four core layers:

1. **Native C++ Bridge (`native/bridge/`)**:
   - Implements high-performance C-ABI entry points in `native/bridge/src/`.
   - Bridges PDFium, PCRE2, FreeType, HarfBuzz, ICU, and QPDF.
   - Compiles with C++23 standard.
   - Header contracts are defined in `native/bridge/include/jpdfium.h`.

2. **Native Rust Integration (`native/rust/jpdfium-impl/`)**:
   - Provides SIMD image resizing (`fast_image_resize`).
   - Lossless PNG optimization (`oxipng`).
   - SVG rasterization (`resvg` / `usvg`).
   - High-ratio stream compression (`zopfli` and `lopdf`).
   - Statically compiled into `libjpdfium_rust.a` and linked into `libjpdfium`.

3. **Core Java Module (`jpdfium/`)**:
   - Public APIs: `PdfDocument`, `PdfPage`, `PdfImageConverter`, `PdfRenderer`.
   - Low-level FFM downcalls in `panama/` package (`JpdfiumLib`, `JpdfiumH`).
   - Zero-copy native buffers managed via `RenderedPageView`.
   - Document inspection, selective rasterization, and redaction engines.

4. **Vips Codec Extension (`jpdfium-vips/`)**:
   - Discovered dynamically via `java.util.ServiceLoader` implementing `ImageCodec`.
   - Provides high-throughput, zero-copy encoding for PNG, JPEG, TIFF, WEBP, AVIF, and HEIC.

## Adding New Features

When introducing a new native feature, follow this checklist:

1. **C Header**: Declare the signature in `native/bridge/include/jpdfium.h`. Use `JPDFIUM_EXPORT` with C linkage.
2. **Implementation**: Add implementation in `native/bridge/src/` with proper boundary validation.
3. **Stub Implementation**: Add matching mock behavior in `native/bridge/src/jpdfium_stub.cpp`.
4. **Java Downcall**: Add the binding in `jpdfium/src/main/java/stirling/software/jpdfium/panama/JpdfiumLib.java`.
5. **High-Level API**: Expose clean, fluent Java methods in `PdfDocument`, `PdfPage`, or dedicated service classes.
6. **Testing**: Add unit tests in `jpdfium/src/test/java/` and verify with `./gradlew test`.
7. **Benchmarks**: If performance-critical, measure with JMH (`./gradlew :jpdfium:jmh`).

## Ownership, Mutation, and Security Contracts

PDFium calls are serialized by a process-wide guard, but Java objects can outlive
the native state behind them. Keep this answer true for every change: **what prevents
this handle, buffer, or security operation from becoming unsafe when surrounding state changes?**

- **Identity vs state**: a `PdfDocument`/`PdfPage` is an identity; structural operations
  (metadata/font strip, page rasterization) free and replace native state. Such operations
  bump the document epoch and invalidate previously opened pages, which then fail loudly
  (`IllegalStateException`) instead of touching freed memory. Never silently retarget a page.
- **Mutation policy is invalidate, not reject or snapshot**: callers reopen pages after
  structural operations. Do not mix policies within one operation.
- **Buffers**: every native output buffer validates null, writability, scope, dimensions
  (overflow-safe), pixel budget, and byte capacity on the Java side, plus independent
  dimension checks natively. Render ABIs carry an explicit byte capacity.
- **Redaction**: content removal is mandatory (visual-only covers are refused in Java
  and rejected by the bridge). Sanitization on save is opt-in (`setSanitizeOnSave`).
- **Threads**:   handles are confined to one thread at a time; only `close()` is safe
  cross-thread. Do not add per-object monitors, use the existing global guard boundary.
- **Memory**: do not replace shared scratch or add pools without measurements (heap,
  retained native bytes, guard hold time) proving the benefit. Native tests that change
  bridge behavior must run against real natives, not just the stub.

## Performance Contributions

Every optimization must state its workload, measured benefit, bounded-memory policy,
and correctness regression. Optimize in this order: lifetime/capacity correctness,
PDFium-call count, crossing count, copies/peak bytes, guard hold time, allocation
rate/retention, native loops, and only then instruction-level tuning.

- Measure with JMH (micro), component drivers, and end-to-end runs; record heap
  bytes/op (`-prof gc`), peak RSS, guard wait/hold time (`NativeGuard.stats()`),
  calls/op, and budgets. Report intervals across forks, not single fastest runs.
- Prefer fewer calls and fewer copies over hotter leaves: batch repetitive tiny FFM
  calls, move detached I/O outside the guard, and validate buffer capacity in both
  Java and native code. Never mark rendering, parsing, saving, redaction, or text
  extraction as `critical` downcalls.
- Keep `Arena.ofConfined()` for single-call temporaries and `Arena.ofShared()` for
  cross-thread memory; never use `ofAuto()` or `global()` for bounded paths.
- Budgets: `jpdfium.maxRenderPixels`, `jpdfium.maxSaveResultBytes` (opt-in),
  and the caller-supplied bound in `PdfDocument.open(InputStream, long)`.
  Stream opens have **no** default cap - consumers opt in. Checked arithmetic
  everywhere.

## Code Standards and Style

- **Modern Java 25**: Use modern language features (records, pattern matching, switch expressions). Avoid Lombok.
- **Imports**: Never use inline fully-qualified class names. Always add explicit imports at the top of the file.
- **Comments**: Keep comments concise, accurate, and capped at two lines. Avoid commentary on obvious code.
- **Formatting**:
  - Java: Enforced via Spotless (`./gradlew spotlessApply` and `./gradlew spotlessCheck`).
  - C++: Enforced via `.clang-format` and verified via `.clang-tidy`.
- **Commits**: Use standard, everyday commit messages with only a title and no body
  (e.g. `Add direct stream rasterization`, `Fix empty page boxes`). No `feat:`/`fix:`
  prefixes. Keep one concern per commit and fold import-only churn into the commit
  that needs it instead of a separate style commit.

## Running Samples and Benchmarks

- Run all samples: `./gradlew runAllSamples`
- Run a single sample: `./gradlew runSample -Psample=01`
- Run JMH microbenchmarks: `./gradlew :jpdfium:jmh`
- Run specific benchmark: `./gradlew :jpdfium:jmh -Pjmh.include=FlattenBenchmark`
