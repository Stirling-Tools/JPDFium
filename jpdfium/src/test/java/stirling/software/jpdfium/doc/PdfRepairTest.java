package stirling.software.jpdfium.doc;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import stirling.software.jpdfium.panama.NativeLoader;
import stirling.software.jpdfium.panama.NativeRuntime;
import stirling.software.jpdfium.panama.QpdfLib;
import stirling.software.jpdfium.panama.RepairLib;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfRepairTest {

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static byte[] MINIMAL_PDF;

    @BeforeAll
    static void loadNative() {
        NativeLoader.ensureLoaded();
        MINIMAL_PDF = ("""
                %PDF-1.4
                1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
                2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
                3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj
                xref
                0 4
                0000000000 65535 f\s
                0000000009 00000 n\s
                0000000058 00000 n\s
                0000000115 00000 n\s
                trailer<</Size 4/Root 1 0 R>>
                startxref
                190
                %%EOF""")
                .getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void builderRequiresInput() {
        assertThrows(IllegalStateException.class,
                () -> PdfRepair.builder().build());
    }

    @Test
    void builderAcceptsByteArray() {
        var repair = PdfRepair.builder().input(MINIMAL_PDF).build();
        assertNotNull(repair);
    }

    @Test
    void builderAllEnablesEverything() {
        var repair = PdfRepair.builder().input(MINIMAL_PDF).all().build();
        assertNotNull(repair);
    }

    @Test
    void builderIndividualOptions() {
        var repair = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .forceVersion14(true)
                .normalizeXref(true)
                .fixStartxref(true)
                .transcodeBrotli(true)
                .usePdfioFallback(true)
                .validateIcc(true)
                .validateJpx(true)
                .build();
        assertNotNull(repair);
    }

    @Test
    void inspectReturnsNonNullJson() {
        String diag = PdfRepair.inspect(MINIMAL_PDF);
        assertNotNull(diag);
        assertFalse(diag.isBlank());
    }

    @Test
    void inspectHandlesEmptyInput() {
        assertEquals("{\"error\":\"empty input\"}", PdfRepair.inspect(EMPTY_BYTES));
    }

    @Test
    void inspectHandlesNullInput() {
        assertEquals("{\"error\":\"empty input\"}", PdfRepair.inspect((byte[]) null));
    }

    @Test
    void executeReturnsResult() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeWithAllReturnsResult() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .all()
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeProducesDiagnostics() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .build()
                .execute();
        assertNotNull(result.diagnosticJson());
    }

    @Test
    void executeWithBrotliTranscoding() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .transcodeBrotli(true)
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeWithPdfioFallback() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .usePdfioFallback(true)
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeWithIccValidation() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .validateIcc(true)
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeWithJpxValidation() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .validateJpx(true)
                .build()
                .execute();
        assertNotNull(result);
        assertNotNull(result.status());
    }

    @Test
    void executeWithForceVersion14() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .forceVersion14(true)
                .build()
                .execute();
        assertNotNull(result);
    }

    @Test
    void executeWithNormalizeXref() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .normalizeXref(true)
                .build()
                .execute();
        assertNotNull(result);
    }

    @Test
    void executeWithFixStartxref() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .fixStartxref(true)
                .build()
                .execute();
        assertNotNull(result);
    }

    @Test
    void executeResultIsUsable() {
        RepairResult result = PdfRepair.builder()
                .input(MINIMAL_PDF)
                .all()
                .build()
                .execute();
        assertTrue(result.isUsable());
        assertNotNull(result.repairedPdf());
        assertTrue(result.repairedPdf().length > 0);
    }

    /**
     * A repaired PDF must be complete on disk before it is read back.
     *
     * <p>Pdfio writes the cross-reference table and trailer during its close
     * step. If the bytes are read while that writer is still open, the result is
     * a truncated PDF whose xref is missing - which an independent parser
     * rejects. This test therefore reopens the repair output with PDFBox, which
     * shares no code with the repair path.
     */
    @Test
    void repairedOutputIsReopenableByAnIndependentParser() throws Exception {
        byte[] damaged = RepairCorpus.zeroXref(RepairCorpus.validMultiPage());
        RepairResult result = PdfRepair.builder()
                .input(damaged)
                .normalizeXref(true)
                .fixStartxref(true)
                .usePdfioFallback(true)
                .build()
                .execute();

        assertTrue(result.isUsable(), "repair must succeed: " + result.status());
        assertNotNull(result.repairedPdf());
        assertTrue(result.repairedPdf().length > 0, "repair must not return empty bytes");

        try (PDDocument reopened = Loader.loadPDF(result.repairedPdf())) {
            assertTrue(reopened.getNumberOfPages() > 0,
                    "repaired output must expose its pages (finalised xref + trailer)");
        }
    }

    /**
     * Same guarantee on the PDFio path itself, invoked directly.
     *
     * <p>Going through {@link PdfRepair} is not enough: an earlier stage usually
     * succeeds first, so the PDFio branch never runs. This pins the contract at
     * the entry point that does the read-back.
     */
    @Test
    void pdfioRepairOutputIsReopenableByAnIndependentParser() throws Exception {
        // No published build links PDFio (CMakeLists.txt never defines
        // JPDFIUM_HAS_PDFIO, so the bridge compiles the stub that reports
        // unavailable). Skip rather than assert a branch that cannot run here;
        // -Djpdfium.pdfio=true enables it once a PDFio-enabled build exists.
        Assumptions.assumeTrue(Boolean.getBoolean("jpdfium.pdfio"),
                "PDFio is not linked into this native build");

        // A zeroed xref table forces PDFio to rebuild from a full object scan,
        // which is the path its page-copy salvage depends on.
        byte[] damaged = RepairCorpus.zeroXref(RepairCorpus.validMultiPage());
        RepairResult result = RepairLib.pdfioRepair(damaged);

        assertTrue(result.isUsable(),
                "pdfio repair must succeed: " + result.status() + " " + result.diagnosticJson());
        assertNotNull(result.repairedPdf());
        assertTrue(result.repairedPdf().length > 0, "pdfio repair must not return empty bytes");

        // An independent parser rejects a file whose xref/trailer was never
        // written, which is exactly what reading before the writer's close step
        // would produce.
        try (PDDocument reopened = Loader.loadPDF(result.repairedPdf())) {
            assertTrue(reopened.getNumberOfPages() > 0,
                    "pdfio output must be finalised (xref + trailer) before read-back");
        }
    }

    /**
     * Repair writes untrusted input to temporary files. They must not survive the
     * call, on any path - success, failure, or exception.
     */
    @Test
    void repairLeavesNoTemporaryFilesBehind() throws Exception {
        Set<String> before = repairTempFiles();

        PdfRepair.builder().input(RepairCorpus.zeroXref(RepairCorpus.validMultiPage()))
                .fixStartxref(true).usePdfioFallback(true).build().execute();
        // Also exercise a hopeless input so the failure path is covered too.
        PdfRepair.builder().input("not a pdf at all".getBytes(StandardCharsets.UTF_8))
                .fixStartxref(true).usePdfioFallback(true).build().execute();

        Set<String> after = repairTempFiles();
        Set<String> leaked = new TreeSet<>(after);
        leaked.removeAll(before);
        assertTrue(leaked.isEmpty(), "repair left temporary files behind: " + leaked);
    }

    /** Snapshot of any jpdfium repair temp files currently present. */
    private static Set<String> repairTempFiles() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (var s = Files.list(tmp)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("jpdfium_pdfio_"))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    void repairsSynthesizedDefectedFixtures() throws IOException {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        String[] fixtures = {
            "/pdfs/repair/truncated_no_eof.pdf",
            "/pdfs/repair/corrupt_xref_offsets.pdf",
            "/pdfs/repair/missing_startxref.pdf",
            "/pdfs/repair/corrupt_stream_length.pdf",
            "/pdfs/repair/multiple_eof.pdf",
            "/pdfs/repair/unclosed_stream_obj.pdf",
            "/pdfs/repair/stale_linearized.pdf",
            "/pdfs/repair/noncontiguous_xref_index.pdf",
            "/pdfs/repair/xref_stream_mismatch.pdf",
            "/pdfs/repair/flate_crlf_corrupt.pdf"
        };
        for (String fixture : fixtures) {
            byte[] input;
            try (var is = PdfRepairTest.class.getResourceAsStream(fixture)) {
                assertNotNull(is, "Missing fixture: " + fixture);
                input = is.readAllBytes();
            }
            RepairResult result = PdfRepair.builder().input(input).all().build().execute();
            assertTrue(result.isUsable(), "Expected repairable fixture: " + fixture);
            try (var doc = stirling.software.jpdfium.PdfDocument.open(result.repairedPdf())) {
                assertEquals(1, doc.pageCount(), "Expected 1 page after repair for: " + fixture);
            }
            try (PDDocument reopened = Loader.loadPDF(result.repairedPdf())) {
                assertEquals(1, reopened.getNumberOfPages(),
                        "Expected PDFBox to reopen 1 page for: " + fixture);
                String text = new PDFTextStripper().getText(reopened).trim();
                assertTrue(text.contains("Repair Test"),
                        "Expected repaired text 'Repair Test' in " + fixture + ", got: " + text);
            }
        }
    }

    @Test
    void repairsBrokenPagesTreePreservingInheritedAttributes() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        String obj1 = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n";
        String obj2 = "2 0 obj\n<< /Type /Pages /Kids [] /Count 0 /MediaBox [0 0 500 700] >>\nendobj\n";
        String obj3 = "3 0 obj\n<< /Type /Page /Parent 2 0 R /Contents 5 0 R /Resources << /Font << /F1 4 0 R >> >> >>\nendobj\n";
        String obj4 = "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n";
        String stream = "BT /F1 14 Tf 40 600 Td (BrokenPagesRecovered) Tj ET\n";
        String obj5 = "5 0 obj\n<< /Length " + stream.length() + " >>\nstream\n" + stream + "endstream\nendobj\n";

        StringBuilder sb = new StringBuilder(1024);
        sb.append("%PDF-1.4\n");
        String[] objs = {obj1, obj2, obj3, obj4, obj5};
        int[] offsets = new int[objs.length];
        for (int i = 0; i < objs.length; i++) {
            offsets[i] = sb.length();
            sb.append(objs[i]);
        }
        int xrefStart = sb.length();
        sb.append("xref\n0 6\n0000000000 65535 f \n");
        for (int off : offsets) {
            sb.append(String.format(Locale.ROOT, "%010d 00000 n ", off)).append('\n');
        }
        sb.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n")
                .append(xrefStart).append("\n%%EOF\n");

        byte[] damaged = sb.toString().getBytes(StandardCharsets.US_ASCII);
        RepairResult result = PdfRepair.builder().input(damaged).all().build().execute();
        assertTrue(result.isUsable(), "Expected broken /Pages tree to be repaired: " + result.status());
        try (PDDocument reopened = Loader.loadPDF(result.repairedPdf())) {
            assertEquals(1, reopened.getNumberOfPages());
            assertEquals(500f, reopened.getPage(0).getMediaBox().getWidth(), 0.5f);
            assertTrue(new PDFTextStripper().getText(reopened).contains("BrokenPagesRecovered"));
        }
    }

    @Test
    void repairsTruncatedIncrementalUpdateWithLargeTail() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        byte[] base = RepairCorpus.validMultiPage();
        StringBuilder tail = new StringBuilder(6144);
        for (int id = 200; id < 340; id++) {
            tail.append(id).append(" 0 obj\n<< /Type /ExtGState /CA 1.0 >>\nendobj\n");
        }
        tail.append("340 0 obj\n<< /Length 120 >>\nstream\nBT (PARTIAL_UPDATE");
        byte[] tailBytes = tail.toString().getBytes(StandardCharsets.US_ASCII);
        byte[] damaged = new byte[base.length + tailBytes.length];
        System.arraycopy(base, 0, damaged, 0, base.length);
        System.arraycopy(tailBytes, 0, damaged, base.length, tailBytes.length);

        RepairResult result = PdfRepair.builder().input(damaged).all().build().execute();
        assertTrue(result.isUsable(), "Expected truncated incremental update to repair: " + result.status());
        try (PDDocument reopened = Loader.loadPDF(result.repairedPdf())) {
            assertTrue(reopened.getNumberOfPages() >= 2,
                    "Expected base document pages preserved after truncated incremental rebuild");
        }
    }

    // The Java FLAG_* constants are passed to the native JPDFIUM_REPAIR_* bits
    // verbatim. These two tests pin the mapping per flag: each exercises a
    // single builder option whose effect is observable in the written output,
    // so any future permutation of the bit assignments fails here.
    @Test
    void forceVersion14FlagAloneForcesA14Header() {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        byte[] pdf17 = MINIMAL_PDF.clone();
        pdf17[5] = '1';
        pdf17[6] = '.';
        pdf17[7] = '7';

        RepairResult untouched = PdfRepair.builder().input(pdf17).build().execute();
        assertTrue(untouched.isUsable(), "baseline repair failed: " + untouched.status());
        assertTrue(header(untouched.repairedPdf()).startsWith("%PDF-1.7"),
                "baseline: without forceVersion14() qpdf keeps the 1.7 header");

        RepairResult forced =
                PdfRepair.builder().input(pdf17).forceVersion14(true).build().execute();
        assertTrue(forced.isUsable(), "forced repair failed: " + forced.status());
        assertTrue(header(forced.repairedPdf()).startsWith("%PDF-1.4"),
                "forceVersion14() alone must reach JPDFIUM_REPAIR_FORCE_V14 in the bridge");
    }

    @Test
    void normalizeXrefFlagAloneRewritesObjectStreamsToClassicXref() {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        Assumptions.assumeTrue(QpdfLib.isSupported(), "Requires qpdf native");
        byte[] withObjStm = QpdfLib.optimize(MINIMAL_PDF, 0, -1,
                2 /* qpdf_o_generate */, 1 /* qpdf_s_preserve */, 0 /* qpdf_dl_none */);
        assertNotNull(withObjStm, "qpdf object-stream generation failed");
        assertTrue(containsAscii(withObjStm, "/ObjStm"), "expected generated object streams");

        RepairResult normalized =
                PdfRepair.builder().input(withObjStm).normalizeXref(true).build().execute();
        assertTrue(normalized.isUsable(), "normalized repair failed: " + normalized.status());
        byte[] out = normalized.repairedPdf();
        assertFalse(containsAscii(out, "/ObjStm"),
                "normalizeXref() alone must reach JPDFIUM_REPAIR_NORMALIZE_XREF in the bridge");
        assertFalse(containsAscii(out, "/Type/XRef"),
                "xref stream must be replaced by a classic xref table");
        assertTrue(containsAscii(out, " 65535 f "), "classic xref free-list head expected");
    }

    private static String header(byte[] pdf) {
        int end = Math.min(pdf.length, 9);
        return new String(pdf, 0, end, StandardCharsets.ISO_8859_1);
    }

    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] target = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= haystack.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (haystack[i + j] != target[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    @Test
    void executeFailsClosedOnUnboundedDictionaryNesting() {
        // Regression: inputs nesting more dictionaries than the repair tracking
        // window used to throw ArrayIndexOutOfBoundsException out of execute().
        // They must fail closed instead.
        byte[] deepNesting = ("%PDF-1.4\n" + "<<".repeat(100) + "/Im1 5 0 R")
                .getBytes(StandardCharsets.ISO_8859_1);
        RepairResult result = PdfRepair.builder().input(deepNesting).all().build().execute();
        assertEquals(RepairResult.Status.FAILED, result.status(),
                "hostile nesting must fail closed, never throw");
    }

    @Test
    void executeFailsClosedOnHeaderlessDeepPageScan() {
        // Regression: a headerless, deeply nested /Type /Page drives the page-tree
        // prescan through the same bounded tracking stack; it must fail closed.
        byte[] headerless = ("<<".repeat(70) + "/Type /Page")
                .getBytes(StandardCharsets.ISO_8859_1);
        RepairResult result = PdfRepair.builder().input(headerless).all().build().execute();
        assertEquals(RepairResult.Status.FAILED, result.status(),
                "garbage must fail closed, never throw");
    }

    @Test
    void executeHonorsObjectNumberCapDuringSalvage() {
        // Regression: an object number above the 9,000,000 cap used to overflow
        // Integer.parseInt inside salvageTruncatedPdf and kill execute() with a
        // NumberFormatException. The cap must hold here like everywhere else.
        String hostile = "%PDF-1.4\n"
                + "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
                + "2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n"
                + "2147483648 0 obj\n<<>>\nendobj\n"
                + "trailer\n<< /Root 1 0 R >>\n%%EOF";
        RepairResult result = PdfRepair.builder()
                .input(hostile.getBytes(StandardCharsets.ISO_8859_1))
                .all()
                .build()
                .execute();
        assertNotNull(result, "execute must return a result, never throw");
        if (result.isUsable()) {
            assertFalse(new String(result.repairedPdf(), StandardCharsets.ISO_8859_1)
                            .contains("/Size 2147483649"),
                    "salvage must cap /Size at the accepted object-number bound");
        }
    }

    @Test
    void repairsGluedEndobjAfterAStream() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        // Regression: after closing a stream the position guard used to
        // re-enter stream state at the "stream" suffix of "endstream" and
        // over-guarded the rest of the file, so normalizeGluedEndobj never
        // split a glued "endobjN 0 obj" that follows a stream. qpdf attaches
        // the glued object to the previous one and the page is lost.
        String formData = "BT ET\n";
        String contentData = "BT /F1 12 Tf 8 8 Td (Marker) Tj ET\n";

        StringBuilder sb = new StringBuilder();
        sb.append("%PDF-1.4\n");
        int o1 = sb.length();
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        int o2 = sb.length();
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        int o4 = sb.length();
        sb.append("4 0 obj\n<< /Type /XObject /Subtype /Form /BBox [0 0 8 8] /Length ")
                .append(formData.length()).append(" >>\nstream\n").append(formData)
                .append("endstream\nendobj\n");
        int o5 = sb.length();
        sb.append("5 0 obj\n<< /Length ").append(contentData.length())
                // Glued "endobj3 0 obj": the page object header follows the
                // content stream's endobj without a separator.
                .append(" >>\nstream\n").append(contentData).append("endstream\nendobj3 0 obj\n");
        int o3 = sb.length();
        sb.append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] "
                + "/Resources << /XObject << /Im1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n");
        int xref = sb.length();
        sb.append(String.format(Locale.ROOT,
                "xref\n0 6\n0000000000 65535 f \n%010d 00000 n \n%010d 00000 n \n"
                        + "%010d 00000 n \n%010d 00000 n \n%010d 00000 n \n"
                        + "trailer\n<< /Root 1 0 R /Size 6 >>\nstartxref\n%d\n%%%%EOF\n",
                o1, o2, o3, o4, o5, xref));

        byte[] pdf = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        RepairResult result = PdfRepair.builder().input(pdf).all().build().execute();
        assertTrue(result.isUsable(), "repair failed: " + result.status());
        try (PDDocument reopened = Loader.loadPDF(result.repairedPdf())) {
            assertEquals(1, reopened.getNumberOfPages(),
                    "the glued page object must be recovered after a stream");
            assertTrue(new PDFTextStripper().getText(reopened).contains("Marker"),
                    "page content must survive the glued-endobj split");
        }
    }

    @Test
    void streamDataContainingEndstreamKeywordStaysUntouched() throws Exception {
        Assumptions.assumeTrue(NativeRuntime.isFull(), "Requires full native repair bridge");
        // Regression: stream data containing the bytes "endstream" ended the
        // position guard early; the remaining data was treated as object
        // syntax and the resource-key dedup edited it, shifting the stream
        // under its own /Length so the page object landed inside the extended
        // stream and the page was lost. The guard must follow the declared
        // /Length exactly.
        StringBuilder refs = new StringBuilder();
        for (int i = 0; i < 120; i++) refs.append("/Im1 4 0 R\n");
        // The trailing space (not an EOL) is what defeats the old scanner: no
        // re-entry at the "stream" suffix, so the dedup targets stay unguarded.
        String contentData = "q\nendstream " + refs
                + "BT /F1 12 Tf 8 8 Td (Marker) Tj ET\nQ\n";
        String formData = "BT ET\n";

        StringBuilder sb = new StringBuilder();
        sb.append("%PDF-1.4\n");
        int o1 = sb.length();
        sb.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        int o2 = sb.length();
        sb.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        int o4 = sb.length();
        sb.append("4 0 obj\n<< /Type /XObject /Subtype /Form /BBox [0 0 8 8] /Length ")
                .append(formData.length()).append(" >>\nstream\n").append(formData)
                .append("endstream\nendobj\n");
        int o5 = sb.length();
        sb.append("5 0 obj\n<< /Length ").append(contentData.length())
                .append(" >>\nstream\n").append(contentData).append("endstream\nendobj\n");
        int o3 = sb.length();
        sb.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] "
                + "/Resources << /XObject << /Im1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n");
        int xref = sb.length();
        sb.append(String.format(Locale.ROOT,
                "xref\n0 6\n0000000000 65535 f \n%010d 00000 n \n%010d 00000 n \n"
                        + "%010d 00000 n \n%010d 00000 n \n%010d 00000 n \n"
                        + "trailer\n<< /Root 1 0 R /Size 6 >>\nstartxref\n%d\n%%%%EOF\n",
                o1, o2, o3, o4, o5, xref));

        byte[] pdf = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        RepairResult result = PdfRepair.builder().input(pdf).all().build().execute();
        assertTrue(result.isUsable(), "repair failed: " + result.status());
        try (PDDocument reopened = Loader.loadPDF(result.repairedPdf())) {
            assertEquals(1, reopened.getNumberOfPages(),
                    "the page must survive the repair");
            assertTrue(new PDFTextStripper().getText(reopened).contains("Marker"),
                    "stream data must not be edited by the repair-token normalizers");
        }
    }
}
