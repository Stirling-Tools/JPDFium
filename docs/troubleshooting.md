# Troubleshooting

## `NativeNotFoundException`

The native module for the running platform was not found. Check that the module for the running JVM's operating system and architecture is on the runtime classpath, or bundled into the packaged application.

The exception message names the platform it looked for, which identifies the module to add. Common causes:

- The native module is not declared as a runtime dependency.
- The module was declared but stripped during packaging, for example by a shade or assembly step that filtered native artifacts.
- Architecture mismatch: an `x64` module on an `arm64` JVM, or the reverse.
- musl versus glibc. Alpine and other musl-based images need the `linux-musl` modules, not the glibc ones.

The module names are listed in [installation.md](installation.md). The build host does not affect this: a Linux build that declares the Windows module runs on Windows.

## Restricted method errors

Java 25 gates Foreign Function and Memory API calls behind a flag. Without it, the JVM either throws `IllegalCallerException` or prints a warning about restricted method calls, depending on how the application was launched.

```bash
java --enable-native-access=ALL-UNNAMED -jar app.jar
```

In containers and application servers, make sure the flag reaches the JVM that actually runs the application, not just a wrapper script.

## Cache permission failures

Native binaries are extracted into a per-user cache. A cache failure does not throw: the loader treats the cache as an optimization, falls back to `java.io.tmpdir`, and continues. Look for a slower first start as the symptom rather than an error.

If the temporary directory is also not writable, extraction fails and the load throws. Point the cache at a writable location:

```bash
java -Djpdfium.native.cacheDir=/var/cache/jpdfium --enable-native-access=ALL-UNNAMED -jar app.jar
```

To diagnose cache problems, bypass it with `-Djpdfium.native.cache=false`, which extracts to a temporary directory on every run.

## Slow startup

The first run in each cache directory pays for extraction. Later runs reuse the extracted libraries. If your process starts and exits frequently, that cost recurs whenever the cache is cleared or `jpdfium.native.cache=false` is set.

## `PdfCorruptException` when rendering

PDFium reports a render failure both for genuinely malformed documents and for pages that exceed the native pixel ceiling of 268,435,456 pixels.

To predict which case you are in, check the page size before rendering. The renderer converts points to pixels as `round(points * dpi / 72)`, rounding half up, after rounding the DPI itself to an integer:

```java
int effectiveDpi = Math.max(1, Math.round(150f));
try (var page = document.page(0)) {
    PageSize size = page.size();
    long widthPx  = Math.round(size.width()  * effectiveDpi / 72.0);
    long heightPx = Math.round(size.height() * effectiveDpi / 72.0);
    System.out.printf("%d x %d pt at %d dpi -> %d x %d px (%,d total)%n",
            (int) size.width(), (int) size.height(), effectiveDpi,
            widthPx, heightPx, widthPx * heightPx);
}
```

If the product exceeds 268,435,456, reduce the DPI or render regions instead of whole pages. Set `jpdfium.maxRenderPixels` to a lower value to reject oversized pages earlier, with an error that names the limit.

## `JPDFiumException` about render bounds

This is the configurable `jpdfium.maxRenderPixels` limit, not the native ceiling. Raise the property or render at a lower DPI.

## `PdfPasswordException`

The document is encrypted and the supplied password is missing or incorrect.

```java
try (var document = PdfDocument.open(path, password)) {
    // Process the document.
}
```

A `null` password throws `IllegalArgumentException`. An empty password performs a plain open.

## `RedactIncompleteException`

The redaction audit found content that should have been removed. The audit re-reads the affected scope and confirms that no matching content survives, so reaching this exception means the removal did not cover everything the pattern matched.

Discard the document and reopen the source file. Do not save after a failed redaction.

## `RedactUnverifiableException`

The redaction could not be verified. Two stages can produce it: the post-redaction audit, and the sanitization stage that runs during save when `setSanitizeOnSave(true)` is active.

The exception message does not say which stage failed. To distinguish them:

- Call `document.sanitizeReport()`. A report containing `"sanitize stage unavailable"` means sanitization failed because qpdf is not linked.
- Otherwise the audit itself could not run. Confirm the native module is intact.

`PdfEncryption.isQpdfAvailable()` reports whether qpdf is present, independently of any exception.

Discard the document and reopen the source file in both cases.

## Unexpected redaction match results

Matching runs against NFKC-normalized text, with matches aligned to grapheme and shaping clusters. A pattern that matches the glyphs you see may not match the normalized form, so a redaction can report success while removing less than you expected, or find nothing at all. Compare the pattern against extracted text rather than a rendering of the page.

This is a matching problem, not an audit failure. It does not produce `RedactIncompleteException`.

## Sanitization unavailable

Sanitization after redaction requires the qpdf component. `PdfEncryption.isQpdfAvailable()` reports whether it is present. Without it, a save with `setSanitizeOnSave(true)` throws `RedactUnverifiableException` rather than writing a document whose metadata still holds the redacted text.

Either use a native module that includes qpdf, or leave `setSanitizeOnSave` off and treat metadata as not sanitized.

## `UncommittedMarksException` on save

Redact marks were created but not committed. Call `commitRedactions()` before saving, or clear the pending marks.

## `RedactedSaveException`

`saveBytesIncremental()` was called after content redaction. Incremental saves append a revision and leave the original content recoverable, so they are refused. Use `save()` or `saveBytes()`, which write a full file.

## Unexpected renderer output

Check `-Djpdfium.renderer`. `auto` prefers Skia; `agg` produces slightly different antialiasing and font rasterization. The renderer is resolved once per JVM, so the property must be set before the first JPDFium call.

```java
System.out.println(JpdfiumLib.isSkiaActive());
```

## Unexpected output size

Rendering is DPI-driven: dimensions in points times DPI divided by 72. A page measured in inches rather than points produces images 72 times larger than expected. Check the page dimensions in points before raising the DPI:

```java
try (var page = document.page(0)) {
    PageSize size = page.size();
    System.out.printf("%.1f x %.1f pt%n", size.width(), size.height());
}
```

## Getting help

Report reproducible failures as a GitHub issue, including the JPDFium version, the Java version and vendor, the operating system and architecture, and a document that triggers the problem when you are able to share one.
