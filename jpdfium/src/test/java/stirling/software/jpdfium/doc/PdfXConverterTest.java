package stirling.software.jpdfium.doc;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
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
import stirling.software.jpdfium.exception.JPDFiumException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                .build();
        byte[] out = PdfXConverter.applyMetadata(input, options);
        String text = new String(out, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("PDF/X-3:2002"), "PDF/X-3 GTS version missing");
        assertTrue(text.contains("GTS_PDFXConformance"), "PDF/X-3 conformance missing");
        PdfXConverter.checkMetadata(out, options);
    }

    @Test
    void unsupportedStandardsFailBeforeTouchingTheDocument() throws Exception {
        JPDFiumException ex1A = assertThrows(JPDFiumException.class,
                () -> PdfXConverter.applyMetadata(new byte[] {1, 2, 3},
                        PdfXOptions.builder().standard(PdfXStandard.PDF_X_1A).build()));
        assertTrue(ex1A.getMessage().contains("not supported"), ex1A.getMessage());
        JPDFiumException ex6 = assertThrows(JPDFiumException.class,
                () -> PdfXConverter.applyMetadata(new byte[] {1, 2, 3},
                        PdfXOptions.builder().standard(PdfXStandard.PDF_X_6).build()));
        assertTrue(ex6.getMessage().contains("not supported"), ex6.getMessage());
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
            PdfXOptions options = PdfXOptions.builder()
                    .standard(PdfXStandard.PDF_X_4)
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
}
