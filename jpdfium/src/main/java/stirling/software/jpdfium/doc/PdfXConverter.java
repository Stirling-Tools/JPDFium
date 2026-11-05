package stirling.software.jpdfium.doc;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.PdfVersion;

/**
 * PDF/X metadata bootstrap for PDF/X-1a, PDF/X-3, PDF/X-4 and PDF/X-6:
 * CMYK conversion, a GTS_PDFX output intent, XMP metadata, the standard base
 * PDF version and page trim boxes.
 *
 * <p>This is <strong>not a PDF/X validator</strong>. It writes the base version and
 * missing trim boxes, can reject non-embedded fonts on request, and rejects
 * transparency for standards that forbid it. It does not repair font programs,
 * trapping, /ID, or every remaining RGB object. Use an external preflight
 * (veraPDF or Acrobat) before claiming PDF/X conformance;
 * {@link #checkMetadata(byte[], PdfXOptions)} only checks the intent/ICC/version
 * wiring written here.
 */
public final class PdfXConverter {

    private PdfXConverter() {}

    /** True when the loaded native library can run the implemented metadata pipeline. */
    public static boolean isSupported() {
        return PdfOutputIntent.pdfxSupported()
                && PdfColorConverter.supportsColorSpace(PdfColorConverter.ColorSpace.CMYK);
    }

    /** True when {@code standard}'s intent/XMP metadata is wired into this build. */
    public static boolean supports(PdfXStandard standard) {
        return standard != null && standard.metadataSupported();
    }

    /** Convert colours in place and apply PDF/X intent metadata. */
    public static PdfDocument applyMetadata(PdfDocument doc, PdfXOptions options) {
        prepare(doc, options);
        PdfXStandard standard = options.standard();
        byte[] converted = PdfOutputIntent.applyPdfxMetadata(doc.saveBytes(),
                options.outputIntentProfile(),
                PdfOutputIntent.Spec.pdfx(options.conditionIdentifier(), options.info()),
                standard.gtsVersion(), standard.gtsConformance());
        try (PdfDocument metadata = PdfDocument.open(converted)) {
            applyPageBoxes(metadata);
            applyDocumentInfo(metadata, options);
            byte[] versioned = PdfVersionConverter.saveWithVersionToBytes(
                    metadata.rawHandle(), versionFor(standard));
            requireEmbeddedFonts(versioned, options);
            return PdfDocument.open(versioned);
        }
    }

