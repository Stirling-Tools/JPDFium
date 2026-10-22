package stirling.software.jpdfium.doc;

import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.ColorBindings;

/** ICC output intent handling (declares the intended printing condition). */
public final class PdfOutputIntent {

    /** PDF/X output intent subtype name. */
    public static final String SUBTYPE_PDFX = "GTS_PDFX";
    /** PDF/A output intent subtype name. */
    public static final String SUBTYPE_PDFA = "GTS_PDFA1";

    private PdfOutputIntent() {}

    /** Output intent metadata written alongside the ICC profile. */
    public record Spec(String subtype, String conditionIdentifier, String info) {

        public Spec {
            if (subtype == null || subtype.isBlank()) subtype = SUBTYPE_PDFX;
            if (conditionIdentifier == null) conditionIdentifier = "";
            if (info == null) info = "";
        }

        /** PDF/X output intent (defaults to subtype {@code GTS_PDFX}). */
        public static Spec pdfx(String conditionIdentifier, String info) {
            return new Spec(SUBTYPE_PDFX, conditionIdentifier, info);
        }

        /** PDF/A output intent. */
        public static Spec pdfa(String conditionIdentifier, String info) {
            return new Spec(SUBTYPE_PDFA, conditionIdentifier, info);
        }

        /** Convenience: PDF/X-4 style intent with an empty description. */
        public static Spec pdfx(String conditionIdentifier) {
            return new Spec(SUBTYPE_PDFX, conditionIdentifier, "");
        }
    }

    /** True when this build can write an output intent (requires QPDF). */
    public static boolean isSupported() {
        return ColorBindings.outputIntentAvailable();
    }

    /** True when the PDF/X output-intent + XMP bootstrap is available. */
    public static boolean pdfxSupported() {
        return ColorBindings.pdfxAvailable();
    }

    /** Write the output intent into a byte array PDF. */
    public static byte[] apply(byte[] pdf, byte[] iccProfile, Spec spec) {
        if (pdf == null || pdf.length == 0) {
            throw new JPDFiumException("Output intent requires a non-empty PDF");
        }
        if (iccProfile == null || iccProfile.length == 0) {
            throw new JPDFiumException("Output intent requires an ICC profile");
        }
        if (!isSupported()) {
            throw new JPDFiumException("This build has no output intent support"
                    + " (jpdfium_color_output_intent_* unavailable; requires QPDF)");
        }
        Spec effective = spec == null ? new Spec(SUBTYPE_PDFX, "", "") : spec;
        byte[] result = ColorBindings.outputIntentBytes(pdf, iccProfile, effective.subtype(),
                effective.conditionIdentifier(), effective.info());
        if (result == null) {
            throw new JPDFiumException("Failed to write output intent"
                    + " (check that the ICC profile is a valid N-component profile)");
        }
        return result;
    }

    /** Write the output intent directly from file to file (no document-sized buffers). */
    public static boolean apply(Path in, Path out, byte[] iccProfile, Spec spec) {
        if (in == null || out == null || iccProfile == null || iccProfile.length == 0) {
            return false;
        }
        if (!isSupported()) return false;
        Spec effective = spec == null ? new Spec(SUBTYPE_PDFX, "", "") : spec;
        return ColorBindings.outputIntentFile(in.toString(), out.toString(), iccProfile,
                effective.subtype(), effective.conditionIdentifier(), effective.info());
    }

    /** Serialise {@code doc}, write the output intent, and return a new document. */
    public static PdfDocument apply(PdfDocument doc, byte[] iccProfile, Spec spec) {
        if (doc == null) throw new JPDFiumException("Output intent requires a document");
        return PdfDocument.open(apply(doc.saveBytes(), iccProfile, spec));
    }

    /**
     * Write a PDF/X output intent and create/update the catalog XMP metadata.
     * This is metadata application, not PDF/X certification: it does not
     * preflight transparency, fonts, trim boxes, trapping or RGB content. The
     * resulting file still needs a PDF/X-aware validator.
     */
    public static byte[] applyPdfxMetadata(byte[] pdf, byte[] iccProfile, Spec spec,
                                   String gtsVersion, String gtsConformance) {
        if (pdf == null || pdf.length == 0) {
            throw new JPDFiumException("PDF/X metadata requires a non-empty PDF");
        }
        if (iccProfile == null || iccProfile.length == 0) {
            throw new JPDFiumException("PDF/X metadata requires an ICC profile");
        }
        if (gtsVersion == null || gtsVersion.isBlank()) {
            throw new JPDFiumException("PDF/X metadata requires a GTS_PDFXVersion");
        }
        if (!pdfxSupported()) {
            throw new JPDFiumException("This native build has no PDF/X metadata bootstrap"
                    + " (jpdfium_color_pdfx_* unavailable; requires QPDF)");
        }
        Spec effective = spec == null ? new Spec(SUBTYPE_PDFX, "", "") : spec;
        String conformance = gtsConformance == null || gtsConformance.isBlank()
                ? gtsVersion : gtsConformance;
        byte[] result = ColorBindings.pdfxBytes(pdf, iccProfile, gtsVersion, conformance,
                effective.conditionIdentifier(), effective.info());
        if (result == null) {
            throw new JPDFiumException("Failed to write PDF/X metadata"
                    + " (check that the ICC profile is a valid 4-component profile)");
        }
        return result;
    }

    /** File-to-file PDF/X metadata application (not a compliance check). */
    public static boolean applyPdfxMetadata(Path in, Path out, byte[] iccProfile, Spec spec,
                                            String gtsVersion, String gtsConformance) {
        if (in == null || out == null || iccProfile == null || iccProfile.length == 0
                || gtsVersion == null || gtsVersion.isBlank()) {
            return false;
        }
        if (!pdfxSupported()) return false;
        Spec effective = spec == null ? new Spec(SUBTYPE_PDFX, "", "") : spec;
        String conformance = gtsConformance == null || gtsConformance.isBlank()
                ? gtsVersion : gtsConformance;
        return ColorBindings.pdfxFile(in.toString(), out.toString(), iccProfile, gtsVersion,
                conformance, effective.conditionIdentifier(), effective.info());
    }

    /**
     * @deprecated Use {@link #applyPdfxMetadata(byte[], byte[], Spec, String, String)};
     *     this method only applies metadata and does not make the file PDF/X-compliant.
     */
    @Deprecated
    public static byte[] applyPdfx(byte[] pdf, byte[] iccProfile, Spec spec,
                                   String gtsVersion, String gtsConformance) {
        return applyPdfxMetadata(pdf, iccProfile, spec, gtsVersion, gtsConformance);
    }

    /**
     * @deprecated Use {@link #applyPdfxMetadata(Path, Path, byte[], Spec, String, String)};
     *     this method only applies metadata and does not make the file PDF/X-compliant.
     */
    @Deprecated
    public static boolean applyPdfx(Path in, Path out, byte[] iccProfile, Spec spec,
                                    String gtsVersion, String gtsConformance) {
        return applyPdfxMetadata(in, out, iccProfile, spec, gtsVersion, gtsConformance);
    }

}
