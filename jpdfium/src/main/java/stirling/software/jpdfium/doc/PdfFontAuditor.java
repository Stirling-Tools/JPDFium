package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.PageEditBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.*;

/**
 * Audit fonts used in a PDF document.
 *
 * <p>Enumerates every distinct font referenced by text objects on each page,
 * reporting the font name, family, weight, flags, embedding status, italic
 * angle, and font data size.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("input.pdf"))) {
 *     PdfFontAuditor.AuditReport report = PdfFontAuditor.audit(doc);
 *     for (PdfFontAuditor.FontInfo fi : report.fonts()) {
 *         System.out.printf("%-30s embedded=%b weight=%d flags=0x%04x pages=%s%n",
 *             fi.baseName(), fi.embedded(), fi.weight(), fi.flags(), fi.pages());
 *     }
 *     if (!report.nonEmbeddedFonts().isEmpty()) {
 *         System.out.println("WARNING: Non-embedded fonts: " + report.nonEmbeddedFonts());
 *     }
 * }
 * }</pre>
 */
public final class PdfFontAuditor {

    private PdfFontAuditor() {}

    /**
     * Information about a single font used in the document.
     */
    public record FontInfo(
            String baseName,      // PostScript name (e.g. "TimesNewRomanPSMT")
            String familyName,    // Family name (e.g. "Times New Roman")
            boolean embedded,     // true if font data is embedded
            int weight,           // 400=normal, 700=bold, -1=unknown
            int flags,            // PDF font descriptor flags (ISO 32000-1 table 123)
            int italicAngle,      // Degrees counterclockwise from vertical
            long fontDataSize,    // Size of embedded font data in bytes (0 if not embedded)
            Set<Integer> pages    // Set of page indices where this font appears
    ) {}

    /** Font descriptor flag constants (ISO 32000-1:2008 table 123). */
    public static final int FLAG_FIXED_PITCH   = 1;
    public static final int FLAG_SERIF         = 1 << 1;
    public static final int FLAG_SYMBOLIC      = 1 << 2;
    public static final int FLAG_SCRIPT        = 1 << 3;
    public static final int FLAG_NONSYMBOLIC   = 1 << 5;
    public static final int FLAG_ITALIC        = 1 << 6;
    public static final int FLAG_ALL_CAP       = 1 << 16;
    public static final int FLAG_SMALL_CAP     = 1 << 17;
    public static final int FLAG_FORCE_BOLD    = 1 << 18;

    /**
     * Result of a font audit.
     */
    public record AuditReport(
            List<FontInfo> fonts,
            List<FontInfo> nonEmbeddedFonts,
            int totalFontCount,
            int embeddedCount,
            int nonEmbeddedCount
    ) {}

    /**
     * Audit all fonts in the document.
     *
     * @param doc open PDF document
     * @return audit report
     */
    public static AuditReport audit(PdfDocument doc) {
        // Track unique fonts by base name -> builder
        Map<String, FontBuilder> fontMap = new LinkedHashMap<>();

        for (int pageIdx = 0; pageIdx < doc.pageCount(); pageIdx++) {
            try (PdfPage page = doc.page(pageIdx)) {
                MemorySegment rawPage = page.rawHandle();
                int objCount;
                try {
                    objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
                } catch (Throwable t) { continue; }

                Set<Long> seenOnPage = new HashSet<>();
                for (int i = 0; i < objCount; i++) {
                    MemorySegment obj;
                    try {
                        obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                    } catch (Throwable t) { continue; }
                    if (obj.equals(MemorySegment.NULL)) continue;

                    int type;
                    try {
                        type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
                    } catch (Throwable t) { continue; }

                    if (type != 1) continue; // FPDF_PAGEOBJ_TEXT = 1

                    MemorySegment font;
                    try {
                        font = (MemorySegment) PageEditBindings.FPDFTextObj_GetFont.invokeExact(obj);
                    } catch (Throwable t) { continue; }
                    if (font.equals(MemorySegment.NULL)) continue;

                    // Deduplicate by font handle address within a page
                    long fontAddr = font.address();
                    if (!seenOnPage.add(fontAddr)) continue;

                    String baseName = getFontBaseName(font);
                    String key = baseName + "@" + fontAddr;

                    FontBuilder fb = fontMap.get(key);
                    if (fb == null) {
                        fb = new FontBuilder();
                        fb.baseName = baseName;
                        fb.familyName = getFontFamilyName(font);
                        fb.embedded = getFontIsEmbedded(font);
                        fb.weight = getFontWeight(font);
                        fb.flags = getFontFlags(font);
                        fb.italicAngle = getFontItalicAngle(font);
                        fb.fontDataSize = getFontDataSize(font);
                        fb.pages = new TreeSet<>();
                        fontMap.put(key, fb);
                    }
                    fb.pages.add(pageIdx);
                }
            }
        }

        // Merge fonts with the same base name (from different handle addresses)
        Map<String, FontBuilder> merged = new LinkedHashMap<>();
        for (FontBuilder fb : fontMap.values()) {
            FontBuilder existing = merged.get(fb.baseName);
            if (existing != null) {
                existing.pages.addAll(fb.pages);
            } else {
                merged.put(fb.baseName, fb);
            }
        }

        List<FontInfo> fonts = new ArrayList<>();
        List<FontInfo> nonEmbedded = new ArrayList<>();
        for (FontBuilder fb : merged.values()) {
            FontInfo fi = fb.build();
            fonts.add(fi);
            if (!fi.embedded()) nonEmbedded.add(fi);
        }

        return new AuditReport(
                Collections.unmodifiableList(fonts),
                Collections.unmodifiableList(nonEmbedded),
                fonts.size(),
                fonts.size() - nonEmbedded.size(),
                nonEmbedded.size()
        );
    }

