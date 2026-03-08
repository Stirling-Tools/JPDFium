package stirling.software.jpdfium.model;

/**
 * PDF page size (width and height in points, 1 pt = 1/72 inch).
 */
public record PageSize(float width, float height) {

    /** A4: 595 x 842 pt (210 x 297 mm) */
    public static final PageSize A4 = new PageSize(595, 842);

    /** A3: 842 x 1190 pt (297 x 420 mm) */
    public static final PageSize A3 = new PageSize(842, 1190);

    /** US Letter: 612 x 792 pt (8.5 x 11 in) */
    public static final PageSize LETTER = new PageSize(612, 792);

    /** US Legal: 612 x 1008 pt (8.5 x 14 in) */
    public static final PageSize LEGAL = new PageSize(612, 1008);
}
