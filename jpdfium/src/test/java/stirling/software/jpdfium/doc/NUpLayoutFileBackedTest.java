package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.PageImportBindings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class NUpLayoutFileBackedTest {

    @BeforeEach
    void requireNativeStreamingBindings() {
        assumeTrue(
                PageImportBindings.FPDF_ImportNPagesToOne != null
                        && DocBindings.FPDF_SaveAsCopy != null
                        && DocBindings.FPDF_CloseDocument != null,
                "native streaming bindings unavailable");
    }

    private static PdfDocument openMinimal() throws Exception {
        URL url = NUpLayoutFileBackedTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return PdfDocument.open(Path.of(url.toURI()));
    }

    private static NUpLayout minimalLayout(PdfDocument doc) {
        return NUpLayout.from(doc).grid(2, 2).a4Landscape().build();
    }

    private static OutputStream failingSink() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("sink full");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("sink full");
            }
        };
    }

    @Test
    void savePathProducesValidNUp(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("nup.pdf");
        try (PdfDocument doc = openMinimal()) {
            minimalLayout(doc).save(out);
        }
        assertTrue(Files.size(out) > 0);
        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(1, reopened.pageCount());
        }
    }

    @Test
    void saveToStreamMatchesToBytes() throws Exception {
        byte[] direct;
        ByteArrayOutputStream streamed = new ByteArrayOutputStream();
        try (PdfDocument doc = openMinimal()) {
            NUpLayout layout = minimalLayout(doc);
            direct = layout.toBytes();
            layout.saveTo(streamed);
        }
        assertTrue(direct.length > 0);
        // Same page count through both sinks (trailer IDs may differ byte-wise).
        try (PdfDocument a = PdfDocument.open(direct);
             PdfDocument b = PdfDocument.open(streamed.toByteArray())) {
            assertEquals(a.pageCount(), b.pageCount());
        }
    }

    @Test
    void sinkFailurePropagatesAsIoException() throws Exception {
        try (PdfDocument doc = openMinimal()) {
            NUpLayout layout = minimalLayout(doc);
            assertThrows(IOException.class, () -> layout.saveTo(failingSink()));
        }
    }

    @Test
    void failedSaveLeavesExistingDestinationUntouched(@TempDir Path tmp) throws Exception {
        // Destination is a non-empty directory: publishing the staged file must
        // fail, and the transaction must leave the destination as it was.
        Path out = tmp.resolve("dest.pdf");
        Files.createDirectory(out);
        Path sentinel = out.resolve("keep.txt");
        byte[] original = "ORIGINAL-CONTENT".getBytes(StandardCharsets.US_ASCII);
        Files.write(sentinel, original);

        try (PdfDocument doc = openMinimal()) {
            NUpLayout layout = minimalLayout(doc);
            assertThrows(Exception.class, () -> layout.save(out));
        }

        assertTrue(Files.isDirectory(out), "destination directory must remain");
        assertArrayEquals(original, Files.readAllBytes(sentinel),
                "existing destination contents must be unchanged after a failed save");
        // The failed publication must not leave the staging file behind beside
        // the destination; only the destination itself may remain.
        try (var siblings = Files.list(tmp)) {
            assertEquals(1, siblings.count(), "staging file must be cleaned up");
        }
    }
}
