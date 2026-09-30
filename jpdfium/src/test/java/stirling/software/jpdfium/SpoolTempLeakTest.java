package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import stirling.software.jpdfium.panama.NativeRuntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stream-open spools to a temporary file. The spool holds untrusted document
 * content, so it must be owner-only while open and gone once the document is
 * closed - on the success path and after a rejected stream alike.
 */
class SpoolTempLeakTest {

    private static byte[] pdfBytes() throws Exception {
        return new String(ConcurrencyTest.class.getResourceAsStream("/pdfs/general/minimal.pdf")
                .readAllBytes(), StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Set<String> spools() throws Exception {
        try (var s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("jpdfium-spool-"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    void spoolIsRemovedOnClose() throws Exception {
        Set<String> before = spools();
        PdfDocument doc = PdfDocument.open(new ByteArrayInputStream(pdfBytes()));
        assertTrue(doc.pageCount() > 0);
        Set<String> during = spools();
        during.removeAll(before);
        assertEquals(1, during.size(), "exactly one spool file while open: " + during);

        // Owner-only while the document holds it.
        Path spool = Path.of(System.getProperty("java.io.tmpdir"), during.iterator().next());
        try {
            var perms = Files.getPosixFilePermissions(spool);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Set.copyOf(perms), "spool must not be readable by other users");
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }

        doc.close();
        assertTrue(spools().equals(before), "spool must be deleted on close");
    }

    @Test
    void rejectedStreamLeavesNoSpool() throws Exception {
        Set<String> before = spools();
        byte[] bytes = pdfBytes();
        // limit=10 actually creates a spool file before the bound is enforced;
        // limit=0 and limit=-1 are rejected before spool creation and do not
        // exercise cleanup. Use assertThrows so a regression that stops enforcing
        // the bound does not silently pass.
        assertThrows(IllegalArgumentException.class,
                () -> PdfDocument.open(new ByteArrayInputStream(bytes), 10));
        assertTrue(spools().equals(before), "a rejected stream must leave no spool behind");
    }

    @Test
    void failedOpenLeavesNoSpool() throws Exception {
        // Needs a bridge that actually rejects bad content. The stub accepts any
        // bytes, so the open would succeed and the spool would legitimately stay.
        assumeFalse(NativeRuntime.isStub(), "requires a real bridge to reject invalid content");
        Set<String> before = spools();
        try {
            PdfDocument.open(new ByteArrayInputStream("not a pdf".getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException expected) {
            // PDFium refused the content
        }
        assertTrue(spools().equals(before), "a failed open must leave no spool behind");
    }
}
