package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.fonts.FontNormalizer;
import stirling.software.jpdfium.panama.QpdfLib;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfFontVeraPdfTest {

    private static boolean verapdfAvailable = false;

    @BeforeAll
    static void checkVerapdf() {
        Process p = null;
        try {
            p = new ProcessBuilder("verapdf", "--version").start();
            verapdfAvailable = p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            verapdfAvailable = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            verapdfAvailable = false;
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }

    @Test
    void testFontVeraPdfOnGeneralSamples(@TempDir Path tempDir) throws Exception {
        assumeTrue(verapdfAvailable, "verapdf CLI is not available");
        assumeTrue(PdfFontEmbedder.isAvailable(), "native font embedder is not available");

        List<String> fixtures = List.of(
                "/pdfs/general/90ms_rksj_h_sample.pdf",
                "/pdfs/general/gb_euc_h_sample.pdf",
                "/pdfs/general/ksc_euc_h_sample.pdf",
                "/pdfs/general/basic-text.pdf"
        );

        List<String> allFailures = new ArrayList<>();
        for (String fixture : fixtures) {
            var url = getClass().getResource(fixture);
            assertNotNull(url, "Missing required test fixture: " + fixture);
            byte[] input = Files.readAllBytes(Path.of(url.toURI()));
            PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input);
            Path outPath = tempDir.resolve("embedded-" + Path.of(fixture).getFileName());
            Files.write(outPath, result.pdf());

            List<String> fontViolations2b = getFontViolations(outPath, "2b");
            if (!fontViolations2b.isEmpty()) {
                allFailures.add(fixture + " [2b]: " + fontViolations2b);
            }
            if (!fixture.endsWith("basic-text.pdf")) {
                // PDF/A-1b (clause 6.3.3.3) requires embedding predefined non-Identity CMaps
                // directly, whereas PDF/A-2b allows ISO 32000 predefined CJK CMaps; every
                // other 1b font rule (widths, CIDToGIDMap, CIDSet, flags) must still pass.
                List<String> unexpected1b = getFontViolations(outPath, "1b").stream()
                        .filter(v -> !v.startsWith("[6.3.3.3]"))
                        .toList();
                if (!unexpected1b.isEmpty()) {
                    allFailures.add(fixture + " [1b]: " + unexpected1b);
                }
            }
        }

        byte[] unembeddedLatin;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Hello PDF/A-1b and 2b font verification");
                cs.endText();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            unembeddedLatin = baos.toByteArray();
        }
        PdfFontEmbedder.Result latinEmbedded = PdfFontEmbedder.embedMissing(unembeddedLatin);
        Path latinOut = tempDir.resolve("embedded-latin-standard14.pdf");
        Files.write(latinOut, latinEmbedded.pdf());
        List<String> latin1b = getFontViolations(latinOut, "1b");
        List<String> latin2b = getFontViolations(latinOut, "2b");
        if (!latin1b.isEmpty()) {
            allFailures.add("latin-standard14 [1b]: " + latin1b);
        }
        if (!latin2b.isEmpty()) {
            allFailures.add("latin-standard14 [2b]: " + latin2b);
        }

        assertTrue(allFailures.isEmpty(), "PDF/A font violations: " + allFailures);
    }

    @Test
    @EnabledIfSystemProperty(named = "jpdfium.safedocs.dir", matches = ".+")
    void testFontVeraPdfOnSafeDocsSample(@TempDir Path tempDir) throws Exception {
        assumeTrue(verapdfAvailable, "verapdf CLI is not available");
        assumeTrue(PdfFontEmbedder.isAvailable(), "native font embedder is not available");

        Path configured = Path.of(System.getProperty("jpdfium.safedocs.dir"));
        Path safedocsBase = Files.isDirectory(configured.resolve("pdfs"))
                ? configured.resolve("pdfs")
                : configured;
        assumeTrue(Files.isDirectory(safedocsBase),
                "SafeDocs base directory not found: " + safedocsBase);

        List<Path> candidateFolders;
        try (Stream<Path> s = Files.list(safedocsBase)) {
            candidateFolders = s.filter(Files::isDirectory).sorted().toList();
        }
        List<Path> candidates = new ArrayList<>();
        for (Path dir : candidateFolders) {
            try (Stream<Path> s = Files.list(dir)) {
                candidates.addAll(s.filter(p -> p.toString().endsWith(".pdf"))
                        .limit(3)
                        .toList());
            }
        }

        int tested = 0;
        int passed2b = 0;

        for (Path pdf : candidates) {
            byte[] bytes = Files.readAllBytes(pdf);
            if (bytes.length == 0 || bytes.length > 5 * 1024 * 1024) continue;

            PdfFontEmbedder.Result result;
            try {
                result = PdfFontEmbedder.embedMissing(bytes);
            } catch (Exception e) {
                continue;
            }
            if (result.embedded().isEmpty() || !result.unresolved().isEmpty()) {
                continue;
            }

            tested++;
            Path normPath = tempDir.resolve("norm-" + pdf.getFileName());
            Files.write(normPath, result.pdf());
            try (PdfDocument doc = PdfDocument.open(normPath)) {
                FontNormalizer.normalizeAll(doc);
                doc.save(normPath);
            }
            List<String> violationsNorm = getFontViolations(normPath, "2b");
            if (violationsNorm.isEmpty()) passed2b++;

            assertTrue(violationsNorm.isEmpty(),
                    "Normalized PDF " + pdf.getFileName() + " must have 0 font violations under PDF/A-2b: " + violationsNorm);
        }
        assertTrue(tested > 0, "must test at least one SafeDocs document");
        assertEquals(tested, passed2b, "All tested SafeDocs documents must pass PDF/A-2b font rules after normalization");
    }

    @Test
    void testCompactedCidSetDifferencesAndPredefinedCmapsPassVeraPdf(@TempDir Path tempDir) throws Exception {
        assumeTrue(verapdfAvailable, "verapdf CLI is not available");
        assumeTrue(PdfFontEmbedder.isAvailable(), "native font embedder is not available");

        List<String> failures = new ArrayList<>();

        // 1. Compacted /CIDSet including CID 0 (.notdef) verified against PDF/A-1b, 2b, and 3b.
        byte[] cidInput;
        try (PDDocument doc = new PDDocument();
                InputStream fontStream = PDDocument.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            assertNotNull(fontStream);
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType0Font font = PDType0Font.load(doc, fontStream, false);
            COSDictionary descendant = (COSDictionary) ((COSArray) font.getCOSObject()
                    .getDictionaryObject(COSName.DESCENDANT_FONTS)).getObject(0);
            ((COSDictionary) descendant.getDictionaryObject(COSName.FONT_DESC))
                    .setItem(COSName.CID_SET, new PDStream(doc, new ByteArrayInputStream(new byte[] {(byte) 0xFF})).getCOSObject());
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("A");
                cs.endText();
            }
            doc.save(baos);
            cidInput = baos.toByteArray();
        }
        Path cidOut = tempDir.resolve("cidset-subset.pdf");
        var cidSub = QpdfLib.subsetFonts(cidInput);
        byte[] cidBytes = cidSub.pdf() != null ? cidSub.pdf() : cidInput;
        Files.write(cidOut, cidBytes);
        for (String flavour : List.of("1b", "2b", "3b")) {
            List<String> v = getFontViolations(cidOut, flavour);
            if (!v.isEmpty()) failures.add("subsetted CIDSet [" + flavour + "]: " + v);
        }
        Path cidNormOut = tempDir.resolve("cidset-normalized.pdf");
        try (PdfDocument doc = PdfDocument.open(cidInput)) {
            FontNormalizer.normalizeAll(doc);
            doc.save(cidNormOut);
        }
        for (String flavour : List.of("1b", "2b", "3b")) {
            List<String> v = getFontViolations(cidNormOut, flavour);
            if (!v.isEmpty()) failures.add("normalized CIDSet [" + flavour + "]: " + v);
        }

        // 2. Simple TrueType with /BaseEncoding /StandardEncoding (0x27 -> /quoteright) normalized for PDF/A-2b/3b.
        byte[] diffInput;
        try (PDDocument doc = new PDDocument();
                InputStream fontStream = PDDocument.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf");
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            assertNotNull(fontStream);
            PDPage page = new PDPage();
            doc.addPage(page);
            PDTrueTypeFont font = PDTrueTypeFont.load(doc, fontStream, WinAnsiEncoding.INSTANCE);
            COSDictionary enc = new COSDictionary();
            enc.setItem(COSName.TYPE, COSName.ENCODING);
            enc.setItem(COSName.BASE_ENCODING, COSName.STANDARD_ENCODING);
            font.getCOSObject().setItem(COSName.ENCODING, enc);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("a'b");
                cs.endText();
            }
            doc.save(baos);
            diffInput = baos.toByteArray();
        }
        Path diffOut = tempDir.resolve("base-encoding-normalized.pdf");
        try (PdfDocument doc = PdfDocument.open(diffInput)) {
            FontNormalizer.normalizeAll(doc);
            doc.save(diffOut);
        }
        for (String flavour : List.of("2b", "3b")) {
            List<String> v = getFontViolations(diffOut, flavour);
            if (!v.isEmpty()) failures.add("BaseEncoding 0x27 [" + flavour + "]: " + v);
        }

        // 3. Predefined RKSJ CMaps: 90ms-RKSJ-V and 90msp-RKSJ-H.
        var rksjUrl = getClass().getResource("/pdfs/general/90ms_rksj_h_sample.pdf");
        assertNotNull(rksjUrl);
        byte[] rksjBase = Files.readAllBytes(Path.of(rksjUrl.toURI()));
        for (String cmap : List.of("90ms-RKSJ-V", "90msp-RKSJ-H")) {
            byte[] variant;
            try (PDDocument doc = Loader.loadPDF(rksjBase);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                COSDictionary fonts = doc.getPage(0).getResources().getCOSObject().getCOSDictionary(COSName.FONT);
                for (COSName key : fonts.keySet()) {
                    COSDictionary f = fonts.getCOSDictionary(key);
                    if (COSName.TYPE0.equals(f.getDictionaryObject(COSName.SUBTYPE))) {
                        f.setItem(COSName.ENCODING, COSName.getPDFName(cmap));
                    }
                }
                doc.save(baos);
                variant = baos.toByteArray();
            }
            Path out = tempDir.resolve("embedded-" + cmap + ".pdf");
            Files.write(out, PdfFontEmbedder.embedMissing(variant).pdf());
            for (String flavour : List.of("2b", "3b")) {
                List<String> v = getFontViolations(out, flavour);
                if (!v.isEmpty()) failures.add(cmap + " [" + flavour + "]: " + v);
            }
        }

        // 4. Cyclic /Kids in /AcroForm during stripSignatures + font embedding.
        byte[] cyclicInput;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 720);
                cs.showText("Signature strip check");
                cs.endText();
            }
            COSDictionary sig = new COSDictionary();
            sig.setItem(COSName.FT, COSName.SIG);
            COSArray byteRange = new COSArray();
            for (int v : new int[] {0, 10, 20, 30}) byteRange.add(COSInteger.get(v));
            COSDictionary sigVal = new COSDictionary();
            sigVal.setItem(COSName.getPDFName("ByteRange"), byteRange);
            sig.setItem(COSName.V, sigVal);

            COSDictionary cyclicField = new COSDictionary();
            cyclicField.setDirect(false);
            cyclicField.setItem(COSName.FT, COSName.TX);
            COSArray kids = new COSArray();
            kids.add(cyclicField);
            cyclicField.setItem(COSName.KIDS, kids);

            COSArray fields = new COSArray();
            fields.add(cyclicField);
            fields.add(sig);
            COSDictionary acroForm = new COSDictionary();
            acroForm.setItem(COSName.FIELDS, fields);
            doc.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, acroForm);
            doc.save(baos);
            cyclicInput = baos.toByteArray();
        }
        Path cyclicOut = tempDir.resolve("cyclic-stripped.pdf");
        Files.write(cyclicOut, PdfFontEmbedder.embedMissing(cyclicInput, true).pdf());
        try (PDDocument doc = Loader.loadPDF(cyclicOut.toFile())) {
            COSDictionary acro = doc.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.ACRO_FORM);
            if (acro != null && acro.getCOSArray(COSName.FIELDS) != null) {
                for (COSBase item : acro.getCOSArray(COSName.FIELDS)) {
                    if (item instanceof COSDictionary d) {
                        assertTrue(!COSName.SIG.equals(d.getDictionaryObject(COSName.FT)),
                                "Signature field must be stripped even with cyclic /Kids");
                    }
                }
            }
        }
        List<String> cyclicV = getFontViolations(cyclicOut, "2b");
        if (!cyclicV.isEmpty()) failures.add("cyclicKids stripSignatures [2b]: " + cyclicV);

        assertTrue(failures.isEmpty(), "PDF/A font conformance failures: " + failures);
    }

    private static List<String> getFontViolations(Path pdf, String flavour) {
        List<String> violations = new ArrayList<>();
        Process p = null;
        try {
            p = new ProcessBuilder(
                    "verapdf", "--flavour", flavour, "--format", "xml", "-v", pdf.toAbsolutePath().toString()
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start();

            final Process proc = p;
            CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(() -> {
                try (InputStream in = proc.getInputStream()) {
                    return in.readAllBytes();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });

            byte[] xmlBytes = stdoutFuture.get(60, TimeUnit.SECONDS);
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                violations.add("verapdf timed out waiting for exit");
                return violations;
            }
            int exitCode = p.exitValue();
            if (exitCode != 0 && exitCode != 1) {
                violations.add("verapdf exited with error code " + exitCode);
                return violations;
            }

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder db = dbf.newDocumentBuilder();

            Document doc = db.parse(new ByteArrayInputStream(xmlBytes));
            NodeList exceptions = doc.getElementsByTagName("taskException");
            if (exceptions.getLength() > 0) {
                violations.add("verapdf reported taskException (" + exceptions.getLength() + ")");
                return violations;
            }

            NodeList summaries = doc.getElementsByTagName("batchSummary");
            if (summaries.getLength() == 0) {
                violations.add("verapdf XML report missing batchSummary");
                return violations;
            }
            Element summary = (Element) summaries.item(0);
            for (String attr : List.of("failedToParse", "encrypted", "outOfMemory", "veraExceptions")) {
                String val = summary.getAttribute(attr);
                if (!"0".equals(val)) {
                    violations.add("verapdf batchSummary " + attr + "=" + val);
                }
            }

            NodeList validationReports = doc.getElementsByTagName("validationReports");
            if (validationReports.getLength() == 0) {
                violations.add("verapdf XML report missing validationReports");
                return violations;
            }
            Element valReports = (Element) validationReports.item(0);
            String failedJobs = valReports.getAttribute("failedJobs");
            if (!"0".equals(failedJobs)) {
                violations.add("verapdf validationReports failedJobs=" + failedJobs);
            }

            NodeList reports = doc.getElementsByTagName("validationReport");
            if (reports.getLength() == 0) {
                violations.add("verapdf XML report missing validationReport");
                return violations;
            }
            for (int i = 0; i < reports.getLength(); i++) {
                Element report = (Element) reports.item(i);
                String jobEndStatus = report.getAttribute("jobEndStatus");
                if (!"normal".equals(jobEndStatus)) {
                    violations.add("verapdf validationReport jobEndStatus=" + jobEndStatus);
                }
            }

            NodeList rules = doc.getElementsByTagName("rule");
            for (int i = 0; i < rules.getLength(); i++) {
                Element rule = (Element) rules.item(i);
                String status = rule.getAttribute("status");
                if (!"failed".equalsIgnoreCase(status)) continue;
                String clause = rule.getAttribute("clause");
                String desc = "";
                NodeList descNodes = rule.getElementsByTagName("description");
                if (descNodes.getLength() > 0) {
                    desc = descNodes.item(0).getTextContent();
                }
                String obj = "";
                NodeList objNodes = rule.getElementsByTagName("object");
                if (objNodes.getLength() > 0) {
                    obj = objNodes.item(0).getTextContent();
                }
                if (isFontRule(clause, desc, obj, flavour)) {
                    violations.add("[" + clause + "] (" + obj + "): " + desc);
                }
            }
        } catch (Exception e) {
            violations.add("verapdf execution error: " + e.getMessage());
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
        return violations;
    }

    private static boolean isFontRule(String clause, String desc, String obj, String flavour) {
        if (clause.startsWith("6.2.11.8")) {
            return false;
        }
        if (flavour != null && flavour.startsWith("1")) {
            return clause.startsWith("6.3");
        }
        if (flavour != null && (flavour.startsWith("2") || flavour.startsWith("3"))) {
            return clause.startsWith("6.2.11");
        }
        if (clause.startsWith("6.2.2") || clause.startsWith("6.2.3") || clause.startsWith("6.2.4") || clause.startsWith("6.7")) {
            return false;
        }
        String o = obj.toLowerCase();
        if (o.contains("font") || o.contains("glyph") || o.contains("cmap")) return true;
        String d = desc.toLowerCase();
        return d.contains("font") || d.contains("glyph") || d.contains("cmap") || d.contains("cidset") || d.contains("charset");
    }
}
