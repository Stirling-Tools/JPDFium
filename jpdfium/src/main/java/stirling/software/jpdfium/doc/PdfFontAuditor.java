package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.QpdfLib;
import stirling.software.jpdfium.util.NativeJsonParser;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Audit fonts used in a PDF document.
 *
 * <p>Enumerates every distinct font referenced by text objects on each page,
 * inside nested form XObjects, and across annotation appearance streams when
 * supported by the loaded native library, reporting the font name, family, weight, flags,
 * embedding status, italic angle, and font data size.
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

    private static final int FPDF_PAGEOBJ_TEXT = 1;
    private static final int FPDF_PAGEOBJ_FORM = 5;

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
     * One font dictionary as stored in the PDF, independent of whether any page draws with it.
     *
     * @param baseName      {@code /BaseFont} without the leading slash
     * @param subtype       {@code /Subtype} without the leading slash, e.g. {@code Type1}
     * @param encoding      named encoding, {@code custom} for an encoding dictionary without
     *                      {@code /BaseEncoding}, or empty
     * @param embedded      the font descriptor or its CID descendant carries a readable font
     *                      program
     * @param hasDescriptor the font or its CID descendant has a {@code /FontDescriptor}
     * @param cid           the font is a Type 0 font with a CID descendant
     * @param hasToUnicode  the font has a readable {@code /ToUnicode} stream
     * @param cidToGidMap   {@code Identity}, {@code stream}, or empty
     * @param pages         page indices whose resource dictionaries reach this font; empty for
     *                      fonts that only exist in unused resources or AcroForm {@code /DR}
     * @param object        {@code "obj gen"} for an indirect font, empty for a direct one
     */
    public record FontResource(
            String baseName,
            String subtype,
            String encoding,
            boolean embedded,
            boolean hasDescriptor,
            boolean cid,
            boolean hasToUnicode,
            String cidToGidMap,
            Set<Integer> pages,
            String object
    ) {
        /** True when no page resource dictionary reaches this font. */
        public boolean resourceOnly() {
            return pages.isEmpty();
        }
    }

    /**
     * Check whether font auditing is supported by the loaded native library.
     */
    public static boolean isSupported() {
        return PageEditBindings.FPDFPage_CountObjects != null
                && PageEditBindings.FPDFPage_GetObject != null
                && PageEditBindings.FPDFPageObj_GetType != null
                && PageEditBindings.FPDFTextObj_GetFont != null
                && PageEditBindings.FPDFFont_GetBaseFontName != null;
    }

    /**
     * Audit all fonts in the document.
     *
     * @param doc open PDF document
     * @return audit report
     */
    public static AuditReport audit(PdfDocument doc) {
        if (!isSupported()) {
            throw new JPDFiumException("Font auditing is not supported by this PDFium build");
        }
        // Track unique fonts by base name -> builder
        Map<String, FontBuilder> fontMap = new LinkedHashMap<>();

        for (int pageIdx = 0; pageIdx < doc.pageCount(); pageIdx++) {
            PdfPage page;
            try {
                page = doc.page(pageIdx);
            } catch (JPDFiumException unopenablePage) {
                continue;
            }
            try (page) {
                MemorySegment rawPage = page.rawHandleForRead();
                int objCount;
                try {
                    objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
                } catch (Throwable t) {
                    NativeRuntime.rethrowFatal(t);
                    throw new JPDFiumException("Failed to count page objects on page " + pageIdx, t);
                }

                Set<Long> seenOnPage = new HashSet<>();
                for (int i = 0; i < objCount; i++) {
                    MemorySegment obj;
                    try {
                        obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                    } catch (Throwable t) {
                        NativeRuntime.rethrowFatal(t);
                        throw new JPDFiumException("Failed to get page object " + i + " on page " + pageIdx, t);
                    }
                    if (obj.equals(MemorySegment.NULL)) continue;
                    scanObject(obj, pageIdx, fontMap, seenOnPage, 0);
                }

                if (AnnotationBindings.FPDFPage_GetAnnotCount != null) {
                    int annotCount = 0;
                    try {
                        annotCount = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(rawPage);
                    } catch (Throwable t) {
                        NativeRuntime.rethrowFatal(t);
                        throw new JPDFiumException("Failed to get annotation count on page " + pageIdx, t);
                    }
                    if (annotCount > 0) {
                        if (AnnotationBindings.FPDFAnnot_GetObjectCount == null
                                || AnnotationBindings.FPDFAnnot_GetObject == null) {
                            throw new JPDFiumException("Annotation font inspection is required but not supported by this PDFium build");
                        }
                        for (int a = 0; a < annotCount; a++) {
                            MemorySegment annot = MemorySegment.NULL;
                            try {
                                annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(rawPage, a);
                            } catch (Throwable t) {
                                NativeRuntime.rethrowFatal(t);
                                throw new JPDFiumException("Failed to get annotation " + a + " on page " + pageIdx, t);
                            }
                            if (annot.equals(MemorySegment.NULL)) continue;
                            try {
                                int annotObjCount = (int) AnnotationBindings.FPDFAnnot_GetObjectCount.invokeExact(annot);
                                for (int j = 0; j < annotObjCount; j++) {
                                    MemorySegment obj = (MemorySegment) AnnotationBindings.FPDFAnnot_GetObject.invokeExact(annot, j);
                                    if (obj.equals(MemorySegment.NULL)) continue;
                                    scanObject(obj, pageIdx, fontMap, seenOnPage, 0);
                                }
                            } catch (Throwable t) {
                                NativeRuntime.rethrowFatal(t);
                                throw t instanceof JPDFiumException jpe ? jpe : new JPDFiumException(t);
                            } finally {
                                try {
                                    AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot);
                                } catch (Throwable t) {
                                    NativeRuntime.rethrowFatal(t);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Merge fonts with the same base name (from different handle addresses)
        Map<String, FontBuilder> merged = new LinkedHashMap<>();
        for (FontBuilder fb : fontMap.values()) {
            FontBuilder existing = merged.get(fb.baseName);
            if (existing != null) {
                existing.pages.addAll(fb.pages);
                existing.embedded = existing.embedded && fb.embedded;
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
     * Audit every font resource in the document, not only fonts drawn by page objects.
     *
     * <p>Resource-only fonts are reported with an empty {@link FontInfo#pages()} set, no family
     * name, weight {@code -1}, flags {@code 0} and size {@code 0}. Type 3 fonts count as
     * embedded because their glyph procedures live in the document.
     *
     * <p>When the native build cannot inventory resources, this falls back to
     * {@link #audit(PdfDocument)}.
     *
     * @param doc open PDF document
     * @return page and resource font audit
     */
    public static AuditReport auditAll(PdfDocument doc) {
        AuditReport pageReport = audit(doc);
        List<FontResource> resources = inventory(doc);
        if (resources.isEmpty()) return pageReport;

        Map<String, FontInfo> pageByName = new LinkedHashMap<>();
        for (FontInfo font : pageReport.fonts()) pageByName.putIfAbsent(font.baseName(), font);

        Map<String, ResourceAggregate> aggregate = new LinkedHashMap<>();
        for (FontResource resource : resources) {
            if (resource.baseName().isEmpty()) continue;
            ResourceAggregate existing = aggregate.get(resource.baseName());
            if (existing == null) {
                aggregate.put(resource.baseName(), new ResourceAggregate(resource));
            } else {
                existing.add(resource);
            }
        }

        // Page-audit names missing from the resource inventory are PDFium fallback objects, not
        // font dictionaries the embedder can address; reporting them as unresolved would be
        // noise. Inventory names remain authoritative for what actually exists in the file.
        List<FontInfo> fonts = new ArrayList<>(aggregate.size());
        List<FontInfo> nonEmbedded = new ArrayList<>();
        for (ResourceAggregate agg : aggregate.values()) {
            FontInfo page = pageByName.get(agg.baseName);
            FontInfo font = agg.build(page);
            fonts.add(font);
            if (!font.embedded()) nonEmbedded.add(font);
        }
        return new AuditReport(
                Collections.unmodifiableList(fonts),
                Collections.unmodifiableList(nonEmbedded),
                fonts.size(),
                fonts.size() - nonEmbedded.size(),
                nonEmbedded.size());
    }

    /**
     * Check if any non-embedded font resources remain, including unused resource dictionaries
     * and AcroForm {@code /DR}.
     */
    public static boolean hasNonEmbeddedFonts(PdfDocument doc) {
        return !auditAll(doc).nonEmbeddedFonts().isEmpty();
    }

    /** True when this native build can report document-wide font resources. */
    public static boolean inventoryAvailable() {
        return QpdfLib.isFontInventorySupported();
    }

    /**
     * Inventory every font dictionary, including fonts that no page draws with.
     *
     * @param doc open PDF document
     * @return fonts in file order, or an empty list when the native build cannot inventory
     */
    public static List<FontResource> inventory(PdfDocument doc) {
        // sourceBytes() is the open-time snapshot. After an in-memory edit it no longer
        // describes the document, so inventory the serialized current state instead.
        byte[] source = doc.sourceBytes();
        if (doc.isContentModified() || source == null) source = doc.saveBytes();
        return source == null ? List.of() : inventoryBytes(source);
    }

    /**
     * Names of every non-embedded font resource, including unused resource dictionaries and
     * AcroForm {@code /DR}. Falls back to the page audit when resource inventory is unavailable.
     */
    static List<String> unembeddedNames(byte[] pdf) {
        Set<String> names = new LinkedHashSet<>();
        for (FontResource resource : unembeddedResources(pdf)) names.add(resource.baseName());
        return List.copyOf(names);
    }

    /**
     * Non-embedded font resources, one entry per resource, or one synthetic entry per page font
     * when the native inventory is unavailable.
     */
    static List<FontResource> unembeddedResources(byte[] pdf) {
        if (pdf == null || pdf.length == 0) return List.of();
        if (QpdfLib.isFontInventorySupported()) {
            String json = QpdfLib.inspectFonts(pdf);
            if (json != null) {
                List<FontResource> all = parseInventory(json);
                if (!all.isEmpty()) return nonEmbeddedResources(all);
            }
        }
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            return auditFallback(doc);
        } catch (Exception e) {
            throw e instanceof JPDFiumException jpe ? jpe : new JPDFiumException(e);
        }
    }

    /**
     * File-backed form of {@link #unembeddedResources(byte[])}. Uses the native file inventory
     * when available so the input is never materialized on the Java heap.
     */
    static List<FontResource> unembeddedResources(Path pdf) {
        if (QpdfLib.isFileFontInventorySupported()) {
            String json = QpdfLib.inspectFonts(pdf);
            if (json != null) {
                List<FontResource> all = parseInventory(json);
                if (!all.isEmpty()) return nonEmbeddedResources(all);
            }
        }
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            return auditFallback(doc);
        } catch (Exception e) {
            throw e instanceof JPDFiumException jpe ? jpe : new JPDFiumException(e);
        }
    }

    private static List<FontResource> nonEmbeddedResources(List<FontResource> all) {
        List<FontResource> resources = new ArrayList<>();
        for (FontResource resource : all) {
            if (resource.baseName().isEmpty() || resource.embedded()
                    || "Type3".equals(resource.subtype())) continue;
            resources.add(resource);
        }
        return resources;
    }

    private static List<FontResource> auditFallback(PdfDocument doc) {
        List<FontResource> resources = new ArrayList<>();
        for (FontInfo font : audit(doc).nonEmbeddedFonts()) {
            resources.add(new FontResource(
                    font.baseName(), "", "", false, false, false, false, "", Set.of(), ""));
        }
        return resources;
    }

    static List<FontResource> inventoryBytes(byte[] pdf) {
        if (pdf == null || pdf.length == 0) return List.of();
        String json = QpdfLib.inspectFonts(pdf);
        return json == null ? List.of() : parseInventory(json);
    }

    private static List<FontResource> parseInventory(String json) {
        if (json.length() <= 2) return List.of();
        List<Map<String, String>> parsed = NativeJsonParser.parseArray(json);
        List<FontResource> resources = new ArrayList<>(parsed.size());
        for (Map<String, String> field : parsed) {
            String baseName = field.getOrDefault("baseFont", "");
            if (baseName.isEmpty()) continue;
            resources.add(new FontResource(
                    baseName,
                    field.getOrDefault("subtype", ""),
                    field.getOrDefault("encoding", ""),
                    "true".equals(field.get("program")),
                    "true".equals(field.get("descriptor")),
                    "true".equals(field.get("cid")),
                    "true".equals(field.get("toUnicode")),
                    field.getOrDefault("cidToGid", ""),
                    parsePages(field.get("pages")),
                    field.getOrDefault("object", "")));
        }
        return resources;
    }

    private static Set<Integer> parsePages(String pages) {
        if (pages == null || pages.isEmpty()) return Set.of();
        Set<Integer> result = new TreeSet<>();
        for (String token : pages.split(",")) {
            int dash = token.indexOf('-');
            try {
                if (dash < 0) {
                    result.add(Integer.parseInt(token));
                } else {
                    int start = Integer.parseInt(token.substring(0, dash));
                    int end = Integer.parseInt(token.substring(dash + 1));
                    for (int page = start; page <= end; page++) result.add(page);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static final class ResourceAggregate {
        private final String baseName;
        private boolean embedded = true;
        private final Set<Integer> pages = new TreeSet<>();

        ResourceAggregate(FontResource first) {
            baseName = first.baseName();
            add(first);
        }

        void add(FontResource resource) {
            // A Type 3 font is itself embedded, but sharing its /BaseFont with an
            // unembedded TrueType/Type1 font must not make that font look embedded.
            boolean resourceEmbedded = resource.embedded()
                    || "Type3".equals(resource.subtype())
                    || "/Type3".equals(resource.subtype());
            embedded &= resourceEmbedded;
            pages.addAll(resource.pages());
        }

        FontInfo build(FontInfo page) {
            // Type 3 resources are already counted as embedded by add(); an
            // unembedded simple font with the same name must keep the aggregate
            // non-embedded.
            boolean effectiveEmbedded = this.embedded;
            if (page == null) {
                // Resource-only fonts have no descriptor to read flags from. Zero is
                // "no flags", not "unknown", so bit tests cannot see every bit set.
                return new FontInfo(baseName, "", effectiveEmbedded, -1, 0, 0, 0,
                        Collections.unmodifiableSet(pages));
            }
            Set<Integer> usedPages = page.pages().isEmpty() ? pages : page.pages();
            return new FontInfo(
                    page.baseName(),
                    page.familyName(),
                    effectiveEmbedded,
                    page.weight(),
                    page.flags(),
                    page.italicAngle(),
                    page.fontDataSize(),
                    usedPages);
        }
    }

    private static void scanObject(
            MemorySegment obj,
            int pageIdx,
            Map<String, FontBuilder> fontMap,
            Set<Long> seenOnPage,
            int depth) {
        if (depth > 32) {
            throw new JPDFiumException(
                    "Form XObject nesting exceeds 32 levels; font audit is incomplete");
        }
        int type;
        try {
            type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw t instanceof JPDFiumException jpe ? jpe : new JPDFiumException(t);
        }
        if (type == FPDF_PAGEOBJ_TEXT) {
            MemorySegment font;
            try {
                font = (MemorySegment) PageEditBindings.FPDFTextObj_GetFont.invokeExact(obj);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                throw t instanceof JPDFiumException jpe ? jpe : new JPDFiumException(t);
            }
            if (font.equals(MemorySegment.NULL)) return;
            long fontAddr = font.address();
            if (!seenOnPage.add(fontAddr)) return;
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
            return;
        }
        if (type != FPDF_PAGEOBJ_FORM) return;
        if (!PageEditBindings.formTraversalAvailable()) {
            throw new JPDFiumException("Form XObject traversal not supported in this PDFium build");
        }
        int children;
        try {
            children = (int) PageEditBindings.FPDFFormObj_CountObjects.invokeExact(obj);
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw t instanceof JPDFiumException jpe ? jpe : new JPDFiumException(t);
        }
        for (int i = 0; i < children; i++) {
            MemorySegment child;
            try {
                child = PageEditBindings.formObject(obj, i);
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                throw new JPDFiumException("Failed to get form child object " + i, t);
            }
            if (!child.equals(MemorySegment.NULL)) {
                scanObject(child, pageIdx, fontMap, seenOnPage, depth + 1);
            }
        }
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
            long length = 0;
            while (length < written && buf.get(ValueLayout.JAVA_BYTE, length) != 0) length++;
            byte[] raw = buf.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE);
            try {
                return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();
            } catch (CharacterCodingException latin1) {
                return new String(raw, StandardCharsets.ISO_8859_1);
            }
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
