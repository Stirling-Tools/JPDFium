package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.PageSize;
import stirling.software.jpdfium.model.PdfVersion;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.text.PdfTextExtractor;
import stirling.software.jpdfium.text.PageText;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * One-call document audit. Tells you everything about a PDF.
 */
public final class DocInfo {

    private final String pdfVersion;
    private final boolean tagged;
    private final boolean encrypted;
    private final int pageCount;
    private final List<PageSize> pageSizes;
    private final boolean hasJavaScript;
    private final int javaScriptCount;
    private final boolean hasSignatures;
    private final int signatureCount;
    private final boolean hasBookmarks;
    private final int bookmarkCount;
    private final boolean hasAttachments;
    private final int attachmentCount;
    private final boolean hasAnnotations;
    private final Map<AnnotationType, Integer> annotationCounts;
    private final boolean hasFormFields;
    private final int formFieldCount;
    private final int imageCount;
    private final int estimatedTextPages;
    private final List<Integer> blankPages;
    private final long fileSize;
    private final int permissions;

    private DocInfo(Builder b) {
        this.pdfVersion = b.pdfVersion;
        this.tagged = b.tagged;
        this.encrypted = b.encrypted;
        this.pageCount = b.pageCount;
        this.pageSizes = b.pageSizes;
        this.hasJavaScript = b.hasJavaScript;
        this.javaScriptCount = b.javaScriptCount;
        this.hasSignatures = b.hasSignatures;
        this.signatureCount = b.signatureCount;
        this.hasBookmarks = b.hasBookmarks;
        this.bookmarkCount = b.bookmarkCount;
        this.hasAttachments = b.hasAttachments;
        this.attachmentCount = b.attachmentCount;
        this.hasAnnotations = b.hasAnnotations;
        this.annotationCounts = b.annotationCounts;
        this.hasFormFields = b.hasFormFields;
        this.formFieldCount = b.formFieldCount;
        this.imageCount = b.imageCount;
        this.estimatedTextPages = b.estimatedTextPages;
        this.blankPages = b.blankPages;
        this.fileSize = b.fileSize;
        this.permissions = b.permissions;
    }

    public static DocInfo analyze(PdfDocument doc) {
        return analyze(doc, -1);
    }

    public static DocInfo analyze(PdfDocument doc, long fileSize) {
        Builder b = new Builder();
        MemorySegment rawDoc = doc.rawHandle();

        b.pdfVersion = getPdfVersion(rawDoc);

        // Tagged
        try {
            b.tagged = (int) DocBindings.FPDFCatalog_IsTagged.invokeExact(rawDoc) != 0;
        } catch (Throwable t) { b.tagged = false; }

        // Encrypted
        PdfMetadata meta = PdfMetadata.of(rawDoc);
        b.encrypted = meta.securityHandlerRevision() > 0;
        b.permissions = meta.permissions();

        // Page count and sizes
        b.pageCount = doc.pageCount();
        b.pageSizes = new ArrayList<>();
        int totalAnnots = 0;
        Map<AnnotationType, Integer> annotCounts = new EnumMap<>(AnnotationType.class);
        int widgets = 0;
        int imgCount = 0;
        int textPages = 0;
        List<Integer> blanks = new ArrayList<>();

        for (int i = 0; i < b.pageCount; i++) {
            try (PdfPage page = doc.page(i)) {
                b.pageSizes.add(page.size());

                // Annotations
                List<Annotation> annots = page.annotations();
                for (Annotation a : annots) {
                    annotCounts.merge(a.type(), 1, Integer::sum);
                    if (a.type() == AnnotationType.WIDGET) widgets++;
                    totalAnnots++;
                }

                // Image count via page objects
                MemorySegment rawPage = page.rawHandle();
                try {
                    int objCount = (int) stirling.software.jpdfium.panama.PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
                    for (int j = 0; j < objCount; j++) {
                        MemorySegment obj = (MemorySegment) stirling.software.jpdfium.panama.PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, j);
                        if (!obj.equals(MemorySegment.NULL)) {
                            int type = (int) stirling.software.jpdfium.panama.PageEditBindings.FPDFPageObj_GetType.invokeExact(obj);
                            if (type == 3) imgCount++; // IMAGE
                        }
                    }
                } catch (Throwable ignored) {}

                // Text detection
                try {
                    String text = page.extractTextJson();
                    if (text != null && text.length() > 10) textPages++;
                    else blanks.add(i);
                } catch (Exception e) {
                    blanks.add(i);
                }
            }
        }

        b.hasAnnotations = totalAnnots > 0;
        b.annotationCounts = Collections.unmodifiableMap(annotCounts);
        b.hasFormFields = widgets > 0;
        b.formFieldCount = widgets;
        b.imageCount = imgCount;
        b.estimatedTextPages = textPages;
        b.blankPages = Collections.unmodifiableList(blanks);

        // JavaScript
        try {
            b.javaScriptCount = (int) stirling.software.jpdfium.panama.JavaScriptBindings.FPDFDoc_GetJavaScriptActionCount.invokeExact(rawDoc);
            b.hasJavaScript = b.javaScriptCount > 0;
        } catch (Throwable t) { b.hasJavaScript = false; b.javaScriptCount = 0; }

