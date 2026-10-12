package stirling.software.jpdfium.panama;

import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.SaveOptions;
import stirling.software.jpdfium.model.StorageOptions;

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
    private final boolean durable;
    private boolean published;

    private OutputTransaction(Path destination, Path staging, boolean durable) {
        this.destination = destination;
        this.staging = staging;
        this.durable = durable;
    }

    public Path staging() {
        return staging;
    }

    public Path destination() {
        return destination;
    }

    public static OutputTransaction begin(Path destination) throws IOException {
        return begin(destination, true);
    }

    /**
     * Create a staging file beside the resolved {@code destination} so its publication is a
     * rename, whatever the destination path itself points at.
     *
     * <p>An output that is a symlink is resolved first: staging beside the link could land on a
     * different filesystem from the real target, and {@link #publishStagedDurable} would then be
     * unable to move it atomically.
     */
    public static Path stagingFile(StorageOptions options, Path destination) throws IOException {
        return options.createStagingFile(resolveSymlinks(destination.toAbsolutePath()));
    }

    /**
     * Open a transaction for a destination whose durability is optional: {@code durable=false} is
     * for internal staging files, where the page cache already serves the bytes.
     */
    public static OutputTransaction begin(Path destination, boolean durable) throws IOException {
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
        return new OutputTransaction(resolved, staging, durable);
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
        // destination was already symlink-resolved in begin(); resolve it once
        // there, not again on every publish.
        publishResolved(staging, destination, durable);
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
        publishStaged(staging, destination, true);
    }

    /**
     * Fast path for internal staging files: an atomic rename with no permission mirroring and no
     * fsync, since the same process reopens and deletes the file.
     */
    public static void publishStaged(Path staging, Path destination, boolean durable)
            throws IOException {
        publishResolved(staging, resolveSymlinks(destination), durable);
    }

    /**
     * Outcome of a durable publication. A publication that did not happen is
     * never represented here: it throws instead.
     *
     * <ul>
     *   <li>{@link #CONFIRMED} — the destination was replaced and its directory
     *       entry was flushed, so the replacement survives a crash.</li>
     *   <li>{@link #UNCONFIRMED} — the destination was replaced but the
     *       directory entry could not be synchronised (the platform cannot open
     *       a directory as a channel, or the flush failed). The new bytes are
     *       visible; their crash durability is not guaranteed. The rename is
     *       never undone to "fix" this.</li>
     * </ul>
     */
    public enum Durability {
        CONFIRMED,
        UNCONFIRMED
    }

    /**
     * Durable publication that reports whether the directory entry was
     * synchronised, rather than leaving the caller to assume it was.
     *
     * <p>Mirrors the native contract ({@code JPDF_E_UNSYNCED}): the destination
     * always holds the new bytes on return, and only the crash-durability of
     * the replacement is in question.
     *
     * @param staging     finished file to publish
     * @param destination caller-chosen destination path
     * @return whether the directory entry flush was confirmed
     * @throws IOException if the publication itself failed; the destination is
     *                     left untouched
     */
    public static Durability publishStagedDurable(Path staging, Path destination) throws IOException {
        return publishDurableResolved(staging, resolveSymlinks(destination));
    }

    /**
     * Validate a staging file that a caller filled itself, applying the same
     * size/budget gate the instance {@link #publish} applies. The static
     * {@link #publishStaged} methods do not validate, so callers that hand
     * their own staging file to them must run this first.
     *
     * @param staging  finished staging file
     * @param maxBytes upper bound in bytes; {@code 0} disables the bound
     * @return true when the staging file is non-empty and within {@code maxBytes}
     */
    public static boolean isPublishableStaging(Path staging, long maxBytes) {
        try {
            long size = Files.size(staging);
            return size > 0 && (maxBytes <= 0 || size <= maxBytes);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Publish when {@code target} is already symlink-resolved. The instance path
     * resolves the destination once in {@code begin()}; re-walking symlinks here
     * would repeat up to {@link #MAX_SYMLINK_HOPS} stat/readlink calls per publish
     * for no benefit.
     */
    private static void publishResolved(Path staging, Path target, boolean durable)
            throws IOException {
        if (durable) {
            publishDurableResolved(staging, target);
            return;
        }
        moveIntoPlace(staging, target);
    }

    private static Durability publishDurableResolved(Path staging, Path target) throws IOException {
        applyDestinationPermissions(staging, target);
        // Flush file content before the rename: without this a power loss can
        // leave the destination with zero or partial bytes despite a
        // successful rename, breaking the no-partial-destination contract.
        try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        moveIntoPlace(staging, target);
        // Flush the directory entry so the rename itself survives a crash. The
        // destination already holds the new bytes here, so a failure is
        // reported as UNCONFIRMED rather than rolled back.
        Path dir = target.getParent();
        if (dir == null) {
            return Durability.UNCONFIRMED;
        }
        FileChannel dirChannel;
        try {
            dirChannel = FileChannel.open(dir, StandardOpenOption.READ);
        } catch (IOException | UnsupportedOperationException e) {
            // The platform cannot open a directory as a channel (for example
            // Windows): the rename's durability cannot be established here.
            return Durability.UNCONFIRMED;
        }
        try (dirChannel) {
            dirChannel.force(true);
            return Durability.CONFIRMED;
        } catch (IOException e) {
            return Durability.UNCONFIRMED;
        }
    }

    /**
     * Replace the destination with a single atomic rename.
     *
     * <p>Atomic movement is the contract, not a preference: if the platform
     * cannot move the file atomically the destination is left exactly as it was
     * and an {@link IOException} is thrown. There is no non-atomic downgrade —
     * an in-place overwrite can expose a partially written destination, which
     * is the failure the staging file exists to prevent.
     */
    private static void moveIntoPlace(Path staging, Path target) throws IOException {
        moveIntoPlace(ATOMIC_MOVE, staging, target);
    }

    /** The production mover: a single atomic rename with replacement. */
    private static final MoveOperation ATOMIC_MOVE = (source, target) ->
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);

    /**
     * Seam for the atomic-refusal contract. A filesystem that refuses atomic
     * movement cannot be produced on every platform, so the move itself is
     * injectable; the policy under test (refuse, never downgrade) does not
     * depend on the mover.
     */
    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path target) throws IOException;
    }

    static void moveIntoPlace(MoveOperation operation, Path staging, Path target)
            throws IOException {
        try {
            operation.move(staging, target);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException(
                    "Atomic publication is unavailable for " + target
                            + "; refusing to replace it non-atomically", e);
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
