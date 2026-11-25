package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.fonts.PdfFontRewriteOptions;
import stirling.software.jpdfium.fonts.FontRewriteResult;
import stirling.software.jpdfium.fonts.PdfFontRewriter;

/**
 * PDF/A converter supporting PDF/A-1b, PDF/A-1a, PDF/A-2b, PDF/A-2u, PDF/A-2a,
 * PDF/A-3b, PDF/A-3u, and PDF/A-3a, combining font conformance normalization with
 * profile-specific metadata, OutputIntent, annotation, transparency, Unicode,
 * logical structure tagging, and color-space compliance.
 */
public final class PdfAConverter {

    public enum Profile {
        PDFA_1B(PdfFontRewriteOptions.FLAG_CONVERT_PDFA1B),
        PDFA_1A(PdfFontRewriteOptions.FLAG_CONVERT_PDFA1B | PdfFontRewriteOptions.FLAG_PDFA_LEVEL_A),
        PDFA_2B(PdfFontRewriteOptions.FLAG_CONVERT_PDFA2B),
        PDFA_2U(PdfFontRewriteOptions.FLAG_CONVERT_PDFA2B | PdfFontRewriteOptions.FLAG_PDFA_LEVEL_U),
        PDFA_2A(PdfFontRewriteOptions.FLAG_CONVERT_PDFA2B | PdfFontRewriteOptions.FLAG_PDFA_LEVEL_A),
        PDFA_3B(PdfFontRewriteOptions.FLAG_CONVERT_PDFA3B),
        PDFA_3U(PdfFontRewriteOptions.FLAG_CONVERT_PDFA3B | PdfFontRewriteOptions.FLAG_PDFA_LEVEL_U),
        PDFA_3A(PdfFontRewriteOptions.FLAG_CONVERT_PDFA3B | PdfFontRewriteOptions.FLAG_PDFA_LEVEL_A);

        private final int nativeFlag;

        Profile(int nativeFlag) {
            this.nativeFlag = nativeFlag;
        }

        public int nativeFlag() {
            return nativeFlag;
        }
    }

    private PdfAConverter() {}

    /** Converts the given PDF bytes to the requested PDF/A profile using default options. */
    public static FontRewriteResult convert(byte[] pdf, Profile profile) {
        return convert(pdf, profile, PdfFontRewriteOptions.defaults());
    }

    /** Converts the given PDF bytes to the requested PDF/A profile using the supplied options. */
    public static FontRewriteResult convert(byte[] pdf, Profile profile, PdfFontRewriteOptions options) {
        Profile effectiveProfile = profile == null ? Profile.PDFA_2B : profile;
        PdfFontRewriteOptions opts = options == null ? PdfFontRewriteOptions.defaults() : options;
        int flags = opts.toNativeFlags()
                & ~(PdfFontRewriteOptions.FLAG_CONVERT_PDFA1B
                | PdfFontRewriteOptions.FLAG_CONVERT_PDFA2B
                | PdfFontRewriteOptions.FLAG_CONVERT_PDFA3B
                | PdfFontRewriteOptions.FLAG_PDFA_LEVEL_U
                | PdfFontRewriteOptions.FLAG_PDFA_LEVEL_A);
        flags |= effectiveProfile.nativeFlag();
        return PdfFontRewriter.rewriteWithFlags(pdf, flags, opts.donorFontDirs());
    }

    /** Converts the given PDF bytes to PDF/A-1b (ISO 19005-1, PDF 1.4, no transparency). */
    public static FontRewriteResult toPdfA1b(byte[] pdf) {
        return convert(pdf, Profile.PDFA_1B);
    }

    /** Converts the given PDF bytes to PDF/A-1b using the supplied options. */
    public static FontRewriteResult toPdfA1b(byte[] pdf, PdfFontRewriteOptions options) {
        return convert(pdf, Profile.PDFA_1B, options);
    }

    /** Converts the given PDF bytes to PDF/A-1a (ISO 19005-1, tagged + Unicode). */
    public static FontRewriteResult toPdfA1a(byte[] pdf) {
        return convert(pdf, Profile.PDFA_1A);
    }

    /** Converts the given PDF bytes to PDF/A-1a using the supplied options. */
    public static FontRewriteResult toPdfA1a(byte[] pdf, PdfFontRewriteOptions options) {
        return convert(pdf, Profile.PDFA_1A, options);
    }

    /**
     * Converts the given PDF bytes to PDF/A-2b using default font conformance
     * and structure normalization options.
     */
    public static FontRewriteResult toPdfA2b(byte[] pdf) {
        return convert(pdf, Profile.PDFA_2B);
    }

    /**
     * Converts the given PDF bytes to PDF/A-2b using the supplied options
     * (with {@code convertToPdfA2b} enforced).
     */
    public static FontRewriteResult toPdfA2b(byte[] pdf, PdfFontRewriteOptions options) {
        return convert(pdf, Profile.PDFA_2B, options);
    }

    /** Converts the given PDF bytes to PDF/A-2u (ISO 19005-2, Unicode). */
    public static FontRewriteResult toPdfA2u(byte[] pdf) {
        return convert(pdf, Profile.PDFA_2U);
    }

    /** Converts the given PDF bytes to PDF/A-2u using the supplied options. */
    public static FontRewriteResult toPdfA2u(byte[] pdf, PdfFontRewriteOptions options) {
        return convert(pdf, Profile.PDFA_2U, options);
    }

    /** Converts the given PDF bytes to PDF/A-2a (ISO 19005-2, tagged + Unicode). */
    public static FontRewriteResult toPdfA2a(byte[] pdf) {
        return convert(pdf, Profile.PDFA_2A);
    }

    /** Converts the given PDF bytes to PDF/A-2a using the supplied options. */
    public static FontRewriteResult toPdfA2a(byte[] pdf, PdfFontRewriteOptions options) {
        return convert(pdf, Profile.PDFA_2A, options);
    }

    /** Converts the given PDF bytes to PDF/A-3b (ISO 19005-3, preserves attachments). */
    public static FontRewriteResult toPdfA3b(byte[] pdf) {
        return convert(pdf, Profile.PDFA_3B);
    }

    /** Converts the given PDF bytes to PDF/A-3b using the supplied options. */
    public static FontRewriteResult toPdfA3b(byte[] pdf, PdfFontRewriteOptions options) {
        return convert(pdf, Profile.PDFA_3B, options);
    }

    /** Converts the given PDF bytes to PDF/A-3u (ISO 19005-3, Unicode + attachments). */
    public static FontRewriteResult toPdfA3u(byte[] pdf) {
        return convert(pdf, Profile.PDFA_3U);
    }

    /** Converts the given PDF bytes to PDF/A-3u using the supplied options. */
    public static FontRewriteResult toPdfA3u(byte[] pdf, PdfFontRewriteOptions options) {
        return convert(pdf, Profile.PDFA_3U, options);
    }

    /** Converts the given PDF bytes to PDF/A-3a (ISO 19005-3, tagged + Unicode + attachments). */
    public static FontRewriteResult toPdfA3a(byte[] pdf) {
        return convert(pdf, Profile.PDFA_3A);
    }

    /** Converts the given PDF bytes to PDF/A-3a using the supplied options. */
    public static FontRewriteResult toPdfA3a(byte[] pdf, PdfFontRewriteOptions options) {
        return convert(pdf, Profile.PDFA_3A, options);
    }
}
