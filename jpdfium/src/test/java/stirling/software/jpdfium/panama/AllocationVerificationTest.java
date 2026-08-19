package stirling.software.jpdfium.panama;

import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.doc.PdfPageEditor;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CI Gate: Hard-fail allocation verification tests for PDFium FFM hot call paths.
 *
 * <p>Measures steady-state Java heap allocation per operation using
 * {@link ThreadMXBean#getThreadAllocatedBytes(long)} after JIT warmup.
 *
 * <p>Budget criteria:
 * <ul>
 *   <li><strong>Zero-allocation paths</strong> (page render into buffer, page count, permissions,
 *       rotation, fast-path downcalls): budget is 16.0 B/op to allow for minimal JVM bookkeeping /
 *       noise floor while strictly catching any boxing, object construction, or heap copying.
 *   <li><strong>String-returning paths</strong> (text JSON extraction): budget is bounded by the
 *       expected String byte size.
 * </ul>
 */
class AllocationVerificationTest {

    private static final int WARMUP_ITERATIONS = 20_000;
    private static final int MEASURE_ITERATIONS = 10_000;
    private static final double ZERO_ALLOC_BUDGET_BYTES = 16.0;

    private static Path tempPdf;
    private static PdfDocument document;
    private static PdfPage page;
    private static Arena sharedArena;
    private static MemorySegment scratchBitmap;
    private static MemorySegment rawPageSegment;
    private static ThreadMXBean threadBean;
    private static long threadId;

    @BeforeAll
    static void setUp() throws Exception {
        NativeLoader.ensureLoaded();
        assumeTrue(NativeRuntime.isFull(), "Allocation verification requires real PDFium native library");

        tempPdf = Files.createTempFile("alloc-test-", ".pdf");
        try (var in = AllocationVerificationTest.class.getResourceAsStream("/pdfs/general/minimal.pdf")) {
            if (in != null) {
                Files.write(tempPdf, in.readAllBytes());
            } else {
                try (PdfDocument blank = PdfDocument.createEmpty()) {
                    blank.save(tempPdf);
                }
            }
        }
        document = PdfDocument.open(tempPdf);
        page = document.page(0);
        rawPageSegment = page.rawHandle();

        sharedArena = Arena.ofShared();
        long bufferSize = 612L * 792L * 4L;
        scratchBitmap = sharedArena.allocate(bufferSize);

        threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        threadId = Thread.currentThread().threadId();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (page != null) page.close();
        if (document != null) document.close();
        if (sharedArena != null) sharedArena.close();
        if (tempPdf != null) Files.deleteIfExists(tempPdf);
    }

    @Test
    @DisplayName("Hot Path 1: Page count downcall allocates under budget (< 16 B/op)")
    void testPageCount_allocatesUnderBudget() {
        // Warmup to trigger C2 JIT compilation
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            int _ = document.pageCount();
        }

        long beforeAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);
        int sink = 0;
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            sink += document.pageCount();
        }
        long afterAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);

        double bytesPerOp = (afterAllocatedBytes - beforeAllocatedBytes) / (double) MEASURE_ITERATIONS;
        assertTrue(sink > 0, "Page count must be positive");
        assertTrue(bytesPerOp < ZERO_ALLOC_BUDGET_BYTES,
                "document.pageCount() steady-state bytes/op (" + bytesPerOp + ") exceeded budget " + ZERO_ALLOC_BUDGET_BYTES);
    }

    @Test
    @DisplayName("Hot Path 2: Document permissions query allocates under budget (< 16 B/op)")
    void testPermissions_allocatesUnderBudget() {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            long _ = document.permissions();
        }

        long beforeAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);
        long sink = 0;
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            sink += document.permissions();
        }
        long afterAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);

        double bytesPerOp = (afterAllocatedBytes - beforeAllocatedBytes) / (double) MEASURE_ITERATIONS;
        assertTrue(sink != 0, "Permissions sink should be non-zero");
        assertTrue(bytesPerOp < ZERO_ALLOC_BUDGET_BYTES,
                "document.permissions() steady-state bytes/op (" + bytesPerOp + ") exceeded budget " + ZERO_ALLOC_BUDGET_BYTES);
    }

    @Test
    @DisplayName("Hot Path 3: Direct page render into native scratch buffer allocates under budget (< 16 B/op)")
    void testDirectPageRender_allocatesUnderBudget() {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            page.renderInto(scratchBitmap, 612, 792);
        }

        long beforeAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            page.renderInto(scratchBitmap, 612, 792);
        }
        long afterAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);

        double bytesPerOp = (afterAllocatedBytes - beforeAllocatedBytes) / (double) MEASURE_ITERATIONS;
        assertTrue(bytesPerOp < ZERO_ALLOC_BUDGET_BYTES,
                "page.renderInto(scratchBitmap) steady-state bytes/op (" + bytesPerOp + ") exceeded budget " + ZERO_ALLOC_BUDGET_BYTES);
    }

    @Test
    @DisplayName("Hot Path 4: Page rotation query allocates under budget (< 16 B/op)")
    void testPageRotation_allocatesUnderBudget() {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            int _ = PdfPageEditor.getRotation(rawPageSegment);
        }

        long beforeAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);
        int sink = 0;
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            sink += PdfPageEditor.getRotation(rawPageSegment);
        }
        long afterAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);

        double bytesPerOp = (afterAllocatedBytes - beforeAllocatedBytes) / (double) MEASURE_ITERATIONS;
        assertTrue(sink >= 0, "Rotation sink should be non-negative");
        assertTrue(bytesPerOp < ZERO_ALLOC_BUDGET_BYTES,
                "PdfPageEditor.getRotation() steady-state bytes/op (" + bytesPerOp + ") exceeded budget " + ZERO_ALLOC_BUDGET_BYTES);
    }

    @Test
    @DisplayName("Hot Path 5: Fast-path crop downcall allocates under budget (< 16 B/op)")
    void testCropFastPath_allocatesUnderBudget() {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            JpdfiumLib.cropRemoveContent(page.nativeHandle(), 0, 0, 612, 792);
        }

        long beforeAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            JpdfiumLib.cropRemoveContent(page.nativeHandle(), 0, 0, 612, 792);
        }
        long afterAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);

        double bytesPerOp = (afterAllocatedBytes - beforeAllocatedBytes) / (double) MEASURE_ITERATIONS;
        assertTrue(bytesPerOp < ZERO_ALLOC_BUDGET_BYTES,
                "JpdfiumLib.cropRemoveContent fast-path steady-state bytes/op (" + bytesPerOp + ") exceeded budget " + ZERO_ALLOC_BUDGET_BYTES);
    }

    @Test
    @DisplayName("Hot Path 6: Text extraction allocates bounded by unmarshalled String size")
    void testTextExtraction_allocatesBoundedByStringSize() {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            String _ = page.extractTextJson();
        }

        long beforeAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);
        int totalChars = 0;
        int lastLen = 0;
        for (int i = 0; i < MEASURE_ITERATIONS; i++) {
            String json = page.extractTextJson();
            lastLen = json.length();
            totalChars += lastLen;
        }
        long afterAllocatedBytes = threadBean.getThreadAllocatedBytes(threadId);

        double bytesPerOp = (afterAllocatedBytes - beforeAllocatedBytes) / (double) MEASURE_ITERATIONS;
        assertTrue(totalChars >= 0, "Character count should be non-negative");
        // String in Java heap allocates byte[] + String header + confined arena: ~2 * length + 256 bytes
        double expectedMaxStringBytes = (lastLen * 2.0) + 256.0;
        assertTrue(bytesPerOp <= expectedMaxStringBytes,
                "page.extractTextJson() steady-state bytes/op (" + bytesPerOp + ") exceeded expected string budget " + expectedMaxStringBytes);
    }
}
