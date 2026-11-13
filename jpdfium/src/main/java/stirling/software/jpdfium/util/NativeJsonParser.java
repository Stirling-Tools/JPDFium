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
        List<Map<String, String>> result = new ArrayList<>();
        if (json == null || "[]".equals(json)) return result;

        int length = json.length();
        int pos = 0;
        while (pos < length) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            int bodyStart = objStart + 1;
            pos = objEnd + 1;

            Map<String, String> fields = new LinkedHashMap<>();
            // Hand-split on commas outside quotes, reading the object body in place so
            // no per-object substring is materialised.
            int pairStart = bodyStart;
            boolean inQuotes = false;
            for (int i = bodyStart; i <= objEnd; i++) {
                char c = i < objEnd ? json.charAt(i) : ',';
                if (c == '\\' && inQuotes && i + 1 < objEnd) {
                    i++;
                    continue;
                }
                if (c == '"') inQuotes = !inQuotes;
                if ((c == ',' && !inQuotes) || i == objEnd) {
                    int colon = json.indexOf(':', pairStart);
                    if (colon >= 0 && colon < i) {
                        fields.put(keyText(json, pairStart, colon), valueText(json, colon + 1, i));
                    }
                    pairStart = i + 1;
                }
            }
            result.add(fields);
        }
        return result;
    }

    /** Extract an int field from a single JSON object string. Returns 0 if missing. */
    public static int intField(String json, String key) {
        int index = fieldValueOffset(json, key);
        if (index < 0) return 0;
        int length = json.length();
        boolean negative = json.charAt(index) == '-';
        if (negative) index++;
        long limit = negative ? 2_147_483_648L : 2_147_483_647L;
        long value = 0;
        boolean digits = false;
        while (index < length) {
            char c = json.charAt(index);
            if (c < '0' || c > '9') break;
            value = value * 10 + (c - '0');
            if (value > limit) throw new NumberFormatException("integer overflow");
            digits = true;
            index++;
        }
        if (!digits) return 0;
        return (int) (negative ? -value : value);
    }

    /** Extract a long field from a single JSON object string. Returns 0 if missing. */
    public static long longField(String json, String key) {
        int index = fieldValueOffset(json, key);
        if (index < 0) return 0L;
        int length = json.length();
        boolean negative = json.charAt(index) == '-';
        if (negative) index++;
        long value = 0L;
        boolean digits = false;
        while (index < length) {
            char c = json.charAt(index);
            if (c < '0' || c > '9') break;
            int digit = c - '0';
            if (negative) {
                if (value < (Long.MIN_VALUE + digit) / 10)
                    throw new NumberFormatException("long overflow");
                value = value * 10L - digit;
            } else {
                if (value > (Long.MAX_VALUE - digit) / 10)
                    throw new NumberFormatException("long overflow");
                value = value * 10L + digit;
            }
            digits = true;
            index++;
        }
        if (!digits) return 0L;
        return value;
    }

    /** Extract a boolean field from a single JSON object string. Returns false if missing. */
    public static boolean boolField(String json, String key) {
        int index = fieldValueOffset(json, key);
        return index >= 0 && json.startsWith("true", index);
    }

    /**
     * Extract a string field from a single JSON object string, decoding JSON
     * escapes. Returns "" if missing.
     */
    public static String stringField(String json, String key) {
        int index = fieldValueOffset(json, key);
        if (index < 0 || json.charAt(index) != '"') return "";

        int valueStart = index + 1;
        int length = json.length();
        int valueEnd = length;
        boolean escaped = false;
        for (int i = valueStart; i < length; i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                escaped = true;
                i++;
            } else if (c == '"') {
                valueEnd = i;
                break;
            }
        }
        if (!escaped) return json.substring(valueStart, valueEnd);

        StringBuilder out = new StringBuilder(valueEnd - valueStart);
        boolean pendingEscape = false;
        for (int i = valueStart; i < valueEnd; i++) {
            char c = json.charAt(i);
            if (pendingEscape) {
                switch (c) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (i + 4 < length) {
                            try {
                                out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
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
                pendingEscape = false;
            } else if (c == '\\') {
                pendingEscape = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Locate the offset of the value that follows {@code "key"}: in {@code json}.
     * The key must be quoted and followed by optional whitespace, a colon, and
     * optional whitespace. No lookup string is allocated.
     *
     * @return index of the first value character, or {@code -1} when absent
     */
    private static int fieldValueOffset(String json, String key) {
        if (json == null || key == null) return -1;
        int length = json.length();
        int keyLength = key.length();
        int quote = json.indexOf('"');
        while (quote >= 0) {
            int keyStart = quote + 1;
            int keyEnd = keyStart + keyLength;
            if (keyEnd < length
                    && json.charAt(keyEnd) == '"'
                    && json.regionMatches(keyStart, key, 0, keyLength)) {
                int i = keyEnd + 1;
                while (i < length && json.charAt(i) <= ' ') i++;
                if (i < length && json.charAt(i) == ':') {
                    i++;
                    while (i < length && json.charAt(i) <= ' ') i++;
                    return i < length ? i : -1;
                }
            }
            quote = json.indexOf('"', quote + 1);
        }
        return -1;
    }

    /**
     * Extract a key slice, dropping every quote character before trimming, which
     * matches the original {@code replace("\"", "").trim()} ordering.
     */
    private static String keyText(String json, int start, int end) {
        int quotes = 0;
        for (int i = start; i < end; i++) {
            if (json.charAt(i) == '"') quotes++;
        }
        if (quotes == 0) {
            int from = start;
            int to = end;
            while (from < to && json.charAt(from) <= ' ') from++;
            while (to > from && json.charAt(to - 1) <= ' ') to--;
            return json.substring(from, to);
        }
        char[] buffer = new char[end - start - quotes];
        int size = 0;
        for (int i = start; i < end; i++) {
            char c = json.charAt(i);
            if (c != '"') buffer[size++] = c;
        }
        int from = 0;
        int to = size;
        while (from < to && buffer[from] <= ' ') from++;
        while (to > from && buffer[to - 1] <= ' ') to--;
        return new String(buffer, from, to - from);
    }

    /**
     * Extract a value slice, trimming whitespace before dropping quote characters,
     * which matches the original {@code trim().replace("\"", "")} ordering.
     */
    private static String valueText(String json, int start, int end) {
        while (start < end && json.charAt(start) <= ' ') start++;
        while (end > start && json.charAt(end - 1) <= ' ') end--;
        int quotes = 0;
        for (int i = start; i < end; i++) {
            if (json.charAt(i) == '"') quotes++;
        }
        if (quotes == 0) return json.substring(start, end);
        char[] buffer = new char[end - start - quotes];
        int size = 0;
        for (int i = start; i < end; i++) {
            char c = json.charAt(i);
            if (c != '"') buffer[size++] = c;
        }
        return new String(buffer, 0, size);
    }
}
