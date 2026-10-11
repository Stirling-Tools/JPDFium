package stirling.software.jpdfium.panama;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Publication contract: atomic replacement or a loud refusal, never a downgrade. */
class OutputTransactionPublishTest {

    private static final byte[] SENTINEL = "sentinel bytes that must survive".getBytes();

    private static Path stage(Path dir, String content) throws IOException {
        Path staged = dir.resolve("staged.pdf");
        Files.write(staged, content.getBytes(), StandardOpenOption.CREATE_NEW);
        return staged;
    }

    @Test
    void publishStagedReplacesDestinationAndRemovesStaging(@TempDir Path dir) throws IOException {
        Path destination = dir.resolve("out.pdf");
        Files.write(destination, SENTINEL);
        Path staged = stage(dir, "replacement");

        OutputTransaction.publishStaged(staged, destination);

        assertArrayEquals("replacement".getBytes(), Files.readAllBytes(destination));
        assertFalse(Files.exists(staged), "staging is consumed by the publication");
        try (Stream<Path> entries = Files.list(dir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().startsWith(".jpdfium-save-")),
                    "no staging leftovers");
        }
    }

    @Test
    void durablePublishReportsAnExplicitDurabilityOutcome(@TempDir Path dir) throws IOException {
        Path destination = dir.resolve("out.pdf");
        Files.write(destination, SENTINEL);
        Path staged = stage(dir, "durable replacement");

        OutputTransaction.Durability durability =
                OutputTransaction.publishStagedDurable(staged, destination);

        assertNotNull(durability, "the outcome is always reported, never assumed");
        assertArrayEquals("durable replacement".getBytes(), Files.readAllBytes(destination));
    }

    @Test
    void refusedAtomicMoveIsNeverDowngraded(@TempDir Path dir) throws IOException {
        Path destination = dir.resolve("out.pdf");
        Files.write(destination, SENTINEL);
        Path staged = stage(dir, "must not appear");

        AtomicInteger invocations = new AtomicInteger();
        OutputTransaction.MoveOperation refusing = (source, target) -> {
            invocations.incrementAndGet();
            throw new AtomicMoveNotSupportedException(
                    source.toString(), target.toString(), "atomic movement refused by test");
        };

        IOException failure = assertThrows(IOException.class,
                () -> OutputTransaction.moveIntoPlace(refusing, staged, destination));

        assertEquals(1, invocations.get(), "a refused atomic move must not be retried non-atomically");
        assertTrue(failure.getMessage().contains("non-atomically"), failure.getMessage());
        assertArrayEquals(SENTINEL, Files.readAllBytes(destination), "destination untouched");
        assertTrue(Files.exists(staged), "staging is left for the caller to clean up");
    }

    @Test
    void instanceAbortCleansStagingWhenPublicationIsRefused(@TempDir Path dir) throws IOException {
        // A non-empty directory is not a legal rename target, so publication
        // fails before the destination is touched.
        Path destination = dir.resolve("out.pdf");
        Files.createDirectories(destination);
        Files.write(destination.resolve("inner.txt"), SENTINEL);

        Path staging;
        try (OutputTransaction tx = OutputTransaction.begin(destination)) {
            staging = tx.staging();
            Files.write(staging, "staged".getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
            assertThrows(IOException.class, () -> tx.publish(null));
        }

        assertTrue(Files.isDirectory(destination), "destination type preserved");
        assertArrayEquals(SENTINEL, Files.readAllBytes(destination.resolve("inner.txt")));
        assertFalse(Files.exists(staging), "close() cleans the unpublished staging file");
    }

    @Test
    void isPublishableStagingAppliesTheByteBudget(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("empty.bin");
        Files.createFile(empty);
        assertFalse(OutputTransaction.isPublishableStaging(empty, 0), "empty staging is never publishable");

        Path staged = stage(dir, "1234567890");
        assertTrue(OutputTransaction.isPublishableStaging(staged, 0), "0 disables the bound");
        assertTrue(OutputTransaction.isPublishableStaging(staged, 10), "bound inclusive");
        assertFalse(OutputTransaction.isPublishableStaging(staged, 9), "over budget refused");

        assertFalse(OutputTransaction.isPublishableStaging(dir.resolve("missing.bin"), 0),
                "a missing staging file is not publishable");
    }
}
