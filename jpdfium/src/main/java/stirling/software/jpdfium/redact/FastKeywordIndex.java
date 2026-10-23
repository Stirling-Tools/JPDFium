package stirling.software.jpdfium.redact;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

// Keyword index for page-level redaction candidate prefiltering.
//
// Contract: zero false negatives. If the authoritative native PCRE2 matcher
// would match, the prefilter must report a candidate. False positives are
// acceptable (native verifies). Folding uses Unicode simple case folding
// (single code point, PCRE2-compatible): no multi-character expansions
// (PCRE2 does not fold s-s/SS or sharp-s), final sigma folds to sigma,
// supplementary pairs fold by code point.
final class FastKeywordIndex {

    private static final class TrieNode {
        final char ch;
        // Every original keyword folding to this node. Multiple inputs can
        // share one key (NFKC variants like ﬁle/file, folding equivalents
        // like istanbul/İstanbul); keeping only the last would send a
        // non-matching spelling to native and miss the other (CWE-212).
        List<String> keywords;
        boolean keywordStartsWithWordChar;
        boolean keywordEndsWithWordChar;
        TrieNode firstChild;
        TrieNode nextSibling;

        TrieNode(char ch) {
            this.ch = ch;
        }

        TrieNode findChild(char target) {
            TrieNode curr = firstChild;
            while (curr != null) {
                if (curr.ch == target) {
                    return curr;
                }
                curr = curr.nextSibling;
            }
            return null;
        }

        TrieNode getOrCreateChild(char target) {
            TrieNode existing = findChild(target);
            if (existing != null) {
                return existing;
            }
            TrieNode newNode = new TrieNode(target);
            newNode.nextSibling = firstChild;
            firstChild = newNode;
            return newNode;
        }
    }

    private final TrieNode[] asciiRoot = new TrieNode[128];
    private TrieNode nonAsciiRoot;
    private final boolean caseSensitive;
    private final int keywordCount;
    private final List<String> allKeywords;
    private final boolean needsConservativeFallback;
    private boolean anyNonWordStartKeyword;

