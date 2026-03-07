package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fluent builder for N-up PDF layouts.
 *
 * <p>Tiles multiple source pages onto each output page using PDFium's
 * {@code FPDF_ImportNPagesToOne} API. The resulting document is saved via
 * {@code FPDF_SaveAsCopy} - entirely through the Panama FFM layer, with
 * no round-trip through the jpdfium C bridge.
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * // Four-up on A4 landscape - one line
 * NUpLayout.from(doc).grid(2, 2).a4Landscape().build().save(outputPath);
 *
 * // Six-up on US Letter landscape, returned as bytes
 * byte[] pdf = NUpLayout.from(doc).grid(3, 2).letterLandscape().build().toBytes();
 *
 * // Custom page size (A3 landscape: 1190 x 842 pt)
 * NUpLayout.from(doc).grid(4, 2).pageSize(1190, 842).build().save(outputPath);
 * }</pre>
 *
 * <p>The source {@link PdfDocument} must remain open until {@link #toBytes()} or
 * {@link #save(Path)} is called - the raw document handle is resolved lazily.
 */
public final class NUpLayout {

    // -------------------------------------------------------------------------
    // Standard page sizes (PDF points: 1 pt = 1/72 inch)
    // -------------------------------------------------------------------------

    public static final float A4_WIDTH      = 595f;
    public static final float A4_HEIGHT     = 842f;
    public static final float LETTER_WIDTH  = 612f;
    public static final float LETTER_HEIGHT = 792f;
    public static final float A3_WIDTH      = 842f;
    public static final float A3_HEIGHT     = 1190f;

    // -------------------------------------------------------------------------

    private final PdfDocument doc;
    private final float       outputWidth;
    private final float       outputHeight;
    private final int         cols;
    private final int         rows;

    private NUpLayout(PdfDocument doc, float outputWidth, float outputHeight,
                      int cols, int rows) {
        this.doc          = doc;
        this.outputWidth  = outputWidth;
        this.outputHeight = outputHeight;
        this.cols         = cols;
        this.rows         = rows;
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Begin building an N-up layout for the given source document.
     *
     * @param doc source PDF document (must be open when {@link #toBytes()} is called)
     * @return a new {@link Builder}
     */
    public static Builder from(PdfDocument doc) {
        return new Builder(doc);
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /**
     * Render the N-up layout to a byte array.
     *
     * <p>The source document must still be open when this method is called.
     *
     * @return PDF bytes of the N-up document
     * @throws RuntimeException if PDFium fails to produce the layout
     */
    public byte[] toBytes() {
        return PdfPageImporter.importNPagesToOne(
                doc.rawHandle(), outputWidth, outputHeight, cols, rows);
    }

    /**
     * Render the N-up layout and write it to {@code path}.
     *
     * @param path destination file
     * @throws IOException if the file cannot be written
     */
    public void save(Path path) throws IOException {
        Files.write(path, toBytes());
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /** Number of source-page columns per output page. */
    public int cols() { return cols; }

    /** Number of source-page rows per output page. */
    public int rows() { return rows; }

    /** Source pages tiled per output page ({@code cols} x {@code rows}). */
    public int pagesPerSheet() { return cols * rows; }

    /** Output page width in PDF points. */
    public float outputWidth()  { return outputWidth; }

    /** Output page height in PDF points. */
    public float outputHeight() { return outputHeight; }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link NUpLayout}.
     *
     * <p>Default configuration: 2 x 2 grid on A4 landscape.
     */
    public static final class Builder {

        private final PdfDocument doc;
        private float outputWidth  = A4_HEIGHT;   // A4 landscape
        private float outputHeight = A4_WIDTH;
        private int   cols         = 2;
        private int   rows         = 2;

        private Builder(PdfDocument doc) {
            if (doc == null) throw new NullPointerException("doc must not be null");
            this.doc = doc;
        }

        /**
         * Grid of {@code cols} columns x {@code rows} rows per output page.
         *
         * @param cols source-page columns (>= 1)
         * @param rows source-page rows    (>= 1)
         */
        public Builder grid(int cols, int rows) {
            if (cols < 1 || rows < 1)
                throw new IllegalArgumentException("cols and rows must be >= 1");
            this.cols = cols;
            this.rows = rows;
            return this;
        }

        /**
         * Custom output page size in PDF points (1 pt = 1/72 inch).
         *
         * @param width  output page width  (> 0)
         * @param height output page height (> 0)
         */
        public Builder pageSize(float width, float height) {
            if (width <= 0 || height <= 0)
                throw new IllegalArgumentException("width and height must be > 0");
            this.outputWidth  = width;
            this.outputHeight = height;
            return this;
        }

        /** A4 portrait output page (595 x 842 pt). */
        public Builder a4Portrait()      { return pageSize(A4_WIDTH,      A4_HEIGHT);     }

        /** A4 landscape output page (842 x 595 pt). */
        public Builder a4Landscape()     { return pageSize(A4_HEIGHT,     A4_WIDTH);      }

        /** A3 portrait output page (842 x 1190 pt). */
        public Builder a3Portrait()      { return pageSize(A3_WIDTH,      A3_HEIGHT);     }

        /** A3 landscape output page (1190 x 842 pt). */
        public Builder a3Landscape()     { return pageSize(A3_HEIGHT,     A3_WIDTH);      }

        /** US Letter portrait output page (612 x 792 pt). */
        public Builder letterPortrait()  { return pageSize(LETTER_WIDTH,  LETTER_HEIGHT); }

        /** US Letter landscape output page (792 x 612 pt). */
        public Builder letterLandscape() { return pageSize(LETTER_HEIGHT, LETTER_WIDTH);  }

        /** Build the configured {@link NUpLayout}. */
        public NUpLayout build() {
            return new NUpLayout(doc, outputWidth, outputHeight, cols, rows);
        }
    }
}
