package stirling.software.jpdfium.spi;

import java.nio.charset.StandardCharsets;

/**
 * Minimal SVG content sniffing shared by the {@link javax.imageio} reader SPI.
 * <p>{@code ImageIO} probes the reader stack with the raw leading bytes, so this
 */
final class SvgImageFormat {

    private SvgImageFormat() {}

    /**
     * True when {@code prefix} starts with an SVG root element, tolerating a
     * UTF-8/UTF-16 byte-order mark, the XML declaration, comments and a doctype.
     */
    static boolean looksLikeSvg(byte[] prefix) {
        if (prefix == null || prefix.length == 0) {
            return false;
        }
        // UTF-16 BOMs would need real decoding; SVG-in-the-wild is UTF-8, and a
        // false negative only means ImageIO falls through to the next reader.
        if (prefix.length >= 3
                && (prefix[0] & 0xFF) == 0xEF
                && (prefix[1] & 0xFF) == 0xBB
                && (prefix[2] & 0xFF) == 0xBF) {
            prefix = java.util.Arrays.copyOfRange(prefix, 3, prefix.length);
        }
        String text = new String(prefix, StandardCharsets.UTF_8);
        int i = skipProlog(text);
        String root = rootElementName(text, i);
        if (root == null) {
            return false;
        }
        // A namespace prefix ("<svg:svg>") is just as valid as the bare element.
        return root.equalsIgnoreCase("svg") || root.toLowerCase(java.util.Locale.ROOT).endsWith(":svg");
    }

    /** Advance past whitespace, the XML declaration, comments and doctypes. */
    private static int skipProlog(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                i++;
            } else if (text.startsWith("<?", i)) {
                int end = text.indexOf("?>", i);
                if (end < 0) {
                    return text.length();
                }
                i = end + 2;
            } else if (text.startsWith("<!--", i)) {
                int end = text.indexOf("-->", i);
                if (end < 0) {
                    return text.length();
                }
                i = end + 3;
            } else if (text.startsWith("<!", i)) {
                int end = text.indexOf('>', i);
                if (end < 0) {
                    return text.length();
                }
                i = end + 1;
            } else {
                return i;
            }
        }
        return i;
    }

    /** Element name of the first start tag at or after {@code from}, or null. */
    private static String rootElementName(String text, int from) {
        int lt = text.indexOf('<', from);
        if (lt < 0 || lt + 1 >= text.length()) {
            return null;
        }
        int start = lt + 1;
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (Character.isWhitespace(c) || c == '>' || c == '/') {
                break;
            }
            end++;
        }
        if (end == start) {
            return null;
        }
        return text.substring(start, end);
    }
}
