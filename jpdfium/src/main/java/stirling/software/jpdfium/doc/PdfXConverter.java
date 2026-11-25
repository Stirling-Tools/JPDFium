package stirling.software.jpdfium.doc;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.BitSet;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.fonts.PdfFontRewriteOptions;
import stirling.software.jpdfium.fonts.PdfFontRewriter;
import stirling.software.jpdfium.model.PdfVersion;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.RenderResult;
import stirling.software.jpdfium.panama.ColorBindings;
import stirling.software.jpdfium.panama.DocBindings;
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
 * images rather than flattening them. Signed documents are rejected, and the
 * conversion fails if the page count changes.
 *
 * <p>This is <strong>not a PDF/X validator</strong>. A font that cannot be found is
 * left unembedded (rejected with {@code requireEmbeddedFonts}), and trapping is not
 * applied. veraPDF has no PDF/X profiles,
 * so use a PDF/X preflight such as Acrobat or callas before claiming conformance;
 * {@link #checkMetadata(byte[], PdfXOptions)} only checks the intent/ICC/version
 * wiring written here.
 */
public final class PdfXConverter {

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
        try (PdfDocument copy = PdfDocument.open(doc.saveBytes())) {
            return applyMetadataToCopy(copy, options);
        }
    }

    private static PdfDocument applyMetadataToCopy(PdfDocument doc, PdfXOptions options) {
        prepare(doc, options);
        applyDocumentInfo(doc, options);
        PdfXStandard standard = options.standard();
        byte[] converted = PdfOutputIntent.applyPdfxMetadata(nativePrepareWithFlatten(doc, options),
                options.outputIntentProfile(),
                PdfOutputIntent.Spec.pdfx(options.conditionIdentifier(), options.info()),
                standard.gtsVersion(), standard.gtsConformance());
        if (!options.fontData().isEmpty()) {
            converted = PdfFontEmbedder.embed(converted, options.fontData());
        }
        converted = embedMissingFonts(converted, options);
        try (PdfDocument metadata = PdfDocument.open(converted)) {
            requireSamePageCount(doc.pageCount(), metadata.pageCount());
            applyPageBoxes(metadata);
            requireEmbeddedFonts(metadata, options);
            byte[] out = PdfVersionConverter.forceVersion(
                    metadata.saveBytes(), versionFor(standard));
            return PdfDocument.open(out);
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
            int pages;
            try (PdfDocument source = PdfDocument.open(input)) {
                pages = source.pageCount();
                prepare(source, options);
                applyDocumentInfo(source, options);
                source.save(staging);
            }
            nativePrepare(staging, options);
            PdfXStandard standard = options.standard();
            if (!PdfOutputIntent.applyPdfxMetadata(staging, metadata,
                    options.outputIntentProfile(),
                    PdfOutputIntent.Spec.pdfx(options.conditionIdentifier(), options.info()),
                    standard.gtsVersion(), standard.gtsConformance())) {
                throw new JPDFiumException("PDF/X metadata application failed");
            }
            if (!options.fontData().isEmpty()) {
                applyFontData(metadata, options);
            }
            embedMissingFonts(metadata, options);
            try (PdfDocument document = PdfDocument.open(metadata)) {
                requireSamePageCount(pages, document.pageCount());
                applyPageBoxes(document);
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

    private static byte[] embedMissingFonts(byte[] pdf, PdfXOptions options) {
        if (!options.embedMissingFonts()) return pdf;
        byte[] out = pdf;
        if (PdfFontRewriter.isAvailable()) {
            try {
                PdfFontRewriteOptions fontOpts = PdfFontRewriteOptions.builder()
                        .convertToPdfA2b(false)
                        .donorFontDirs(options.fontDirectories())
                        .build();
                out = PdfFontRewriter.rewrite(out, fontOpts).pdf();
            } catch (JPDFiumException ignored) {
                // Fall back to PdfFontEmbedder below.
            }
        }
        if (!PdfFontEmbedder.isAvailable()) return out;
        try {
            return PdfFontEmbedder.embedMissing(out, options.fontDirectories().toArray(Path[]::new)).pdf();
        } catch (IOException | JPDFiumException e) {
            if (options.requireEmbeddedFonts()) {
                throw new JPDFiumException("PDF/X font embedding failed: " + e.getMessage(), e);
            }
            return out;
        }
    }

    private static void embedMissingFonts(Path pdf, PdfXOptions options) {
        if (!options.embedMissingFonts()) return;
        if (PdfFontRewriter.isAvailable()) {
            try {
                PdfFontRewriteOptions fontOpts = PdfFontRewriteOptions.builder()
                        .convertToPdfA2b(false)
                        .donorFontDirs(options.fontDirectories())
                        .build();
                byte[] rewritten = PdfFontRewriter.rewrite(Files.readAllBytes(pdf), fontOpts).pdf();
                Files.write(pdf, rewritten);
            } catch (IOException | JPDFiumException ignored) {
                // Fall back to PdfFontEmbedder below.
            }
        }
        if (!PdfFontEmbedder.isAvailable()) return;
        try {
            PdfFontEmbedder.embedMissingTo(
                    pdf, pdf, options.fontDirectories().toArray(Path[]::new));
        } catch (IOException | JPDFiumException e) {
            if (options.requireEmbeddedFonts()) {
                throw new JPDFiumException("PDF/X font embedding failed: " + e.getMessage(), e);
            }
        }
    }

    private static boolean isSigned(PdfDocument doc) {
        int count = PdfSignatures.count(doc.rawHandleForRead());
        for (int i = 0; i < count; i++) {
            if (PdfSignatures.details(doc.nativeHandle(), i).byteRange() != null) return true;
        }
        return false;
    }

    /** ISO 32000 signature dictionaries announce themselves with /ByteRange. */
    private static final byte[] BYTE_RANGE = "/ByteRange".getBytes(StandardCharsets.US_ASCII);

    /**
     * Fails closed on a raw {@code /ByteRange} marker anywhere in the bytes.
     * PDFium's signature enumeration only reports signatures it recognizes
     * (AcroForm signature fields); a dictionary it cannot see would let the
     * rewrite silently invalidate a real signature.
     */
    private static void requireNotSignedByRawScan(byte[] input) {
        outer:
        for (int i = 0; i + BYTE_RANGE.length <= input.length; i++) {
            for (int j = 0; j < BYTE_RANGE.length; j++) {
                if (input[i + j] != BYTE_RANGE[j]) continue outer;
            }
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
        // ISO 15930 forbids /Trapped true in PDF/X output: the input's claim
        // must not survive the conversion.
        document.setTrappedFalse();
        if (options.standard() == PdfXStandard.PDF_X_1A || options.standard() == PdfXStandard.PDF_X_3) {
            document.setMetadata("GTS_PDFXVersion", options.standard().gtsVersion());
            if (options.standard().gtsConformance() != null) {
                document.setMetadata("GTS_PDFXConformance", options.standard().gtsConformance());
            }
            if (options.title() == null || options.title().isBlank()) {
                PdfMetadata meta = PdfMetadata.of(document.rawHandleForRead());
                if (meta.title().isEmpty()) {
                    throw new JPDFiumException("Title is required for " + options.standard().name());
                }
            }
        }
        if (options.title() != null && !options.title().isBlank()) {
            document.setMetadata("Title", options.title());
        } else if (options.standard() == PdfXStandard.PDF_X_4 || options.standard() == PdfXStandard.PDF_X_6) {
            PdfMetadata meta = PdfMetadata.of(document.rawHandleForRead());
            if (meta.title().isEmpty()) {
                document.setMetadata("Title", "Untitled");
            }
        }
        PdfMetadata metadata = PdfMetadata.of(document.rawHandleForRead());
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
        requireNotSignedByRawScan(input);
        requireSupported(options);
        try (PdfDocument source = PdfDocument.open(input)) {
            prepare(source, options);
            applyDocumentInfo(source, options);
            PdfXStandard standard = options.standard();
            byte[] converted = PdfOutputIntent.applyPdfxMetadata(nativePrepareWithFlatten(source, options),
                    options.outputIntentProfile(),
                    PdfOutputIntent.Spec.pdfx(options.conditionIdentifier(), options.info()),
                    standard.gtsVersion(), standard.gtsConformance());
            if (!options.fontData().isEmpty()) {
                converted = PdfFontEmbedder.embed(converted, options.fontData());
            }
            converted = embedMissingFonts(converted, options);
            byte[] out;
            try (PdfDocument metadata = PdfDocument.open(converted)) {
                requireSamePageCount(source.pageCount(), metadata.pageCount());
                applyPageBoxes(metadata);
                requireEmbeddedFonts(metadata, options);
                out = metadata.saveBytes();
            }
            if (options.embedMissingFonts() && PdfFontRewriter.isAvailable()) {
                try {
                    PdfFontRewriteOptions fontOpts = PdfFontRewriteOptions.builder()
                            .convertToPdfA2b(false)
                            .donorFontDirs(options.fontDirectories())
                            .build();
                    int flags = fontOpts.toNativeFlags();
                    if (standard == PdfXStandard.PDF_X_4 || standard == PdfXStandard.PDF_X_6) {
                        flags |= PdfFontRewriteOptions.FLAG_INJECT_DEFAULT_RGB;
                    }
                    out = PdfFontRewriter.rewriteWithFlags(out, flags, fontOpts.donorFontDirs()).pdf();
                } catch (JPDFiumException ignored) {
                    // Keep existing PDF/X bytes if font rewriter declines the document.
                }
            }
            return PdfVersionConverter.forceVersion(out, versionFor(standard));
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
        if (!options.flattenTransparency() || options.standard().allowsTransparency()) {
            return nativePrepare(source.saveBytes(), options);
        }
        BitSet toFlatten = new BitSet();
        int count = source.pageCount();
        for (int i = 0; i < count; i++) {
            try (PdfPage page = source.page(i)) {
                if (PdfPageObjects.hasTransparencyStrict(page.rawHandleForRead())) {
                    toFlatten.set(i);
                }
            }
        }
        byte[] current = toFlatten.isEmpty()
                ? source.saveBytes()
                : flattenSelectedPages(source, toFlatten, options);
        try {
            return nativePrepare(current, options);
        } catch (JPDFiumException e) {
            BitSet extra = parseIncompatiblePages(e.getMessage(), count);
            if (extra.isEmpty()) {
                throw e;
            }
            toFlatten.or(extra);
            byte[] flattened = flattenSelectedPages(source, toFlatten, options);
            return nativePrepare(flattened, options);
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
            try {
                int p = Integer.parseInt(tok.trim()) - 1;
                if (p >= 0 && p < pageCount) {
                    pages.set(p);
                }
            } catch (NumberFormatException ignored) {
                // Skip malformed token.
            }
        }
        return pages;
    }

    private static byte[] flattenSelectedPages(PdfDocument source, BitSet toFlatten, PdfXOptions options) {
        int count = source.pageCount();
        int dpi = options.flattenDpi();
        try (PdfDocument out = PdfDocumentFactory.createNew()) {
            for (int i = 0; i < count; i++) {
                if (!toFlatten.get(i)) {
                    PdfPageImporter.importPagesByIndex(
                            out.rawHandle(), source.rawHandleForRead(), new int[]{i}, i);
                    continue;
                }
                Rect media;
                BufferedImage rawImg;
                try (PdfPage page = source.page(i)) {
                    media = page.getMediaBox();
                    RenderResult rendered = page.renderAt(dpi, false, null);
                    rawImg = rendered.toBufferedImage();
                }
                // Strip alpha channel (TYPE_INT_ARGB -> TYPE_INT_RGB on white) so PdfDocument.fromImage
                // never attaches an /SMask soft mask that PDF/X-1a/3 forbids.
                BufferedImage img = new BufferedImage(
                        rawImg.getWidth(), rawImg.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                try {
                    g.setColor(Color.WHITE);
                    g.fillRect(0, 0, img.getWidth(), img.getHeight());
                    g.drawImage(rawImg, 0, 0, null);
                } finally {
                    g.dispose();
                }
                float w = Math.max(1f, Math.abs(media.width()));
                float h = Math.max(1f, Math.abs(media.height()));
                byte[] singlePagePdf = buildOpaqueJpegPagePdf(img, w, h, options.jpegQuality());
                try (PdfDocument imgDoc = PdfDocument.open(singlePagePdf)) {
                    PdfPageImporter.importPagesByIndex(
                            out.rawHandle(), imgDoc.rawHandleForRead(), new int[]{0}, i);
                }
            }
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

    private static byte[] buildOpaqueJpegPagePdf(BufferedImage rgb, float pageW, float pageH, int quality) {
        byte[] jpeg;
        try {
            ByteArrayOutputStream jb = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
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
        String wStr = String.format(Locale.ROOT, "%.4f", pageW);
        String hStr = String.format(Locale.ROOT, "%.4f", pageH);
        byte[] content = ("q " + wStr + " 0 0 " + hStr + " 0 0 cm /Im0 Do Q\n")
                .getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream pdf = new ByteArrayOutputStream(jpeg.length + 1024);
        int[] off = new int[6];
        try {
            pdf.write("%PDF-1.3\n%\u00E2\u00E3\u00CF\u00D3\n".getBytes(StandardCharsets.ISO_8859_1));
            off[1] = pdf.size();
            pdf.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            off[2] = pdf.size();
            pdf.write("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n".getBytes(StandardCharsets.US_ASCII));
            off[3] = pdf.size();
            pdf.write(("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + wStr + " " + hStr
                    + "] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>\nendobj\n")
                    .getBytes(StandardCharsets.US_ASCII));
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
            int xref = pdf.size();
            pdf.write("xref\n0 6\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
            for (int i = 1; i <= 5; i++) {
                pdf.write(String.format(Locale.ROOT, "%010d 00000 n \n", off[i])
                        .getBytes(StandardCharsets.US_ASCII));
            }
            pdf.write(("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n")
                    .getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            throw new JPDFiumException("Failed to build flattened page PDF: " + e.getMessage(), e);
        }
        return pdf.toByteArray();
    }

    private static byte[] nativePrepare(byte[] pdf, PdfXOptions options) {
        IccCmykTransform xform = new IccCmykTransform(options.outputIntentProfile());
        return ColorBindings.pdfxPrepareBytes(pdf, nativeLevel(options.standard()),
                xform.lutBytes(), IccCmykTransform.lutGrid(), nativeFlags(options),
                options.jpegQuality());
    }

    private static void nativePrepare(Path pdf, PdfXOptions options) {
        IccCmykTransform xform = new IccCmykTransform(options.outputIntentProfile());
        ColorBindings.pdfxPrepareFile(pdf.toString(), pdf.toString(),
                nativeLevel(options.standard()), xform.lutBytes(), IccCmykTransform.lutGrid(),
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
