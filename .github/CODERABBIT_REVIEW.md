<!-- markdownlint-disable MD013 -->

# JPDFium review standards

## Scope and priorities

Review JPDFium as a Java 25 FFM library with a C++23 bridge and native integrations. Prioritize correctness/security, lifecycle reliability, bounded memory, performance, API compatibility, and maintainability, in that order when they conflict. Large PDFs and sustained workloads are first-class use cases.

Inspect the actual diff, relevant callers, ownership paths, tests, and pinned dependency contracts. Do not prescribe a replacement architecture based on assumptions. Preserve the existing bridge and codec abstractions unless a concrete defect or measured limitation justifies a change.

These rules apply to maintained source. Generated bindings and vendored code require correctness, provenance, and integration review, not manual style rewrites. Identify the generator or upstream fix when applicable.

## Review discipline

- Report a specific location, violated contract, triggering condition, impact, minimal correction, and verification test.
- Distinguish confirmed defects, plausible risks requiring investigation, and optional improvements. Static findings are not runtime reproductions.
- Never claim a test, benchmark, sanitizer run, or dependency guarantee without evidence.
- Trace Java-to-C++ behavior and dependent resources across the boundary.
- Review complete changed ownership transitions, not only changed lines.
- Prioritize changed-code issues and existing issues directly exposed by the change. Avoid unrelated cleanup sweeps.
- Group repeated style violations into one representative comment.
- Do not infer authorship from code style. Flag observable quality problems, not alleged AI provenance.
- Do not recommend speculative abstractions, fallback chains, pools, lock-free queues, or language features without a concrete need.
- Preserve stable public behavior. Flag incompatible ownership, exception, thread-access, output-format, or save semantics.

## Java 25 style

- Use imports rather than package-qualified type names in method bodies and signatures. Qualification is acceptable for genuine name collisions, generated code, and intentionally explicit disambiguation.
- Remove unused imports, raw types, dead branches, redundant casts, unnecessary wrappers, and duplicated helpers.
- Use descriptive domain names. Avoid vague utility classes, object-typed ownership tokens, and boolean option matrices.
- Prefer immutable operation parameters. Use records where they genuinely model immutable data; do not introduce records merely to appear modern.
- Use try-with-resources for deterministic ownership. Preserve original failures; cleanup must not silently mask them.
- Do not catch `Throwable` merely to swallow or log it. Catch broadly only at a justified boundary or cleanup guard, preserving fatal-error policy and cleanup ordering.
- Avoid boxing, stream pipelines, repeated string encoding, capturing callbacks, and temporary collections in demonstrated hot paths. Do not claim that source-level allocations survive JIT optimization without measurement.
- Reuse repository formatting, naming, and visibility conventions. Do not recommend preview/incubator APIs or a higher Java requirement without explicit project agreement.

## C++23 style and ownership

- Establish RAII ownership immediately after acquisition. Prefer unique ownership and stateless deleters; `shared_ptr` requires actual shared lifetime.
- Use `span`/`string_view` for bounded borrowing only when backing lifetime is established. Do not return dangling views or operation-workspace-backed results.
- Keep output allocations locally owned until successful publication. Every early return and exception must preserve the documented state and cleanup contract.
- Explicitly finalize fallible writers and check the result. A nonthrowing destructor is fallback cleanup, not evidence of successful output.
- Keep destructors and C ABI cleanup nonthrowing. Exported boundaries translate exceptions; `noexcept` alone does not translate them.
- Avoid blanket catch blocks compensating for raw-resource management, manual `new`/`delete` ownership, unnecessary `shared_ptr`, and type-erased inner-loop callbacks.
- Check conversions, integer arithmetic, sizes, alignment, finite floating-point inputs, and narrowing before allocation or pointer operations.
- Use `expected`/`optional`/strong types selectively where they clarify contracts. Do not turn simple code into a generic framework.
- Reject undefined behavior used as an optimization. Alias, alignment, lifetime, and CPU-feature assumptions must be true and tested.

## FFM and ABI

