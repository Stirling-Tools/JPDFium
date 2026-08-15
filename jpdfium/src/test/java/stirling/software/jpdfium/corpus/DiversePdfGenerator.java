package stirling.software.jpdfium.corpus;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Generates a reproducible corpus of diverse synthetic PDFs for testing JPDFium.
 * All content is produced with PDFBox, so the corpus stays independent of the
 * code under test. Every document mixes raster images (DCT / Flate / SMask /
 * DeviceGray), Type1 base-14 text (optionally embedded Type0 Unicode text),
 * rotated text (incl. 45 degrees), and filled/stroked vector shapes, plus a
 * long tail of edge cases (rotation, crop boxes, clipping, transparency,
 * blend modes, annotations, AcroForm, AES-256 encryption, blank/tiny/huge
 * pages).
 *
 * <p>Every string drawn with a latin base-14 or embedded Type0 font is recorded
 * in {@code manifest.tsv} as ground truth, so the test harness can verify that
 * PDFium extracts exactly the text that was drawn.
 *
 * <p>Usage:   {@code java DiversePdfGenerator [outDir] [count] [seed]} -
 * defaults: {@code pdfs/ 300 42}.
 */
public final class DiversePdfGenerator {

    // Base-14 fonts usable for text drawing. SYMBOL / ZAPF_DINGBATS are
    // excluded: their special encodings reject most ASCII (PDFBox throws on
    // encode), and extracted text for them is glyph-name noise anyway.
    private static final Standard14Fonts.FontName[] BASE14 = {
            Standard14Fonts.FontName.HELVETICA,
            Standard14Fonts.FontName.HELVETICA_BOLD,
            Standard14Fonts.FontName.HELVETICA_OBLIQUE,
            Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE,
            Standard14Fonts.FontName.TIMES_ROMAN,
            Standard14Fonts.FontName.TIMES_BOLD,
            Standard14Fonts.FontName.TIMES_ITALIC,
            Standard14Fonts.FontName.TIMES_BOLD_ITALIC,
            Standard14Fonts.FontName.COURIER,
            Standard14Fonts.FontName.COURIER_BOLD,
            Standard14Fonts.FontName.COURIER_OBLIQUE,
            Standard14Fonts.FontName.COURIER_BOLD_OBLIQUE
    };

    // Pure-ASCII strings: safe for every base-14 font incl. Symbol / ZapfDingbats.
    private static final String[] CORPUS = {
            "The quick brown fox jumps over the lazy dog",
            "Pack my box with five dozen liquor jugs",
            "Sphinx of black quartz, judge my vow",
            "How vexingly quick daft zebras jump",
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit",
            "0123456789 !@#$%^&*()_+-=[]{}|;':\",./<>?",
            "PDFium render test 3.14159265",
            "JPDFium synthetic corpus",
            "Waltz, bad nymph, for quick jigs vex",
            "Bright vixens jump; dozy fowl quack"
    };

    // WinAnsi-safe accented text (Latin base-14 fonts only).
    private static final String ACCENTED =
            "Caf\u00E9, na\u00EFve, Z\u00FCrich, se\u00F1or, fa\u00E7ade (\u00B13 \u00B0C)";

    // Hungarian pangram; needs the embedded Type0 font (outside WinAnsi).
    private static final String HU_PANGRAM =
            "\u00C1rv\u00EDzt\u0171r\u0151 t\u00FCk\u00F6rf\u00FAr\u00F3g\u00E9p";

    /**
     * Library entry point: generates {@code count} PDFs under {@code outDir}
     * and writes {@code manifest.tsv} next to them.
     */
    public static Path generate(Path outDir, int count, long seed) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Files.createDirectories(outDir);

        File ttf = findSystemFont();
        System.out.println("Unicode font: " + (ttf != null ? ttf : "none found, Type0 docs skipped"));

