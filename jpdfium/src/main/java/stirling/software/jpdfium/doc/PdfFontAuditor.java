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
    ) {
        public FontInfo {
            pages = pages == null ? Set.of() : Set.copyOf(pages);
        }
    }

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
    ) {
        public AuditReport {
            fonts = fonts == null ? List.of() : List.copyOf(fonts);
            nonEmbeddedFonts = nonEmbeddedFonts == null ? List.of() : List.copyOf(nonEmbeddedFonts);
        }
    }

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
        public FontResource {
            pages = pages == null ? Set.of() : Set.copyOf(pages);
        }

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
                throw new JPDFiumException(
                        "font audit is incomplete: unable to open page " + pageIdx, unopenablePage);
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
                    int annotCount;
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
                            MemorySegment annot;
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
                            } catch (JPDFiumException jpe) {
                                throw jpe;
                            } catch (Throwable t) {
                                NativeRuntime.rethrowFatal(t);
                                throw new JPDFiumException(t);
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
        // Prefer the original file so a large file-backed document is never
        // serialized just to list its font resources.
        Path sourcePath = doc.sourcePath();
        if (!doc.isContentModified()
                && sourcePath != null
                && QpdfLib.isFileFontInventorySupported()) {
            String json = QpdfLib.inspectFonts(sourcePath);
            if (json != null) {
                List<FontResource> parsed = parseInventory(json);
                if (!parsed.isEmpty()) return parsed;
            }
        }
        // sourceBytes() is the open-time snapshot. After an in-memory edit it no longer
        // describes the document, so inventory the serialized current state instead.
        byte[] source = doc.sourceBytes();
        if (doc.isContentModified() || source == null) source = doc.saveBytes();
        return source == null ? List.of() : inventoryBytes(source);
    }

    /**
     * Single-parse font inventory snapshot combining unembedded resources and corrupt embedded
     * font keys from one {@link QpdfLib#inspectFonts} pass.
     */
    record FontInventorySnapshot(
            List<FontResource> allResources,
            List<FontResource> unembeddedResources,
            List<String> unembeddedNames,
            List<String> corruptEmbeddedKeys,
            boolean inventoryUsed) {
        FontInventorySnapshot {
            allResources = List.copyOf(allResources);
            unembeddedResources = List.copyOf(unembeddedResources);
            unembeddedNames = List.copyOf(unembeddedNames);
            corruptEmbeddedKeys = List.copyOf(corruptEmbeddedKeys);
        }

        boolean hasIssues() {
            return !unembeddedResources.isEmpty() || !corruptEmbeddedKeys.isEmpty();
        }
    }

    private static final FontInventorySnapshot EMPTY_SNAPSHOT =
            new FontInventorySnapshot(List.of(), List.of(), List.of(), List.of(), false);

    /** Inspect unembedded and corrupt fonts in one QPDF inventory parse. */
    static FontInventorySnapshot inspectInventory(byte[] pdf) {
        if (pdf == null || pdf.length == 0) return EMPTY_SNAPSHOT;
        if (QpdfLib.isFontInventorySupported()) {
            String json = QpdfLib.inspectFonts(pdf);
            if (json != null) {
                return parseSnapshot(json);
            }
        }
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            if (QpdfLib.isFontInventorySupported()) {
                List<FontResource> all = inventory(doc);
                if (!all.isEmpty()) {
                    List<FontResource> nonEmb = nonEmbeddedResources(all);
                    return new FontInventorySnapshot(
                            all, nonEmb, uniqueNames(nonEmb), List.of(), false);
                }
            }
            List<FontResource> fallback = auditFallback(doc);
            return new FontInventorySnapshot(
                    List.of(), fallback, uniqueNames(fallback), List.of(), false);
        } catch (JPDFiumException jpe) {
            throw jpe;
        } catch (Exception e) {
            throw new JPDFiumException(e);
        }
    }

    /** File-backed single-parse inspection using native file inventory when available. */
    static FontInventorySnapshot inspectInventory(Path pdf) {
        if (pdf == null) return EMPTY_SNAPSHOT;
        if (QpdfLib.isFileFontInventorySupported()) {
            String json = QpdfLib.inspectFonts(pdf);
            if (json != null) {
                return parseSnapshot(json);
            }
        }
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            if (QpdfLib.isFontInventorySupported()) {
                List<FontResource> all = inventory(doc);
                if (!all.isEmpty()) {
                    List<FontResource> nonEmb = nonEmbeddedResources(all);
                    return new FontInventorySnapshot(
                            all, nonEmb, uniqueNames(nonEmb), List.of(), false);
                }
            }
            List<FontResource> fallback = auditFallback(doc);
            return new FontInventorySnapshot(
                    List.of(), fallback, uniqueNames(fallback), List.of(), false);
        } catch (JPDFiumException jpe) {
            throw jpe;
        } catch (Exception e) {
            throw new JPDFiumException(e);
        }
    }

    /**
     * Names of every non-embedded font resource, including unused resource dictionaries and
     * AcroForm {@code /DR}. Falls back to the page audit when resource inventory is unavailable.
     */
    static List<String> unembeddedNames(byte[] pdf) {
        return inspectInventory(pdf).unembeddedNames();
    }

    /**
     * Non-embedded font resources, one entry per resource, or one synthetic entry per page font
     * when the native inventory is unavailable.
     */
    static List<FontResource> unembeddedResources(byte[] pdf) {
        return inspectInventory(pdf).unembeddedResources();
    }

    /**
     * File-backed form of {@link #unembeddedResources(byte[])}. Uses the native file inventory
     * when available so the input is never materialized on the Java heap.
     */
    static List<FontResource> unembeddedResources(Path pdf) {
        return inspectInventory(pdf).unembeddedResources();
    }

    private static FontInventorySnapshot parseSnapshot(String json) {
        if (json.length() <= 2) {
            return new FontInventorySnapshot(List.of(), List.of(), List.of(), List.of(), true);
        }
        List<Map<String, String>> parsed = NativeJsonParser.parseArray(json);
        List<FontResource> all = new ArrayList<>(parsed.size());
        Set<String> corrupt = new LinkedHashSet<>();
        for (Map<String, String> field : parsed) {
            String baseName = field.getOrDefault("baseFont", "");
            if (baseName.isEmpty()) continue;
            all.add(toFontResource(baseName, field));
            if ("true".equals(field.get("program"))
                    && "false".equals(field.get("programValid"))) {
                corrupt.add(baseName);
            }
        }
        List<FontResource> unembedded = nonEmbeddedResources(all);
        return new FontInventorySnapshot(
                all, unembedded, uniqueNames(unembedded), List.copyOf(corrupt), true);
    }

    private static List<String> uniqueNames(List<FontResource> resources) {
        if (resources.isEmpty()) return List.of();
        Set<String> names = new LinkedHashSet<>();
        for (FontResource resource : resources) names.add(resource.baseName());
        return List.copyOf(names);
    }

    private static FontResource toFontResource(String baseName, Map<String, String> field) {
        return new FontResource(
                baseName,
                field.getOrDefault("subtype", ""),
                field.getOrDefault("encoding", ""),
                "true".equals(field.get("program")),
                "true".equals(field.get("descriptor")),
                "true".equals(field.get("cid")),
                "true".equals(field.get("toUnicode")),
                field.getOrDefault("cidToGid", ""),
                parsePages(field.get("pages")),
                field.getOrDefault("object", ""));
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
            resources.add(toFontResource(baseName, field));
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
        } catch (JPDFiumException jpe) {
            throw jpe;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException(t);
        }
        if (type == FPDF_PAGEOBJ_TEXT) {
            MemorySegment font;
            try {
                font = (MemorySegment) PageEditBindings.FPDFTextObj_GetFont.invokeExact(obj);
            } catch (JPDFiumException jpe) {
                throw jpe;
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                throw new JPDFiumException(t);
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
        } catch (JPDFiumException jpe) {
            throw jpe;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            throw new JPDFiumException(t);
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

    /**
     * Result of walking page content for font usage. {@code complete} is false when any page,
     * form XObject, or annotation appearance could not be inspected, in which case absence from
     * {@link #visible()} says nothing about whether a font is painted.
     */
    record FontUsageScan(Set<String> visible, Set<String> invisible, boolean complete) {
        FontUsageScan {
            visible = visible == null ? Set.of() : Set.copyOf(visible);
            invisible = invisible == null ? Set.of() : Set.copyOf(invisible);
        }

        static FontUsageScan incomplete() {
            return new FontUsageScan(Set.of(), Set.of(), false);
        }

        /** True only when every observed use of {@code key} is rendering mode 3. */
        boolean invisibleOnly(String key) {
            return complete && invisible.contains(key) && !visible.contains(key);
        }
    }

    /**
     * Font base names used by text objects that are actually painted. Text in rendering mode 3
     * (invisible OCR layers) is excluded. Nested form XObjects are traversed when the native build
     * supports it.
     *
     * @return the keys, or {@code null} when any part of the page content could not be inspected
     */
    public static Set<String> visibleFontKeys(PdfDocument doc) {
        FontUsageScan scan = scanFontUsage(doc);
        return scan.complete() ? scan.visible() : null;
    }

    /**
     * Classify every text object by render mode. A partial walk must not be used for suppression:
     * an unvisited font would be mistaken for invisible text and skipped by best-effort embedding.
     */
    static FontUsageScan scanFontUsage(PdfDocument doc) {
        Set<String> visible = new LinkedHashSet<>();
        Set<String> invisible = new LinkedHashSet<>();
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                MemorySegment raw = page.rawHandleForRead();
                int annotations =
                        (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(raw);
                // Annotation appearance streams are not walked here, so the
                // result would be incomplete.
                if (annotations > 0) return FontUsageScan.incomplete();
                int count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(raw);
                for (int j = 0; j < count; j++) {
                    MemorySegment obj =
                            (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(raw, j);
                    if (!collectFontUsage(obj, visible, invisible, 0)) {
                        return FontUsageScan.incomplete();
                    }
                }
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                // A page that cannot be walked tells us nothing; signal an
                // incomplete result instead of hiding fonts.
                return FontUsageScan.incomplete();
            }
        }
        return new FontUsageScan(visible, invisible, true);
    }

    /**
     * Embedded font base names whose stored program is empty, unreadable, or
     * does not start with a recognized font magic. Such a font renders with a
     * fallback and should be replaced when an embeddable substitute exists.
     */
    public static Set<String> corruptEmbeddedKeys(PdfDocument doc) {
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                MemorySegment raw = page.rawHandleForRead();
                int count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(raw);
                for (int j = 0; j < count; j++) {
                    MemorySegment obj =
                            (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(raw, j);
                    collectCorruptFonts(obj, keys, 0);
                }
            } catch (Throwable t) {
                NativeRuntime.rethrowFatal(t);
                // Best effort; leave unknown fonts alone.
            }
        }
        return keys;
    }

    /**
     * Names of fonts whose descriptor points at an embedded program that is not a
     * recognizable font program. Uses the qpdf inventory so a program PDFium refuses to expose
     * is still detected.
     */
    static List<String> corruptEmbeddedKeys(byte[] pdf) {
        if (pdf == null || pdf.length == 0 || !QpdfLib.isFontInventorySupported()) return List.of();
        return inspectInventory(pdf).corruptEmbeddedKeys();
    }

    private static void collectCorruptFonts(MemorySegment obj, Set<String> keys, int depth) {
        if (depth > 64 || obj == null || obj.equals(MemorySegment.NULL)) return;
        try {
            int type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
            if (type == 1) {
                MemorySegment font =
                        (MemorySegment) PageEditBindings.FPDFTextObj_GetFont.invokeExact(obj);
                String name = getFontBaseName(font);
                if (name.isEmpty() || "<unknown>".equals(name)) return;
                if ((int) PageEditBindings.FPDFFont_GetIsEmbedded.invokeExact(font) == 1) {
                    byte[] data = getFontProgram(font);
                    if (!looksLikeFontProgram(data)) {
                        keys.add(name);
                    }
                }
            } else if (type == 5 && PageEditBindings.formTraversalAvailable()) {
                int children =
                        (int) PageEditBindings.FPDFFormObj_CountObjects.invokeExact(obj);
                for (int i = 0; i < children; i++) {
                    collectCorruptFonts(PageEditBindings.formObject(obj, i), keys, depth + 1);
                }
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            // Best effort.
        }
    }

    private static byte[] getFontProgram(MemorySegment font) {
        try {
            long needed = getFontDataSize(font);
            if (needed <= 0 || needed > 64L * 1024 * 1024) return null;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(needed);
                MemorySegment outLen = arena.allocate(ValueLayout.JAVA_LONG);
                int ok = (int) PageEditBindings.FPDFFont_GetFontData.invokeExact(
                        font, buf, needed, outLen);
                if (ok == 0) return null;
                long len = Math.min(needed, outLen.get(ValueLayout.JAVA_LONG, 0));
                if (len <= 0) return null;
                return buf.asSlice(0, len).toArray(ValueLayout.JAVA_BYTE);
            }
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            return null;
        }
    }

    static boolean looksLikeFontProgram(byte[] data) {
        if (data == null || data.length < 4) return false;
        if (data[0] == 0x00 && data[1] == 0x01 && data[2] == 0x00 && data[3] == 0x00) return true;
        // CFF header: major=1, minor=0, hdrSize>=4, offSize in 1..4. OffSize
        // selects the byte width of the Top DICT INDEX offsets (CFF spec 5176).
        if (data[0] == 0x01 && data[1] == 0x00 && (data[2] & 0xFF) >= 0x04
                && (data[2] & 0xFF) < data.length && (data[3] & 0xFF) >= 0x01
                && (data[3] & 0xFF) <= 0x04) {
            return true;
        }
        String head = new String(data, 0, Math.min(4, data.length), StandardCharsets.ISO_8859_1);
        if ("OTTO".equals(head) || "true".equals(head) || "ttcf".equals(head)) return true;
        return (data[0] == '%' && data[1] == '!')
                || ((data[0] & 0xFF) == 0x80 && data[1] == 0x01);
    }

    private static boolean collectFontUsage(
            MemorySegment obj, Set<String> visible, Set<String> invisible, int depth) {
        if (depth > 64 || obj == null || obj.equals(MemorySegment.NULL)) return false;
        try {
            int type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
            if (type == 1) {
                int renderMode =
                        (int) PageEditBindings.FPDFTextObj_GetTextRenderMode.invokeExact(obj);
                MemorySegment font =
                        (MemorySegment) PageEditBindings.FPDFTextObj_GetFont.invokeExact(obj);
                String name = getFontBaseName(font);
                if (!name.isEmpty() && !"<unknown>".equals(name)) {
                    (renderMode == 3 ? invisible : visible).add(PdfFontEmbedder.key(name));
                }
            } else if (type == 5) {
                if (!PageEditBindings.formTraversalAvailable()) return false;
                int children =
                        (int) PageEditBindings.FPDFFormObj_CountObjects.invokeExact(obj);
                for (int i = 0; i < children; i++) {
                    MemorySegment child = PageEditBindings.formObject(obj, i);
                    if (!collectFontUsage(child, visible, invisible, depth + 1)) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            NativeRuntime.rethrowFatal(t);
            // A failed child scan makes the whole classification incomplete.
            return false;
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

    private static final class FontBuilder {
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
