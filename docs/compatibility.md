# Compatibility and limitations

## Java

| Requirement | Value |
|---|---|
| Minimum Java version | 25 |
| Reason | JPDFium is built and tested against Java 25. |
| Continuous integration | Temurin 25 on Linux, macOS, and Windows x64. Microsoft build of OpenJDK 25 on Windows arm64. |
| Required JVM flag | `--enable-native-access=ALL-UNNAMED` |

JPDFium is built and tested against Java 25. Earlier releases are not supported.

## Platforms

| Platform | Architectures | Notes |
|---|---|---|
| Linux (glibc) | x64, arm64 | Tested on Ubuntu. |
| Linux musl | x64, arm64 | For Alpine and similar. Requires the `linux-musl` artifacts, which are experimental. |
| macOS | x64, arm64 | Tested on macOS 14 and macOS 15. |
| Windows | x64, arm64 | Tested on Windows Server 2022 and Windows 11 arm64. |

Graphics processing uses the CPU. There is no GPU rendering path.

## Redaction

Redaction removes content. It is never a drawing operation.

- Visual-only redaction is refused in both the Java and native layers.
- Every redaction is audited afterwards. Surviving content throws `RedactIncompleteException`. An audit that cannot run throws `RedactUnverifiableException`.
- Sanitization of metadata, outlines, and form field values is opt-in through `setSanitizeOnSave(true)` and requires the qpdf component.
- Font glyph outlines are not erased during sanitization. The text is already removed from the content streams, but a determined recovery from an embedded font subset is outside the guarantee this library makes.

## Editing

- `flatten()` bakes annotations and form fields into page content. Text stays selectable, and the elements are no longer editable. The document is not made read-only.
- `FlattenMode.FULL` rasterizes pages and replaces all content with an image. Nothing remains extractable.
- `PdfSelectiveFlatten` removes targeted annotations without baking their appearance, so the removed elements no longer appear. Use it to drop elements, not to preserve them.
- `save()` writes a full new file. Incremental save is available through `saveBytesIncremental()`, but is refused after content redaction.
- Linearization is not retained by a save. Run `PdfLinearizer.linearize` separately if you need it.
- Metadata, structure tree, and outlines are preserved by a plain save and removed only when explicitly requested.
- Saving over the document's own source file is refused.

## Encryption and signatures

- Password-protected documents open through overloads accepting a password. A wrong or missing password throws `PdfPasswordException`.
- Encryption can be applied file to file, or to an in-memory document before saving.
- Signatures can be enumerated and their signed bytes extracted.
- Signature creation, verification, and certificate trust checks are not supported. JPDFium enumerates signatures and computes a digest over each signature's `/ByteRange`. Use a PDF signature verification library for the rest.
- Whether an existing signature survives a save is not determined by JPDFium. Verify signatures yourself after any save.

## Forms

Forms can be read, filled, and flattened. Form field creation is not exposed.

## Rendering

- The default background is opaque white. Requesting transparency renders over a transparent background. The native buffer is straight, non-premultiplied RGBA.
- Page `/Rotate` is honored. Rotations of 90 and 270 degrees swap the output width and height.
- Pixel dimensions are `points * dpi / 72`, rounded to the nearest integer.
- A hard native ceiling of 268,435,456 pixels per page always applies, independent of `jpdfium.maxRenderPixels`.
- `renderTo(Path, dpi)` infers the format from the file extension. Unrecognized extensions throw `IllegalArgumentException`. There is no `gif` token.

## Images

Without `jpdfium-vips`, formats are whatever `javax.imageio` provides, which depends on your JDK and any ImageIO plugins on the classpath. A stock JDK 25 provides PNG, JPEG, and BMP.

With `jpdfium-vips`, the libvips codec reads and writes WebP, HEIC, AVIF, JXL, and JPEG 2000 in addition to PNG, JPEG, and TIFF. Reading and writing are probed independently, so a libvips build without a given encoder still decodes that format. See [images.md](images.md) for the per-format table.

## Artifact availability

Native modules are published for eight platform targets. All eight run in the release CI matrix, defined in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml), which builds each target and runs the test suite against it.

The vips modules cover six targets: Linux x64 and arm64, macOS x64 and arm64, and Windows x64 and arm64. There is no musl vips build, so Alpine and other musl runtimes are limited to ImageIO formats.

A published artifact means the module exists for that target. It is not a promise that the artifact carries native libraries: the musl modules are experimental and can be published without a payload while their build is stabilised. Run [`.github/scripts/check-natives-jars.sh`](../.github/scripts/check-natives-jars.sh) against a downloaded release to see which platforms shipped libraries.

## Feature availability

Some capabilities depend on how the native library was compiled and throw `UnsupportedOperationException` when absent. Release artifacts are built with the optional components enabled: Skia, qpdf, PCRE2, ICU, HarfBuzz, FreeType, pugixml, unibreak, and the Rust acceleration library. AGG is part of PDFium itself and is always present.

If a release artifact reports a missing capability, report an issue.

## API stability

Each 1.x **patch** release keeps the public API of the release it follows source- and binary-compatible. A 1.x **minor** release may instead drop a public type or package whole. That is why this project does not claim [Semantic Versioning](https://semver.org/spec/v2.0.0.html), where a removal would force a major version: the 1.x line trades that guarantee for the freedom to remove a mistake in a minor release. A minor release never changes the members of a surviving type, though: fields, methods and constructors that survive keep their signatures, and a type is removed in full rather than losing individual members. Every removal is listed under "Removed" in [CHANGELOG.md](../CHANGELOG.md). Declining to claim SemVer is deliberate, not an oversight.

Every listed removal is excluded by name in [`.github/workflows/compatibility.yml`](../.github/workflows/compatibility.yml), which downloads the previous release from Maven Central and runs japicmp with `--error-on-binary-incompatibility` and `--error-on-source-incompatibility`. Any incompatible change that is not excluded there fails that check, including removing a member of a surviving type, so a minor release can drop whole types only after they are recorded here.

The upcoming 1.3.0 release removes three types that were public in 1.2.0. `stirling.software.jpdfium.text.edit` disappears with its `TextEditor` placeholder, an empty class with a private constructor and no members that no API referred to. `stirling.software.jpdfium.panama.NativeGuard` was a deprecated facade whose documented contract no longer held, and `PdfiumRuntime` replaces it. `stirling.software.jpdfium.doc.PdfAConverter` was a Ghostscript-backed helper removed with that dependency; PDF/A conformance handling now runs through the in-process compression pipeline (`PdfCompressor`). Code that names any of the three has to move to the replacement; nothing else from 1.2.0 is affected. The gate excludes exactly these three classes by name, so any further removal fails it.

## Handling untrusted PDFs

PDFs are untrusted input. JPDFium validates buffers on both sides of the FFM boundary, and reports a redaction it cannot verify rather than returning success. It does not bound the resources a hostile document consumes. Controls and recommended settings are in [deployment.md](deployment.md).

To report a vulnerability, see [SECURITY.md](../SECURITY.md).

