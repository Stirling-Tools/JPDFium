package stirling.software.jpdfium;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Atomic publication of a fully written staging file onto its destination.
 *
 * <p>Every file-producing operation writes a candidate to a staging file that
 * {@link stirling.software.jpdfium.model.StorageOptions#createStagingFile(Path)}
 * places beside the destination, then publishes it here. Requesting
 * {@link StandardCopyOption#ATOMIC_MOVE} makes the intent explicit and, because
 * staging and destination share a directory, the publish is a single
 * {@code rename(2)} / {@code MoveFileExW}.
 *
 * <p>If the platform cannot perform the move atomically the destination is left
 * untouched and an {@link IOException} is thrown. We never downgrade to an
 * in-place overwrite: a partially written destination is exactly the failure
 * mode the staging file exists to prevent.
 */
final class AtomicPublish {

    private AtomicPublish() {}

    /**
     * Replace {@code destination} with {@code staged} in one atomic step.
     *
     * @param staged      a fully written staging file (usually beside {@code destination})
     * @param destination the file to replace
     * @throws IOException if the atomic move fails or the platform cannot move atomically
     */
    static void replace(Path staged, Path destination) throws IOException {
        try {
            Files.move(staged, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException(
                    "Atomic publication is unavailable for " + destination
                            + "; refusing to replace it non-atomically", e);
        }
    }
}
