# Concurrency

PDFium calls are serialized within each loaded copy of PDFium. Adding threads does not parallelize PDFium rendering, although non-PDFium work may overlap; to run PDFium on several cores, see [Several PDFium instances in one process](#several-pdfium-instances-in-one-process).

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

## Several PDFium instances in one process

`PdfiumInstancePool` loads independent copies of PDFium in one JVM, so native work runs on several cores without a JVM per engine. Each isolated instance has its own class loader, its own copy of JPDFium's classes and lock, and its own renamed copy of the native libraries.

```java
PdfiumInstancePool pool = PdfiumInstancePool.builder()
        .size(4)                         // instance 0 plus 3 isolated instances
        .isolate("com.example.pdfjobs.") // your classes that use JPDFium types
        .build();

String text = (String) pool.call("com.example.pdfjobs.ExtractText", pdfBytes);
```

A task is a class under an isolated package that implements `Function<Object, Object>` and has a public no-argument constructor. Inputs and results must be JDK types, because JPDFium types from one instance are different classes in another. Instance 0 is the caller's own JPDFium and shares its lock with any other JPDFium use in the JVM.

What to know before using it:

- **A crash in any instance ends the process.** Host the pool in a worker process when a damaged document must not take anything else down.
- **Linux and macOS only.** On Windows `PdfiumInstancePool.maxInstances()` is 1.
- **Natives must load from the classpath bundle.** Each instance needs a directory it can copy; a library found through `System.loadLibrary` has none.
- **Each instance copies the bundle once** into `instance-NNN` inside the native cache entry (about 33 MB on Linux), renaming every library except PartitionAlloc, which must stay single per process. The copies are owner-only like the entry, removed with it, and reused across runs only while their recorded SHA-256 hashes match (`-Djpdfium.native.verify=marker` skips the hashing, as for the cache). Concurrent processes preparing the same copy wait on a file lock. On macOS each copy is re-signed ad hoc with `/usr/bin/codesign`.
- **Instance numbers are unique per process**, up to 999, because the JVM refuses to load one library file in two class loaders.
- The pool checks that each instance's bridge and bindings resolved PDFium to its own copy, and on Linux that `/proc/self/maps` shows no PDFium or bridge outside the cache entry and every renamed library inside its own instance directory. It fails the build otherwise.

## Scaling beyond one process

To use more than one core for native work without sharing a process, run bounded worker processes, each with its own JVM and documents. A worker process can itself host a `PdfiumInstancePool`.

## Cancellation

Cancellation is cooperative. Resources that running native work still owns, such as memory arenas, permits, and staging files, are released when the task exits, not when cancellation is requested. Cancelling a `Future` does not interrupt an active native call.

Because cancellation is not immediate, do not assume the document is in a known state when a cancelled task returns. Discard and reopen it unless you can confirm which step completed.

Output is published only by the task that commits it. A cancellation arriving after the commit does not prevent publication.

## Shared buffers

Each render allocates its own output buffer. Do not write into a buffer from another thread while a render is in progress. JPDFium validates capacity and dimensions on the Java side and independently in the native layer, but it cannot detect an application-level data race.
