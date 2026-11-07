package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.panama.DocBindings;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.FfmHelper.Scratch;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Read PDF page labels (the logical page numbers like i, ii, iii, 1, 2, 3
 * shown in viewers, distinct from physical page indices).
 *
 * <p>Uses PDFium's {@code FPDF_GetPageLabel} to read labels defined by the
 * document's {@code /PageLabels} number tree.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("book.pdf"))) {
 *     List<String> labels = PdfPageLabels.list(doc);
 *     labels.forEach(l -> System.out.println("  " + l));
 * }
 * }</pre>
 */
public final class PdfPageLabels {

    private PdfPageLabels() {}

    /**
     * Get the page label for a specific page.
     *
     * @param doc       open PDF document
     * @param pageIndex 0-based page index
     * @return the page label, or empty if no label is defined
     */
    public static Optional<String> get(PdfDocument doc, int pageIndex) {
        MemorySegment rawDoc = doc.rawHandleForRead();
        if (rawDoc == null || rawDoc.equals(MemorySegment.NULL) || rawDoc.address() == 0) {
            return Optional.empty();
        }
        Scratch scratch = FfmHelper.scratch();
        MemorySegment buf = scratch.buffer();
        long cap = scratch.bufferSize();
        long needed;
        try {
            needed = (long) DocBindings.FPDF_GetPageLabel.invokeExact(
                    rawDoc, pageIndex, buf, cap);
        } catch (Throwable t) { return Optional.empty(); }

        if (needed <= 2) return Optional.empty();

        if (needed > cap) {
            MemorySegment largeBuf = scratch.ensureNativeBuffer(needed);
            long actual;
            try {
                // Two-call contract: decode the bytes reported by the retry, not the probe.
                actual = (long) DocBindings.FPDF_GetPageLabel.invokeExact(
                        rawDoc, pageIndex, largeBuf, needed);
            } catch (Throwable t) { return Optional.empty(); }
            if (actual <= 2) return Optional.empty();
            String label = scratch.decodeWideString(largeBuf, actual);
            return label.isEmpty() ? Optional.empty() : Optional.of(label);
        }

        String label = scratch.decodeWideString(needed);
        return label.isEmpty() ? Optional.empty() : Optional.of(label);
    }

    /**
     * Get all page labels for the entire document.
     *
     * @param doc open PDF document
     * @return list of labels indexed by page number; empty string if page has no label
     */
    public static List<String> list(PdfDocument doc) {
        int count = doc.pageCount();
        if (count == 0) return Collections.emptyList();

        List<String> labels = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            labels.add(get(doc, i).orElse(""));
        }
        return Collections.unmodifiableList(labels);
    }

    /**
     * Check if the document has any page labels defined.
     *
     * @param doc open PDF document
     * @return true if at least one page has a non-empty label
     */
    public static boolean hasLabels(PdfDocument doc) {
        // Check first few pages for labels
        int check = Math.min(doc.pageCount(), 5);
        for (int i = 0; i < check; i++) {
            if (get(doc, i).isPresent()) return true;
        }
        return false;
    }
}
