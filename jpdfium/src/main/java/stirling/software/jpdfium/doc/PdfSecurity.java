package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.FlattenMode;
import stirling.software.jpdfium.panama.FontLib;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.redact.pii.XmpRedactor;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive PDF security hardening and sanitization (builder pattern).
 *
 * <p>Removes potentially dangerous or sensitive content: JavaScript, embedded files,
 * actions, metadata, links, fonts, comments, hidden text, and interactive forms.
 *
 * <pre>{@code
 * // Full sanitization in one call
 * PdfSecurity.Result result = PdfSecurity.builder()
 *     .all()
 *     .build()
 *     .execute(doc);
 * System.out.println(result.summary());
 *
 * // Selective: only JS and metadata
 * PdfSecurity.Result result = PdfSecurity.builder()
 *     .removeJavaScript(true)
 *     .removeXmpMetadata(true)
 *     .removeDocumentMetadata(true)
 *     .build()
 *     .execute(doc);
 *
 * // Quick one-liner
 * PdfSecurity.sanitize(doc);
 * }</pre>
 */
public final class PdfSecurity {

    private final boolean removeJavaScript;
    private final boolean removeEmbeddedFiles;
    private final boolean removeActions;
    private final boolean removeXmpMetadata;
    private final boolean removeDocumentMetadata;
    private final boolean removeLinks;
    private final boolean removeFonts;
    private final boolean removeComments;
    private final boolean removeHiddenText;
    private final boolean flattenForms;

    private PdfSecurity(Builder b) {
        this.removeJavaScript = b.removeJavaScript;
        this.removeEmbeddedFiles = b.removeEmbeddedFiles;
        this.removeActions = b.removeActions;
        this.removeXmpMetadata = b.removeXmpMetadata;
        this.removeDocumentMetadata = b.removeDocumentMetadata;
        this.removeLinks = b.removeLinks;
        this.removeFonts = b.removeFonts;
        this.removeComments = b.removeComments;
        this.removeHiddenText = b.removeHiddenText;
        this.flattenForms = b.flattenForms;
    }

    /** Create a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Execute the configured security hardening on the given document.
     *
     * @param doc document to sanitize (modified in place)
     * @return result with counts of removed items and action log
     */
    public Result execute(PdfDocument doc) {
        List<String> actions = new ArrayList<>();

        int jsCount = removeJavaScript
                ? removeAnnotationsByTypeAcrossAllPages(doc, AnnotationType.SCREEN, AnnotationType.WIDGET)
                : 0;
        if (jsCount > 0) actions.add("Removed %d JavaScript annotations".formatted(jsCount));

        int fileCount = removeEmbeddedFiles ? doRemoveEmbeddedFiles(doc) : 0;
        if (fileCount > 0) actions.add("Removed %d embedded files".formatted(fileCount));

        int actionCount = removeActions
                ? removeAnnotationsByTypeAcrossAllPages(doc, AnnotationType.LINK, AnnotationType.SCREEN, AnnotationType.WIDGET)
                : 0;
        if (actionCount > 0) actions.add("Removed %d action annotations".formatted(actionCount));

        int xmpCount = removeXmpMetadata ? doRemoveXmpMetadata(doc) : 0;
        if (xmpCount > 0) actions.add("Removed %d XMP metadata fields".formatted(xmpCount));

        int metaCount = removeDocumentMetadata ? doRemoveDocumentMetadata(doc) : 0;
        if (metaCount > 0) actions.add("Removed %d document metadata fields".formatted(metaCount));

        int linkCount = removeLinks
                ? removeAnnotationsByTypeAcrossAllPages(doc, AnnotationType.LINK)
                : 0;
        if (linkCount > 0) actions.add("Removed %d links".formatted(linkCount));

        int fontCount = removeFonts ? doRemoveFonts(doc) : 0;
        if (fontCount > 0) actions.add("Removed %d fonts".formatted(fontCount));

        int commentCount = removeComments ? doRemoveComments(doc) : 0;
        if (commentCount > 0) actions.add("Removed %d comment annotations".formatted(commentCount));

        int hiddenTextCount = removeHiddenText ? doRemoveHiddenText(doc) : 0;
        if (hiddenTextCount > 0) actions.add("Removed %d hidden text objects".formatted(hiddenTextCount));

        int formCount = flattenForms ? doFlattenForms(doc) : 0;
        if (formCount > 0) actions.add("Flattened %d form fields".formatted(formCount));

        return new Result(jsCount, fileCount, actionCount, xmpCount, metaCount,
                linkCount, fontCount, commentCount, hiddenTextCount, formCount,
                List.copyOf(actions));
    }

