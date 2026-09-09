package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PdfDocumentMergeSplitTest {

    @Test
    void testAppendAndInsertDocument() throws Exception {
        byte[] pdf1 = SyntheticPdfFactory.createDiverse(2);
        byte[] pdf2 = SyntheticPdfFactory.createDiverse(3);

        try (PdfDocument doc1 = PdfDocument.open(pdf1);
             PdfDocument doc2 = PdfDocument.open(pdf2)) {
            assertEquals(2, doc1.pageCount());
            assertEquals(3, doc2.pageCount());

            // Append doc2 to doc1
            doc1.appendDocument(doc2);
            assertEquals(5, doc1.pageCount());

            // Insert another doc at index 1
            try (PdfDocument doc3 = PdfDocument.open(pdf2)) {
                doc1.insertDocument(1, doc3);
                assertEquals(8, doc1.pageCount());
            }
        }
    }

    @Test
    void testStaticMergeMethods() throws Exception {
        byte[] pdf1 = SyntheticPdfFactory.createDiverse(2);
        byte[] pdf2 = SyntheticPdfFactory.createDiverse(3);

        try (PdfDocument doc1 = PdfDocument.open(pdf1);
             PdfDocument doc2 = PdfDocument.open(pdf2)) {

            // Varargs merge
            try (PdfDocument merged = PdfDocument.merge(doc1, doc2)) {
                assertNotNull(merged);
                assertEquals(5, merged.pageCount());
            }

            // List merge
            try (PdfDocument mergedList = PdfDocument.mergeDocuments(List.of(doc1, doc2))) {
                assertNotNull(mergedList);
                assertEquals(5, mergedList.pageCount());
            }
        }
    }

    @Test
    void testSplitAndExtractMethods() throws Exception {
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
}
