package stirling.software.jpdfium.util;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativeJsonParserTest {

    @Test
    void parsesCompactObjects() {
        List<Map<String, String>> rows =
                NativeJsonParser.parseArray("[{\"start\":0,\"len\":5},{\"start\":7,\"len\":3}]");
        assertEquals(2, rows.size());
        assertEquals("0", rows.get(0).get("start"));
        assertEquals("5", rows.get(0).get("len"));
        assertEquals("7", rows.get(1).get("start"));
    }

    @Test
    void parsesSpacedMembers() {
        List<Map<String, String>> rows =
                NativeJsonParser.parseArray("[{\"a\": 1, \"b\": 2}]");
        assertEquals(1, rows.size());
        assertEquals("1", rows.get(0).get("a"));
        assertEquals("2", rows.get(0).get("b"));
    }

    @Test
    void keepsCommasInsideQuotes() {
        List<Map<String, String>> rows =
                NativeJsonParser.parseArray("[{\"match\":\"a,b\",\"len\":3}]");
        assertEquals(1, rows.size());
        assertEquals("a,b", rows.get(0).get("match"));
        assertEquals("3", rows.get(0).get("len"));
    }

    @Test
    void keepsEscapedQuotes() {
        List<Map<String, String>> rows =
                NativeJsonParser.parseArray("[{\"match\":\"a\\\"b,c\",\"len\":5}]");
        assertEquals(1, rows.size());
        assertEquals("a\\b,c", rows.get(0).get("match"));
    }

    @Test
    void keepsBracesInsideStrings() {
        List<Map<String, String>> rows = NativeJsonParser.parseArray(
                "[{\"match\":\"a}b\",\"len\":3},{\"match\":\"c{d\",\"len\":4}]");
        assertEquals(2, rows.size());
        assertEquals("a}b", rows.get(0).get("match"));
        assertEquals("3", rows.get(0).get("len"));
        assertEquals("c{d", rows.get(1).get("match"));
        assertEquals("4", rows.get(1).get("len"));
    }

    @Test
    void parsesNestedObjectsWithoutTruncatingTheRow() {
        List<Map<String, String>> rows =
                NativeJsonParser.parseArray("[{\"page\":{\"index\":2},\"len\":5},{\"len\":6}]");
        assertEquals(2, rows.size());
        assertEquals("5", rows.get(0).get("len"));
        assertEquals("6", rows.get(1).get("len"));
    }

    @Test
    void fieldLookupsSkipStringValuesThatLookLikeMembers() {
        String nested = "{\"text\":\"\\\"len\\\":9\",\"len\":7}";
        assertEquals(7, NativeJsonParser.intField(nested, "len"));
        assertEquals(7L, NativeJsonParser.longField(nested, "len"));

        String stringValue = "{\"note\":\"name:1\",\"name\":\"real\"}";
        assertEquals("real", NativeJsonParser.stringField(stringValue, "name"));

        String boolValue = "{\"reason\":\"signed:false\",\"signed\":true}";
        assertTrue(NativeJsonParser.boolField(boolValue, "signed"));
    }

    @Test
    void fieldLookupsTolerateWhitespaceAfterTheColon() {
        String json = "{\"text\":\"len:9\",\"len\" : 7}";
        assertEquals(7, NativeJsonParser.intField(json, "len"));
        assertEquals(7L, NativeJsonParser.longField(json, "len"));
    }
}