- Verify `FunctionDescriptor` argument order, return carrier, native widths, alignment, padding, and calling convention against the real C declarations.
- Audit LP64/LLP64 explicitly. `uint64_t`/`int64_t` must not become platform C `long` accidentally.
- Cache stable layouts, symbol lookups, and downcall handles. Avoid per-operation linking or generic argument-array invocation.
- Validate pointer/length pairs and capacity before reinterpretation. Zero-length address views do not establish ownership or safe bounds.
- Enforce buffer contracts in Java and C++: address, supported storage, writability, scope/thread accessibility, checked dimensions/stride/required bytes, and limits.
- Pass `MemorySegment` rather than stripping its lifetime into an address when the API can preserve segment semantics.
- Confined arenas must remain on their owner thread. An execution-backend change must account for caller-confined buffers; extracting an address is not a solution.
- Retained native pointers require an explicit lease until native retirement. A strong Java reference does not prevent explicit arena closure.
- Critical downcalls require the documented extremely short, bounded, callback-free behavior in all cases. Do not apply them to rendering/parsing/saving merely for speed.
- Upcall stubs, arenas, and captured state must outlive native callbacks. Exceptions cannot escape callbacks; callback reentrancy must follow an explicit policy.
- System call state must be captured correctly when relevant; do not read overwritten `errno` later.
- Review native-image metadata and ABI probes when signatures change. Prefer reproducible generation to handwritten drift.

## PDFium execution and lifecycle

- Enforce the current execution-domain contract. Do not assume an owner-thread backend exists or recommend changing the pinned engine's threading guarantees.
- Admission and lifecycle checks must be atomic with native use. Ordinary work, nested admitted continuation, cleanup, and destruction need distinct state rules.
- Reject new work after quiescence; accepted batches must follow their documented continuation contract.
- Cleanup may remain valid while quiescing, but must not call PDFium after destruction. Do not expose unrestricted teardown as an admission bypass.
- Page/text/form/progressive resources must retire before their parent state becomes invalid. Generation checking does not substitute for native cleanup.
- Destructive reload, replacement, flattening, and page deletion need a consistent invalidate-or-reject policy. Stale objects must remain safely closeable.
- Progressive native state retires before target/cancel memory is released. Cover immediate DONE, failure, cancellation, explicit close, parent close, and exception paths.
- Lease acquisition and caller close must have a defined linearization point. Track acquisition failures, overflow, duplicate release, and arena-close failure.
- Do not hold engine serialization across unrelated QPDF work, detached image processing, caller callbacks, or output I/O unnecessarily.
- Counters and global telemetry are not a complete ownership registry. Check registration/retirement coverage and underflow.

## Memory and performance

- Preserve file-backed large-input paths and direct native file/pipeline outputs. Flag unbounded `readAllBytes`, complete-document buffers, repeated serialization/reload, and unnecessary full-result copies.
- Distinguish Java heap, native live bytes, process RSS/PSS, allocator retention, mapped files, and temporary storage. Do not claim heap profiling or HotSpot NMT measures all dependency allocations.
- Prefer reducing work and data movement before micro-optimizing instructions. Track full-frame passes and simultaneous buffer lifetimes.
- Pools, caches, queues, and reusable scratch need byte budgets, oversized-request behavior, release policy, and bounded idle retention. `ThreadLocal` storage must not multiply unbounded native memory.
- Scratch must support alignment, nesting, exceptions, overflow, and no escaping views. Resetting a bump pointer does not invalidate old FFM segments.
- PMR must match object lifetime. Destroy dependent objects before resource release; vector growth may retain superseded allocations.
- SIMD requires a reference path and tests for tails, alignment, stride, overlap, and numerical semantics. No unchecked overread, false `restrict` promise, global fast-math, or portable `march=native` artifacts.
- Performance claims need representative measurements. Do not require a benchmark for every stylistic change or assert all locks/allocations are inherently unacceptable.
- Performance-sensitive changes should report component and end-to-end time, allocation, peak footprint, and retention where relevant. Admission counts alone are not speedup evidence.

## Native integrations

