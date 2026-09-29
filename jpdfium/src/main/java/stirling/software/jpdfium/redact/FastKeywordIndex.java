package stirling.software.jpdfium.redact;

import java.text.Normalizer;
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
        java.util.List<String> keywords;
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
        java.util.ArrayList<String> kept = new java.util.ArrayList<>();
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
        String normalized = Normalizer.normalize(word, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(normalized.length());
        int len = normalized.length();
        for (int i = 0; i < len; ) {
            int codePoint = normalized.codePointAt(i);
            int folded = foldCodePoint(codePoint, caseSensitive);
            sb.append(Character.toChars(folded));
            i += Character.charCount(codePoint);
        }
        String key = sb.toString();
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
            curr.keywords = new java.util.ArrayList<>(1);
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

    void findMatches(char[] text, int len, boolean wholeWord, Set<String> out) {
        if (text == null || len == 0 || keywordCount == 0) {
            return;
        }
        if (!caseSensitive && needsConservativeFallback && containsSupplementary(text, len)) {
            out.addAll(allKeywords);
            return;
        }
        scanCharArray(text, len, wholeWord, out);
        if (containsNonAscii(text, len)) {
            String normalized = Normalizer.normalize(new String(text, 0, len), Normalizer.Form.NFKC);
            if (!caseSensitive && containsSupplementary(normalized)) {
                out.addAll(allKeywords);
                return;
            }
            scanCharSequence(normalized, wholeWord, out);
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
        scanCharSequence(text, wholeWord, out);
        if (containsNonAscii(text)) {
            String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
            if (!caseSensitive && containsSupplementary(normalized)) {
                out.addAll(allKeywords);
                return;
            }
            scanCharSequence(normalized, wholeWord, out);
        }
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
            char[] foldedChars = Character.toChars(folded);
            TrieNode node = lookupFirst(foldedChars[0]);
            if (node == null) {
                continue;
            }
            int charLen = Character.charCount(codePoint);
            // For supplementary code points the trie holds surrogate pairs;
            // walk the remaining folded chars before matching continuations.
            if (foldedChars.length > 1 || charLen > 1) {
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
                continue;
            }

            if (node.keywords != null && checkLeftBound(text, i, wholeWord, node)
                    && checkRightBound(text, i + 1, len, wholeWord, node)) {
                out.addAll(node.keywords);
            }

            TrieNode curr = node;
            for (int j = i + 1; j < len; j++) {
                char cj = text[j];
                // Fold single BMP char; lone surrogates fall back to conservative path above.
                int foldedJ = caseSensitive ? cj : foldCodePoint(cj, false);
                // Supplementary text chars are handled via the code-point entry;
                // a BMP trie edge never matches a surrogate unit here.
                if (!caseSensitive && Character.isSurrogate(cj)) {
                    break;
                }
                curr = curr.findChild((char) foldedJ);
                if (curr == null) {
                    break;
                }
                if (curr.keywords != null && checkLeftBound(text, i, wholeWord, curr)
                        && checkRightBound(text, j + 1, len, wholeWord, curr)) {
                    out.addAll(curr.keywords);
                }
            }
        }
    }

    private void scanContinuationCharArray(char[] text, int len, int entry, int start, boolean wholeWord,
            TrieNode node, Set<String> out) {
        TrieNode curr = node;
        int j = start;
        while (j < len) {
            int codePoint = Character.codePointAt(text, j, len);
            int folded = foldCodePoint(codePoint, caseSensitive);
            char[] foldedChars = Character.toChars(folded);
            for (char fc : foldedChars) {
                curr = curr.findChild(fc);
                if (curr == null) {
                    return;
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
            char[] foldedChars = Character.toChars(folded);
            TrieNode node = lookupFirst(foldedChars[0]);
            if (node == null) {
                continue;
            }
            int charLen = Character.charCount(codePoint);
            if (foldedChars.length > 1 || charLen > 1) {
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
        }
    }

    private void scanContinuationCharSequence(CharSequence text, int len, int entry, int start,
            boolean wholeWord, TrieNode node, Set<String> out) {
        TrieNode curr = node;
        int j = start;
        while (j < len) {
            int codePoint = Character.codePointAt(text, j);
            int folded = foldCodePoint(codePoint, caseSensitive);
            char[] foldedChars = Character.toChars(folded);
            for (char fc : foldedChars) {
                curr = curr.findChild(fc);
                if (curr == null) {
                    return;
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
