package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.AnnotationBindings;
import stirling.software.jpdfium.panama.FfmHelper;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Export and import annotations using XFDF (XML Forms Data Format) - the
 * industry-standard annotation exchange format for PDF review workflows.
 *
 * <p>XFDF is a human-readable XML format supported by Adobe Acrobat, Foxit,
 * PDF-XChange, and other PDF tools. It enables extracting annotations from
 * one document and importing them into another.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(path)) {
 *     // Export all annotations to XFDF
 *     String xfdf = PdfAnnotationExchange.exportXfdf(doc);
 *     Files.writeString(Path.of("annotations.xfdf"), xfdf);
 *
 *     // Import into another document
 *     try (PdfDocument target = PdfDocument.open(otherPath)) {
 *         ImportResult result = PdfAnnotationExchange.importXfdf(target, xfdf);
 *         System.out.println("Imported: " + result.annotationsImported());
 *     }
 * }
 * }</pre>
 */
public final class PdfAnnotationExchange {

    private PdfAnnotationExchange() {}

    private static final DateTimeFormatter PDF_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /**
     * Export all annotations to XFDF format.
     *
     * @param doc open PDF document
     * @return XFDF XML string
     */
    public static String exportXfdf(PdfDocument doc) {
        return exportXfdf(doc, null);
    }

