# Concurrency

PDFium calls are serialized within each process. Adding threads does not parallelize PDFium rendering, although non-PDFium work may overlap.

## Rules

A document may be used by different threads sequentially, but access must not overlap. Its pages follow the same rule.

`close()` is idempotent: a second call returns without releasing the native handle again. It acquires the same lock as PDFium calls, so it cannot free the document in the middle of a single native call.

It does not track a compound operation. A redaction or save that spans several native calls can have its handle freed by a concurrent `close()` between two of them. Do not close a document while another thread is using it.

Library initialization and shutdown happen once per process. No caller action is required.

## Why access must not overlap

Structural operations free the underlying native state and replace it. The document's structural epoch is incremented, and pages opened beforehand become invalid. Those pages then fail with `IllegalStateException` on their next use. JPDFium does not retarget a page at a newer document state.

Reopen pages after a structural operation:

```java
void rasterize(Path input, Path output) throws IOException {
    try (var document = PdfDocument.open(input)) {
        document.flatten(FlattenMode.FULL);
        try (var page = document.page(0)) {
            page.renderTo(output, 150);
        }
    }
}
```

## Why PDFium work is serialized

Upstream PDFium shares mutable state across all documents, including the font manager and cache, the page module, parser tables, and the last-error slot. JPDFium admits one PDFium call at a time through a single process-wide lock, so throughput does not increase with thread count.

Java work that does not enter PDFium is unaffected. Text processing, image encoding, and file I/O between native calls run in parallel, which is why `PdfPipeline` can process pages concurrently while the native portion stays serialized.

`PdfPipeline.PDFIUM_LOCK` is deprecated as of 1.0.4. JPDFium serializes PDFium calls internally, so caller-side locking is unnecessary. The field remains so existing `synchronized (PDFIUM_LOCK)` blocks keep compiling.

## Observing contention

`PdfiumRuntime.stats()` reports the acquisition count unconditionally, plus native-call wait and hold times. Enable wait/hold timing with `-Djpdfium.nativeGuard.telemetry=true`. Wait time well above hold time indicates threads are waiting on the lock.

## Parallel work

Independent documents can be processed on separate threads, and `PdfPipeline` parallelizes the non-native work between calls. These properties bound how much runs at once:

| Property | Default |
|---|---|
| `jpdfium.pipeline.maxParallelism` | `0`, unbounded |
| `jpdfium.image.maxConcurrency` | `0`, unbounded |
| `jpdfium.qpdf.maxConcurrency` | `0`, unbounded |

These limits bound admission, not memory. Two admitted operations may each allocate their full working set. Apply a process-level memory limit if you need a hard ceiling.

Negative values are rejected as invalid configuration rather than treated as unlimited.

See [configuration.md](configuration.md) for the full list.

## Scaling beyond one process

To use more than one core for native work, run bounded worker processes, each with its own JVM and documents.

## Cancellation

Cancellation is cooperative. Resources that running native work still owns, such as memory arenas, permits, and staging files, are released when the task exits, not when cancellation is requested. Cancelling a `Future` does not interrupt an active native call.

Because cancellation is not immediate, do not assume the document is in a known state when a cancelled task returns. Discard and reopen it unless you can confirm which step completed.

Output is published only by the task that commits it. A cancellation arriving after the commit does not prevent publication.

## Shared buffers

Each render allocates its own output buffer. Do not write into a buffer from another thread while a render is in progress. JPDFium validates capacity and dimensions on the Java side and independently in the native layer, but it cannot detect an application-level data race.
