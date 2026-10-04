package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.SaveOptions;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
        // Stage beside the resolved target, not beside the link: a symlink can
        // point at another filesystem, and a staging file on the other side of
        // that boundary could only be published by a non-atomic copy, which is
        // exactly what this transaction exists to avoid.
        Path resolved = resolveSymlinks(abs);
        Path parent = resolved.getParent();
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
        return new OutputTransaction(resolved, staging);
    }

    /**
     * Shared staged-output validation: non-empty, within the explicit or
     * global byte budget, and reopenable when requested. Every save path that
     * hands a staged file to a caller or a destination must go through here,
     * so a validating policy cannot be silently dropped by one path while
     * another enforces it.
     */
    static void validateStaged(Path staging, SaveOptions options) throws IOException {
        long size = Files.size(staging);
        if (size <= 0) {
            throw new JPDFiumException("save produced an empty staging file for " + staging);
        }
        long cap = options == null ? 0 : options.maxOutputBytes();
        if (cap <= 0) cap = JpdfiumLib.maxSaveResultBytes();
        if (cap > 0 && size > cap) {
            throw new JPDFiumException("save output " + size
                    + " bytes exceeds limit " + cap);
        }
        if (options != null && options.verifyReopen()) {
            long probe = JpdfiumLib.docOpen(staging.toAbsolutePath().toString());
            try {
                if (JpdfiumLib.docPageCount(probe) < 0) {
                    throw new JPDFiumException(
                            "reopen validation failed for " + staging);
                }
            } finally {
                JpdfiumLib.docClose(probe);
            }
        }
    }

    /** Validate size/cap and move into place; never leaves a partial destination. */
    public void publish(SaveOptions options) throws IOException {
        if (published) return;
        validateStaged(staging, options);
        publishStaged(staging, destination);
        published = true;
    }

    /**
     * Move a finished staging file onto a caller-chosen destination, atomically.
     *
     * <p>Staging files are owner-only while they are being written, which is
     * right for a partially written PDF and wrong for the final artifact: a
     * plain rename would hand the caller an owner-only file and would silently
     * drop the permissions of the file it replaced. This therefore
     * <ul>
     *   <li>follows an existing symlink so publishing replaces the real file
     *       instead of the link;</li>
     *   <li>copies the destination's current permissions onto the staging file
     *       when one exists, or applies the same default a plain
     *       {@code Files.write} would produce (umask included) when it does not.</li>
     * </ul>
     *
     * @param staging     finished file to publish
     * @param destination caller-chosen destination path
     * @throws IOException on any failure; the staging file is left in place
     */
    public static void publishStaged(Path staging, Path destination) throws IOException {
        Path target = resolveSymlinks(destination);
        applyDestinationPermissions(staging, target);
        // Flush file content before the rename: without this a power loss can
        // leave the destination with zero or partial bytes despite a
        // successful rename, breaking the no-partial-destination contract.
        try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
        // Flush the directory entry so the rename itself survives a crash.
        // Windows cannot open directories: NTFS journals renames, so skip there.
        Path dir = target.getParent();
        if (dir != null) {
            try (FileChannel dirChannel = FileChannel.open(dir, StandardOpenOption.READ)) {
                dirChannel.force(true);
            } catch (IOException | UnsupportedOperationException ignored) {
                // Best effort durability only; the bytes are already correct.
            }
        }
    }

    /** Bounded hop count; a symlink cycle is an error, not an infinite walk. */
    private static final int MAX_SYMLINK_HOPS = 32;

    private static Path resolveSymlinks(Path destination) throws IOException {
        Path current = destination;
        for (int hops = 0; hops < MAX_SYMLINK_HOPS; hops++) {
            if (!Files.isSymbolicLink(current)) return current;
            Path parent = current.getParent();
            Path link = Files.readSymbolicLink(current);
            current = link.isAbsolute() ? link.normalize()
                    : (parent == null ? link.normalize() : parent.resolve(link).normalize());
        }
        throw new IOException("too many symbolic links resolving " + destination);
    }

    private static void applyDestinationPermissions(Path staging, Path target) {
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(staging, Files.getPosixFilePermissions(target));
                return;
            }
            // No destination yet, so mirror what a direct Files.write would
            // create: 0666 masked by the process umask. createFile applies the
            // umask (createTempFile does not - it always forces owner-only), so
            // it is the only way to learn the effective default. The probe name
            // derives from the already-unique staging name.
            Path parent = staging.getParent();
            if (parent == null) return;
            Path probe = parent.resolve(staging.getFileName() + ".perm");
            Files.createFile(probe);
            try {
                Files.setPosixFilePermissions(staging, Files.getPosixFilePermissions(probe));
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (UnsupportedOperationException _) {
            // Non-POSIX: a move keeps the file, and Windows inherits the
            // destination directory's ACL, so there is nothing to copy.
        } catch (IOException _) {
            // Permissions are best effort: the bytes are already correct and a
            // stricter mode is safer than failing the publication.
        }
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
