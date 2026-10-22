# Editing documents

## Flattening

`flatten()` bakes annotations and form fields into the page content stream.

Excerpt, requires an open `PdfDocument`:

```java
// Excerpt: requires an open PdfDocument named document.
document.flatten();
```

After flattening:

- Existing page text remains selectable and extractable.
- Flattened elements are no longer interactive annotations or form fields. They are ordinary page content, which other tools can still modify.
- The document is not made read-only, and no permission flags are set.
- Visual-only redaction is refused in both the Java and native layers; see [Redaction](#redaction).

Two modes are available on `PdfDocument`:

| Mode | Behavior |
|---|---|
| `FlattenMode.ANNOTATIONS` | The default. Bakes annotations and form fields into page content via PDFium's `FPDFPage_Flatten`. Text stays selectable. |
| `FlattenMode.FULL` | Rasterizes each page at the configured DPI and replaces all content with an image. Nothing remains selectable or extractable. |

`FULL` uses `jpdfium.flatten.dpi`, which defaults to 150.

Rasterization invalidates pages you opened beforehand; reopen them afterwards. See [concurrency.md](concurrency.md).

`PdfSelectiveFlatten` flattens by annotation type rather than all at once. Note that it removes the targeted annotations and regenerates page content without baking their appearance streams, so the removed elements no longer appear. Use it when you intend to drop them, not to preserve their look.

## Redaction

Redaction in JPDFium removes content from the document. It is not a drawing operation.

Excerpt, requires an open `PdfPage`:

```java
// Excerpt: requires an open PdfPage named page.
page.redactPattern("\\d{3}-\\d{2}-\\d{4}", 0xFF000000);
```

For each match, JPDFium locates the text with a PCRE2 pattern, aligns matches to grapheme and shaping clusters, and applies a redaction annotation, which removes the underlying content. The fill rectangle drawn afterwards covers an area whose content has already been removed.

Matching runs against NFKC-normalized text, so a pattern that matches the glyphs you see may not match the normalized form.

### Failure behavior

- Visual-only redaction is refused. Passing `removeContent = false` throws `IllegalArgumentException`, and the native layer rejects the same request.
- If the audit finds surviving content, the call throws `RedactIncompleteException`.
- If the audit cannot run, the call throws `RedactUnverifiableException`.
- Annotations intersecting a redacted region are removed with it.
- Saving is refused while redact marks are uncommitted, throwing `UncommittedMarksException`.

Discard a document after a failed redaction rather than saving it. Reopen the source file instead.

Related APIs: `redactRegion`, `redactWords`, `redactInRect`, and the two-phase `markRedactRegion` / `commitRedactions` pair. `redactWordsEx` returns a match count.

### What the audit covers

The audit scope differs by operation, and it is narrower than "the text is gone from the file".

| Operation | Audit scope |
|---|---|
| `redactPattern`, `redactWords` | Extracted page text. The pattern is re-run against the normalized text and a fingerprint of the surviving characters is compared. |
| `redactRegion`, `redactInRect` | Page text plus a walk of page objects: text, image, path, shading, and form objects, recursing into nested forms to a bounded depth. |

Neither audit inspects document metadata, XMP, outline titles, form field values, embedded files, or `ToUnicode` CMaps. Neither detects text inside a raster image.

Annotations overlapping a redacted region are removed rather than inspected, so their contents go away with the annotation.

### Sanitization

Sanitization removes additional document data on save. It is opt-in:

Excerpt, requires an open `PdfDocument`:

```java
// Excerpt: requires an open PdfDocument named document.
document.setSanitizeOnSave(true);
```

With it enabled, a save after redaction also removes `/Info` metadata, the structure tree root, embedded files, JavaScript, XFA, and any outline or form field value containing a redacted literal, then rewrites the file.

Sanitization requires the qpdf component. Without it, the save fails with `RedactUnverifiableException` rather than skipping the step. Font glyph outlines are not erased; the text is already removed from the content streams, but recovery from an embedded font subset is outside what this library guarantees.

`sanitizeReport()` returns a JSON report describing what the last save removed.

### Incremental save after redaction

Incremental save appends a new revision and leaves the original content recoverable in the file. JPDFium refuses it after content redaction with `RedactedSaveException`. Use `save` or `saveBytes`, which write a full file.

## Encryption and passwords

Password-protected documents open through overloads that accept a password:

Excerpt:

```java
// Excerpt: opening a password-protected document.
try (var document = PdfDocument.open(Path.of("secure.pdf"), "secret")) {
    // ...
}
```

- A `null` password throws `IllegalArgumentException`.
- An empty password performs a plain open.
- A wrong or missing password throws `PdfPasswordException`.

Encryption is applied file to file with the bundled qpdf component:

Excerpt:

```java
// Excerpt: encrypting one file and reopening it with a password.
PdfEncryption.encrypt(
        Path.of("plain.pdf"), Path.of("secure.pdf"), "user123", "owner456");

try (var document = PdfDocument.open(Path.of("secure.pdf"), "user123")) {
    System.out.println(PdfEncryption.isEncrypted(document.rawHandle()));
    System.out.printf("permissions=0x%08X%n", PdfEncryption.permissions(document.rawHandle()));
}
```

`PdfEncryption.encrypt` also has an overload taking a key length of 128 or 256, plus `decrypt`, `isQpdfAvailable`, `securityRevision`, `unlockOwner`, `isOwnerUnlocked`, and `removeEncryption`. The file-oriented `PdfSecurity` class exposes the same operations with an explicit permission bitmask, and adds `encryptBytes` and `decryptBytes`.

To retain an original document's protection after processing an unencrypted working copy:

```java
import stirling.software.jpdfium.doc.PdfSecurity;

PdfSecurity.copyEncryption(processedPath, outputPath, originalPath, knownPassword);
```

Either original password is sufficient. Both passwords, permission flags, and encryption settings are retained, including an unknown second password. `PdfSecurity.isCopyEncryptionSupported()` checks for the bundled native capability; no separate qpdf executable is needed. The input must be unencrypted, the source encrypted, and the output distinct from both. A failed operation leaves an existing destination untouched and removes temporary output.

This copies encryption, not source content or digital signatures. The application must enforce editing permissions and signature restrictions. Copying also reuses the source encryption key; use `encrypt` with explicitly chosen passwords when a fresh key is required.

Permissions are a raw PDFium bitmask with no named constants in the Java API. `PdfDocument.permissions()` returns the same value for the open document, and returns 0 when the document is not encrypted.

To encrypt a document that is already open, `PdfEncryption.setEncryption` applies AES-256 to the in-memory handle. It must be called before saving, and the encryption takes effect during the save.

## Digital signatures

Signatures are read-only. JPDFium can enumerate them, read their metadata, and compute a digest over the bytes each signature covers.

Excerpt, requires an open `PdfDocument`:

```java
// Excerpt: requires an open PdfDocument named document.
// SHA-256 digest of the bytes in this signature's /ByteRange.
byte[] digest = document.signatureDigest(0, 1); // 1 = SHA-256
```

The return value is a digest, not the signed bytes themselves and not the signature container. It is one input to external verification.

JPDFium does not create signatures, verify cryptographic signatures, or validate certificate trust. Use an external PDF signing or verification library for those operations.

`signatureDetails`, `signatureRevisionCount`, and `PdfSignatures` provide the remaining read-only access.

Whether an existing signature remains valid across a save is not determined by JPDFium. Because `save()` performs a full rewrite, verify signatures yourself after any save.

## Save semantics

`save()` writes a complete new file rather than appending a revision.

- The write goes to a staging file created as a sibling of the destination, so publication stays on one filesystem.
- Publication uses `rename` on POSIX and `MoveFileEx` with `MOVEFILE_REPLACE_EXISTING` on Windows. There is no copy fallback.
- If publication fails, the staging file is removed and the call reports an error. The destination is never opened, so an existing file there is unchanged.
- A failed PDFium write, a flush failure, or a close failure discards the staging file and reports an error.
- Zero-byte output is treated as a failure.
- Saving over the document's own source file is refused.
- Metadata, structure tree, and outlines are not removed by a plain save. Removal requires an explicit request through `PdfSanitizer`, `PdfSecurity`, or `RedactOptions`.
- Linearization is not retained. If you need a linearized file, run `PdfLinearizer.linearize` as a separate step after saving.

`SaveOptions` exposes `maxOutputBytes` and `verifyReopen`. There are no flags for encryption, PDF version, linearization, or signature handling.

## Color conversion

`PdfColorConverter` rewrites page-object fill and stroke colors in place.

```java
PdfColorConverter.toGrayscale(doc);   // luminance preserving
PdfColorConverter.toRgb(doc);         // rewrite everything as DeviceRGB
```

Grayscale and DeviceRGB always work because PDFium exposes RGB setters directly.
DeviceCMYK and image conversion depend on how the native library was built, so probe
first. Each probe returns `false` when the loaded library lacks the required support,
and the corresponding conversion raises `JPDFiumException` rather than silently
producing the wrong colorspace:

| Probe | Meaning |
|---|---|
| `PdfColorConverter.supportsColorSpace(ColorSpace.CMYK)` | PDFium exposes the CMYK setters, the source colour-space probe **and** the raw-component/ICC-profile probes |
| `PdfColorConverter.supportsFormConversion()` | form XObject children can be edited and written back |
| `PdfColorConverter.supportsImageConversion()` | the CMYK JPEG encoder is compiled in |
| `PdfOutputIntent.isSupported()` | QPDF is available for catalog editing |

```java
PdfColorConverter.ColorConvertOptions opts = PdfColorConverter.ColorConvertOptions.builder()
        .targetColorSpace(PdfColorConverter.ColorSpace.CMYK)
        .iccProfile(cmykProfileBytes)   // optional; null = naive black generation
        .convertForms(true)             // recurse into form XObjects when supported
        .jpegQuality(90)
        .strictImages(true)
        .build();
int converted = PdfColorConverter.convert(doc, opts);
```

With a CMYK target, already-DeviceCMYK objects keep their exact ink values.
DeviceGray is also preserved: it is already a valid process colour (K) and
converting it forces a page-content rebuild that can shift text and spot-colour
artwork. By default ICCBased, Separation, DeviceN, Indexed and pattern colours
are left untouched: rewriting them through the RGB getter would flatten spot
colours and destroy overprint semantics. DeviceRGB text/path objects are
converted.

Opt in with `convertSpecialColors(true)` to convert ICCBased/Cal/Lab/Indexed colours;
ICCBased fills use their embedded source ICC profile when one is available. Spots stay
preserved unless `convertSpotColors(true)` is also set, in which case their tint
transform is used and the spot name/overprint intent is lost. Patterns are never
flattened.

Text and path colors become DeviceCMYK `k`/`K` operators only when the PDFium build
carries the JPDFium color patch (`FPDFPageObj_SetFillColorCMYK`,
`FPDFPageObj_GetFillColorSpace`, `FPDFFormObj_MarkDirty`). Without it, convert images
only:

```java
int images = PdfColorConverter.convertImagesToCmyk(doc, 90);   // -1 when unavailable
```

Form XObjects are recursed into when `supportsFormConversion()` is true; shared forms
are visited once per document and their content stream is marked dirty before the page
is regenerated. On stock PDFium the traversal APIs exist but the dirty marker does not,
so form content is skipped rather than silently lost.

DeviceRGB image XObjects whose source stream is DCT/JPEG are decoded, converted
to CMYK and re-encoded as 4-component JPEGs. Plain Flate sources (line art,
screenshots, QR codes) are left untouched by default; call
`convertLosslessImages(true)` to opt into converting them too. DeviceGray and
already-DeviceCMYK images are preserved; grayscale is already K-only and valid
in PDF/X. Images whose rendered alpha is not fully opaque are skipped, because
the public PDFium API cannot preserve the mask when the image stream is
replaced. On a patched build with form conversion, images inside form XObjects
are visited too. The image dictionary keys `/OC`, `/Intent`, `/Interpolate`,
`/Name`, `/Metadata` and `/Alternates` are preserved when the JPEG
stream is replaced.

For print-accurate CMYK, attach an ICC output intent and pass the same profile to the
converter. The profile is sampled into one 33x33x33 RGB-to-CMYK lookup table that is
shared by vectors and images, so an sRGB fill and an sRGB image pixel with the same
components receive the same ink values. Without a profile, both fall back to
profile-free naive black generation (`c=255-r, m=255-g, y=255-b; k=min(c,m,y)`).

```java
byte[] icc = Files.readAllBytes(Path.of("CoatedFOGRA39.icc"));
doc = PdfOutputIntent.apply(doc, icc, PdfOutputIntent.Spec.pdfx("FOGRA39", "Coated FOGRA39"));
```

`PdfOutputIntent.apply` returns a new document; the original is left open. The native
path rewrites the whole file with QPDF, merges `/OutputIntents` (replacing intents with
the same `/S` while preserving unrelated intents such as a PDF/A `GTS_PDFA1` entry) and
preserves the original stream encoding. It does not change the PDF header or XMP
metadata; use `PdfOutputIntent.applyPdfxMetadata` or `PdfXConverter` for the PDF/X
version header and `GTS_PDFXVersion` tags. It is not a metadata-only incremental update:
validate rendering after changing the print condition.

For PDF/X-3/-4 metadata application, use `PdfXConverter`. It runs the CMYK colour and
image conversion, then writes a GTS_PDFX output intent and both XMP version tags. The
same ICC profile is used as the vector and image conversion target:

```java
PdfXOptions pdfx = PdfXOptions.builder()
        .standard(PdfXStandard.PDF_X_4)
        .useCompactCmyk()   // bundled 5.2 KB generic fallback
        .build();

// Or supply a print-condition profile explicitly:
PdfXOptions explicit = PdfXOptions.builder()
        .standard(PdfXStandard.PDF_X_4)
        .outputIntentProfile(cmykProfileBytes)
        .conditionIdentifier("FOGRA39")
        .info("Coated FOGRA39")
        .build();
try (PdfDocument out = PdfXConverter.applyMetadata(doc, pdfx)) {
    out.save(target);
}
```

This is metadata application, not certification: PDF/X-1a flattening, PDF/X-6/PDF 2.0
rules, transparency checks, font embedding, trim/trap/ID requirements and a full
preflight report are not implemented. Run veraPDF or Acrobat preflight before making a
compliance claim.

`PdfRecolor` rewrites the colors of existing vector objects, which raster
contrast/brightness adjustment (see `PdfColorAdjuster`) cannot do:

```java
PdfRecolor.invert(doc);                // dark mode; near-white fills are preserved
PdfRecolor.tint(doc, 0, 0, 128, 50);   // toward navy, 50% strength
PdfRecolor.highContrast(doc, 1.5);
PdfRecolor.binarize(doc, 128);
```

With the JPDFium colour-space probe, only DeviceRGB/DeviceGray fills and strokes are
touched. DeviceCMYK, ICC, Separation, DeviceN, Indexed and pattern colors are skipped.
On stock PDFium the probe is absent, so the legacy fallback still skips DeviceCMYK and
may rewrite other spaces through sRGB; recolour print files only on a patched native.
Content inside form XObjects is not recursed into.

## What is not supported

| Capability | Status |
|---|---|
| Visual-only redaction | Refused by design, in both the Java and native layers. |
| Creating digital signatures | Not exposed. PDFium provides read access only. |
| Verifying digital signatures | Not exposed. Use an external cryptographic library. |
| Creating form fields | Not exposed. Forms can be read, filled, and flattened. |

Some capabilities depend on how the native library was compiled and throw `UnsupportedOperationException` when absent.

