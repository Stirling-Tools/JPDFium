package stirling.software.jpdfium.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * How merge/split moves document bytes: heap or disk.
 */
public final class StorageOptions {

    /** Memory and file trade-offs for merge/split intermediates. */
    public enum Mode {
        /** File-backed when the native file path is available, else memory. */
        AUTO,
        /** Always keep intermediates on the Java heap. */
        MEMORY,
        /** File-backed intermediates; fail when the native path is unavailable or fails. */
        FILE
    }

    private final Mode mode;
    private final Path tempDir;

    private StorageOptions(Builder b) {
        this.mode = b.mode;
        this.tempDir = b.tempDir;
    }

    public Mode mode() { return mode; }

    /** Temp dir for intermediates, null for the platform default. */
    public Path tempDir() { return tempDir; }

    public static StorageOptions defaults() { return builder().build(); }

    public static Builder builder() { return new Builder(); }

    /** Temp file in the configured dir. Owner-only from creation on POSIX. */
    public Path createTempFile(String prefix, String suffix) throws IOException {
        if (tempDir != null) {
            Files.createDirectories(tempDir);
            return restrictedTemp(tempDir, prefix, suffix);
        }
        return restrictedTemp(null, prefix, suffix);
    }

    /**
     * Staging file beside {@code target} so publish is a rename.
     * Owner-only from creation on POSIX; Windows uses temp-dir defaults.
     */
    public Path createStagingFile(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null && Files.isDirectory(parent)) {
            return restrictedTemp(parent, ".jpdfium-stage", ".pdf");
        }
        return createTempFile("jpdfium-stage", ".pdf");
    }

    private static Path restrictedTemp(Path dir, String prefix, String suffix) throws IOException {
        try {
            var attr = PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------"));
            return dir == null
                    ? Files.createTempFile(prefix, suffix, attr)
                    : Files.createTempFile(dir, prefix, suffix, attr);
        } catch (UnsupportedOperationException e) {
            return dir == null
                    ? Files.createTempFile(prefix, suffix)
                    : Files.createTempFile(dir, prefix, suffix);
        }
    }

    public static final class Builder {
        private Mode mode = Mode.AUTO;
        private Path tempDir;

        private Builder() {}

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /** File-backed intermediates. */
        public Builder file() {
            this.mode = Mode.FILE;
            return this;
        }

        /** Heap intermediates. Needed for password-protected inputs. */
        public Builder memory() {
            this.mode = Mode.MEMORY;
            return this;
        }

        /** Dir for temp files, null for the platform default. */
        public Builder tempDir(Path tempDir) {
            this.tempDir = tempDir;
            return this;
        }

        public StorageOptions build() {
            return new StorageOptions(this);
        }
    }
}
