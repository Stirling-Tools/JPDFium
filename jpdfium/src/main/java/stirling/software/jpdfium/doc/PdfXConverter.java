package stirling.software.jpdfium.doc;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.ColorBindings;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.PdfiumRuntime;
import stirling.software.jpdfium.panama.QpdfLib;

/**
 * PDF/X metadata bootstrap for PDF/X-1a, PDF/X-3, PDF/X-4 and PDF/X-6:
 * CMYK conversion, a GTS_PDFX output intent, XMP metadata, the standard base
 * PDF version and page trim boxes.
 *
 * <p>Colours are rewritten in the existing content streams, images keep their masks,
 * printable annotations are flattened, JavaScript is removed and missing fonts are
 * embedded from the system font directories unless {@code embedMissingFonts} is off.
 * PDF/X-1a and PDF/X-3 reject transparency, hidden optional content and JBIG2/JPX
 * images unless {@code flattenTransparency} is enabled. Signed documents are rejected,
 * and the conversion fails if the page count changes.
 *
 * <p>This is <strong>not a PDF/X validator</strong>. A font that cannot be found is
 * left unembedded (rejected with {@code requireEmbeddedFonts}), and trapping is not
 * applied. veraPDF has no PDF/X profiles,
 * so use a dedicated PDF/X preflight validator before claiming conformance;
 * {@link #checkMetadata(byte[], PdfXOptions)} only checks the intent/ICC/version
 * wiring written here.
 */
public final class PdfXConverter {

    private static final String PROP_MAX_FLATTEN_PAGE_PIXELS = "jpdfium.pdfx.maxFlattenPagePixels";
    private static final String PROP_MAX_FLATTEN_TOTAL_PIXELS = "jpdfium.pdfx.maxFlattenTotalPixels";
    private static final String PROP_MAX_FLATTEN_BYTES = "jpdfium.pdfx.maxFlattenBytes";
    private static final long DEFAULT_MAX_FLATTEN_PAGE_PIXELS = 64_000_000L;
    private static final long DEFAULT_MAX_FLATTEN_TOTAL_PIXELS = 512_000_000L;
    private static final long DEFAULT_MAX_FLATTEN_BYTES = 512L * 1024 * 1024;

    private PdfXConverter() {}

    /** True when the loaded native library can run the implemented metadata pipeline. */
    public static boolean isSupported() {
        return PdfOutputIntent.pdfxSupported()
                && ColorBindings.pdfxPrepareAvailable()
                && QpdfLib.isForceVersionFileSupported()
                && QpdfLib.isForceVersionSupported()
                && DocBindings.EPDF_SetMetaText != null
                && DocBindings.EPDF_GetMetaTrapped != null
                && DocBindings.EPDF_SetMetaTrapped != null;
    }

    /** True when {@code standard}'s intent/XMP metadata is wired into this build. */
    public static boolean supports(PdfXStandard standard) {
        return standard != null && standard.metadataSupported();
    }

    /** Convert colours and apply PDF/X intent metadata into a new document; {@code doc} is not modified. */
    public static PdfDocument applyMetadata(PdfDocument doc, PdfXOptions options) {
        if (doc == null) {
            throw new JPDFiumException("PDF/X metadata application requires a document");
        }
        if (doc.hasRawByteRangeMarker()) {
            throw new JPDFiumException("PDF/X conversion rewrites the document and would invalidate"
                    + " its digital signature; remove the signature first");
        }
        byte[] copyBytes = doc.saveBytes();
        requireNotSignedByRawScan(copyBytes);
        try (PdfDocument copy = PdfDocument.open(copyBytes)) {
            return applyMetadataToCopy(copy, options);
        }
    }

    private static PdfDocument applyMetadataToCopy(PdfDocument doc, PdfXOptions options) {
        int expectedPages = doc.pageCount();
        byte[] out = convertOpenDocument(doc, options);
        PdfDocument result = PdfDocument.open(out);
        try {
            requireSamePageCount(expectedPages, result.pageCount());
            return result;
        } catch (RuntimeException e) {
            result.close();
            throw e;
        }
    }

