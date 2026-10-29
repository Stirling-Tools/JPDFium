package stirling.software.jpdfium.crop;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generates a self-contained visual walkthrough for the crop fixes. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CropVisualWalkthroughTest {

    private static final int DPI = 144;

    @Test
    void generateWalkthrough() throws Exception {
        Path outDir = Path.of("samples-output", "crop-visual-walkthrough");
        Files.createDirectories(outDir);

        List<Case> cases = new ArrayList<>();
        byte[] word = wordPdf("WORD", 48, 250, 400);
        cases.add(new Case("word-left", "Word straddles left crop edge (keep left half)",
                word, new Rect(0, 0, 306, 792), 0f, List.of("WORD"), List.of(), ""));
        cases.add(new Case("word-right", "Word straddles right crop edge (keep right half)",
                word, new Rect(306, 0, 306, 792), 0f, List.of("WORD"), List.of(), ""));
        cases.add(new Case("letter-bottom", "Letter origin below crop, glyph reaches inside",
                wordPdf("B", 96, 100, 100), new Rect(0, 105, 612, 687), 0f,
                List.of("B"), List.of(), ""));

        byte[] boundary = CropTestPdfGenerator.boundaryWordColumnsPdf();
        cases.add(new Case("margin-auto", "AUTO: remove only wholly outside words",
                boundary, new Rect(0, 0, 306, 792), 0f,
                List.of("INSIDE"), List.of("NEAR", "FAR"), ""));
        cases.add(new Case("margin-10", "10pt margin: keep NEAR, remove FAR",
                boundary, new Rect(0, 0, 306, 792), 10f,
                List.of("INSIDE", "NEAR"), List.of("FAR"), ""));
        cases.add(new Case("margin-40", "40pt margin: keep NEAR and FAR",
                boundary, new Rect(0, 0, 306, 792), 40f,
                List.of("INSIDE", "NEAR", "FAR"), List.of(), ""));

        cases.add(new Case("form-word", "Form XObject word straddles the crop edge",
                CropTestPdfGenerator.formStraddleUnderRectPdf(), new Rect(0, 0, 306, 792), 0f,
                List.of("EDGE_WORD"), List.of(), ""));
        cases.add(new Case("pdfbox-grid", "PDFBox grid: inside words survive, outside words removed",
                CropTestPdfGenerator.textGridPdf(), new Rect(0, 0, 306, 792), 0f,
                List.of("KEEP_A", "KEEP_B", "KEEP_C", "KEEP_D"),
                List.of("DROP_A", "DROP_B", "DROP_C", "DROP_D"), ""));

        for (String corpusName : new String[] {"tracemonkey.pdf", "TAMReview.pdf",
                "160F-2019.pdf", "PDFBOX-4352-0.pdf"}) {
            Path corpusPdf = Path.of("build", "test-corpus", corpusName);
            if (!Files.exists(corpusPdf)) continue;
            byte[] corpus = Files.readAllBytes(corpusPdf);
            float w;
            float h;
            try (PdfDocument doc = PdfDocument.open(corpus); PdfPage page = doc.page(0)) {
                w = page.size().width();
                h = page.size().height();
            }
            cases.add(new Case("corpus-" + corpusName.replace(".pdf", ""),
                    "Corpus: " + corpusName + " - central 60% crop",
                    corpus, new Rect(w * 0.2f, h * 0.2f, w * 0.6f, h * 0.6f), 0f,
                    List.of(), List.of(), "Downloaded pdf.js/pdfbox corpus sample"));
        }

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset='utf-8'>")
                .append("<title>JPDFium crop visual walkthrough</title>")
                .append("<style>body{font-family:system-ui,sans-serif;margin:24px;max-width:1600px}")
                .append("table{border-collapse:collapse;margin:12px 0 32px}td,th{border:1px solid #ccc;")
                .append("padding:8px;vertical-align:top}img{max-width:100%;border:1px solid #ddd}")
                .append(".ok{color:#087f23;font-weight:600}.bad{color:#b00020;font-weight:600}")
                .append(".note{color:#555}</style></head><body>")
                .append("<h1>JPDFium crop visual walkthrough</h1>")
                .append("<p>Each case shows the original with the red crop boundary, a clip-only reference, "
                        + "and the hard-crop output. The CV column compares the hard-crop output with the "
                        + "clip-only reference inside the crop (border band excluded); low difference means "
                        + "no visible content was lost.</p>");

        List<String> failures = new ArrayList<>();
        for (Case c : cases) {
            html.append("<h2>").append(c.label).append("</h2>");
            boolean fixedCase = !c.present.isEmpty() || !c.absent.isEmpty();
            try {
                BufferedImage before = renderMarked(c.input, c.crop);
                BufferedImage reference = render(c.input, c.crop, 10000f);
                byte[] output = hardCrop(c.input, c.crop, c.margin);
                BufferedImage after = renderPdf(output);
                String text = text(output);

                Metrics m = compare(reference, after);
                boolean presentOk = c.present.stream().allMatch(text::contains);
                boolean absentOk = c.absent.stream().noneMatch(text::contains);

                writePng(outDir.resolve(c.id + "-before.png"), before);
                writePng(outDir.resolve(c.id + "-clip-reference.png"), reference);
                writePng(outDir.resolve(c.id + "-after.png"), after);
                Files.write(outDir.resolve(c.id + "-after.pdf"), output);

                html.append("<table><tr><th>Before (red = crop boundary)</th><th>Clip-only reference</th>")
                        .append("<th>Hard crop (margin=").append(c.margin).append(")</th></tr><tr>")
                        .append("<td>").append(img(before)).append("</td><td>").append(img(reference))
                        .append("</td><td>").append(img(after)).append("</td></tr></table>");
                html.append("<p>CV vs clip-only: mean abs diff <b>").append(fmt(m.mean))
                        .append("</b>, changed pixels <b>").append(fmt(m.changedPct))
                        .append("%</b>, max channel diff <b>").append(m.max).append("</b>.");
                html.append(" Text presence: ").append(presentOk ? "<span class='ok'>PASS</span>"
                        : "<span class='bad'>FAIL</span>");
                html.append("; absent as expected: ").append(absentOk ? "<span class='ok'>PASS</span>"
                        : "<span class='bad'>FAIL</span>");
                if (!c.note.isEmpty()) {
                    html.append(" <span class='note'>(").append(c.note).append(")</span>");
                }
                html.append("</p>");
                html.append("<p>Extracted text: <code>").append(escape(shorten(text, 240)))
                        .append("</code></p>");

                if (!presentOk) failures.add(c.id + " missing expected text " + c.present);
                if (!absentOk) failures.add(c.id + " retained unexpected text " + c.absent);
                if (fixedCase && (m.mean > 1.0 || m.changedPct > 1.0)) {
                    failures.add(c.id + " visible content diverged from clip-only: mean " + fmt(m.mean)
                            + ", changed " + fmt(m.changedPct) + "%");
                }
            } catch (Exception e) {
                html.append("<p class='bad'>Crop refused: ").append(escape(e.getMessage()))
                        .append("</p><p class='note'>The audit failure is the safe outcome: the file "
                                + "was not silently left half-removed.</p>");
                if (fixedCase) failures.add(c.id + " failed: " + e);
            }
        }

        html.append("</body></html>");
        Files.writeString(outDir.resolve("index.html"), html.toString());
        System.out.println("WALKTHROUGH: " + outDir.toAbsolutePath().resolve("index.html"));
        assertTrue(failures.isEmpty(), "walkthrough regressions: " + String.join("; ", failures));
    }

    private record Case(String id, String label, byte[] input, Rect crop, float margin,
                        List<String> present, List<String> absent, String note) {}

    private record Metrics(double mean, double changedPct, int max) {}

    private static BufferedImage render(byte[] input, Rect crop, float margin) throws Exception {
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, crop, margin);
            byte[] output = doc.saveBytes();
            return renderPdf(output);
        }
    }

    private static BufferedImage renderMarked(byte[] input, Rect crop) throws Exception {
        BufferedImage img = renderPdf(input);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(2f));
        int x0 = Math.round(crop.x()) * DPI / 72;
        int x1 = Math.round(crop.x() + crop.width()) * DPI / 72;
        int y0 = img.getHeight() - Math.round(crop.y() + crop.height()) * DPI / 72;
        int y1 = img.getHeight() - Math.round(crop.y()) * DPI / 72;
        if (x0 > 0 && x0 < img.getWidth()) g.drawLine(x0, 0, x0, img.getHeight());
        if (x1 > 0 && x1 < img.getWidth()) g.drawLine(x1, 0, x1, img.getHeight());
        if (y0 > 0 && y0 < img.getHeight()) g.drawLine(0, y0, img.getWidth(), y0);
        if (y1 > 0 && y1 < img.getHeight()) g.drawLine(0, y1, img.getWidth(), y1);
        g.dispose();
        return img;
    }

    private static BufferedImage renderPdf(byte[] pdf) throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdf); PdfPage page = doc.page(0)) {
            return page.renderAt(DPI).toBufferedImage();
        }
    }

    private static byte[] hardCrop(byte[] input, Rect crop, float margin) throws Exception {
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfPageGeometry.cropAndRemoveContent(doc, 0, crop, margin);
            return doc.saveBytes();
        }
    }

    private static byte[] wordPdf(String text, float size, float x, float y) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(612, 792));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
                cs.beginText();
                cs.newLineAtOffset(x, y);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replace("\n", "\\n");
        }
    }

    private static Metrics compare(BufferedImage ref, BufferedImage act) {
        int border = 6;
        long sum = 0;
        long changed = 0;
        long count = 0;
        int max = 0;
        int w = Math.min(ref.getWidth(), act.getWidth());
        int h = Math.min(ref.getHeight(), act.getHeight());
        for (int y = border; y < h - border; y++) {
            for (int x = border; x < w - border; x++) {
                int a = ref.getRGB(x, y);
                int b = act.getRGB(x, y);
                int d = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                        + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                        + Math.abs((a & 0xFF) - (b & 0xFF));
                sum += d;
                if (d > 24) changed++;
                max = Math.max(max, d);
                count++;
            }
        }
        return new Metrics((double) sum / Math.max(1, count),
                (double) changed * 100.0 / Math.max(1, count), max);
    }

    private static void writePng(Path path, BufferedImage image) throws Exception {
        ImageIO.write(image, "png", path.toFile());
    }

    private static String img(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return "<img src='data:image/png;base64,"
                + Base64.getEncoder().encodeToString(out.toByteArray()) + "'>";
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
