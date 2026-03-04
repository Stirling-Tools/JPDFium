# Contributing to JPDFium

## Development Setup

1. **Clone**
   ```bash
   git clone https://github.com/Stirling-Tools/JPDFium.git
   cd JPDFium
   ```

2. **Build the stub native library** (no PDFium download needed)
   ```bash
   g++ -std=c++17 -shared -fPIC -O2 \
       -Inative/bridge/include \
       native/bridge/src/jpdfium_stub.cpp \
       -o jpdfium-natives/jpdfium-natives-linux-x64/src/main/resources/natives/linux-x64/libjpdfium.so
   ```

3. **Run all unit tests**
   ```bash
   ./gradlew test
   ```

4. **Open in IntelliJ IDEA** — import as a Gradle project. Add
   `--enable-native-access=ALL-UNNAMED` to Run Configurations → Templates → Application → VM Options.

## Project Structure

```
JPDFium/
├── native/
│   ├── bridge/
│   │   ├── include/
│   │   │   ├── jpdfium.h              # Public C API (consumed by jextract)
│   │   │   └── jpdfium_internal.h     # DocWrapper, PageWrapper, helpers
│   │   └── src/
│   │       ├── jpdfium_document.cpp   # Real PDFium implementation
│   │       └── jpdfium_stub.cpp       # Stub for testing without PDFium
│   ├── setup-pdfium.sh                # Download bblanchon/pdfium-binaries
│   ├── build-real.sh                  # Build bridge against real PDFium
│   └── build-stub.sh                  # Build stub only
│
├── jpdfium/                           # All Java source (main module)
│   └── src/
│       ├── main/java/stirling/software/jpdfium/
│       │   ├── PdfDocument.java
│       │   ├── PdfPage.java
│       │   ├── exception/             # JPDFiumException hierarchy
│       │   ├── fonts/                 # FontInfo record
│       │   ├── model/                 # Rect, PageSize, RenderResult
│       │   ├── panama/                # NativeLoader, JpdfiumLib, JpdfiumH (generated)
│       │   ├── redact/                # PdfRedactor, RedactOptions, RedactResult
│       │   ├── text/                  # PdfTextExtractor, PdfTextSearcher, PageText, …
│       │   ├── text/edit/             # TextEditor (stub)
│       │   └── transform/             # PageOps
│       └── test/java/stirling/software/jpdfium/
│           ├── PdfDocumentTest.java   # Unit tests (stub native)
│           ├── RealPdfIntegrationTest.java  # Integration tests (real PDFium)
│           ├── PdfViewerApp.java      # Swing visual viewer
│           ├── samples/               # Numbered manual-test classes (S01–S08)
│           └── …
│
├── jpdfium-natives/                   # Platform-specific native JARs
│   ├── jpdfium-natives-linux-x64/
│   └── …
├── jpdfium-spring/                    # Spring Boot auto-configuration
├── jpdfium-bom/                       # Maven BOM
└── buildSrc/                          # Gradle convention plugins
```

## Adding a New Native Function

Adding a feature follows a consistent pattern across all layers:

**Step 1 — C header** (`native/bridge/include/jpdfium.h`):
```c
JPDFIUM_EXPORT int32_t jpdfium_page_rotate(int64_t page, int32_t rotation);
```
Rules:
- Return `int32_t` error codes (`0` = OK, negative = error)
- Opaque handles use `int64_t`
- Output params via pointer (`float* width`)
- Caller-freed buffers via double pointer + length (`uint8_t** data, int64_t* len`)
- Strings are UTF-8 `const char*` — convert to UTF-16LE internally for PDFium

**Step 2 — Real implementation** (`native/bridge/src/jpdfium_document.cpp`):
```cpp
int32_t jpdfium_page_rotate(int64_t page, int32_t rotation) {
    PageWrapper* pw = decodePage(page);
    if (!pw || !pw->page) return JPDFIUM_ERR_INVALID;
    FPDFPage_SetRotation(pw->page, rotation);
    return JPDFIUM_OK;
}
```

**Step 3 — Stub** (`native/bridge/src/jpdfium_stub.cpp`):
```cpp
int32_t jpdfium_page_rotate(int64_t, int32_t) { return JPDFIUM_OK; }
```

**Step 4 — jextract function list** (`jpdfium/build.gradle.kts`):
```kotlin
val jpdfiumFunctions = listOf(
    …,
    "jpdfium_page_rotate"   // add here
)
```
Regenerate: `./gradlew :jpdfium:generateBindings`

> **Note on redaction functions:** `jpdfium_redact_words` delegates to `jpdfium_redact_words_ex`
> internally. New redaction features should be added to `jpdfium_redact_words_ex` (and its stub)
> rather than creating a third variant.

**Step 5 — Java wrapper** (`panama/JpdfiumLib.java`):
```java
public static void pageRotate(long page, int rotation) {
    check(JpdfiumH.jpdfium_page_rotate(page, rotation), "pageRotate");
}
```

**Step 6 — High-level API** (`PdfPage.java`):
```java
public void rotate(int rotation) {
    ensureOpen();
    JpdfiumLib.pageRotate(handle, rotation);
}
```

**Step 7 — Tests** — a unit test (stub, always runs in `./gradlew test`) and an
integration test tagged with `@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")`.

