# Callbacks and binding concurrency

## Policy

No Java or Python application code runs while the PDFium domain is held. The 1.0 safe API has no
application callbacks at all. Future progress or cancellation uses polled state (atomic flags,
buffered events), not upcalls.

Native-to-native callbacks are allowed when they cannot re-enter the API, for example the PNG
encoder's byte sink, which writes into an output transaction outside the domain.

## Locks never span managed code

The domain is acquired and released inside one native call. A binding must not expose
`enter()`/`exit()` downcalls that bracket arbitrary Java or Python code. When an atomic batch is
needed, add a coarse native operation instead.

## Python GIL sequence

```text
GIL held:     validate arguments, convert inputs to native values
GIL released: run the jpdf_* call (admission, PDFium work, leaving the domain)
GIL held:     read error detail, build the result or raise the mapped exception
```

No Python object is touched while the GIL is released. Because the domain is always released
before the GIL is reacquired, a thread waiting for the GIL can never hold the domain, so the two
locks cannot deadlock.

## Java

Java keeps its own lock only until it migrates to the safe API (see
[JAVA_MIGRATION.md](JAVA_MIGRATION.md)). Until then the authority latch prevents the Java lock
and the native domain from both owning PDFium in one process.
