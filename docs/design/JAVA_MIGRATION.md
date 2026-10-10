# Java migration to the native domain

Java used to serialize PDFium with `PdfiumRuntime` (a `ReentrantLock`, admission contexts and
live-resource counters) over raw pointer-valued handles. The native domain now owns that
authority, so Java and Python share one enforced contract. Moving the remaining Java families
onto safe `jpdf_*` operations is the rest of this release gate.

## Status

Done:

- Every PDFium entry point Java binds goes through its `jpdf_guard_*` shim, resolved by
  `NativeDomain` from `Symbols`, `FastLinks` and the jextract `JpdfiumH` lookup. PDFium symbols
  without a guard are treated as absent, so no raw PDFium path remains.
- `PdfiumRuntime` holds no lock. `state`, `quiesce`, `resume`, `shutdown` and `stats` delegate to
  `jpdf_runtime_*`; its live counts remain only to refuse shutdown while Java wrappers are open.
- `NativeDomain` starts the runtime in its class init, before any guarded handle exists.
- Per-thread scratch segments replace the global ones in `JpdfiumLib` that relied on the lock.
- The bridge ABI is version 2; older natives are refused at load.

Remaining: steps 2-4 below, and moving the `FileWriteSink` save upcall onto the native file
writer so no Java code runs inside a guarded PDFium call.

## End state

- Java calls `jpdf_*` operations. Native code owns serialization, lifecycle and handle checks.
- `PdfiumRuntime` delegates state, diagnostics and shutdown to `jpdf_runtime_*`.
- Java keeps only binding bookkeeping, such as wrapper `closed` flags.
- No independently reachable PDFium path keeps a second serializer.

## Rules

- Do not reinterpret legacy pointer handles as `jpdf_handle` tokens. Migrate deliberately to the
  separate safe surface.
- Native acquisition and release stay inside one downcall. Never expose an FFM `enter()` /
  `exit()` pair around Java code; add coarse native operations for atomic batches.
- Raw `jpdfium_init*` callers and the safe runtime refuse each other, so an unguarded legacy
  caller can never run beside the domain.
- A sequence of guarded calls is not atomic. Keep the documented rule that one document is used
  by one thread at a time, and add a coarse native operation where atomicity matters.

## Inventory

[JAVA_MIGRATION_INVENTORY.md](JAVA_MIGRATION_INVENTORY.md) lists every native symbol the Java
module binds, per class, whether it reaches PDFium, and its safe replacement. Regenerate it with
`python native/tools/downcall_inventory.py` after binding changes.

Now: 466 symbols across 40 classes; 439 bound through a guard; 9 EmbedPDF annotation symbols are
absent from the pinned PDFium build; 19 have a safe `jpdf_*` replacement.

## Order

1. Done: lifecycle delegates to `jpdf_runtime_*`; every PDFium call is guarded.
2. Documents and pages: `PdfDocument` / `PdfPage` hold `jpdf_handle` tokens; legacy raw-handle
   extraction (`jpdfium_doc_raw_handle`) becomes an explicitly unsupported escape.
3. Rendering: `PdfPage.render*` move to `jpdf_page_render` and bitmap views.
4. Remaining families (text, annotations, forms, editing, save, redaction) each get coarse safe
   operations, one family per change, with Java and Python parity fixtures.
5. Done: the Java lock is gone.

Families that stay on the legacy surface past step 4 must be listed here as unsupported low-level
escapes with their ownership contract.

## Legacy native follow-ups

- `jpdfium_doc_save_to_file` and the repair writers keep their own staging helpers
  (`jpdfium_document.cpp`, `jpdfium_repair.cpp`); move them onto `jpdf::OutputTransaction`.
- Legacy save staging on Windows uses narrow-char APIs (`_sopen_s`, `MoveFileExA`), so non-ANSI
  paths can fail; the safe transaction uses wide-char paths.