**Step 8 — Rebuild and verify:**
```bash
# Rebuild stub
g++ -std=c++17 -shared -fPIC -O2 -Inative/bridge/include \
    native/bridge/src/jpdfium_stub.cpp \
    -o jpdfium-natives/jpdfium-natives-linux-x64/src/main/resources/natives/linux-x64/libjpdfium.so

./gradlew test                       # unit tests (stub)
./gradlew :jpdfium:integrationTest   # integration tests (real PDFium)
```

## Build with Real PDFium

```bash
# 1. Download (~25 MB, gitignored)
bash native/setup-pdfium.sh

# 2. Compile real bridge
PDFIUM_DIR=native/pdfium
g++ -std=c++17 -shared -fPIC -O2 \
    -Inative/bridge/include -I${PDFIUM_DIR}/include \
    native/bridge/src/jpdfium_document.cpp \
    -L${PDFIUM_DIR}/lib -lpdfium \
    -Wl,-rpath,'$ORIGIN' \
    -o jpdfium-natives/jpdfium-natives-linux-x64/src/main/resources/natives/linux-x64/libjpdfium.so

# 3. Copy libpdfium.so alongside bridge (required for $ORIGIN rpath)
cp ${PDFIUM_DIR}/lib/libpdfium.so \
   jpdfium-natives/jpdfium-natives-linux-x64/src/main/resources/natives/linux-x64/

# 4. Integration tests
./gradlew :jpdfium:integrationTest
```

## Manual Testing

The `samples` package provides quick 1-click runnable classes for each feature:

```bash
# Visual Swing viewer
./gradlew :jpdfium:viewer [-Ppdf=/path/to/file.pdf]
```

Or right-click any `S01_Render` … `S08_FullPipeline` class in IntelliJ and hit Run.
`RunAllSamples` runs all 8 in sequence. Output lands in `jpdfium/samples-output/`.

See `jpdfium/src/test/java/stirling/software/jpdfium/samples/` for details.

## Key Design Decisions

- **C bridge, not raw jextract** — PDFium's 400+ function API has platform-specific wide
  strings and complex lifetime rules. The bridge owns all of this.
- **Handles as `int64_t`** — Opaque, no structs across the FFM boundary.
- **Bridge copies byte buffers** — `jpdfium_doc_open_bytes` copies data because the Java
  `Arena` frees it before the document is closed.
- **BGRA → RGBA in C** — PDFium renders BGRA; the bridge swaps channels so Java always
  receives consistent RGBA.
- **No `FPDF_ApplyRedactions`** — Does not exist in the public PDFium API.
- **Object Fission Algorithm** — True text removal via `jpdfium_redact_words_ex`:
  1. Map text-page char indices → page objects (spatial correlation, bounding-box centres).
  2. Fully-contained objects → `FPDFPage_RemoveObject` + `FPDFPageObj_Destroy`.
  3. Partially-overlapping objects → split into Prefix + Suffix text objects. Prefix uses
     the original transformation matrix; Suffix uses original `a,b,c,d` + `e,f` from
     `FPDFText_GetCharOrigin` so surviving text stays pinned at its absolute position.
  4. Fallback: objects unmapped by spatial correlation removed if ≥70% within match bbox.
  5. Paint filled rectangles at all match bboxes, then single `FPDFPage_GenerateContent`.
  The legacy `jpdfium_redact_words` now delegates to `jpdfium_redact_words_ex`.
- **UTF-16LE for search** — `FPDFText_FindStart` expects 2-byte UTF-16LE, not 4-byte
  `wchar_t`. Use `utf8_to_utf16le()` helper.
- **Wide regex for pattern redaction** — `std::wregex` on a `std::wstring` built from
  PDFium codepoints handles accented, CJK, and Cyrillic characters correctly.
- **NativeLoader extracts to a temp directory** — Both `libpdfium.so` and `libjpdfium.so`
  are extracted to the same temp dir so the `$ORIGIN` rpath resolves at runtime.
- **Single `jpdfium` module** — All Java API lives in one module. No internal
  module split (core/bindings/document) to simplify dependency management for consumers.

## Coding Standards

- **Java** — Follow existing style. No Lombok. Records for value types. Javadoc on all public API.
- **C++** — C++17. All exported functions prefixed `jpdfium_`. RAII via
  `DocWrapper`/`PageWrapper` destructors. `extern "C"` in `jpdfium.h` is mandatory.
- **Commits** — Conventional commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `build:`.
- **Tests** — Every Java API method needs a unit test (stub) and an integration test (real PDFium).

## Pull Request Checklist

- [ ] Stub implementation added for any new native function
- [ ] Unit tests pass: `./gradlew test`
- [ ] Integration tests pass: `./gradlew :jpdfium:integrationTest`
- [ ] New public API has Javadoc
- [ ] No memory leaks in C++ (check with valgrind when modifying native code)
- [ ] README updated if adding user-visible features

## Areas Looking for Help

- 🍎 macOS / Windows native builds and testing
- 📝 Text editing API (`FPDFText_*` edit functions) → `text/edit/TextEditor`
- 🔄 Page transforms (rotate, crop, merge, split) → `transform/PageOps`
- 🧪 PDF corpus — test files for edge cases (encrypted, large, CJK, RTL)
- 📦 Maven Central publishing setup
- 🤖 CI/CD (GitHub Actions for multi-platform native builds)
