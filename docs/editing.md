# Editing documents

## Flattening

`flatten()` bakes annotations and form fields into the page content stream.

```java
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

```java
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

```java
document.setSanitizeOnSave(true);
```

With it enabled, a save after redaction also removes `/Info` metadata, the structure tree root, embedded files, JavaScript, XFA, and any outline or form field value containing a redacted literal, then rewrites the file.

Sanitization requires the qpdf component. Without it, the save fails with `RedactUnverifiableException` rather than skipping the step. Font glyph outlines are not erased; the text is already removed from the content streams, but recovery from an embedded font subset is outside what this library guarantees.

`sanitizeReport()` returns a JSON report describing what the last save removed.

### Incremental save after redaction

Incremental save appends a new revision and leaves the original content recoverable in the file. JPDFium refuses it after content redaction with `RedactedSaveException`. Use `save` or `saveBytes`, which write a full file.

## Encryption and passwords

Password-protected documents open through overloads that accept a password:

```java
try (var document = PdfDocument.open(Path.of("secure.pdf"), "secret")) {
    // ...
}
```

- A `null` password throws `IllegalArgumentException`.
- An empty password performs a plain open.
- A wrong or missing password throws `PdfPasswordException`.

Encryption is applied file to file with the bundled qpdf component:

```java
PdfEncryption.encrypt(
        Path.of("plain.pdf"), Path.of("secure.pdf"), "user123", "owner456");

try (var document = PdfDocument.open(Path.of("secure.pdf"), "user123")) {
    System.out.println(PdfEncryption.isEncrypted(document.rawHandle()));
    System.out.printf("permissions=0x%08X%n", PdfEncryption.permissions(document.rawHandle()));
}
```

`PdfEncryption.encrypt` also has an overload taking a key length of 128 or 256, plus `decrypt`, `isQpdfAvailable`, `securityRevision`, `unlockOwner`, `isOwnerUnlocked`, and `removeEncryption`. The file-oriented `PdfSecurity` class exposes the same operations with an explicit permission bitmask, and adds `encryptBytes` and `decryptBytes`.

Permissions are a raw PDFium bitmask with no named constants in the Java API. `PdfDocument.permissions()` returns the same value for the open document, and returns 0 when the document is not encrypted.

To encrypt a document that is already open, `PdfEncryption.setEncryption` applies AES-256 to the in-memory handle. It must be called before saving, and the encryption takes effect during the save.

## Digital signatures

Signatures are read-only. JPDFium can enumerate them, read their metadata, and compute a digest over the bytes each signature covers.

```java
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

## What is not supported

| Capability | Status |
|---|---|
| Visual-only redaction | Refused by design, in both the Java and native layers. |
| Creating digital signatures | Not exposed. PDFium provides read access only. |
| Verifying digital signatures | Not exposed. Use an external cryptographic library. |
| Creating form fields | Not exposed. Forms can be read, filled, and flattened. |

Some capabilities depend on how the native library was compiled and throw `UnsupportedOperationException` when absent.

