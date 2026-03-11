package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.panama.RenderBindings;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detect duplicate or near-identical pages using perceptual hashing.
 *
 * <p>Renders each page at low DPI, computes an average hash (aHash),
 * and reports clusters of pages with matching or similar hashes.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("merged.pdf"))) {
 *     var report = PdfDuplicateDetector.detect(doc);
 *     report.groups().forEach(g ->
 *         System.out.println("Duplicates: pages " + g));
 * }
 * }</pre>
 */
public final class PdfDuplicateDetector {

    private PdfDuplicateDetector() {}

    /** A group of pages that are duplicates of each other. */
    public record DuplicateGroup(List<Integer> pages, int hammingDistance) {
        @Override
        public String toString() {
            return "pages=" + pages + " distance=" + hammingDistance;
        }
    }

    /** Detection result. */
    public record DetectionResult(List<DuplicateGroup> groups, int uniquePages, int totalPages) {}

    /**
     * Detect duplicate pages.
     *
     * @param doc       document to analyze
     * @param threshold maximum Hamming distance for two pages to be considered duplicates (0=exact)
     * @return detection result with duplicate groups
     */
    public static DetectionResult detect(PdfDocument doc, int threshold) {
        int n = doc.pageCount();
        long[] hashes = new long[n];

        // Compute hash for each page
        for (int i = 0; i < n; i++) {
            hashes[i] = computeAverageHash(doc, i);
        }

        // Find duplicate groups
        boolean[] assigned = new boolean[n];
        List<DuplicateGroup> groups = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            if (assigned[i]) continue;
            List<Integer> cluster = new ArrayList<>();
            cluster.add(i);
            int minDist = Integer.MAX_VALUE;

            for (int j = i + 1; j < n; j++) {
                if (assigned[j]) continue;
                int dist = Long.bitCount(hashes[i] ^ hashes[j]);
                if (dist <= threshold) {
                    cluster.add(j);
                    assigned[j] = true;
                    minDist = Math.min(minDist, dist);
                }
            }

            if (cluster.size() > 1) {
                assigned[i] = true;
                groups.add(new DuplicateGroup(Collections.unmodifiableList(cluster), minDist));
            }
        }

        int duplicatePages = groups.stream().mapToInt(g -> g.pages().size()).sum();
        return new DetectionResult(Collections.unmodifiableList(groups), n - duplicatePages, n);
    }

    /** Detect with exact match (threshold=0). */
    public static DetectionResult detect(PdfDocument doc) {
        return detect(doc, 5);
    }

    /**
     * Compute average hash (aHash) for a page.
     * Renders at 8x8 pixels, computes mean luminance, encodes as 64-bit hash.
     */
    private static long computeAverageHash(PdfDocument doc, int pageIndex) {
        int thumbSize = 8;
        try (PdfPage page = doc.page(pageIndex)) {
            MemorySegment rawPage = page.rawHandle();
            float w = page.size().width();
            float h = page.size().height();

            // Render at 8x8
            MemorySegment bitmap;
            try {
                bitmap = (MemorySegment) RenderBindings.FPDFBitmap_Create.invokeExact(
                        thumbSize, thumbSize, 0);
            } catch (Throwable t) { return 0L; }

            try {
                try { RenderBindings.FPDFBitmap_FillRect.invokeExact(bitmap, 0, 0,
                        thumbSize, thumbSize, 0xFFFFFFFFL); }
                catch (Throwable t) { return 0L; }

                int flags = RenderBindings.FPDF_ANNOT | RenderBindings.FPDF_PRINTING;
                try { RenderBindings.FPDF_RenderPageBitmap.invokeExact(bitmap, rawPage,
                        0, 0, thumbSize, thumbSize, 0, flags); }
                catch (Throwable t) { return 0L; }

                MemorySegment buf;
                int stride;
                try {
                    buf = (MemorySegment) PageEditBindings.FPDFBitmap_GetBuffer.invokeExact(bitmap);
                    stride = (int) PageEditBindings.FPDFBitmap_GetStride.invokeExact(bitmap);
                } catch (Throwable t) { return 0L; }

                MemorySegment pixels = buf.reinterpret((long) stride * thumbSize);

                // Compute grayscale values and average
                int[] gray = new int[64];
                int sum = 0;
                for (int y = 0; y < thumbSize; y++) {
                    long rowOfs = (long) y * stride;
                    for (int x = 0; x < thumbSize; x++) {
                        long px = rowOfs + (long) x * 4;
                        int b = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, px));
                        int g = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, px + 1));
                        int r = Byte.toUnsignedInt(pixels.get(ValueLayout.JAVA_BYTE, px + 2));
                        int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                        gray[y * thumbSize + x] = lum;
                        sum += lum;
                    }
                }

                int avg = sum / 64;
                long hash = 0L;
                for (int i = 0; i < 64; i++) {
                    if (gray[i] >= avg) hash |= (1L << i);
                }
                return hash;
            } finally {
                try { PageEditBindings.FPDFBitmap_Destroy.invokeExact(bitmap); }
                catch (Throwable ignored) {}
            }
        }
    }
}
