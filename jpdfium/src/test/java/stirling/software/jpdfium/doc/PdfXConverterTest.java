package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFreeText;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;
import stirling.software.jpdfium.model.PdfVersion;
import stirling.software.jpdfium.model.Rect;
import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Bootstrap contract for {@link PdfXConverter}; not a PDF/X conformance suite. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class PdfXConverterTest {

    @Test
    void pdfx4WritesIntentAndMetadataAndPreservesPages() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");

        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        int pages;
        try (PdfDocument doc = PdfDocument.open(input)) {
            pages = doc.pageCount();
        }

        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .outputIntentProfile(icc)
                .conditionIdentifier("FOGRA39")
                .info("Coated FOGRA39")
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, options);
        String text = new String(out, StandardCharsets.ISO_8859_1);

        assertTrue(text.contains("/OutputIntents"), "output intent missing");
        assertTrue(text.contains("/GTS_PDFX"), "PDF/X output intent subtype missing");
        assertTrue(text.contains("GTS_PDFXVersion"), "XMP version missing");
        assertTrue(text.contains("PDF/X-4"), "XMP version is not PDF/X-4");
        assertTrue(text.contains("GTS_PDFXConformance"), "XMP conformance missing");
        assertTrue(text.contains("/Metadata"), "XMP metadata stream missing");
        assertTrue(text.startsWith("%PDF-1.6"),
                "PDF/X-4 output must declare a PDF 1.6 or later base version");
        PdfXConverter.checkMetadata(out, options);

        try (PDDocument pdfbox = Loader.loadPDF(out)) {
            PDMetadata metadata = pdfbox.getDocumentCatalog().getMetadata();
            assertTrue(metadata != null, "PDFBox must see a catalog metadata stream");
            String xmp = new String(metadata.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("GTS_PDFXVersion"), "XMP version missing in PDFBox");
            assertTrue(xmp.contains("PDF/X-4"), "XMP version is not PDF/X-4 in PDFBox");
            List<PDOutputIntent> intents =
                    pdfbox.getDocumentCatalog().getOutputIntents();
            assertTrue(intents != null && intents.size() == 1,
                    "PDFBox must see exactly one output intent");
            assertEquals("FOGRA39", intents.get(0).getOutputConditionIdentifier());
            assertEquals("Coated FOGRA39", intents.get(0).getInfo());
            assertNotNull(intents.get(0).getDestOutputIntent());
            assertEquals(4, intents.get(0).getDestOutputIntent().getInt(COSName.N, -1));
        }

        try (PdfDocument reopened = PdfDocument.open(out)) {
            assertEquals(pages, reopened.pageCount(), "page count changed");
        }
    }

    @Test
    void pdfxPreservesPageLevelXmpStreams() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");

        String xmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta>"
                + "<?xpacket end=\"w\"?>";
        byte[] input;
        try (PDDocument pdfbox = new PDDocument()) {
            PDPage page = new PDPage();
            pdfbox.addPage(page);
            PDStream stream = new PDStream(pdfbox,
                    new ByteArrayInputStream(xmp.getBytes(StandardCharsets.UTF_8)));
            stream.getCOSObject().setItem(COSName.TYPE, COSName.METADATA);
            page.getCOSObject().setItem(COSName.METADATA, stream.getCOSObject());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            pdfbox.save(bytes);
            input = bytes.toByteArray();
        }

        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .outputIntentProfile(icc)
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, options);
        assertTrue(xmlMetadataStreams(out) >= 2,
                "catalog and page XMP streams must survive the in-memory rewrite");

        Path inputFile = Files.createTempFile("jpdfium-pdfx-in-", ".pdf");
        Path outputFile = Files.createTempFile("jpdfium-pdfx-out-", ".pdf");
        try {
            Files.write(inputFile, input);
            PdfXConverter.applyMetadata(inputFile, outputFile, options);
            assertTrue(xmlMetadataStreams(Files.readAllBytes(outputFile)) >= 2,
                    "catalog and page XMP streams must survive the file rewrite");
        } finally {
            Files.deleteIfExists(inputFile);
            Files.deleteIfExists(outputFile);
        }
    }

    private static int xmlMetadataStreams(byte[] pdf) throws Exception {
        int count = 0;
        try (PDDocument pdfbox = Loader.loadPDF(pdf)) {
            for (COSObject object : pdfbox.getDocument().getObjectsByType(COSName.METADATA)) {
                if (!(object.getObject() instanceof COSStream stream)) continue;
                count++;
                DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(stream.createInputStream());
            }
        }
        return count;
    }

    @Test
    void pdfx4RaisesPdfVersionForPdf2Source() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");

        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        System.arraycopy("%PDF-2.0".getBytes(StandardCharsets.US_ASCII), 0, input, 0, 8);
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .outputIntentProfile(icc)
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, options);
        assertTrue(new String(out, 0, 8, StandardCharsets.US_ASCII).startsWith("%PDF-1.6"),
                "PDF/X-4 must not inherit a downgraded PDF 1.0 header");
    }

    @Test
    void bundledCompactCmykProfileWorksWithoutSystemProfile() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");

        byte[] icc = PdfIccProfiles.compactCmyk();
        assertEquals(4, ICC_Profile.getInstance(icc).getNumComponents(),
                "bundled output intent must be a CMYK profile");
        assertTrue(icc.length < 16 * 1024,
                "the bundled fallback must stay small; was " + icc.length + " bytes");

        byte[] input = Files.readAllBytes(resource("/pdfs/general/basic-text.pdf"));
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .useCompactCmyk()
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, options);
        PdfXConverter.checkMetadata(out, options);

        try (PDDocument pdfbox = Loader.loadPDF(out)) {
            assertEquals(PdfIccProfiles.COMPACT_CMYK_CONDITION,
                    pdfbox.getDocumentCatalog().getOutputIntents().get(0)
                            .getOutputConditionIdentifier());
        }
    }

    @Test
    void pdfxAddsMissingPdfxidVersionFromPdfxOnlyMetadata() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        String sourceXmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF "
                + "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" "
                + "xmlns:pdfx=\"http://ns.adobe.com/pdfx/1.3/\" "
                + "pdfx:GTS_PDFXVersion=\"PDF/X-3:2002\" "
                + "pdfx:GTS_PDFXConformance=\"PDF/X-3:2002\"/>"
                + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        byte[] input;
        try (PDDocument pdfbox = new PDDocument()) {
            PDPage page = new PDPage();
            pdfbox.addPage(page);
            PDStream metadata = new PDStream(pdfbox,
                    new ByteArrayInputStream(sourceXmp.getBytes(StandardCharsets.UTF_8)));
            metadata.getCOSObject().setItem(COSName.TYPE, COSName.METADATA);
            pdfbox.getDocumentCatalog().getCOSObject().setItem(COSName.METADATA,
                    metadata.getCOSObject());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            pdfbox.save(bytes);
            input = bytes.toByteArray();
        }

        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .useCompactCmyk()
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, options);
        try (PDDocument pdfbox = Loader.loadPDF(out)) {
            String xmp = new String(pdfbox.getDocumentCatalog().getMetadata().toByteArray(),
                    StandardCharsets.UTF_8);
            assertTrue(xmp.contains("pdfxid:GTS_PDFXVersion=\"PDF/X-4\""),
                    "PDF/X-4 metadata must contain the pdfxid version");
            assertTrue(xmp.contains("pdfx:GTS_PDFXVersion=\"PDF/X-4\""),
                    "PDF/X-4 metadata must update the Adobe version");
        }
    }

    @Test
    void pdfxMetadataCanBeReapplied() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] input = Files.readAllBytes(resource("/pdfs/general/basic-text.pdf"));

        PdfXOptions first = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .useCompactCmyk()
                .build();
        byte[] once = PdfXConverter.applyMetadata(input, first);

        PdfXOptions second = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_3)
                .useCompactCmyk()
                .build();
        byte[] twice = PdfXConverter.applyMetadata(once, second);

        try (PDDocument pdfbox = Loader.loadPDF(twice)) {
            PDMetadata metadata = pdfbox.getDocumentCatalog().getMetadata();
            assertNotNull(metadata, "reapplied PDF/X output has no XMP metadata");
            String xmp = new String(metadata.toByteArray(), StandardCharsets.UTF_8);
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xmp.getBytes(StandardCharsets.UTF_8)));
            assertEquals(2, occurrences(xmp, "GTS_PDFXVersion"),
                    "XMP version must be updated in place, not duplicated");
            assertTrue(xmp.contains("PDF/X-3:2002"),
                    "the second pass must write the new PDF/X version");
            assertTrue(!xmp.contains("PDF/X-4"), "the old PDF/X version must be replaced");
        }
    }

    @Test
    void pdfx3UsesItsOwnGtsNames() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");

        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_3)
                .outputIntentProfile(icc)
                .conditionIdentifier("FOGRA39")
                .title("PDF/X-3 Document")
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, options);
        String text = new String(out, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("PDF/X-3:2002"), "PDF/X-3 GTS version missing");
        PdfXConverter.checkMetadata(out, options);
    }

    @Test
    void pdfDateToXmpValidatesCalendarDatesAndTimeZones() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");

        assertXmpModifyDate("D:20240229120000Z", "2024-02-29T12:00:00Z");
        assertXmpModifyDate("D:20240301000000+05'30'", "2024-03-01T00:00:00+05:30");
        assertXmpModifyDate("D:20240229120000-08'00'", "2024-02-29T12:00:00-08:00");
        assertXmpModifyDate("D:20230229120000Z", null);
        assertXmpModifyDate("D:20260231120000Z", null);
        assertXmpModifyDate("D:20240229120000+25'00'", null);
        assertXmpModifyDate("D:20240229120000+05'60'", null);
    }

    private static void assertXmpModifyDate(String pdfDate, String expectedXmp)
            throws Exception {
        byte[] input;
        try (PDDocument pdfbox = new PDDocument()) {
            pdfbox.addPage(new PDPage());
            pdfbox.getDocumentInformation().getCOSObject()
                    .setString(COSName.getPDFName("ModDate"), pdfDate);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdfbox.save(out);
            input = out.toByteArray();
        }

        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .useCompactCmyk()
                .build();
        byte[] output = PdfXConverter.applyMetadata(input, options);
        try (PDDocument pdfbox = Loader.loadPDF(output)) {
            PDMetadata metadata = pdfbox.getDocumentCatalog().getMetadata();
            assertNotNull(metadata, "XMP metadata missing for " + pdfDate);
            String xmp = new String(metadata.toByteArray(), StandardCharsets.UTF_8);
            if (expectedXmp == null) {
                String invalidIso = pdfDate.substring(2, 6) + "-" + pdfDate.substring(6, 8)
                        + "-" + pdfDate.substring(8, 10) + "T" + pdfDate.substring(10, 12)
                        + ":" + pdfDate.substring(12, 14);
                assertFalse(xmp.contains(invalidIso),
                        "invalid date must not be written to XMP: " + pdfDate);
            } else {
                assertTrue(xmp.contains(expectedXmp),
                        "missing XMP ModifyDate " + expectedXmp + " for " + pdfDate);
            }
        }
    }

    @Test
    void versionConverterWritesRequestedVersion() throws Exception {
        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        try (var doc = PdfDocument.open(input)) {
            for (PdfVersion v :
                    PdfVersion.values()) {
                byte[] out = PdfVersionConverter.saveWithVersionToBytes(doc.rawHandle(), v);
                assertTrue(new String(out, 0, 8, StandardCharsets.ISO_8859_1)
                                .startsWith("%PDF-" + v),
                        "version converter wrote the wrong header for " + v);
            }
            Path file = Files.createTempFile("jpdfium-version-", ".pdf");
            try {
                PdfVersionConverter.saveWithVersion(
                        doc.rawHandle(), PdfVersion.V2_0, file);
                byte[] head = Files.readAllBytes(file);
                assertTrue(new String(head, 0, 8, StandardCharsets.ISO_8859_1)
                                .startsWith("%PDF-2.0"),
                        "file version converter wrote a non-2.0 header");
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void everyStandardWritesItsGtsNamesAndBaseVersion() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        for (PdfXStandard standard : PdfXStandard.values()) {
            PdfXOptions options = PdfXOptions.builder()
                    .standard(standard)
                    .useCompactCmyk()
                    .title("Every Standard Test")
                    .build();
            byte[] out = PdfXConverter.applyMetadata(input, options);
            String text = new String(out, StandardCharsets.ISO_8859_1);
            assertTrue(text.startsWith("%PDF-" + standard.pdfVersion().substring(4)),
                    standard + " base version missing: " + text.substring(0, 16));
            assertTrue(text.contains("GTS_PDFXVersion"), standard + " GTS_PDFXVersion attribute missing");
            assertTrue(text.contains(standard.gtsVersion()), standard + " GTS version missing");
            if (standard.gtsConformance() != null) {
                assertTrue(text.contains("GTS_PDFXConformance"),
                        standard + " GTS_PDFXConformance attribute missing");
                assertTrue(text.contains(standard.gtsConformance()),
                        standard + " GTS conformance missing");
            } else {
                assertFalse(text.contains("GTS_PDFXConformance"),
                        standard + " must not write GTS_PDFXConformance");
            }
            PdfXConverter.checkMetadata(out, options);
        }
    }

    @Test
    void missingProfileIsRejected() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .conditionIdentifier("FOGRA39")
                .build();
        assertThrows(JPDFiumException.class, () -> PdfXConverter.applyMetadata(input, options));
    }

    @Test
    void fileToFilePdfxBootstrapWorks() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");

        Path input = resource("/pdfs/general/minimal.pdf");
        Path output = Files.createTempFile("jpdfium-pdfx-", ".pdf");
        try {
            assertTrue(PdfOutputIntent.applyPdfxMetadata(input, output, icc,
                    PdfOutputIntent.Spec.pdfx("FOGRA39", "Coated FOGRA39"),
                    PdfXStandard.PDF_X_4.gtsVersion(),
                    PdfXStandard.PDF_X_4.gtsConformance()),
                    "file-to-file PDF/X bootstrap failed");
            assertTrue(Files.size(output) > 0, "PDF/X output is empty");
            try (PDDocument pdfbox = Loader.loadPDF(output.toFile())) {
                assertTrue(pdfbox.getDocumentCatalog().getMetadata() != null,
                        "file-to-file output has no XMP metadata");
                assertTrue(pdfbox.getDocumentCatalog().getOutputIntents() != null
                                && !pdfbox.getDocumentCatalog().getOutputIntents().isEmpty(),
                        "file-to-file output has no output intent");
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void fileToFilePipelineStreamsThroughPdfx() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        Path input = resource("/pdfs/general/basic-text.pdf");
        Path output = Files.createTempFile("jpdfium-pdfx-stream-", ".pdf");
        try {
            for (PdfXStandard standard : PdfXStandard.values()) {
                PdfXOptions options = PdfXOptions.builder()
                        .standard(standard)
                        .useCompactCmyk()
                        .build();
                PdfXConverter.applyMetadata(input, output, options);
                byte[] bytes = Files.readAllBytes(output);
                PdfXConverter.checkMetadata(bytes, options);
                try (PDDocument pdfbox = Loader.loadPDF(output.toFile())) {
                    assertNotNull(pdfbox.getDocumentCatalog().getMetadata(),
                            "streamed output has no XMP metadata");
                    assertTrue(pdfbox.getDocumentCatalog().getOutputIntents() != null
                                    && !pdfbox.getDocumentCatalog().getOutputIntents().isEmpty(),
                            "streamed output has no output intent");
                    assertEquals(null, pdfbox.getDocumentCatalog().getVersion(),
                            standard + " left a catalog /Version override");
                    assertTrue(pdfbox.getVersion() == Float.parseFloat(
                                    standard.pdfVersion().substring(4)),
                            standard + " header version is wrong");
                }
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void ghentFixturesAlsoConvertToPdfX4() throws Exception {
        String env = System.getenv("GHENT_DIR");
        assumeTrue(env != null && !env.isBlank(), "GHENT_DIR not set");
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");

        Path dir = Path.of(env);
        Path nested = dir.resolve("Ghent_PDF_Output_Suite_V50_Testpages");
        if (Files.isDirectory(nested)) {
            // GHENT_DIR may point at either the zip root or the extracted dir.
            dir = nested;
        }
        List<Path> fixtures;
        try (Stream<Path> paths = Files.list(dir)) {
            fixtures = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".pdf"))
                    .sorted()
                    .toList();
        }
        assumeTrue(!fixtures.isEmpty(), "no Ghent PDFs found under " + dir);

        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .outputIntentProfile(icc)
                .conditionIdentifier("FOGRA39")
                .info("Coated FOGRA39")
                .build();
        for (Path input : fixtures) {
            byte[] source = Files.readAllBytes(input);
            int pages;
            try (PdfDocument doc = PdfDocument.open(source)) {
                pages = doc.pageCount();
            }
            byte[] out = PdfXConverter.applyMetadata(source, options);
            PdfXConverter.checkMetadata(out, options);
            try (PdfDocument reopened = PdfDocument.open(out)) {
                assertEquals(pages, reopened.pageCount(),
                        input.getFileName() + " page count changed");
            }
        }
    }

    @Test
    void constantAlphaIsVisibleToTheStrictProbeButNotThePageFlag() throws Exception {
        byte[] input = pageWithConstantAlpha();
        try (PdfDocument doc = PdfDocument.open(input);
                PdfPage page = doc.page(0)) {
            assertFalse(PdfPageObjects.summarize(page.rawHandleForRead()).hasTransparency(),
                    "PDFium's page-level flag does not report /ca 0.5; this is the regression");
            assertTrue(PdfPageObjects.hasTransparencyStrict(page.rawHandleForRead()),
                    "strict probe must see constant alpha in the object graphics state");
            JPDFiumException error = assertThrows(JPDFiumException.class,
                    () -> PdfXConverter.requireNoTransparency(doc, PdfXStandard.PDF_X_3));
            assertTrue(error.getMessage().contains("forbids transparency"), error.getMessage());
            assertTrue(error.getMessage().contains("page 0"), error.getMessage());
            assertFalse(doc.isContentModified(),
                    "a read-only transparency probe must not mark the document modified");
        }
    }

    @Test
    void pdfx3RejectsConstantAlphaEvenWhenThePageFlagIsClear() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] input = pageWithConstantAlpha();

        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_3)
                .useCompactCmyk()
                .build();
        JPDFiumException error = assertThrows(JPDFiumException.class,
                () -> PdfXConverter.applyMetadata(input, options));
        assertTrue(error.getMessage().contains("forbids transparency"), error.getMessage());
        assertTrue(error.getMessage().contains("page 0"), error.getMessage());
    }

    @Test
    void transparencyProbeDoesNotMarkTheDocumentModified() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] input = pageWithConstantAlpha();
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_3)
                .useCompactCmyk()
                .build();
        try (PdfDocument doc = PdfDocument.open(input)) {
            assertThrows(JPDFiumException.class,
                    () -> PdfXConverter.applyMetadata(doc, options));
            assertFalse(doc.isContentModified(),
                    "a read-only transparency preflight must not mark the document modified");
        }
    }

    @Test
    void optionsWriteTitleTrappedAndDates() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");
        byte[] input = nonEmbeddedTextPdf();
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .outputIntentProfile(icc)
                .conditionIdentifier("FOGRA39")
                .info("Coated FOGRA39")
                .title("PDF/X title")
                .requireEmbeddedFonts(false)
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, options);
        try (PDDocument pdfbox = Loader.loadPDF(out)) {
            assertEquals("PDF/X title", pdfbox.getDocumentInformation().getTitle());
            assertEquals("False", pdfbox.getDocumentInformation().getTrapped());
            assertTrue(pdfbox.getDocumentInformation().getCOSObject().getItem(COSName.getPDFName("Trapped")) instanceof COSName,
                    "/Trapped in Info dictionary must be a PDF Name object");
            assertNotNull(pdfbox.getDocumentInformation().getCreationDate());
            assertNotNull(pdfbox.getDocumentInformation().getModificationDate());
        }
    }

    @Test
    void pdfx1aAndPdfx3WriteGtsPdfxVersionToInfoDictionary() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");
        byte[] input = nonEmbeddedTextPdf();
        for (PdfXStandard std : List.of(PdfXStandard.PDF_X_1A, PdfXStandard.PDF_X_3)) {
            PdfXOptions options = PdfXOptions.builder()
                    .standard(std)
                    .outputIntentProfile(icc)
                    .conditionIdentifier("FOGRA39")
                    .info("Coated FOGRA39")
                    .title("PDF/X title")
                    .requireEmbeddedFonts(false)
                    .build();
            byte[] out = PdfXConverter.applyMetadata(input, options);
            try (PDDocument pdfbox = Loader.loadPDF(out)) {
                assertEquals(std.gtsVersion(),
                        pdfbox.getDocumentInformation().getCustomMetadataValue("GTS_PDFXVersion"),
                        "Expected GTS_PDFXVersion in Info dictionary for " + std);
                if (std == PdfXStandard.PDF_X_1A) {
                    assertEquals(std.gtsConformance(),
                            pdfbox.getDocumentInformation().getCustomMetadataValue("GTS_PDFXConformance"),
                            "Expected GTS_PDFXConformance in Info dictionary for " + std);
                }
            }
        }
    }

    @Test
    void requireEmbeddedFontsRejectsAllOverloadsAndLeavesDestinationUntouched() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] icc = findCmykProfile().orElse(null);
        assumeTrue(icc != null, "no CMYK ICC profile available on this machine");
        byte[] input = nonEmbeddedTextPdf();
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .outputIntentProfile(icc)
                .conditionIdentifier("FOGRA39")
                .info("Coated FOGRA39")
                .requireEmbeddedFonts(true)
                .embedMissingFonts(false)
                .build();
        JPDFiumException rej1 = assertThrows(JPDFiumException.class,
                () -> PdfXConverter.applyMetadata(input, options));
        assertTrue(rej1.getMessage().contains("embedded fonts"), rej1.getMessage());
        try (PdfDocument doc = PdfDocument.open(input)) {
            JPDFiumException rej2 = assertThrows(JPDFiumException.class,
                    () -> PdfXConverter.applyMetadata(doc, options));
            assertTrue(rej2.getMessage().contains("embedded fonts"), rej2.getMessage());
        }
        Path source = Files.createTempFile("jpdfium-font-source-", ".pdf");
        Path output = Files.createTempFile("jpdfium-font-output-", ".pdf");
        byte[] sentinel = "untouched".getBytes(StandardCharsets.US_ASCII);
        try {
            Files.write(source, input);
            Files.write(output, sentinel);
            JPDFiumException rej3 = assertThrows(JPDFiumException.class,
                    () -> PdfXConverter.applyMetadata(source, output, options));
            assertTrue(rej3.getMessage().contains("embedded fonts"), rej3.getMessage());
            assertArrayEquals(sentinel, Files.readAllBytes(output),
                    "a rejected conversion must not replace the destination");
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(output);
        }
    }

    private static byte[] nonEmbeddedTextPdf() throws Exception {
        try (PDDocument pdfbox = new PDDocument()) {
            PDPage page = new PDPage();
            pdfbox.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdfbox, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Helvetica");
                content.endText();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            pdfbox.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static byte[] pageWithConstantAlpha() throws Exception {
        try (PDDocument pdfbox = new PDDocument()) {
            PDPage page = new PDPage();
            pdfbox.addPage(page);
            PDResources resources = new PDResources();
            PDExtendedGraphicsState state = new PDExtendedGraphicsState();
            state.setNonStrokingAlphaConstant(0.5f);
            resources.put(COSName.getPDFName("gs1"), state);
            try (PDPageContentStream content = new PDPageContentStream(
                    pdfbox, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                content.setGraphicsStateParameters(state);
                content.addRect(100, 100, 100, 100);
                content.fill();
            }
            page.setResources(resources);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            pdfbox.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static Path resource(String name) throws Exception {
        return Path.of(PdfXConverterTest.class.getResource(name).toURI());
    }

    /** First real CMYK profile on this machine, if any. */
    private static Optional<byte[]> findCmykProfile() {
        String explicit = System.getProperty("jpdfium.test.cmykIcc");
        if (explicit != null && !explicit.isBlank()) {
            try {
                byte[] bytes = Files.readAllBytes(Path.of(explicit));
                if (ICC_Profile.getInstance(bytes).getNumComponents() != 4) {
                    throw new JPDFiumException("configured CMYK profile is not 4-component: "
                            + explicit);
                }
                return Optional.of(bytes);
            } catch (IOException e) {
                throw new JPDFiumException("cannot read configured CMYK profile: " + explicit, e);
            }
        }
        String[] candidates = {
            "/System/Library/ColorSync/Profiles/Generic CMYK Profile.icc",
            "/usr/share/color/icc/ghostscript/default_cmyk.icc",
            "/usr/share/color/icc/ISOcoated_v2_300_eci.icc",
            "/usr/share/color/icc/colord/ISOcoated_v2_300_eci.icc",
        };
        for (String candidate : candidates) {
            try {
                Path path = Path.of(candidate);
                if (!Files.isReadable(path)) continue;
                byte[] bytes = Files.readAllBytes(path);
                if (ICC_Profile.getInstance(bytes).getNumComponents() == 4) {
                    return Optional.of(bytes);
                }
            } catch (Exception _) {
                // try the next candidate
            }
        }
        return Optional.of(PdfIccProfiles.compactCmyk());
    }

    @Test
    void titleIsRequiredForPdfx1aAndPdfx3() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        for (PdfXStandard std : List.of(PdfXStandard.PDF_X_1A, PdfXStandard.PDF_X_3)) {
            PdfXOptions options = PdfXOptions.builder()
                    .standard(std)
                    .useCompactCmyk()
                    .title(null)
                    .build();
            JPDFiumException ex = assertThrows(JPDFiumException.class,
                    () -> PdfXConverter.applyMetadata(input, options));
            assertTrue(ex.getMessage().contains("Title is required"), ex.getMessage());
        }
    }

    @Test
    void trappedTrueIsPreserved() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        byte[] withTrappedTrue;
        try (PDDocument doc = Loader.loadPDF(input)) {
            doc.getDocumentInformation().setTrapped("True");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            withTrappedTrue = baos.toByteArray();
        }
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .useCompactCmyk()
                .title("Preserve Trapped")
                .build();
        byte[] out = PdfXConverter.applyMetadata(withTrappedTrue, options);
        try (PDDocument doc = Loader.loadPDF(out)) {
            assertEquals("True", doc.getDocumentInformation().getTrapped());
        }
    }

    @Test
    void catalogVersionStrippedOnByteAndDocumentPaths() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        byte[] withCatalogVersion;
        try (PDDocument doc = Loader.loadPDF(input)) {
            doc.getDocumentCatalog().setVersion("1.7");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            withCatalogVersion = baos.toByteArray();
        }
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .useCompactCmyk()
                .title("Strip Version")
                .build();
        // Test byte[] path
        byte[] outBytes = PdfXConverter.applyMetadata(withCatalogVersion, options);
        try (PDDocument doc = Loader.loadPDF(outBytes)) {
            assertNull(doc.getDocumentCatalog().getVersion(),
                    "byte path left catalog /Version override");
        }
        // Test PdfDocument path
        try (PdfDocument srcDoc = PdfDocument.open(withCatalogVersion);
                PdfDocument resDoc = PdfXConverter.applyMetadata(srcDoc, options)) {
            byte[] outDocBytes = resDoc.saveBytes();
            try (PDDocument doc = Loader.loadPDF(outDocBytes)) {
                assertNull(doc.getDocumentCatalog().getVersion(),
                        "PdfDocument path left catalog /Version override");
            }
        }
    }

    @Test
    void trimBoxAndArtBoxRules() throws Exception {
        assumeTrue(PdfXConverter.isSupported(), "PDF/X native pipeline unavailable");
        byte[] input = Files.readAllBytes(resource("/pdfs/general/minimal.pdf"));
        PdfXOptions options = PdfXOptions.builder()
                .standard(PdfXStandard.PDF_X_4)
                .useCompactCmyk()
                .title("Box test")
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, options);
        try (PdfDocument doc = PdfDocument.open(out);
                PdfPage page = doc.page(0)) {
            assertTrue(page.getTrimBox().isPresent(), "TrimBox should be set when absent");
            assertFalse(page.getArtBox().isPresent(), "ArtBox should not be set");
        }

        byte[] withArtBox;
        try (PDDocument pdfbox = Loader.loadPDF(input)) {
            PDPage p = pdfbox.getPage(0);
            p.setArtBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(10, 10, 100, 100));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdfbox.save(baos);
            withArtBox = baos.toByteArray();
        }
        byte[] outArt = PdfXConverter.applyMetadata(withArtBox, options);
        try (PdfDocument doc = PdfDocument.open(outArt);
                PdfPage page = doc.page(0)) {
            assertTrue(page.getArtBox().isPresent(), "ArtBox must be preserved");
            assertTrue(page.getTrimBox().isEmpty(), "TrimBox must NOT be set when ArtBox is present");
        }
    }

    @Test
    void strictCropRemovesBoundaryCrossingWords() throws Exception {
        byte[] input;
        try (PDDocument pdfbox = new PDDocument()) {
            PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(612, 792));
            pdfbox.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(pdfbox, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(150, 150);
                cs.showText("INSIDE");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(280, 150);
                cs.showText("CROSSINGBOUNDARY");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(150, 450);
                cs.showText("OUTSIDE");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdfbox.save(baos);
            input = baos.toByteArray();
        }
        try (PdfDocument doc = PdfDocument.open(input)) {
            stirling.software.jpdfium.transform.PdfPageGeometry.cropAndRemoveContentStrict(
                    doc, 0, new stirling.software.jpdfium.model.Rect(100, 100, 200, 200));
            var text = stirling.software.jpdfium.text.PdfTextExtractor.extractPage(doc, 0).plainText();
            assertTrue(text.contains("INSIDE"), "fully inside text should be kept");
            assertFalse(text.contains("OUTSIDE"), "outside text should be removed");
            assertFalse(text.contains("CROSSING"), "boundary-crossing word should be removed");
            assertFalse(text.contains("BOUNDARY"), "boundary-crossing word should be removed");
        }
    }

    @Test
    void strictCropAuditFailureArmsNativeIncompleteStateAndBlocksFullSave() throws Exception {
        byte[] input;
        try (PDDocument pdfbox = new PDDocument()) {
            PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(612, 792));
            pdfbox.addPage(page);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdfbox.save(baos);
            input = baos.toByteArray();
        }
        try (PdfDocument doc = PdfDocument.open(input)) {
            try (PdfPage page = doc.page(0)) {
                page.markRedactionIncomplete();
            }
            assertThrows(stirling.software.jpdfium.exception.RedactIncompleteException.class,
                    doc::saveBytes,
                    "saveBytes() must fail closed with RedactIncompleteException when incomplete state is armed");
            try (PdfPage page = doc.page(0)) {
                page.clearRedactionIncomplete();
            }
            assertTrue(doc.saveBytes().length > 0,
                    "a passing strict-crop audit must clear the failure marker");
        }
    }

    @Test
    void rotatedPageWithInheritedMediaBoxKeepsUnrotatedCoordinates() throws Exception {
        byte[] pdf;
        try (PDDocument pdfbox = new PDDocument()) {
            PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(612, 792));
            page.setRotation(90);
            pdfbox.addPage(page);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdfbox.save(baos);
            pdf = baos.toByteArray();
        }
        try (PdfDocument doc = PdfDocument.open(pdf)) {
            try (PdfPage page = doc.page(0)) {
                assertEquals(1, page.rotation());
                Rect mb = page.getMediaBox();
                assertEquals(612, Math.round(mb.width()));
                assertEquals(792, Math.round(mb.height()));
                assertEquals(0, Math.round(mb.x()));
                assertEquals(0, Math.round(mb.y()));
            }
        }
    }

    @Test
    void fontAuditorDetectsNonEmbeddedFontInAnnotation() throws Exception {
        assumeTrue(PdfFontAuditor.isSupported(), "font auditor requires full PDFium natives");
        byte[] pdfWithAnnot;
        try (PDDocument pdfbox = new PDDocument()) {
            PDPage page = new PDPage();
            pdfbox.addPage(page);

            PDAnnotationFreeText annot = new PDAnnotationFreeText();
            annot.setRectangle(new org.apache.pdfbox.pdmodel.common.PDRectangle(50, 50, 200, 50));
            annot.setContents("AnnotText");

            PDAppearanceDictionary appearance = new PDAppearanceDictionary();
            PDAppearanceStream appStream = new PDAppearanceStream(pdfbox);
            appStream.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(0, 0, 200, 50));
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            appStream.setResources(res);
            try (var os = appStream.getContentStream().createOutputStream()) {
                String ops = "q BT /F1 12 Tf 10 10 Td (AnnotText) Tj ET Q";
                os.write(ops.getBytes(StandardCharsets.US_ASCII));
            }
            appearance.setNormalAppearance(appStream);
            annot.setAppearance(appearance);
            page.getAnnotations().add(annot);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdfbox.save(baos);
            pdfWithAnnot = baos.toByteArray();
        }

        try (PdfDocument doc = PdfDocument.open(pdfWithAnnot)) {
            var audit = PdfFontAuditor.audit(doc);
            assertTrue(audit.fonts().stream().anyMatch(f -> f.baseName().contains("Helvetica")),
                    "Font auditor should detect Helvetica in Annotation");
            assertTrue(PdfFontAuditor.hasNonEmbeddedFonts(doc),
                    "hasNonEmbeddedFonts should be true for non-embedded font in Annotation");
        }
    }

    @Test
    void fontAuditorDetectsNonEmbeddedFontInFormXObject() throws Exception {
        assumeTrue(PdfFontAuditor.isSupported(), "font auditor requires full PDFium natives");
        byte[] pdfWithForm;
        try (PDDocument pdfbox = new PDDocument()) {
            PDPage page = new PDPage();
            pdfbox.addPage(page);

            PDFormXObject form = new PDFormXObject(pdfbox);
            form.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(0, 0, 100, 100));
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            form.setResources(res);
            try (var os = form.getContentStream().createOutputStream()) {
                String ops = "q BT /F1 12 Tf 10 10 Td (FormText) Tj ET Q";
                os.write(ops.getBytes(StandardCharsets.US_ASCII));
            }
            if (page.getResources() == null) page.setResources(new PDResources());
            page.getResources().add(form, "Fm1");

            try (PDPageContentStream pageStream = new PDPageContentStream(pdfbox, page)) {
                pageStream.drawForm(form);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            pdfbox.save(baos);
            pdfWithForm = baos.toByteArray();
        }
        try (PdfDocument doc = PdfDocument.open(pdfWithForm)) {
            var audit = PdfFontAuditor.audit(doc);
            assertTrue(audit.fonts().stream().anyMatch(f -> f.baseName().contains("Helvetica")),
                    "Font auditor should detect Helvetica in Form XObject");
            assertTrue(PdfFontAuditor.hasNonEmbeddedFonts(doc),
                    "hasNonEmbeddedFonts should be true for non-embedded font in Form XObject");
        }
    }

    @Test
    void fontAuditorRejectsFormNestingDeeperThan32() throws Exception {
        assumeTrue(PdfFontAuditor.isSupported(), "font auditor requires full PDFium natives");

        try (PdfDocument doc = PdfDocument.open(nestedForms(32))) {
            assertFalse(PdfFontAuditor.audit(doc).fonts().isEmpty(),
                    "32 nested forms must still be auditable");
        }

        try (PdfDocument doc = PdfDocument.open(nestedForms(33))) {
            JPDFiumException failure = assertThrows(JPDFiumException.class,
                    () -> PdfFontAuditor.audit(doc),
                    "33 nested forms must fail the audit rather than return a partial report");
            assertTrue(failure.getMessage().contains("exceeds 32"), failure.getMessage());
        }
    }

    private static byte[] nestedForms(int depth) throws IOException {
        try (PDDocument pdfbox = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(612, 792));
            pdfbox.addPage(page);

            PDFormXObject nested = null;
            for (int level = depth - 1; level >= 0; level--) {
                PDFormXObject form = new PDFormXObject(pdfbox);
                form.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(0, 0, 100, 100));
                PDResources resources = new PDResources();
                String childName = null;
                if (nested == null) {
                    resources.put(COSName.getPDFName("F1"),
                            new PDType1Font(Standard14Fonts.FontName.HELVETICA));
                } else {
                    childName = resources.add(nested).getName();
                }
                form.setResources(resources);
                try (var os = form.getContentStream().createOutputStream()) {
                    String ops = nested == null
                            ? "q BT /F1 12 Tf 10 10 Td (DeepText) Tj ET Q"
                            : "q /" + childName + " Do Q";
                    os.write(ops.getBytes(StandardCharsets.US_ASCII));
                }
                nested = form;
            }

            if (page.getResources() == null) page.setResources(new PDResources());
            page.getResources().add(nested, "FmOuter");
            try (PDPageContentStream contents = new PDPageContentStream(pdfbox, page)) {
                contents.drawForm(nested);
            }
            pdfbox.save(out);
            return out.toByteArray();
        }
    }
}
