package stirling.software.jpdfium.panama;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Commit boundary and no-clobber publication races. */
class PublishCommitTest {

    @Test
    void cancelAndPublishCompeteForOneCommit() {
        QpdfLib.PublishCommit commit = new QpdfLib.PublishCommit();
        assertTrue(commit.tryCommit(), "first claim wins");
        assertFalse(commit.tryCommit(), "second claim loses");
        assertFalse(commit.cancel(), "cancel after commit loses");
    }

    @Test
    void cancelImmediatelyBeforeCommitWins(@TempDir Path dir) throws Exception {
        Path staging = dir.resolve("s.pdf");
        Path output = dir.resolve("o.pdf");
        Files.write(staging, new byte[]{1});
        QpdfLib.PublishCommit commit = new QpdfLib.PublishCommit();
        assertTrue(commit.cancel(), "cancel before commit must win");
        assertThrows(IOException.class, () -> QpdfLib.publish(staging, output, 0, commit));
        assertFalse(Files.exists(output), "cancelled commit must not publish");
        assertTrue(Files.exists(staging), "staging stays for caller cleanup");
        Files.deleteIfExists(staging);
    }

    @Test
    void cancelAfterCommitBeginsLoses(@TempDir Path dir) throws Exception {
        Path staging = dir.resolve("s.pdf");
        Path output = dir.resolve("o.pdf");
        Files.write(staging, new byte[]{2, 3});
        QpdfLib.PublishCommit commit = new QpdfLib.PublishCommit();
        QpdfLib.publish(staging, output, 0, commit);
        assertFalse(commit.cancel(), "late cancel must lose after commit won");
        assertArrayEquals(new byte[]{2, 3}, Files.readAllBytes(output));
        Files.deleteIfExists(staging);
        Files.deleteIfExists(output);
    }

    @Test
    void concurrentCreatorWinsNoClobberRace(@TempDir Path dir) throws Exception {
        Path staging = dir.resolve("staging.pdf");
        Path output = dir.resolve("output.pdf");
        Files.write(staging, new byte[]{9, 9, 9});
        byte[] existing = {1, 2, 3, 4};
        CountDownLatch publisherReady = new CountDownLatch(1);
        CountDownLatch creatorDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> publisher = pool.submit(() -> {
                publisherReady.countDown();
                try {
                    // Pause exactly between readiness and publication so the
                    // concurrent creator wins the exclusive create first.
                    assertTrue(creatorDone.await(10, TimeUnit.SECONDS));
                    QpdfLib.publishNewFile(staging, output, 0);
                    return "published";
                } catch (Exception e) {
                    return "refused:" + e.getClass().getSimpleName();
                }
            });
            assertTrue(publisherReady.await(10, TimeUnit.SECONDS));
            Files.write(output, existing);
            creatorDone.countDown();
            Object result = publisher.get(10, TimeUnit.SECONDS);
            assertTrue(result.toString().startsWith("refused"),
                    "late publisher must lose, got: " + result);
            assertArrayEquals(existing, Files.readAllBytes(output),
                    "existing destination must remain unchanged");
        } finally {
            pool.shutdownNow();
            Files.deleteIfExists(staging);
            Files.deleteIfExists(output);
        }
    }

    @Test
    void existingDestinationFailsNoClobber(@TempDir Path dir) throws Exception {
        Path staging = dir.resolve("s.pdf");
        Path output = dir.resolve("o.pdf");
        Files.write(staging, new byte[]{5});
        Files.write(output, new byte[]{6, 7});
        assertThrows(FileAlreadyExistsException.class,
                () -> QpdfLib.publishNewFile(staging, output, 0));
        assertArrayEquals(new byte[]{6, 7}, Files.readAllBytes(output));
        assertTrue(Files.exists(staging), "losing staging stays for caller cleanup");
        Files.deleteIfExists(staging);
        Files.deleteIfExists(output);
    }

    @Test
    void publicationFailurePreservesDestination(@TempDir Path dir) throws Exception {
        Path staging = dir.resolve("missing.pdf");
        Path output = dir.resolve("o.pdf");
        byte[] before = {8, 8};
        Files.write(output, before);
        try {
            QpdfLib.publish(staging, output, 0, new QpdfLib.PublishCommit());
            fail("missing staging must fail");
        } catch (IOException expected) {
            assertArrayEquals(before, Files.readAllBytes(output),
                    "failed publication must leave destination untouched");
        } finally {
            Files.deleteIfExists(output);
        }
    }

}
