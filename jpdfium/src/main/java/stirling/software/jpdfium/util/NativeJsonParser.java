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
        if (json == null || json.equals("[]")) return result;

        int pos = 0;
        while (pos < json.length()) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart + 1, objEnd);
            pos = objEnd + 1;

            Map<String, String> fields = new LinkedHashMap<>();
            for (String pair : obj.split(",(?=\")")) {
                int colon = pair.indexOf(':');
                if (colon < 0) continue;
                String key = pair.substring(0, colon).replace("\"", "").trim();
                String val = pair.substring(colon + 1).trim().replace("\"", "");
                fields.put(key, val);
            }
            result.add(fields);
        }
        return result;
    }

    /** Extract an int field from a single JSON object string. Returns 0 if missing. */
    public static int intField(String json, String key) {
        String needle = "\"" + key + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) return 0;
        idx += needle.length();
        int end = idx;
        while (end < json.length() && (json.charAt(end) == '-' || Character.isDigit(json.charAt(end)))) end++;
        if (end == idx) return 0;
        return Integer.parseInt(json.substring(idx, end));
    }

    /** Extract a boolean field from a single JSON object string. Returns false if missing. */
    public static boolean boolField(String json, String key) {
        String needle = "\"" + key + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) return false;
        return json.indexOf("true", idx + needle.length()) == idx + needle.length();
    }

    /** Extract a string field from a single JSON object string. Returns "" if missing. */
    public static String stringField(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return "";
        idx += needle.length();
        int end = json.indexOf('"', idx);
        return end > idx ? json.substring(idx, end) : "";
    }
}