    /** Apply PDF/X metadata while converting a PDF file without holding it in Java bytes. */
    public static void applyMetadata(Path input, Path output, PdfXOptions options)
            throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("input and output paths are required");
        }
        requireSupported(options);
        Path staging = null;
        Path metadata = null;
        try {
            staging = Files.createTempFile(
                    output.toAbsolutePath().getParent(), "jpdfium-pdfx-", ".pdf");
            metadata = Files.createTempFile(
                    output.toAbsolutePath().getParent(), "jpdfium-pdfx-meta-", ".pdf");
            try (PdfDocument source = PdfDocument.open(input)) {
                prepare(source, options);
                source.save(staging);
            }
            PdfXStandard standard = options.standard();
            if (!PdfOutputIntent.applyPdfxMetadata(staging, metadata,
                    options.outputIntentProfile(),
                    PdfOutputIntent.Spec.pdfx(options.conditionIdentifier(), options.info()),
                    standard.gtsVersion(), standard.gtsConformance())) {
                throw new JPDFiumException("PDF/X metadata application failed");
            }
            try (PdfDocument document = PdfDocument.open(metadata)) {
                applyPageBoxes(document);
                applyDocumentInfo(document, options);
                requireEmbeddedFonts(document, options);
                document.save(staging);
            }
            if (!PdfVersionConverter.forceVersion(staging, output, versionFor(standard))) {
                throw new JPDFiumException("PDF/X version rewrite failed");
            }
        } finally {
            deleteQuietly(staging);
            deleteQuietly(metadata);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            path.toFile().deleteOnExit();
        }
    }

    /** Check metadata without making the caller materialize the PDF. */
    public static void checkMetadata(Path pdf, PdfXOptions options) throws IOException {
        if (pdf == null) {
            throw new JPDFiumException("PDF/X metadata check requires a path");
        }
        if (!Files.isRegularFile(pdf) || Files.size(pdf) == 0) {
            throw new JPDFiumException("PDF/X metadata check requires a non-empty file");
        }
        if (options == null || options.outputIntentProfile() == null) {
            throw new JPDFiumException("PDF/X metadata check requires the output intent profile");
        }
        validateCmykProfile(options.outputIntentProfile());
        if (!containsAll(pdf, new byte[][] {ascii("/OutputIntents")})) {
            throw new JPDFiumException("PDF/X metadata check: missing /OutputIntents");
        }
        if (!containsAll(pdf, new byte[][] {ascii("/GTS_PDFX")})) {
            throw new JPDFiumException("PDF/X metadata check: missing GTS_PDFX metadata");
        }
        if (!containsAll(pdf, new byte[][] {
                ascii("GTS_PDFXVersion"), ascii(options.standard().gtsVersion())})) {
            throw new JPDFiumException("PDF/X metadata check: mismatched GTS_PDFXVersion");
        }
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean containsAll(Path pdf, byte[][] markers) throws IOException {
        int overlap = 0;
        for (byte[] marker : markers) {
            overlap = Math.max(overlap, marker.length - 1);
        }
        boolean[] found = new boolean[markers.length];
        byte[] carry = new byte[0];
        try (InputStream input = Files.newInputStream(pdf)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                byte[] chunk = new byte[carry.length + read];
                System.arraycopy(carry, 0, chunk, 0, carry.length);
                System.arraycopy(buffer, 0, chunk, carry.length, read);
                for (int i = 0; i < markers.length; i++) {
                    if (!found[i]) found[i] = contains(chunk, markers[i]);
                }
                if (allFound(found)) return true;
                int keep = Math.min(overlap, chunk.length);
                carry = new byte[keep];
                System.arraycopy(chunk, chunk.length - keep, carry, 0, keep);
            }
        }
        return allFound(found);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static boolean allFound(boolean[] found) {
        for (boolean value : found) {
            if (!value) return false;
        }
        return true;
    }

    private static void applyDocumentInfo(PdfDocument document, PdfXOptions options) {
        document.setTrappedFalse();
        if (options.title() != null && !options.title().isBlank()) {
            document.setMetadata("Title", options.title());
        }
        PdfMetadata metadata = PdfMetadata.of(document.rawHandleForRead());
        String now = DateTimeFormatter.ofPattern("'D:'yyyyMMddHHmmss'+00''00'")
                .format(Instant.now().atZone(ZoneOffset.UTC));
        if (metadata.creationDate().isEmpty()) {
            document.setMetadata("CreationDate", now);
        }
        if (metadata.modDate().isEmpty()) {
            document.setMetadata("ModDate", now);
        }
    }

    private static void applyPageBoxes(PdfDocument document) {
        for (int i = 0; i < document.pageCount(); i++) {
            try (PdfPage page = document.page(i)) {
                if (page.getTrimBox().isEmpty()) {
                    page.setTrimBox(page.getCropBox().orElse(page.getMediaBox()));
                }
            }
        }
    }

    private static void requireEmbeddedFonts(byte[] pdf, PdfXOptions options) {
        if (!options.requireEmbeddedFonts()) return;
        try (PdfDocument document = PdfDocument.open(pdf)) {
            requireEmbeddedFonts(document, options);
        }
    }

    private static void requireEmbeddedFonts(PdfDocument document, PdfXOptions options) {
        if (options.requireEmbeddedFonts() && PdfFontAuditor.hasNonEmbeddedFonts(document)) {
            throw new JPDFiumException(
                    "PDF/X requires embedded fonts; conversion left a non-embedded font");
        }
    }

    private static PdfVersion versionFor(PdfXStandard standard) {
        return switch (standard) {
            case PDF_X_1A, PDF_X_3 -> PdfVersion.V1_3;
            case PDF_X_4 -> PdfVersion.V1_6;
            case PDF_X_6 -> PdfVersion.V2_0;
        };
    }

    /** Apply PDF/X metadata and return the new PDF bytes. */
    public static byte[] applyMetadata(byte[] input, PdfXOptions options) {
        if (input == null || input.length == 0) {
            throw new JPDFiumException("PDF/X metadata requires non-empty PDF bytes");
        }
        requireSupported(options);
        try (PdfDocument source = PdfDocument.open(input)) {
            prepare(source, options);
            PdfXStandard standard = options.standard();
            byte[] converted = PdfOutputIntent.applyPdfxMetadata(source.saveBytes(),
                    options.outputIntentProfile(),
                    PdfOutputIntent.Spec.pdfx(options.conditionIdentifier(), options.info()),
                    standard.gtsVersion(), standard.gtsConformance());
            try (PdfDocument metadata = PdfDocument.open(converted)) {
                applyPageBoxes(metadata);
                applyDocumentInfo(metadata, options);
                byte[] versioned = PdfVersionConverter.saveWithVersionToBytes(
                        metadata.rawHandle(), versionFor(standard));
                requireEmbeddedFonts(versioned, options);
                return versioned;
            }
        }
    }

    private static void prepare(PdfDocument doc, PdfXOptions options) {
        if (doc == null) {
            throw new JPDFiumException("PDF/X metadata application requires a document");
        }
        if (options == null) {
            throw new JPDFiumException("PDF/X metadata application requires options");
        }
        PdfXStandard standard = options.standard();
        if (!supports(standard)) {
            throw new JPDFiumException("PDF/X metadata is not supported for " + standard);
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
        requireNoTransparency(doc, standard);
        if (options.convertColors()) {
            PdfColorConverter.convert(doc, options.colorOptions());
        }
    }

    /**
     * Reject transparent page content for standards whose PDF version forbids
     * it. Package-private so the read-only probe can be tested without a
     * native CMYK build.
     */
    static void requireNoTransparency(PdfDocument doc, PdfXStandard standard) {
        if (standard.allowsTransparency()) return;
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                if (PdfPageObjects.hasTransparencyStrict(page.rawHandleForRead())) {
                    throw new JPDFiumException(standard + " forbids transparency;"
                            + " page " + i + " requires flattening");
                }
            }
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

    private static void requireSupported(PdfXOptions options) {
        if (options == null || !supports(options.standard())) {
            throw new JPDFiumException("PDF/X metadata is not supported for "
                    + (options == null ? "null" : options.standard()));
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
