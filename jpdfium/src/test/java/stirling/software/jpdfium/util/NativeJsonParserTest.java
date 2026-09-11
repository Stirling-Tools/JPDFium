package stirling.software.jpdfium.util;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
