# Native-first architecture

Status: accepted for the PyJPDFium first pass. Supersedes the resident-JVM/IPC default for Python.

## Decision

JPDFium's native library owns PDFium serialization, lifecycle, handle validation and memory
ownership. Java (FFM) and Python (nanobind, in the PyJPDFium repository) are peer bindings over
one safe C API, `native/bridge/include/jpdfium_api.h`. Python needs no JVM.

Native-first buys deployment simplicity and one implementation. It does not buy isolation: a
native crash still ends the host process. Hard isolation stays an optional worker process.

```text
Java API (FFM)            Python API (nanobind, PyJPDFium)
        \                      /
         safe jpdf_* C API (jpdfium_api.h)
           runtime domain . object table . output transactions
                       |
             libjpdfium (one core) + PDFium + Rust helpers
```

## Single core

The domain, the object table and PDFium itself live in `libjpdfium` only. Bindings and future
optional modules (OCR) link to it dynamically and must never compile their own copy, or two
libraries would each believe they are the only serializer.

Until Java migrates (see [JAVA_MIGRATION.md](JAVA_MIGRATION.md)), a process picks one authority:
the first of `jpdfium_init*` (legacy bridge, Java-side lock) or `jpdf_runtime_init` (native
domain) wins, and the other is refused for the life of the process.

## Source layout

| Path | Contents |
|---|---|
| `native/bridge/include/jpdfium_api.h` | Safe, versioned C API |
| `native/bridge/src/runtime/` | Domain, admission, lifecycle, error detail |
| `native/bridge/src/objects/` | Generation-checked object table |
| `native/bridge/src/pdf/` | Document, page and render entry points |
| `native/bridge/src/images/` | Owned pixel storage, format conversion, bitmap entry points |
| `native/bridge/src/output/` | Output transactions and image writers |
| `native/bridge/tests/api_*.cpp` | Table unit tests and real-PDFium API tests |

## First pass scope

In: lazy Python loading, safe document/page/bitmap handles, one native PDFium domain, concurrency
and close-race tests, stale-handle rejection, transactional render output, the Java migration
map. Out: OCR, text-layer writing, OCRmyPDF, broader bindings, optimization, IPC.

Related: [NATIVE_ABI.md](NATIVE_ABI.md), [LIFETIME.md](LIFETIME.md),
[CALLBACKS.md](CALLBACKS.md), [CAPABILITIES.md](CAPABILITIES.md), [TESTING.md](TESTING.md).
