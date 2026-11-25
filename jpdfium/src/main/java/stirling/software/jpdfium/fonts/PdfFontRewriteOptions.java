package stirling.software.jpdfium.fonts;

import java.nio.file.Path;
import java.util.List;

/**
 * Configuration for font conformance normalization and PDF/A-2b rewriting.
 */
public record PdfFontRewriteOptions(
        boolean embedMissing,
        boolean reconcileWidths,
        boolean repairNotdefAndMissingGlyphs,
        boolean normalizeCmapsAndEncodings,
        boolean cleanInvalidMetadata,
        boolean convertToPdfA2b,
        boolean stripSignatures,
        List<Path> donorFontDirs) {

    public static final int FLAG_EMBED_MISSING = 1 << 0;
    public static final int FLAG_RECONCILE_WIDTHS = 1 << 1;
    public static final int FLAG_REPAIR_NOTDEF = 1 << 2;
    public static final int FLAG_NORMALIZE_CMAPS = 1 << 3;
    public static final int FLAG_CLEAN_METADATA = 1 << 4;
    public static final int FLAG_CONVERT_PDFA2B = 1 << 5;
    public static final int FLAG_STRIP_SIGNATURES = 1 << 6;
    public static final int FLAG_CONVERT_PDFA1B = 1 << 7;
    public static final int FLAG_CONVERT_PDFA3B = 1 << 8;
    public static final int FLAG_PDFA_LEVEL_U = 1 << 9;
    public static final int FLAG_PDFA_LEVEL_A = 1 << 10;
    public static final int FLAG_INJECT_DEFAULT_RGB = 1 << 11;

    public PdfFontRewriteOptions {
        donorFontDirs = donorFontDirs == null ? List.of() : List.copyOf(donorFontDirs);
    }

    public PdfFontRewriteOptions(
            boolean embedMissing,
            boolean reconcileWidths,
            boolean repairNotdefAndMissingGlyphs,
            boolean normalizeCmapsAndEncodings,
            boolean cleanInvalidMetadata,
            boolean convertToPdfA2b,
            List<Path> donorFontDirs) {
        this(
                embedMissing,
                reconcileWidths,
                repairNotdefAndMissingGlyphs,
                normalizeCmapsAndEncodings,
                cleanInvalidMetadata,
                convertToPdfA2b,
                false,
                donorFontDirs);
    }

    /** Full PDF/A-2b font and structure rewrite defaults (refuses signed documents). */
    public static PdfFontRewriteOptions defaults() {
        return new PdfFontRewriteOptions(true, true, true, true, true, true, false, List.of());
    }

    /** Font-only conformance rewrite without PDF/A structure/metadata changes. */
    public static PdfFontRewriteOptions fontsOnly() {
        return new PdfFontRewriteOptions(true, true, true, true, true, false, false, List.of());
    }

    public int toNativeFlags() {
        int flags = 0;
        if (embedMissing) flags |= FLAG_EMBED_MISSING;
        if (reconcileWidths) flags |= FLAG_RECONCILE_WIDTHS;
        if (repairNotdefAndMissingGlyphs) flags |= FLAG_REPAIR_NOTDEF;
        if (normalizeCmapsAndEncodings) flags |= FLAG_NORMALIZE_CMAPS;
        if (cleanInvalidMetadata) flags |= FLAG_CLEAN_METADATA;
        if (convertToPdfA2b) flags |= FLAG_CONVERT_PDFA2B;
        if (stripSignatures) flags |= FLAG_STRIP_SIGNATURES;
        return flags;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Builder() {}
        private boolean embedMissing = true;
        private boolean reconcileWidths = true;
        private boolean repairNotdefAndMissingGlyphs = true;
        private boolean normalizeCmapsAndEncodings = true;
        private boolean cleanInvalidMetadata = true;
        private boolean convertToPdfA2b = true;
        private boolean stripSignatures = false;
        private List<Path> donorFontDirs = List.of();

        public Builder embedMissing(boolean v) {
            this.embedMissing = v;
            return this;
        }

        public Builder reconcileWidths(boolean v) {
            this.reconcileWidths = v;
            return this;
        }

        public Builder repairNotdefAndMissingGlyphs(boolean v) {
            this.repairNotdefAndMissingGlyphs = v;
            return this;
        }

        public Builder normalizeCmapsAndEncodings(boolean v) {
            this.normalizeCmapsAndEncodings = v;
            return this;
        }

        public Builder cleanInvalidMetadata(boolean v) {
            this.cleanInvalidMetadata = v;
            return this;
        }

        public Builder convertToPdfA2b(boolean v) {
            this.convertToPdfA2b = v;
            return this;
        }

        public Builder stripSignatures(boolean v) {
            this.stripSignatures = v;
            return this;
        }

        public Builder donorFontDirs(List<Path> dirs) {
            this.donorFontDirs = dirs == null ? List.of() : List.copyOf(dirs);
            return this;
        }

        public PdfFontRewriteOptions build() {
            return new PdfFontRewriteOptions(
                    embedMissing,
                    reconcileWidths,
                    repairNotdefAndMissingGlyphs,
                    normalizeCmapsAndEncodings,
                    cleanInvalidMetadata,
                    convertToPdfA2b,
                    stripSignatures,
                    donorFontDirs);
        }
    }
}
