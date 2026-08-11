package stirling.software.jpdfium;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native-memory leak detector for JPDFium's FFM/Panama layer.
 *
 * <p>JVM heap metrics do not capture native allocations made through FFM
 * {@code Arena} or PDFium's internal allocators. On Linux we read
 * {@code /proc/self/status} (VmRSS) to track actual resident-set size before
 * and after a large number of open/render/close cycles.
 *
 * <p>On macOS and Windows the RSS assertion is skipped (OS APIs differ), but
 * the cycles still run so that any JVM-visible exception or crash is caught.
 *
 * <p>This test is designed to pass against the stub bridge: the stub allocates
 * real native memory segments through FFM Arenas and must release them properly.
 */
@DisplayName("Native memory leak detection")
class NativeMemoryLeakTest {

    private static final int CYCLES = 500;
    private static final long ALLOWED_GROWTH_MB = 15;
    private static final Pattern PATTERN = Pattern.compile("\\s+");

    @Test
    @DisplayName("500 open/renderAt/close cycles: RSS growth within 15 MB (Linux)")
    @EnabledOnOs(OS.LINUX)
    void noNativeLeakOnRepeatedOpenRenderClose_linux() throws Exception {
        byte[] src = pdfBytes();

        // Warm up JIT and any lazy-init paths before taking baseline.
        warmUp(src, 20);
        System.gc(); Thread.sleep(200);

        long baselineKb = readLinuxVmRSS();
        doOpenRenderCloseCycles(src, CYCLES);
        System.gc(); Thread.sleep(200);
        long afterKb = readLinuxVmRSS();

        long growthMb = (afterKb - baselineKb) / 1024;
        System.out.printf("RSS baseline: %d KB, after %d cycles: %d KB, delta: %+d MB%n",
            baselineKb, CYCLES, afterKb, growthMb);

        assertTrue(growthMb < ALLOWED_GROWTH_MB,
            "RSS grew by " + growthMb + " MB after " + CYCLES
            + " open/render/close cycles -- likely native memory leak."
            + " Check that PdfDocument.close() calls jpdfium_doc_close and"
            + " that all FFM Arenas are closed in try-with-resources.");
    }

    @Test
    @DisplayName("500 open/renderAt/close cycles: no crash or leak-visible exceptions (all OS)")
    void noExceptionLeakOnRepeatedOpenRenderClose() throws Exception {
        byte[] src = pdfBytes();
        warmUp(src, 10);
        // Just assert no unexpected exceptions; RSS check is Linux-only.
        doOpenRenderCloseCycles(src, CYCLES);
    }

    @Test
    @DisplayName("500 open/extractText/close cycles: no crash or exception (all OS)")
    void noLeakOnRepeatedOpenExtractClose() throws Exception {
        byte[] src = pdfBytes();
        for (int i = 0; i < CYCLES; i++) {
            try (PdfDocument doc = PdfDocument.open(src.clone())) {
                try (PdfPage page = doc.page(0)) {
                    String json = page.extractTextJson();
                    assertNotNull(json, "extractTextJson() must not return null");
                }
            }
        }
    }

    @Test
    @DisplayName("500 open/saveBytes/close cycles: no crash or exception (all OS)")
    void noLeakOnRepeatedOpenSaveClose() throws Exception {
        byte[] src = pdfBytes();
        for (int i = 0; i < CYCLES; i++) {
            try (PdfDocument doc = PdfDocument.open(src.clone())) {
                byte[] saved = doc.saveBytes();
                assertNotNull(saved, "saveBytes() must not return null");
                assertTrue(saved.length > 0, "saveBytes() must return non-empty bytes");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void warmUp(byte[] src, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            try (PdfDocument doc = PdfDocument.open(src.clone())) {
                try (PdfPage page = doc.page(0)) {
                    page.renderAt(72);
                }
            }
        }
    }

    private static void doOpenRenderCloseCycles(byte[] src, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            try (PdfDocument doc = PdfDocument.open(src.clone())) {
                try (PdfPage page = doc.page(0)) {
                    var result = page.renderAt(72);
                    // Consume enough of the result to exercise the buffer path.
                    assertNotNull(result, "renderAt() must not return null");
                    assertTrue(result.width() > 0 && result.height() > 0,
                        "Render result must have positive dimensions");
                }
            }
        }
    }

    private static byte[] pdfBytes() throws IOException {
        try (InputStream in = NativeMemoryLeakTest.class
                .getResourceAsStream("/pdfs/general/minimal.pdf")) {
            if (in == null) throw new IOException("minimal.pdf test resource missing");
            return in.readAllBytes();
        }
    }

    /**
     * Reads VmRSS (resident set size) from {@code /proc/self/status} on Linux.
     *
     * @return resident set size in kilobytes
     */
    private static long readLinuxVmRSS() throws IOException {
        String status = Files.readString(Path.of("/proc/self/status"), StandardCharsets.UTF_8);
        for (String line : status.split("\n")) {
            if (line.startsWith("VmRSS:")) {
                // Line format: "VmRSS:    12345 kB"
                String[] parts = PATTERN.split(line.trim());
                if (parts.length >= 2) {
                    return Long.parseLong(parts[1]);
                }
            }
        }
        throw new IOException("VmRSS not found in /proc/self/status");
    }
}
