package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.panama.FfmHelper;
import stirling.software.jpdfium.panama.FfmHelper.Scratch;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import stirling.software.jpdfium.panama.ImageObjBindings;
import stirling.software.jpdfium.panama.PageEditBindings;
import stirling.software.jpdfium.exception.JPDFiumException;

/**
 * Detect and report duplicate embedded resources (images) across pages.
 *
 * <p>Walks all pages, fingerprints image objects by their rendered pixel data,
 * and groups duplicates. This helps identify opportunities to reduce file size
 * by de-duplicating shared images.
 *
 * <pre>{@code
 * try (PdfDocument doc = PdfDocument.open(Path.of("large.pdf"))) {
 *     var report = PdfResourceDedup.analyze(doc);
 *     System.out.printf("Found %d duplicate groups (%d total duplicates)%n",
 *         report.groups().size(), report.totalDuplicates());
 * }
 * }</pre>
 */
public final class PdfResourceDedup {

    private PdfResourceDedup() {}

    private static final int FPDF_PAGEOBJ_IMAGE = 3;

    /** An image resource identified by page and object index. */
    public record ImageRef(int pageIndex, int objectIndex, int widthPx, int heightPx) {}

    /** A group of duplicate image resources. */
    public record DuplicateGroup(String fingerprint, List<ImageRef> images) {
        public int count() { return images.size(); }
    }

    /** Analysis result. */
    public record DedupReport(
            List<DuplicateGroup> groups,
            int totalImages,
            int uniqueImages,
            int totalDuplicates
    ) {
        public String summary() {
            var sb = new StringBuilder();
            sb.append(String.format("Resource Dedup Report: %d total images, %d unique, %d duplicates%n",
                    totalImages, uniqueImages, totalDuplicates));
            for (DuplicateGroup g : groups) {
                sb.append(String.format("  Group [%s...]: %d duplicates%n",
                        g.fingerprint().substring(0, Math.min(12, g.fingerprint().length())),
                        g.count()));
                for (ImageRef ref : g.images()) {
                    sb.append(String.format("    page=%d, obj=%d, %dx%d px%n",
                            ref.pageIndex(), ref.objectIndex(), ref.widthPx(), ref.heightPx()));
                }
            }
            return sb.toString();
        }
    }

    /**
     * Analyze the document for duplicate image resources.
     *
     * @param doc open PDF document
     * @return dedup report with duplicate groups
     */
    public static DedupReport analyze(PdfDocument doc) {
        int pageCount = doc.pageCount();
        if (pageCount == 0) {
            return new DedupReport(Collections.emptyList(), 0, 0, 0);
        }

        Map<String, List<ImageRef>> fingerprints = new LinkedHashMap<>();
        int totalImages = 0;
        Scratch scratch = FfmHelper.scratch();

        for (int p = 0; p < pageCount; p++) {
            try (PdfPage page = doc.page(p)) {
                MemorySegment rawPage = page.rawHandle();

                int objCount;
                try {
                    objCount = (int) PageEditBindings.FPDFPage_CountObjects.invokeExact(rawPage);
                } catch (Throwable t) { continue; }

                for (int i = 0; i < objCount; i++) {
                    MemorySegment obj;
                    try {
                        obj = (MemorySegment) PageEditBindings.FPDFPage_GetObject.invokeExact(rawPage, i);
                    } catch (Throwable t) { continue; }

                    int type;
                    try { type = (int) PageEditBindings.FPDFPageObj_GetType.invokeExact(obj); }
                    catch (Throwable t) { continue; }

                    if (type == FPDF_PAGEOBJ_IMAGE) {
                        totalImages++;
                        int w = 0;
                        int h = 0;
                        try {
                            int ok = (int) ImageObjBindings
                                    .FPDFImageObj_GetImagePixelSize.invokeExact(obj, scratch.int0, scratch.int1);
                            if (ok != 0) {
                                w = scratch.int0.get(ValueLayout.JAVA_INT, 0);
                                h = scratch.int1.get(ValueLayout.JAVA_INT, 0);
                            }
                        } catch (Throwable t) { NativeRuntime.rethrowFatal(t); }

                        String fp = fingerprint(obj, w, h, scratch);
                        ImageRef ref = new ImageRef(p, i, w, h);
                        fingerprints.computeIfAbsent(fp, k -> new ArrayList<>()).add(ref);
                    }
                }
            }
        }

        // Build duplicate groups (only groups with 2+ images)
        List<DuplicateGroup> groups = new ArrayList<>();
        int duplicateCount = 0;
        for (var entry : fingerprints.entrySet()) {
            if (entry.getValue().size() > 1) {
                groups.add(new DuplicateGroup(entry.getKey(),
                        Collections.unmodifiableList(entry.getValue())));
                duplicateCount += entry.getValue().size();
            }
        }

        return new DedupReport(
                Collections.unmodifiableList(groups),
                totalImages, fingerprints.size(), duplicateCount
        );
    }

    /**
     * Generate a fingerprint for an image object based on its pixel data hash.
     */
    private static String fingerprint(MemorySegment imgObj, int w, int h, Scratch scratch) {
        if (w == 0 && h == 0) {
            return "unknown-" + System.identityHashCode(imgObj);
        }
        try {
            int ok = (int) PageEditBindings.FPDFPageObj_GetBounds.invokeExact(
                    imgObj, scratch.float0, scratch.float1, scratch.float2, scratch.float3);
            if (ok == 0) {
                throw new JPDFiumException("FPDFPageObj_GetBounds failed");
            }

            // Hash dimensions + display bounds
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(intBytes(w));
            md.update(intBytes(h));
            byte[] hash = md.digest(intBytes(w * 31 + h));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Throwable t) {
            return String.format("dim-%dx%d", w, h);
        }
    }

    private static byte[] intBytes(int v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }
}
