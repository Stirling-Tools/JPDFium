# JPDFium

[![Maven Central](https://img.shields.io/maven-central/v/com.stirling/jpdfium.svg)](https://central.sonatype.com/artifact/com.stirling/jpdfium)
[![Java 25](https://img.shields.io/badge/java-25-ED8B00.svg)](https://jdk.java.net/25/)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![CI](https://github.com/Stirling-Tools/JPDFium/actions/workflows/ci.yml/badge.svg)](https://github.com/Stirling-Tools/JPDFium/actions/workflows/ci.yml)

JPDFium is a Java 25 library for rendering, editing, and redacting PDFs. It uses PDFium, the rendering engine in Chromium, through Java's Foreign Function and Memory API, and provides prebuilt native binaries for Linux, macOS, and Windows.

The binaries are built from the [EmbedPDF PDFium fork](https://github.com/embedpdf/runtime), pinned per release.

Image reading and writing use ImageIO, with optional libvips support.

> Requires Java 25 and `--enable-native-access=ALL-UNNAMED`. CPU rendering only.

## Quick start

Windows x64, using Gradle Kotlin DSL:

```kotlin
dependencies {
    implementation(platform("com.stirling:jpdfium-bom:1.1.5"))
    implementation("com.stirling:jpdfium")
    runtimeOnly("com.stirling:jpdfium-natives-windows-x64")
}
```

```bash
./gradlew jar
java --enable-native-access=ALL-UNNAMED -jar app.jar input.pdf page-1.png
```

Package `app.jar` with `Main-Class: Main` and the runtime classpath included, for example with the Gradle `application` plugin or a shadow JAR. The command above assumes `input.pdf` exists in the working directory and `page-1.png` is the output file.

```java
import java.io.IOException;
import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;

public class Main {
    public static void main(String[] args) throws IOException {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        try (var document = PdfDocument.open(input);
             var page = document.page(0)) {
            page.renderTo(output, 150);
        }
    }
}
```

Documents and pages are `AutoCloseable`, so use try-with-resources. The output format comes from the file extension and the second argument is the DPI.

`PdfRenderer` renders to `BufferedImage` in the style of PDFBox, and `PdfImageIO` reads and writes images with ImageIO-style signatures.

Maven coordinates, other platforms, and Spring Boot: [docs/installation.md](docs/installation.md).

## Platform artifacts

Declare one native module per platform your application runs on. All are published for `x64` and `arm64`.

| Runtime platform | Native module |
|---|---|
| Linux, glibc | `jpdfium-natives-linux-x64`, `jpdfium-natives-linux-arm64` |
| Linux musl, such as Alpine | `jpdfium-natives-linux-musl-x64`, `jpdfium-natives-linux-musl-arm64` |
| macOS | `jpdfium-natives-darwin-x64`, `jpdfium-natives-darwin-arm64` |
| Windows | `jpdfium-natives-windows-x64`, `jpdfium-natives-windows-arm64` |

All modules use the `com.stirling` group. Include and package a native module for each target operating system and architecture. The build host does not determine platform support.

Verify that your runtime classpath, application distribution, or container includes the required modules. Missing native modules cause `NativeNotFoundException` unless runtime downloading is enabled.

Optional: `com.stirling:jpdfium-vips` with a matching `jpdfium-natives-vips-<platform>` adds WebP, HEIC, AVIF, JXL, and JPEG 2000. `com.stirling:jpdfium-spring` provides Spring Boot auto-configuration.

## Editing and thread safety

`redactPattern` removes matched page content and audits extracted page text. The audit does not inspect metadata or form field values. Optional sanitization removes additional document data on save. See [redaction and sanitization](docs/editing.md#redaction) for the scope and limitations.

`flatten` incorporates annotations and form fields into page content. Existing page text remains selectable. Flattened elements are no longer interactive annotations or form fields.

`save` writes a full new file. Incremental save is refused after redaction, because an appended revision leaves the original content recoverable. `save` does not preserve linearization. Run `PdfLinearizer` separately when linearized output is required.

A document may be used by different threads sequentially, but access must not overlap, and do not close a document another thread is using. PDFium calls are serialized within a process, so adding threads does not parallelize rendering.

## Configuration and limits

PDFs are untrusted input. JPDFium validates buffers on both sides of the FFM boundary, but it does not bound what a hostile document consumes.

| Property | Default | Description |
|---|---|---|
| `jpdfium.renderer` | `auto` | `skia`, `agg`, or `auto`. Prefers Skia. Read once per JVM. |
| `jpdfium.maxRenderPixels` | unlimited | Maximum pixels per rendered page. |
| `jpdfium.maxSaveResultBytes` | disabled | Maximum size of a saved document. |
| `jpdfium.native.download` | `false` | Fetch a missing native module at runtime instead of bundling it. |

Set render and save limits before processing untrusted PDFs. These limits do not bound total memory usage. Apply a process-level memory limit, and use a terminable worker process when an enforceable timeout is required.

A hard native ceiling of 268,435,456 pixels per page applies regardless of `jpdfium.maxRenderPixels`.

## Not supported

- Creating or verifying digital signatures. Signatures can be enumerated and a digest computed over each `/ByteRange`.
- Creating form fields. Forms can be read, filled, and flattened.
- GPU rendering.

## Documentation

[docs/](docs/README.md) covers installation, native loading, deployment, configuration, rendering, images, editing, concurrency, compatibility, and troubleshooting.

- [API reference on javadoc.io](https://javadoc.io/doc/com.stirling/jpdfium)
- Vulnerabilities: [SECURITY.md](SECURITY.md)
- Contributions: [CONTRIBUTING.md](CONTRIBUTING.md)

## License

MIT. See [LICENSE](LICENSE). PDFium and the bundled native binaries carry their own licenses, listed in [NOTICE](NOTICE).