    /** Apply PDF/X metadata while converting a PDF file without holding it in Java bytes. */
    public static void applyMetadata(Path input, Path output, PdfXOptions options)
            throws IOException {
        if (input == null || output == null) {
            throw new IllegalArgumentException("input and output paths are required");
        }
        if (Files.isRegularFile(input)) {
            requireNotSignedByRawScan(input);
        }
        requireSupported(options);
        Path staging = null;
        Path stagedOut = null;
        try {
            Path parent = output.toAbsolutePath().getParent();
            Path stagingDir = parent != null ? parent : Path.of(".");
            staging = Files.createTempFile(stagingDir, "jpdfium-pdfx-", ".pdf");
            stagedOut = Files.createTempFile(stagingDir, "jpdfium-pdfx-out-", ".pdf");
            int pages;
            try (PdfDocument source = PdfDocument.open(input)) {
                pages = source.pageCount();
                prepare(source, options);
                applyDocumentInfo(source, options);
                applyPageBoxes(source);
                source.save(staging);
                requireNotSignedByRawScan(staging);
                nativePrepareFileWithFlatten(source, staging, options);
            }
            if (!options.fontData().isEmpty()) {
                applyFontData(staging, options);
            }
            PdfFontAuditor.FontInventorySnapshot verifiedFonts = embedMissingFonts(staging, options);
            requireEmbeddedFonts(staging, verifiedFonts, options);
            PdfXStandard standard = options.standard();
            if (!PdfOutputIntent.applyPdfxMetadata(staging, stagedOut,
                    options.outputIntentProfile(),
                    PdfOutputIntent.Spec.pdfx(options.conditionIdentifier(), options.info()),
                    standard.gtsVersion(), standard.gtsConformance())) {
                throw new JPDFiumException("PDF/X metadata application failed");
            }
            try (PdfDocument result = PdfDocument.open(stagedOut)) {
                requireSamePageCount(pages, result.pageCount());
            }
            try {
                Files.move(stagedOut, output,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(stagedOut, output, StandardCopyOption.REPLACE_EXISTING);
            }
            stagedOut = null;
        } finally {
            deleteQuietly(staging);
            deleteQuietly(stagedOut);
        }
    }

    private record FontStepResult(
            byte[] pdf, PdfFontAuditor.FontInventorySnapshot verifiedSnapshot) {}

    private static byte[] convertOpenDocument(PdfDocument source, PdfXOptions options) {
        prepare(source, options);
        applyDocumentInfo(source, options);
        applyPageBoxes(source);
        byte[] converted = nativePrepareWithFlatten(source, options);
        if (!options.fontData().isEmpty()) {
            converted = PdfFontEmbedder.embed(converted, options.fontData());
        }
        FontStepResult fontStep = embedMissingFonts(converted, options);
        requireEmbeddedFonts(fontStep.pdf(), fontStep.verifiedSnapshot(), options);
        PdfXStandard standard = options.standard();
        return PdfOutputIntent.applyPdfxMetadata(
                fontStep.pdf(),
                options.outputIntentProfile(),
                PdfOutputIntent.Spec.pdfx(options.conditionIdentifier(), options.info()),
                standard.gtsVersion(),
                standard.gtsConformance());
    }

    private static FontStepResult embedMissingFonts(byte[] pdf, PdfXOptions options) {
        if (!options.embedMissingFonts()) {
            return new FontStepResult(pdf, null);
        }
        if (QpdfLib.isFontInventorySupported()) {
            PdfFontAuditor.FontInventorySnapshot snapshot = PdfFontAuditor.inspectInventory(pdf);
            if (!snapshot.hasIssues()) {
                return new FontStepResult(pdf, snapshot);
            }
        }
        if (!PdfFontEmbedder.isAvailable()) {
            return new FontStepResult(pdf, null);
        }
        try {
            PdfFontEmbedder.Result emb = PdfFontEmbedder.embedMissing(
                    pdf, options.fontDirectories().toArray(Path[]::new));
            return new FontStepResult(emb.pdf(), null);
        } catch (IOException | JPDFiumException e) {
            if (options.requireEmbeddedFonts()) {
                throw new JPDFiumException("PDF/X font embedding failed: " + e.getMessage(), e);
            }
            return new FontStepResult(pdf, null);
        }
    }

    private static PdfFontAuditor.FontInventorySnapshot embedMissingFonts(
            Path pdf, PdfXOptions options) throws IOException {
        if (!options.embedMissingFonts()) {
            return null;
        }
        if (QpdfLib.isFileFontInventorySupported()) {
            PdfFontAuditor.FontInventorySnapshot snapshot = PdfFontAuditor.inspectInventory(pdf);
            if (!snapshot.hasIssues()) {
                return snapshot;
            }
        }
        if (!PdfFontEmbedder.isAvailable()) {
            return null;
        }
        try {
            PdfFontEmbedder.embedMissingTo(
                    pdf, pdf, options.fontDirectories().toArray(Path[]::new));
            return null;
        } catch (IOException | JPDFiumException e) {
            if (options.requireEmbeddedFonts()) {
                throw new JPDFiumException("PDF/X font embedding failed: " + e.getMessage(), e);
            }
            return null;
        }
    }

    private static boolean isSigned(PdfDocument doc) {
        int count = doc.signatureCount();
        for (int i = 0; i < count; i++) {
            if (doc.signatureDetails(i).byteRange() != null) return true;
        }
        return false;
    }

    /**
     * Fails closed on a raw {@code /ByteRange [...]} signature array anywhere in the bytes.
     * PDFium's signature enumeration only reports signatures it recognizes
     * (AcroForm signature fields); a dictionary it cannot see would let the
     * rewrite silently invalidate a real signature.
     */
    private static void requireNotSignedByRawScan(byte[] input) {
        if (PdfDocument.containsByteRangeArray(input, input.length)) {
            throw new JPDFiumException("PDF/X conversion rewrites the document and would invalidate"
                    + " its digital signature; remove the signature first");
        }
    }

    private static void requireNotSignedByRawScan(Path input) throws IOException {
        if (PdfDocument.containsByteRangeArray(input)) {
            throw new JPDFiumException("PDF/X conversion rewrites the document and would invalidate"
                    + " its digital signature; remove the signature first");
        }
    }

    private static void applyFontData(Path pdf, PdfXOptions options) throws IOException {
        byte[] withFonts = PdfFontEmbedder.embed(Files.readAllBytes(pdf), options.fontData());
        Files.write(pdf, withFonts);
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
        // PDF/X permits both /Trapped true and false. Preserve an existing true
        // claim and normalize only absent or unknown values.
        if (document.getTrapped() != DocBindings.PDFTRAPPED_TRUE) {
            document.setTrappedFalse();
        }
        PdfMetadata metadata = PdfMetadata.of(document.rawHandleForRead());
        if (options.standard() == PdfXStandard.PDF_X_1A || options.standard() == PdfXStandard.PDF_X_3) {
            document.setMetadata("GTS_PDFXVersion", options.standard().gtsVersion());
            if (options.standard().gtsConformance() != null) {
                document.setMetadata("GTS_PDFXConformance", options.standard().gtsConformance());
            }
            if ((options.title() == null || options.title().isBlank())
                    && metadata.title().isEmpty()) {
                throw new JPDFiumException("Title is required for " + options.standard().name());
            }
        }
        if (options.title() != null && !options.title().isBlank()) {
            document.setMetadata("Title", options.title());
        } else if ((options.standard() == PdfXStandard.PDF_X_4 || options.standard() == PdfXStandard.PDF_X_6)
                && metadata.title().isEmpty()) {
            document.setMetadata("Title", "Untitled");
        }
        String now = DateTimeFormatter.ofPattern("'D:'yyyyMMddHHmmss'+00''00'''")
                .format(Instant.now().atZone(ZoneOffset.UTC));
        if (metadata.creationDate().isEmpty()) {
            document.setMetadata("CreationDate", now);
        }
        if (metadata.modDate().isEmpty()) {
            document.setMetadata("ModDate", now);
        }
    }

    private static void requireSamePageCount(int expected, int actual) {
        if (expected != actual) {
            throw new JPDFiumException("PDF/X conversion produced " + actual + " pages from "
                    + expected + "; the source page tree could not be rewritten safely");
        }
    }

    private static Rect clampToMediaBox(Rect box, Rect media) {
        float mx0 = Math.min(media.x(), media.x() + media.width());
        float my0 = Math.min(media.y(), media.y() + media.height());
        float mx1 = Math.max(media.x(), media.x() + media.width());
        float my1 = Math.max(media.y(), media.y() + media.height());
        float bx0 = Math.min(box.x(), box.x() + box.width());
        float by0 = Math.min(box.y(), box.y() + box.height());
        float bx1 = Math.max(box.x(), box.x() + box.width());
        float by1 = Math.max(box.y(), box.y() + box.height());
        float nx0 = Math.max(mx0, Math.min(mx1, bx0));
        float ny0 = Math.max(my0, Math.min(my1, by0));
        float nx1 = Math.max(mx0, Math.min(mx1, bx1));
        float ny1 = Math.max(my0, Math.min(my1, by1));
        if (nx1 <= nx0 || ny1 <= ny0) {
            return new Rect(mx0, my0, mx1 - mx0, my1 - my0);
        }
        return new Rect(nx0, ny0, nx1 - nx0, ny1 - ny0);
    }

    private static boolean rectDiffers(Rect a, Rect b) {
        return Math.abs(a.x() - b.x()) > 1e-4f
                || Math.abs(a.y() - b.y()) > 1e-4f
                || Math.abs(a.width() - b.width()) > 1e-4f
                || Math.abs(a.height() - b.height()) > 1e-4f;
    }

    private static void applyPageBoxes(PdfDocument document) {
        for (int i = 0; i < document.pageCount(); i++) {
            try (PdfPage page = document.page(i)) {
                Rect media = page.getMediaBox();
                page.getCropBox()
                        .ifPresent(
                                box -> {
                                    Rect clamped = clampToMediaBox(box, media);
                                    if (rectDiffers(box, clamped)) {
                                        page.setCropBox(clamped);
                                    }
                                });
                page.getBleedBox()
                        .ifPresent(
                                box -> {
                                    Rect clamped = clampToMediaBox(box, media);
                                    if (rectDiffers(box, clamped)) {
                                        page.setBleedBox(clamped);
                                    }
                                });
                page.getTrimBox()
                        .ifPresent(
                                box -> {
                                    Rect clamped = clampToMediaBox(box, media);
                                    if (rectDiffers(box, clamped)) {
                                        page.setTrimBox(clamped);
                                    }
                                });
                page.getArtBox()
                        .ifPresent(
                                box -> {
                                    Rect clamped = clampToMediaBox(box, media);
                                    if (rectDiffers(box, clamped)) {
                                        page.setArtBox(clamped);
                                    }
                                });
                // ISO 15930 forbids having both TrimBox and ArtBox.
                if (page.getTrimBox().isPresent() || page.getArtBox().isPresent()) {
                    continue;
                }
                Rect fallback = page.getCropBox().orElse(media);
                page.setTrimBox(clampToMediaBox(fallback, media));
            }
        }
    }

    private static void requireEmbeddedFonts(
            byte[] pdf,
            PdfFontAuditor.FontInventorySnapshot verifiedSnapshot,
            PdfXOptions options) {
        if (!options.requireEmbeddedFonts()) return;
        if (verifiedSnapshot != null) {
            if (!verifiedSnapshot.unembeddedResources().isEmpty()) {
                throw new JPDFiumException(
                        "PDF/X requires embedded fonts; conversion left a non-embedded font");
            }
            return;
        }
        if (QpdfLib.isFontInventorySupported()) {
            PdfFontAuditor.FontInventorySnapshot snapshot = PdfFontAuditor.inspectInventory(pdf);
            if (!snapshot.unembeddedResources().isEmpty()) {
                throw new JPDFiumException(
                        "PDF/X requires embedded fonts; conversion left a non-embedded font");
            }
            return;
        }
        try (PdfDocument metadata = PdfDocument.open(pdf)) {
            if (PdfFontAuditor.hasNonEmbeddedFonts(metadata)) {
                throw new JPDFiumException(
                        "PDF/X requires embedded fonts; conversion left a non-embedded font");
            }
        }
    }

    private static void requireEmbeddedFonts(
            Path pdf,
            PdfFontAuditor.FontInventorySnapshot verifiedSnapshot,
            PdfXOptions options) {
        if (!options.requireEmbeddedFonts()) return;
        if (verifiedSnapshot != null) {
            if (!verifiedSnapshot.unembeddedResources().isEmpty()) {
                throw new JPDFiumException(
                        "PDF/X requires embedded fonts; conversion left a non-embedded font");
            }
            return;
        }
        if (QpdfLib.isFileFontInventorySupported()) {
            PdfFontAuditor.FontInventorySnapshot snapshot = PdfFontAuditor.inspectInventory(pdf);
            if (!snapshot.unembeddedResources().isEmpty()) {
                throw new JPDFiumException(
                        "PDF/X requires embedded fonts; conversion left a non-embedded font");
            }
            return;
        }
        try (PdfDocument document = PdfDocument.open(pdf)) {
            if (PdfFontAuditor.hasNonEmbeddedFonts(document)) {
                throw new JPDFiumException(
                        "PDF/X requires embedded fonts; conversion left a non-embedded font");
            }
        }
    }

    /** Apply PDF/X metadata and return the new PDF bytes. */
    public static byte[] applyMetadata(byte[] input, PdfXOptions options) {
        if (input == null || input.length == 0) {
            throw new JPDFiumException("PDF/X metadata requires non-empty PDF bytes");
        }
        requireNotSignedByRawScan(input);
        requireSupported(options);
        int expectedPages;
        byte[] out;
        try (PdfDocument source = PdfDocument.open(input)) {
            expectedPages = source.pageCount();
            out = convertOpenDocument(source, options);
        }
        try (PdfDocument result = PdfDocument.open(out)) {
            requireSamePageCount(expectedPages, result.pageCount());
        }
        return out;
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
            throw new JPDFiumException("PDF/X conversion requires the native PDF/X preparation pass"
                    + " and the QPDF PDF/X metadata bootstrap");
        }
        byte[] profile = options.outputIntentProfile();
        if (profile == null || profile.length == 0) {
            throw new JPDFiumException("PDF/X metadata requires an output intent ICC profile");
        }
        validateCmykProfile(profile);
        if (isSigned(doc)) {
            throw new JPDFiumException("PDF/X conversion rewrites the document and would invalidate"
                    + " its digital signature; remove the signature first");
        }
        if (!options.flattenTransparency()) {
            requireNoTransparency(doc, standard);
        }
        if (!standard.allowsIccBasedColors() && !options.convertColors()) {
            throw new JPDFiumException(standard + " forbids ICC-based, Lab, and RGB colour spaces; "
                    + "colour conversion cannot be disabled");
        }
    }

