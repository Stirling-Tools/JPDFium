package stirling.software.jpdfium.doc;

/**
 * PDF/X family metadata. Only PDF/X-3 and PDF/X-4 have metadata support; this
 * is not a compliance claim. PDF/X-1a and PDF/X-6 require additional
 * validation/flattening and are rejected.
 */
public enum PdfXStandard {
    PDF_X_1A("PDF 1.3", "PDF/X-1:2001", "PDF/X-1a:2001", false),
    PDF_X_3("PDF 1.3", "PDF/X-3:2002", "PDF/X-3:2002", true),
    PDF_X_4("PDF 1.6", "PDF/X-4", "PDF/X-4", true),
    PDF_X_6("PDF 2.0", "PDF/X-6", "PDF/X-6", false);

    private final String pdfVersion;
    private final String gtsVersion;
    private final String gtsConformance;
    private final boolean metadataSupported;

    PdfXStandard(String pdfVersion, String gtsVersion, String gtsConformance,
                 boolean metadataSupported) {
        this.pdfVersion = pdfVersion;
        this.gtsVersion = gtsVersion;
        this.gtsConformance = gtsConformance;
        this.metadataSupported = metadataSupported;
    }

    /** Human-readable base PDF version (PDF/X-4 is PDF 1.6). */
    public String pdfVersion() {
        return pdfVersion;
    }

    /** Value of the XMP {@code GTS_PDFXVersion} property. */
    public String gtsVersion() {
        return gtsVersion;
    }

    /** Value of the XMP {@code GTS_PDFXConformance} property. */
    public String gtsConformance() {
        return gtsConformance;
    }

    /** True when this bootstrap can write the matching intent/XMP metadata. */
    public boolean metadataSupported() {
        return metadataSupported;
    }

    /** @deprecated Use {@link #metadataSupported()}; metadata is not certification. */
    @Deprecated
    public boolean implemented() {
        return metadataSupported();
    }

    /** True when the standard permits transparency (PDF/X-4 and later). */
    public boolean allowsTransparency() {
        return this == PDF_X_4 || this == PDF_X_6;
    }

    /** True when ICCBased colours are permitted (PDF/X-3 and later). */
    public boolean allowsIccBasedColors() {
        return this != PDF_X_1A;
    }
}