        StringBuilder manifest = new StringBuilder("file\tpages\tencrypted\tfeatures\tgroundtruth\n");
        for (int i = 0; i < count; i++) {
            String name = String.format("gen-%04d.pdf", i);
            DocFeatures features = new DocFeatures();
            Random rng = new Random(seed * 1_000_003L + i);
            try (PDDocument doc = new PDDocument()) {
                buildDocument(doc, rng, i, ttf, features);
                doc.save(outDir.resolve(name).toFile());
            }
            manifest.append(name).append('\t')
                    .append(features.pages).append('\t')
                    .append(features.encrypted).append('\t')
                    .append(features.flags()).append('\t')
                    .append(features.groundTruth()).append('\n');
            if ((i + 1) % 100 == 0 || i + 1 == count) {
                System.out.printf("wrote %d/%d%n", i + 1, count);
            }
        }
        Path manifestPath = outDir.resolve("manifest.tsv");
        Files.writeString(manifestPath, manifest);
        System.out.println("Done. Manifest: " + manifestPath);
        return manifestPath;
    }

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 0 ? args[0] : "pdfs");
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 300;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 42L;
        generate(outDir, count, seed);
    }

    // ---------------------------------------------------------------- document

    private static void buildDocument(PDDocument doc, Random rng, int index, File ttf,
                                      DocFeatures f) throws IOException {
        boolean blankDoc = index % 37 == 36;                 // edge case: content-free page
        int maxPages = index % 11 == 10 ? 12 : 6;            // occasional longer documents
        f.pages = blankDoc ? 1 : 1 + rng.nextInt(maxPages);
        f.encrypted = index % 20 == 19;                      // ~5% AES-256, empty user password

        PDFont unicodeFont = (ttf != null && index % 3 == 0) ? PDType0Font.load(doc, ttf) : null;

        PDDocumentInformation info = doc.getDocumentInformation();
        info.setTitle("Synthetic " + index);
        info.setAuthor("DiversePdfGenerator");
        info.setSubject("JPDFium test corpus");
        info.setKeywords("synthetic,test," + index);
        info.setCreator("PDFBox");
        info.setCreationDate(Calendar.getInstance());
        doc.setVersion(new float[]{1.4f, 1.5f, 1.6f, 1.7f}[rng.nextInt(4)]);

        for (int p = 0; p < f.pages; p++) {
            PDRectangle media = randomPageSize(rng, index);
            PDPage page = new PDPage(media);
            if (rng.nextInt(4) == 0) {
                page.setRotation(90 * rng.nextInt(4));
                f.note("page-rotation");
            }
            if (rng.nextInt(6) == 0) {
                float mx = media.getWidth() * 0.1f;
                float my = media.getHeight() * 0.1f;
                page.setCropBox(new PDRectangle(mx, my,
                        media.getWidth() - 2 * mx, media.getHeight() - 2 * my));
                f.note("cropbox");
            }
            doc.addPage(page);

            List<PDAnnotation> annots = new ArrayList<>();
            if (!blankDoc) {
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    paintPage(doc, cs, media, rng, unicodeFont, f);
                }
                maybeAddAnnotations(page, annots, rng, index, f);
            }
            if (!annots.isEmpty()) {
                page.setAnnotations(annots);
            }
        }

        if (!blankDoc && index % 9 == 8) {
            addForm(doc, f);
        }
        if (f.encrypted) {
            StandardProtectionPolicy spp =
                    new StandardProtectionPolicy("owner-" + index, "", new AccessPermission());
            spp.setEncryptionKeyLength(256);
            doc.protect(spp);
        }
    }

    private static PDRectangle randomPageSize(Random rng, int index) {
        return switch (rng.nextInt(6)) {
            case 0 -> PDRectangle.A4;
            case 1 -> PDRectangle.LETTER;
            case 2 -> PDRectangle.LEGAL;
            case 3 -> new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            case 4 -> new PDRectangle(200 + rng.nextInt(1200), 200 + rng.nextInt(1600));
            default -> {
                if (index % 53 == 52) yield new PDRectangle(2000, 2000);   // huge page
                if (index % 47 == 46) yield new PDRectangle(60, 60);       // tiny page
                yield new PDRectangle(300 + rng.nextInt(600), 300 + rng.nextInt(900));
            }
        };
    }

    // -------------------------------------------------------------------- page

    private static void paintPage(PDDocument doc, PDPageContentStream cs, PDRectangle media,
                                  Random rng, PDFont unicodeFont, DocFeatures f) throws IOException {
        float w = media.getWidth();
        float h = media.getHeight();

        int images = 1 + rng.nextInt(3);
        for (int i = 0; i < images; i++) {
            drawRandomImage(doc, cs, rng, w, h, f);
        }
        int shapes = 2 + rng.nextInt(7);
        for (int i = 0; i < shapes; i++) {
            drawRandomShape(cs, rng, w, h, f);
        }
        if (rng.nextInt(3) == 0) {
            drawClippedContent(cs, rng, w, h, f);
        }
        int texts = 3 + rng.nextInt(12);
        for (int i = 0; i < texts; i++) {
            drawRandomText(cs, rng, w, h, unicodeFont, f);
        }
        int rotated = 1 + rng.nextInt(3);
        for (int i = 0; i < rotated; i++) {
            drawRotatedText(cs, rng, w, h, unicodeFont, f);
        }
    }

    // ------------------------------------------------------------------ images

    private static void drawRandomImage(PDDocument doc, PDPageContentStream cs, Random rng,
                                        float pw, float ph, DocFeatures f) throws IOException {
        int iw = 16 + rng.nextInt(400);
        int ih = 16 + rng.nextInt(400);
        PDImageXObject img;
        switch (rng.nextInt(5)) {
            case 0 -> {                                 // DCTDecode, random JPEG quality
                img = jpegImage(doc, gradientImage(iw, ih, rng), rng);
                f.note("dct-image");
            }
            case 1 -> {                                 // FlateDecode via PNG round-trip
                img = pngImage(doc, noiseImage(iw, ih, rng));
                f.note("png-image");
            }
            case 2 -> {                                 // FlateDecode direct from BufferedImage
                img = LosslessFactory.createFromImage(doc, checkerImage(iw, ih, rng));
                f.note("flate-image");
            }
            case 3 -> {                                 // ARGB -> base image + SMask
                img = LosslessFactory.createFromImage(doc, alphaArtImage(iw, ih, rng));
                f.note("smask-image");
            }
            default -> {                                // DeviceGray, 8-bit
                img = pngImage(doc, grayImage(iw, ih, rng));
                f.note("gray-image");
            }
        }

        float dw = 30 + rng.nextFloat() * pw * 0.6f;
        float dh = 30 + rng.nextFloat() * ph * 0.6f;
        float x = rng.nextFloat() * Math.max(1, pw - dw);
        float y = rng.nextFloat() * Math.max(1, ph - dh);
        if (rng.nextInt(3) == 0) {                      // rotated about its own center
            float cx = x + dw / 2;
            float cy = y + dh / 2;
            cs.saveGraphicsState();
            cs.transform(Matrix.getRotateInstance(Math.toRadians(rng.nextInt(360)), cx, cy));
            cs.drawImage(img, x, y, dw, dh);
            cs.restoreGraphicsState();
            f.note("rotated-image");
        } else {
            cs.drawImage(img, x, y, dw, dh);
        }
    }

    private static PDImageXObject jpegImage(PDDocument doc, BufferedImage src, Random rng)
            throws IOException {
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.25f + rng.nextFloat() * 0.7f);
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return PDImageXObject.createFromByteArray(doc, bos.toByteArray(), "jpeg");
    }

    private static PDImageXObject pngImage(PDDocument doc, BufferedImage bi) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(bi, "png", bos);
        return PDImageXObject.createFromByteArray(doc, bos.toByteArray(), "png");
    }

    private static BufferedImage gradientImage(int w, int h, Random rng) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        g.setPaint(new GradientPaint(0, 0, randomColor(rng), w, h, randomColor(rng)));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return bi;
    }

    private static BufferedImage noiseImage(int w, int h, Random rng) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            px[i] = 0xFF000000 | rng.nextInt(0x1000000);
        }
        bi.setRGB(0, 0, w, h, px, 0, w);
        return bi;
    }

    private static BufferedImage checkerImage(int w, int h, Random rng) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int cell = 4 + rng.nextInt(24);
        int c1 = rng.nextInt(0x1000000);
        int c2 = rng.nextInt(0x1000000);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                bi.setRGB(x, y, ((x / cell) + (y / cell)) % 2 == 0 ? c1 : c2);
            }
        }
        return bi;
    }

    private static BufferedImage alphaArtImage(int w, int h, Random rng) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        int n = 3 + rng.nextInt(8);
        for (int i = 0; i < n; i++) {
            g.setColor(new Color(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256),
                    40 + rng.nextInt(216)));
            int sw = Math.max(2, rng.nextInt(w));
            int sh = Math.max(2, rng.nextInt(h));
            if (rng.nextBoolean()) {
                g.fillOval(rng.nextInt(w) - sw / 2, rng.nextInt(h) - sh / 2, sw, sh);
            } else {
                g.fillRect(rng.nextInt(w) - sw / 2, rng.nextInt(h) - sh / 2, sw, sh);
            }
        }
        g.dispose();
        return bi;
    }

    private static BufferedImage grayImage(int w, int h, Random rng) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        byte[] data = ((DataBufferByte) bi.getRaster().getDataBuffer()).getData();
        rng.nextBytes(data);
        return bi;
    }

    // ------------------------------------------------------------------ shapes

    private static void drawRandomShape(PDPageContentStream cs, Random rng, float pw, float ph,
                                        DocFeatures f) throws IOException {
        setRandomPaint(cs, rng, f);
        cs.setLineWidth(0.5f + rng.nextFloat() * 8f);
        if (rng.nextInt(4) == 0) {
            cs.setLineDashPattern(new float[]{2 + rng.nextInt(10), 2 + rng.nextInt(10)}, 0);
            f.note("dashed");
        }
        if (rng.nextInt(3) == 0) {
            cs.setLineCapStyle(rng.nextInt(3));
        }
        if (rng.nextInt(3) == 0) {
            cs.setLineJoinStyle(rng.nextInt(3));
        }

        float x = rng.nextFloat() * pw;
        float y = rng.nextFloat() * ph;
        float s = 20 + rng.nextFloat() * Math.min(pw, ph) * 0.4f;

        switch (rng.nextInt(4)) {
            case 0 -> cs.addRect(x, y, s, s * (0.3f + rng.nextFloat()));
            case 1 -> addCircle(cs, x, y, s / 2);
            case 2 -> addPolygon(cs, x, y, s / 2, 3 + rng.nextInt(6), rng);
            default -> addStar(cs, x, y, s / 2, 4 + rng.nextInt(4), rng);
        }

        switch (rng.nextInt(3)) {
            case 0 -> { cs.fill(); f.note("filled-shape"); }
            case 1 -> { cs.stroke(); f.note("stroked-shape"); }
            default -> { cs.fillAndStroke(); f.note("filled-shape"); }
        }
    }

    private static void setRandomPaint(PDPageContentStream cs, Random rng, DocFeatures f)
            throws IOException {
        if (rng.nextInt(5) == 0) {                      // DeviceCMYK
            cs.setNonStrokingColor(rng.nextFloat(), rng.nextFloat(), rng.nextFloat(),
                    rng.nextFloat());
            cs.setStrokingColor(rng.nextFloat(), rng.nextFloat(), rng.nextFloat(),
                    rng.nextFloat());
            f.note("cmyk");
        } else {                                        // DeviceRGB
            cs.setNonStrokingColor(randomColor(rng));
            cs.setStrokingColor(randomColor(rng));
        }
        if (rng.nextInt(4) == 0) {                      // ExtGState: alpha (+ blend mode)
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.2f + rng.nextFloat() * 0.8f);
            gs.setStrokingAlphaConstant(0.2f + rng.nextFloat() * 0.8f);
            if (rng.nextInt(3) == 0) {
                gs.setBlendMode(BlendMode.MULTIPLY);
                f.note("blend-mode");
            }
            cs.setGraphicsStateParameters(gs);
            f.note("alpha");
        }
    }

    private static void addCircle(PDPageContentStream cs, float cx, float cy, float r)
            throws IOException {
        float k = 0.5522847498f * r;
        cs.moveTo(cx + r, cy);
        cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
        cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
        cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
        cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
        cs.closePath();
    }

    private static void addPolygon(PDPageContentStream cs, float cx, float cy, float r,
                                   int sides, Random rng) throws IOException {
        double start = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < sides; i++) {
            double a = start + i * 2 * Math.PI / sides;
            float px = cx + (float) (r * Math.cos(a));
            float py = cy + (float) (r * Math.sin(a));
            if (i == 0) cs.moveTo(px, py); else cs.lineTo(px, py);
        }
        cs.closePath();
    }

    private static void addStar(PDPageContentStream cs, float cx, float cy, float r,
                                int points, Random rng) throws IOException {
        float inner = r * (0.3f + rng.nextFloat() * 0.3f);
        double start = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < points * 2; i++) {
            double a = start + i * Math.PI / points;
            float rr = (i % 2 == 0) ? r : inner;
            float px = cx + (float) (rr * Math.cos(a));
            float py = cy + (float) (rr * Math.sin(a));
            if (i == 0) cs.moveTo(px, py); else cs.lineTo(px, py);
        }
        cs.closePath();
    }

    private static void drawClippedContent(PDPageContentStream cs, Random rng, float pw,
                                           float ph, DocFeatures f) throws IOException {
        cs.saveGraphicsState();
        addCircle(cs, rng.nextFloat() * pw, rng.nextFloat() * ph,
                30 + rng.nextFloat() * Math.min(pw, ph) * 0.25f);
        cs.clip();
        cs.setNonStrokingColor(randomColor(rng));
        for (int i = 0; i < 6; i++) {
            cs.addRect(rng.nextFloat() * pw, rng.nextFloat() * ph,
                    20 + rng.nextFloat() * 100, 20 + rng.nextFloat() * 100);
            cs.fill();
        }
        cs.restoreGraphicsState();
        f.note("clip");
    }

    // -------------------------------------------------------------------- text

    private static void drawRandomText(PDPageContentStream cs, Random rng, float pw, float ph,
                                       PDFont unicodeFont, DocFeatures f) throws IOException {
        boolean useUnicode = unicodeFont != null && rng.nextInt(3) == 0;
        PDFont font;
        String text;
        if (useUnicode) {
            font = unicodeFont;
            text = rng.nextBoolean() ? HU_PANGRAM : ACCENTED;
        } else {
            Standard14Fonts.FontName base = BASE14[rng.nextInt(BASE14.length)];
            font = new PDType1Font(base);
            text = rng.nextInt(4) == 0 ? ACCENTED : CORPUS[rng.nextInt(CORPUS.length)];
        }
        f.groundTruth.add(text);                        // differential ground truth

        float size = 4 + rng.nextFloat() * 60;
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(randomColor(rng));
        if (rng.nextInt(3) == 0) cs.setCharacterSpacing(rng.nextFloat() * 4 - 1);
        if (rng.nextInt(4) == 0) cs.setWordSpacing(rng.nextFloat() * 8);
        if (rng.nextInt(4) == 0) cs.setHorizontalScaling(50 + rng.nextInt(120));
        if (rng.nextInt(5) == 0) cs.setTextRise(rng.nextFloat() * 10 - 5);
        if (rng.nextInt(5) == 0) {
            RenderingMode[] modes =
                    {RenderingMode.FILL, RenderingMode.STROKE, RenderingMode.FILL_STROKE};
            RenderingMode mode = modes[rng.nextInt(modes.length)];
            cs.setRenderingMode(mode);
            if (mode != RenderingMode.FILL) {
                cs.setStrokingColor(randomColor(rng));
            }
            f.note("render-mode");
        }
        cs.newLineAtOffset(rng.nextFloat() * pw, rng.nextFloat() * ph);
        cs.showText(text);
        if (rng.nextInt(4) == 0) {                      // second line via leading + T*
            String second = CORPUS[rng.nextInt(CORPUS.length)];
            cs.setLeading(size * 1.4f);
            cs.newLine();
            cs.showText(second);
            f.groundTruth.add(second);
        }
        cs.endText();
        f.note(useUnicode ? "type0-text" : "type1-text");
    }

    private static void drawRotatedText(PDPageContentStream cs, Random rng, float pw, float ph,
                                        PDFont unicodeFont, DocFeatures f) throws IOException {
        boolean useUnicode = unicodeFont != null && rng.nextInt(3) == 0;
        PDFont font = useUnicode ? unicodeFont
                : new PDType1Font(BASE14[rng.nextInt(BASE14.length)]);
        String text = useUnicode ? HU_PANGRAM : CORPUS[rng.nextInt(CORPUS.length)];
        f.groundTruth.add(text);
        float size = 6 + rng.nextFloat() * 48;
        float x = rng.nextFloat() * pw;
        float y = rng.nextFloat() * ph;
        double angle = switch (rng.nextInt(4)) {
            case 0 -> 45;
            case 1 -> -45;
            case 2 -> 90;
            default -> rng.nextInt(360);
        };
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(randomColor(rng));
        cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(angle), x, y));
        cs.showText(text);
        cs.endText();
        f.note("rotated-text");
    }

    // -------------------------------------------------- annotations and forms

    private static void maybeAddAnnotations(PDPage page, List<PDAnnotation> annots, Random rng,
                                            int index, DocFeatures f) {
        PDRectangle media = page.getMediaBox();
        if (rng.nextInt(3) == 0) {
            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(media.getWidth() * 0.1f,
                    media.getUpperRightY() - 60, 120, 20));
            PDBorderStyleDictionary border = new PDBorderStyleDictionary();
            border.setWidth(0);
            link.setBorderStyle(border);
            PDActionURI uri = new PDActionURI();
            uri.setURI("https://example.com/doc/" + index);
            link.setAction(uri);
            annots.add(link);
            f.note("link-annot");
        }
        if (rng.nextInt(4) == 0) {
            PDAnnotationText note = new PDAnnotationText();
            note.setName(PDAnnotationText.NAME_NOTE);
            note.setContents("synthetic note " + index);
            note.setRectangle(new PDRectangle(rng.nextFloat() * media.getWidth(),
                    rng.nextFloat() * media.getHeight(), 18, 20));
            annots.add(note);
            f.note("text-annot");
        }
    }

    private static void addForm(PDDocument doc, DocFeatures f) throws IOException {
        PDAcroForm acroForm = new PDAcroForm(doc);
        doc.getDocumentCatalog().setAcroForm(acroForm);
        acroForm.setNeedAppearances(true);              // let the consumer build appearances
        // PDFBox requires a default appearance + resources or constructAppearances
        // throws "/DA is a required entry".
        org.apache.pdfbox.pdmodel.PDResources resources =
                new org.apache.pdfbox.pdmodel.PDResources();
        resources.put(org.apache.pdfbox.cos.COSName.getPDFName("Helv"),
                new PDType1Font(Standard14Fonts.FontName.HELVETICA));
        acroForm.setDefaultResources(resources);
        acroForm.setDefaultAppearance("/Helv 12 Tf 0 g");

        PDTextField field = new PDTextField(acroForm);
        field.setPartialName("sampleField");
        field.setValue("edit me");
        acroForm.getFields().add(field);

        PDPage page = doc.getPage(0);
        PDAnnotationWidget widget = field.getWidgets().get(0);
        PDRectangle media = page.getMediaBox();
        widget.setRectangle(new PDRectangle(media.getWidth() * 0.25f,
                media.getHeight() * 0.5f, 200, 24));
        widget.setPage(page);
        List<PDAnnotation> annots = new ArrayList<>(page.getAnnotations());
        annots.add(widget);
        page.setAnnotations(annots);
        f.note("acroform");
    }

    // ------------------------------------------------------------------ misc

    private static Color randomColor(Random rng) {
        return new Color(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256));
    }

    /** Best-effort TTF lookup for embedded Type0 documents; null if none found. */
    private static File findSystemFont() {
        String[] candidates = {
                "/System/Library/Fonts/Supplemental/Arial.ttf",        // macOS
                "/usr/share/fonts/dejavu/DejaVuSans.ttf",              // Fedora / RHEL
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",     // Debian / Ubuntu
                "/usr/share/fonts/TTF/DejaVuSans.ttf",                 // Arch
                "C:\\Windows\\Fonts\\arial.ttf"                        // Windows
        };
        for (String p : candidates) {
            File f = new File(p);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /** Per-document feature flags, written to manifest.tsv for the test harness. */
    private static final class DocFeatures {
        int pages;
        boolean encrypted;
        final Set<String> flags = new TreeSet<>();
        final List<String> groundTruth = new ArrayList<>();

        void note(String s) {
            flags.add(s);
        }

        String flags() {
            return String.join(",", flags);
        }

        String groundTruth() {
            return groundTruth.stream()
                    .map(s -> s.replace("|", "\\p"))
                    .collect(Collectors.joining("|"));
        }
    }
}