    private static byte[] nativePrepareWithFlatten(PdfDocument source, PdfXOptions options) {
        byte[] sourceBytes = source.saveBytes();
        requireNotSignedByRawScan(sourceBytes);
        if (!options.flattenTransparency() || options.standard().allowsTransparency()) {
            return nativePrepare(sourceBytes, options);
        }
        try {
            return nativePrepare(sourceBytes, options);
        } catch (JPDFiumException e) {
            BitSet toFlatten = parseIncompatiblePages(e.getMessage(), source.pageCount());
            if (toFlatten.isEmpty()) {
                throw e;
            }
            byte[] flattened = flattenSelectedPages(source, toFlatten, options);
            return nativePrepare(flattened, options);
        }
    }

    private static void nativePrepareFileWithFlatten(
            PdfDocument source, Path staging, PdfXOptions options) throws IOException {
        if (!options.flattenTransparency() || options.standard().allowsTransparency()) {
            nativePrepare(staging, options);
            return;
        }
        try {
            nativePrepare(staging, options);
        } catch (JPDFiumException e) {
            BitSet toFlatten = parseIncompatiblePages(e.getMessage(), source.pageCount());
            if (toFlatten.isEmpty()) {
                throw e;
            }
            byte[] flattened = flattenSelectedPages(source, toFlatten, options);
            Files.write(staging, flattened);
            nativePrepare(staging, options);
        }
    }

