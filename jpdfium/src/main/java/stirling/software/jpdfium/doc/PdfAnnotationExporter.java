package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.AnnotationBindings;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Export and import PDF annotations as structured JSON.
 *
 * <p>Critical for review workflows: export annotations from one copy, send the
 * JSON file (tiny), and import into another copy.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("reviewed.pdf"))) {
 *     String json = PdfAnnotationExporter.exportJson(doc);
 *     Files.writeString(Path.of("annotations.json"), json);
 * }
 * }</pre>
 */
public final class PdfAnnotationExporter {

    private PdfAnnotationExporter() {}

    /** A serializable annotation record. */
    public record AnnotationData(
            int pageIndex,
            String type,
            float left, float bottom, float right, float top,
            String contents,
            int r, int g, int b, int a
    ) {}

    /**
     * Export all annotations from a document as a list.
     *
     * @param doc open PDF document
     * @return list of annotation data records
     */
    public static List<AnnotationData> export(PdfDocument doc) {
        List<AnnotationData> result = new ArrayList<>();
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
                        AnnotationData data = readAnnotation(annot, p);
                        if (data != null) result.add(data);
                    } finally {
                        try { AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot); }
                        catch (Throwable ignored) {}
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Export annotations as JSON string.
     */
    public static String exportJson(PdfDocument doc) {
        List<AnnotationData> data = export(doc);
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < data.size(); i++) {
            AnnotationData d = data.get(i);
            sb.append("  {");
            sb.append("\"page\":").append(d.pageIndex()).append(",");
            sb.append("\"type\":\"").append(escapeJson(d.type())).append("\",");
            sb.append("\"rect\":[").append(d.left()).append(",").append(d.bottom())
              .append(",").append(d.right()).append(",").append(d.top()).append("],");
            sb.append("\"contents\":\"").append(escapeJson(d.contents() != null ? d.contents() : "")).append("\",");
            sb.append("\"color\":[").append(d.r()).append(",").append(d.g())
              .append(",").append(d.b()).append(",").append(d.a()).append("]");
            sb.append("}");
            if (i < data.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Import annotations from a list of annotation data.
     *
     * @param doc  target document
     * @param data annotation records to create
     * @return number of annotations successfully created
     */
    public static int importAnnotations(PdfDocument doc, List<AnnotationData> data) {
        int created = 0;
        for (AnnotationData d : data) {
            if (d.pageIndex() < 0 || d.pageIndex() >= doc.pageCount()) continue;

            try (PdfPage page = doc.page(d.pageIndex())) {
                MemorySegment rawPage = page.rawHandle();
                AnnotationType type = AnnotationType.valueOf(d.type());
                if (type == null) continue;

                MemorySegment annot;
                try {
                    annot = (MemorySegment) AnnotationBindings.FPDFPage_CreateAnnot.invokeExact(
                            rawPage, type.code());
                } catch (Throwable t) { continue; }
                if (annot.equals(MemorySegment.NULL)) continue;

                try {
                    // Set rect
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment rect = arena.allocate(AnnotationBindings.FS_RECTF_LAYOUT);
                        rect.set(ValueLayout.JAVA_FLOAT, 0, d.left());
                        rect.set(ValueLayout.JAVA_FLOAT, 4, d.bottom());
                        rect.set(ValueLayout.JAVA_FLOAT, 8, d.right());
                        rect.set(ValueLayout.JAVA_FLOAT, 12, d.top());
                        try {
                            AnnotationBindings.FPDFAnnot_SetRect.invokeExact(annot, rect);
                        } catch (Throwable ignored) {}
                    }

                    // Set color
                    try {
                        AnnotationBindings.FPDFAnnot_SetColor.invokeExact(
                                annot, 0, d.r(), d.g(), d.b(), d.a());
                    } catch (Throwable ignored) {}

                    created++;
                } finally {
                    try { AnnotationBindings.FPDFPage_CloseAnnot.invokeExact(annot); }
                    catch (Throwable ignored) {}
                }
            }
        }
        return created;
    }

    private static AnnotationData readAnnotation(MemorySegment annot, int pageIndex) {
        try (Arena arena = Arena.ofConfined()) {
            int subtype;
            try {
                subtype = (int) AnnotationBindings.FPDFAnnot_GetSubtype.invokeExact(annot);
            } catch (Throwable t) { return null; }

            AnnotationType type = AnnotationType.fromCode(subtype);
            String typeName = (type != null) ? type.name() : "UNKNOWN_" + subtype;

            // Get rect
            MemorySegment rect = arena.allocate(AnnotationBindings.FS_RECTF_LAYOUT);
            try {
                int ok = (int) AnnotationBindings.FPDFAnnot_GetRect.invokeExact(annot, rect);
                if (ok == 0) return null;
            } catch (Throwable t) { return null; }

            float left = rect.get(ValueLayout.JAVA_FLOAT, 0);
            float bottom = rect.get(ValueLayout.JAVA_FLOAT, 4);
            float right = rect.get(ValueLayout.JAVA_FLOAT, 8);
            float top = rect.get(ValueLayout.JAVA_FLOAT, 12);

            // Get color
            MemorySegment r = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment g = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment b = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment a = arena.allocate(ValueLayout.JAVA_INT);
            int ri = 0, gi = 0, bi = 0, ai = 255;
            try {
                int ok = (int) AnnotationBindings.FPDFAnnot_GetColor.invokeExact(
                        annot, 0, r, g, b, a);
                if (ok != 0) {
                    ri = r.get(ValueLayout.JAVA_INT, 0);
                    gi = g.get(ValueLayout.JAVA_INT, 0);
                    bi = b.get(ValueLayout.JAVA_INT, 0);
                    ai = a.get(ValueLayout.JAVA_INT, 0);
                }
            } catch (Throwable ignored) {}

            return new AnnotationData(pageIndex, typeName, left, bottom, right, top,
                    null, ri, gi, bi, ai);
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
