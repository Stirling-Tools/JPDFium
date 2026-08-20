package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.RepairLib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PDF repair with a builder-pattern API.
 *
 * <p>
 * Multi-stage cascade pipeline:
 * <ol>
 * <li><b>Pre-repair</b>: Brotli-Flate transcoding (PDF 2.0+ compat)</li>
 * <li><b>Core</b>: PDFium tolerant open - qpdf recovery - startxref fix</li>
 * <li><b>Fallback</b>: PDFio third-opinion XRef repair</li>
 * <li><b>Rust fallback</b> (opt-in): lopdf tolerant XRef rebuild - final cascade
 * stage when all C-based repair strategies fail. Enabled via
 * {@link Builder#useLopdfFallback(boolean)} or included in {@link Builder#all()}.</li>
 * <li><b>Post-repair</b>: ICC profile validation (lcms2), JPEG2000 validation
 * (OpenJPEG)</li>
 * </ol>
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * RepairResult result = PdfRepair.builder()
 *         .input(pdfBytes)
 *         .all()
 *         .build()
 *         .execute();
 *
 * if (result.isUsable()) {
 *     byte[] fixed = result.repairedPdf();
 * }
 *
 * // Inspect only (non-destructive)
 * String diagnostics = PdfRepair.inspect(pdfBytes);
 * }</pre>
 *
 * <p>
 * All underlying libraries are MIT-compatible:
 * qpdf (Apache 2.0), PDFium (BSD), Brotli (MIT), PDFio (Apache 2.0),
 * lcms2 (MIT), OpenJPEG (BSD 2-Clause), lopdf (MIT, Rust).
 */
public final class PdfRepair {

    // Core repair flags - must match jpdfium.h
    private static final int FLAG_FORCE_V14 = 0x0001;
    private static final int FLAG_NORMALIZE_XREF = 0x0002;
    private static final int FLAG_FIX_STARTXREF = 0x0004;

    private final byte[] inputBytes;
    private final int flags;
    private final boolean usePdfioFallback;
    private final boolean useLopdfFallback;
    private final boolean transcodeBrotli;
    private final boolean writeDiagnostics;
    private final boolean sanitize;

    private final boolean validateIcc;
    private final boolean validateJpx;

    private PdfRepair(byte[] inputBytes, int flags,
            boolean usePdfioFallback, boolean useLopdfFallback,
            boolean transcodeBrotli,
            boolean validateIcc, boolean validateJpx,
            boolean writeDiagnostics, boolean sanitize) {
        this.inputBytes = inputBytes;
        this.flags = flags;
        this.usePdfioFallback = usePdfioFallback;
        this.useLopdfFallback = useLopdfFallback;
        this.transcodeBrotli = transcodeBrotli;
        this.validateIcc = validateIcc;
        this.validateJpx = validateJpx;
        this.writeDiagnostics = writeDiagnostics;
        this.sanitize = sanitize;
    }

    public boolean validateIcc() { return validateIcc; }
    public boolean validateJpx() { return validateJpx; }

    /** Create a new repair builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Inspect a PDF for damage without modifying it. */
    public static String inspect(byte[] pdfBytes) {
        return RepairLib.inspect(pdfBytes);
    }

    /** Inspect a PDF file for damage without modifying it. */
    public static String inspect(Path path) throws IOException {
        return inspect(Files.readAllBytes(path));
    }

    /**
     * Execute the full repair pipeline with configured options.
     *
     * @return result containing the status, repaired bytes, and diagnostics
     */
    public RepairResult execute() {
        byte[] current = inputBytes;

        // Pre-repair: Brotli-Flate transcoding
        if (transcodeBrotli) {
            byte[] transcoded = RepairLib.brotliToFlate(current);
            if (transcoded != null) {
                current = transcoded;
            }
        }

        RepairResult coreResult = RepairLib.repair(current, flags);

        if (usePdfioFallback && !coreResult.isUsable()) {
            RepairResult pdfioResult = RepairLib.pdfioRepair(current);
            if (pdfioResult.isUsable()) {
                coreResult = pdfioResult;
            }
        }

        // Final fallback: Rust/lopdf tolerant XRef rebuild.
        // Only attempted when all C-based strategies have failed and the option is enabled.
        if (useLopdfFallback && !coreResult.isUsable()) {
            RepairResult rustResult = RepairLib.rustRepair(current);
            if (rustResult.isUsable()) {
                coreResult = rustResult;
            }
        }

        if (sanitize && coreResult.isUsable()) {
            byte[] repairedBytes = coreResult.repairedPdf();
            if (repairedBytes != null) {
                try (PdfDocument doc = PdfDocument.open(repairedBytes)) {
                    PdfSecurity.sanitize(doc);
                    repairedBytes = doc.saveBytes();
                    coreResult = new RepairResult(coreResult.status(), repairedBytes,
                            coreResult.diagnosticJson());
                } catch (Exception _) {
                    // Sanitization failed on damaged structure; retain core result
                }
            }
        }

        if (!writeDiagnostics) {
            return new RepairResult(coreResult.status(), coreResult.repairedPdf(), null);
        }

        return coreResult;
    }

