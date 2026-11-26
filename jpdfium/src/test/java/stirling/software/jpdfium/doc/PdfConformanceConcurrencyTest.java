package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.exception.JPDFiumException;
import stirling.software.jpdfium.fonts.FontRewriteResult;
import stirling.software.jpdfium.fonts.PdfFontRewriter;
import stirling.software.jpdfium.panama.BridgeAlloc;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.PdfiumRuntime;
import stirling.software.jpdfium.panama.PdfiumRuntimeTestProbe;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Multi-threaded JVM stress test mixing PDF/A (1b, 2b, 2u) and PDF/X (1a selective flattening,
 * lossless opaque SMask stripping, 3, 4, PdfDocument overload, and failure paths), verifying zero
 * crashes, zero deadlocks, zero handle/allocation leaks, and zero temp spool leaks.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfConformanceConcurrencyTest {

    @Test
    @Timeout(120)
    void parallelPdfAAndPdfXConversionsLeaveZeroLeaks() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "real native library required");
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        assumeTrue(PdfFontRewriter.isAvailable(), "PdfFontRewriter unavailable");

        byte[] basicRgbPdf = buildBasicTextPdf("Parallel conformance body text", false);
        byte[] transparentPdf = buildTransparentTwoPagePdf();
        byte[] opaqueSMaskPdf = buildOpaqueSMaskPdf();
        byte[] signedMarkerPdf = buildSignedMarkerPdf();

        byte[] cmykV2 = PdfIccProfiles.compactCmykV2();
        byte[] cmykV4 = PdfIccProfiles.compactCmyk();

        PdfXOptions x1aFlatten = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_1A)
                .outputIntentProfile(cmykV2)
                .title("Parallel X1a Flatten")
                .flattenTransparency(true)
                .flattenDpi(120)
                .build();
        PdfXOptions x3Opts = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_3)
                .outputIntentProfile(cmykV2)
                .title("Parallel X3")
                .build();
        PdfXOptions x4Opts = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .outputIntentProfile(cmykV4)
                .title("Parallel X4")
                .build();

        // Warm once before capturing baseline so static init allocations do not skew deltas.
        PdfAConverter.toPdfA2b(basicRgbPdf);
        PdfiumRuntime.LiveResources liveBefore = PdfiumRuntime.liveResources();
        BridgeAlloc.Snapshot allocBefore = BridgeAlloc.snapshot();
        Set<String> tempsBefore = tempFiles();

        int threads = 8;
        int rounds = 4;
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    gate.await();
                    for (int r = 0; r < rounds; r++) {
                        int op = (threadId + r) % 8;
                        switch (op) {
                            case 0 -> verifyPdfA(PdfAConverter.toPdfA1b(basicRgbPdf), 1);
                            case 1 -> verifyPdfA(PdfAConverter.toPdfA2b(basicRgbPdf), 1);
                            case 2 -> verifyPdfA(PdfAConverter.toPdfA2u(basicRgbPdf), 1);
                            case 3 -> {
                                byte[] out = PdfXConverter.applyMetadata(transparentPdf, x1aFlatten);
                                assertPdfHeader(out);
                                try (PDDocument doc = Loader.loadPDF(out)) {
                                    assertEquals(2, doc.getNumberOfPages());
                                    PDFTextStripper stripper = new PDFTextStripper();
                                    stripper.setStartPage(1);
                                    stripper.setEndPage(1);
                                    assertTrue(stripper.getText(doc).contains("Clean vector page"),
                                            "Non-transparent page must remain vector text");
                                }
                            }
                            case 4 -> {
                                byte[] out = PdfXConverter.applyMetadata(opaqueSMaskPdf, x1aFlatten);
                                assertPdfHeader(out);
                                try (PDDocument doc = Loader.loadPDF(out)) {
                                    assertEquals(1, doc.getNumberOfPages());
                                    String text = new PDFTextStripper().getText(doc);
                                    assertTrue(text.contains("Opaque mask keeps text"),
                                            "All-0xFF SMask page must strip losslessly without rasterizing");
                                }
                            }
                            case 5 -> {
                                byte[] out = PdfXConverter.applyMetadata(basicRgbPdf, x4Opts);
                                assertPdfHeader(out);
                                PdfXConverter.checkMetadata(out, x4Opts);
                                assertEquals(0, PdfFontAuditor.unembeddedNames(out).size());
                            }
                            case 6 -> {
                                try (PdfDocument inDoc = PdfDocument.open(basicRgbPdf);
                                        PdfDocument outDoc = PdfXConverter.applyMetadata(inDoc, x3Opts)) {
                                    assertEquals(1, outDoc.pageCount());
                                    byte[] saved = outDoc.saveBytes();
                                    assertPdfHeader(saved);
                                    PdfXConverter.checkMetadata(saved, x3Opts);
                                }
                            }
                            default -> assertThrows(JPDFiumException.class,
                                    () -> PdfXConverter.applyMetadata(signedMarkerPdf, x4Opts));
                        }
                    }
                    return null;
                }));
            }
            gate.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        }

        assertEquals(liveBefore, PdfiumRuntime.liveResources(),
                "Native PdfDocument/PdfPage/Session handles leaked under concurrent conformance load");
        assertFalse(PdfiumRuntimeTestProbe.domainLocked(),
                "Domain lock remained held after worker completion");
        assertEquals(0, PdfiumRuntimeTestProbe.entryDepth(), "Unmatched domain entry depth");
        assertEquals(allocBefore, BridgeAlloc.snapshot(),
                "BridgeAlloc save/render byte counters leaked");
        Set<String> newlyLeakedTemps = new TreeSet<>(tempFiles());
        newlyLeakedTemps.removeAll(tempsBefore);
        assertTrue(newlyLeakedTemps.isEmpty(),
                "Temporary jpdfium-* spool files leaked: " + newlyLeakedTemps);
    }

    @Test
    @Timeout(180)
    void unwarmedColdStartConcurrentInitDoesNotDeadlock(@TempDir Path tmp) throws Exception {
        assumeTrue(NativeRuntime.isFull(), "real native library required");
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        assumeTrue(PdfFontRewriter.isAvailable(), "PdfFontRewriter unavailable");

        Path inputPdf = tmp.resolve("input.pdf");
        Files.write(inputPdf, buildBasicTextPdf("Cold start concurrency text", false));
        Path isolatedTmp = Files.createDirectories(tmp.resolve("tmp"));
        Path isolatedCache = Files.createDirectories(tmp.resolve("cache"));

        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        String libPath = System.getProperty("java.library.path");

        int rounds = 12;
        for (int round = 0; round < rounds; round++) {
            List<String> cmd = new ArrayList<>();
            cmd.add(java);
            cmd.add("--enable-native-access=ALL-UNNAMED");
            cmd.add("-Djava.io.tmpdir=" + isolatedTmp);
            cmd.add("-Djpdfium.native.cacheDir=" + isolatedCache);
            if (libPath != null && !libPath.isBlank()) {
                cmd.add("-Djava.library.path=" + libPath);
            }
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add(ColdStartConcurrencyProbeMain.class.getName());
            cmd.add(inputPdf.toString());

            Path logFile = tmp.resolve("round-" + round + ".log");
            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                String log = Files.exists(logFile) ? Files.readString(logFile) : "";
                fail("Cold-start child JVM timed out on round " + round
                        + " (possible <clinit> deadlock):\n" + log);
            }
            int rc = process.exitValue();
            if (rc != 0) {
                String log = Files.exists(logFile) ? Files.readString(logFile) : "";
                assertEquals(0, rc, "Cold-start child JVM failed on round " + round + ":\n" + log);
            }
        }

        try (var stream = Files.list(isolatedTmp)) {
            List<String> leftovers = stream
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("jpdfium-"))
                    .toList();
            assertTrue(leftovers.isEmpty(),
                    "Isolated tmpdir retained jpdfium-* files: " + leftovers);
        }
    }

    @Test
    @Timeout(60)
    void flattenPixelCapThrowsJpdfiumExceptionWithoutLeakingHandles() throws Exception {
        assumeTrue(NativeRuntime.isFull(), "real native library required");
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");

        byte[] transparentPdf = buildTransparentTwoPagePdf();
        PdfXOptions flattenOpts = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_1A)
                .outputIntentProfile(PdfIccProfiles.compactCmykV2())
                .title("Pixel Cap Test")
                .flattenTransparency(true)
                .flattenDpi(150)
                .build();

        PdfiumRuntime.LiveResources before = PdfiumRuntime.liveResources();
        String prev = System.getProperty("jpdfium.pdfx.maxFlattenPagePixels");
        System.setProperty("jpdfium.pdfx.maxFlattenPagePixels", "1000");
        try {
            JPDFiumException ex = assertThrows(JPDFiumException.class,
                    () -> PdfXConverter.applyMetadata(transparentPdf, flattenOpts));
            assertTrue(ex.getMessage().contains("maxFlattenPagePixels"),
                    "Expected pixel cap diagnostic, got: " + ex.getMessage());
        } finally {
            if (prev == null) {
                System.clearProperty("jpdfium.pdfx.maxFlattenPagePixels");
            } else {
                System.setProperty("jpdfium.pdfx.maxFlattenPagePixels", prev);
            }
        }
        assertEquals(before, PdfiumRuntime.liveResources(),
                "Pixel cap failure must not leak native document or page handles");
        assertFalse(PdfiumRuntimeTestProbe.domainLocked());
    }

    private static void verifyPdfA(FontRewriteResult result, int expectedPages) throws Exception {
        byte[] out = result.pdf();
        assertPdfHeader(out);
        try (PdfDocument doc = PdfDocument.open(out)) {
            assertEquals(expectedPages, doc.pageCount());
        }
        assertEquals(0, PdfFontAuditor.unembeddedNames(out).size());
    }

    private static void assertPdfHeader(byte[] pdf) {
        assertTrue(pdf.length > 16, "Output PDF unexpectedly short");
        String head = new String(Arrays.copyOf(pdf, 8), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF-1.") || head.startsWith("%PDF-2."),
                "Invalid PDF header: " + head);
    }

    private static Set<String> tempFiles() throws Exception {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var stream = Files.list(tmp)) {
            return stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("jpdfium-"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static byte[] buildBasicTextPdf(String text, boolean withSignatureMarker)
            throws Exception {
        try (PDDocument doc = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText(text);
                cs.endText();
            }
            if (withSignatureMarker) {
                COSDictionary sig = new COSDictionary();
                sig.setName(COSName.TYPE, "Sig");
                sig.setString(COSName.CONTENTS, "00");
                page.getCOSObject().setItem(COSName.getPDFName("JpdfiumTestSig"), sig);
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildSignedMarkerPdf() throws Exception {
        byte[] base = buildBasicTextPdf("Signed placeholder", false);
        String raw = new String(base, StandardCharsets.ISO_8859_1);
        int idx = raw.lastIndexOf("%%EOF");
        String patched = raw.substring(0, idx)
                + "% /ByteRange [0 1 2 3]\n"
                + raw.substring(idx);
        return patched.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] buildTransparentTwoPagePdf() throws Exception {
        try (PDDocument doc = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage clean = new PDPage(PDRectangle.A4);
            doc.addPage(clean);
            try (PDPageContentStream cs = new PDPageContentStream(doc, clean)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Clean vector page");
                cs.endText();
            }
            PDPage translucent = new PDPage(PDRectangle.A4);
            doc.addPage(translucent);
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.4f);
            try (PDPageContentStream cs = new PDPageContentStream(doc, translucent)) {
                cs.setGraphicsStateParameters(gs);
                cs.setNonStrokingColor(0.1f, 0.5f, 0.9f);
                cs.addRect(72, 600, 120, 80);
                cs.fill();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] buildOpaqueSMaskPdf() throws Exception {
        try (PDDocument doc = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            BufferedImage rgb = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            PDImageXObject image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
                    .createFromImage(doc, rgb);

            COSStream smask = doc.getDocument().createCOSStream();
            smask.setName(COSName.TYPE, "XObject");
            smask.setName(COSName.SUBTYPE, "Image");
            smask.setInt(COSName.WIDTH, 4);
            smask.setInt(COSName.HEIGHT, 4);
            smask.setName(COSName.COLORSPACE, "DeviceGray");
            smask.setInt(COSName.BITS_PER_COMPONENT, 8);
            try (OutputStream sos = smask.createOutputStream()) {
                byte[] allOpaque = new byte[16];
                Arrays.fill(allOpaque, (byte) 0xFF);
                sos.write(allOpaque);
            }
            image.getCOSObject().setItem(COSName.SMASK, smask);

            if (page.getResources() == null) {
                page.setResources(new PDResources());
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 72, 500, 40, 40);
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Opaque mask keeps text");
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