    /**
     * Convenience: full sanitization (all options enabled).
     */
    public static String sanitize(PdfDocument doc) {
        return builder().all().build().execute(doc).summary();
    }

    /**
     * Result of a security hardening operation.
     */
    public record Result(int jsAnnotationsRemoved, int embeddedFilesRemoved,
                         int actionAnnotationsRemoved, int xmpMetadataFieldsRemoved,
                         int documentMetadataFieldsRemoved, int linksRemoved,
                         int fontsRemoved, int commentsRemoved, int hiddenTextRemoved,
                         int formsFlattened, List<String> actions) {

        /** Human-readable summary. */
        public String summary() {
            if (actions.isEmpty()) return "Security Report:\n  No items needed removal.";
            var sb = new StringBuilder("Security Report:\n");
            for (String action : actions) sb.append("  - ").append(action).append('\n');
            sb.append("  Total: ").append(totalRemoved()).append(" items processed");
            return sb.toString();
        }

        /** Total number of items removed/processed. */
        public int totalRemoved() {
            return jsAnnotationsRemoved + embeddedFilesRemoved + actionAnnotationsRemoved
                    + xmpMetadataFieldsRemoved + documentMetadataFieldsRemoved
                    + linksRemoved + fontsRemoved + commentsRemoved + hiddenTextRemoved
                    + formsFlattened;
        }
    }

    public static final class Builder {
        private boolean removeJavaScript;
        private boolean removeEmbeddedFiles;
        private boolean removeActions;
        private boolean removeXmpMetadata;
        private boolean removeDocumentMetadata;
        private boolean removeLinks;
        private boolean removeFonts;
        private boolean removeComments;
        private boolean removeHiddenText;
        private boolean flattenForms;

        private Builder() {}

        /** Remove JavaScript-bearing annotations (Screen, Widget). */
        public Builder removeJavaScript(boolean v) { this.removeJavaScript = v; return this; }
        /** Remove embedded file attachments. */
        public Builder removeEmbeddedFiles(boolean v) { this.removeEmbeddedFiles = v; return this; }
        /** Remove action annotations (Link, Screen, Widget). */
        public Builder removeActions(boolean v) { this.removeActions = v; return this; }
        /** Remove XMP metadata stream. */
        public Builder removeXmpMetadata(boolean v) { this.removeXmpMetadata = v; return this; }
        /** Remove document information metadata (Title, Author, etc.). */
        public Builder removeDocumentMetadata(boolean v) { this.removeDocumentMetadata = v; return this; }
        /** Remove hyperlink annotations. */
        public Builder removeLinks(boolean v) { this.removeLinks = v; return this; }
        /** Remove embedded fonts. */
        public Builder removeFonts(boolean v) { this.removeFonts = v; return this; }
        /** Remove non-widget, non-link comment annotations (sticky notes, highlights, etc.). */
        public Builder removeComments(boolean v) { this.removeComments = v; return this; }
        /** Remove invisible text objects (render mode 3). */
        public Builder removeHiddenText(boolean v) { this.removeHiddenText = v; return this; }
        /** Flatten interactive form fields into static content. */
        public Builder flattenForms(boolean v) { this.flattenForms = v; return this; }

        /** Enable all security hardening operations. */
        public Builder all() {
            this.removeJavaScript = true;
            this.removeEmbeddedFiles = true;
            this.removeActions = true;
            this.removeXmpMetadata = true;
            this.removeDocumentMetadata = true;
            this.removeLinks = true;
            this.removeFonts = true;
            this.removeComments = true;
            this.removeHiddenText = true;
            this.flattenForms = true;
            return this;
        }

        public PdfSecurity build() { return new PdfSecurity(this); }
    }

    private static int doRemoveEmbeddedFiles(PdfDocument doc) {
        List<Attachment> attachments = PdfAttachments.list(doc.rawHandle());
        int count = attachments.size();
        for (int i = count - 1; i >= 0; i--) {
            PdfAttachments.delete(doc.rawHandle(), i);
        }
        return count;
    }

