package stirling.software.jpdfium.doc;

import stirling.software.jpdfium.PdfDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PdfFontVeraPdfTest {

    private static boolean verapdfAvailable = false;

    @BeforeAll
    static void checkVerapdf() {
        try {
            Process p = new ProcessBuilder("verapdf", "--version").start();
            verapdfAvailable = (p.waitFor() == 0);
        } catch (Exception e) {
            verapdfAvailable = false;
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

        for (String fixture : fixtures) {
            var url = getClass().getResource(fixture);
            if (url == null) continue;
            byte[] input = Files.readAllBytes(Path.of(url.toURI()));
            PdfFontEmbedder.Result result = PdfFontEmbedder.embedMissing(input);
            Path outPath = tempDir.resolve("embedded-" + Path.of(fixture).getFileName());
            Files.write(outPath, result.pdf());

            List<String> fontViolations1b = getFontViolations(outPath, "1b");
            List<String> fontViolations2b = getFontViolations(outPath, "2b");

            System.out.println("Fixture: " + fixture + " -> 1b font violations: " + fontViolations1b.size() + ", 2b font violations: " + fontViolations2b.size());
            for (String v : fontViolations2b) {
                System.out.println("  [2b font violation] " + v);
            }
            for (String v : fontViolations1b) {
                System.out.println("  [1b font violation] " + v);
            }
        }
    }

    @Test
    void testFontVeraPdfOnSafeDocsSample(@TempDir Path tempDir) throws Exception {
        assumeTrue(verapdfAvailable, "verapdf CLI is not available");
        assumeTrue(PdfFontEmbedder.isAvailable(), "native font embedder is not available");

        Path safedocsBase = Path.of("/private/var/folders/10/4hn925296yx_fqxp5g2z__v40000gn/T/safedocs/pdfs");
        assumeTrue(Files.isDirectory(safedocsBase), "SafeDocs base directory not found");

        List<String> folders = List.of("0000", "0400", "0800", "1200", "1600", "2000");
        List<Path> candidates = new ArrayList<>();
        for (String folder : folders) {
            Path dir = safedocsBase.resolve(folder);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> s = Files.list(dir)) {
                candidates.addAll(s.filter(p -> p.toString().endsWith(".pdf"))
                        .limit(8)
                        .toList());
            }
        }

        int tested = 0;
        int passed2b = 0;

        for (Path pdf : candidates) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(pdf);
            } catch (Exception e) {
                continue;
            }
            if (bytes.length == 0 || bytes.length > 5 * 1024 * 1024) continue;

            PdfFontEmbedder.Result result;
            try {
                result = PdfFontEmbedder.embedMissing(bytes);
            } catch (Exception e) {
                continue;
            }
            if (result.embedded().isEmpty()) continue;
            if (!result.unresolved().isEmpty()) {
                System.out.println("SafeDocs " + pdf.getFileName() + " partial embedding, unresolved=" + result.unresolved());
                continue;
            }

            tested++;
            Path embedOnlyPath = tempDir.resolve("embed-only-" + pdf.getFileName());
            Files.write(embedOnlyPath, result.pdf());
            List<String> violationsEmbedOnly = getFontViolations(embedOnlyPath, "2b");

            Path normPath = tempDir.resolve("norm-" + pdf.getFileName());
            Files.write(normPath, result.pdf());
            try (PdfDocument doc = PdfDocument.open(normPath)) {
                stirling.software.jpdfium.fonts.FontNormalizer.normalizeAll(doc);
                doc.save(normPath);
            } catch (Exception ignored) {
            }
            List<String> violationsNorm = getFontViolations(normPath, "2b");
            if (violationsNorm.isEmpty()) passed2b++;

            System.out.println("SafeDocs " + pdf.getFileName() + " embedded=" + result.embedded());
            System.out.println("  -> Embed-only 2b violations: " + violationsEmbedOnly);
            System.out.println("  -> Normalized 2b violations: " + violationsNorm);
            assertTrue(violationsNorm.isEmpty(), "Normalized PDF must have 0 font violations under PDF/A-2b: " + violationsNorm);
        }
        System.out.println("SafeDocs font test summary: tested " + tested + ", passed 2b font rules: " + passed2b);
        assertTrue(tested > 0, "must test at least one SafeDocs document");
        assertEquals(tested, passed2b, "All tested SafeDocs documents must pass PDF/A-2b font rules after normalization");
    }

    private static List<String> getFontViolations(Path pdf, String flavour) {
        List<String> violations = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(
                    "verapdf", "--flavour", flavour, "--format", "xml", "-v", pdf.toAbsolutePath().toString()
            ).redirectErrorStream(true).start();

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            try (InputStream in = p.getInputStream()) {
                Document doc = db.parse(in);
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
            }
            p.waitFor();
        } catch (Exception e) {
            violations.add("verapdf execution error: " + e.getMessage());
        }
        return violations;
    }

    private static boolean isFontRule(String clause, String desc, String obj, String flavour) {
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
