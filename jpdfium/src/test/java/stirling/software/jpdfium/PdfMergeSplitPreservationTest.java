package stirling.software.jpdfium;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Merge/split must treat forms, bookmarks and annotations the same on the
 * file-backed and bytes paths. Known limitation, asserted as parity: QPDF
 * page merge carries widget annotations but not the catalog-level AcroForm
 * field tree, identically on both paths.
 */
class PdfMergeSplitPreservationTest {

    private static Path fixture(String name) {
        return Path.of("src/test/resources/pdfs/general", name);
    }

    private static int formFieldCount(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
            return form == null ? 0 : form.getFields().size();
        }
    }

    private static int annotCount(byte[] pdf) throws Exception {
        int total = 0;
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (PdfPage page = doc.page(i)) {
                    total += page.annotations().size();
                }
            }
        }
        return total;
    }

    @Test
    void mergeKeepsAnnotsAndMatchesFormsOnBothPaths(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        Path forms = fixture("all_form_fields.pdf");
        Path text = tmp.resolve("text.pdf");
        Files.write(text, SyntheticPdfFactory.createDiverse(2));

        Path fileOut = tmp.resolve("merged-file.pdf");
        PdfMerge.mergeFilesToFile(List.of(forms, text), fileOut);
        byte[] fileBytes = Files.readAllBytes(fileOut);

        byte[] bytesPath;
        try (PdfDocument merged = PdfMerge.mergeFiles(List.of(forms, text))) {
            bytesPath = merged.saveBytes();
        }

        int annots = annotCount(bytesPath);
        assertTrue(annots > 0, "merged output must carry annotations");
        assertEquals(annots, annotCount(fileBytes),
                "annotation count must agree between merge paths");
        assertEquals(formFieldCount(bytesPath), formFieldCount(fileBytes),
                "form field count must agree between merge paths");
    }

    @Test
    void splitKeepsAnnotsAndMatchesFormsOnBothPaths(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isExtractFileSupported(), "needs file-backed qpdf extract symbol");
        Path forms = fixture("all_form_fields.pdf");

        Path fileOut = tmp.resolve("part-file.pdf");
        PdfSplit.extractPageRangeToFile(forms, 0, 0, fileOut);
        byte[] fileBytes = Files.readAllBytes(fileOut);

        byte[] bytesPath;
        try (PdfDocument doc = PdfDocument.open(forms);
             PdfDocument part = PdfSplit.extractPageRange(doc, 0, 0)) {
            bytesPath = part.saveBytes();
        }

        int annots = annotCount(bytesPath);
        assertTrue(annots > 0, "split output must carry annotations");
        assertEquals(annots, annotCount(fileBytes),
                "annotation count must agree between split paths");
        assertEquals(formFieldCount(bytesPath), formFieldCount(fileBytes),
                "form field count must agree between split paths");
    }

    @Test
    void mergeKeepsBookmarksOnFileBackedDocs(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "needs real PDFium native library");
        assumeTrue(QpdfLib.isMergeFilesSupported(), "needs file-backed qpdf merge symbol");
        Path a = tmp.resolve("a.pdf");
        Path b = tmp.resolve("b.pdf");
        Files.write(a, SyntheticPdfFactory.createDiverse(2));
        Files.write(b, SyntheticPdfFactory.createDiverse(2));

        int fileBookmarks;
        try (PdfDocument doc1 = PdfDocument.open(a);
             PdfDocument doc2 = PdfDocument.open(b);
             PdfDocument merged = PdfMerge.merge(List.of(doc1, doc2))) {
            fileBookmarks = merged.bookmarks().size();
        }

        byte[] bytesPath;
        try (PdfDocument merged = PdfMerge.mergeFiles(List.of(a, b))) {
            bytesPath = merged.saveBytes();
        }
        int bytesBookmarks;
        try (PdfDocument check = PdfDocument.open(bytesPath)) {
            bytesBookmarks = check.bookmarks().size();
        }
        assertEquals(bytesBookmarks, fileBookmarks, "bookmark count must agree between merge paths");
    }
}
