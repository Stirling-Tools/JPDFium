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
- **Limits are unlimited by default**: pixel, save-result, frame, and concurrency
  bounds default to 0 = unbounded. Consumers enable them via system properties
  (`jpdfium.maxRenderPixels`, `jpdfium.image.max_*`, `jpdfium.qpdf.maxConcurrency`,
  `jpdfium.image.maxConcurrency`, `jpdfium.pipeline.maxParallelism`), explicit
  options (`SaveOptions.maxOutputBytes`, `StorageOptions`, `ProcessingMode`),
  or direct APIs (`QpdfLib.setMaxConcurrency`, `VipsImageConverter.setMaxConcurrency`,
  `QpdfLib.withSlot`). Negative values are invalid configuration and rejected,
  never silently treated as unlimited. Permits bound admission, not bytes;
  `maxBytes` on publish bounds acceptance, not transient disk use during native write.

## Performance Contributions

Every optimization must state its workload, measured benefit, bounded-memory policy,
and correctness regression. Optimize in this order: lifetime/capacity correctness,
PDFium-call count, crossing count, copies/peak bytes, guard hold time, allocation
rate/retention, native loops, and only then instruction-level tuning.

- Measure with JMH (micro), component drivers, and end-to-end runs; record heap
  bytes/op (`-prof gc`), peak RSS, guard wait/hold time (`PdfiumRuntime.stats()`),
  calls/op, and budgets. Report intervals across forks, not single fastest runs.
- Prefer fewer calls and fewer copies over hotter leaves: batch repetitive tiny FFM
  calls, move detached I/O outside the guard, and validate buffer capacity in both
  Java and native code. Never mark rendering, parsing, saving, redaction, or text
  extraction as `critical` downcalls.
- Keep `Arena.ofConfined()` for single-call temporaries and `Arena.ofShared()` for
  cross-thread memory; never use `ofAuto()` or `global()` for bounded paths.
- Budgets are opt-in with no default cap: `jpdfium.maxRenderPixels` (0 = unlimited),
  `jpdfium.maxSaveResultBytes` (0 = disabled), `jpdfium.image.max_*` (0 = unlimited),
  `jpdfium.qpdf/image.maxConcurrency` (0 = unbounded), `jpdfium.pipeline.maxParallelism`
  (0 = unbounded), plus the caller-supplied bound in `PdfDocument.open(InputStream, long)`
  and `SaveOptions.maxOutputBytes`. Stream opens have **no** default cap.
  Checked arithmetic everywhere. Concurrency permits bound admission, not memory;
  size checks on publish bound acceptance, not transient native/disk use.

## Code Standards and Style

- **Modern Java 25**: Use modern language features (records, pattern matching, switch expressions). Avoid Lombok.
- **Imports**: Never use inline fully-qualified class names. Always add explicit imports at the top of the file.
- **Comments**: Keep inline comments concise and capped at two lines. Public
  ownership, safety, cancellation, and output-semantics contracts may use longer
  Javadoc when the guarantee needs exact wording (what is bounded, when staging
  publishes, what survives cancellation). Prefer fewer words over more.
- **Cancellation**: logical cancel never releases resources still owned by
  running native work. Arenas, leases, staging files, and permits retire on
  actual task exit (finally), never on `Future.cancel`/`shutdownNow`.
  Publication uses an explicit commit (`RUNNING→COMMITTING→COMMITTED` vs
  `RUNNING→CANCELLED`); only the commit winner publishes, late cancel loses.
  Replacement is staging + atomic rename; no-clobber is `CREATE_NEW` (O_EXCL)
  streaming, never precheck + move.
- **Formatting**:
  - Java: Enforced via Spotless (`./gradlew spotlessApply` and `./gradlew spotlessCheck`).
  - C++: Enforced via `.clang-format` and verified via `.clang-tidy`.
