package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import stirling.software.jpdfium.PdfDocument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanitize-stage pinning tests: every save of a redacted document runs a
 * mandatory qpdf pass. These tests assert BYTE-LEVEL absence of the redacted
 * value in the output - metadata, XMP, outline titles, form-field values and
 * annotations must not carry a copy of the redacted text, and the sanitize
 * report must account for what was scrubbed.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class SanitizeStageRedactTest {

    private static Path testPdf(String name) throws Exception {
        var url = SanitizeStageRedactTest.class.getResource("/pdfs/redact/" + name);
        assertNotNull(url, name + " not found on classpath - run RedactTestPdfGenerator first");
        return Path.of(url.toURI());
    }

    /** Concatenate every FlateDecode stream payload (byte-level grep target). */
    private static byte[] inflatedView(byte[] pdf) {
        var out = new ByteArrayOutputStream();
        int idx = 0;
        while (true) {
            int s = indexOf(pdf, "stream".getBytes(StandardCharsets.US_ASCII), idx);
            if (s < 0) break;
            s += 6;
            if (s < pdf.length && (pdf[s] == '\r')) s++;
            if (s < pdf.length && (pdf[s] == '\n')) s++;
            int e = indexOf(pdf, "endstream".getBytes(StandardCharsets.US_ASCII), s);
            if (e < 0) break;
            try (var in = new InflaterInputStream(new ByteArrayInputStream(pdf, s, e - s))) {
                out.write(in.readAllBytes());
            } catch (Exception ignored) {
                // non-flate stream: include raw bytes too (they cannot hide
                // ASCII secrets from a byte grep either way)
                out.write(pdf, s, e - s);
            }
            idx = e + 9;
        }
        return out.toByteArray();
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++)
                if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** Decode the "u":NN char JSON into a plain string. */
    private static String decodeChars(String json) {
        StringBuilder sb = new StringBuilder();
        var m = java.util.regex.Pattern.compile("\"u\":(\\d+)").matcher(json);
        while (m.find()) {
            int u = Integer.parseInt(m.group(1));
            if (u >= 32 && u < 0x2000) sb.append((char) u);
        }
        return sb.toString();
    }

    private static long countOccurrences(byte[] hay, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.US_ASCII);
        long count = 0;
        for (int i = 0; i + n.length <= hay.length; i++) {
            boolean ok = true;
            for (int j = 0; j < n.length; j++) {
                if (hay[i + j] != n[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                count++;
                i += n.length - 1;
            }
        }
        return count;
    }

    @Test
    void redactedValueIsByteLevelAbsentFromSavedOutput() throws Exception {
        Path pdf = testPdf("redact-test-sanitize-remnants.pdf");
        byte[] redacted;
        String report;
        try (var doc = PdfDocument.open(pdf)) {
            doc.setSanitizeOnSave(true);
            try (var page = doc.page(0)) {
                int n = page.redactWordsEx(new String[]{"SECRET"}, 0xFF000000, 0f,
                        false, false, true, false);
                assertTrue(n >= 1, "SECRET must match on the page");
                // NOTE: no flatten - the raw /Text annotation (whose /Contents
                // echoes the secret) must be REMOVED by the sanitize stage.
            }
            redacted = doc.saveBytes();
            report = doc.sanitizeReport();
        }

        // The sanitize stage must have run and reported.
        assertTrue(report != null && !report.isEmpty(), "sanitize report missing");
        assertFalse(report.contains("\"error\""), "sanitize failed: " + report);

        // Byte-level: no copy of the redacted value anywhere in the output -
        // not in content streams, not in metadata, not in outlines/annots.
        byte[] view = inflatedView(redacted);
        long count = countOccurrences(view, "SECRET");
        if (count > 0) {
            StringBuilder ctx = new StringBuilder("SECRET contexts: ");
            int i = 0;
            while ((i = indexOf(view, "SECRET".getBytes(StandardCharsets.US_ASCII), i)) >= 0
                    && ctx.length() < 400) {
                int from = Math.max(0, i - 30);
                ctx.append(new String(view, from, Math.min(60, view.length - from),
                        StandardCharsets.US_ASCII).replaceAll("\\s+", " ")).append(" | ");
                i += 6;
            }
            assertEquals(0, count, "redacted value still present (report=" + report + "): " + ctx);
        }

        // /Info dictionary is gone.
        assertFalse(indexOf(redacted, "/Info".getBytes(StandardCharsets.US_ASCII), 0) >= 0 &&
                        indexOf(redacted, "/Info ".getBytes(StandardCharsets.US_ASCII), 0) >= 0,
                "/Info dictionary present in redacted output");

        // Structure tree + XFA must be gone.
        assertEquals(-1, indexOf(redacted, "/StructTreeRoot".getBytes(StandardCharsets.US_ASCII), 0));
        assertEquals(-1, indexOf(redacted, "/XFA".getBytes(StandardCharsets.US_ASCII), 0));

        // Report contents: annotation removed, field blanked, outline
        // blanked, info removed, XMP scrubbed, embedded font subset + its
        // ToUnicode filtered.
        assertTrue(report.contains("\"annots_removed\":1"), "annotation not removed: " + report);
        assertTrue(report.contains("\"fields_blanked\":1"), "field value not blanked: " + report);
        assertTrue(report.contains("\"outlines_blanked\":1"), "outline not blanked: " + report);
        assertTrue(report.contains("\"info_removed\":true"), "info not removed: " + report);
        assertTrue(report.contains("\"xmp_scrubbed\":true"), "XMP not scrubbed: " + report);
        assertTrue(report.contains("\"tounicode_filtered\":1"),
                "embedded font ToUnicode not filtered: " + report);
        assertTrue(report.contains("\"fonts_subset\":1"),
                "embedded font program not re-subset: " + report);

        // The surviving document still opens and renders; survivors remain.
        try (var doc = PdfDocument.open(redacted);
             var page = doc.page(0)) {
            String text = decodeChars(page.extractTextJson());
            assertTrue(text.contains("VISIBLE") && text.contains("TEXT"),
                    "surviving text lost: " + text);
            assertFalse(text.contains("SECRET"), "redacted text extractable: " + text);
            assertTrue(text.contains("EMBEDDED") && text.contains("LINE"),
                    "embedded-font survivors lost: " + text);
        }
    }

    @Test
    void unredactedSaveRunsNoSanitizeStage() throws Exception {
        Path pdf = testPdf("redact-test-sanitize-remnants.pdf");
        try (var doc = PdfDocument.open(pdf)) {
            byte[] saved = doc.saveBytes();
            assertEquals("", doc.sanitizeReport(), "sanitize report must be empty before redaction");
            // Unredacted save keeps its metadata (no scrub without redaction).
            assertTrue(indexOf(saved, "/Info".getBytes(StandardCharsets.US_ASCII), 0) >= 0,
                    "unredacted save unexpectedly scrubbed");
        }
    }

    @Test
    void redactedSaveReportExposedViaSession() throws Exception {
        Path pdf = testPdf("redact-test-sanitize-remnants.pdf");
        try (var session = RedactionSession.open(pdf)) {
            session.sanitizeOnSave(true);
            session.markWordsOnPage(0, new String[]{"SECRET"}, 0xFF000000,
                    0f, false, false, false);
            session.commitPage(0);
            session.saveBytes();
            String report = session.sanitizeReport();
            assertTrue(report != null && !report.isEmpty(), "session sanitize report missing");
            assertTrue(report.contains("annots_removed"), "report missing fields: " + report);
        }
    }

    @Test
    void defaultRedactionPreservesStructureWithoutSanitizing() throws Exception {
        Path pdf = testPdf("redact-test-sanitize-remnants.pdf");
        try (var doc = PdfDocument.open(pdf)) {
            try (var page = doc.page(0)) {
                page.redactWordsEx(new String[]{"SECRET"}, 0xFF000000, 0f,
                        false, false, true, false);
            }
            byte[] saved = doc.saveBytes();
            assertEquals("", doc.sanitizeReport(), "sanitize report must be empty when sanitizeOnSave is not opted in");
            // /Info metadata is preserved by default (no unwanted structure rewrite).
            assertTrue(indexOf(saved, "/Info".getBytes(StandardCharsets.US_ASCII), 0) >= 0,
                    "default save must preserve document metadata");
        }
    }
}
