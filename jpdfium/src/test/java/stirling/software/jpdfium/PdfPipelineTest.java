package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfPipelineTest {

    private static Path minimalPdf() throws Exception {
        URL url = PdfPipelineTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    @Test
    void streamingFlushKeepsDocumentValid() throws Exception {
        // flushInterval=1 forces flushViaTempFile on every page; doc must survive.
        ProcessingMode streaming = ProcessingMode.builder().streaming(true).flushInterval(1).build();
        int[] visited = new int[1];
        try (PdfDocument out = PdfPipeline.process(minimalPdf(), streaming, (doc, i) -> visited[0]++)) {
            assertEquals(3, out.pageCount());
            assertEquals(3, visited[0]);
        }
    }

    @Test
    void streamingParallelFlushKeepsDocumentValid() throws Exception {
        ProcessingMode mode = ProcessingMode.builder()
                .streaming(true).parallel(2).flushInterval(1).build();
        try (PdfDocument out = PdfPipeline.process(minimalPdf(), mode, (doc, i) -> {})) {
            assertEquals(3, out.pageCount());
        }
    }
}
