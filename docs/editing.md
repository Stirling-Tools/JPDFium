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

## Metadata

`PdfDocument.metadata()` reads the standard /Info entries and custom keys.
`PdfMetadata.of(document)` exposes typed getters, `custom()`, `trapped()`, and
the catalog XMP packet through `xmp()`.

`document.editMetadata()` returns a `PdfMetadataEditor` that writes standard
fields, custom keys, /Trapped, and the XMP packet:

```java
try (PdfDocument document = PdfDocument.open(input)) {
    document.editMetadata()
            .title("Quarterly report")
            .author("Finance")
            .custom("Department", "Finance")
            .xmp(xmpPacket)
            .applyAndSave(output);
}
```

`applyAndSave(output)` applies the changes and saves with the signature-aware
policy described below. A null value removes an /Info entry,
`removeCustom` removes a custom key, and `removeXmp` removes the catalog packet.
Custom keys must be non-reserved PDF name tokens.

Metadata edits preserve signatures and PDF/A or PDF/X claims by default:

- On a signed document, `applyAndSave` appends an incremental revision for
  /Info and /Trapped changes, so the bytes covered by existing signatures stay
  unchanged. Replacing or removing XMP is refused because it requires a full
  QPDF rewrite. Call `preserveSignatures(false)` only when invalidating the
  signature is intentional.
- Replacing XMP on a PDF/A or PDF/X document keeps the existing identification,
  extension, and PDF/X version properties. Standard /Info changes are mirrored
  into the matching XMP properties, and `removeXmp` is refused. Call
  `preserveConformance(false)` to opt out.
- A document that is both signed and PDF/A or PDF/X refuses /Info changes while
  both policies are enabled; disable one policy explicitly.

`apply()` alone does not choose a save mode. For a signed document, use
`applyAndSave(output)` or `PdfDocument.saveIncremental(output)`. A full
`save()` rewrites every object and invalidates the signature.

Setting XMP rewrites the document through QPDF, so object-stream,
cross-reference-stream, and compressed-content PDFs are accepted. Apply
metadata before encryption.

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

`save()` performs a full rewrite and invalidates existing signatures. Use
`saveIncremental()` when the document must remain signed; it appends only the
changed objects, leaving the original signed revision intact. `PdfMetadataEditor`
does this automatically in `applyAndSave()` for /Info-only edits. Whether a
particular signature profile accepts appended revisions is a policy decision
made by the validator; cryptographic validation of the original revision is
preserved.

## Save semantics

`save()` writes a complete new file rather than appending a revision.
`saveIncremental()` writes the original bytes followed by a revision containing
only the changed objects; it is the save mode used for signed documents.

- The write goes to a staging file created as a sibling of the destination, so publication stays on one filesystem.
- Publication uses `rename` on POSIX and `MoveFileEx` with `MOVEFILE_REPLACE_EXISTING` on Windows. There is no copy fallback.
- If publication fails, the staging file is removed and the call reports an error. The destination is never opened, so an existing file there is unchanged.
- A failed PDFium write, a flush failure, or a close failure discards the staging file and reports an error.
- Zero-byte output is treated as a failure.
- Saving over the document's own source file is refused.
- Metadata, structure tree, and outlines are not removed by a plain save. Removal requires an explicit request through `PdfSanitizer`, `PdfSecurity`, or `RedactOptions`.
- Linearization is not retained. If you need a linearized file, run `PdfLinearizer.linearize` as a separate step after saving.

`SaveOptions` exposes `maxOutputBytes` and `verifyReopen`. There are no flags for encryption, PDF version, linearization, or signature handling.

## What is not supported

| Capability | Status |
|---|---|
| Visual-only redaction | Refused by design, in both the Java and native layers. |
| Creating digital signatures | Not exposed. PDFium provides read access only. |
| Verifying digital signatures | Not exposed. Use an external cryptographic library. |
| Creating form fields | Not exposed. Forms can be read, filled, and flattened. |

Some capabilities depend on how the native library was compiled and throw `UnsupportedOperationException` when absent.

