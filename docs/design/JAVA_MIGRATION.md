# Java migration to the native domain

Java currently serializes PDFium with `PdfiumRuntime` (a `ReentrantLock`, admission contexts and
live-resource counters) over raw pointer-valued handles. The end state moves that authority into
the native domain so Java and Python share one enforced contract. This is a release gate for the
native-first architecture, not optional cleanup.

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
- Until migration completes, the authority latch keeps a process on one side: legacy
  `jpdfium_init*` and `jpdf_runtime_init` refuse each other.

## Inventory

[JAVA_MIGRATION_INVENTORY.md](JAVA_MIGRATION_INVENTORY.md) lists every native symbol the Java
module binds, per class, whether it reaches PDFium, and its safe replacement. Regenerate it with
`python native/tools/downcall_inventory.py` after binding changes.

At this pass: 470 symbols across 42 classes, 430 reaching PDFium, 22 with a safe replacement.

## Order

1. Lifecycle: `PdfiumRuntime` init, state and shutdown delegate to `jpdf_runtime_*`; the latch
   then lets Java claim the safe authority.
2. Documents and pages: `PdfDocument` / `PdfPage` hold `jpdf_handle` tokens; legacy raw-handle
   extraction (`jpdfium_doc_raw_handle`) becomes an explicitly unsupported escape.
3. Rendering: `PdfPage.render*` move to `jpdf_page_render` and bitmap views.
4. Remaining families (text, annotations, forms, editing, save, redaction) each get coarse safe
   operations, one family per change, with Java and Python parity fixtures.
5. Remove the Java lock once no supported path reaches PDFium without the native domain.

Families that stay on the legacy surface past step 4 must be listed here as unsupported low-level
escapes with their ownership contract.

## Legacy native follow-ups

- `jpdfium_doc_save_to_file` and the repair writers keep their own staging helpers
  (`jpdfium_document.cpp`, `jpdfium_repair.cpp`); move them onto `jpdf::OutputTransaction`.
- Legacy save staging on Windows uses narrow-char APIs (`_sopen_s`, `MoveFileExA`), so non-ANSI
  paths can fail; the safe transaction uses wide-char paths.
