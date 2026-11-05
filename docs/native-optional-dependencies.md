# Optional native dependencies

PDFio is optional. PoDoFo is a required native dependency and is linked into `libjpdfium` whenever the bridge is built.

## PDFio

PDFio supplies an independent XRef parser used as the third repair fallback.

```bash
# Build once into a prefix; the ttf submodule is required by pdfio's font code.
git clone --recursive https://github.com/michaelrsweet/pdfio.git
cd pdfio
./configure --prefix="$PWD/../pdfio-install" --disable-shared --enable-static
(cd ttf && ./configure --disable-shared --enable-static && make -j)
make -j libpdfio.a
mkdir -p ../pdfio-install/{include,lib}
cp pdfio.h ../pdfio-install/include/
cp ttf/ttf.h ../pdfio-install/include/
cp libpdfio.a ttf/libttf.a ../pdfio-install/lib/

cmake -S native -B native/build-real \
  -DPDFIO_ROOT="$PWD/../pdfio-install" \
  -DCMAKE_BUILD_TYPE=Release
cmake --build native/build-real -j
```

Set `JPDFIUM_HAS_PDFIO=1` automatically; `RepairLib.pdfioRepair` then reports a real status instead of `unavailable`.

## PoDoFo (required)

PoDoFo supplies a font-program replacement bridge and a conservative repair path.

```bash
brew install podofo          # macOS
# Debian/Ubuntu: apt install libpodofo-dev (version must match CMake's package)
cmake -S native -B native/build-real -DCMAKE_BUILD_TYPE=Release
cmake --build native/build-real -j
```

CMake reports `PoDoFo enabled: <version>`. Without it, `PodofoLib.isAvailable()` returns false and `PdfFontEmbedder` rejects requests instead of silently leaving fonts unembedded.

`PdfFontEmbedder` replaces the bytes behind an existing `/FontFile{,2,3}` stream. It requires the caller to supply a program that matches the font dictionary (encoding, widths, CID map). It does not search for or synthesize a substitute, and it does not fix glyph mappings. That remains a preflight decision, not a qpdf/PDFio operation.
