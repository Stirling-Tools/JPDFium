# JPDFium

Java 25 FFM bindings for PDFium (EmbedPDF fork).

- Linux x64/arm64, macOS x64/arm64, Windows x64/arm64 (+ Linux musl builds)
- MIT licensed; bundled natives carry their own licenses (see NOTICE)

Requires the JVM flag `--enable-native-access=ALL-UNNAMED`.

## Quick Start

```java
try (var doc = PdfDocument.open(Path.of("input.pdf"))) {
    try (var page = doc.page(0)) {
        ImageIO.write(page.renderAt(150).toBufferedImage(), "PNG", new File("page0.png"));
        page.redactPattern("\\d{3}-\\d{2}-\\d{4}", 0xFF000000);
        page.flatten();
    }
    doc.save(Path.of("output.pdf"));
}
```

More examples live in `jpdfium/src/test/java/stirling/software/jpdfium/samples/` (`S01_Render` through `S95_RedactPipelinePerf`). Full API reference is in the Javadoc.

## Project Structure

```
native/bridge/          C++ bridge: document, render, text, redact, PII pipeline,
                        repair, image, Brotli, OpenJPEG, PDFio, ICC, Unicode
native/build-real.sh    build the real PDFium bridge
native/build-stub.sh    build a stub bridge (unit tests without PDFium)
native/setup-pdfium.sh  download and build the EmbedPDF PDFium fork
native/rust/            optional Rust modules (lopdf + zopfli compression, repair, resize)
jpdfium/                Java API (stirling.software.jpdfium): core API, panama/ FFM
                        bindings, doc/ inspection & editing, text/, redact/, transform/,
                        fonts/, model/, util/, plus runnable samples under src/test
jpdfium-natives-<platform>/  native JARs: linux/darwin/windows × x64/arm64
                        (+ linux-musl-{x64,arm64} for Alpine / musl runtimes)
jpdfium-vips/         optional libvips image conversions (HEIC, AVIF, JXL, WebP, PNG, JPEG)
jpdfium-spring/         Spring Boot auto-configuration
jpdfium-bom/            Maven BOM for dependency management
```

## Building

### Prerequisites

- Java 25, https://jdk.java.net/25/
- C++23 compiler (`gcc-c++` / `g++` / Xcode CLT / MSVC)
- CMake 3.20+
- Gradle 9.7 (via wrapper)
- jextract 25 (optional, to regenerate FFM bindings)

Fedora / RHEL:
```bash
sudo dnf install -y pcre2-devel freetype-devel harfbuzz-devel \
    libicu-devel qpdf-devel pugixml-devel libunibreak-devel
```

Ubuntu / Debian:
```bash
sudo apt install -y libpcre2-dev libfreetype-dev libharfbuzz-dev \
    libicu-dev libqpdf-dev libpugixml-dev libunibreak-dev
```

Missing libraries are auto-detected via pkg-config and silently skipped at runtime.

### Build via Gradle

```bash
./gradlew quickTry            # stub bridge, runs all samples, no PDFium needed
./gradlew fullBuildAndTest    # real PDFium: download, build, test, run samples
./gradlew test                # unit tests
./gradlew :jpdfium:integrationTest
./gradlew runAllSamples
./gradlew runSample -Psample=01   # run a specific sample (01..95)
./gradlew :jpdfium:generateBindings  # regenerate FFM bindings from jpdfium.h
```

Set `jpdfium.jextractHome` in `~/.gradle/gradle.properties` or `JEXTRACT_HOME` before regenerating bindings (defaults to `~/Downloads/jextract-25`).

### Manual build (real PDFium)

```bash
./gradlew buildPdfium       # build EmbedPDF PDFium fork (~15 GB, first build 15-60 min)
./gradlew buildRealBridge   # compile native bridge with CMake
./gradlew test
./gradlew :jpdfium:integrationTest
```

For Java-only development, `./gradlew buildStubBridge` provides a pass-through stub.

## Thread Safety

- Use a `PdfDocument` (and its pages) from one thread at a time.
- Native calls are serialized internally, so separate documents on separate threads are fine.
- `FPDF_InitLibrary` / `FPDF_DestroyLibrary` run once globally.

## Testing

```bash
./gradlew :jpdfium:integrationTest
./gradlew :jpdfium:integrationTest --tests "stirling.software.jpdfium.CorpusRedactTest"
./gradlew :jpdfium:integrationTest --tests "stirling.software.jpdfium.redact.ObjectFissionCoordinateTest"
```

HTML reports are written to `samples-output/`.

## License

MIT. PDFium, and bundled natives carry their own licenses, see NOTICE and `native/licenses/`.
