package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.model.ImageFormat;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.model.RenderQuality;
import stirling.software.jpdfium.panama.BridgeAlloc;
import stirling.software.jpdfium.panama.JpdfiumH$shared;
import stirling.software.jpdfium.panama.JpdfiumLib;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PdfiumRuntime;
import stirling.software.jpdfium.redact.PdfRedactor;
import stirling.software.jpdfium.redact.RedactOptions;
import stirling.software.jpdfium.redact.pii.XmpRedactor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ownership, mutation, and security-contract regression tests.
 *
 * <p>Native lifecycle tests require the real bridge ({@code assumeTrue(isFull())});
 * pure contract validation (buffer sizes, DPI, passwords, channels, double-close)
 * runs against either bridge because rejection happens before any native call.
 */
class LifecycleTest {

    private static Path pdfPath() throws Exception {
        URL url = LifecycleTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf test resource missing");
        return Path.of(url.toURI());
    }

    private static byte[] pdfBytes() throws IOException {
        return Objects.requireNonNull(
                LifecycleTest.class.getResourceAsStream("/pdfs/general/minimal.pdf")).readAllBytes();
    }

    // Structural mutation must invalidate pages opened before it.
    @Test
    void metadataStripInvalidatesOpenPages() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "lifecycle requires real native library");
        try (PdfDocument doc = PdfDocument.open(pdfPath())) {
            PdfPage page = doc.page(0);
            assertDoesNotThrow(page::size);
            XmpRedactor.stripAll(doc);
            assertThrows(IllegalStateException.class, page::size);
            assertThrows(IllegalStateException.class, page::extractText);
            assertDoesNotThrow(page::close);
            assertDoesNotThrow(page::close);
            try (PdfPage fresh = doc.page(0)) {
                assertDoesNotThrow(fresh::size);
            }
        }
    }

    // Raster replacement frees the page out from under open handles.
    @Test
    void convertPageToImageInvalidatesOpenPages() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "lifecycle requires real native library");
        try (PdfDocument doc = PdfDocument.open(pdfPath())) {
            PdfPage page = doc.page(0);
            assertDoesNotThrow(page::size);
            doc.convertPageToImage(0, 72);
            assertThrows(IllegalStateException.class, () -> page.renderAt(72));
            assertDoesNotThrow(page::close);
            try (PdfPage fresh = doc.page(0)) {
                assertDoesNotThrow(fresh::size);
            }
        }
    }

    // End-to-end open, mutate, stale-use, close, reopen, save sequence.
    @Test
    void lifecycleStateMachine() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "lifecycle requires real native library");
        PdfDocument doc = PdfDocument.open(pdfPath());
        PdfPage page = doc.page(0);
        assertDoesNotThrow(page::size);
        XmpRedactor.stripAll(doc);
        assertThrows(IllegalStateException.class, page::size);
        assertDoesNotThrow(page::close);
        PdfPage replacement = doc.page(0);
        byte[] saved = assertDoesNotThrow(doc::saveBytes);
        assertTrue(saved.length > 0);
        assertDoesNotThrow(replacement::close);
        assertDoesNotThrow(doc::close);
        assertDoesNotThrow(doc::close);
        assertThrows(IllegalStateException.class, doc::pageCount);
        assertThrows(IllegalStateException.class, replacement::size);
    }

    // Rejection happens before the downcall, so no native write occurs.
    @Test
    void renderIntoRejectsBadBuffers() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfPath());
             PdfPage page = doc.page(0);
             Arena arena = Arena.ofConfined()) {
            MemorySegment tiny = arena.allocate(16);
            assertThrows(IllegalArgumentException.class, () -> page.renderInto(tiny, 100, 100));
            assertThrows(IllegalArgumentException.class,
                    () -> page.renderInto((MemorySegment) null, 10, 10));
            assertThrows(IllegalArgumentException.class, () -> page.renderInto(tiny, 0, 10));
            MemorySegment readOnly = arena.allocate(40000).asReadOnly();
            assertThrows(IllegalArgumentException.class, () -> page.renderInto(readOnly, 100, 100));
            MemorySegment heap = MemorySegment.ofArray(new byte[40000]);
            assertThrows(IllegalArgumentException.class, () -> page.renderInto(heap, 100, 100));
        }
    }

    // Overlay-only redaction would leave extractable content behind.
    @Test
    void commitRedactionsRefusesVisualOnly() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfPath());
             PdfPage page = doc.page(0)) {
            assertThrows(IllegalArgumentException.class, () -> page.commitRedactions(0xFF000000, false));
            // The rejected mutation leaves the page usable.
            assertDoesNotThrow(page::size);
        }
    }

    // Non-positive DPI silently flipped transparency in native code.
    @Test
    void renderRejectsNonPositiveDpi() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfPath());
             PdfPage page = doc.page(0)) {
            assertThrows(IllegalArgumentException.class, () -> page.renderAt(0));
            assertThrows(IllegalArgumentException.class, () -> page.renderAt(-72));
            assertThrows(IllegalArgumentException.class, () -> page.renderImage(0));
            assertThrows(IllegalArgumentException.class,
                    () -> page.renderToBytes(0, ImageFormat.PNG));
        }
    }

    // Null and empty passwords must behave identically on every overload.
    @Test
    void passwordContractMatrix() throws Exception {
        byte[] bytes = pdfBytes();
        assertThrows(IllegalArgumentException.class, () -> PdfDocument.open(bytes, null));
        assertThrows(IllegalArgumentException.class, () -> PdfDocument.open(pdfPath(), null));
        assertThrows(IllegalArgumentException.class,
                () -> PdfDocument.open(ByteBuffer.wrap(bytes), null));
        try (PdfDocument a = PdfDocument.open(bytes, "");
             PdfDocument b = PdfDocument.open(pdfPath(), "")) {
            assertEquals(a.pageCount(), b.pageCount());
        }
    }

    // Stream opens are unbounded by default; the caller opts into a bound.
    @Test
    void streamBoundsAreOptIn() throws Exception {
        byte[] bytes = pdfBytes();
        assertThrows(IllegalArgumentException.class,
                () -> PdfDocument.open(new ByteArrayInputStream(bytes), 10));
        assertThrows(IllegalArgumentException.class,
                () -> PdfDocument.open(new ByteArrayInputStream(bytes), 0));
        try (PdfDocument doc = PdfDocument.open(
                new ByteArrayInputStream(bytes), bytes.length + 1024)) {
            assertTrue(doc.pageCount() > 0);
        }
        try (PdfDocument doc = PdfDocument.open(new ByteArrayInputStream(bytes))) {
            assertTrue(doc.pageCount() > 0);
        }
        try (PdfDocument doc = PdfDocument.open(new ByteArrayInputStream(bytes), "")) {
            assertTrue(doc.pageCount() > 0);
        }
    }

    // A failing sink must not stall unrelated PDFium work.
    @Test
    void throwingChannelPropagatesAndReleasesGuard() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfPath())) {
            WritableByteChannel throwing = new WritableByteChannel() {
                private boolean open = true;

                @Override
                public int write(ByteBuffer src) throws IOException {
                    throw new IOException("boom");
                }

                @Override
                public boolean isOpen() {
                    return open;
                }

                @Override
                public void close() {
                    open = false;
                }
            };
            assertThrows(IOException.class,
                    () -> JpdfiumLib.docSaveTo(doc.nativeHandle(), throwing));
            // The guard was released: unrelated native work still proceeds.
            assertTrue(doc.pageCount() > 0);
            assertTrue(doc.saveBytes().length > 0);
        }
    }

    // Channel writes may be partial; output must still reopen correctly.
    // (Byte equality is not asserted: each save regenerates the trailer /ID.)
    @Test
    void partialWriteChannelMatchesSaveBytes() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfPath())) {
            int expectedPages = doc.pageCount();
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            WritableByteChannel chunked = new WritableByteChannel() {
                private boolean open = true;

                @Override
                public int write(ByteBuffer src) {
                    int n = Math.min(1, src.remaining());
                    for (int i = 0; i < n; i++) sink.write(src.get());
                    return n;
                }

                @Override
                public boolean isOpen() {
                    return open;
                }

                @Override
                public void close() {
                    open = false;
                }
            };
            JpdfiumLib.docSaveTo(doc.nativeHandle(), chunked);
            byte[] bytes = sink.toByteArray();
            assertTrue(bytes.length > 0);
            try (PdfDocument reopened = PdfDocument.open(bytes)) {
                assertEquals(expectedPages, reopened.pageCount());
            }
        }
    }

    // Repeat close must never double-free native handles.
    @Test
    void doubleCloseIsHarmless() throws Exception {
        PdfDocument doc = PdfDocument.open(pdfPath());
        PdfPage page = doc.page(0);
        assertDoesNotThrow(page::close);
        assertDoesNotThrow(page::close);
        assertDoesNotThrow(doc::close);
        assertDoesNotThrow(doc::close);
    }

    // Heap and direct transports must open identical documents.
    @Test
    void directBufferTransportEquivalence() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "transport equivalence requires real native library");
        byte[] bytes = pdfBytes();
        byte[] prefix = {0x00, 0x01, 0x02, 0x03};
        ByteBuffer direct = ByteBuffer.allocateDirect(prefix.length + bytes.length);
        direct.put(prefix);
        direct.put(bytes);
        direct.flip();
        direct.position(prefix.length);
        ByteBuffer sliceView = direct.slice();
        int remaining = sliceView.remaining();
        try (PdfDocument fromBuffer = PdfDocument.open(sliceView);
             PdfDocument fromBytes = PdfDocument.open(bytes)) {
            assertEquals(fromBytes.pageCount(), fromBuffer.pageCount());
        }
        assertEquals(prefix.length + remaining, direct.limit());
    }

    // ABI handshake: packaged Java/native combinations agree or fail clearly.
    // The layout assertion pins the uint64_t mapping: C_LONG is the normalized
    // 8-byte layout on every platform, including LLP64 Windows.
    @Test
    void abiHandshake() {
        assertEquals(JpdfiumLib.EXPECTED_ABI_VERSION, JpdfiumLib.abiVersion());
        assertEquals(JpdfiumLib.EXPECTED_PTR_SIZE, JpdfiumLib.abiQuery(0));
        assertEquals(JpdfiumLib.EXPECTED_RECTF_SIZE, JpdfiumLib.abiQuery(1));
        assertEquals(JpdfiumLib.EXPECTED_RECTF_RIGHT_OFFSET, JpdfiumLib.abiQuery(2));
        assertEquals(-1, JpdfiumLib.abiQuery(999));
        assertEquals(8, JpdfiumH$shared.C_LONG.byteSize());
        assertEquals(8, JpdfiumH$shared.C_LONG.byteAlignment());
        assertDoesNotThrow(JpdfiumLib::checkAbiCompatible);
    }

    // Live-byte accounting must return to baseline after close.
    @Test
    void bridgeAllocTracksViewsAndSaves() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfPath());
             PdfPage page = doc.page(0)) {
            long renderBefore = BridgeAlloc.liveBytes(BridgeAlloc.Tag.RENDER_OUTPUT);
            long saveBefore = BridgeAlloc.liveBytes(BridgeAlloc.Tag.SAVE_OUTPUT);
            try (var view = JpdfiumLib.renderPageView(page.nativeHandle(), 72, false)) {
                assertTrue(view.width() > 0);
                assertTrue(BridgeAlloc.liveBytes(BridgeAlloc.Tag.RENDER_OUTPUT) > renderBefore);
            }
            assertEquals(renderBefore, BridgeAlloc.liveBytes(BridgeAlloc.Tag.RENDER_OUTPUT));
            assertTrue(doc.saveBytes().length > 0);
            assertEquals(saveBefore, BridgeAlloc.liveBytes(BridgeAlloc.Tag.SAVE_OUTPUT));
            assertTrue(BridgeAlloc.peakBytes(BridgeAlloc.Tag.SAVE_OUTPUT) > 0);
        }
    }

    // PdfiumRuntime execution domain counting is always on; wait/hold timing stays gated off by default.
    @Test
    void guardStatsAdvanceWithUse() {
        var before = PdfiumRuntime.stats();
        PdfiumRuntime.execute(() -> {
        });
        var after = PdfiumRuntime.stats();
        assertTrue(after.acquisitions() >= before.acquisitions() + 1);
        assertEquals(0, after.waitNanos());
        assertEquals(0, after.holdNanos());
    }

    // Over-budget saves fail before the Java copy exists.
    @Test
    void saveBytesRespectsMaxSaveBytes() throws Exception {
        String previous = System.getProperty("jpdfium.maxSaveResultBytes");
        System.setProperty("jpdfium.maxSaveResultBytes", "10");
        try (PdfDocument doc = PdfDocument.open(pdfPath())) {
            assertThrows(JPDFiumException.class, doc::saveBytes);
        } finally {
            if (previous == null) {
                System.clearProperty("jpdfium.maxSaveResultBytes");
            } else {
                System.setProperty("jpdfium.maxSaveResultBytes", previous);
            }
        }
    }

    // Undersized progressive targets would corrupt the caller arena.
    @Test
    void progressiveRejectsUndersizedBitmap() throws Exception {
        try (PdfDocument doc = PdfDocument.open(pdfPath());
             PdfPage page = doc.page(0);
             Arena arena = Arena.ofConfined()) {
            MemorySegment tiny = arena.allocate(16);
            assertThrows(IllegalArgumentException.class,
                    () -> page.startProgressiveRender(tiny, 100, 100, RenderQuality.SCREEN));
        }
    }

    // Security holds only if the saved artifact stays clean on reopen.
    @Test
    void redactionSurvivesSaveReopen() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "redaction verification requires real native library");
        byte[] input;
        try (var in = LifecycleTest.class.getResourceAsStream(
                "/pdfs/redact/redact-test-sanitize-remnants.pdf")) {
            assumeTrue(in != null, "redact fixture missing");
            input = in.readAllBytes();
        }
        RedactOptions opts = RedactOptions.builder()
                .addWord("SECRET")
                .removeContent(true)
                .build();
        byte[] saved;
        try (PdfDocument doc = PdfDocument.open(input)) {
            PdfRedactor.redact(doc, opts);
            saved = doc.saveBytes();
        }
        try (PdfDocument reopened = PdfDocument.open(saved);
             PdfPage page = reopened.page(0)) {
            String text = page.extractText();
            assertFalseTextContains(text, "SECRET");
        }
    }

    private static void assertFalseTextContains(String text, String needle) {
        if (text != null && text.contains(needle)) {
            throw new AssertionError("reopened text still contains redacted content: " + needle);
        }
    }

    // Unbounded stitches die inside image allocation.
    @Test
    void stitchedImageRespectsPixelBudget() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "stitch budget requires real native library");
        String previous = System.getProperty("jpdfium.maxRenderPixels");
        System.setProperty("jpdfium.maxRenderPixels", "1000");
        try (PdfDocument doc = PdfDocument.open(pdfPath())) {
            assertThrows(JPDFiumException.class,
                    () -> doc.renderer().renderCombinedImage(72));
        } finally {
            if (previous == null) {
                System.clearProperty("jpdfium.maxRenderPixels");
            } else {
                System.setProperty("jpdfium.maxRenderPixels", previous);
            }
        }
    }

    // Same input must paint identical pixels on repeat calls.
    // (Exact equality with the allocating path is not asserted: that path fills
    // a white background and applies screen flags, while renderInto draws over
    // the caller-owned buffer with caller flags.)
    @Test
    void renderIntoIsDeterministic() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "pixel comparison requires real native library");
        try (PdfDocument doc = PdfDocument.open(pdfPath());
             PdfPage page = doc.page(0);
             Arena arena = Arena.ofConfined()) {
            var result = page.renderAt(72, false, null);
            int w = result.width();
            int h = result.height();
            MemorySegment first = arena.allocate((long) w * h * 4);
            MemorySegment second = arena.allocate((long) w * h * 4);
            page.renderInto(first, w, h);
            page.renderInto(second, w, h);
            byte[] a = first.toArray(ValueLayout.JAVA_BYTE);
            byte[] b = second.toArray(ValueLayout.JAVA_BYTE);
            assertArrayEquals(a, b);
            boolean anyNonZero = false;
            for (byte v : a) {
                if (v != 0) {
                    anyNonZero = true;
                    break;
                }
            }
            assertTrue(anyNonZero, "renderInto painted no content");
        }
    }

    // Saving over pending marks would persist unburned redactions.
    @Test
    void uncommittedMarksRefuseSave() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "save guard requires real native library");
        try (PdfDocument doc = PdfDocument.open(pdfPath());
             PdfPage page = doc.page(0)) {
            page.markRedactRegion(new Rect(10, 10, 50, 20), 0xFF000000);
            assertThrows(Exception.class, doc::saveBytes);
            page.clearPendingRedactions();
            assertTrue(doc.saveBytes().length > 0);
        }
    }

    // Slow sinks must not block unrelated native calls.
    @Test
    void slowChannelDoesNotBlockUnrelatedWork() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "concurrency probe requires real native library");
        try (PdfDocument doc = PdfDocument.open(pdfPath())) {
            AtomicInteger writes = new AtomicInteger();
            WritableByteChannel slow = new WritableByteChannel() {
                private boolean open = true;

                @Override
                public int write(ByteBuffer src) throws IOException {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    }
                    int n = src.remaining();
                    src.position(src.limit());
                    writes.addAndGet(n);
                    return n;
                }

                @Override
                public boolean isOpen() {
                    return open;
                }

                @Override
                public void close() {
                    open = false;
                }
            };
            Thread saver = new Thread(() -> {
                try {
                    JpdfiumLib.docSaveTo(doc.nativeHandle(), slow);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            saver.start();
            // Unrelated native work proceeds while the save drains slowly.
            long deadline = System.currentTimeMillis() + 15000;
            int observed = 0;
            while (saver.isAlive() && System.currentTimeMillis() < deadline) {
                observed = doc.pageCount();
                Thread.sleep(10);
            }
            saver.join(15000);
            assertTrue(observed > 0);
            assertTrue(writes.get() > 0);
        }
    }
}
