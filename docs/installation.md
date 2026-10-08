# Installation

JPDFium publishes to Maven Central under the `com.stirling` group. The current release is **1.1.5**.

## Requirements

- Java 25. JPDFium calls native libraries through Java's Foreign Function and Memory API.
- The JVM flag `--enable-native-access=ALL-UNNAMED`.
- One native module matching the platform that runs your application.

Java 25 is the version JPDFium is built and tested against. Earlier releases are not supported.

## Modules

| Module | Purpose | Scope |
|---|---|---|
| `com.stirling:jpdfium` | The library. Pure Java, no native code of its own. | compile |
| `com.stirling:jpdfium-bom` | Bill of materials. Pins a consistent version across all modules below. | platform |
| `com.stirling:jpdfium-natives-<platform>` | Prebuilt PDFium binaries for one platform. | runtime |
| `com.stirling:jpdfium-vips` | Optional libvips image codec for additional formats. | runtime |
| `com.stirling:jpdfium-natives-vips-<platform>` | Prebuilt libvips binaries for one platform. | runtime |
| `com.stirling:jpdfium-spring` | Spring Boot auto-configuration. | compile |

## Gradle (Kotlin DSL)

Windows x64. Substitute the native module for your platform from the table below.

```kotlin
dependencies {
    implementation(platform("com.stirling:jpdfium-bom:1.1.5"))
    implementation("com.stirling:jpdfium")
    runtimeOnly("com.stirling:jpdfium-natives-windows-x64")
}
```

## Maven

Windows x64.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.stirling</groupId>
            <artifactId>jpdfium-bom</artifactId>
            <version>1.1.5</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.stirling</groupId>
        <artifactId>jpdfium</artifactId>
    </dependency>
    <dependency>
        <groupId>com.stirling</groupId>
        <artifactId>jpdfium-natives-windows-x64</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

## Platform artifacts

Every artifact is published for `x64` and `arm64`.

| Runtime platform | Native module |
|---|---|
| Linux (glibc) | `jpdfium-natives-linux-x64`, `jpdfium-natives-linux-arm64` |
| Linux musl, such as Alpine | `jpdfium-natives-linux-musl-x64`, `jpdfium-natives-linux-musl-arm64` |
| macOS | `jpdfium-natives-darwin-x64`, `jpdfium-natives-darwin-arm64` |
| Windows | `jpdfium-natives-windows-x64`, `jpdfium-natives-windows-arm64` |

Every coordinate uses the `com.stirling` group. The optional libvips modules insert `vips`, for example `com.stirling:jpdfium-natives-vips-linux-x64`.

## Spring Boot

Adding `com.stirling:jpdfium-spring` alongside `com.stirling:jpdfium` registers the auto-configuration. Declare the native module for your platform as a runtime dependency in the usual way.

## Optional image formats

Add `com.stirling:jpdfium-vips` and the matching `jpdfium-natives-vips-<platform>` module to read and write WebP, HEIC, AVIF, JXL, and JPEG 2000. Without them, JPDFium uses `javax.imageio` and supports whatever your JDK supports. See [images.md](images.md).

The vips native artifacts are published for Linux x64 and arm64, macOS x64 and arm64, and Windows x64 and arm64. There is no musl build.

## Verifying the installation

Run the JVM with `--enable-native-access=ALL-UNNAMED` and open a PDF you know is readable:

```java
void verify(Path pdf) {
    try (var document = PdfDocument.open(pdf)) {
        System.out.println(document.pageCount() + " pages");
    }
}
```

If the native module resolved, this prints the page count. If it did not, the failure appears as `NativeNotFoundException`; see [troubleshooting.md](troubleshooting.md).

