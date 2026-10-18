package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.SaveOptions;
import stirling.software.jpdfium.panama.BridgeAlloc;
import stirling.software.jpdfium.panama.OutputTransaction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.abort;
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
        byte[] sentinel = "%PDF-1.4 sentinel\n".getBytes(StandardCharsets.US_ASCII);
        Files.write(dest, sentinel);
        try {
            try (PdfDocument doc = PdfDocument.open(pdfBytes())) {
                assertThrows(JPDFiumException.class,
                        () -> doc.saveTo(dest, SaveOptions.maxOutputBytes(10)));
            }
            assertArrayEquals(sentinel, Files.readAllBytes(dest),
                    "failed save must leave destination bytes unchanged");
        } finally {
            Files.deleteIfExists(dest);
        }
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
        // Byte-identical: both paths serialize the same untouched document, so
        // equal length alone would let a corrupt payload of the same size pass.
        assertArrayEquals(expected, viaChannel,
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
        // The SAVE_OUTPUT accounting below cannot observe the temp file: the
        // channel path never allocates that tag, so it passes even with the
        // cleanup deleted. Compare the temp directory instead.
        Path tmpdir = Path.of(System.getProperty("java.io.tmpdir"));
        Set<String> before = stagingNames(tmpdir);
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
        Set<String> leaked = stagingNames(tmpdir);
        leaked.removeAll(before);
        assertTrue(leaked.isEmpty(), "failed channel save left staging files: " + leaked);
    }

    private static Set<String> stagingNames(Path tmpdir) throws IOException {
        try (var paths = Files.list(tmpdir)) {
            return paths.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("jpdfium-save-") && n.endsWith(".pdf"))
                    .collect(Collectors.toSet());
        }
    }

    private static String posixMode(Path p) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
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
                abort("no symlink support on this host: " + e);
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

    /**
     * Concurrent saves to one destination must not share a staging file, and a
     * save must never leave staging debris next to the destination.
     *
     * <p>The workers rendezvous on a barrier first: without it the saves can
     * serialise and the shared-staging bug would go unexercised.
     */
    @Test
    void concurrentSavesShareNoStagingFile(@TempDir Path dir) throws Exception {
        Path dest = dir.resolve("out.pdf");
        int threads = 8;
        CyclicBarrier startLine = new CyclicBarrier(threads);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int i = 0; i < 2 * threads; i++) {
                jobs.add(() -> {
                    startLine.await();
                    int now = inFlight.incrementAndGet();
                    peakInFlight.accumulateAndGet(now, Math::max);
                    try {
                        try (PdfDocument d = PdfDocument.open(pdfBytes())) {
                            d.saveTo(dest);
                        }
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(jobs)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(peakInFlight.get() >= 2,
                "saves must genuinely overlap for this test to mean anything");
        // A shared staging file would publish another save's fragment.
        try (PdfDocument d = PdfDocument.open(dest)) {
            assertTrue(d.pageCount() > 0, "published file must be a complete PDF");
        }
        try (var files = Files.list(dir)) {
            List<String> strays =
                    files.map(p -> p.getFileName().toString()).filter(n -> n.contains("jpdfium-save")).toList();
            assertTrue(strays.isEmpty(), "staging leftovers: " + strays);
        }
    }

    @Test
    void stagingIsCreatedBesideTheResolvedTarget() throws Exception {
        Path linkDir = Files.createTempDirectory("stage-link");
        Path realDir = Files.createTempDirectory("stage-real");
        Path link = linkDir.resolve("alias.pdf");
        Path real = realDir.resolve("target.pdf");
        try {
            try {
                Files.createSymbolicLink(link, real);
            } catch (UnsupportedOperationException | IOException e) {
                abort("no symlink support on this host: " + e);
                return;
            }
            Path realDirResolved = realDir.toRealPath();
            try (OutputTransaction tx = OutputTransaction.begin(link)) {
                assertEquals(realDirResolved, tx.staging().getParent().toRealPath(),
                        "staging must be created in the resolved target's directory");
                assertEquals(realDirResolved, tx.destination().getParent().toRealPath(),
                        "the transaction must target the resolved path, not the link");
                Files.write(tx.staging(), new byte[]{1, 2, 3});
                tx.publish(null);
            }
            assertEquals(3, Files.size(real), "the resolved target must receive the output");
            assertTrue(Files.isSymbolicLink(link), "the link must survive publication");
            try (Stream<Path> stray = Files.list(realDir)) {
                assertEquals(0,
                        stray.filter(p -> p.getFileName().toString().contains("jpdfium-save")).count(),
                        "successful publish must leave no staging file");
            }
        } finally {
            for (Path p : Files.newDirectoryStream(linkDir)) Files.deleteIfExists(p);
            for (Path p : Files.newDirectoryStream(realDir)) Files.deleteIfExists(p);
            Files.deleteIfExists(linkDir);
            Files.deleteIfExists(realDir);
        }
    }
}
