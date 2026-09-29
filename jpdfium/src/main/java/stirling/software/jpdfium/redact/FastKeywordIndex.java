package stirling.software.jpdfium.redact;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Set;

// Keyword index for page-level redaction candidate prefiltering.
final class FastKeywordIndex {

    private static final class Node {
        final char ch;
        String keyword;
        Node firstChild;
        Node nextSibling;

        Node(char ch) {
            this.ch = ch;
        }

        Node findChild(char target) {
            Node curr = firstChild;
            while (curr != null) {
                if (curr.ch == target) {
                    return curr;
                }
                curr = curr.nextSibling;
            }
            return null;
        }

        Node getOrCreateChild(char target) {
            Node existing = findChild(target);
            if (existing != null) {
                return existing;
            }
            Node newNode = new Node(target);
            newNode.nextSibling = firstChild;
            firstChild = newNode;
            return newNode;
        }
    }

    private final Node[] asciiRoot = new Node[128];
    private Node nonAsciiRoot;
    private final boolean caseSensitive;
    private final int keywordCount;

    private FastKeywordIndex(Collection<String> words, boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
        int count = 0;
        for (String word : words) {
            if (word == null || word.isEmpty()) {
                continue;
            }
            addKeyword(word);
            count++;
        }
        this.keywordCount = count;
    }

    public static FastKeywordIndex create(Collection<String> words, boolean caseSensitive) {
        return new FastKeywordIndex(words, caseSensitive);
    }

    private void addKeyword(String word) {
        String normalized = Normalizer.normalize(word, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            sb.append(caseSensitive ? ch : Character.toLowerCase(ch));
        }
        String key = sb.toString();
        char firstChar = key.charAt(0);
        Node curr;
        if (firstChar < 128) {
            curr = asciiRoot[firstChar];
            if (curr == null) {
                curr = new Node(firstChar);
                asciiRoot[firstChar] = curr;
            }
        } else {
            if (nonAsciiRoot == null) {
                nonAsciiRoot = new Node('\0');
            }
            curr = nonAsciiRoot.getOrCreateChild(firstChar);
        }

        for (int i = 1; i < key.length(); i++) {
            curr = curr.getOrCreateChild(key.charAt(i));
        }
        curr.keyword = word;
    }

    private static boolean containsNonAscii(char[] text, int len) {
        for (int i = 0; i < len; i++) {
            if (text[i] >= 128) return true;
        }
        return false;
    }

    private static boolean containsNonAscii(CharSequence text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) >= 128) return true;
        }
        return false;
    }

    public void findMatches(char[] text, int len, boolean wholeWord, Set<String> out) {
        if (text == null || len == 0 || keywordCount == 0) {
            return;
        }
        scanCharArray(text, len, wholeWord, out);
        if (containsNonAscii(text, len)) {
            String normalized = Normalizer.normalize(new String(text, 0, len), Normalizer.Form.NFKC);
            scanCharSequence(normalized, wholeWord, out);
        }
    }

    public void findMatches(CharSequence text, boolean wholeWord, Set<String> out) {
        if (text == null || text.isEmpty() || keywordCount == 0) {
            return;
        }
        scanCharSequence(text, wholeWord, out);
        if (containsNonAscii(text)) {
            String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
            scanCharSequence(normalized, wholeWord, out);
        }
    }

    private void scanCharArray(char[] text, int len, boolean wholeWord, Set<String> out) {
        for (int i = 0; i < len; i++) {
            if (wholeWord && i > 0 && isWordChar(text[i - 1])) {
                continue;
            }

            char c0 = text[i];
            char key0 = caseSensitive ? c0 : Character.toLowerCase(c0);
            Node node;
            if (key0 < 128) {
                node = asciiRoot[key0];
            } else if (nonAsciiRoot != null) {
                node = nonAsciiRoot.findChild(key0);
            } else {
                node = null;
            }

            if (node == null) {
                continue;
            }

            if (node.keyword != null && checkRightBound(text, i + 1, len, wholeWord)) {
                out.add(node.keyword);
            }

            Node curr = node;
            for (int j = i + 1; j < len; j++) {
                char cj = text[j];
                char keyJ = caseSensitive ? cj : Character.toLowerCase(cj);
                curr = curr.findChild(keyJ);
                if (curr == null) {
                    break;
                }
                if (curr.keyword != null && checkRightBound(text, j + 1, len, wholeWord)) {
                    out.add(curr.keyword);
                }
            }
        }
    }

    private void scanCharSequence(CharSequence text, boolean wholeWord, Set<String> out) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            if (wholeWord && i > 0 && isWordChar(text.charAt(i - 1))) {
                continue;
            }

            char c0 = text.charAt(i);
            char key0 = caseSensitive ? c0 : Character.toLowerCase(c0);
            Node node;
            if (key0 < 128) {
                node = asciiRoot[key0];
            } else if (nonAsciiRoot != null) {
                node = nonAsciiRoot.findChild(key0);
            } else {
                node = null;
            }

            if (node == null) {
                continue;
            }

            if (node.keyword != null && checkRightBound(text, i + 1, len, wholeWord)) {
                out.add(node.keyword);
            }

            Node curr = node;
            for (int j = i + 1; j < len; j++) {
                char cj = text.charAt(j);
                char keyJ = caseSensitive ? cj : Character.toLowerCase(cj);
                curr = curr.findChild(keyJ);
                if (curr == null) {
                    break;
                }
                if (curr.keyword != null && checkRightBound(text, j + 1, len, wholeWord)) {
                    out.add(curr.keyword);
                }
            }
        }
    }

    private static boolean checkRightBound(char[] text, int nextIdx, int len, boolean wholeWord) {
        if (!wholeWord || nextIdx >= len) {
            return true;
        }
        return !isWordChar(text[nextIdx]);
    }

    private static boolean checkRightBound(CharSequence text, int nextIdx, int len, boolean wholeWord) {
        if (!wholeWord || nextIdx >= len) {
            return true;
        }
        return !isWordChar(text.charAt(nextIdx));
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