- **Commits**: Use standard, everyday commit messages with only a title and no body
  (e.g. `Add direct stream rasterization`, `Fix empty page boxes`). No `feat:`/`fix:`
  prefixes. Keep one concern per commit and fold import-only churn into the commit
  that needs it instead of a separate style commit.

## Documentation Policy

`README.md` and `docs/` are written to this policy. The goal is accurate, concise
documentation that helps a reader finish a task. Write to this standard directly;
it does not depend on any particular authoring method.

### Accuracy

Review technical accuracy before style. Prose edits never substitute for verification.

- Verify API names, overloads, artifact coordinates, configuration keys, defaults,
  requirements, and failure behavior against the source or the released artifact.
- Do not invent capabilities, guarantees, benchmarks, or compatibility.
- Distinguish verified behavior from recommendations. Where behavior is unknown,
  record an open question in the pull request rather than guessing in the text.
- State prerequisites and limitations next to the instruction they affect.
- Do not silently broaden support or security claims.
- Keep concurrent safety separate from parallel execution. JPDFium serializes every
  PDFium call, so "safe from multiple threads" does not mean "runs in parallel".
- Treat redaction, signing, and save behavior as security-relevant. Describe the exact
  operation and what it does not change.
- Distinguish a published artifact from a tested platform. Publication proves a binary
  exists, not that it has been executed.
- Treat destructive operations as requiring explicit verification: full rewrites,
  signature invalidation, metadata removal, and cache mutation.

### Examples

- Use public APIs only.
- Make quick-start examples runnable, including `throws` clauses for checked
  exceptions and any imports they need.
- Use a concrete released version, or a placeholder with instructions to replace it.
  Remove unexplained placeholders from examples labeled complete. An explicit
  placeholder such as `<version>` is acceptable in reference documentation.
- Show required resource cleanup in the first example that needs it.
- Label incomplete snippets as excerpts.
- Verify examples against the version the document describes.
- Do not invent package names or overloads to complete an example.

### Language

- Start with the subject or the task.
- Use concrete nouns and direct verbs. Prefer "use" to "leverage".
- Remove unsupported adjectives such as seamless, powerful, robust, cutting-edge,
  effortless, and enterprise-grade.
- Do not open with scene-setting or an empty purpose statement.
- Do not use "not just X, but Y" constructions.
- Do not invent capability ranges or comparisons. Concrete ranges backed by
  measurement are fine.
- Do not force items into groups of three.
- Do not append filler clauses about improving the user experience.
- Do not repeat a section's introduction in its conclusion.
- Do not add conversational prompts or closing questions.
- Do not start every bullet with a bold label unless the label aids lookup.
- Keep technical terminology consistent. Call it a page, not a sheet or a surface.
- Do not use semicolons to make prose sound formal.

### Punctuation

- Never use unnecesary special characters such emojis, arrows, etc.
- Write ranges as "Java 25 to 26".
- Use ASCII quotation marks in prose and examples.
- Preserve exact syntax in commands, identifiers, paths, and literal output. For
  example `--enable-native-access=ALL-UNNAMED` must remain unchanged.
- Use three backticks with a language identifier for every fenced block.

### Formatting

- Headings name content or tasks.
- Bullets for independent items, numbered lists only where order matters, tables
  for genuinely comparable information.
- Use bold sparingly.
- Badges are limited to release version, CI, license, and required runtime.
- Keep reference material out of the README when it obstructs setup. Link it instead.

### Before opening a pull request

- Check every strong claim: always, never, all, safe, secure, automatic, guaranteed,
  lossless, thread-safe.
- Remove unresolved placeholders.
- Validate relative links, code fence languages, and banned characters.
- Confirm the examples match the documented release.

## Running Samples and Benchmarks

- Run all samples: `./gradlew runAllSamples`
- Run a single sample: `./gradlew runSample -Psample=01`
- Run JMH microbenchmarks: `./gradlew :jpdfium:jmh`
- Run specific benchmark: `./gradlew :jpdfium:jmh -Pjmh.include=FlattenBenchmark`
