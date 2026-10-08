# Deployment

## Choosing a model

| Deployment model | Recommended approach |
|---|---|
| One known OS and architecture | Bundle its native module. |
| Cross-platform desktop application | Bundle all supported native modules. |
| Per-platform server images | Build one image per platform, each with its own native module. |
| Networked client application | Enable `-Djpdfium.native.download=true`. |
| Restricted or offline environment | Bundle the native modules; leave downloads disabled. |

Bundling is the default recommendation. It has no startup network cost, works offline, and makes the artifact self-contained.

## Bundling several platforms

Declare one runtime dependency per target platform. They coexist on the classpath, and the loader selects the artifact matching the running platform.

```kotlin
dependencies {
    implementation(platform("com.stirling:jpdfium-bom:1.1.5"))
    implementation("com.stirling:jpdfium")
    runtimeOnly("com.stirling:jpdfium-natives-windows-x64")
    runtimeOnly("com.stirling:jpdfium-natives-windows-arm64")
    runtimeOnly("com.stirling:jpdfium-natives-darwin-x64")
    runtimeOnly("com.stirling:jpdfium-natives-darwin-arm64")
    runtimeOnly("com.stirling:jpdfium-natives-linux-x64")
}
```

Dependency selection is explicit, so the build host does not matter. A build running on Linux that declares the Windows and macOS modules resolves them for packaging just as a Windows build would.

Declaring a module and shipping it are separate steps. Verify that your packaging configuration, whether a fat JAR, a shaded JAR, or a container image, actually includes the native module and its resources. A module that is declared but dropped during packaging fails at load time on that platform with `NativeNotFoundException`.

## Per-platform images

For server deployments, build one image per platform and keep the native dependency list to that single platform. This keeps images small and removes any platform-detection risk from the build.

Multi-architecture images are possible, since the loader detects the platform at runtime, but per-platform images are simpler to reason about and to roll back.

## Read-only and containerized filesystems

The application image may be read-only, but native extraction requires a writable directory. JPDFium writes to a per-user cache and falls back to `java.io.tmpdir` when the cache is unavailable.

In a fully read-only filesystem, set `-Djpdfium.native.cacheDir` to a mounted writable volume:

```bash
java -Djpdfium.native.cacheDir=/var/cache/jpdfium --enable-native-access=ALL-UNNAMED -jar app.jar
```

A cache failure on its own is not an error. The loader treats the cache as an optimization and continues with temporary extraction, so the symptom is a slower start rather than an exception. Extraction requires one writable directory: the cache, or `java.io.tmpdir` as the fallback.

## Runtime downloads

`-Djpdfium.native.download=true` lets a client fetch its native artifact on first use instead of shipping it. See [native-loading.md](native-loading.md) for the security model, including checksum verification and the repository override.

Do not enable this in environments without outbound network access; startup will fail on the first fetch rather than degrade.

## Startup flags

Every deployment needs native access enabled:

```bash
java --enable-native-access=ALL-UNNAMED -jar app.jar
```

## Hardening a deployment

PDFs are untrusted input. JPDFium validates buffers on both sides of the FFM boundary and reports a redaction it cannot verify rather than returning success. It does not bound the resources a hostile document can consume.

- Set `jpdfium.maxRenderPixels` to limit output image dimensions. This does not bound all memory used while parsing or rendering the document.
- Set `jpdfium.maxSaveResultBytes` to bound the size of a generated document. It does not bound transient native memory during a save.
- Apply a process-level memory limit to constrain total resource usage.
- For an enforceable timeout, process untrusted documents in a worker process that can be terminated. Cancelling a Java task does not interrupt an active native call.
- Keep JPDFium updated. It tracks upstream PDFium, and new releases include fixes for malformed-document handling.

See [compatibility.md](compatibility.md) for the support matrix.