    private static BitSet parseIncompatiblePages(String msg, int pageCount) {
        BitSet pages = new BitSet();
        if (msg == null) return pages;
        int start = msg.indexOf("[pages:");
        if (start < 0) return pages;
        int end = msg.indexOf(']', start);
        if (end < 0) return pages;
        String list = msg.substring(start + 7, end);
        for (String tok : list.split(",")) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            int dash = t.indexOf('-');
            try {
                if (dash > 0) {
                    int lo = Integer.parseInt(t.substring(0, dash).trim()) - 1;
                    int hi = Integer.parseInt(t.substring(dash + 1).trim()) - 1;
                    if (lo > hi) {
                        int tmp = lo;
                        lo = hi;
                        hi = tmp;
                    }
                    int startPage = Math.max(0, lo);
                    int endPage = Math.min(pageCount - 1, hi);
                    for (int p = startPage; p <= endPage; p++) {
                        pages.set(p);
                    }
                } else {
                    int p = Integer.parseInt(t) - 1;
                    if (p >= 0 && p < pageCount) {
                        pages.set(p);
                    }
                }
            } catch (NumberFormatException ignored) {
                // Skip malformed token.
            }
        }
        return pages;
    }

    private static long configCap(String property, long defaultValue) {
        long value = Long.getLong(property, defaultValue);
        if (value < 0) {
            throw new IllegalArgumentException(property + " must be >= 0 (was " + value + ")");
        }
        return value;
    }

    private static byte[] flattenSelectedPages(PdfDocument source, BitSet toFlatten, PdfXOptions options) {
        int count = source.pageCount();
        int dpi = options.flattenDpi();
        long maxPagePixels = configCap(PROP_MAX_FLATTEN_PAGE_PIXELS, DEFAULT_MAX_FLATTEN_PAGE_PIXELS);
        long maxTotalPixels = configCap(PROP_MAX_FLATTEN_TOTAL_PIXELS, DEFAULT_MAX_FLATTEN_TOTAL_PIXELS);
        long maxFlattenBytes = configCap(PROP_MAX_FLATTEN_BYTES, DEFAULT_MAX_FLATTEN_BYTES);
        long totalPixels = 0L;
        long totalJpegBytes = 0L;
        try (PdfDocument out = PdfDocument.open(source.saveBytes())) {
            for (int i = 0; i < count; i++) {
                if (!toFlatten.get(i)) {
                    continue;
                }
                PageSize visualSize;
                BufferedImage img;
                String pageText;
                try (PdfPage page = source.page(i)) {
                    visualSize = page.size();
                    long lw = Math.max(1L,
                            (long) Math.floor(Math.abs(visualSize.width()) * (double) dpi / 72.0));
                    long lh = Math.max(1L,
                            (long) Math.floor(Math.abs(visualSize.height()) * (double) dpi / 72.0));
                    long pagePixels;
                    try {
                        pagePixels = Math.multiplyExact(lw, lh);
                    } catch (ArithmeticException overflow) {
                        throw new JPDFiumException("Flattened page " + (i + 1)
                                + " pixel dimensions overflow: " + lw + "x" + lh, overflow);
                    }
                    if (maxPagePixels > 0 && pagePixels > maxPagePixels) {
                        throw new JPDFiumException("Flattened page " + (i + 1) + " requires "
                                + pagePixels + " pixels (" + lw + "x" + lh + " at " + dpi
                                + " dpi), exceeding " + PROP_MAX_FLATTEN_PAGE_PIXELS
                                + "=" + maxPagePixels);
                    }
                    if (maxTotalPixels > 0 && pagePixels > maxTotalPixels - totalPixels) {
                        throw new JPDFiumException("Cumulative flattened pixel budget exceeded at page "
                                + (i + 1) + " (" + PROP_MAX_FLATTEN_TOTAL_PIXELS
                                + "=" + maxTotalPixels + ")");
                    }
                    totalPixels += pagePixels;
                    pageText = page.extractText();
                    img = page.renderImage(dpi, false);
                }
                float w = Math.max(1f, Math.abs(visualSize.width()));
                float h = Math.max(1f, Math.abs(visualSize.height()));
                byte[] singlePagePdf =
                        buildOpaqueJpegPagePdf(img, w, h, options.jpegQuality(), pageText);
                if (maxFlattenBytes > 0 && singlePagePdf.length > maxFlattenBytes - totalJpegBytes) {
                    throw new JPDFiumException("Cumulative flattened PDF byte budget exceeded at page "
                            + (i + 1) + " (" + PROP_MAX_FLATTEN_BYTES + "=" + maxFlattenBytes + ")");
                }
                totalJpegBytes += singlePagePdf.length;
                try (PdfDocument imgDoc = PdfDocument.open(singlePagePdf)) {
                    PdfPageImporter.importPagesByIndex(
                            out.rawHandle(), imgDoc.rawHandleForRead(), new int[]{0}, i);
                    final int oldPageIndex = i + 1;
                    PdfiumRuntime.execute(() -> PdfPageEditor.deletePage(out.rawHandle(), oldPageIndex));
                }
            }
            applyPageBoxes(out);
            PdfMetadata srcMeta = PdfMetadata.of(source.rawHandleForRead());
            srcMeta.title().ifPresent(v -> out.setMetadata("Title", v));
            srcMeta.creator().ifPresent(v -> out.setMetadata("Creator", v));
            srcMeta.producer().ifPresent(v -> out.setMetadata("Producer", v));
            srcMeta.creationDate().ifPresent(v -> out.setMetadata("CreationDate", v));
            srcMeta.modDate().ifPresent(v -> out.setMetadata("ModDate", v));
            applyDocumentInfo(out, options);
            return out.saveBytes();
        }
    }

    private static byte[] buildOpaqueJpegPagePdf(
            BufferedImage rgb, float pageW, float pageH, int quality, String pageText) {
        byte[] jpeg;
        try {
            ByteArrayOutputStream jb = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                throw new JPDFiumException("No JPEG ImageWriter available");
            }
            ImageWriter writer = writers.next();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(jb)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(Math.clamp(quality / 100f, 0.1f, 1.0f));
                }
                writer.write(null, new IIOImage(rgb, null, null), param);
            } finally {
                writer.dispose();
            }
            jpeg = jb.toByteArray();
        } catch (IOException e) {
            throw new JPDFiumException("Failed to encode flattened PDF/X page image: " + e.getMessage(), e);
        }

        // Preserve extractable invisible text (3 Tr) via an embedded synthetic Type0 font.
        Map<Integer, Integer> cpToCid = new LinkedHashMap<>();
        List<int[]> lines = extractInvisibleTextLines(pageText, cpToCid);
        boolean hasText = !lines.isEmpty() && !cpToCid.isEmpty();

        String wStr = String.format(Locale.ROOT, "%.4f", pageW);
        String hStr = String.format(Locale.ROOT, "%.4f", pageH);
        StringBuilder cs = new StringBuilder(256 + lines.size() * 64);
        cs.append("q ").append(wStr).append(" 0 0 ").append(hStr).append(" 0 0 cm /Im0 Do Q\n");
        if (hasText) {
            float lineStep = Math.min(10f, Math.max(0.1f, (pageH - 16f) / Math.max(1, lines.size())));
            float fontSize = Math.min(8f, Math.max(0.08f, lineStep * 0.85f));
            cs.append("BT\n/F0 ").append(String.format(Locale.ROOT, "%.4f", fontSize)).append(" Tf\n3 Tr\n");
            for (int li = 0; li < lines.size(); li++) {
                int[] cps = lines.get(li);
                if (cps.length == 0) continue;
                float y = Math.max(2f, pageH - 10f - li * lineStep);
                cs.append("1 0 0 1 10 ").append(String.format(Locale.ROOT, "%.4f", y)).append(" Tm\n<");
                for (int cp : cps) {
                    int cid = cpToCid.getOrDefault(cp, 1);
                    cs.append(String.format(Locale.ROOT, "%04X", cid));
                }
                cs.append("> Tj\n");
            }
            cs.append("ET\n");
        }
        byte[] content = cs.toString().getBytes(StandardCharsets.US_ASCII);

        int objCount = hasText ? 12 : 6;
        ByteArrayOutputStream pdf = new ByteArrayOutputStream(jpeg.length + content.length + 4096);
        int[] off = new int[objCount];
        try {
            pdf.write("%PDF-1.3\n%\u00E2\u00E3\u00CF\u00D3\n".getBytes(StandardCharsets.ISO_8859_1));
            off[1] = pdf.size();
            pdf.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            off[2] = pdf.size();
            pdf.write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            off[3] = pdf.size();
            String fontRes = hasText ? " /Font << /F0 6 0 R >>" : "";
            pdf.write(("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + wStr + " " + hStr
                    + "] /Resources << /XObject << /Im0 4 0 R >>" + fontRes
                    + " >> /Contents 5 0 R >>\nendobj\n").getBytes(StandardCharsets.US_ASCII));
            off[4] = pdf.size();
            pdf.write(("4 0 obj\n<< /Type /XObject /Subtype /Image /Width " + rgb.getWidth()
                    + " /Height " + rgb.getHeight()
                    + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "
                    + jpeg.length + " >>\nstream\n").getBytes(StandardCharsets.US_ASCII));
            pdf.write(jpeg);
            pdf.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            off[5] = pdf.size();
            pdf.write(("5 0 obj\n<< /Length " + content.length + " >>\nstream\n")
                    .getBytes(StandardCharsets.US_ASCII));
            pdf.write(content);
            pdf.write("endstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            if (hasText) {
                int maxCid = cpToCid.size();
                byte[] ttf = InvisibleTtfHolder.BYTES;
                byte[] toUnicode = buildToUnicodeCmap(cpToCid);
                byte[] cidToGid = new byte[(maxCid + 1) * 2];
                for (int c = 1; c <= maxCid; c++) {
                    cidToGid[c * 2] = 0;
                    cidToGid[c * 2 + 1] = 1;
                }

                off[6] = pdf.size();
                pdf.write(("6 0 obj\n<< /Type /Font /Subtype /Type0 /BaseFont /JpdfiumInvisibleOCR"
                        + " /Encoding /Identity-H /DescendantFonts [7 0 R] /ToUnicode 10 0 R >>\nendobj\n")
                        .getBytes(StandardCharsets.US_ASCII));
                off[7] = pdf.size();
                pdf.write(("7 0 obj\n<< /Type /Font /Subtype /CIDFontType2 /BaseFont /JpdfiumInvisibleOCR"
                        + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >>"
                        + " /FontDescriptor 8 0 R /DW 500 /W [0 " + maxCid
                        + " 500] /CIDToGIDMap 11 0 R >>\nendobj\n").getBytes(StandardCharsets.US_ASCII));
                off[8] = pdf.size();
                pdf.write(("8 0 obj\n<< /Type /FontDescriptor /FontName /JpdfiumInvisibleOCR"
                        + " /Flags 4 /FontBBox [0 -200 500 800] /ItalicAngle 0 /Ascent 800"
                        + " /Descent -200 /CapHeight 700 /StemV 80 /FontFile2 9 0 R >>\nendobj\n")
                        .getBytes(StandardCharsets.US_ASCII));
                off[9] = pdf.size();
                pdf.write(("9 0 obj\n<< /Length1 " + ttf.length + " /Length " + ttf.length
                        + " >>\nstream\n").getBytes(StandardCharsets.US_ASCII));
                pdf.write(ttf);
                pdf.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
                off[10] = pdf.size();
                pdf.write(("10 0 obj\n<< /Length " + toUnicode.length + " >>\nstream\n")
                        .getBytes(StandardCharsets.US_ASCII));
                pdf.write(toUnicode);
                pdf.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
                off[11] = pdf.size();
                pdf.write(("11 0 obj\n<< /Length " + cidToGid.length + " >>\nstream\n")
                        .getBytes(StandardCharsets.US_ASCII));
                pdf.write(cidToGid);
                pdf.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            }
            int xref = pdf.size();
            pdf.write(("xref\n0 " + objCount + "\n0000000000 65535 f \n")
                    .getBytes(StandardCharsets.US_ASCII));
            for (int i = 1; i < objCount; i++) {
                pdf.write((String.format(Locale.ROOT, "%010d 00000 n ", off[i]) + "\n")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            pdf.write(("trailer\n<< /Size " + objCount + " /Root 1 0 R >>\nstartxref\n"
                    + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new JPDFiumException("Failed to build flattened page PDF: " + e.getMessage(), e);
        }
        return pdf.toByteArray();
    }

    private static List<int[]> extractInvisibleTextLines(
            String text, Map<Integer, Integer> cpToCid) {
        if (text == null || text.isBlank()) return List.of();
        List<int[]> lines = new ArrayList<>();
        List<Integer> cur = new ArrayList<>();
        int n = text.length();
        for (int i = 0; i < n; ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r') {
                if (i < n && text.charAt(i) == '\n') i++;
                if (!cur.isEmpty()) {
                    lines.add(cur.stream().mapToInt(Integer::intValue).toArray());
                    cur.clear();
                }
                continue;
            }
            if (cp == '\n') {
                if (!cur.isEmpty()) {
                    lines.add(cur.stream().mapToInt(Integer::intValue).toArray());
                    cur.clear();
                }
                continue;
            }
            if (cp == 0 || Character.isSurrogate((char) Math.min(cp, 0xFFFF))) {
                continue;
            }
            if (cp == '\t') cp = ' ';
            Integer cid = cpToCid.get(cp);
            if (cid == null) {
                if (cpToCid.size() >= 65535) continue;
                cid = cpToCid.size() + 1;
                cpToCid.put(cp, cid);
            }
            cur.add(cp);
        }
        if (!cur.isEmpty()) {
            lines.add(cur.stream().mapToInt(Integer::intValue).toArray());
        }
        return lines;
    }

    private static byte[] buildToUnicodeCmap(Map<Integer, Integer> cpToCid) {
        StringBuilder sb = new StringBuilder(256 + cpToCid.size() * 20);
        sb.append("/CIDInit /ProcSet findresource begin\n"
                + "12 dict begin\n"
                + "begincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def\n"
                + "/CMapType 2 def\n"
                + "1 begincodespacerange\n"
                + "<0000> <FFFF>\n"
                + "endcodespacerange\n");
        List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(cpToCid.entrySet());
        for (int i = 0; i < entries.size(); i += 100) {
            int end = Math.min(entries.size(), i + 100);
            sb.append(end - i).append(" beginbfchar\n");
            for (int k = i; k < end; k++) {
                int cp = entries.get(k).getKey();
                int cid = entries.get(k).getValue();
                sb.append(String.format(Locale.ROOT, "<%04X> ", cid));
                if (cp <= 0xFFFF) {
                    sb.append(String.format(Locale.ROOT, "<%04X>", cp)).append('\n');
                } else {
                    int hi = Character.highSurrogate(cp);
                    int lo = Character.lowSurrogate(cp);
                    sb.append(String.format(Locale.ROOT, "<%04X%04X>", hi, lo)).append('\n');
                }
            }
            sb.append("endbfchar\n");
        }
        sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static final class InvisibleTtfHolder {
        static final byte[] BYTES = buildMinimalTtf();

        private static byte[] buildMinimalTtf() {
            // 10-table minimal TrueType font (2 empty glyphs: GID 0 .notdef, GID 1 invisible advance 500)
            byte[] os2 = new byte[86];
            putU16(os2, 0, 1);      // version 1
            putI16(os2, 2, 500);    // xAvgCharWidth
            putU16(os2, 4, 400);    // usWeightClass
            putU16(os2, 6, 5);      // usWidthClass
            putI16(os2, 68, 800);   // sTypoAscender
            putI16(os2, 70, -200);  // sTypoDescender
            putU16(os2, 74, 800);   // usWinAscent
            putU16(os2, 76, 200);   // usWinDescent

            byte[] cmap = new byte[36];
            putU16(cmap, 0, 0);     // version
            putU16(cmap, 2, 1);     // numTables
            putU16(cmap, 4, 3);     // platformID 3 (Windows)
            putU16(cmap, 6, 1);     // encodingID 1 (Unicode BMP)
            putU32(cmap, 8, 12);    // offset to subtable
            putU16(cmap, 12, 4);    // format 4
            putU16(cmap, 14, 24);   // length
            putU16(cmap, 18, 4);    // segCountX2 = 4 (2 segments: 0x0020 and 0xFFFF)
            putU16(cmap, 20, 4);    // searchRange
            putU16(cmap, 22, 1);    // entrySelector
            putU16(cmap, 24, 0);    // rangeShift
            putU16(cmap, 26, 0x0020);
            putU16(cmap, 28, 0xFFFF);
            putU16(cmap, 30, 0);    // reservedPad
            putU16(cmap, 32, 0x0020);
            putU16(cmap, 34, 0xFFFF);
            // Resize cmap to hold idDelta and idRangeOffset (total 44 bytes: 12 + 32)
            byte[] cmapFull = new byte[44];
            System.arraycopy(cmap, 0, cmapFull, 0, 36);
            putU16(cmapFull, 14, 32); // subtable length = 32
            putI16(cmapFull, 36, 1 - 0x0020); // idDelta[0]: 0x0020 -> GID 1
            putI16(cmapFull, 38, 1);          // idDelta[1]: 0xFFFF -> GID 0
            putU16(cmapFull, 40, 0);          // idRangeOffset[0]
            putU16(cmapFull, 42, 0);          // idRangeOffset[1]

            // GID 0 (.notdef) is empty; GID 1 has a 1-contour non-zero bbox so FPDFText preserves
            // 1-char lines and consecutive identical lines while 3 Tr keeps rendering invisible.
            byte[] glyf = new byte[32];
            putI16(glyf, 0, 1);     // numberOfContours = 1
            putI16(glyf, 2, 0);     // xMin
            putI16(glyf, 4, 0);     // yMin
            putI16(glyf, 6, 500);   // xMax
            putI16(glyf, 8, 700);   // yMax
            putU16(glyf, 10, 2);    // endPtsOfContours[0] = 2 (3 points)
            putU16(glyf, 12, 0);    // instructionLength = 0
            glyf[14] = 1;           // point 0: on-curve, 16-bit dx/dy
            glyf[15] = 1;           // point 1: on-curve, 16-bit dx/dy
            glyf[16] = 1;           // point 2: on-curve, 16-bit dx/dy
            putI16(glyf, 17, 0);    // dx[0] = 0 -> x0 = 0
            putI16(glyf, 19, 500);  // dx[1] = 500 -> x1 = 500
            putI16(glyf, 21, -500); // dx[2] = -500 -> x2 = 0
            putI16(glyf, 23, 0);    // dy[0] = 0 -> y0 = 0
            putI16(glyf, 25, 0);    // dy[1] = 0 -> y1 = 0
            putI16(glyf, 27, 700);  // dy[2] = 700 -> y2 = 700

            byte[] head = new byte[54];
            putU32(head, 0, 0x00010000);
            putU32(head, 4, 0x00010000);
            putU32(head, 12, 0x5F0F3CF5);
            putU16(head, 16, 3);
            putU16(head, 18, 1000);
            putI16(head, 36, 0);
            putI16(head, 38, -200);
            putI16(head, 40, 500);
            putI16(head, 42, 800);
            putU16(head, 46, 8);
            putI16(head, 48, 2);
            putI16(head, 50, 0);

            byte[] hhea = new byte[36];
            putU32(hhea, 0, 0x00010000);
            putI16(hhea, 4, 800);
            putI16(hhea, 6, -200);
            putU16(hhea, 10, 500);
            putI16(hhea, 16, 500);
            putI16(hhea, 18, 1);
            putU16(hhea, 34, 2);

            byte[] hmtx = new byte[8];
            putU16(hmtx, 0, 500);
            putU16(hmtx, 4, 500);

            byte[] loca = new byte[6];
            putU16(loca, 4, 16); // GID 1 spans [0, 32) in glyf

            byte[] maxp = new byte[32];
            putU32(maxp, 0, 0x00010000);
            putU16(maxp, 4, 2);
            putU16(maxp, 6, 3); // maxPoints
            putU16(maxp, 8, 1); // maxContours

            byte[] nameBytes = "JpdfiumInvisibleOCR".getBytes(StandardCharsets.UTF_16BE);
            byte[] name = new byte[6 + 36 + nameBytes.length];
            putU16(name, 2, 3); // 3 records (nameID 1, 4, 6)
            putU16(name, 4, 6 + 36);
            int[] nameIds = {1, 4, 6};
            for (int r = 0; r < 3; r++) {
                int rec = 6 + r * 12;
                putU16(name, rec, 3);
                putU16(name, rec + 2, 1);
                putU16(name, rec + 4, 0x0409);
                putU16(name, rec + 6, nameIds[r]);
                putU16(name, rec + 8, nameBytes.length);
                putU16(name, rec + 10, 0);
            }
            System.arraycopy(nameBytes, 0, name, 6 + 36, nameBytes.length);

            byte[] post = new byte[32];
            putU32(post, 0, 0x00030000);
            putI16(post, 8, -100);
            putI16(post, 10, 50);
            putU32(post, 12, 1);

            String[] tags = {"OS/2", "cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name", "post"};
            byte[][] data = {os2, cmapFull, glyf, head, hhea, hmtx, loca, maxp, name, post};
            int headerSize = 12 + tags.length * 16;
            int totalSize = headerSize;
            for (byte[] t : data) totalSize += (t.length + 3) & ~3;

            byte[] out = new byte[totalSize];
            putU32(out, 0, 0x00010000);
            putU16(out, 4, tags.length);
            putU16(out, 6, 128);
            putU16(out, 8, 3);
            putU16(out, 10, tags.length * 16 - 128);

            int cur = headerSize;
            int headOff = 0;
            for (int i = 0; i < tags.length; i++) {
                byte[] tagBytes = tags[i].getBytes(StandardCharsets.US_ASCII);
                int dir = 12 + i * 16;
                System.arraycopy(tagBytes, 0, out, dir, 4);
                int csum = tableChecksum(data[i]);
                putU32(out, dir + 4, csum);
                putU32(out, dir + 8, cur);
                putU32(out, dir + 12, data[i].length);
                System.arraycopy(data[i], 0, out, cur, data[i].length);
                if ("head".equals(tags[i])) headOff = cur;
                cur += (data[i].length + 3) & ~3;
            }
            int whole = tableChecksum(out);
            putU32(out, headOff + 8, 0xB1B0AFBA - whole);
            return out;
        }

        private static int tableChecksum(byte[] b) {
            int sum = 0;
            int n = b.length;
            for (int i = 0; i < n; i += 4) {
                int v = ((b[i] & 0xFF) << 24)
                        | ((i + 1 < n ? b[i + 1] & 0xFF : 0) << 16)
                        | ((i + 2 < n ? b[i + 2] & 0xFF : 0) << 8)
                        | (i + 3 < n ? b[i + 3] & 0xFF : 0);
                sum += v;
            }
            return sum;
        }

        private static void putU16(byte[] b, int o, int v) {
            b[o] = (byte) (v >>> 8);
            b[o + 1] = (byte) v;
        }

        private static void putI16(byte[] b, int o, int v) {
            putU16(b, o, v);
        }

        private static void putU32(byte[] b, int o, int v) {
            b[o] = (byte) (v >>> 24);
            b[o + 1] = (byte) (v >>> 16);
            b[o + 2] = (byte) (v >>> 8);
            b[o + 3] = (byte) v;
        }
    }

    private static byte[] nativePrepare(byte[] pdf, PdfXOptions options) {
        IccCmykTransform xform = IccCmykTransform.cached(options.outputIntentProfile());
        return ColorBindings.pdfxPrepareBytesSegment(pdf, nativeLevel(options.standard()),
                xform.lutSegment(), IccCmykTransform.lutGrid(), nativeFlags(options),
                options.jpegQuality());
    }

    private static void nativePrepare(Path pdf, PdfXOptions options) {
        IccCmykTransform xform = IccCmykTransform.cached(options.outputIntentProfile());
        ColorBindings.pdfxPrepareFileSegment(pdf.toString(), pdf.toString(),
                nativeLevel(options.standard()), xform.lutSegment(), IccCmykTransform.lutGrid(),
                nativeFlags(options), options.jpegQuality());
    }

    private static int nativeLevel(PdfXStandard standard) {
        return switch (standard) {
            case PDF_X_1A -> 1;
            case PDF_X_3 -> 3;
            case PDF_X_4 -> 4;
            case PDF_X_6 -> 6;
        };
    }

    private static int nativeFlags(PdfXOptions options) {
        int flags = ColorBindings.PDFX_CONVERT_SPECIAL;
        if (options.convertColors()) flags |= ColorBindings.PDFX_CONVERT_COLOURS;
        if (options.convertImages()) flags |= ColorBindings.PDFX_CONVERT_IMAGES;
        if (options.neutralsToBlack()) flags |= ColorBindings.PDFX_NEUTRALS_TO_BLACK;
        if (options.strictImages()) flags |= ColorBindings.PDFX_STRICT_IMAGES;
        if (options.flattenTransparency()) flags |= ColorBindings.PDFX_ALLOW_STRIP_OC;
        return flags;
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
