package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Position;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.MemorySegment;

/**
 * Add and scan QR codes and barcodes in PDF documents.
 *
 * <p>QR code generation uses a minimal pure-Java QR encoder (no native dependencies).
 * The QR code is drawn directly as PDF path objects for maximum quality at any zoom level.
 *
 * <p>Barcode scanning requires zxing-cpp in the native bridge (available when built
 * with barcode support).
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("invoice.pdf"))) {
 *     QrOptions qr = QrOptions.builder()
 *         .content("https://example.com/verify/12345")
 *         .size(100)
 *         .position(Position.BOTTOM_RIGHT)
 *         .margin(36)
 *         .build();
 *     PdfBarcode.addQrCode(doc, 0, qr);
 *     doc.save(Path.of("invoice-with-qr.pdf"));
 * }
 * }</pre>
 */
public final class PdfBarcode {

    private PdfBarcode() {}

    /** Error correction level for QR codes. */
    public enum EccLevel {
        LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2);
        final int formatBits;
        EccLevel(int formatBits) { this.formatBits = formatBits; }
    }

    /**
     * Add a QR code to a specific page.
     *
     * @param doc       the document to modify
     * @param pageIndex 0-based page index
     * @param options   QR code options (content, size, position)
     */
    public static void addQrCode(PdfDocument doc, int pageIndex, QrOptions options) {
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawDoc = doc.rawHandle();
            MemorySegment rawPage = page.rawHandle();
            float pageW = page.size().width();
            float pageH = page.size().height();

            // Generate QR matrix
            QrEncoder.EccLevel internalEcc = QrEncoder.EccLevel.valueOf(options.eccLevel().name());
            boolean[][] matrix = QrEncoder.encode(options.content(), internalEcc);
            int modules = matrix.length;
            float moduleSize = options.size() / (float) modules;

            // Calculate position
            float[] pos = calculatePosition(options.position(), options.margin(),
                    options.size(), options.size(), pageW, pageH);
            float startX = pos[0];
            float startY = pos[1];

            // Draw white background
            drawRect(rawDoc, rawPage, startX, startY, options.size(), options.size(),
                    options.bgColor(), true);

            // Draw each dark module as a filled rectangle
            for (int row = 0; row < modules; row++) {
                for (int col = 0; col < modules; col++) {
                    if (matrix[row][col]) {
                        float x = startX + col * moduleSize;
                        float y = startY + (modules - 1 - row) * moduleSize;
                        drawRect(rawDoc, rawPage, x, y, moduleSize, moduleSize,
                                options.fgColor(), true);
                    }
                }
            }

            // Commit the changes
            try { int gcOk = (int) PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage); }
            catch (Throwable t) { throw new RuntimeException("FPDFPage_GenerateContent failed", t); }
        }
    }

    /**
     * Add a QR code to all pages.
     *
     * @param doc     the document to modify
     * @param options QR code options
     */
    public static void addQrCodeToAll(PdfDocument doc, QrOptions options) {
        for (int i = 0; i < doc.pageCount(); i++) {
            addQrCode(doc, i, options);
        }
    }

    private static void drawRect(MemorySegment rawDoc, MemorySegment rawPage,
                                  float x, float y, float w, float h,
                                  int argbColor, boolean filled) {
        try {
            MemorySegment rect = (MemorySegment) PageEditBindings.FPDFPageObj_CreateNewRect.invokeExact(
                    x, y, w, h);
            if (rect.equals(MemorySegment.NULL)) return;

            int a = (argbColor >> 24) & 0xFF;
            int r = (argbColor >> 16) & 0xFF;
            int g = (argbColor >> 8) & 0xFF;
            int b = argbColor & 0xFF;

            int ignored1 = (int) PageEditBindings.FPDFPageObj_SetFillColor.invokeExact(rect, r, g, b, a);

            // Draw mode: 1 = FPDF_FILLMODE_ALTERNATE with fill, no stroke
            int ignored2 = (int) PageEditBindings.FPDFPath_SetDrawMode.invokeExact(rect, 1, 0);

            PageEditBindings.FPDFPage_InsertObject.invokeExact(rawPage, rect);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to draw QR module", t);
        }
    }

    private static float[] calculatePosition(Position pos, float margin,
                                              float objW, float objH,
                                              float pageW, float pageH) {
        float x, y;
        switch (pos) {
            case TOP_LEFT      -> { x = margin; y = pageH - margin - objH; }
            case TOP_CENTER    -> { x = (pageW - objW) / 2; y = pageH - margin - objH; }
            case TOP_RIGHT     -> { x = pageW - margin - objW; y = pageH - margin - objH; }
            case MIDDLE_LEFT   -> { x = margin; y = (pageH - objH) / 2; }
            case CENTER        -> { x = (pageW - objW) / 2; y = (pageH - objH) / 2; }
            case MIDDLE_RIGHT  -> { x = pageW - margin - objW; y = (pageH - objH) / 2; }
            case BOTTOM_CENTER -> { x = (pageW - objW) / 2; y = margin; }
            case BOTTOM_RIGHT  -> { x = pageW - margin - objW; y = margin; }
            default            -> { x = margin; y = margin; }
        }
        return new float[]{x, y};
    }

    /**
     * QR code generation options.
     */
    public static final class QrOptions {
        private final String content;
        private final float size;
        private final Position position;
        private final float margin;
        private final int fgColor;
        private final int bgColor;
        private final EccLevel eccLevel;

        private QrOptions(Builder b) {
            this.content = b.content;
            this.size = b.size;
            this.position = b.position;
            this.margin = b.margin;
            this.fgColor = b.fgColor;
            this.bgColor = b.bgColor;
            this.eccLevel = b.eccLevel;
        }

        public String content() { return content; }
        public float size() { return size; }
        public Position position() { return position; }
        public float margin() { return margin; }
        public int fgColor() { return fgColor; }
        public int bgColor() { return bgColor; }
        public EccLevel eccLevel() { return eccLevel; }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String content = "";
            private float size = 100;
            private Position position = Position.BOTTOM_RIGHT;
            private float margin = 36;
            private int fgColor = 0xFF000000; // black
            private int bgColor = 0xFFFFFFFF; // white
            private EccLevel eccLevel = EccLevel.MEDIUM;

            private Builder() {}

            /** The content to encode in the QR code. */
            public Builder content(String content) { this.content = content; return this; }
            /** Size of the QR code in PDF points. */
            public Builder size(float size) { this.size = size; return this; }
            /** Position on the page. */
            public Builder position(Position pos) { this.position = pos; return this; }
            /** Margin from page edge in PDF points. */
            public Builder margin(float margin) { this.margin = margin; return this; }
            /** Foreground (module) color as ARGB. */
            public Builder fgColor(int argb) { this.fgColor = argb; return this; }
            /** Background color as ARGB. */
            public Builder bgColor(int argb) { this.bgColor = argb; return this; }
            /** Error correction level. */
            public Builder eccLevel(EccLevel level) { this.eccLevel = level; return this; }

            public QrOptions build() {
                if (content == null || content.isEmpty()) {
                    throw new IllegalArgumentException("QR content must not be empty");
                }
                return new QrOptions(this);
            }
        }
    }
}
