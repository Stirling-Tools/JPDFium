package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.SaveOptions;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Transactional file publication for document saves.
 *
 * <p>State machine: {@code CREATED -> WRITING -> FINALIZED -> (VALIDATED) ->
 * PUBLISHED}; any failure before {@code PUBLISHED} aborts and cleans the
 * staging file, leaving the destination untouched. Staging files live beside
 * the destination when possible so the final move is a rename.
 */
public final class OutputTransaction implements AutoCloseable {

    private final Path destination;
    private final Path staging;
    private boolean published;

    private OutputTransaction(Path destination, Path staging) {
        this.destination = destination;
        this.staging = staging;
    }

    public Path staging() {
        return staging;
    }

    public Path destination() {
        return destination;
    }

    public static OutputTransaction begin(Path destination) throws IOException {
        if (destination == null) throw new IllegalArgumentException("destination must not be null");
        Path abs = destination.toAbsolutePath();
        Path parent = abs.getParent();
        Path staging;
        if (parent != null) {
            Files.createDirectories(parent);
            try {
                staging = Files.createTempFile(parent, ".jpdfium-save-",
                        ".pdf", PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                staging = Files.createTempFile(parent, ".jpdfium-save-", ".pdf");
            }
        } else {
            try {
                staging = Files.createTempFile("jpdfium-save-", ".pdf",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                staging = Files.createTempFile("jpdfium-save-", ".pdf");
            }
        }
        return new OutputTransaction(abs, staging);
    }

    /** Validate size/cap and move into place; never leaves a partial destination. */
    public void publish(SaveOptions options) throws IOException {
        if (published) return;
        long size = Files.size(staging);
        if (size <= 0) {
            throw new JPDFiumException("save produced an empty staging file for " + destination);
        }
        long cap = options == null ? 0 : options.maxOutputBytes();
        if (cap <= 0) cap = JpdfiumLib.maxSaveResultBytes();
        if (cap > 0 && size > cap) {
            throw new JPDFiumException("save output " + size
                    + " bytes exceeds limit " + cap);
        }
        try {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        published = true;
    }

    public void abort() {
        deleteQuietly(staging);
    }

    @Override
    public void close() {
        if (!published) abort();
    }

    public static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
        }
    }
}
