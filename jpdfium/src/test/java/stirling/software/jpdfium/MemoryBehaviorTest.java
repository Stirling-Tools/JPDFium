package stirling.software.jpdfium;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import stirling.software.jpdfium.model.StorageOptions;
import stirling.software.jpdfium.panama.PdfiumBuffers;
import stirling.software.jpdfium.panama.QpdfLib;
import stirling.software.jpdfium.doc.PdfMerger;
import stirling.software.jpdfium.panama.PdfiumRuntime;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.charset.StandardCharsets;

/**
 * Memory-behaviour tests: what stays live, what is charged to the Java heap,
 * and what is retained after a large job.
 *
 * <p>These exist because "it does not crash" says nothing about memory. A
 * library that leaks one page per document, or that materializes a 200 MB
 * document-sized array to answer a query, looks perfectly healthy to a
 * functional suite.
 *
 * <p>Heap accounting is measured with the thread allocation counter, so it
 * covers only Java heap on the calling thread. It says nothing about native
 * allocation; native ownership is checked separately through the runtime's live
 * resource counters and the shared-buffer accounting.
 */
class MemoryBehaviorTest {

    private static final ThreadMXBean BEAN =
            (ThreadMXBean) ManagementFactory.getThreadMXBean();

    private static byte[] resource(String name) throws IOException {
        return Objects.requireNonNull(MemoryBehaviorTest.class.getResourceAsStream(
                "/pdfs/general/" + name)).readAllBytes();
    }

    /** Java bytes allocated per operation, after warmup. */
    private static double bytesPerOp(Runnable op, int warmup, int iterations) {
        // The measuring thread must be read here, not at class init: JUnit may
        // run the method on another thread, and an idle thread's counter would
        // let the assertions pass without measuring anything.
        long tid = Thread.currentThread().threadId();
        for (int i = 0; i < warmup; i++) {
            op.run();
        }
        long before = BEAN.getThreadAllocatedBytes(tid);
        for (int i = 0; i < iterations; i++) {
            op.run();
        }
        long after = BEAN.getThreadAllocatedBytes(tid);
        return (after - before) / (double) iterations;
    }

    // Leak detection: every tracked resource must drain to zero.

    @Test
    @Timeout(120)
    void openCloseCyclesLeaveNoTrackedResources() throws Exception {
        PdfiumRuntime.LiveResources before = PdfiumRuntime.liveResources();
        long buffersBefore = PdfiumBuffers.liveSharedBytes();

        for (int i = 0; i < 200; i++) {
            try (PdfDocument doc = PdfDocument.open(resource("minimal.pdf"))) {
                assertEquals(3, doc.pageCount());
                PdfPage page = doc.page(0);
                page.close();
            }
        }

        assertEquals(before, PdfiumRuntime.liveResources(),
                "documents/pages/sessions must drain to their pre-loop value");
        assertEquals(buffersBefore, PdfiumBuffers.liveSharedBytes(),
                "shared render buffers must drain to their pre-loop value");
    }

    @Test
    @Timeout(180)
    void failedOperationsDoNotLeak() throws Exception {
        PdfiumRuntime.LiveResources before = PdfiumRuntime.liveResources();

        // Every one of these fails; none may leave a registration behind.
        for (int i = 0; i < 50; i++) {
            try {
                PdfDocument.open(new byte[]{1, 2, 3}).close();
            } catch (Exception expected) {
                // corrupt input
            }
            try (PdfDocument doc = PdfDocument.open(resource("minimal.pdf"))) {
                doc.page(9_999);
            } catch (Exception expected) {
                // out-of-range page
            }
        }

        assertEquals(before, PdfiumRuntime.liveResources(),
                "a failed open or an out-of-range page must not leak a registration");
    }

