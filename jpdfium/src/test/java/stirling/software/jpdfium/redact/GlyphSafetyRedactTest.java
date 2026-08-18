package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Glyph-safety pinning tests:
 *
 * <ul>
 *   <li>Non-uniform TJ kerning around a redacted word: the surviving runs
 *       keep byte-exact positions (segmentation at deviating pairs, not
 *       blanket per-char objects that would make the extractor synthesize
 *       spaces).</li>
 *   <li>HarfBuzz cluster safety: cutting inside a shaped ligature cluster
 *       (the 'fi' of "office") extends the redaction to the whole cluster -
 *       no orphaned half-ligature survives.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class GlyphSafetyRedactTest {

    private static final String NUM = "(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)";
    private static final Pattern CHAR_POS_RE = Pattern.compile(
            "\\{\"i\":(\\d+),\"u\":(\\d+)," +
            "\"ox\":" + NUM + ",\"oy\":" + NUM + "," +
            "\"l\":" + NUM + ",\"r\":" + NUM + "," +
            "\"b\":" + NUM + ",\"t\":" + NUM + "\\}"
    );

    record CharPos(int unicode, double ox, double oy) {}

    private static Path testPdf(String name) throws Exception {
        var url = GlyphSafetyRedactTest.class.getResource("/pdfs/redact/" + name);
        assertNotNull(url, name + " not found on classpath - run RedactTestPdfGenerator first");
        return Path.of(url.toURI());
    }

    private static List<CharPos> positions(PdfPage page) {
        var out = new ArrayList<CharPos>();
        Matcher m = CHAR_POS_RE.matcher(page.extractCharPositionsJson());
        while (m.find()) {
            out.add(new CharPos(Integer.parseInt(m.group(2)),
                    Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
        }
        return out;
    }

    private static String text(PdfPage page) {
        String json = page.extractTextJson();
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\"u\":(\\d+)").matcher(json);
        while (m.find()) {
            int u = Integer.parseInt(m.group(1));
            if (u >= 32 && u < 0x2000) sb.append((char) u);
        }
        return sb.toString();
    }

    private static void assertPreserved(List<CharPos> before, List<CharPos> after,
                                        String redactedChars, double tolerance, String ctx) {
        for (var e : before) {
            if (e.unicode() == 0x20 || e.unicode() == 0xA0) continue;
            if (redactedChars.indexOf(e.unicode()) >= 0) continue;
            boolean ok = after.stream().anyMatch(a ->
                    a.unicode() == e.unicode() &&
                    Math.abs(a.ox() - e.ox()) < tolerance &&
                    Math.abs(a.oy() - e.oy()) < tolerance);
            if (!ok) {
                var near = after.stream()
                        .filter(a -> a.unicode() == e.unicode())
                        .map(a -> String.format("(%.2f,%.2f)", a.ox(), a.oy()))
                        .limit(3).toList();
                fail(String.format("%s: '%c' moved from (%.2f,%.2f); now at %s", ctx,
                        e.unicode(), e.ox(), e.oy(), near));
            }
        }
    }

    @Test
    void nonUniformTjKerningSurvivorsKeepExactPositions() throws Exception {
        Path pdf = testPdf("redact-test-tj-deviation.pdf");
        List<CharPos> before;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            before = positions(page);
        }

        byte[] redacted;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            int n = page.redactWordsEx(new String[]{"SECRET"}, 0xFF000000, 0f,
                    false, false, true, false);
            assertTrue(n >= 1, "SECRET not matched");
            redacted = doc.saveBytes();
        }

        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            String t = text(page);
            assertFalse(t.contains("SECRET"), "redacted word survived: " + t);
            assertTrue(t.contains("KERN") && t.contains("EDGE") && t.contains("TAIL"),
                    "survivors lost: " + t);
            // Survivor chars keep their exact positions (TJ segmentation).
            assertPreserved(before, positions(page), "SECRET", 0.01, "tj-deviation");
        }
    }

    @Test
    void ligatureClusterCutExtendsToWholeCluster() throws Exception {
        Path pdf = testPdf("redact-test-ligature-cluster.pdf");
        byte[] redacted;
        try (var doc = PdfDocument.open(pdf);
             var page = doc.page(0)) {
            // Redact every 'f'. The second 'f' of "office" is half of the
            // shaped 'fi' ligature cluster - HarfBuzz cluster alignment must
            // pull its 'i' into the redaction instead of leaving an orphaned
            // half-ligature behind.
            int n = page.redactWordsEx(new String[]{"f"}, 0xFF000000, 0f,
                    false, false, true, false);
            assertTrue(n >= 1, "f not matched");
            redacted = doc.saveBytes();
        }

        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            String t = text(page);
            assertFalse(t.contains("f"), "f survived: " + t);
            assertTrue(t.contains("SECRET"), "SECRET lost (f redaction must not touch it): " + t);
            // The orphaned 'i' of the ligature cluster must be gone too.
            assertFalse(t.contains("office"), "office survived (cluster not extended): " + t);
            assertTrue(t.contains("co"), "coffee survivors lost: " + t);
        }
    }
}
