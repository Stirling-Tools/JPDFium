package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPageEditor;
import stirling.software.jpdfium.doc.PdfPageImporter;
import stirling.software.jpdfium.panama.FfmHelper;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * SAMPLE 13 - Import pages between PDF documents.
 *
 * <p>Demonstrates importing specific pages from one PDF into another using
 * page range strings and index-based selection, plus copying viewer preferences.
 *
 * <p><strong>VM Options required:</strong>
 * {@code --enable-native-access=ALL-UNNAMED}
 */
public class S13_PageImport {

    public static void main(String[] args) throws Exception {
        SampleBase.ensureNative();
        List<Path> inputs = SampleBase.inputPdfs(args);
        List<Path> produced = new ArrayList<>();

        System.out.printf("S13_PageImport  |  %d PDF(s)%n", inputs.size());

        Path outDir = SampleBase.out("page-import");

        // 1. Self-double every PDF: append all its own pages to itself
        System.out.println("\n  -- Self-double (importPages from self) --");
        for (Path input : inputs) {
            try (PdfDocument src  = PdfDocument.open(input);
                 PdfDocument dest = PdfDocument.open(input)) {
                int before = dest.pageCount();
                boolean ok = PdfPageImporter.importPages(
                        dest.rawHandle(), src.rawHandle(), null, before);
                Path outFile = outDir.resolve(SampleBase.stem(input) + "-doubled.pdf");
                dest.save(outFile);
                produced.add(outFile);
                System.out.printf("  %s: %d → %d pages (%s)%n",
                        input.getFileName(), before, dest.pageCount(),
                        ok ? "OK" : "FAILED");
            }
        }

        // 2. Cross-document merge: import first page of second PDF into first PDF,
        //    then copy viewer preferences from the source document.
        // Note: limited to 2 simple PDFs — full merges of complex forms can crash PDFium
        if (inputs.size() >= 2) {
            System.out.println("\n  -- Cross-document import + copyViewerPreferences --");
            Path a = inputs.get(0);
            Path b = inputs.get(1);
            try (PdfDocument src  = PdfDocument.open(b);
                 PdfDocument dest = PdfDocument.open(a)) {
                boolean ok = PdfPageImporter.importPages(
                        dest.rawHandle(), src.rawHandle(), "1", dest.pageCount());
                boolean prefsCopied = PdfPageImporter.copyViewerPreferences(
                        dest.rawHandle(), src.rawHandle());
                Path outFile = outDir.resolve(
                        SampleBase.stem(a) + "+" + SampleBase.stem(b) + ".pdf");
                dest.save(outFile);
                produced.add(outFile);
                System.out.printf("  %s + p1 of %s -> %s (%d pages, import=%s, prefs=%s)%n",
                        a.getFileName(), b.getFileName(),
                        outFile.getFileName(), dest.pageCount(),
                        ok ? "OK" : "FAILED",
                        prefsCopied ? "copied" : "none");
            }
        }

        // 3. Import by index: extract only even-indexed pages from each multi-page PDF.
        //    Uses FPDF_ImportPagesByIndex into a freshly-created empty document.
        System.out.println("\n  -- Import by index (even pages of multi-page PDFs) --");
        for (Path input : inputs) {
            try (PdfDocument src = PdfDocument.open(input)) {
                int n = src.pageCount();
                if (n < 2) continue;
                int[] evenIdx = IntStream.range(0, n).filter(i -> i % 2 == 0).toArray();
                MemorySegment rawDest = PdfPageEditor.createDocument();
                try {
                    boolean ok = PdfPageImporter.importPagesByIndex(
                            rawDest, src.rawHandle(), evenIdx, 0);
                    byte[] pdfBytes = FfmHelper.saveRawDocument(rawDest);
                    Path outFile = outDir.resolve(SampleBase.stem(input) + "-even-pages.pdf");
                    Files.write(outFile, pdfBytes);
                    produced.add(outFile);
                    System.out.printf("  %s: pages[0,2,4,...] -> %d/%d pages (%s)%n",
                            input.getFileName(), evenIdx.length, n, ok ? "OK" : "FAILED");
                } finally {
                    PdfPageEditor.closeDocument(rawDest);
                }
            } catch (Exception e) {
                System.err.printf("  %s: import-by-index skipped — %s%n",
                        input.getFileName(), e.getMessage());
            }
        }

        // 4. N-up layout: tile 4 source pages onto each A4-landscape output page (2×2 grid).
        //    Uses FPDF_ImportNPagesToOne — the result is saved directly via FPDF_SaveAsCopy.
        System.out.println("\n  -- N-up layout: 2×2 four-up (A4 landscape) --");
        int nupCount = 0;
        for (Path input : inputs) {
            try (PdfDocument src = PdfDocument.open(input)) {
                if (src.pageCount() < 4) continue; // need at least 4 pages for a meaningful demo
                byte[] nupBytes = PdfPageImporter.importNPagesToOne(
                        src.rawHandle(), 842f, 595f, 2, 2);   // A4 landscape, 2 cols × 2 rows
                int nupPages = (src.pageCount() + 3) / 4;
                Path outFile = outDir.resolve(SampleBase.stem(input) + "-4up.pdf");
                Files.write(outFile, nupBytes);
                produced.add(outFile);
                System.out.printf("  %s: %d pages -> %d output pages (4-up)%n",
                        input.getFileName(), src.pageCount(), nupPages);
                nupCount++;
            } catch (Exception e) {
                System.err.printf("  %s: N-up failed — %s%n", input.getFileName(), e.getMessage());
            }
        }
        if (nupCount == 0) System.out.println("  (no PDFs with 4+ pages found for N-up demo)");

        SampleBase.done("S13_PageImport", produced.toArray(Path[]::new));
    }
}
