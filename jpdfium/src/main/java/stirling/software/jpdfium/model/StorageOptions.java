package stirling.software.jpdfium.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How merge/split moves document bytes: heap or disk.
 */
public final class StorageOptions {

    /** Memory and file trade-offs for merge/split intermediates. */
    public enum Mode {
        /** File-backed when all inputs come from files, else memory. */
        AUTO,
        /** Always keep intermediates on the Java heap. */
        MEMORY,
        /** Always use temp files; fail when inputs are not file-backed. */
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

    /** Temp file in the configured dir. Created mode 0600 on POSIX. */
    public Path createTempFile(String prefix, String suffix) throws IOException {
        if (tempDir != null) {
            Files.createDirectories(tempDir);
            return Files.createTempFile(tempDir, prefix, suffix);
        }
        return Files.createTempFile(prefix, suffix);
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
