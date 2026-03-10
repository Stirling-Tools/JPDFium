package stirling.software.jpdfium.samples;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.doc.PdfPageImporter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

        Path outDir = SampleBase.out("S13_page-import");

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
                System.out.printf("  %s: %d to %d pages (%s)%n",
                        input.getFileName(), before, dest.pageCount(),
                        ok ? "OK" : "FAILED");
            }
        }

        // 2. Cross-document merge: import first page of second PDF into first PDF
        if (inputs.size() >= 2) {
            System.out.println("\n  -- Cross-document import (page 1 of second into first) --");
            Path a = inputs.get(0);
            Path b = inputs.get(1);
            try (PdfDocument src  = PdfDocument.open(b);
                 PdfDocument dest = PdfDocument.open(a)) {
                boolean ok = PdfPageImporter.importPages(
                        dest.rawHandle(), src.rawHandle(), "1", dest.pageCount());
                Path outFile = outDir.resolve(
                        SampleBase.stem(a) + "+" + SampleBase.stem(b) + ".pdf");
                dest.save(outFile);
                produced.add(outFile);
                System.out.printf("  %s + p1 of %s -> %s (%d pages, %s)%n",
                        a.getFileName(), b.getFileName(),
                        outFile.getFileName(), dest.pageCount(),
                        ok ? "OK" : "FAILED");
            }
        }

        SampleBase.done("S13_PageImport", produced.toArray(Path[]::new));
    }
}