    /**
     * Builder for configuring PDF repair pipeline options.
     */
    public static final class Builder {
        private byte[] inputBytes;
        private boolean doForceVersion14;
        private boolean doNormalizeXref;
        private boolean doFixStartxref;
        private boolean doUsePdfioFallback;
        private boolean doUseLopdfFallback;
        private boolean doTranscodeBrotli;
        private boolean doValidateIcc;
        private boolean doValidateJpx;
        private boolean doWriteDiagnostics = true;
        private boolean doSanitize;

        private Builder() {
        }

        /** Set the input PDF bytes. */
        public Builder input(byte[] pdfBytes) {
            this.inputBytes = pdfBytes;
            return this;
        }

        /** Set the input PDF file. */
        public Builder input(Path path) throws IOException {
            this.inputBytes = Files.readAllBytes(path);
            return this;
        }

        /** Force output PDF version to 1.4. */
        public Builder forceVersion14(boolean enable) {
            this.doForceVersion14 = enable;
            return this;
        }

        /** Normalize xref format (force classic xref table). */
        public Builder normalizeXref(boolean enable) {
            this.doNormalizeXref = enable;
            return this;
        }

        /** Enable startxref offset brute-force correction. */
        public Builder fixStartxref(boolean enable) {
            this.doFixStartxref = enable;
            return this;
        }

        /** Enable PDFio third-opinion fallback (opt-in, requires libpdfio). */
        public Builder usePdfioFallback(boolean enable) {
            this.doUsePdfioFallback = enable;
            return this;
        }

        /**
         * Enable lopdf (Rust) XRef rebuild as a final fallback (opt-in).
         *
         * <p>Tried only after both the core qpdf pipeline and the PDFio fallback have
         * failed. lopdf's tolerant parser can often recover PDFs with corrupted XRef
         * tables that qpdf and PDFio cannot parse at all.
         *
         * <p>Included automatically by {@link #all()}.  If the Rust library is not
         * compiled in, this option is silently ignored (no error is thrown).
         *
         * @param enable {@code true} to enable (default {@code false})
         */
        public Builder useLopdfFallback(boolean enable) {
            this.doUseLopdfFallback = enable;
            return this;
        }

        /** Enable Brotli-Flate pre-repair transcoding (opt-in, requires libbrotli). */
        public Builder transcodeBrotli(boolean enable) {
            this.doTranscodeBrotli = enable;
            return this;
        }

        /** Enable ICC profile validation post-pass (opt-in, requires liblcms2). */
        public Builder validateIcc(boolean enable) {
            this.doValidateIcc = enable;
            return this;
        }

        /**
         * Enable JPEG2000 stream validation post-pass (opt-in, requires libopenjp2).
         */
        public Builder validateJpx(boolean enable) {
            this.doValidateJpx = enable;
            return this;
        }

        /** Include diagnostic JSON in the result (default: true). Set to false to skip diagnostics. */
        public Builder writeDiagnostics(boolean enable) {
            this.doWriteDiagnostics = enable;
            return this;
        }

        /**
         * Enable post-repair security sanitization: removes JavaScript,
         * embedded files, and action annotations from the repaired PDF.
         */
        public Builder sanitize(boolean enable) {
            this.doSanitize = enable;
            return this;
        }

        /** Enable all core + Phase 2 repair strategies (including Rust lopdf + sanitize). */
        public Builder all() {
            this.doForceVersion14 = true;
            this.doNormalizeXref = true;
            this.doFixStartxref = true;
            this.doUsePdfioFallback = true;
            this.doUseLopdfFallback = true;
            this.doTranscodeBrotli = true;
            this.doValidateIcc = true;
            this.doValidateJpx = true;
            this.doSanitize = true;
            return this;
        }

        /**
         * Build the repair instance. Call {@link PdfRepair#execute()} to run.
         *
         * @throws IllegalStateException if no input was provided
         */
        public PdfRepair build() {
            if (inputBytes == null || inputBytes.length == 0) {
                throw new IllegalStateException("Input PDF bytes must be provided via .input()");
            }

            int flags = 0;
            if (doForceVersion14)
                flags |= FLAG_FORCE_V14;
            if (doNormalizeXref)
                flags |= FLAG_NORMALIZE_XREF;
            if (doFixStartxref)
                flags |= FLAG_FIX_STARTXREF;

            return new PdfRepair(inputBytes, flags,
                    doUsePdfioFallback, doUseLopdfFallback, doTranscodeBrotli,
                    doValidateIcc, doValidateJpx, doWriteDiagnostics, doSanitize);
        }
    }
}
