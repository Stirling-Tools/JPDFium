# JPDFium

Java 25 FFM bindings for PDFium (EmbedPDF fork).

- Linux x64/arm64, macOS x64/arm64, Windows x64/arm64 (+ Linux musl builds)
- MIT licensed; bundled natives carry their own licenses (see NOTICE)
- Skia is the default renderer (AGG fallback)
- Image I/O prefers the optional libvips natives when `jpdfium-vips` is present

Requires the JVM flag `--enable-native-access=ALL-UNNAMED`.

## Quick Start

```java
try (var doc = PdfDocument.open(Path.of("input.pdf"))) {
    try (var page = doc.page(0)) {
        // Render page to image via active codec (libvips when present)
        page.renderTo(Path.of("page0.png"), 150);
        page.redactPattern("\\d{3}-\\d{2}-\\d{4}", 0xFF000000);
        page.flatten();
    }
    doc.save(Path.of("output.pdf"));
}
```

More examples live in `jpdfium/src/test/java/stirling/software/jpdfium/samples/` (`S01_Render` through `S95_RedactPipelinePerf`). Full API reference is in the Javadoc.

## Native libraries

`jpdfium` itself is pure Java. At runtime it needs exactly one platform natives
jar for every OS it runs on (decide at packaging time, not build time):

| Run platform | Runtime dependency |
|---|---|
| Linux x64 / arm64 | `com.stirling:jpdfium-natives-linux-x64` / `-linux-arm64` |
| Alpine / musl x64 / arm64 | `com.stirling:jpdfium-natives-linux-musl-x64` / `-linux-musl-arm64` |
| macOS x64 / arm64 | `com.stirling:jpdfium-natives-darwin-x64` / `-darwin-arm64` |
| Windows x64 / arm64 | `com.stirling:jpdfium-natives-windows-x64` / `-windows-arm64` |

```groovy
// Gradle (same version as jpdfium, preferably via jpdfium-bom)
runtimeOnly "com.stirling:jpdfium-natives-windows-x64:${jpdfiumVersion}"
```

```xml
<!-- Maven -->
<dependency>
    <groupId>com.stirling</groupId>
    <artifactId>jpdfium-natives-windows-x64</artifactId>
    <version>${jpdfium.version}</version>
    <scope>runtime</scope>
</dependency>
```

Ship the jar for the machine that runs the code, not the one that builds it.
A jar assembled on Linux (including Docker builds) contains only the platforms
declared there, so running that same jar on Windows fails with
`NativeNotFoundException: windows-x64`. Bundle every target OS, or one
per distribution artifact.

Missing natives at runtime can instead be fetched once from Maven Central
and cached with the extracted libraries. This is strictly opt-in and off by
default; enable it only when runtime network access to Central is acceptable:

```bash
java -Djpdfium.native.download=true --enable-native-access=ALL-UNNAMED -jar app.jar
```

The downloader resolves `com.stirling:jpdfium-natives-<platform>:<version>`
from the running jar's stamped version (override with
`-Djpdfium.native.version=<version>`, mirror with
`-Djpdfium.native.repo=<https-url>`), refuses plain HTTP except for loopback
test servers, rejects snapshot versions, and still SHA-256 verifies every
extracted file against the jar manifest. Downloaded jars are reused offline
from later runs. TLS authenticates the repository; per-file checksums attest
the contents, the same split of duties as build-time dependency resolution.

## Image I/O

JPDFium provides familiar PDFBox-style and ImageIO-style APIs:

```java
// PDFBox-style rendering:
var renderer = new PdfRenderer(doc);
BufferedImage image = renderer.renderImageWithDPI(0, 150);

// ImageIO-style read/write (backed by libvips when present, else ImageIO):
PdfImageIO.write(image, "PNG", Path.of("page0.png"));
BufferedImage photo = PdfImageIO.read(Path.of("photo.heic"));
```

Add `stirling.software.jpdfium:jpdfium-vips` (+ a `jpdfium-natives-vips-<platform>` jar) and its libvips-backed `ImageCodec` registers through the service loader as the default for `PdfImageIO`, `PdfRenderer`, `PdfImageConverter`, `SvgConverter`, `Watermark`, and `ExtractedImage`: more formats (HEIC/HEIF/AVIF/JXL/JPEG2000) and WebP writes without ImageIO plugins. Without the module, `javax.imageio` is used. `-Djpdfium.renderer=agg|skia|auto` (or `JPDFIUM_RENDERER`) selects the renderer; Skia is the default.

## Native Loading

Natives are extracted once per content revision into a per-user cache, then reused by every JVM:
`%LOCALAPPDATA%\jpdfium\native` (Windows), `~/Library/Caches/jpdfium/native` (macOS),
`$XDG_CACHE_HOME/jpdfium/native` (Linux). Extraction is SHA-256 verified, published atomically, and safe
across concurrent JVMs; when the cache is not writable (containers, read-only home) the loader falls back to
`java.io.tmpdir`. Stale per-JVM temp dirs and obsolete cache entries are swept best-effort.

Every load re-checks the SHA-256 of each cached file; `-Djpdfium.native.verify=marker` skips that check for
speed. `-Djpdfium.native.cacheDir=<dir>` overrides the root, `-Djpdfium.native.cache=false` forces temp
extraction, and `-Djpdfium.native.sweep=false` disables the stale-dir sweep.

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
                        fonts/, model/, util/, spi/, plus runnable samples
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
- Gradle 9.8 (via wrapper)
- jextract 25 (optional, to regenerate FFM bindings)

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

MIT. PDFium and bundled natives carry their own licenses; see NOTICE and `native/licenses/`.
