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

## Code Standards and Style

- **Modern Java 25**: Use modern language features (records, pattern matching, switch expressions). Avoid Lombok.
- **Imports**: Never use inline fully-qualified class names. Always add explicit imports at the top of the file.
- **Comments**: Keep comments concise, accurate, and capped at two lines. Avoid commentary on obvious code.
- **Formatting**:
  - Java: Enforced via Spotless (`./gradlew spotlessApply` and `./gradlew spotlessCheck`).
  - C++: Enforced via `.clang-format` and verified via `.clang-tidy`.
- **Commits**: Use standard, everyday conventional commit messages (e.g. `feat: add direct stream rasterization`, `fix: handle empty page boxes`).

## Running Samples and Benchmarks

- Run all samples: `./gradlew runAllSamples`
- Run a single sample: `./gradlew runSample -Psample=01`
- Run JMH microbenchmarks: `./gradlew :jpdfium:jmh`
- Run specific benchmark: `./gradlew :jpdfium:jmh -Pjmh.include=FlattenBenchmark`
