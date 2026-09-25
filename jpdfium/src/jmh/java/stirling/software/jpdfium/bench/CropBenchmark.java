package stirling.software.jpdfium.bench;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.transform.PdfPageGeometry;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for crop-remove-content: the allocation-free fast-path
 * downcall plus fresh-document crops over text, image and nested-form
 * fixtures. {@link #openCloseBaseline} is the no-crop baseline.
 *
 * <p>Run: {@code ./gradlew :jpdfium:jmh -Pjmh.include=CropBenchmark}
 * (add {@code -Pjmh.profilers=gc} for allocation counts).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public class CropBenchmark {

    /** Number of downcalls per iteration (a single call is below reliable resolution). */
    private static final int BATCH = 1_000;

    private static final Rect FULL_PAGE = new Rect(0, 0, 612, 792);
    private static final Rect LEFT_HALF = new Rect(0, 0, 306, 792);
    private static final Rect KEEP_TOP = new Rect(0, 200, 612, 592);
    private static final Rect MOSTLY_OUTSIDE = new Rect(180, 0, 432, 792);

    private static final PDRectangle LETTER = new PDRectangle(612, 792);

    private byte[] textStraddle;
    private byte[] textDivide;
    private byte[] imageStraddle;
    private byte[] nestedFormImage;
    private byte[] mostlyOutside;

    private PdfDocument doc;
    private PdfPage page;
    private long pageHandle;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        textStraddle = textStraddlePdf();
        textDivide = textDividePdf();
        imageStraddle = imageStraddlePdf();
        nestedFormImage = nestedFormImagePdf();
        mostlyOutside = mostlyOutsideImagePdf();

        byte[] minimal;
        try (var in = CropBenchmark.class.getResourceAsStream("/pdfs/general/minimal.pdf")) {
            minimal = Objects.requireNonNull(in).readAllBytes();
        }
        doc = PdfDocument.open(minimal);
        page = doc.page(0);
        pageHandle = page.nativeHandle();
    }

    @TearDown(Level.Trial)
    public void closeDoc() throws Exception {
        page.close();
        doc.close();
    }

    // zero-allocation hot path

    /**
     * Full-page crop is a fast-path no-op in the native bridge; measures the pure
     * Java wrapper + FFM downcall cost (should be a few ns per call, no heap
     * allocation - see {@code CropAllocationTest}).
     */
    @Benchmark
    public long cropFastPathDowncall() {
        long h = pageHandle;
        int n = 0;
        for (int i = 0; i < BATCH; i++) {
            JpdfiumLib.cropRemoveContent(h, FULL_PAGE.x(), FULL_PAGE.y(), FULL_PAGE.width(),
                    FULL_PAGE.height());
            n++;
        }
        return n;
    }

    // fresh-document end-to-end

    @Benchmark
    public long openCloseBaseline() {
        try (PdfDocument d = PdfDocument.open(textStraddle); PdfPage p = d.page(0)) {
            return d.pageCount();
        }
    }

    @Benchmark
    public long cropTextDivideFreshDoc() {
        return crop(textDivide, LEFT_HALF);
    }

    @Benchmark
    public long cropTextStraddleFreshDoc() {
        return crop(textStraddle, LEFT_HALF);
    }

    @Benchmark
    public long cropImageStraddleFreshDoc() {
        return crop(imageStraddle, LEFT_HALF);
    }

    @Benchmark
    public long cropNestedFormImageFreshDoc() {
        return crop(nestedFormImage, KEEP_TOP);
    }

    @Benchmark
    public long cropMostlyOutsideImageFreshDoc() {
        return crop(mostlyOutside, MOSTLY_OUTSIDE);
    }


    private static long crop(byte[] pdf, Rect rect) {
        try (PdfDocument d = PdfDocument.open(pdf)) {
            PdfPageGeometry.cropAndRemoveContent(d, 0, rect);
            return d.pageCount();
        }
    }

    // fixture generation (PDFBox)

    private static byte[] textStraddlePdf() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(LETTER);
            d.addPage(p);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                word(cs, "KEEP_LEFT", 100, 700);
                word(cs, "STRADDLE_ME", 260, 700);
                word(cs, "DROP_RIGHT", 400, 700);
            }
            return save(d);
        }
    }

    private static byte[] textDividePdf() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(LETTER);
            d.addPage(p);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                word(cs, "KEEP_LEFT", 100, 700);
                word(cs, "DROP_RIGHT", 400, 700);
            }
            return save(d);
        }
    }

    private static byte[] imageStraddlePdf() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(LETTER);
            d.addPage(p);
            PDImageXObject img = PDImageXObject.createFromByteArray(d, twoTonePng(), "tt");
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.drawImage(img, 280, 400, 40, 100);
            }
            return save(d);
        }
    }

    private static byte[] mostlyOutsideImagePdf() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(LETTER);
            d.addPage(p);
            PDImageXObject img = PDImageXObject.createFromByteArray(d, twoTonePng(), "tt");
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.drawImage(img, 100, 300, 100, 100);
            }
            return save(d);
        }
    }

    private static byte[] nestedFormImagePdf() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(LETTER);
            d.addPage(p);
            PDFormXObject form = new PDFormXObject(d);
            form.setBBox(new PDRectangle(0, 0, 612, 792));
            form.setResources(new PDResources());
            PDImageXObject img = PDImageXObject.createFromByteArray(d, twoTonePng(), "nested");
            form.getResources().put(COSName.getPDFName("ImF"), img);
            try (var os = form.getContentStream().createOutputStream()) {
                os.write("q 200 0 0 200 100 100 cm /ImF Do Q"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            if (p.getResources() == null) p.setResources(new PDResources());
            p.getResources().add(form, "Fm0");
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.drawForm(form);
            }
            return save(d);
        }
    }

    private static void word(PDPageContentStream cs, String text, float x, float y)
            throws Exception {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    /** 100x100: top half red, bottom half blue. */
    private static byte[] twoTonePng() throws Exception {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 100; y++) {
            int rgb = y < 50 ? 0xFF0000 : 0x0000FF;
            for (int x = 0; x < 100; x++) {
                img.setRGB(x, y, rgb);
            }
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        }
    }

    private static byte[] save(PDDocument d) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            d.save(baos);
            return baos.toByteArray();
        }
    }
}
