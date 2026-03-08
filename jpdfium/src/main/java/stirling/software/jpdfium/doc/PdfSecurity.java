package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.panama.FontLib;
import stirling.software.jpdfium.redact.pii.XmpRedactor;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Security hardening operations for PDF documents (builder pattern).
 *
 * <p>Removes potentially dangerous or sensitive content such as JavaScript, embedded files,
 * metadata, links, and fonts. Complements the redaction pipeline for producing
 * safe-to-distribute documents.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("unsafe.pdf"))) {
 *     PdfSecurity.Result result = PdfSecurity.builder()
 *         .removeJavaScript(true)
 *         .removeEmbeddedFiles(true)
 *         .removeActions(true)
 *         .removeXmpMetadata(true)
 *         .removeDocumentMetadata(true)
 *         .removeLinks(true)
 *         .removeFonts(true)
 *         .build()
 *         .execute(doc);
 *     System.out.println(result.summary());
 *     doc.save(Path.of("hardened.pdf"));
 * }
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

    private PdfSecurity(boolean removeJavaScript, boolean removeEmbeddedFiles,
                        boolean removeActions, boolean removeXmpMetadata,
                        boolean removeDocumentMetadata, boolean removeLinks,
                        boolean removeFonts) {
        this.removeJavaScript = removeJavaScript;
        this.removeEmbeddedFiles = removeEmbeddedFiles;
        this.removeActions = removeActions;
        this.removeXmpMetadata = removeXmpMetadata;
        this.removeDocumentMetadata = removeDocumentMetadata;
        this.removeFonts = removeFonts;
        this.removeLinks = removeLinks;
    }

    /** Create a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Execute the configured security hardening on the given document.
     *
     * @param doc document to sanitize (modified in place)
     * @return result with counts of removed items
     */
    public Result execute(PdfDocument doc) {
        int jsCount = removeJavaScript ? doRemoveJavaScript(doc) : 0;
        int fileCount = removeEmbeddedFiles ? doRemoveEmbeddedFiles(doc) : 0;
        int actionCount = removeActions ? doRemoveActions(doc) : 0;
        int xmpCount = removeXmpMetadata ? doRemoveXmpMetadata(doc) : 0;
        int metaCount = removeDocumentMetadata ? doRemoveDocumentMetadata(doc) : 0;
        int linkCount = removeLinks ? doRemoveLinks(doc) : 0;
        int fontCount = removeFonts ? doRemoveFonts(doc) : 0;

        return new Result(jsCount, fileCount, actionCount, xmpCount, metaCount, linkCount, fontCount);
    }

    /**
     * Convenience: full sanitization (remove JS + embedded files + actions + metadata + links + fonts).
     *
     * @param doc document to sanitize
     * @return summary string with counts
     */
    public static String sanitize(PdfDocument doc) {
        Result r = builder()
                .all()
                .build()
                .execute(doc);
        return r.summary();
    }

    /**
     * Result of a security hardening operation.
     */
    public record Result(int jsAnnotationsRemoved, int embeddedFilesRemoved,
                         int actionAnnotationsRemoved, int xmpMetadataFieldsRemoved,
                         int documentMetadataFieldsRemoved, int linksRemoved,
                         int fontsRemoved) {
        /** Human-readable summary. */
        public String summary() {
            return ("Removed: %d JS annotations, %d embedded files, %d action annotations, " +
                    "%d XMP metadata fields, %d document metadata fields, %d links, %d fonts")
                    .formatted(jsAnnotationsRemoved, embeddedFilesRemoved,
                            actionAnnotationsRemoved, xmpMetadataFieldsRemoved,
                            documentMetadataFieldsRemoved, linksRemoved, fontsRemoved);
        }

        /** Total number of items removed. */
        public int totalRemoved() {
            return jsAnnotationsRemoved + embeddedFilesRemoved + actionAnnotationsRemoved
                    + xmpMetadataFieldsRemoved + documentMetadataFieldsRemoved
                    + linksRemoved + fontsRemoved;
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

        private Builder() {}

        /** Remove JavaScript-bearing annotations (Screen, Widget). */
        public Builder removeJavaScript(boolean enable) {
            this.removeJavaScript = enable;
            return this;
        }

        /** Remove embedded file attachments. */
        public Builder removeEmbeddedFiles(boolean enable) {
            this.removeEmbeddedFiles = enable;
            return this;
        }

        /** Remove action annotations (Link, Screen, Widget). */
        public Builder removeActions(boolean enable) {
            this.removeActions = enable;
            return this;
        }

        /** Remove XMP metadata stream from the document. */
        public Builder removeXmpMetadata(boolean enable) {
            this.removeXmpMetadata = enable;
            return this;
        }

        /** Remove document information metadata (Title, Author, Subject, Keywords, Creator, Producer, dates). */
        public Builder removeDocumentMetadata(boolean enable) {
            this.removeDocumentMetadata = enable;
            return this;
        }

        /** Remove external links (URI actions) and launch actions from the PDF. */
        public Builder removeLinks(boolean enable) {
            this.removeLinks = enable;
            return this;
        }

        /** Remove embedded fonts from the PDF. */
        public Builder removeFonts(boolean enable) {
            this.removeFonts = enable;
            return this;
        }

        /** Enable all security hardening operations. */
        public Builder all() {
            this.removeJavaScript = true;
            this.removeEmbeddedFiles = true;
            this.removeActions = true;
            this.removeXmpMetadata = true;
            this.removeDocumentMetadata = true;
            this.removeLinks = true;
            this.removeFonts = true;
            return this;
        }

        public PdfSecurity build() {
            return new PdfSecurity(removeJavaScript, removeEmbeddedFiles, removeActions,
                    removeXmpMetadata, removeDocumentMetadata, removeLinks, removeFonts);
        }
    }

    private static int doRemoveJavaScript(PdfDocument doc) {
        return removeAnnotationsByTypeAcrossAllPages(doc, AnnotationType.SCREEN, AnnotationType.WIDGET);
    }

    private static int doRemoveEmbeddedFiles(PdfDocument doc) {
        List<Attachment> attachments = PdfAttachments.list(doc.rawHandle());
        int count = attachments.size();
        for (int i = count - 1; i >= 0; i--) {
            PdfAttachments.delete(doc.rawHandle(), i);
        }
        return count;
    }

    private static int doRemoveActions(PdfDocument doc) {
        return removeAnnotationsByTypeAcrossAllPages(doc, AnnotationType.LINK, AnnotationType.SCREEN, AnnotationType.WIDGET);
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

    private static int doRemoveXmpMetadata(PdfDocument doc) {
        int count = doc.metadata().size();
        try {
            XmpRedactor.stripAll(doc);
        } catch (JPDFiumException ignored) {
            return 0;
        }
        return count;
    }

    private static int doRemoveDocumentMetadata(PdfDocument doc) {
        int count = (int) XmpRedactor.PII_KEYS.stream()
                .filter(key -> doc.metadata(key).isPresent())
                .count();
        try {
            XmpRedactor.stripPiiKeys(doc);
        } catch (JPDFiumException ignored) {
            return 0;
        }
        return count;
    }

    private static int doRemoveLinks(PdfDocument doc) {
        return removeAnnotationsByTypeAcrossAllPages(doc, AnnotationType.LINK);
    }

    private static int removeAnnotationsByTypeAcrossAllPages(PdfDocument doc, AnnotationType... types) {
        int removed = 0;
        for (int i = 0; i < doc.pageCount(); i++) {
            try (PdfPage page = doc.page(i)) {
                removed += removeAnnotationsByType(page.rawHandle(), types);
            }
        }
        return removed;
    }

    private static int doRemoveFonts(PdfDocument doc) {
        try {
            return FontLib.stripFonts(doc.nativeHandle());
        } catch (JPDFiumException ignored) {
            return 0;
        }
    }
}