- Independent QPDF instances may run concurrently according to the pinned contract. Audit Java scratch, diagnostics, source lifetimes, and configuration before removing serialization.
- QPDF copied objects may retain source stream dependencies. File-backed output does not imply constant total memory.
- Preserve compressed streams where appropriate; expensive recompression must be explicit. Secure redacted output has different requirements from ordinary structural output.
- PDFium bitmap formats, row stride, alpha, and renderer behavior must be queried or explicitly guaranteed. Preserve public pixel semantics.
- libvips lazy graphs must retain borrowed pixels/files through evaluation and references. Avoid unnecessary materialization and uncontrolled inner/outer thread multiplication.
- Keep dependency-global error/configuration behavior honest. Do not assume every diagnostic belongs uniquely to one concurrent operation.
- Audit duplicate dependency copies, symbols, compiler/runtime compatibility, allocator boundaries, initialization, and shutdown. Objects from incompatible library copies must not cross boundaries.
- Do not add silent engine fallback that changes preservation or security semantics.

## Output, preservation, and security

- Distinguish direct streaming output from transactional replacement. Direct sinks may contain partial bytes after late failure; never imply rollback they cannot provide.
- Check source/destination aliasing, partial writes, zero-progress handling, finalization, limits, and cleanup.
- Output limits enforced after generation do not prevent native generation allocation.
- Redaction must remove content, not merely paint an overlay. Extraction checks are scoped evidence, not proof about every PDF representation.
- Define preserved/modified/rejected/unsupported behavior for forms, annotations, outlines, attachments, metadata, tagging, encryption, and signatures.
- Avoid security overclaims such as every-save sanitization or guaranteed secure deletion unless the implementation and tests support them.

## Comments and documentation

- Comments explain invariants, ownership, non-obvious constraints, or reasons. Remove line-by-line narration, repeated summaries, decorative banners, and change-history commentary.
- Keep ordinary inline comments to one or two concise sentences. Longer public contracts and subtle ABI/lifetime explanations are justified; do not shorten them until ambiguity remains.
- Reject self-congratulatory claims such as "allocation-free by construction", "authoritative proof", or "thread-safe" when their scope/evidence is missing.
- Document the implemented backend, not a future architecture as an existing guarantee.
- Remove stale TODOs, placeholder branches, speculative fallbacks, and obsolete comments in changed code. Do not require unrelated cleanup.

## Tests, profiling, and delivery

- Every defect fix needs a focused regression with an observable expected result. No `assertTrue(true)`, swallowed failures, or tests that silently pass when fixtures/tools/source roots are missing.
- Distinguish executed, skipped, aborted, stub-backed, and real-native tests. Compilation is not runtime verification.
- Use latches/barriers for concurrency ordering, bounded waits, `finally` cleanup, worker-failure capture, and termination assertions. Avoid sleep-based race tests.
- Isolate global runtime lifecycle tests. Actual library destruction belongs in subprocess tests unless supported lifecycle behavior is established.
- Test failure injection, repeated close, stale use, terminal rendering, short I/O, finalization, shutdown, and post-large-job retention.
- Fuzz operation sequences with a real state model and expected statuses, not only no-crash parsing. Bound inputs/work, preserve corpus, and instrument the relevant native code.
- Validate semantic preservation through reopen, independent structural checks, and representative rendering/text properties. A successful open is insufficient.
- Benchmark release-equivalent builds using JMH and native/end-to-end drivers as appropriate. Sanitizer/profiler timing is not shipping performance.
- Respect generated-code provenance, dependency pinning, target-scoped CMake flags, portable CPU baselines, and packaged-platform verification.

## Finding format

For each substantive issue, state:

1. Severity and confidence.
2. Exact file/symbol and triggering condition.
3. Violated invariant and observable impact.
4. Minimal maintainable correction.
5. Focused test or measurement needed.

For style-only issues, give a short actionable comment, group repetitions, and do not inflate severity. Do not duplicate formatter diagnostics or demand broad rewrites. If evidence is insufficient, request the specific missing verification rather than inventing certainty.