        // Signatures
        b.signatureCount = PdfSignatures.count(rawDoc);
        b.hasSignatures = b.signatureCount > 0;

        // Bookmarks
        List<Bookmark> bookmarks = PdfBookmarks.list(rawDoc);
        b.bookmarkCount = countBookmarks(bookmarks);
        b.hasBookmarks = b.bookmarkCount > 0;

        // Attachments
        b.attachmentCount = PdfAttachments.count(rawDoc);
        b.hasAttachments = b.attachmentCount > 0;

        b.fileSize = fileSize;

        return new DocInfo(b);
    }

    private static String getPdfVersion(MemorySegment rawDoc) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment versionSeg = a.allocate(ValueLayout.JAVA_INT);
            int ok = (int) DocBindings.FPDF_GetFileVersion.invokeExact(rawDoc, versionSeg);
            if (ok != 0) {
                int v = versionSeg.get(ValueLayout.JAVA_INT, 0);
                return PdfVersion.fromCode(v).toString();
            }
        } catch (Throwable ignored) {}
        return PdfVersion.V1_7.toString();
    }

    private static int countBookmarks(List<Bookmark> bookmarks) {
        int count = 0;
        for (Bookmark bm : bookmarks) {
            count++;
            if (bm.hasChildren()) count += countBookmarks(bm.children());
        }
        return count;
    }

    // Accessors
    public String pdfVersion() { return pdfVersion; }
    public boolean isTagged() { return tagged; }
    public boolean isEncrypted() { return encrypted; }
    public int pageCount() { return pageCount; }
    public List<PageSize> pageSizes() { return pageSizes; }
    public boolean hasJavaScript() { return hasJavaScript; }
    public int javaScriptCount() { return javaScriptCount; }
    public boolean hasSignatures() { return hasSignatures; }
    public int signatureCount() { return signatureCount; }
    public boolean hasBookmarks() { return hasBookmarks; }
    public int bookmarkCount() { return bookmarkCount; }
    public boolean hasAttachments() { return hasAttachments; }
    public int attachmentCount() { return attachmentCount; }
    public boolean hasAnnotations() { return hasAnnotations; }
    public Map<AnnotationType, Integer> annotationCounts() { return annotationCounts; }
    public boolean hasFormFields() { return hasFormFields; }
    public int formFieldCount() { return formFieldCount; }
    public int imageCount() { return imageCount; }
    public int estimatedTextPages() { return estimatedTextPages; }
    public List<Integer> blankPages() { return blankPages; }
    public long fileSize() { return fileSize; }
    public int permissions() { return permissions; }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%d pages", pageCount));
        if (!blankPages.isEmpty()) sb.append(String.format(" (%d blank)", blankPages.size()));
        int scanned = pageCount - estimatedTextPages - blankPages.size();
        if (scanned > 0) sb.append(String.format(", %d scanned", scanned));
        sb.append(String.format(", PDF %s", pdfVersion));
        if (tagged) sb.append(", tagged");
        if (encrypted) sb.append(", encrypted");
        if (formFieldCount > 0) sb.append(String.format(", %d form fields", formFieldCount));
        if (signatureCount > 0) sb.append(String.format(", %d signatures", signatureCount));
        if (bookmarkCount > 0) sb.append(String.format(", %d bookmarks", bookmarkCount));
        if (imageCount > 0) sb.append(String.format(", %d images", imageCount));
        if (attachmentCount > 0) sb.append(String.format(", %d attachments", attachmentCount));
        return sb.toString();
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append(String.format("\"pdfVersion\":\"%s\"", pdfVersion));
        sb.append(String.format(",\"tagged\":%b", tagged));
        sb.append(String.format(",\"encrypted\":%b", encrypted));
        sb.append(String.format(",\"pageCount\":%d", pageCount));
        sb.append(String.format(",\"hasJavaScript\":%b", hasJavaScript));
        sb.append(String.format(",\"javaScriptCount\":%d", javaScriptCount));
        sb.append(String.format(",\"signatureCount\":%d", signatureCount));
        sb.append(String.format(",\"bookmarkCount\":%d", bookmarkCount));
        sb.append(String.format(",\"attachmentCount\":%d", attachmentCount));
        sb.append(String.format(",\"formFieldCount\":%d", formFieldCount));
        sb.append(String.format(",\"imageCount\":%d", imageCount));
        sb.append(String.format(",\"estimatedTextPages\":%d", estimatedTextPages));
        sb.append(String.format(",\"fileSize\":%d", fileSize));
        sb.append(String.format(",\"permissions\":%d", permissions));
        sb.append("}");
        return sb.toString();
    }

    private static class Builder {
        String pdfVersion = PdfVersion.V1_7.toString();
        boolean tagged, encrypted, hasJavaScript, hasSignatures, hasBookmarks,
                hasAttachments, hasAnnotations, hasFormFields;
        int pageCount, javaScriptCount, signatureCount, bookmarkCount, attachmentCount,
                formFieldCount, imageCount, estimatedTextPages, permissions;
        List<PageSize> pageSizes = List.of();
        Map<AnnotationType, Integer> annotationCounts = Map.of();
        List<Integer> blankPages = List.of();
        long fileSize;
    }
}
