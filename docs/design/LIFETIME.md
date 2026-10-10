# Runtime, handle and resource lifetime

## Runtime states

```text
UNINITIALIZED --init--> RUNNING --quiesce/shutdown--> QUIESCING --> DESTROYING --> STOPPED
                                                                              \--> FAILED
```

- `jpdf_runtime_init` is explicit lazy initialization. Bindings call it on first use; importing a
  binding loads nothing. Calling it again while running is a no-op.
- Closing a document never shuts the runtime down. The runtime normally lives for the process.
- `jpdf_runtime_quiesce` stops admitting ordinary work. Closing existing pages and documents is
  still allowed.
- `jpdf_runtime_shutdown` quiesces, then destroys PDFium only when no documents or pages remain.
  Otherwise it returns `JPDF_E_BUSY` with the counts and stays `QUIESCING` so the caller can
  close what is open and retry.
- `jpdf_runtime_resume` returns `QUIESCING` to `RUNNING`, for callers that abandon a shutdown.
- `jpdf_runtime_shutdown_within(ms)` gives up with `JPDF_E_TIMEOUT` if the domain stays busy, so
  an exit hook never waits behind a long operation.
- `STOPPED` and `FAILED` are terminal for the process. There is no silent reinitialization.

## Admission

Every entry point that touches PDFium follows one sequence:

```text
validate plain arguments -> admit -> resolve handles -> execute PDFium work -> leave -> return
```

`jpdf::Admission` is an RAII guard over the single domain mutex. A refused admission holds nothing;
a granted one is released exactly once by its destructor, on every return and exception path.
The runtime state is checked inside the mutex, so admission and shutdown cannot interleave.

The mutex is not recursive. Internal helpers run under the caller's admission and never admit
again; a public entry point reached from inside the domain gets `JPDF_E_REENTRANT` instead of a
deadlock.

Legacy entry points are admitted the same way through their `jpdf_guard_<name>` shim, one call
per admission. Close and free functions are teardown and stay admitted while quiescing. A refused
shim returns a zero value and records the status for `jpdf_guard_take_refusal`, which bindings
check after every guarded call.

Only PDFium work runs admitted. Pixel conversion, PNG encoding and file publication run after
leaving the domain, and bitmap operations never enter it.

## Handles

A `jpdf_handle` is an opaque 64-bit token: object type, slot generation and slot index. Before any
dereference the table checks that the token was issued, its type matches, its generation matches
the slot and the object is still live. A slot whose generation would wrap is retired permanently,
so no stale token can become valid again.

## Close policy

- Close is idempotent at the binding level; natively a second close is `JPDF_E_STALE_HANDLE`.
- Children retain parents: a page holds a reference to its document.
- Closing a document with open pages is `JPDF_E_BUSY` and leaves the document fully usable.
- PDFium objects are destroyed only while admitted. Object destructors never call PDFium.
- There is no cascading close and no close from destructors in native code.

## Bitmaps and views

A render produces an independently owned bitmap: closing its page or document does not affect it,
and it survives runtime shutdown. `jpdf_bitmap_map` returns a view that pins the pixels; the
pointer stays valid until `jpdf_view_release`, even if the bitmap handle is closed first. Pixels
are immutable after publication, so views need no locking.

## Output transactions

`created -> writing -> finalized -> validated -> published`, or `aborted` on any failure.

- The staging file is created exclusively, without following links, beside the
  symlink-resolved destination, so publication is a same-directory rename.
- Output is bounded by `max_bytes`; exceeding it aborts mid-write.
- Publication is atomic replacement only (`rename(2)`, `MoveFileExW`). There is no non-atomic
  fallback; a refused rename fails the operation and leaves the destination untouched.
- `JPDF_WRITE_DURABLE` adds fsync of the file and its directory. It is a separate policy from
  atomicity.
- A pre-publication failure removes the staging file and leaves the destination unchanged.