    /**
     * Check if any non-embedded fonts are used.
     */
    public static boolean hasNonEmbeddedFonts(PdfDocument doc) {
        return !audit(doc).nonEmbeddedFonts().isEmpty();
    }

    private static String getFontBaseName(MemorySegment font) {
        try (Arena arena = Arena.ofConfined()) {
            // First call: get required buffer size
            long needed = (long) PageEditBindings.FPDFFont_GetBaseFontName.invokeExact(
                    font, MemorySegment.NULL, 0L);
            if (needed <= 0) return "<unknown>";

            MemorySegment buf = arena.allocate(needed);
            long written = (long) PageEditBindings.FPDFFont_GetBaseFontName.invokeExact(font, buf, needed);
            if (written <= 0) return "<unknown>";
            // The returned buffer is a NUL-terminated UTF-8 string
            return buf.getString(0);
        } catch (Throwable t) {
            return "<unknown>";
        }
    }

    private static String getFontFamilyName(MemorySegment font) {
        try (Arena arena = Arena.ofConfined()) {
            long needed = (long) PageEditBindings.FPDFFont_GetFamilyName.invokeExact(
                    font, MemorySegment.NULL, 0L);
            if (needed <= 0) return "";

            MemorySegment buf = arena.allocate(needed);
            long written = (long) PageEditBindings.FPDFFont_GetFamilyName.invokeExact(font, buf, needed);
            if (written <= 0) return "";
            return buf.getString(0);
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean getFontIsEmbedded(MemorySegment font) {
        try {
            return (int) PageEditBindings.FPDFFont_GetIsEmbedded.invokeExact(font) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int getFontWeight(MemorySegment font) {
        try {
            return (int) PageEditBindings.FPDFFont_GetWeight.invokeExact(font);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int getFontFlags(MemorySegment font) {
        try {
            return (int) PageEditBindings.FPDFFont_GetFlags.invokeExact(font);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int getFontItalicAngle(MemorySegment font) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment anglePtr = arena.allocate(ValueLayout.JAVA_INT);
            int ok = (int) PageEditBindings.FPDFFont_GetItalicAngle.invokeExact(font, anglePtr);
            if (ok == 0) return 0;
            return anglePtr.get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long getFontDataSize(MemorySegment font) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outBuflen = arena.allocate(ValueLayout.JAVA_LONG);
            int ok = (int) PageEditBindings.FPDFFont_GetFontData.invokeExact(
                    font, MemorySegment.NULL, 0L, outBuflen);
            if (ok == 0) return 0;
            return outBuflen.get(ValueLayout.JAVA_LONG, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static class FontBuilder {
        String baseName;
        String familyName;
        boolean embedded;
        int weight;
        int flags;
        int italicAngle;
        long fontDataSize;
        Set<Integer> pages;

        FontInfo build() {
            return new FontInfo(baseName, familyName, embedded, weight, flags,
                    italicAngle, fontDataSize, Collections.unmodifiableSet(pages));
        }
    }
}