    @Test
    @Timeout(120)
    void abandonedDocumentsCurrentlyPinNativeHandles() throws Exception {
        // MEASURED DEFECT, recorded rather than asserted away: opening a
        // document and dropping it without close() pins the native handle, and
        // every page opened from it, for the life of the process. Measured
        // below as a fact so a future fix is visible as a change in the number.
        // Strong reachability via abandoned/openedPages is intentional here:
        // this documents that unclosed handles stay pinned (no Cleaner), not
        // that GC reclaims them. A WeakReference variant would test a
        // reclamation path that was tried and reverted (see below).
        //
        // A document-level Cleaner was tried and reverted: it reclaims the
        // document handle but cannot decrement the pages opened from that
        // document, so liveResources().pages() stays elevated, shutdown()
        // refuses permanently, and the lifecycle tests cascade. Doing this
        // properly needs per-document page tracking, which is separate work.
        List<PdfDocument> abandoned = new ArrayList<>();
        List<PdfPage> openedPages = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                PdfDocument doc = PdfDocument.open(resource("minimal.pdf"));
                abandoned.add(doc);
                PdfPage page = doc.page(0);
                openedPages.add(page);
                assertTrue(page.size().width() > 0);
            }
            for (int i = 0; i < 3; i++) {
                System.gc();
                Thread.sleep(100);
            }
            PdfiumRuntime.LiveResources live = PdfiumRuntime.liveResources();
            System.out.printf("MEM 20 abandoned documents still pinned: %s%n", live);
            // Recorded as a measured fact, not asserted away: a caller that
            // drops a document without close() pins native state until JVM exit.
            assertTrue(live.documents() > 0,
                    "expected abandoned documents to remain pinned (measured fact)");
        } finally {
            // Must clean up fully: this JVM is shared, and both document AND
            // page registrations are checked by later lifecycle tests. Closing
            // only the documents would leave pages=20 and make every later
            // restoreRunningForTests call fail.
            for (PdfPage page : openedPages) {
                try {
                    page.close();
                } catch (Exception ignored) {
                    // best effort during cleanup
                }
            }
            for (PdfDocument doc : abandoned) {
                try {
                    doc.close();
                } catch (Exception ignored) {
                    // best effort during cleanup
                }
            }
        }
    }

    // Eager loading: file-backed operations must not copy the whole
    // document into Java heap.

    @Test
    @Timeout(300)
    void openingFromPathDoesNotMaterializeTheDocument() throws Exception {
        // Build a document large enough that a whole-document copy would be
        // obvious in the allocation counter.
        Path big = Files.createTempFile("mem-probe", ".pdf");
        try {
            Files.write(big, buildLargePdf(24));
            long fileSize = Files.size(big);

            double perOpen = bytesPerOp(() -> {
                try (PdfDocument doc = PdfDocument.open(big)) {
                    assertTrue(doc.pageCount() > 0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, 20, 40);

            System.out.printf("MEM open(Path) %.1f MB file -> %.1f KB/op Java heap%n",
                    fileSize / 1e6, perOpen / 1024);
            // A copy would cost roughly the file size. Allow generous headroom
            // for the wrapper objects, but nothing close to a full copy.
            assertTrue(perOpen < fileSize / 4,
                    "open(Path) allocated " + perOpen + " B/op for a " + fileSize
                            + " B file - that looks like a whole-document copy");
        } finally {
            Files.deleteIfExists(big);
        }
    }

    @Test
    @Timeout(300)
    void mergeFromPathsDoesNotMaterializeEveryInput() throws Exception {
        Path a = Files.createTempFile("mem-merge-a", ".pdf");
        Path b = Files.createTempFile("mem-merge-b", ".pdf");
        Path out = Files.createTempFile("mem-merge-out", ".pdf");
        try {
            Files.write(a, buildLargePdf(16));
            Files.write(b, buildLargePdf(16));
            long combined = Files.size(a) + Files.size(b);
            Files.deleteIfExists(out);

            double perFileBacked = bytesPerOp(() -> {
                try (PdfDocument merged = PdfMerge.mergeFiles(List.of(a, b),
                        StorageOptions.defaults())) {
                    assertTrue(merged != null && merged.pageCount() > 0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, 3, 8);
            System.out.printf("MEM mergeFiles(paths) %.1f MB input -> %.1f KB/op Java heap%n",
                    combined / 1e6, perFileBacked / 1024);
            assertTrue(perFileBacked < combined / 4,
                    "file-backed merge allocated " + perFileBacked + " B/op for "
                            + combined + " B of input");

            // The heap-mode path is the one that materializes documents; record
            // what it costs so the trade-off is a measured number.
            double perHeapMode = bytesPerOp(() -> {
                try (PdfDocument da = PdfDocument.open(a);
                     PdfDocument db = PdfDocument.open(b);
                     PdfDocument merged = PdfMerge.merge(List.of(da, db),
                             StorageOptions.builder().memory().build())) {
                    assertTrue(merged != null && merged.pageCount() > 0);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, 3, 8);
            System.out.printf("MEM merge(memory) %.1f MB input -> %.1f KB/op Java heap%n",
                    combined / 1e6, perHeapMode / 1024);
        } finally {
            Files.deleteIfExists(a);
            Files.deleteIfExists(b);
            Files.deleteIfExists(out);
        }
    }

    // Retention: a huge job must not degrade later normal work.

    @Test
    @Timeout(600)
    void hugeJobThenSmallJobsDoNotRetainOrDegrade() throws Exception {
        Path big = Files.createTempFile("mem-huge", ".pdf");
        try {
            Files.write(big, buildLargePdf(120));
            PdfiumRuntime.LiveResources baseline = PdfiumRuntime.liveResources();
            long buffersBaseline = PdfiumBuffers.liveSharedBytes();

            // Baseline latency for a small operation.
            byte[] small = resource("minimal.pdf");
            double before = nanosPerSmallOp(small, 300);

            // One huge job.
            try (PdfDocument doc = PdfDocument.open(big)) {
                assertTrue(doc.pageCount() > 0);
            }
            System.gc();
            Thread.sleep(200);

            assertEquals(baseline, PdfiumRuntime.liveResources(),
                    "a large document must not leave live resources behind");

            // Same small operation afterwards: latency must not have regressed
            // materially, which would indicate retained pools or caches.
            double after = nanosPerSmallOp(small, 300);
            System.out.printf("MEM small-op before=%.0f ns after=%.0f ns (huge job first)%n",
                    before, after);
            assertTrue(after < before * 3,
                    "small-document latency regressed from " + before + " ns to "
                            + after + " ns after a large job - state is being retained");
        } finally {
            Files.deleteIfExists(big);
        }
    }

    private static double nanosPerSmallOp(byte[] pdf, int iterations) throws Exception {
        for (int i = 0; i < 200; i++) {
            try (PdfDocument doc = PdfDocument.open(pdf)) {
                doc.pageCount();
            }
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try (PdfDocument doc = PdfDocument.open(pdf)) {
                doc.pageCount();
            }
        }
        return (System.nanoTime() - t0) / (double) iterations;
    }

    /**
     * The path-based merge must try the native file-to-file route before reading
     * any input, otherwise a "file-backed" merge still holds the whole input set
     * on the Java heap.
     */
    @Test
    @Timeout(300)
    void pathMergeMustNotMaterializeEveryInput() throws Exception {
        // Without the native file merge the inputs must be read to be merged at
        // all, so this measures a capability, not the streaming path.
        assumeTrue(QpdfLib.isMergeFilesSupported(),
                "file-backed merge requires the native qpdf file entry point");
        Path a = Files.createTempFile("mem-pathmerge-a", ".pdf");
        Path b = Files.createTempFile("mem-pathmerge-b", ".pdf");
        Path out = Files.createTempFile("mem-pathmerge-out", ".pdf");
        try {
            Files.write(a, bigPdf(12, 400_000));
            Files.write(b, bigPdf(12, 400_000));
            long combined = Files.size(a) + Files.size(b);

            double perOp = bytesPerOp(() -> {
                try {
                    PdfMerger.merge(List.of(a, b), out);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, 3, 8);
            System.out.printf("MEM PdfMerger.merge(paths) %.1f MB input -> %.1f KB/op Java heap%n",
                    combined / 1e6, perOp / 1024);
            assertTrue(perOp < combined / 4,
                    "PdfMerger.merge allocated " + perOp + " B/op for " + combined
                            + " B of input - inputs were materialized before the file merge");
        } finally {
            Files.deleteIfExists(a);
            Files.deleteIfExists(b);
            Files.deleteIfExists(out);
        }
    }

    /** Same builder with a filler payload, so a whole-document copy would dominate. */
    private static byte[] bigPdf(int pages, int fillerBytes) {
        StringBuilder sb = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        sb.append("%PDF-1.4\n");
        offsets.add(0);
        offsets.add(sb.length());
        sb.append("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n");
        offsets.add(sb.length());
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages; i++) kids.append(3 + i).append(" 0 R ");
        sb.append("2 0 obj<</Type/Pages/Kids[").append(kids.toString().trim())
                .append("]/Count ").append(pages).append(">>endobj\n");
        String filler = "x".repeat(fillerBytes);
        int firstContent = 3 + pages;
        for (int i = 0; i < pages; i++) {
            offsets.add(sb.length());
            sb.append(3 + i).append(" 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents ")
                    .append(firstContent + i).append(" 0 R>>endobj\n");
        }
        for (int i = 0; i < pages; i++) {
            offsets.add(sb.length());
            sb.append(firstContent + i).append(" 0 obj<</Length ").append(filler.length())
                    .append(">>\nstream\n").append(filler).append("\nendstream\nendobj\n");
        }
        int xref = sb.length();
        int total = firstContent + pages;
        sb.append("xref\n0 ").append(total).append("\n0000000000 65535 f \n");
        for (int i = 1; i < total; i++) {
            sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offsets.get(i)));
        }
        sb.append("trailer<</Size ").append(total).append("/Root 1 0 R>>\nstartxref\n")
                .append(xref).append("\n%%EOF");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] buildLargePdf(int pages) throws IOException {
        final int firstPageObj = 3;
        final int contentObj = firstPageObj + pages;
        return buildWithXref(pages, firstPageObj, contentObj);
    }

    /** Emits a structurally valid, xref-correct PDF of the requested size. */
    private static byte[] buildWithXref(int pages, int firstPageObj, int contentObj)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        sb.append("%PDF-1.4\n");
        offsets.add(0);
        offsets.add(sb.length());
        sb.append("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n");
        offsets.add(sb.length());
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            kids.append(firstPageObj + i).append(" 0 R ");
        }
        sb.append("2 0 obj<</Type/Pages/Kids[").append(kids.toString().trim())
                .append("]/Count ").append(pages).append(">>endobj\n");
        for (int i = 0; i < pages; i++) {
            offsets.add(sb.length());
            sb.append(firstPageObj + i)
                    .append(" 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Contents ")
                    .append(contentObj).append(" 0 R>>endobj\n");
        }
        offsets.add(sb.length());
        int streamStart = sb.length();
        sb.append(contentObj).append(" 0 obj<</Length 0>>\nstream\n");
        for (int i = 0; i < 4000; i++) {
            sb.append("BT /F1 12 Tf 72 720 Td (padding line ").append(i)
                    .append(" of printable text 0123456789) Tj ET\n");
        }
        int streamEnd = sb.length();
        // Patch the real stream length in place so the xref and /Length agree.
        sb.replace(0, sb.length(), sb.substring(0, streamStart)
                + contentObj + " 0 obj<</Length " + (streamEnd - streamStart - 1)
                + ">>\nstream\n"
                + sb.substring(streamStart + ("" + contentObj + " 0 obj<</Length 0>>\nstream\n").length(),
                               streamEnd)
                + "\nendstream\nendobj\n");
        int xrefOffset = sb.length();
        sb.append("xref\n0 ").append(offsets.size()).append("\n");
        sb.append("0000000000 65535 f \n");
        for (int i = 1; i < offsets.size(); i++) {
            sb.append(String.format("%010d 00000 n \n", offsets.get(i)));
        }
        sb.append("trailer<</Size ").append(offsets.size()).append("/Root 1 0 R>>\n");
        sb.append("startxref\n").append(xrefOffset).append("\n%%EOF\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
