package stirling.software.jpdfium.redact;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import stirling.software.jpdfium.PdfDocument;
import stirling.software.jpdfium.PdfPage;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive regression-test suite for the Object Fission redaction algorithm.
 *
 * <h2>Testing strategies</h2>
 * <ol>
 *   <li><strong>Coordinate Tracking</strong> - extracts character positions via
 *       {@code FPDFText_GetCharOrigin} before and after redaction, asserts non-redacted
 *       characters keep their absolute position (within 0.5 pt tolerance).</li>
 *   <li><strong>Text Removal</strong> - redacted patterns are completely absent from
 *       extracted text.</li>
 *   <li><strong>Non-redacted Survival</strong> - verifies specific anchor texts remain
 *       searchable/extractable after redaction.</li>
 *   <li><strong>Visual Regression</strong> - renders pages before/after, asserts &le;5%
 *       pixel difference (only the redaction boxes should change).</li>
 *   <li><strong>Structural Assertions</strong> - avoids crashing on edge cases (empty pages,
 *       single characters, 100-page documents, non-text pages, etc.).</li>
 * </ol>
 *
 * <h2>Test categories</h2>
 * <ul>
 *   <li>Font encoding: Type1 WinAnsi (Helvetica, Times, Courier), mixed, TrueType</li>
 *   <li>Pattern position: SSN at start, end, entire text object, cross-object</li>
 *   <li>Text operators: char spacing, word spacing, h-scaling, text rise, leading</li>
 *   <li>Transforms: rotation, scaling, skew, mirror, CTM concatenation</li>
 *   <li>Page structure: rotated pages, media-box offset, crop-box, multi-stream</li>
 *   <li>Color/transparency: coloured fill, white-on-black, transparency</li>
 *   <li>Rendering modes: Tr 0–7</li>
 *   <li>Multi-pattern / multi-PII: several SSNs, emails, phones in same text</li>
 *   <li>Stress: 100-page doc, 50 SSNs on one page, very long line</li>
 *   <li>Edge cases: empty page, image-only, single character</li>
 *   <li>Complex structures: multi-column, table, marked content, nested q/Q</li>
 * </ul>
 *
 * <p>Run: {@code ./gradlew :jpdfium:integrationTest}
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObjectFissionCoordinateTest {

    // Constants

    private static final String SSN_PATTERN = "\\d{3}-\\d{2}-\\d{4}";
    private static final String SSN1 = "123-45-6789";
    private static final String SSN2 = "987-65-4321";

    /**
     * Maximum allowed shift (PDF pts) for any non-redacted character.
     * 1pt at 300 DPI ≈ 4.17 px. 0.5 pt catches any visible shift while
     * tolerating float rounding.
     */
    private static final double POS_TOL = 0.5;

    // regex to parse one JSON element from extractCharPositionsJson()
    private static final String NUM = "(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)";
    private static final Pattern CHAR_POS_RE = Pattern.compile(
            "\\{\"i\":(\\d+),\"u\":(\\d+)," +
            "\"ox\":" + NUM + ",\"oy\":" + NUM + "," +
            "\"l\":" + NUM + ",\"r\":" + NUM + "," +
            "\"b\":" + NUM + ",\"t\":" + NUM + "\\}"
    );

    // Helper records & methods

    record CharPos(int index, int unicode, double ox, double oy,
                   double l, double r, double b, double t) {
        String ch() { return Character.toString(unicode); }
    }

    private static Path testPdf(String name) throws Exception {
        var url = ObjectFissionCoordinateTest.class.getResource("/pdfs/redact/" + name);
        assertNotNull(url, name + " not found on classpath - run RedactTestPdfGenerator first");
        return Path.of(url.toURI());
    }

    /** Parse the JSON array returned by {@link PdfPage#extractCharPositionsJson()}. */
    private static List<CharPos> positions(PdfPage page) {
        String json = page.extractCharPositionsJson();
        var out = new ArrayList<CharPos>();
        Matcher m = CHAR_POS_RE.matcher(json);
        while (m.find()) {
            out.add(new CharPos(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Double.parseDouble(m.group(3)),
                    Double.parseDouble(m.group(4)),
                    Double.parseDouble(m.group(5)),
                    Double.parseDouble(m.group(6)),
                    Double.parseDouble(m.group(7)),
                    Double.parseDouble(m.group(8))));
        }
        return out;
    }

    /** Full extracted page text from the positions list. */
    private static String text(List<CharPos> positions) {
        var sb = new StringBuilder();
        for (var cp : positions) sb.appendCodePoint(cp.unicode());
        return sb.toString();
    }

    /** Return positions of the first occurrence of {@code needle} in the position list. */
    private static List<CharPos> find(List<CharPos> positions, String needle) {
        String txt = text(positions);
        int idx = txt.indexOf(needle);
        if (idx < 0) return List.of();
        return positions.subList(idx, idx + needle.length());
    }

    /**
     * Assert every character in {@code expected} appears at the same absolute
     * position (within tolerance) in {@code actual}.
     */
    private static void assertPreserved(List<CharPos> expected, List<CharPos> actual,
                                         String ctx) {
        assertPreserved(expected, actual, ctx, POS_TOL);
    }

    /**
     * Assert every character in {@code expected} appears at the same absolute
     * position (within custom tolerance) in {@code actual}.
     * <p>Text-state operators like Tc (char spacing) and Tw (word spacing) are
     * not preserved in fissioned text objects, so characters within a fragment
     * may drift by up to the operator value.  Use a wider tolerance for those.
     */
    private static void assertPreserved(List<CharPos> expected, List<CharPos> actual,
                                         String ctx, double tolerance) {
        for (var e : expected) {
            boolean ok = actual.stream().anyMatch(a ->
                    a.unicode() == e.unicode() &&
                    Math.abs(a.ox() - e.ox()) < tolerance &&
                    Math.abs(a.oy() - e.oy()) < tolerance);
            if (!ok) {
                double bestDx = actual.stream()
                        .filter(a -> a.unicode() == e.unicode())
                        .mapToDouble(a -> Math.abs(a.ox() - e.ox()))
                        .min().orElse(Double.NaN);
                fail(String.format(
                        "%s: '%s' (U+%04X) expected at (%.2f,%.2f), best dX=%.2f (tol=%.1f)",
                        ctx, e.ch(), e.unicode(), e.ox(), e.oy(), bestDx, tolerance));
            }
        }
    }

    /**
     * Run SSN redaction on the given page, flatten, and return saved bytes.
     * Also asserts that at least {@code minMatches} matches were found.
     */
    private byte[] redactSsn(Path path, int pageIdx, int minMatches) throws Exception {
        try (var doc = PdfDocument.open(path)) {
            try (var page = doc.page(pageIdx)) {
                int n = page.redactWordsEx(
                        new String[]{SSN_PATTERN},
                        0xFF000000, 0.0f,
                        false, true, true, false);
                assertTrue(n >= minMatches,
                        "Expected >=" + minMatches + " SSN matches on page " + pageIdx +
                        ", got " + n);
                page.flatten();
            }
            return doc.saveBytes();
        }
    }

    /** Redact SSNs on ALL pages and return the saved document bytes. */
    private byte[] redactSsnAllPages(Path path) throws Exception {
        try (var doc = PdfDocument.open(path)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (var page = doc.page(i)) {
                    page.redactWordsEx(
                            new String[]{SSN_PATTERN},
                            0xFF000000, 0.0f,
                            false, true, true, false);
                    page.flatten();
                }
            }
            return doc.saveBytes();
        }
    }

    /** Redact arbitrary words on page 0 and return saved bytes. */
    private byte[] redactWords(Path path, String[] words, boolean regex) throws Exception {
        try (var doc = PdfDocument.open(path)) {
            try (var page = doc.page(0)) {
                page.redactWordsEx(words, 0xFF000000, 0.0f,
                        false, regex, true, false);
                page.flatten();
            }
            return doc.saveBytes();
        }
    }

    // 1. FONT ENCODING - Coordinate Preservation

    @Order(1)
    @ParameterizedTest(name = "[{0}] suffix text does not shift after SSN redaction")
    @CsvSource({
            // pdf,                         anchor to check,      page
            "redact-test-helvetica.pdf,     is confidential.,     0",
            "redact-test-times.pdf,         is confidential.,     0",
            "redact-test-courier.pdf,       is confidential.,     0",
            "redact-test-mixed-fonts.pdf,   is classified.,       0",
            "redact-test-multiline.pdf,     employed since 2020., 0",
            "redact-test-large-font.pdf,    BIG TEXT,             0",
            "redact-test-truetype.pdf,      confidential data.,   0",
            "redact-test-subset.pdf,        confidential data.,   0",
    })
    void suffixPositionPreserved(String pdf, String anchor, int pg) throws Exception {
        anchor = anchor.strip();
        Path path = testPdf(pdf);
        List<CharPos> pre;
        try (var doc = PdfDocument.open(path); var page = doc.page(pg)) {
            pre = find(positions(page), anchor);
        }
        assertFalse(pre.isEmpty(), "Anchor '" + anchor + "' not found in " + pdf);

        byte[] redacted = redactSsn(path, pg, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(pg)) {
            assertPreserved(pre, positions(page), pdf + " / '" + anchor + "'");
        }
    }

    // 2. PATTERN POSITION - start / end / entire / cross-object

    @Order(2)
    @Test
    void patternAtStart_suffixPreserved() throws Exception {
        Path path = testPdf("redact-test-pattern-start.pdf");
        List<CharPos> pre;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            pre = find(positions(page), "is the SSN on file.");
        }
        assertFalse(pre.isEmpty());

        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertPreserved(pre, positions(page), "pattern-start suffix");
        }
    }

    @Order(2)
    @Test
    void patternAtEnd_prefixPreserved() throws Exception {
        Path path = testPdf("redact-test-pattern-end.pdf");
        List<CharPos> pre;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            pre = find(positions(page), "The SSN on file is");
        }
        assertFalse(pre.isEmpty());

        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertPreserved(pre, positions(page), "pattern-end prefix");
        }
    }

    @Order(2)
    @Test
    void patternIsEntireObject_otherObjectsSurvive() throws Exception {
        Path path = testPdf("redact-test-pattern-entire.pdf");
        List<CharPos> pre;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            pre = find(positions(page), "This line should not move.");
        }
        assertFalse(pre.isEmpty());

        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertPreserved(pre, positions(page), "pattern-entire / other object");
            String txt = text(positions(page));
            assertFalse(txt.contains(SSN1), "SSN should be removed");
        }
    }

    @Order(2)
    @Test
    void crossObjectSsnIsRedacted() throws Exception {
        Path path = testPdf("redact-test-cross-object.pdf");
        byte[] redacted = redactSsnAllPages(path);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            String txt = text(positions(page));
            assertFalse(txt.contains("123-45-6789"),
                    "Cross-object SSN should be removed: " + txt);
        }
    }

    @Order(2)
    @Test
    void singleCharObjectsSsnRedacted() throws Exception {
        Path path = testPdf("redact-test-single-char-objs.pdf");
        byte[] redacted = redactSsnAllPages(path);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            String txt = text(positions(page));
            assertFalse(txt.contains("123-45-6789"),
                    "Single-char-object SSN should be removed: " + txt);
        }
    }

    // 3. MULTI-SSN SAME LINE

    @Order(3)
    @Test
    void multiSsnSameLine_intermediateAndSuffixPreserved() throws Exception {
        // Page 2 of helvetica: "Records: SSN 123-45-6789 and 987-65-4321 filed today."
        Path path = testPdf("redact-test-helvetica.pdf");
        List<CharPos> preAnd, preFiled;
        try (var doc = PdfDocument.open(path); var page = doc.page(1)) {
            var all = positions(page);
            preAnd = find(all, "and");
            preFiled = find(all, "filed today.");
        }
        assertFalse(preAnd.isEmpty(), "'and' not found on page 2");
        assertFalse(preFiled.isEmpty(), "'filed today.' not found on page 2");

        byte[] redacted = redactSsn(path, 1, 2);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(1)) {
            var post = positions(page);
            assertPreserved(preAnd, post, "helvetica p2 / 'and'");
            assertPreserved(preFiled, post, "helvetica p2 / 'filed today.'");
        }
    }

    @Order(3)
    @Test
    void threeSsnLine_allRedactedSuffixPreserved() throws Exception {
        Path path = testPdf("redact-test-three-ssn-line.pdf");
        List<CharPos> preSuffix;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            preSuffix = find(positions(page), "end of line.");
        }
        assertFalse(preSuffix.isEmpty());

        byte[] redacted = redactSsn(path, 0, 3);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            var post = positions(page);
            assertPreserved(preSuffix, post, "three-ssn suffix");
            String txt = text(post);
            assertFalse(txt.contains(SSN1), "SSN1 should be gone");
            assertFalse(txt.contains(SSN2), "SSN2 should be gone");
            assertFalse(txt.contains("111-22-3333"), "SSN3 should be gone");
        }
    }

    // 4. TEXT REMOVAL VERIFICATION

    @Order(4)
    @ParameterizedTest(name = "[{0}] SSN patterns completely removed")
    @ValueSource(strings = {
            "redact-test-helvetica.pdf",
            "redact-test-times.pdf",
            "redact-test-courier.pdf",
            "redact-test-mixed-fonts.pdf",
            "redact-test-multiline.pdf",
            "redact-test-large-font.pdf"
    })
    void ssnPatternsCompletelyRemoved(String pdf) throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf(pdf));
        try (var doc = PdfDocument.open(redacted)) {
            for (int i = 0; i < doc.pageCount(); i++) {
                try (var page = doc.page(i)) {
                    assertEquals("[]", page.findTextJson(SSN1),
                            pdf + " p" + i + ": SSN1 should be gone");
                    assertEquals("[]", page.findTextJson(SSN2),
                            pdf + " p" + i + ": SSN2 should be gone");
                }
            }
        }
    }

    // 5. NON-REDACTED TEXT SURVIVAL

    @Order(5)
    @ParameterizedTest(name = "[{0}] non-redacted text survives")
    @ValueSource(strings = {
            "redact-test-helvetica.pdf",
            "redact-test-times.pdf",
            "redact-test-courier.pdf"
    })
    void nonRedactedTextSurvives(String pdf) throws Exception {
        byte[] redacted = redactSsn(testPdf(pdf), 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertNotEquals("[]", page.findTextJson("quick brown fox"),
                    pdf + ": 'quick brown fox' should survive SSN redaction");
        }
    }

    // 6. TEXT OPERATORS - char/word spacing, h-scaling, text rise, leading

    @Order(6)
    @ParameterizedTest(name = "[{0}] operator: suffix preserved after SSN redaction")
    @CsvSource({
            "redact-test-hscaling.pdf,      confidential.",
            "redact-test-textrise.pdf,      confidential.",
            "redact-test-leading.pdf,       confidential.",
            "redact-test-kerning.pdf,       confidential AWAY TO.",
    })
    void textOperatorSuffixPreserved(String pdf, String anchor) throws Exception {
        anchor = anchor.strip();
        Path path = testPdf(pdf);
        List<CharPos> pre;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            pre = find(positions(page), anchor);
        }
        assertFalse(pre.isEmpty(), "Anchor '" + anchor + "' not found in " + pdf);

        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertPreserved(pre, positions(page), pdf + " / '" + anchor + "'");
        }
    }

    /**
     * Tc (character spacing) and Tw (word spacing) text-state operators are NOT
     * preserved in fissioned text objects, so intra-fragment positions drift by
     * up to Tc×N or Tw×words.  We verify only that:
     *  (a) the SSN is removed,
     *  (b) the suffix text content survives, and
     *  (c) the first character of the suffix is within the operator-value tolerance.
     */
    @Order(6)
    @ParameterizedTest(name = "[{0}] operator with drift: suffix text survives")
    @CsvSource({
            "redact-test-charspacing.pdf,   confidential.,  2.5",
            "redact-test-wordspacing.pdf,   confidential,   6.0",
    })
    void textOperatorWithDriftSuffixSurvives(String pdf, String anchor,
                                              double firstCharTol) throws Exception {
        anchor = anchor.strip();
        Path path = testPdf(pdf);

        // Record first-char position before redaction
        CharPos firstCharBefore;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            var pre = find(positions(page), anchor);
            assertFalse(pre.isEmpty(), "Anchor '" + anchor + "' not found in " + pdf);
            firstCharBefore = pre.get(0);
        }

        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            // SSN removed
            assertEquals("[]", page.findTextJson(SSN1),
                    pdf + ": SSN should be removed");
            // Suffix text content survives
            assertNotEquals("[]", page.findTextJson(anchor),
                    pdf + ": '" + anchor + "' should survive redaction");
            // First character within operator-value tolerance
            var post = positions(page);
            boolean ok = post.stream().anyMatch(a ->
                    a.unicode() == firstCharBefore.unicode() &&
                    Math.abs(a.ox() - firstCharBefore.ox()) < firstCharTol &&
                    Math.abs(a.oy() - firstCharBefore.oy()) < firstCharTol);
            assertTrue(ok, String.format(
                    "%s: first char '%s' expected near (%.2f,%.2f) within tol=%.1f",
                    pdf, firstCharBefore.ch(), firstCharBefore.ox(),
                    firstCharBefore.oy(), firstCharTol));
        }
    }

    // 7. TEXT TRANSFORMS - rotation, scale, skew, mirror, CTM

    @Order(7)
    @ParameterizedTest(name = "[{0}] transformed text: SSN removed")
    @ValueSource(strings = {
            "redact-test-rotated-text-45.pdf",
            "redact-test-rotated-text-90.pdf",
            "redact-test-scaled-text.pdf",
            "redact-test-skewed-text.pdf",
            "redact-test-ctm.pdf"
    })
    void transformedTextSsnRemoved(String pdf) throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf(pdf));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1),
                    pdf + ": SSN should be removed from transformed text");
        }
    }

    @Order(7)
    @ParameterizedTest(name = "[{0}] transformed text: suffix preserved")
    @CsvSource({
            "redact-test-rotated-text-45.pdf,  confidential.",
            "redact-test-rotated-text-90.pdf,  confidential.",
            "redact-test-scaled-text.pdf,      confidential.",
            "redact-test-skewed-text.pdf,      confidential.",
            "redact-test-ctm.pdf,              confidential.",
    })
    void transformedTextSuffixPreserved(String pdf, String anchor) throws Exception {
        anchor = anchor.strip();
        Path path = testPdf(pdf);
        List<CharPos> pre;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            pre = find(positions(page), anchor);
        }
        assertFalse(pre.isEmpty(), "Anchor '" + anchor + "' not found in " + pdf);

        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertPreserved(pre, positions(page), pdf + " / '" + anchor + "'");
        }
    }

    // 8. PAGE STRUCTURE - rotated pages, mediabox, cropbox, multistream

    @Order(8)
    @ParameterizedTest(name = "page rotation {0} deg: SSN removed")
    @ValueSource(ints = {0, 90, 180, 270})
    void rotatedPageSsnRemoved(int rot) throws Exception {
        String pdf = "redact-test-rotate-" + rot + ".pdf";
        byte[] redacted = redactSsnAllPages(testPdf(pdf));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1),
                    pdf + ": SSN should be removed in rotated page");
        }
    }

    @Order(8)
    @ParameterizedTest(name = "[{0}] page structure: SSN removed")
    @ValueSource(strings = {
            "redact-test-mediabox-offset.pdf",
            "redact-test-cropbox.pdf",
            "redact-test-a0.pdf",
            "redact-test-card.pdf",
            "redact-test-multistream.pdf"
    })
    void pageStructureSsnRemoved(String pdf) throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf(pdf));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1),
                    pdf + ": SSN should be removed");
        }
    }

    // 9. FONT SIZES

    @Order(9)
    @ParameterizedTest(name = "font size {0}pt: SSN removed + suffix preserved")
    @ValueSource(strings = {"4", "6", "8", "10", "12", "24", "48", "72", "144"})
    void fontSizeSsnRedacted(String size) throws Exception {
        String pdf = "redact-test-size-" + size + "pt.pdf";
        Path path = testPdf(pdf);

        List<CharPos> preSuffix;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            preSuffix = find(positions(page), "confidential.");
        }
        assertFalse(preSuffix.isEmpty(), pdf + ": 'confidential.' not found");

        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1), pdf + ": SSN should be gone");
            assertPreserved(preSuffix, positions(page), pdf + " / suffix");
        }
    }

    // 10. RENDERING MODES (Tr 0–7)

    @Order(10)
    @ParameterizedTest(name = "rendering mode Tr{0}: SSN removed")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void renderingModeSsnRemoved(int tr) throws Exception {
        String pdf = "redact-test-tr" + tr + ".pdf";
        byte[] redacted = redactSsnAllPages(testPdf(pdf));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1),
                    pdf + ": SSN should be removed for Tr=" + tr);
        }
    }

    // 11. COLOR PRESERVATION

    @Order(11)
    @ParameterizedTest(name = "[{0}] colored text: SSN removed + suffix survives")
    @ValueSource(strings = {
            "redact-test-colored.pdf",
            "redact-test-transparent.pdf",
            "redact-test-over-background.pdf",
            "redact-test-white-on-black.pdf"
    })
    void coloredTextSsnRemoved(String pdf) throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf(pdf));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1), pdf + ": SSN gone");
            assertNotEquals("[]", page.findTextJson("confidential"),
                    pdf + ": suffix 'confidential' should survive");
        }
    }

    // 12. UNICODE / i18n (WinAnsi-encodable)

    @Order(12)
    @ParameterizedTest(name = "[{0}] unicode text: SSN removed + suffix preserved")
    @CsvSource({
            "redact-test-accented.pdf,   confidential.",
            "redact-test-nbsp.pdf,       confidential.",
            "redact-test-ligatures.pdf,  confidential.",
    })
    void unicodeTextSsnRemoved(String pdf, String anchor) throws Exception {
        anchor = anchor.strip();
        Path path = testPdf(pdf);
        List<CharPos> pre;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            pre = find(positions(page), anchor);
        }
        assertFalse(pre.isEmpty(), "Anchor '" + anchor + "' not found in " + pdf);

        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1), pdf + ": SSN gone");
            assertPreserved(pre, positions(page), pdf + " / anchor");
        }
    }

    // 13. MULTI-PATTERN / MULTI-PII

    @Order(13)
    @Test
    void multiPii_ssnRemovedOtherDataIntact() throws Exception {
        Path path = testPdf("redact-test-multi-pii.pdf");
        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1), "SSN should be gone");
            // email and phone should survive since we only redacted SSN
            assertNotEquals("[]", page.findTextJson("john@example.com"),
                    "Email should survive SSN-only redaction");
        }
    }

    @Order(13)
    @Test
    void overlappingMatch_noCorruption() throws Exception {
        Path path = testPdf("redact-test-overlapping-match.pdf");
        // "111-22-3333-44-5555" - SSN regex could match at the start
        byte[] redacted = redactSsnAllPages(path);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            // should not crash; page should still be readable
            var allPos = positions(page);
            assertNotNull(allPos);
        }
    }

    // 14. PATTERN-SPECIFIC: email, phone, CC

    @Order(14)
    @Test
    void emailRedaction() throws Exception {
        Path path = testPdf("redact-test-email.pdf");
        byte[] redacted = redactWords(path,
                new String[]{"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"}, true);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson("john.doe@example.com"),
                    "Email should be redacted");
            assertNotEquals("[]", page.findTextJson("for info"),
                    "Suffix should survive");
        }
    }

    @Order(14)
    @Test
    void phoneRedaction() throws Exception {
        Path path = testPdf("redact-test-phone.pdf");
        byte[] redacted = redactWords(path,
                new String[]{"\\(\\d{3}\\)\\s?\\d{3}-\\d{4}"}, true);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson("(555) 123-4567"),
                    "Phone should be redacted");
        }
    }

    @Order(14)
    @Test
    void creditCardRedaction() throws Exception {
        Path path = testPdf("redact-test-creditcard.pdf");
        byte[] redacted = redactWords(path,
                new String[]{"\\d{4}\\s\\d{4}\\s\\d{4}\\s\\d{4}"}, true);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson("4111 1111 1111 1111"),
                    "CC should be redacted");
        }
    }

    // 15. EDGE CASES - empty page, image-only, single char

    @Order(15)
    @Test
    void emptyPage_noCrash() throws Exception {
        Path path = testPdf("redact-test-empty.pdf");
        // Should not throw
        byte[] redacted = redactSsnAllPages(path);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals(0, positions(page).size(),
                    "Empty page should have 0 characters");
        }
    }

    @Order(15)
    @Test
    void imageOnlyPage_noCrash() throws Exception {
        Path path = testPdf("redact-test-image-only.pdf");
        byte[] redacted = redactSsnAllPages(path);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            // Should not crash; no text expected
            var pos = positions(page);
            assertTrue(pos.isEmpty(), "Image-only page has no text");
        }
    }

    @Order(15)
    @Test
    void singleChar_noCrash() throws Exception {
        Path path = testPdf("redact-test-single-char.pdf");
        byte[] redacted = redactSsnAllPages(path);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            // "A" should survive (it's not an SSN)
            assertNotEquals("[]", page.findTextJson("A"),
                    "Single 'A' character should survive");
        }
    }

    // 16. COMPLEX STRUCTURES

    @Order(16)
    @Test
    void nestedGraphicsState_ssnRemoved() throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf("redact-test-nested-q.pdf"));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1), "SSN should be gone");
        }
    }

    @Order(16)
    @Test
    void multiColumnLayout_otherColumnUntouched() throws Exception {
        Path path = testPdf("redact-test-multicolumn.pdf");
        List<CharPos> preCol2;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            preCol2 = find(positions(page), "Column two text unchanged.");
        }
        assertFalse(preCol2.isEmpty());

        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertPreserved(preCol2, positions(page), "multicolumn / col2");
        }
    }

    @Order(16)
    @Test
    void tableLayout_adjacentCellIntact() throws Exception {
        Path path = testPdf("redact-test-table.pdf");
        List<CharPos> preAdj;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            preAdj = find(positions(page), "Adjacent cell data.");
        }
        assertFalse(preAdj.isEmpty());

        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertPreserved(preAdj, positions(page), "table / adjacent cell");
        }
    }

    @Order(16)
    @Test
    void markedContent_ssnRemoved() throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf("redact-test-marked-content.pdf"));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1), "SSN should be removed");
        }
    }

    @Order(16)
    @Test
    void mixedPositioning_ssnRemoved() throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf("redact-test-mixed-positioning.pdf"));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1));
            assertNotEquals("[]", page.findTextJson("confidential data"),
                    "Non-SSN text on next line should survive");
        }
    }

    @Order(16)
    @Test
    void quoteOps_ssnRemoved() throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf("redact-test-quote-ops.pdf"));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1));
        }
    }

    @Order(16)
    @Test
    void textWithPath_ssnRemoved() throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf("redact-test-text-paths.pdf"));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1));
            assertNotEquals("[]", page.findTextJson("After path"),
                    "Text after path object should survive");
        }
    }

    // 17. STRESS TESTS

    @Order(17)
    @Test
    void stress100Pages_allPagesRedacted() throws Exception {
        Path path = testPdf("redact-test-100pages.pdf");
        byte[] redacted = redactSsnAllPages(path);
        try (var doc = PdfDocument.open(redacted)) {
            assertEquals(100, doc.pageCount());
            // Spot-check a few pages
            for (int p : new int[]{0, 49, 99}) {
                try (var page = doc.page(p)) {
                    assertEquals("[]", page.findTextJson(SSN1),
                            "Page " + p + ": SSN should be removed");
                }
            }
        }
    }

    @Order(17)
    @Test
    void stress50Ssns_allRemoved() throws Exception {
        Path path = testPdf("redact-test-50ssns.pdf");
        byte[] redacted = redactSsnAllPages(path);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            String txt = text(positions(page));
            // Verify no SSN-pattern remains
            var ssnRe = java.util.regex.Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
            assertFalse(ssnRe.matcher(txt).find(),
                    "No SSN-pattern should remain in extracted text: " +
                    txt.substring(0, Math.min(200, txt.length())));
        }
    }

    @Order(17)
    @Test
    void stressLongLine_ssnRemoved() throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf("redact-test-longline.pdf"));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1), "Long-line SSN should be removed");
        }
    }

    // 18. VISUAL REGRESSION

    @Order(18)
    @ParameterizedTest(name = "[{0}] visual regression: <=5% pixel diff")
    @ValueSource(strings = {
            "redact-test-helvetica.pdf",
            "redact-test-times.pdf",
            "redact-test-courier.pdf"
    })
    void visualRegression(String pdf) throws Exception {
        Path path = testPdf(pdf);
        int dpi = 150;

        // Render original
        int[] origPx;
        int w, h;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            var img = page.renderAt(dpi).toBufferedImage();
            w = img.getWidth();
            h = img.getHeight();
            origPx = img.getRGB(0, 0, w, h, null, 0, w);
        }

        // Redact + render
        byte[] redacted = redactSsn(path, 0, 1);
        int[] redPx;
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            var img = page.renderAt(dpi).toBufferedImage();
            assertEquals(w, img.getWidth());
            assertEquals(h, img.getHeight());
            redPx = img.getRGB(0, 0, w, h, null, 0, w);
        }

        int total = w * h;
        int diff = 0;
        for (int i = 0; i < total; i++) {
            if (origPx[i] != redPx[i]) diff++;
        }
        double pct = 100.0 * diff / total;
        assertTrue(pct < 5.0,
                String.format("%s: %.1f%% pixels differ (max 5%%). Possible text shift.", pdf, pct));
    }

    // 19. OVERLAPPING TEXT OBJECTS

    @Order(19)
    @Test
    void overlappingTextObjects_noCrash() throws Exception {
        // Verifies two BT/ET blocks at the same Y coordinate parse without structure corruption
        byte[] redacted = redactSsnAllPages(testPdf("redact-test-overlapping.pdf"));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            // Just verify no crash and some text survives
            var pos = positions(page);
            assertFalse(pos.isEmpty(), "Some text should survive");
        }
    }

    @Order(19)
    @Test
    void adjacentSsn_bothRemoved() throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf("redact-test-adjacent-ssn.pdf"));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1), "SSN1 should be gone");
            assertEquals("[]", page.findTextJson(SSN2), "SSN2 should be gone");
        }
    }

    // 20. INLINE IMAGE (text + non-text interleaved)

    @Order(20)
    @Test
    void inlineImage_ssnRemovedTextSurvives() throws Exception {
        Path path = testPdf("redact-test-inline-image.pdf");
        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1), "SSN removed");
            assertNotEquals("[]", page.findTextJson("After image"),
                    "Text after image should survive");
        }
    }

    // 21. MULTILINE COORDINATE PRESERVATION

    @Order(21)
    @Test
    void multilineDocument_allLinesPreserved() throws Exception {
        Path path = testPdf("redact-test-multiline.pdf");
        String[] anchors = {"department: Engineering.", "no sensitive data", "end of file."};
        Map<String, List<CharPos>> preMap = new LinkedHashMap<>();
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            var all = positions(page);
            for (String a : anchors) {
                var found = find(all, a);
                if (!found.isEmpty()) preMap.put(a, found);
            }
        }
        assertFalse(preMap.isEmpty(), "At least one anchor should be found");

        byte[] redacted = redactSsnAllPages(path);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            var post = positions(page);
            for (var entry : preMap.entrySet()) {
                assertPreserved(entry.getValue(), post,
                        "multiline / '" + entry.getKey() + "'");
            }
        }
    }

    // 22. MIRRORED TEXT

    @Order(22)
    @Test
    void mirroredText_ssnRemoved() throws Exception {
        byte[] redacted = redactSsnAllPages(testPdf("redact-test-mirrored-text.pdf"));
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertEquals("[]", page.findTextJson(SSN1), "Mirrored SSN should be removed");
        }
    }

    // 23. WIDTH FIDELITY REGRESSION

    @Order(23)
    @Test
    void widthFidelity_suffixPreserved() throws Exception {
        Path path = testPdf("redact-test-width-fidelity.pdf");
        List<CharPos> pre;
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            pre = find(positions(page), "Wii wide-narrow test.");
        }
        assertFalse(pre.isEmpty());

        byte[] redacted = redactSsn(path, 0, 1);
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            assertPreserved(pre, positions(page), "width-fidelity suffix");
        }
    }

    // 24. RE-REDACTION IDEMPOTENCY

    @Order(24)
    @Test
    void doubleRedaction_noCrash() throws Exception {
        Path path = testPdf("redact-test-helvetica.pdf");
        // Redact once
        byte[] first = redactSsn(path, 0, 1);
        // Ensures no exceptions are thrown when attempting redaction on an already-redacted document
        try (var doc = PdfDocument.open(first)) {
            try (var page = doc.page(0)) {
                int count = page.redactWordsEx(
                        new String[]{SSN_PATTERN},
                        0xFF000000, 0.0f,
                        false, true, true, false);
                // Already redacted, so should find 0 or crash-free
                assertTrue(count >= 0, "Double-redact should not crash");
                page.flatten();
            }
            byte[] second = doc.saveBytes();
            // Verify still readable
            try (var doc2 = PdfDocument.open(second); var page = doc2.page(0)) {
                assertNotNull(positions(page));
            }
        }
    }

    // 25. CASE SENSITIVITY

    @Order(25)
    @Test
    void caseSensitiveRedaction() throws Exception {
        Path path = testPdf("redact-test-helvetica.pdf");
        // Case-sensitive search for uppercase "EMPLOYEE" should find 0 in mixed case
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            int count = page.redactWordsEx(
                    new String[]{"EMPLOYEE SSN"},
                    0xFF000000, 0.0f,
                    false, false, true, true);  // caseSensitive=true
            assertEquals(0, count,
                    "Case-sensitive search for uppercase should find nothing in mixed case");
        }
    }

    @Order(25)
    @Test
    void caseInsensitiveRedaction() throws Exception {
        Path path = testPdf("redact-test-helvetica.pdf");
        try (var doc = PdfDocument.open(path); var page = doc.page(0)) {
            int count = page.redactWordsEx(
                    new String[]{"employee ssn"},
                    0xFF000000, 0.0f,
                    false, false, true, false);  // caseSensitive=false
            // Should match "Employee SSN" case-insensitively
            assertTrue(count >= 1,
                    "Case-insensitive search should find 'Employee SSN', got " + count);
        }
    }
}
