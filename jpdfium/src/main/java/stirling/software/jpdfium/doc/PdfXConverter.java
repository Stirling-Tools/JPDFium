package stirling.software.jpdfium.doc;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * PDF/X metadata bootstrap: CMYK conversion, a GTS_PDFX output intent and XMP
 * metadata.
 *
 * <p>This is <strong>not a PDF/X validator</strong>. It does not check or repair
 * transparency, embedded fonts, trim/art boxes, trapping, /ID, or all remaining
 * RGB content. Use an external preflight (veraPDF or Acrobat) before claiming
 * PDF/X conformance; {@link #checkMetadata(byte[], PdfXOptions)} only checks the
 * intent/ICC/version wiring written here.
 */
public final class PdfXConverter {

    private PdfXConverter() {}

    /** True when the loaded native library can run the implemented pipeline. */
    public static boolean isSupported() {
        return PdfOutputIntent.pdfxSupported()
                && PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK);
    }

    /** True when {@code standard} is wired into this initial implementation. */
    public static boolean supports(PdfXStandard standard) {
        return standard != null && standard.metadataSupported();
    }

    /** Convert colours in place and apply PDF/X intent metadata. */
    public static PdfDocument applyMetadata(PdfDocument doc, PdfXOptions options) {
        if (doc == null) throw new JPDFiumException("PDF/X metadata application requires a document");
        if (options == null) throw new JPDFiumException("PDF/X metadata application requires options");
        PdfXStandard standard = options.standard();
        if (!supports(standard)) {
            throw new JPDFiumException("PDF/X metadata is not supported for " + standard
                    + " (supported: PDF/X-3 and PDF/X-4)");
        }
        if (!isSupported()) {
            throw new JPDFiumException("PDF/X metadata application requires vector CMYK support,"
                    + " source colour-space probes and the QPDF PDF/X metadata bootstrap");
        }

        byte[] profile = options.outputIntentProfile();
        if (profile == null || profile.length == 0) {
            throw new JPDFiumException("PDF/X metadata requires an output intent ICC profile");
        }
        validateCmykProfile(profile);

        if (options.convertColors()) {
            PdfColorConverter.convert(doc, options.colorOptions());
        }
        byte[] pdf = doc.saveBytes();
        byte[] converted = PdfOutputIntent.applyPdfxMetadata(pdf, profile,
                PdfOutputIntent.Spec.pdfx(options.conditionIdentifier(), options.info()),
                standard.gtsVersion(), standard.gtsConformance());
        return PdfDocument.open(converted);
    }

    /** Apply PDF/X metadata while converting a PDF file to another file. */
    public static void applyMetadata(Path input, Path output, PdfXOptions options)
            throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("input and output paths are required");
        }
        try (PdfDocument source = PdfDocument.open(input);
                PdfDocument converted = applyMetadata(source, options)) {
            converted.save(output);
        }
    }

    /** Apply PDF/X metadata and return the new PDF bytes. */
    public static byte[] applyMetadata(byte[] input, PdfXOptions options) {
        if (input == null || input.length == 0) {
            throw new JPDFiumException("PDF/X metadata requires non-empty PDF bytes");
        }
        try (PdfDocument source = PdfDocument.open(input);
                PdfDocument converted = applyMetadata(source, options)) {
            return converted.saveBytes();
        }
    }

    /** Check the intent/ICC/metadata wiring; this is not a PDF/X compliance check. */
    public static void checkMetadata(byte[] pdf, PdfXOptions options) {
        if (pdf == null || pdf.length == 0) {
            throw new JPDFiumException("PDF/X metadata check requires non-empty PDF bytes");
        }
        if (options == null || options.outputIntentProfile() == null) {
            throw new JPDFiumException("PDF/X metadata check requires the output intent profile");
        }
        validateCmykProfile(options.outputIntentProfile());
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        if (!text.contains("/OutputIntents") || !text.contains("/GTS_PDFX")) {
            throw new JPDFiumException("PDF/X metadata check: missing GTS_PDFX output intent");
        }
        if (!text.contains("GTS_PDFXVersion")
                || !text.contains(options.standard().gtsVersion())) {
            throw new JPDFiumException("PDF/X metadata check: missing or mismatched"
                    + " GTS_PDFXVersion metadata");
        }
    }

    private static void validateCmykProfile(byte[] profile) {
        IccCmykTransform.validateIccStructure(profile);
        try {
            ICC_Profile icc = ICC_Profile.getInstance(profile);
            if (icc.getNumComponents() != 4
                    || icc.getColorSpaceType() != ColorSpace.TYPE_CMYK) {
                throw new JPDFiumException("PDF/X output intent must be a 4-component"
                        + " CMYK ICC profile");
            }
        } catch (IllegalArgumentException e) {
            throw new JPDFiumException("Invalid PDF/X output intent ICC profile: "
                    + e.getMessage(), e);
        }
    }

    /** @deprecated Use {@link #applyMetadata(PdfDocument, PdfXOptions)}. */
    @Deprecated
    public static PdfDocument convert(PdfDocument doc, PdfXOptions options) {
        return applyMetadata(doc, options);
    }

    /** @deprecated Use {@link #applyMetadata(Path, Path, PdfXOptions)}. */
    @Deprecated
    public static void convert(Path input, Path output, PdfXOptions options) throws IOException {
        applyMetadata(input, output, options);
    }

    /** @deprecated Use {@link #applyMetadata(byte[], PdfXOptions)}. */
    @Deprecated
    public static byte[] convert(byte[] input, PdfXOptions options) {
        return applyMetadata(input, options);
    }

    /** @deprecated Use {@link #checkMetadata(byte[], PdfXOptions)}. */
    @Deprecated
    public static void validate(byte[] pdf, PdfXOptions options) {
        checkMetadata(pdf, options);
    }

}
