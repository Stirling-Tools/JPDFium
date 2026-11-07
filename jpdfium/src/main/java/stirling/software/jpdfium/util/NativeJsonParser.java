package stirling.software.jpdfium.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight parser for the simple JSON arrays returned by native bridge functions.
 *
 * <p>Native functions return flat JSON arrays of objects with string/int/bool values.
 * This avoids pulling in a full JSON library for these trivial shapes.
 */
public final class NativeJsonParser {

    private NativeJsonParser() {}

    /**
     * Parse a JSON array of flat objects into a list of key-value maps.
     * Handles: {@code [{"start":0,"end":5,"match":"Hello"}, ...]}
     *
     * @return list of maps, one per JSON object. Values are raw strings (unquoted).
     */
    public static List<Map<String, String>> parseArray(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return List.of();
        List<Map<String, String>> result = new ArrayList<>();

        int pos = 0;
        while (pos < json.length()) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findObjectEnd(json, objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart + 1, objEnd);
            pos = objEnd + 1;

            Map<String, String> fields = new LinkedHashMap<>();
            // Hand-split on commas outside quotes: avoids per-object regex Matcher/String[] churn.
            int pairStart = 0;
            boolean inQuotes = false;
            for (int i = 0; i <= obj.length(); i++) {
                char c = i < obj.length() ? obj.charAt(i) : ',';
                if (c == '\\' && inQuotes && i + 1 < obj.length()) {
                    i++;
                    continue;
                }
                if (c == '"') inQuotes = !inQuotes;
                if ((c == ',' && !inQuotes) || i == obj.length()) {
                    String pair = obj.substring(pairStart, i);
                    pairStart = i + 1;
                    int colon = pair.indexOf(':');
                    if (colon < 0) continue;
                    String key = pair.substring(0, colon).replace("\"", "").trim();
                    String val = pair.substring(colon + 1).trim().replace("\"", "");
                    fields.put(key, val);
                }
            }
            result.add(fields);
        }
        return result;
    }

    /** Extract an int field from a single JSON object string. Returns 0 if missing. */
    public static int intField(String json, String key) {
        int idx = indexOfFieldValue(json, key);
        if (idx < 0) return 0;
        int end = idx;
        while (end < json.length() && (json.charAt(end) == '-' || Character.isDigit(json.charAt(end)))) end++;
        if (end == idx) return 0;
        // Out-of-range values propagate: silently mapping overflow to 0 would
        // turn a huge offset into a valid-looking one.
        return Integer.parseInt(json, idx, end, 10);
    }

    /** Extract a long field from a single JSON object string. Returns 0 if missing. */
    public static long longField(String json, String key) {
        int idx = indexOfFieldValue(json, key);
        if (idx < 0) return 0L;
        int end = idx;
        while (end < json.length() && (json.charAt(end) == '-' || Character.isDigit(json.charAt(end)))) end++;
        if (end == idx) return 0L;
        try {
            return Long.parseLong(json, idx, end, 10);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** Extract a boolean field from a single JSON object string. Returns false if missing. */
    public static boolean boolField(String json, String key) {
        int idx = indexOfFieldValue(json, key);
        return idx >= 0 && json.startsWith("true", idx);
    }

    /**
     * Extract a string field from a single JSON object string, decoding JSON
     * escapes. Returns "" if missing.
     */
    public static String stringField(String json, String key) {
        int idx = indexOfFieldValue(json, key);
        if (idx < 0 || idx >= json.length() || json.charAt(idx) != '"') return "";
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = idx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            try {
                                out.append((char) Integer.parseInt(json, i + 1, i + 5, 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                out.append("\\u");
                            }
                        } else {
                            out.append("\\u");
                        }
                    }
                    default -> out.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Find the {@code '}'} that closes the object opened at {@code objStart}, skipping braces
     * inside string literals (including escapes) and counting nested objects. Returns -1 when the
     * object is unterminated.
     */
    private static int findObjectEnd(String json, int objStart) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = objStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Index of the first character after the {@code "key":} member's colon, or -1 when the key is
     * absent. String literals are walked quote/escape-aware, so a string value that merely contains
     * {@code "key":} text is never mistaken for the member, and whitespace after the colon is
     * skipped.
     */
    private static int indexOfFieldValue(String json, String key) {
        int from = 0;
        while (from < json.length()) {
            int open = json.indexOf('"', from);
            if (open < 0) return -1;
            int close = findStringEnd(json, open);
            if (close < 0) return -1;
            if (close - open - 1 == key.length()
                    && json.regionMatches(open + 1, key, 0, key.length())) {
                int colon = close + 1;
                while (colon < json.length() && Character.isWhitespace(json.charAt(colon))) colon++;
                if (colon < json.length() && json.charAt(colon) == ':') {
                    int value = colon + 1;
                    while (value < json.length() && Character.isWhitespace(json.charAt(value))) value++;
                    return value;
                }
            }
            from = close + 1;
        }
        return -1;
    }

    /** Index of the closing quote of the string literal opened at {@code open}, or -1. */
    private static int findStringEnd(String json, int open) {
        boolean escaped = false;
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }
}
