package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.panama.NativeRuntime;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfDocumentMergeSplitTest {

    @Test
    void testStaticMergeMethods() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "PDF merge and split methods require real PDFium native library");
        byte[] pdf1 = SyntheticPdfFactory.createDiverse(2);
        byte[] pdf2 = SyntheticPdfFactory.createDiverse(3);

        try (PdfDocument doc1 = PdfDocument.open(pdf1);
             PdfDocument doc2 = PdfDocument.open(pdf2)) {

            // Varargs merge
            try (PdfDocument merged = PdfDocument.merge(doc1, doc2)) {
                assertNotNull(merged);
                assertEquals(5, merged.pageCount());
                byte[] mergedBytes = merged.saveBytes();
                assertNotNull(mergedBytes);
                // Verify with independent PDFBox parser
                assertEquals(5, PdfVerifier.pageCount(mergedBytes, "varargs merged doc"));
            }

            // List merge
            try (PdfDocument mergedList = PdfDocument.mergeDocuments(List.of(doc1, doc2))) {
                assertNotNull(mergedList);
                assertEquals(5, mergedList.pageCount());
                byte[] listBytes = mergedList.saveBytes();
                assertNotNull(listBytes);
                // Verify with independent PDFBox parser
                assertEquals(5, PdfVerifier.pageCount(listBytes, "list merged doc"));
            }
        }
    }

    @Test
    void testSplitAndExtractMethods() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "PDF merge and split methods require real PDFium native library");
        byte[] pdf = SyntheticPdfFactory.createDiverse(5);

        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertEquals(5, doc.pageCount());

            // Extract pages with varargs
            try (PdfDocument extracted = doc.extractPages(0, 2, 4)) {
                assertNotNull(extracted);
                assertEquals(3, extracted.pageCount());
            }

            // Extract pages with Set
            try (PdfDocument extractedSet = doc.extractPages(Set.of(1, 3))) {
                assertNotNull(extractedSet);
                assertEquals(2, extractedSet.pageCount());
            }

            // Extract contiguous range
            try (PdfDocument range = doc.extractPageRange(1, 3)) {
                assertNotNull(range);
                assertEquals(3, range.pageCount());
            }

            // Split every N pages
            List<PdfDocument> chunks = doc.splitEveryNPages(2);
            try {
                assertEquals(3, chunks.size());
                assertEquals(2, chunks.get(0).pageCount());
                assertEquals(2, chunks.get(1).pageCount());
                assertEquals(1, chunks.get(2).pageCount());
            } finally {
                for (PdfDocument chunk : chunks) {
                    chunk.close();
                }
            }
        }
    }

    @Test
    void testStubMode() throws Exception {
        assumeTrue(NativeRuntime.isStub(), "Stub mode specific verification");
        byte[] pdf = SyntheticPdfFactory.singlePageWithText("Stub test");
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            assertNotNull(doc);
        }
    }
}
