package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import stirling.software.jpdfium.model.SaveOptions;
import stirling.software.jpdfium.panama.BridgeAlloc;
import stirling.software.jpdfium.panama.OutputTransaction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transactional file saves and bounded-memory channel spooling.
 */
class SaveTransportTest {

    private static byte[] pdfBytes() throws IOException {
        return Objects.requireNonNull(SaveTransportTest.class.getResourceAsStream(
                "/pdfs/general/minimal.pdf")).readAllBytes();
    }

    @Test
    void saveToPathRoundTrips() throws Exception {
        Path dest = Files.createTempFile("jpdfium-save-", ".pdf");
        Files.deleteIfExists(dest);
        try {
            try (PdfDocument doc = PdfDocument.open(pdfBytes())) {
                doc.saveTo(dest);
            }
            assertTrue(Files.size(dest) > 0, "staged save must publish a non-empty file");
            try (PdfDocument reopened = PdfDocument.open(dest)) {
                assertEquals(3, reopened.pageCount());
            }
        } finally {
            Files.deleteIfExists(dest);
        }
    }

    @Test
    void failedSaveLeavesDestinationUntouched() throws Exception {
        Path dest = Files.createTempFile("jpdfium-save-keep-", ".pdf");
        byte[] sentinel = "%PDF-1.4 sentinel\n".getBytes();
        Files.write(dest, sentinel);
        try (PdfDocument doc = PdfDocument.open(pdfBytes())) {
            assertThrows(Exception.class,
                    () -> doc.saveTo(dest, SaveOptions.maxOutputBytes(10)));
        }
        assertTrue(Files.exists(dest), "failed save must not delete the destination");
    }

    @Test
    void saveToChannelMatchesSaveBytes() throws Exception {
        byte[] expected;
        try (PdfDocument doc = PdfDocument.open(pdfBytes())) {
            expected = doc.saveBytes();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PdfDocument doc = PdfDocument.open(pdfBytes())) {
            doc.saveTo(baos);
        }
        byte[] viaChannel = baos.toByteArray();
        assertTrue(viaChannel.length > 0);
        assertEquals(expected.length, viaChannel.length,
                "channel spool must carry the same bytes as saveBytes for minimal.pdf");
    }

    @Test
    void saveToTempFileIsOwnedByCaller() throws Exception {
        Path tmp;
        try (PdfDocument doc = PdfDocument.open(pdfBytes())) {
            tmp = doc.saveToTempFile();
        }
        try {
            assertTrue(Files.size(tmp) > 0);
            try (PdfDocument reopened = PdfDocument.open(tmp)) {
                assertEquals(3, reopened.pageCount());
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @Timeout(60)
    void throwingChannelStillCleansTemp() throws Exception {
        long liveBefore = BridgeAlloc.liveBytes(BridgeAlloc.Tag.SAVE_OUTPUT);
        WritableByteChannel failing = new WritableByteChannel() {
            private boolean open = true;

            @Override
            public int write(ByteBuffer src) throws IOException {
                throw new IOException("boom");
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() {
                open = false;
            }
        };
        try (PdfDocument doc = PdfDocument.open(pdfBytes())) {
            assertThrows(IOException.class, () -> doc.saveTo(failing));
        }
        assertEquals(liveBefore, BridgeAlloc.liveBytes(BridgeAlloc.Tag.SAVE_OUTPUT),
                "channel spool must not leak SAVE_OUTPUT accounting");
    }

    private static String posixMode(Path p) throws IOException {
        return java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(p));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void newDestinationGetsTheSameModeAsADirectWrite() throws Exception {
        Path dir = Files.createTempDirectory("pub-perm");
        try {
            // Reference: what a plain Files.write produces with this umask.
            Path reference = dir.resolve("reference.bin");
            Files.write(reference, new byte[]{1, 2, 3});

            Path dest = dir.resolve("published.pdf");
            try (OutputTransaction tx = OutputTransaction.begin(dest)) {
                Files.write(tx.staging(), new byte[]{1, 2, 3, 4});
                tx.publish(null);
            }
            assertEquals(posixMode(reference), posixMode(dest),
                    "a new destination must get the same mode a direct write would produce");
        } finally {
            for (Path p : Files.newDirectoryStream(dir)) Files.deleteIfExists(p);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void existingDestinationKeepsItsMode() throws Exception {
        Path dir = Files.createTempDirectory("pub-perm2");
        try {
            Path dest = dir.resolve("existing.pdf");
            Files.createFile(dest);
            Files.setPosixFilePermissions(dest,
                    PosixFilePermissions.fromString("rw-rw-rw-"));
            assertEquals("rw-rw-rw-", posixMode(dest));

            try (OutputTransaction tx = OutputTransaction.begin(dest)) {
                Files.write(tx.staging(), new byte[]{9, 9, 9});
                tx.publish(null);
            }
            assertEquals("rw-rw-rw-", posixMode(dest),
                    "replacing a file must not silently change its permissions");
        } finally {
            for (Path p : Files.newDirectoryStream(dir)) Files.deleteIfExists(p);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void symlinkedDestinationIsFollowedNotReplaced() throws Exception {
        Path dir = Files.createTempDirectory("pub-perm3");
        Path real = dir.resolve("real.pdf");
        Path link = dir.resolve("link.pdf");
        try {
            Files.write(real, new byte[]{7, 7});
            try {
                Files.createSymbolicLink(link, real);
            } catch (UnsupportedOperationException | IOException e) {
                return; // no symlink support here
            }
            try (OutputTransaction tx = OutputTransaction.begin(link)) {
                Files.write(tx.staging(), new byte[]{8, 8, 8, 8});
                tx.publish(null);
            }
            assertTrue(Files.isSymbolicLink(link), "the link must survive publication");
            assertEquals(4, Files.size(real), "the symlink target must receive the output");
        } finally {
            if (Files.isSymbolicLink(link)) Files.delete(link);
            for (Path p : Files.newDirectoryStream(dir)) Files.deleteIfExists(p);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveOptionsValidation() {
        assertThrows(IllegalArgumentException.class, () -> SaveOptions.builder().maxOutputBytes(-1));
        assertEquals(0, SaveOptions.fast().maxOutputBytes());
        assertTrue(SaveOptions.validated().verifyReopen());
        assertEquals(1234, SaveOptions.maxOutputBytes(1234).maxOutputBytes());
    }
}
