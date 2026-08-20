package stirling.software.jpdfium;

import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.model.Rect;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Native Image Sample Application Runner and Verification Suite.
 * Executed as a standalone GraalVM Native Image binary in CI to verify that
 * all major PDFium operations (rendering, text extraction, redaction, flattening,
 * page geometry, merging, splitting, repair, and document serialization)
 * execute natively and produce valid, verified PDF outputs.
 */
public class GraalVmSmokeApp {

    private GraalVmSmokeApp() {}

    public static void main(String[] args) throws Exception {
        System.out.println("Starting GraalVM Native Image JPDFium Major Samples and Verifications");
        System.out.println("Platform detected: " + NativeLoader.detectPlatform());

        NativeLoader.ensureLoaded();
        System.out.println("NativeLoader loaded native bridge successfully.");

        byte[] minimalBytes = readResourceBytes("pdfs/general/minimal.pdf");
        byte[] basicTextBytes = readResourceBytes("pdfs/general/basic-text.pdf");
        byte[] mozillaBytes = readResourceBytes("pdfs/general/mozilla_tracemonkey.pdf");
        byte[] damagedBytes = readResourceBytes("pdfs/repair/pdfjs-bug1020858.pdf");

        // 1. Sample 01: Document Open and Page Count Verification
        try (PdfDocument doc = PdfDocument.open(mozillaBytes)) {
            int count = doc.pageCount();
            System.out.println("Sample 01 - Doc Open and Page Count: mozilla_tracemonkey.pdf has " + count + " pages");
            if (count != 3) {
                throw new AssertionError("Page count verification failed: expected 3, got " + count);
            }
        }

        // 2. Sample 02: Page Geometry and Dimensions Verification
        try (PdfDocument doc = PdfDocument.open(basicTextBytes);
             PdfPage page = doc.page(0)) {
            PageSize size = page.size();
            System.out.println("Sample 02 - Page Geometry: " + size.width() + "x" + size.height() + " pt");
            if (size.width() <= 0 || size.height() <= 0) {
                throw new AssertionError("Page size verification failed: invalid dimensions " + size);
            }
        }

        // 3. Sample 03: Page Rendering to Bitmap Verification
        try (PdfDocument doc = PdfDocument.open(basicTextBytes);
             PdfPage page = doc.page(0)) {
            RenderResult result = page.renderAt(72);
            System.out.println("Sample 03 - Page Rendering: rendered " + result.width() + "x" + result.height() + " image");
            if (result.width() <= 0 || result.height() <= 0 || result.rgba() == null || result.rgba().length == 0) {
                throw new AssertionError("Render verification failed: invalid render result");
            }
        }

        // 4. Sample 04: Text Extraction Verification
        try (PdfDocument doc = PdfDocument.open(basicTextBytes);
             PdfPage page = doc.page(0)) {
            String textJson = page.extractTextJson();
            System.out.println("Sample 04 - Text Extraction: JSON length " + (textJson != null ? textJson.length() : 0));
            if (textJson == null || textJson.isEmpty()) {
                throw new AssertionError("Text extraction verification failed: empty JSON text output");
            }
        }

        // 5. Sample 05: PDF Merging Verification
        byte[] mergedPdfBytes;
        try (PdfDocument doc1 = PdfDocument.open(minimalBytes);
             PdfDocument doc2 = PdfDocument.open(basicTextBytes);
             PdfDocument mergedDoc = PdfMerge.merge(List.of(doc1, doc2))) {
            mergedPdfBytes = mergedDoc.saveBytes();
        }

        // VERIFY MERGED PDF
        try (PdfDocument verifiedMerged = PdfDocument.open(mergedPdfBytes)) {
            int mergedPages = verifiedMerged.pageCount();
            System.out.println("Sample 05 - PDF Merging: merged PDF has " + mergedPages + " pages");
            if (mergedPages < 2) {
                throw new AssertionError("Merge verification failed: expected at least 2 pages, got " + mergedPages);
            }
        }

        // 6. Sample 06: Text and Region Redaction Verification
        byte[] redactedPdfBytes;
        try (PdfDocument doc = PdfDocument.open(basicTextBytes);
             PdfPage page = doc.page(0)) {
            page.redactRegion(new Rect(10.0f, 10.0f, 200.0f, 100.0f), 0xFFFF0000, true);
            redactedPdfBytes = doc.saveBytes();
        }

        // VERIFY REDACTED PDF
        try (PdfDocument verifiedRedacted = PdfDocument.open(redactedPdfBytes)) {
            System.out.println("Sample 06 - Text Redaction: verified redacted output PDF opens cleanly, page count " + verifiedRedacted.pageCount());
            if (verifiedRedacted.pageCount() < 1) {
                throw new AssertionError("Redaction verification failed: invalid page count after redaction");
            }
        }

        // 7. Sample 07: Page Flattening Verification
        byte[] flattenedPdfBytes;
        try (PdfDocument doc = PdfDocument.open(basicTextBytes);
             PdfPage page = doc.page(0)) {
            page.flatten();
            flattenedPdfBytes = doc.saveBytes();
        }

        // VERIFY FLATTENED PDF
        try (PdfDocument verifiedFlattened = PdfDocument.open(flattenedPdfBytes)) {
            System.out.println("Sample 07 - Page Flattening: verified flattened output PDF opens cleanly, page count " + verifiedFlattened.pageCount());
            if (verifiedFlattened.pageCount() < 1) {
                throw new AssertionError("Flattening verification failed: invalid page count after flattening");
            }
        }

        // 8. Sample 08: PDF Repair Verification
        try (PdfDocument repairedDoc = PdfDocument.open(damagedBytes)) {
            int repairedPages = repairedDoc.pageCount();
            System.out.println("Sample 08 - PDF Repair: damaged corpus PDF opened and verified, page count " + repairedPages);
            if (repairedPages < 1) {
                throw new AssertionError("Repair verification failed: expected at least 1 page in repaired document");
            }
        }

        System.out.println("SUCCESS: All major samples and PDF output verifications passed natively under GraalVM Native Image!");
    }

    private static byte[] readResourceBytes(String resourcePath) throws Exception {
        InputStream is = GraalVmSmokeApp.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            return createSamplePdfBytes();
        }
        try (is) {
            return is.readAllBytes();
        }
    }

    private static byte[] createSamplePdfBytes() {
        String pdf = """
                %PDF-1.4
                1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
                2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
                3 0 obj << /Type /Parent 2 0 R /Type /Page /MediaBox [0 0 612 792] >> endobj
                xref
                0 4
                0000000000 65535 f\s
                0000000009 00000 n\s
                0000000058 00000 n\s
                00000000115 00000 n\s
                trailer << /Size 4 /Root 1 0 R >>
                startxref
                197
                %%EOF
                """;
        return pdf.getBytes(StandardCharsets.ISO_8859_1);
    }
}
