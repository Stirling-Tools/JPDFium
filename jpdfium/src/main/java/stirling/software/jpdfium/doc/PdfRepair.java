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
 * lcms2 (MIT), OpenJPEG (BSD 2-Clause).
 */
public final class PdfRepair {

    // Core repair flags - must match jpdfium.h
    private static final int FLAG_FORCE_V14 = 0x0001;
    private static final int FLAG_NORMALIZE_XREF = 0x0002;
    private static final int FLAG_FIX_STARTXREF = 0x0004;

    private final byte[] inputBytes;
    private final int flags;
    private final boolean usePdfioFallback;
    private final boolean transcodeBrotli;
    private final boolean writeDiagnostics;
    private final boolean sanitize;

    private PdfRepair(byte[] inputBytes, int flags,
            boolean usePdfioFallback, boolean transcodeBrotli,
            boolean validateIcc, boolean validateJpx,
            boolean writeDiagnostics, boolean sanitize) {
        this.inputBytes = inputBytes;
        this.flags = flags;
        this.usePdfioFallback = usePdfioFallback;
        this.transcodeBrotli = transcodeBrotli;
        this.writeDiagnostics = writeDiagnostics;
        this.sanitize = sanitize;
    }

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

        // Core repair cascade (qpdf + PDFium)
        RepairResult coreResult = RepairLib.repair(current, flags);

        // If core repair failed, try PDFio fallback
        if (usePdfioFallback && !coreResult.isUsable()) {
            RepairResult pdfioResult = RepairLib.pdfioRepair(current);
            if (pdfioResult.isUsable()) {
                coreResult = pdfioResult;
            }
        }

        // Post-repair: security sanitization (remove JS, embedded files, actions)
        if (sanitize && coreResult.isUsable()) {
            byte[] repairedBytes = coreResult.repairedPdf();
            try (PdfDocument doc = PdfDocument.open(repairedBytes)) {
                PdfSecurity.sanitize(doc);
                repairedBytes = doc.saveBytes();
                coreResult = new RepairResult(coreResult.status(), repairedBytes,
                        coreResult.diagnosticJson());
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
        private boolean forceVersion14;
        private boolean normalizeXref;
        private boolean fixStartxref;
        private boolean usePdfioFallback;
        private boolean transcodeBrotli;
        private boolean validateIcc;
        private boolean validateJpx;
        private boolean writeDiagnostics = true;
        private boolean sanitize;

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
            this.forceVersion14 = enable;
            return this;
        }

        /** Normalize xref format (force classic xref table). */
        public Builder normalizeXref(boolean enable) {
            this.normalizeXref = enable;
            return this;
        }

        /** Enable startxref offset brute-force correction. */
        public Builder fixStartxref(boolean enable) {
            this.fixStartxref = enable;
            return this;
        }

        /** Enable PDFio third-opinion fallback (opt-in, requires libpdfio). */
        public Builder usePdfioFallback(boolean enable) {
            this.usePdfioFallback = enable;
            return this;
        }

        /** Enable Brotli-Flate pre-repair transcoding (opt-in, requires libbrotli). */
        public Builder transcodeBrotli(boolean enable) {
            this.transcodeBrotli = enable;
            return this;
        }

        /** Enable ICC profile validation post-pass (opt-in, requires liblcms2). */
        public Builder validateIcc(boolean enable) {
            this.validateIcc = enable;
            return this;
        }

        /**
         * Enable JPEG2000 stream validation post-pass (opt-in, requires libopenjp2).
         */
        public Builder validateJpx(boolean enable) {
            this.validateJpx = enable;
            return this;
        }

        /** Include diagnostic JSON in the result (default: true). Set to false to skip diagnostics. */
        public Builder writeDiagnostics(boolean enable) {
            this.writeDiagnostics = enable;
            return this;
        }

        /**
         * Enable post-repair security sanitization: removes JavaScript,
         * embedded files, and action annotations from the repaired PDF.
         */
        public Builder sanitize(boolean enable) {
            this.sanitize = enable;
            return this;
        }

        /** Enable all core + Phase 2 repair strategies (including sanitize). */
        public Builder all() {
            this.forceVersion14 = true;
            this.normalizeXref = true;
            this.fixStartxref = true;
            this.usePdfioFallback = true;
            this.transcodeBrotli = true;
            this.validateIcc = true;
            this.validateJpx = true;
            this.sanitize = true;
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
            if (forceVersion14)
                flags |= FLAG_FORCE_V14;
            if (normalizeXref)
                flags |= FLAG_NORMALIZE_XREF;
            if (fixStartxref)
                flags |= FLAG_FIX_STARTXREF;

            return new PdfRepair(inputBytes, flags,
                    usePdfioFallback, transcodeBrotli, validateIcc, validateJpx,
                    writeDiagnostics, sanitize);
        }
    }
}