    private FastKeywordIndex(Collection<String> words, boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
        int count = 0;
        boolean supplementary = false;
        List<String> kept = new ArrayList<>();
        for (String word : words) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            addKeyword(word);
            kept.add(word);
            count++;
            if (!caseSensitive && containsSupplementary(word)) {
                supplementary = true;
            }
        }
        this.keywordCount = count;
        this.allKeywords = List.copyOf(kept);
        this.needsConservativeFallback = supplementary;
    }

    static FastKeywordIndex create(Collection<String> words, boolean caseSensitive) {
        return new FastKeywordIndex(words, caseSensitive);
    }

    private static int foldCodePoint(int codePoint, boolean caseSensitive) {
        if (caseSensitive) {
            return codePoint;
        }
        // Fast path for ASCII characters
        if (codePoint < 128) {
            return (codePoint >= 'A' && codePoint <= 'Z') ? (codePoint + 32) : codePoint;
        }
        // Lowercase via uppercase first so PCRE2 case-equivalent variants
        // without NFKC decompositions (Cyrillic U+1C80-U+1C88, long s, etc.)
        // merge to the same form instead of being missed.
        int lower = Character.toLowerCase(Character.toUpperCase(codePoint));
        // Unicode simple folding: final sigma (U+03C2) folds to sigma (U+03C3).
        // Character.toLowerCase leaves final sigma unchanged, but PCRE2
        // caseless matching treats them as equivalent (shared uppercase Σ).
        if (lower == 0x3C2) {
            return 0x3C3;
        }
        return lower;
    }

    private static boolean containsSupplementary(CharSequence text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (Character.isHighSurrogate(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSupplementary(char[] text, int len) {
        for (int i = 0; i < len; i++) {
            if (Character.isHighSurrogate(text[i])) {
                return true;
            }
        }
        return false;
    }

    private void addKeyword(String word) {
        // Same normalization order as findMatches(): NFKC first, then collapse
        // space runs; folding and collapsing commute (spaces have no case).
        String normalized = collapseWhitespace(Normalizer.normalize(word, Normalizer.Form.NFKC));
        StringBuilder sb = new StringBuilder(normalized.length());
        int len = normalized.length();
        for (int i = 0; i < len; ) {
            int codePoint = normalized.codePointAt(i);
            int folded = foldCodePoint(codePoint, caseSensitive);
            sb.append(Character.toChars(folded));
            i += Character.charCount(codePoint);
        }
        String key = collapseWhitespace(sb.toString());
        if (key.isEmpty()) {
            return;
        }
        char firstChar = key.charAt(0);
        TrieNode curr;
        if (firstChar < 128) {
            curr = asciiRoot[firstChar];
            if (curr == null) {
                curr = new TrieNode(firstChar);
                asciiRoot[firstChar] = curr;
            }
        } else {
            if (nonAsciiRoot == null) {
                nonAsciiRoot = new TrieNode('\0');
            }
            curr = nonAsciiRoot.getOrCreateChild(firstChar);
        }

        for (int i = 1; i < key.length(); i++) {
            curr = curr.getOrCreateChild(key.charAt(i));
        }
        if (curr.keywords == null) {
            curr.keywords = new ArrayList<>(1);
        }
        curr.keywords.add(word);
        // Whole-word boundaries mirror PCRE2 \b: each side is enforced only
        // when the corresponding keyword edge is a word character. Keywords
        // starting or ending with non-word characters (e.g. "#1234", "(555)")
        // must remain candidates regardless of the adjacent text. Edge flags
        // depend only on the shared folded key.
        curr.keywordStartsWithWordChar = isWordChar(key.charAt(0));
        curr.keywordEndsWithWordChar = isWordChar(key.charAt(key.length() - 1));
        if (!curr.keywordStartsWithWordChar) {
            anyNonWordStartKeyword = true;
        }
    }

    private static boolean containsNonAscii(char[] text, int len) {
        for (int i = 0; i < len; i++) {
            if (text[i] >= 128) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNonAscii(CharSequence text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) >= 128) {
                return true;
            }
        }
        return false;
    }

    // Must stay in sync with isLiteralSpace() in jpdfium_redact_pattern.cpp: the
    // native matcher lets a space in a keyword match any whitespace run (a phrase
    // wrapped onto the next line has a line break there), so the prefilter has to
    // treat every run as one space or it drops those pages. The set is exactly
    // Unicode White_Space, which is what PCRE2 \s matches under PCRE2_UCP.
    private static boolean isKeywordSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
                || c == '\u0085' || c == '\u00A0' || c == '\u1680'
                || (c >= '\u2000' && c <= '\u200A') || c == '\u2028' || c == '\u2029'
                || c == '\u202F' || c == '\u205F' || c == '\u3000';
    }

    private static boolean needsCollapse(char[] text, int len) {
        for (int i = 0; i < len; i++) {
            char c = text[i];
            if (isKeywordSpace(c) && (c != ' ' || (i + 1 < len && isKeywordSpace(text[i + 1])))) {
                return true;
            }
        }
        return false;
    }

    private static boolean needsCollapse(CharSequence text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (isKeywordSpace(c) && (c != ' ' || (i + 1 < len && isKeywordSpace(text.charAt(i + 1))))) {
                return true;
            }
        }
        return false;
    }

    // Keywords and page text must be normalized in the same order: NFKC first
    // (compatibility forms and compatibility spaces fold to their canonical
    // spelling), then every space run collapses to one ASCII space. NFKC maps
    // the compatibility spaces to ASCII space, so collapsing first would leave
    // runs (e.g. "John \u2003Doe") that the folded trie key no longer matches.
    private static String normalize(CharSequence text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return needsCollapse(normalized) ? collapseWhitespace(normalized) : normalized;
    }

    private static String collapseWhitespace(CharSequence text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean inSpace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isKeywordSpace(c)) {
                if (!inSpace) {
                    sb.append(' ');
                }
                inSpace = true;
                continue;
            }
            inSpace = false;
            sb.append(c);
        }
        return sb.toString();
    }

    void findMatches(char[] text, int len, boolean wholeWord, Set<String> out) {
        if (text == null || len == 0 || keywordCount == 0) {
            return;
        }
        if (!caseSensitive && needsConservativeFallback && containsSupplementary(text, len)) {
            out.addAll(allKeywords);
            return;
        }
        // Native matches the raw page text, so scan the collapsed raw text as
        // well: NFKC can compose "cafe\u0301" into "caf\u00E9" and would otherwise
        // lose the native match for "cafe".
        if (needsCollapse(text, len)) {
            scanCharSequence(collapseWhitespace(new String(text, 0, len)), wholeWord, out);
        } else {
            scanCharArray(text, len, wholeWord, out);
        }
        if (containsNonAscii(text, len)) {
            scanNormalized(new String(text, 0, len), wholeWord, out);
        }
    }

    void findMatches(CharSequence text, boolean wholeWord, Set<String> out) {
        if (text == null || text.isEmpty() || keywordCount == 0) {
            return;
        }
        if (!caseSensitive && needsConservativeFallback && containsSupplementary(text)) {
            out.addAll(allKeywords);
            return;
        }
        scanCharSequence(needsCollapse(text) ? collapseWhitespace(text) : text, wholeWord, out);
        if (containsNonAscii(text)) {
            scanNormalized(text, wholeWord, out);
        }
    }

    private void scanNormalized(CharSequence text, boolean wholeWord, Set<String> out) {
        String normalized = normalize(text);
        if (!caseSensitive && needsConservativeFallback && containsSupplementary(normalized)) {
            out.addAll(allKeywords);
            return;
        }
        scanCharSequence(normalized, wholeWord, out);
    }

    private void scanCharArray(char[] text, int len, boolean wholeWord, Set<String> out) {
        for (int i = 0; i < len; i++) {
            // Fast path: inside a word run only non-word-start keywords can
            // match, so skip the trie walk unless such keywords exist.
            if (wholeWord && i > 0 && isWordChar(text[i - 1]) && !anyNonWordStartKeyword) {
                continue;
            }

            int codePoint = Character.codePointAt(text, i, len);
            int folded = foldCodePoint(codePoint, caseSensitive);

            if (folded <= 0xFFFF) {
                char fc0 = (char) folded;
                TrieNode node = lookupFirst(fc0);
                if (node == null) {
                    continue;
                }
                int charLen = Character.charCount(codePoint);
                if (charLen > 1) {
                    if (node.keywords != null && checkLeftBound(text, i, wholeWord, node)
                            && checkRightBound(text, i + charLen, len, wholeWord, node)) {
                        out.addAll(node.keywords);
                    }
                    scanContinuationCharArray(text, len, i, i + charLen, wholeWord, node, out);
                    continue;
                }

                if (node.keywords != null && checkLeftBound(text, i, wholeWord, node)
                        && checkRightBound(text, i + 1, len, wholeWord, node)) {
                    out.addAll(node.keywords);
                }

                TrieNode curr = node;
                for (int j = i + 1; j < len; j++) {
                    char cj = text[j];
                    if (!caseSensitive && Character.isSurrogate(cj)) {
                        break;
                    }
                    int foldedJ = caseSensitive ? cj : foldCodePoint(cj, false);
                    curr = curr.findChild((char) foldedJ);
                    if (curr == null) {
                        break;
                    }
                    if (curr.keywords != null && checkLeftBound(text, i, wholeWord, curr)
                            && checkRightBound(text, j + 1, len, wholeWord, curr)) {
                        out.addAll(curr.keywords);
                    }
                }
                continue;
            }

            char[] foldedChars = Character.toChars(folded);
            TrieNode node = lookupFirst(foldedChars[0]);
            if (node == null) {
                continue;
            }
            int charLen = Character.charCount(codePoint);
            TrieNode curr = node;
            boolean ok = true;
            for (int k = 1; k < foldedChars.length; k++) {
                curr = curr.findChild(foldedChars[k]);
                if (curr == null) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            if (curr.keywords != null && checkLeftBound(text, i, wholeWord, curr)
                    && checkRightBound(text, i + charLen, len, wholeWord, curr)) {
                out.addAll(curr.keywords);
            }
            scanContinuationCharArray(text, len, i, i + charLen, wholeWord, curr, out);
        }
    }

    private void scanContinuationCharArray(char[] text, int len, int entry, int start, boolean wholeWord,
            TrieNode node, Set<String> out) {
        TrieNode curr = node;
        int j = start;
        while (j < len) {
            int codePoint = Character.codePointAt(text, j, len);
            int folded = foldCodePoint(codePoint, caseSensitive);
            if (folded <= 0xFFFF) {
                curr = curr.findChild((char) folded);
                if (curr == null) {
                    return;
                }
            } else {
                char[] foldedChars = Character.toChars(folded);
                for (char fc : foldedChars) {
                    curr = curr.findChild(fc);
                    if (curr == null) {
                        return;
                    }
                }
            }
            j += Character.charCount(codePoint);
            if (curr.keywords != null && checkLeftBound(text, entry, wholeWord, curr)
                    && checkRightBound(text, j, len, wholeWord, curr)) {
                out.addAll(curr.keywords);
            }
        }
    }

    private TrieNode lookupFirst(char folded) {
        if (folded < 128) {
            return asciiRoot[folded];
        }
        if (nonAsciiRoot != null) {
            return nonAsciiRoot.findChild(folded);
        }
        return null;
    }

    private void scanCharSequence(CharSequence text, boolean wholeWord, Set<String> out) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (wholeWord && i > 0 && isWordChar(text.charAt(i - 1)) && !anyNonWordStartKeyword) {
                continue;
            }

            int codePoint = Character.codePointAt(text, i);
            int folded = foldCodePoint(codePoint, caseSensitive);

            if (folded <= 0xFFFF) {
                char fc0 = (char) folded;
                TrieNode node = lookupFirst(fc0);
                if (node == null) {
                    continue;
                }
                int charLen = Character.charCount(codePoint);
                if (charLen > 1) {
                    if (node.keywords != null && checkLeftBound(text, i, wholeWord, node)
                            && checkRightBound(text, i + charLen, len, wholeWord, node)) {
                        out.addAll(node.keywords);
                    }
                    scanContinuationCharSequence(text, len, i, i + charLen, wholeWord, node, out);
                    continue;
                }

                if (node.keywords != null && checkLeftBound(text, i, wholeWord, node)
                    && checkRightBound(text, i + 1, len, wholeWord, node)) {
                    out.addAll(node.keywords);
                }

                TrieNode curr = node;
                for (int j = i + 1; j < len; j++) {
                    char cj = text.charAt(j);
                    if (!caseSensitive && Character.isSurrogate(cj)) {
                        break;
                    }
                    int foldedJ = caseSensitive ? cj : foldCodePoint(cj, false);
                    curr = curr.findChild((char) foldedJ);
                    if (curr == null) {
                        break;
                    }
                    if (curr.keywords != null && checkLeftBound(text, i, wholeWord, curr)
                            && checkRightBound(text, j + 1, len, wholeWord, curr)) {
                        out.addAll(curr.keywords);
                    }
                }
                continue;
            }

            char[] foldedChars = Character.toChars(folded);
            TrieNode node = lookupFirst(foldedChars[0]);
            if (node == null) {
                continue;
            }
            int charLen = Character.charCount(codePoint);
            TrieNode curr = node;
            boolean ok = true;
            for (int k = 1; k < foldedChars.length; k++) {
                curr = curr.findChild(foldedChars[k]);
                if (curr == null) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            if (curr.keywords != null && checkLeftBound(text, i, wholeWord, curr)
                    && checkRightBound(text, i + charLen, len, wholeWord, curr)) {
                out.addAll(curr.keywords);
            }
            scanContinuationCharSequence(text, len, i, i + charLen, wholeWord, curr, out);
        }
    }

    private void scanContinuationCharSequence(CharSequence text, int len, int entry, int start,
            boolean wholeWord, TrieNode node, Set<String> out) {
        TrieNode curr = node;
        int j = start;
        while (j < len) {
            int codePoint = Character.codePointAt(text, j);
            int folded = foldCodePoint(codePoint, caseSensitive);
            if (folded <= 0xFFFF) {
                curr = curr.findChild((char) folded);
                if (curr == null) {
                    return;
                }
            } else {
                char[] foldedChars = Character.toChars(folded);
                for (char fc : foldedChars) {
                    curr = curr.findChild(fc);
                    if (curr == null) {
                        return;
                    }
                }
            }
            j += Character.charCount(codePoint);
            if (curr.keywords != null && checkLeftBound(text, entry, wholeWord, curr)
                    && checkRightBound(text, j, len, wholeWord, curr)) {
                out.addAll(curr.keywords);
            }
        }
    }

    private static boolean checkLeftBound(char[] text, int entryIdx, boolean wholeWord, TrieNode node) {
        if (!wholeWord || !node.keywordStartsWithWordChar || entryIdx <= 0) {
            return true;
        }
        return !isWordChar(text[entryIdx - 1]);
    }

    private static boolean checkLeftBound(CharSequence text, int entryIdx, boolean wholeWord, TrieNode node) {
        if (!wholeWord || !node.keywordStartsWithWordChar || entryIdx <= 0) {
            return true;
        }
        return !isWordChar(text.charAt(entryIdx - 1));
    }

    private static boolean checkRightBound(char[] text, int nextIdx, int len, boolean wholeWord,
            TrieNode node) {
        if (!wholeWord || !node.keywordEndsWithWordChar || nextIdx >= len) {
            return true;
        }
        return !isWordChar(text[nextIdx]);
    }

    private static boolean checkRightBound(CharSequence text, int nextIdx, int len, boolean wholeWord,
            TrieNode node) {
        if (!wholeWord || !node.keywordEndsWithWordChar || nextIdx >= len) {
            return true;
        }
        return !isWordChar(text.charAt(nextIdx));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
