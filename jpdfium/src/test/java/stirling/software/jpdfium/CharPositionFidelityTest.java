package stirling.software.jpdfium;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that Object Fission redaction preserves the absolute positions of surviving
 * characters with sub-point accuracy.
 *
 * The test uses minimal.pdf which contains text including SSN patterns on page 0.
 * After redacting all SSN patterns, non-digit text ("Hello World") must remain in
 * exactly the same coordinates as before. Any coordinate shift indicates the algorithm
 * is incorrectly repositioning surviving text fragments.
 *
 * Historically, when Object Fission prefix/suffix fragments were not pinned via
 * FPDFText_GetCharOrigin, the text renderer shifted surviving text to fill the gap
 * left by removed characters. These tests make that regression immediately detectable.
 */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class CharPositionFidelityTest {

    /** Tolerance in PDF points. Sub-point shifts are acceptable; anything larger is a bug. */
    private static final double POSITION_TOLERANCE_PT = 0.5;

    /**
     * Search radius for matching a before-char to its after-counterpart.
     * Wider than the tolerance to first locate the candidate, then verify with tighter check.
     */
    private static final double SEARCH_RADIUS_PT = 2.0;

    private static Path pdfPath() throws Exception {
        var url = CharPositionFidelityTest.class.getResource("/pdfs/general/minimal.pdf");
        assertNotNull(url, "minimal.pdf missing from test resources");
        return Path.of(url.toURI());
    }

    /**
     * Core fidelity check: for every character that appears in both the before and
     * after snapshots (matched by unicode value + approximate origin), the position
     * difference must be within POSITION_TOLERANCE_PT.
     *
     * This tolerates the redaction removing entire characters (they simply won't appear
     * in the after list) but rejects any case where a surviving character drifted, even
     * by a fraction of a point.
     */
    @Test
    void survivingCharPositionsArePreservedAfterSsnRedaction() throws Exception {
        List<CharPos> before;
        try (var doc = PdfDocument.open(pdfPath()); var page = doc.page(0)) {
            before = parseCharPositions(page.extractCharPositionsJson());
        }

        byte[] redacted;
        try (var doc = PdfDocument.open(pdfPath())) {
            try (var page = doc.page(0)) {
                int matches = page.redactWordsEx(
                        new String[]{"\\d{3}-\\d{2}-\\d{4}"},
                        0xFF000000, 0f, false, true, true, false);
                assertTrue(matches >= 1, "Expected at least one SSN match; got " + matches);
                page.flatten();
            }
            redacted = doc.saveBytes();
        }

        List<CharPos> after;
        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            after = parseCharPositions(page.extractCharPositionsJson());
        }

        // For every char in the after list, find the nearest matching char in the before
        // list (same unicode, within search radius) and verify the position matches within
        // tolerance. We iterate the after list so we only check chars that actually survived.
        int outOfTolerance = 0;
        StringBuilder failures = new StringBuilder();

        for (CharPos afterChar : after) {
            // Skip whitespace: flatten can merge or reorder whitespace glyphs.
            if (afterChar.unicode() == ' ' || afterChar.unicode() == '\n'
                    || afterChar.unicode() == '\r') continue;

            CharPos beforeChar = findNearest(before, afterChar.unicode(),
                    afterChar.ox(), afterChar.oy(), SEARCH_RADIUS_PT);

            if (beforeChar == null) {
                // A char that appears after but not before is unexpected; flag it.
                if (failures.length() < 600) {
                    failures.append(String.format(
                        "  NEW CHAR (not in before): u=%d ('%s') ox=%.2f oy=%.2f%n",
                        afterChar.unicode(), (char) afterChar.unicode(),
                        afterChar.ox(), afterChar.oy()));
                }
                outOfTolerance++;
                continue;
            }

            double dox = Math.abs(afterChar.ox() - beforeChar.ox());
            double doy = Math.abs(afterChar.oy() - beforeChar.oy());
            if (dox > POSITION_TOLERANCE_PT || doy > POSITION_TOLERANCE_PT) {
                outOfTolerance++;
                if (failures.length() < 600) {
                    failures.append(String.format(
                        "  SHIFTED: u=%d ('%s') before=(%.2f,%.2f) after=(%.2f,%.2f) delta=(%.3f,%.3f)%n",
                        afterChar.unicode(), (char) afterChar.unicode(),
                        beforeChar.ox(), beforeChar.oy(),
                        afterChar.ox(), afterChar.oy(), dox, doy));
                }
            }
        }

        String report = String.format("before=%d after=%d outOfTolerance=%d%n%s",
            before.size(), after.size(), outOfTolerance, failures);

        assertEquals(0, outOfTolerance,
            "Surviving characters shifted or appeared unexpectedly after redaction:\n" + report);
    }

    /**
     * Targeted check: the alphabetic text "Hello" and "World" must survive redaction
     * at the exact same coordinates, since they share no text object with the SSN.
     * This is the simplest possible regression for the Object Fission pin-by-origin logic.
     */
    @Test
    void helloWorldPositionsAreUnchangedAfterSsnRedaction() throws Exception {
        List<CharPos> before;
        List<CharPos> after;

        try (var doc = PdfDocument.open(pdfPath()); var page = doc.page(0)) {
            before = parseCharPositions(page.extractCharPositionsJson());
        }

        byte[] redacted;
        try (var doc = PdfDocument.open(pdfPath())) {
            try (var page = doc.page(0)) {
                page.redactWordsEx(new String[]{"\\d{3}-\\d{2}-\\d{4}"},
                        0xFF000000, 0f, false, true, true, false);
                page.flatten();
            }
            redacted = doc.saveBytes();
        }

        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            after = parseCharPositions(page.extractCharPositionsJson());
        }

        // Check every alphabetic char in the before list (H, e, l, l, o, W, o, r, l, d).
        List<CharPos> alphaChars = before.stream()
                .filter(cp -> Character.isLetter(cp.unicode()))
                .toList();

        assertFalse(alphaChars.isEmpty(), "No alphabetic characters found in minimal.pdf page 0");

        int missing = 0;
        int shifted = 0;
        StringBuilder failures = new StringBuilder();

        for (CharPos expected : alphaChars) {
            CharPos found = findNearest(after, expected.unicode(),
                    expected.ox(), expected.oy(), SEARCH_RADIUS_PT);

            if (found == null) {
                missing++;
                failures.append(String.format(
                    "  MISSING: '%s' (u=%d) ox=%.2f oy=%.2f%n",
                    (char) expected.unicode(), expected.unicode(), expected.ox(), expected.oy()));
                continue;
            }

            double dox = Math.abs(found.ox() - expected.ox());
            double doy = Math.abs(found.oy() - expected.oy());
            if (dox > POSITION_TOLERANCE_PT || doy > POSITION_TOLERANCE_PT) {
                shifted++;
                failures.append(String.format(
                    "  SHIFTED: '%s' (u=%d) before=(%.2f,%.2f) after=(%.2f,%.2f) delta=(%.3f,%.3f)%n",
                    (char) expected.unicode(), expected.unicode(),
                    expected.ox(), expected.oy(), found.ox(), found.oy(), dox, doy));
            }
        }

        String report = String.format("alphaChars=%d missing=%d shifted=%d%n%s",
            alphaChars.size(), missing, shifted, failures);

        assertEquals(0, missing + shifted,
            "Alphabetic characters were removed or repositioned by SSN redaction:\n" + report);
    }

    @Test
    void ssnCharactersAreAbsentAfterRedaction() throws Exception {
        byte[] redacted;
        try (var doc = PdfDocument.open(pdfPath())) {
            try (var page = doc.page(0)) {
                page.redactWordsEx(new String[]{"\\d{3}-\\d{2}-\\d{4}"},
                        0xFF000000, 0f, false, true, true, false);
                page.flatten();
            }
            redacted = doc.saveBytes();
        }

        try (var doc = PdfDocument.open(redacted); var page = doc.page(0)) {
            // Both SSNs that appear in minimal.pdf must be gone from the text layer.
            assertEquals("[]", page.findTextJson("123-45-6789"),
                "First SSN must not be searchable after Object Fission redaction");
            assertEquals("[]", page.findTextJson("987-65-4321"),
                "Second SSN must not be searchable after Object Fission redaction");
        }
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    /**
     * Parse the char-positions JSON produced by jpdfium_text_get_char_positions.
     * Format: [{"i":0,"u":72,"ox":10.1,"oy":20.2,"l":10.0,"r":18.0,"b":15.0,"t":27.0}, ...]
     *
     * Hand-parsed because we control this exact schema and adding a JSON library
     * for one fixed format would be an unnecessary dependency.
     */
    static List<CharPos> parseCharPositions(String json) {
        List<CharPos> result = new ArrayList<>();
        int pos = 0;
        while (true) {
            int open = json.indexOf('{', pos);
            if (open < 0) break;
            int close = json.indexOf('}', open);
            if (close < 0) break;
            String obj = json.substring(open + 1, close);
            result.add(new CharPos(
                intField(obj, "\"i\":"),
                intField(obj, "\"u\":"),
                doubleField(obj, "\"ox\":"),
                doubleField(obj, "\"oy\":"),
                doubleField(obj, "\"l\":"),
                doubleField(obj, "\"r\":"),
                doubleField(obj, "\"b\":"),
                doubleField(obj, "\"t\":")
            ));
            pos = close + 1;
        }
        return result;
    }

    private static int intField(String obj, String key) {
        int k = obj.indexOf(key);
        if (k < 0) return 0;
        int start = k + key.length();
        int end = start;
        while (end < obj.length() && (Character.isDigit(obj.charAt(end)) || obj.charAt(end) == '-')) end++;
        if (start == end) return 0;
        return Integer.parseInt(obj.substring(start, end));
    }

    private static double doubleField(String obj, String key) {
        int k = obj.indexOf(key);
        if (k < 0) return 0.0;
        int start = k + key.length();
        int end = start;
        while (end < obj.length()) {
            char c = obj.charAt(end);
            if (c == ',' || c == '}') break;
            end++;
        }
        String s = obj.substring(start, end).trim();
        return s.isEmpty() ? 0.0 : Double.parseDouble(s);
    }

    /**
     * Find the character in {@code candidates} with the matching unicode value and whose
     * origin is within {@code searchRadius} PDF points. Returns the nearest or null.
     */
    private static CharPos findNearest(List<CharPos> candidates, int unicode,
                                       double ox, double oy, double searchRadius) {
        CharPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (CharPos cp : candidates) {
            if (cp.unicode() != unicode) continue;
            double dx = cp.ox() - ox;
            double dy = cp.oy() - oy;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist <= searchRadius && dist < bestDist) {
                bestDist = dist;
                best = cp;
            }
        }
        return best;
    }

    record CharPos(int index, int unicode, double ox, double oy, double l, double r, double b, double t) {}
}
