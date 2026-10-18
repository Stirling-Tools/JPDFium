package stirling.software.jpdfium.model;

/**
 * Output policy for document saves.
 *
 * <p>Memory/latency trade-off is explicit, not hidden behind overloads:
 * <ul>
 *   <li>{@code saveBytes()} materializes the whole document (high peak).</li>
 *   <li>{@code saveTo(Path)} streams natively to a sibling temp file, then
 *       atomically publishes (bounded native + Java memory, guard held during
 *       the file write only).</li>
 *   <li>{@code saveTo(channel)} spools to a temp file under the guard, then
 *       transfers in bounded chunks with the guard released (slow channels
 *       never stall unrelated PDFium work).</li>
 * </ul>
 */
public final class SaveOptions {

    private final long maxOutputBytes;
    private final boolean verifyReopen;
    private final boolean durable;

    private SaveOptions(Builder b) {
        this.maxOutputBytes = b.maxOutputBytes;
        this.verifyReopen = b.verifyReopen;
        this.durable = b.durable;
    }

    /** Maximum accepted output size in bytes; {@code 0} disables the bound. */
    public long maxOutputBytes() {
        return maxOutputBytes;
    }

    /** Whether the staged file must reopen before it is published. */
    public boolean verifyReopen() {
        return verifyReopen;
    }

    /**
     * Whether the published file must survive a crash (fsync + directory flush). Internal staging
     * files pass {@code false}, since the page cache already serves their bytes.
     */
    public boolean durable() {
        return durable;
    }

    /** Low-overhead file save without reopen validation. */
    public static SaveOptions fast() {
        return builder().build();
    }

    /** Internal staging save: no reopen validation and no crash-durability flush. */
    public static SaveOptions ephemeral() {
        return builder().durable(false).build();
    }

    /** Save with a reopen validation pass before publish. */
    public static SaveOptions validated() {
        return builder().verifyReopen(true).build();
    }

    /** Save refusing outputs larger than {@code maxBytes}. */
    public static SaveOptions maxOutputBytes(long maxBytes) {
        return builder().maxOutputBytes(maxBytes).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().maxOutputBytes(maxOutputBytes).verifyReopen(verifyReopen)
                .durable(durable);
    }

    public static final class Builder {
        private long maxOutputBytes;
        private boolean verifyReopen;
        private boolean durable = true;

        private Builder() {}

        /**
         * Refuse outputs larger than {@code maxBytes}.
         * Native streaming saves abort mid-write; buffered/channel paths
         * fail during staging before publish.
         */
        public Builder maxOutputBytes(long maxBytes) {
            if (maxBytes < 0) throw new IllegalArgumentException("maxOutputBytes must be >= 0");
            this.maxOutputBytes = maxBytes;
            return this;
        }

        /** Reopen the staged file before publish (extra I/O, stronger guarantee). */
        public Builder verifyReopen(boolean verify) {
            this.verifyReopen = verify;
            return this;
        }

        /**
         * Require the published file to survive a crash (fsync + directory flush). Defaults to
         * {@code true}; set {@code false} only for internal staging files.
         */
        public Builder durable(boolean durable) {
            this.durable = durable;
            return this;
        }

        public SaveOptions build() {
            return new SaveOptions(this);
        }
    }
}
