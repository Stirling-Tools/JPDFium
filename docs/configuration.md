# Configuration

JPDFium is configured with system properties. Where noted, an environment variable is used as the default when the property is absent.

## Renderer

| Property | Default | Description |
|---|---|---|
| `jpdfium.renderer` | `auto` | Renderer selection: `skia`, `agg`, or `auto`. Environment fallback: `JPDFIUM_RENDERER`. |

`auto` prefers Skia and falls back to AGG. The fallback happens only when the native library was built without Skia support. Official release binaries include both renderers. An explicit `skia` request against a build without Skia prints a warning to standard error and continues with AGG rather than failing.

The value is read once when the library class initializes, so it must be set before the first JPDFium call. An unrecognized value is treated as `auto`.

See [rendering.md](rendering.md) for what the two renderers affect.

## Limits

All limits are opt-in. The default of `0` means unbounded.

| Property | Default | Read | Description |
|---|---|---|---|
| `jpdfium.maxRenderPixels` | `0` (unlimited) | per call | Maximum pixels per rendered page. Exceeding it throws `JPDFiumException`. |
| `jpdfium.maxSaveResultBytes` | `0` (disabled) | per call | Maximum size of a saved document, in bytes. Exceeding it throws `JPDFiumException`. |
| `jpdfium.image.max_pixels` | `0` (unlimited) | at startup | Maximum pixels accepted when decoding an image. |
| `jpdfium.image.max_dimension` | `0` (unlimited) | at startup | Maximum width or height accepted when decoding an image. |

"per call" means the property is consulted on each operation and can be changed at runtime. "at startup" means it is read once during class initialization.

`jpdfium.maxRenderPixels` is a policy check applied in Java. The native bridge additionally enforces a hard ceiling of 268,435,456 pixels per page, which cannot be raised or lowered. Exceeding that ceiling throws `PdfCorruptException`, which is a different exception from the one above; see [troubleshooting.md](troubleshooting.md).

`jpdfium.maxSaveResultBytes` bounds the resulting document. It does not bound the transient native memory a single save may use. To bound that, apply limits at the process level.

Negative values are rejected with `IllegalStateException`.

## Concurrency

| Property | Default | Description |
|---|---|---|
| `jpdfium.image.maxConcurrency` | `0` (unbounded) | Maximum concurrent image operations. |
| `jpdfium.qpdf.maxConcurrency` | `0` (unbounded) | Maximum concurrent qpdf operations, such as merge and optimize. |
| `jpdfium.pipeline.maxParallelism` | `0` (unbounded) | Maximum parallelism of internal processing pipelines. |

These bound concurrent admission, not memory. Two operations admitted together may each use their full allocation. Combine them with process-level memory limits if you need a hard memory ceiling.

## Native loading

| Property | Default | Description |
|---|---|---|
| `jpdfium.native.cacheDir` | Per-user cache | Root directory for extracted native binaries. Consulted when the cache path is resolved. |
| `jpdfium.native.cache` | `true` | Set to `false` to extract to a temporary directory on every run. |
| `jpdfium.native.verify` | Verifies each load | Set to `marker` to skip SHA-256 verification of cached files. |
| `jpdfium.native.sweep` | `true` | Set to `false` to disable cleanup of stale cache entries. |
| `jpdfium.native.download` | `false` | Set to `true` to download a missing native module at runtime. |
| `jpdfium.native.version` | Version stamped in the JAR | Version to download at runtime. |
| `jpdfium.native.repo` | Maven Central | Repository URL for runtime downloads. |

`jpdfium.native.verify=marker` weakens cache-integrity verification. See [native-loading.md](native-loading.md) for the trust model before using it.

## Rendering

| Property | Default | Description |
|---|---|---|
| `jpdfium.flatten.dpi` | `150` | DPI used by full-page rasterization during `flatten()`. |

## Diagnostics

These are for troubleshooting JPDFium itself and may change between releases.

| Property | Default | Description |
|---|---|---|
| `jpdfium.nativeGuard.telemetry` | `false` | Records native-call wait and hold times. Read through `PdfiumRuntime.stats()`. Read once at startup. |
| `jpdfium.traceDomain` | unset | JFR event domain for native tracing. Read once at startup. |

## Per-operation options

These option types configure a single operation rather than the process:

- `SaveOptions.maxOutputBytes` for one save.
- `RenderOptions` for background color, transparency, and color type.
- `StorageOptions` for input and output staging locations.
- `ProcessingMode` for sequential or parallel processing.
- `RedactOptions` for sanitization and metadata stripping during redaction.

Most of these do not correspond to a system property.

### Precedence for save size limits

Two settings bound the size of a saved document, and they do not combine. `SaveOptions.maxOutputBytes` is used when it is greater than zero; otherwise `jpdfium.maxSaveResultBytes` applies.

They also differ in when they are enforced. `maxOutputBytes` is passed to the native writer and aborts the write once the budget is exceeded. `jpdfium.maxSaveResultBytes` is checked against the staged file before publication, so the write runs to completion first.

