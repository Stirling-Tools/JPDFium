<p align="center">
  <img src="docs/images/jpdfium-logo.png" width="160" alt="JPDFium logo">
</p>

<h1 align="center">JPDFium</h1>

<p align="center"><b>PDF rendering, editing, and redaction for Java</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/com.stirling/jpdfium">
    <img src="https://img.shields.io/maven-central/v/com.stirling/jpdfium.svg" alt="Maven Central">
  </a>
  <a href="https://jdk.java.net/25/">
    <img src="https://img.shields.io/badge/java-25-ED8B00.svg" alt="Java 25">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License: MIT">
  </a>
  <a href="https://github.com/Stirling-Tools/JPDFium/actions/workflows/ci.yml">
    <img src="https://github.com/Stirling-Tools/JPDFium/actions/workflows/ci.yml/badge.svg" alt="CI">
  </a>
</p>

JPDFium is a Java 25 library for rendering, editing, and redacting PDFs. It runs PDFium, the rendering engine in Chromium, through Java's Foreign Function and Memory API, and ships prebuilt native binaries for Linux, macOS, and Windows, so applications do not compile or manage the engine themselves. The binaries are built from the [EmbedPDF PDFium fork](https://github.com/embedpdf/runtime), pinned per release.

## Key Capabilities

- **Rendering** - Rasterise pages to `BufferedImage` or straight to PNG, JPEG, and other formats, at a configurable DPI.
- **Editing** - Flatten annotations and form fields into page content, fill forms, linearise, and write full or incremental saves.
- **Redaction** - Remove matched page content by pattern, audit the extracted text, and optionally sanitise metadata and form values on save.
- **Images** - Read and write raster images through ImageIO, with optional libvips support for WebP, HEIC, AVIF, JXL, and JPEG 2000.

JPDFium ships prebuilt PDFium binaries for Linux, macOS, and Windows on x64 and arm64. Package the native module for each target operating system and architecture, or enable runtime download to fetch a missing module and cache it.

## Quick Start

The checked-in [example project](examples/render-first-page) is a complete Gradle project that renders the first page of a PDF. It requires Java 25, and selects the native module for the machine that runs Gradle. The minimal configuration below pins Linux x64.

`build.gradle.kts`:

```kotlin
plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(platform("com.stirling:jpdfium-bom:1.2.0"))
    implementation("com.stirling:jpdfium")
    runtimeOnly("com.stirling:jpdfium-natives-linux-x64")
}

application {
    mainClass.set("Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
```

`src/main/java/Main.java`:

```java
import java.io.IOException;
import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: Main <input.pdf> <output.png>");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        try (var document = PdfDocument.open(input);
             var page = document.page(0)) {
            page.renderTo(output, 150);
        }
        System.out.println("Rendered " + input + " to " + output.toAbsolutePath());
    }
}
```

From the repository root, run the checked-in example:

```bash
./gradlew -p examples/render-first-page run --args="sample.pdf page-1.png"
```

The first page is written to `examples/render-first-page/page-1.png`. For your own application, use the configuration above with a Gradle wrapper and run `./gradlew run --args="input.pdf page-1.png"` from your application directory. Replace `jpdfium-natives-linux-x64` with the module for your target platform (table below). The JVM must start with `--enable-native-access=ALL-UNNAMED`; the example sets it through `applicationDefaultJvmArgs`.

Documents and pages are `AutoCloseable`, so use try-with-resources. The output format comes from the file extension, and the second argument to `renderTo` is the DPI.

`PdfRenderer` renders to `BufferedImage` in the style of PDFBox, and `PdfImageIO` reads and writes images with ImageIO-style signatures. Packaging the application as an executable JAR or a distribution is covered in the [deployment guide](docs/deployment.md).

## Platform artifacts

Declare one native module per platform your application runs on. All are published for `x64` and `arm64`.

| Runtime platform | Native module |
|---|---|
| Linux, glibc | `jpdfium-natives-linux-x64`, `jpdfium-natives-linux-arm64` |
| Linux musl, such as Alpine | `jpdfium-natives-linux-musl-x64`, `jpdfium-natives-linux-musl-arm64` |
| macOS | `jpdfium-natives-darwin-x64`, `jpdfium-natives-darwin-arm64` |
| Windows | `jpdfium-natives-windows-x64`, `jpdfium-natives-windows-arm64` |

All modules use the `com.stirling` group. Package a native module for each target operating system and architecture.

The musl modules are experimental. They are packaged only when the Alpine PDFium prebuild is available, so a release can publish them without libraries, and loading then fails with `NativeNotFoundException`. Use a glibc base image, or build the natives yourself as described in the [contributing guide](CONTRIBUTING.md).

The `jpdfium` artifact itself depends on `jpdfium-natives-linux-x64` at runtime, so that module is always on the classpath; exclude it if you want only the module for your target platform.

Verify that your runtime classpath, application distribution, or container includes the required modules. Missing native modules cause `NativeNotFoundException` unless runtime downloading is enabled.

Optional: `com.stirling:jpdfium-vips` with a matching `jpdfium-natives-vips-<platform>` adds WebP, HEIC, AVIF, JXL, and JPEG 2000.

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

## Resources

- [Documentation](docs/README.md)
- [Changelog](CHANGELOG.md)
- [Example project](examples/render-first-page)
- [Feature samples](jpdfium/src/test/java/stirling/software/jpdfium/samples)
- [Installation guide](docs/installation.md)
- [API reference on javadoc.io](https://javadoc.io/doc/com.stirling/jpdfium)
- [Releases](https://github.com/Stirling-Tools/JPDFium/releases)

## Support

- **Bug reports and questions**: [GitHub Issues](https://github.com/Stirling-Tools/JPDFium/issues)
- **Security**: report vulnerabilities as described in [SECURITY.md](SECURITY.md).

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, coding standards, and the review process.

## License

MIT. See [LICENSE](LICENSE). PDFium and the bundled native binaries carry their own licenses, listed in [NOTICE](NOTICE).