    private static int doRemoveXmpMetadata(PdfDocument doc) {
        int count = doc.metadata().size();
        try {
            XmpRedactor.stripAll(doc);
        } catch (JPDFiumException ignored) { return 0; }
        return count;
    }

    private static int doRemoveDocumentMetadata(PdfDocument doc) {
        int count = (int) XmpRedactor.PII_KEYS.stream()
                .filter(key -> doc.metadata(key).isPresent())
                .count();
        try {
            XmpRedactor.stripPiiKeys(doc);
        } catch (JPDFiumException ignored) { return 0; }
        return count;
    }

    private static int doRemoveFonts(PdfDocument doc) {
        try {
            return FontLib.stripFonts(doc.nativeHandle());
        } catch (JPDFiumException ignored) { return 0; }
    }

    private static int doRemoveComments(PdfDocument doc) {
        int removed = 0;
        int count = doc.pageCount();
        for (int i = 0; i < count; i++) {
            try (PdfPage page = doc.page(i)) {
                removed += removeCommentAnnotations(page.rawHandle());
            }
        }
        return removed;
    }

    private static int doRemoveHiddenText(PdfDocument doc) {
        int removed = 0;
        int count = doc.pageCount();
        for (int i = 0; i < count; i++) {
            try (PdfPage page = doc.page(i)) {
                removed += removeHiddenTextObjects(page.rawHandle());
            }
        }
        return removed;
    }

    private static int doFlattenForms(PdfDocument doc) {
        int formCount = 0;
        int count = doc.pageCount();
        for (int i = 0; i < count; i++) {
            try (PdfPage page = doc.page(i)) {
                for (Annotation a : PdfAnnotations.list(page.rawHandle())) {
                    if (a.type() == AnnotationType.WIDGET) formCount++;
                }
            }
        }
        if (formCount > 0) doc.flatten(FlattenMode.ANNOTATIONS);
        return formCount;
    }

    private static int removeCommentAnnotations(MemorySegment rawPage) {
        List<Annotation> annots = PdfAnnotations.list(rawPage);
        int removed = 0;
        for (int i = annots.size() - 1; i >= 0; i--) {
            AnnotationType type = annots.get(i).type();
            if (type != AnnotationType.WIDGET && type != AnnotationType.LINK
                    && type != AnnotationType.REDACT) {
                PdfAnnotations.remove(rawPage, i);
                removed++;
            }
        }
        return removed;
    }

    private static int removeHiddenTextObjects(MemorySegment rawPage) {
        int count;
        try { count = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage); }
        catch (Throwable t) { return 0; }

        int removed = 0;
        for (int i = count - 1; i >= 0; i--) {
            MemorySegment obj;
            try { obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i); }
            catch (Throwable t) { continue; }
            if (obj.equals(MemorySegment.NULL)) continue;

            int type;
            try { type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj); }
            catch (Throwable t) { continue; }
            if (type != 1) continue; // TEXT only

            int renderMode;
            try { renderMode = (int) PageEditBindings.FPDFTextObj_GetTextRenderMode.invokeExact(obj); }
            catch (Throwable t) { continue; }
            if (renderMode == 3) { // invisible
                try { PageEditBindings.FPDFPage_RemoveObject.invokeExact(rawPage, obj); removed++; }
                catch (Throwable ignored) {}
            }
        }
        if (removed > 0) {
            try { PageEditBindings.FPDFPage_GenerateContent.invokeExact(rawPage); }
            catch (Throwable t) { throw new RuntimeException("FPDFPage_GenerateContent failed", t); }
        }
        return removed;
    }

    private static int removeAnnotationsByTypeAcrossAllPages(PdfDocument doc, AnnotationType... types) {
        int removed = 0;
        int count = doc.pageCount();
        for (int i = 0; i < count; i++) {
            try (PdfPage page = doc.page(i)) {
                removed += removeAnnotationsByType(page.rawHandle(), types);
            }
        }
        return removed;
    }

    private static int removeAnnotationsByType(MemorySegment rawPage, AnnotationType... types) {
        List<Annotation> annots = PdfAnnotations.list(rawPage);
        int removed = 0;
        for (int i = annots.size() - 1; i >= 0; i--) {
            AnnotationType type = annots.get(i).type();
            for (AnnotationType t : types) {
                if (type == t) {
                    PdfAnnotations.remove(rawPage, i);
                    removed++;
                    break;
                }
            }
        }
        return removed;
    }
}
