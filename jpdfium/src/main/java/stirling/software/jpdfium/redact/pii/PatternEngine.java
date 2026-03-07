package stirling.software.jpdfium.redact.pii;

import stirling.software.jpdfium.panama.Pcre2Lib;
import stirling.software.jpdfium.util.NativeJsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * PCRE2 JIT-compiled pattern engine for high-performance PII detection.
 *
 * <p>Compiles regex patterns once to native machine code via PCRE2's JIT compiler,
 * then runs them at near-native speed against extracted text. Supports:
 * <ul>
 *   <li>Lookaheads and lookbehinds</li>
 *   <li>Unicode word boundaries ({@code \b})</li>
 *   <li>Script-aware {@code \w} (matches accented, CJK, Cyrillic characters)</li>
 *   <li>Named capture groups ({@code (?P&lt;name&gt;...)})</li>
 *   <li>Luhn post-validation for credit card numbers</li>
 * </ul>
 *
 * <p><b>Usage</b></p>
 * <pre>{@code
 * try (PatternEngine engine = PatternEngine.create(PiiCategory.all())) {
 *     List<Match> matches = engine.findAll("Call John at john@example.com or 555-123-4567");
 *     for (Match m : matches) {
 *         System.out.printf("[%s] %s at %d-%d%n", m.category(), m.text(), m.start(), m.end());
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Thread safety:</strong> A {@code PatternEngine} instance must be confined
 * to a single thread. Create separate instances for concurrent use.
 */
public final class PatternEngine implements AutoCloseable {

    private final List<CompiledPattern> patterns;
    private volatile boolean closed = false;

    private PatternEngine(List<CompiledPattern> patterns) {
        this.patterns = patterns;
    }

    /**
     * Compile a single PCRE2 pattern with default UTF+UCP flags.
     *
     * @param regex PCRE2 regex pattern
     * @return pattern engine instance (must be closed when done)
     */
    public static PatternEngine compile(String regex) {
        return compile(regex, null);
    }

    /**
     * Compile a single named PCRE2 pattern with default UTF+UCP flags.
     *
     * @param regex    PCRE2 regex pattern
     * @param category category for matches, or null for uncategorized
     * @return pattern engine instance
     */
    public static PatternEngine compile(String regex, PiiCategory category) {
        int flags = Pcre2Lib.PCRE2_UTF | Pcre2Lib.PCRE2_UCP;
        long handle = Pcre2Lib.compile(regex, flags);
        List<CompiledPattern> patterns = List.of(new CompiledPattern(handle, category, regex));
        return new PatternEngine(patterns);
    }

    /**
     * Create a pattern engine from a map of category to regex patterns.
     * All patterns are compiled with PCRE2 JIT for maximum performance.
     *
     * @param categoryPatterns map of category to PCRE2 regex
     * @return pattern engine instance
     * @see PiiCategory#all()
     */
    public static PatternEngine create(Map<PiiCategory, String> categoryPatterns) {
        int flags = Pcre2Lib.PCRE2_UTF | Pcre2Lib.PCRE2_UCP;
        List<CompiledPattern> compiled = new ArrayList<>();
        for (Map.Entry<PiiCategory, String> entry : categoryPatterns.entrySet()) {
            long handle = Pcre2Lib.compile(entry.getValue(), flags);
            compiled.add(new CompiledPattern(handle, entry.getKey(), entry.getValue()));
        }
        return new PatternEngine(compiled);
    }

    /**
     * Find all pattern matches in the given text.
     *
     * @param text text to search (typically extracted from a PDF page)
     * @return list of matches with positions, matched text, and category labels
     */
    public List<Match> findAll(String text) {
        ensureOpen();
        if (text == null || text.isEmpty()) return List.of();

        List<Match> allMatches = new ArrayList<>();
        for (CompiledPattern cp : patterns) {
            String json = Pcre2Lib.matchAll(cp.handle, text);
            List<Match> parsed = parseMatchesJson(json, cp.category);

            if (PiiCategory.CREDIT_CARD == cp.category) {
                parsed = parsed.stream()
                        .filter(m -> validateCreditCard(m.text))
                        .toList();
            }

            allMatches.addAll(parsed);
        }

        allMatches.sort((a, b) -> {
            int cmp = Integer.compare(a.start, b.start);
            return cmp != 0 ? cmp : Integer.compare(b.end, a.end);
        });

        return Collections.unmodifiableList(allMatches);
    }

    /**
     * Validate a potential credit card number using the Luhn algorithm.
     *
     * @param number potential card number (may contain spaces/dashes)
     * @return true if the number passes Luhn validation
     */
    public static boolean validateCreditCard(String number) {
        return Pcre2Lib.luhnValidate(number);
    }

    public int patternCount() {
        return patterns.size();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("PatternEngine is already closed");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (CompiledPattern cp : patterns) {
            Pcre2Lib.free(cp.handle);
        }
    }

    /**
     * A single pattern match result.
     *
     * @param start    character offset of match start in the input text
     * @param end      character offset of match end (exclusive)
     * @param text     matched text
     * @param category PII category, or null for uncategorized patterns
     */
    public record Match(int start, int end, String text, PiiCategory category) {
        public int length() { return end - start; }
    }

    private record CompiledPattern(long handle, PiiCategory category, String regex) {}

    static List<Match> parseMatchesJson(String json, PiiCategory category) {
        List<Match> matches = new ArrayList<>();
        for (Map<String, String> fields : NativeJsonParser.parseArray(json)) {
            int start = Integer.parseInt(fields.getOrDefault("start", "0"));
            int end = Integer.parseInt(fields.getOrDefault("end", "0"));
            String matchText = fields.getOrDefault("match", "");
            matches.add(new Match(start, end, matchText, category));
        }
        return matches;
    }
}