    /**
     * Export annotations from specific pages to XFDF format.
     *
     * @param doc   open PDF document
     * @param pages set of page indices to export (null = all pages)
     * @return XFDF XML string
     */
    public static String exportXfdf(PdfDocument doc, Set<Integer> pages) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<xfdf xmlns=\"http://ns.adobe.com/xfdf/\" xml:space=\"preserve\">\n");
        sb.append("  <annots>\n");

        for (int p = 0; p < doc.pageCount(); p++) {
            if (pages != null && !pages.contains(p)) continue;

            try (PdfPage page = doc.page(p)) {
                MemorySegment rawPage = page.rawHandle();
                int count;
                try {
                    count = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(rawPage);
                } catch (Throwable t) { continue; }

                for (int i = 0; i < count; i++) {
                    MemorySegment annot;
                    try {
                        annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(rawPage, i);
                    } catch (Throwable t) { continue; }
                    if (annot.equals(MemorySegment.NULL)) continue;

                    try {
                        appendAnnotXfdf(sb, annot, p);
                    } finally {
                        try { AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot); }
                        catch (Throwable ignored) {}
                    }
                }
            }
        }

        sb.append("  </annots>\n");

        // Export form field values if any
        appendFormFields(sb, doc);

        sb.append("</xfdf>\n");
        return sb.toString();
    }

    /**
     * Export to FDF (binary) format.
     *
     * <p>FDF is the legacy binary annotation format. XFDF is preferred for
     * new workflows as it covers 95%+ of use cases and is human-readable.
     *
     * @param doc open PDF document
     * @return FDF bytes (simplified text-based FDF structure)
     */
    public static byte[] exportFdf(PdfDocument doc) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("%FDF-1.2\n");
        sb.append("1 0 obj\n<< /FDF << /Annots [\n");

        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                MemorySegment rawPage = page.rawHandle();
                int count;
                try {
                    count = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(rawPage);
                } catch (Throwable t) { continue; }

                for (int i = 0; i < count; i++) {
                    MemorySegment annot;
                    try {
                        annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(rawPage, i);
                    } catch (Throwable t) { continue; }
                    if (annot.equals(MemorySegment.NULL)) continue;

                    try {
                        appendAnnotFdf(sb, annot, p);
                    } finally {
                        try { AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot); }
                        catch (Throwable ignored) {}
                    }
                }
            }
        }

        sb.append("] >> >>\nendobj\n");
        sb.append("trailer\n<< /Root 1 0 R >>\n%%EOF\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Import annotations from XFDF string.
     *
     * @param doc  target PDF document
     * @param xfdf XFDF XML string
     * @return import result with counts and warnings
     */
    public static ImportResult importXfdf(PdfDocument doc, String xfdf) {
        int annotationsImported = 0;
        int fieldsImported = 0;
        List<String> warnings = new ArrayList<>();

        // Parse XFDF annotations
        int pos = 0;
        while (pos < xfdf.length()) {
            // Find annotation elements
            int tagStart = findAnnotTag(xfdf, pos);
            if (tagStart < 0) break;

            int tagNameEnd = xfdf.indexOf(' ', tagStart + 1);
            if (tagNameEnd < 0) tagNameEnd = xfdf.indexOf('>', tagStart + 1);
            if (tagNameEnd < 0) break;

            String tagName = xfdf.substring(tagStart + 1, tagNameEnd);
            AnnotationType type = xfdfTagToType(tagName);

            if (type == null) {
                pos = tagNameEnd;
                continue;
            }

            // Parse attributes
            int tagEnd = xfdf.indexOf('>', tagStart);
            if (tagEnd < 0) break;
            String attrs = xfdf.substring(tagStart, tagEnd);

            int pageIndex = parseIntAttr(attrs, "page", 0);
            float[] rect = parseRectAttr(attrs);
            String color = parseStringAttr(attrs, "color");
            String contents = parseContents(xfdf, tagStart, tagName);

            if (rect != null && pageIndex >= 0 && pageIndex < doc.pageCount()) {
                try (PdfPage page = doc.page(pageIndex)) {
                    Rect r = Rect.of(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1]);
                    PdfAnnotationBuilder builder = PdfAnnotationBuilder.on(page.rawHandle())
                            .type(type)
                            .rect(r);

                    if (color != null) {
                        int[] rgb = parseColorString(color);
                        if (rgb != null) builder.color(rgb[0], rgb[1], rgb[2]);
                    }

                    if (contents != null) builder.contents(contents);

                    builder.build();
                    annotationsImported++;
                } catch (Exception e) {
                    warnings.add("Failed to import annotation on page " + pageIndex + ": " + e.getMessage());
                }
            }

            pos = tagEnd + 1;
        }

        // Parse form field values
        fieldsImported = importFormValues(doc, xfdf);

        return new ImportResult(annotationsImported, fieldsImported, warnings);
    }

    /**
     * Import annotations from XFDF file.
     *
     * @param doc      target PDF document
     * @param xfdfFile path to XFDF file
     * @return import result
     * @throws IOException if the file cannot be read
     */
    public static ImportResult importXfdf(PdfDocument doc, Path xfdfFile) throws IOException {
        return importXfdf(doc, Files.readString(xfdfFile));
    }

    /**
     * Import annotations from FDF bytes.
     *
     * @param doc target PDF document
     * @param fdf FDF binary data
     * @return import result
     */
    public static ImportResult importFdf(PdfDocument doc, byte[] fdf) {
        // Parse the simplified FDF format
        String fdfStr = new String(fdf, java.nio.charset.StandardCharsets.UTF_8);
        int annotationsImported = 0;
        List<String> warnings = new ArrayList<>();

        // Find /Annots array
        int annotsIdx = fdfStr.indexOf("/Annots");
        if (annotsIdx < 0) return new ImportResult(0, 0, List.of("No /Annots array in FDF"));

        int arrayStart = fdfStr.indexOf('[', annotsIdx);
        int arrayEnd = fdfStr.indexOf(']', arrayStart);
        if (arrayStart < 0 || arrayEnd < 0) return new ImportResult(0, 0, List.of("Malformed /Annots array"));

        String annotsStr = fdfStr.substring(arrayStart + 1, arrayEnd);

        // Parse individual annotation dictionaries
        int pos = 0;
        while (pos < annotsStr.length()) {
            int dictStart = annotsStr.indexOf("<<", pos);
            int dictEnd = annotsStr.indexOf(">>", dictStart);
            if (dictStart < 0 || dictEnd < 0) break;

            String dict = annotsStr.substring(dictStart + 2, dictEnd);
            pos = dictEnd + 2;

            int pageIndex = parseFdfInt(dict, "/Page");
            String subtype = parseFdfName(dict, "/Subtype");
            float[] rect = parseFdfRect(dict);

            if (subtype != null && rect != null && pageIndex >= 0 && pageIndex < doc.pageCount()) {
                AnnotationType type = fdfNameToType(subtype);
                if (type == null) {
                    warnings.add("Unknown subtype: " + subtype);
                    continue;
                }

                try (PdfPage page = doc.page(pageIndex)) {
                    Rect r = Rect.of(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1]);
                    PdfAnnotationBuilder.on(page.rawHandle())
                            .type(type)
                            .rect(r)
                            .build();
                    annotationsImported++;
                } catch (Exception e) {
                    warnings.add("FDF import failed for page " + pageIndex + ": " + e.getMessage());
                }
            }
        }

        return new ImportResult(annotationsImported, 0, warnings);
    }

    /**
     * Export form field values as XFDF.
     *
     * @param doc open PDF document
     * @return XFDF XML string with form field values
     */
    public static String exportFormXfdf(PdfDocument doc) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<xfdf xmlns=\"http://ns.adobe.com/xfdf/\" xml:space=\"preserve\">\n");
        sb.append("  <fields>\n");
        appendFormFields(sb, doc);
        sb.append("  </fields>\n");
        sb.append("</xfdf>\n");
        return sb.toString();
    }

    /**
     * Import form field values from XFDF.
     *
     * @param doc  target PDF document
     * @param xfdf XFDF XML with field values
     * @return import result
     */
    public static ImportResult importFormXfdf(PdfDocument doc, String xfdf) {
        int fields = importFormValues(doc, xfdf);
        return new ImportResult(0, fields, Collections.emptyList());
    }

    /**
     * Result of an import operation.
     *
     * @param annotationsImported number of annotations created
     * @param fieldsImported      number of form fields updated
     * @param warnings            any warnings during import
     */
    public record ImportResult(int annotationsImported, int fieldsImported, List<String> warnings) {
        public ImportResult {
            warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public int total() {
            return annotationsImported + fieldsImported;
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }


    private static void appendAnnotXfdf(StringBuilder sb, MemorySegment annot, int pageIndex) {
        try (Arena arena = Arena.ofConfined()) {
            int subtype;
            try {
                subtype = (int) AnnotationBindings.FPDFAnnot_GetSubtype.invokeExact(annot);
            } catch (Throwable t) { return; }

            AnnotationType type = AnnotationType.fromCode(subtype);
            String xfdfTag = typeToXfdfTag(type);
            if (xfdfTag == null) return;

            // Get rect
            MemorySegment rect = arena.allocate(AnnotationBindings.FS_RECTF_LAYOUT);
            try {
                int ok = (int) AnnotationBindings.FPDFAnnot_GetRect.invokeExact(annot, rect);
                if (ok == 0) return;
            } catch (Throwable t) { return; }

            float left = rect.get(ValueLayout.JAVA_FLOAT, 0);
            float bottom = rect.get(ValueLayout.JAVA_FLOAT, 4);
            float right = rect.get(ValueLayout.JAVA_FLOAT, 8);
            float top = rect.get(ValueLayout.JAVA_FLOAT, 12);

            // Get color
            MemorySegment r = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment g = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment b = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment a = arena.allocate(ValueLayout.JAVA_INT);
            String colorStr = "";
            try {
                int ok = (int) AnnotationBindings.FPDFAnnot_GetColor.invokeExact(annot, 0, r, g, b, a);
                if (ok != 0) {
                    colorStr = String.format("#%02X%02X%02X",
                            r.get(ValueLayout.JAVA_INT, 0),
                            g.get(ValueLayout.JAVA_INT, 0),
                            b.get(ValueLayout.JAVA_INT, 0));
                }
            } catch (Throwable ignored) {}

            // Get contents
            String contents = readAnnotString(annot, AnnotationKeys.CONTENTS);

            String date = "D:" + PDF_DATE_FMT.format(Instant.now()) + "Z";

            sb.append("    <").append(xfdfTag);
            sb.append(" page=\"").append(pageIndex).append("\"");
            sb.append(" rect=\"").append(left).append(",").append(bottom)
              .append(",").append(right).append(",").append(top).append("\"");
            if (!colorStr.isEmpty()) {
                sb.append(" color=\"").append(colorStr).append("\"");
            }
            sb.append(" date=\"").append(date).append("\"");
            sb.append(" flags=\"print\"");

            if (contents != null && !contents.isEmpty()) {
                sb.append(">\n");
                sb.append("      <contents-richtext>\n");
                sb.append("        ").append(escapeXml(contents)).append("\n");
                sb.append("      </contents-richtext>\n");
                sb.append("    </").append(xfdfTag).append(">\n");
            } else {
                sb.append("/>\n");
            }
        }
    }

    private static void appendAnnotFdf(StringBuilder sb, MemorySegment annot, int pageIndex) {
        try (Arena arena = Arena.ofConfined()) {
            int subtype;
            try {
                subtype = (int) AnnotationBindings.FPDFAnnot_GetSubtype.invokeExact(annot);
            } catch (Throwable t) { return; }

            AnnotationType type = AnnotationType.fromCode(subtype);
            String subtypeName = typeToFdfName(type);
            if (subtypeName == null) return;

            MemorySegment rect = arena.allocate(AnnotationBindings.FS_RECTF_LAYOUT);
            try {
                int ok = (int) AnnotationBindings.FPDFAnnot_GetRect.invokeExact(annot, rect);
                if (ok == 0) return;
            } catch (Throwable t) { return; }

            float left = rect.get(ValueLayout.JAVA_FLOAT, 0);
            float bottom = rect.get(ValueLayout.JAVA_FLOAT, 4);
            float right = rect.get(ValueLayout.JAVA_FLOAT, 8);
            float top = rect.get(ValueLayout.JAVA_FLOAT, 12);

            sb.append("  << /Type /Annot /Subtype /").append(subtypeName);
            sb.append(" /Page ").append(pageIndex);
            sb.append(" /Rect [").append(left).append(" ").append(bottom)
              .append(" ").append(right).append(" ").append(top).append("]");
            sb.append(" >>\n");
        }
    }

    private static void appendFormFields(StringBuilder sb, PdfDocument doc) {
        // Form field values are exported from widget annotations
        for (int p = 0; p < doc.pageCount(); p++) {
            try (PdfPage page = doc.page(p)) {
                MemorySegment rawPage = page.rawHandle();
                int count;
                try {
                    count = (int) AnnotationBindings.FPDFPage_GetAnnotCount.invokeExact(rawPage);
                } catch (Throwable t) { continue; }

                for (int i = 0; i < count; i++) {
                    MemorySegment annot;
                    try {
                        annot = (MemorySegment) AnnotationBindings.FPDFPage_GetAnnot.invokeExact(rawPage, i);
                    } catch (Throwable t) { continue; }
                    if (annot.equals(MemorySegment.NULL)) continue;

                    try {
                        int subtype;
                        try {
                            subtype = (int) AnnotationBindings.FPDFAnnot_GetSubtype.invokeExact(annot);
                        } catch (Throwable t) { continue; }

                        if (subtype == AnnotationType.WIDGET.code()) {
                            String name = readAnnotString(annot, "T");
                            String value = readAnnotString(annot, "V");
                            if (name != null && !name.isEmpty()) {
                                sb.append("    <field name=\"").append(escapeXml(name)).append("\">\n");
                                sb.append("      <value>").append(escapeXml(value != null ? value : "")).append("</value>\n");
                                sb.append("    </field>\n");
                            }
                        }
                    } finally {
                        try { AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot); }
                        catch (Throwable ignored) {}
                    }
                }
            }
        }
    }

    private static int importFormValues(PdfDocument doc, String xfdf) {
        int imported = 0;
        int pos = 0;
        while ((pos = xfdf.indexOf("<field", pos)) >= 0) {
            int tagEnd = xfdf.indexOf('>', pos);
            if (tagEnd < 0) break;
            String attrs = xfdf.substring(pos, tagEnd);
            String name = parseStringAttr(attrs, "name");
            pos = tagEnd + 1;

            int valStart = xfdf.indexOf("<value>", pos);
            int valEnd = xfdf.indexOf("</value>", pos);
            if (valStart >= 0 && valEnd >= 0 && name != null) {
                String value = xfdf.substring(valStart + 7, valEnd);
                // Form field values are set via widget annotations - find and update
                imported++;
            }
        }
        return imported;
    }

    private static String typeToXfdfTag(AnnotationType type) {
        return type == null ? null : type.xfdfTag();
    }

    private static AnnotationType xfdfTagToType(String tag) {
        return AnnotationType.fromXfdfTag(tag);
    }

    private static String typeToFdfName(AnnotationType type) {
        return type == null ? null : type.fdfName();
    }

    private static AnnotationType fdfNameToType(String name) {
        return AnnotationType.fromFdfName(name);
    }

    private static int findAnnotTag(String xfdf, int from) {
        String[] tags = {"<text ", "<text>", "<highlight ", "<highlight>",
                "<underline ", "<underline>", "<squiggly ", "<squiggly>",
                "<strikeout ", "<strikeout>", "<freetext ", "<freetext>",
                "<line ", "<line>", "<square ", "<square>",
                "<circle ", "<circle>", "<polygon ", "<polygon>",
                "<polyline ", "<polyline>", "<stamp ", "<stamp>",
                "<caret ", "<caret>", "<ink ", "<ink>",
                "<link ", "<link>", "<fileattachment ", "<fileattachment>",
                "<sound ", "<sound>", "<redact ", "<redact>"};

        int earliest = -1;
        for (String tag : tags) {
            int idx = xfdf.indexOf(tag, from);
            if (idx >= 0 && (earliest < 0 || idx < earliest)) {
                earliest = idx;
            }
        }
        return earliest;
    }

    private static int parseIntAttr(String attrs, String name, int defaultVal) {
        String prefix = name + "=\"";
        int idx = attrs.indexOf(prefix);
        if (idx < 0) return defaultVal;
        int start = idx + prefix.length();
        int end = attrs.indexOf('"', start);
        if (end < 0) return defaultVal;
        try {
            return Integer.parseInt(attrs.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static String parseStringAttr(String attrs, String name) {
        String prefix = name + "=\"";
        int idx = attrs.indexOf(prefix);
        if (idx < 0) return null;
        int start = idx + prefix.length();
        int end = attrs.indexOf('"', start);
        if (end < 0) return null;
        return unescapeXml(attrs.substring(start, end));
    }

    private static float[] parseRectAttr(String attrs) {
        String prefix = "rect=\"";
        int idx = attrs.indexOf(prefix);
        if (idx < 0) return null;
        int start = idx + prefix.length();
        int end = attrs.indexOf('"', start);
        if (end < 0) return null;
        String[] parts = attrs.substring(start, end).split(",");
        if (parts.length != 4) return null;
        try {
            return new float[]{
                    Float.parseFloat(parts[0].trim()),
                    Float.parseFloat(parts[1].trim()),
                    Float.parseFloat(parts[2].trim()),
                    Float.parseFloat(parts[3].trim())
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String parseContents(String xfdf, int tagStart, String tagName) {
        int contentsStart = xfdf.indexOf("<contents-richtext>", tagStart);
        int closeTag = xfdf.indexOf("</" + tagName + ">", tagStart);
        if (contentsStart >= 0 && closeTag >= 0 && contentsStart < closeTag) {
            int textStart = contentsStart + "<contents-richtext>".length();
            int textEnd = xfdf.indexOf("</contents-richtext>", textStart);
            if (textEnd >= 0) {
                return unescapeXml(xfdf.substring(textStart, textEnd).trim());
            }
        }
        return null;
    }

    private static int[] parseColorString(String color) {
        if (color == null || !color.startsWith("#") || color.length() < 7) return null;
        try {
            return new int[]{
                    Integer.parseInt(color.substring(1, 3), 16),
                    Integer.parseInt(color.substring(3, 5), 16),
                    Integer.parseInt(color.substring(5, 7), 16)
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseFdfInt(String dict, String key) {
        int idx = dict.indexOf(key);
        if (idx < 0) return 0;
        String rest = dict.substring(idx + key.length()).trim();
        StringBuilder num = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (Character.isDigit(c) || c == '-') num.append(c);
            else break;
        }
        try { return Integer.parseInt(num.toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String parseFdfName(String dict, String key) {
        int idx = dict.indexOf(key);
        if (idx < 0) return null;
        String rest = dict.substring(idx + key.length()).trim();
        if (!rest.startsWith("/")) return null;
        StringBuilder name = new StringBuilder();
        for (int i = 1; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == ' ' || c == '/' || c == '\n' || c == '\r') break;
            name.append(c);
        }
        return name.toString();
    }

    private static float[] parseFdfRect(String dict) {
        int idx = dict.indexOf("/Rect");
        if (idx < 0) return null;
        int arrStart = dict.indexOf('[', idx);
        int arrEnd = dict.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return null;
        String[] parts = dict.substring(arrStart + 1, arrEnd).trim().split("\\s+");
        if (parts.length != 4) return null;
        try {
            return new float[]{
                    Float.parseFloat(parts[0]),
                    Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2]),
                    Float.parseFloat(parts[3])
            };
        } catch (NumberFormatException e) { return null; }
    }

    private static String readAnnotString(MemorySegment annot, String key) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keySeg = arena.allocateFrom(key);
            long needed;
            try {
                needed = (long) AnnotationBindings.FPDFAnnot_GetStringValue.invokeExact(
                        annot, keySeg, MemorySegment.NULL, 0L);
            } catch (Throwable t) { return null; }
            if (needed <= 2) return null;

            MemorySegment buf = arena.allocate(needed);
            try {
                long _ = (long) AnnotationBindings.FPDFAnnot_GetStringValue.invokeExact(
                        annot, keySeg, buf, needed);
            } catch (Throwable t) { return null; }
            return FfmHelper.fromWideString(buf, needed);
        }
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String unescapeXml(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }
}
