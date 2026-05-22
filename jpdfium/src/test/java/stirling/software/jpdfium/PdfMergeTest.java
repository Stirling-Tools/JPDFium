package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfMergeTest {

    private static Path resource(String name) throws Exception {
        URL url = PdfMergeTest.class.getResource("/pdfs/general/" + name);
        assertNotNull(url, name + " test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void mergeFilesCombinesPages() throws Exception {
        try (PdfDocument merged = PdfMerge.mergeFiles(List.of(
                resource("minimal.pdf"),
                resource("minimal.pdf"),
                resource("minimal.pdf")))) {
            assertEquals(9, merged.pageCount(), "3 inputs of 3 pages each = 9 pages");
        }
    }

    @Test
    void mergedDocumentStaysValidAfterSourcesClosed() throws Exception {
        PdfDocument merged;
        try (PdfDocument a = PdfDocument.open(resource("minimal.pdf"));
             PdfDocument b = PdfDocument.open(resource("minimal.pdf"))) {
            merged = PdfMerge.merge(List.of(a, b));
        }
        try {
            assertEquals(6, merged.pageCount());
            Path tmp = Files.createTempFile("jpdfium-merge-validity-", ".pdf");
            tmp.toFile().deleteOnExit();
            merged.save(tmp);
            assertTrue(Files.size(tmp) > 0);
        } finally {
            merged.close();
        }
    }
}
