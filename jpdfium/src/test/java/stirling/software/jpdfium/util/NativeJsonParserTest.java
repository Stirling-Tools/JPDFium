package stirling.software.jpdfium.util;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void keepsColonsInsideValues() {
        List<Map<String, String>> rows =
                NativeJsonParser.parseArray("[{\"match\":\"a:b\",\"len\":3}]");
        assertEquals(1, rows.size());
        assertEquals("a:b", rows.get(0).get("match"));
        assertEquals("3", rows.get(0).get("len"));
    }

    @Test
    void parsesMultipleObjectsWithDifferentKeys() {
        List<Map<String, String>> rows =
                NativeJsonParser.parseArray(" [ {\"a\":\"x\"} , {\"b\":\"y\",\"c\":\"z\"} ] ");
        assertEquals(2, rows.size());
        assertEquals("x", rows.get(0).get("a"));
        assertEquals(1, rows.get(0).size());
        assertEquals("y", rows.get(1).get("b"));
        assertEquals("z", rows.get(1).get("c"));
    }

    @Test
    void trimsKeySpacesAfterRemovingQuotes() {
        List<Map<String, String>> rows = NativeJsonParser.parseArray("[{\" a \" : 1}]");
        assertEquals(1, rows.size());
        assertEquals("1", rows.get(0).get("a"));
    }

    @Test
    void returnsEmptyForNullOrEmptyArray() {
        assertTrue(NativeJsonParser.parseArray(null).isEmpty());
        assertTrue(NativeJsonParser.parseArray("[]").isEmpty());
        assertTrue(NativeJsonParser.parseArray("[   ]").isEmpty());
    }

    @Test
    void parsesPositiveInt() {
        assertEquals(42, NativeJsonParser.intField("{\"count\":42}", "count"));
    }

    @Test
    void parsesNegativeInt() {
        assertEquals(-17, NativeJsonParser.intField("{\"count\":-17}", "count"));
    }

    @Test
    void parsesZeroInt() {
        assertEquals(0, NativeJsonParser.intField("{\"count\":0}", "count"));
    }

    @Test
    void returnsZeroForMissingInt() {
        assertEquals(0, NativeJsonParser.intField("{\"other\":5}", "count"));
        assertEquals(0, NativeJsonParser.intField("{\"count\":}", "count"));
        assertEquals(0, NativeJsonParser.intField("{\"count\":\"7\"}", "count"));
        assertEquals(0, NativeJsonParser.intField(null, "count"));
    }

    @Test
    void parsesIntWithWhitespaceAroundColon() {
        assertEquals(11, NativeJsonParser.intField("{\"count\" :  11}", "count"));
    }

    @Test
    void parsesIntExtremes() {
        assertEquals(Integer.MAX_VALUE, NativeJsonParser.intField("{\"c\":2147483647}", "c"));
        assertEquals(Integer.MIN_VALUE, NativeJsonParser.intField("{\"c\":-2147483648}", "c"));
    }

    @Test
    void ignoresIntKeyEmbeddedInOtherKey() {
        assertEquals(9, NativeJsonParser.intField("{\"revision\":3,\"revisionIndex\":9}", "revisionIndex"));
    }

    @Test
    void ignoresIntKeyInsideStringValue() {
        assertEquals(
                7, NativeJsonParser.intField("{\"note\":\"the count: 5\",\"count\":7}", "count"));
        assertEquals(
                4, NativeJsonParser.intField("{\"note\":\"say \\\"count\\\":1\",\"count\":4}", "count"));
    }

    @Test
    void rejectsOverflowingIntegers() {
        assertThrows(NumberFormatException.class,
                () -> NativeJsonParser.intField("{\"c\":2147483648}", "c"));
        assertThrows(NumberFormatException.class,
                () -> NativeJsonParser.intField("{\"c\":-2147483649}", "c"));
        assertThrows(NumberFormatException.class,
                () -> NativeJsonParser.longField("{\"size\":9223372036854775808}", "size"));
        assertThrows(NumberFormatException.class,
                () -> NativeJsonParser.longField("{\"size\":-9223372036854775809}", "size"));
    }

    @Test
    void parsesLongLargerThanInt() {
        assertEquals(9_223_372_036_854_775_807L, NativeJsonParser.longField("{\"size\":9223372036854775807}", "size"));
        assertEquals(4_294_967_296L, NativeJsonParser.longField("{\"size\":4294967296}", "size"));
    }

    @Test
    void parsesNegativeLong() {
        assertEquals(-9_000_000_000L, NativeJsonParser.longField("{\"size\":-9000000000}", "size"));
    }

    @Test
    void returnsZeroForMissingLong() {
        assertEquals(0L, NativeJsonParser.longField("{\"other\":5}", "size"));
        assertEquals(0L, NativeJsonParser.longField("{\"size\":}", "size"));
        assertEquals(0L, NativeJsonParser.longField(null, "size"));
    }

    @Test
    void parsesBooleanTrue() {
        assertTrue(NativeJsonParser.boolField("{\"signed\":true}", "signed"));
        assertTrue(NativeJsonParser.boolField("{\"signed\" : true}", "signed"));
    }

    @Test
    void parsesBooleanFalse() {
        assertFalse(NativeJsonParser.boolField("{\"signed\":false}", "signed"));
        assertFalse(NativeJsonParser.boolField("{\"signed\" : false}", "signed"));
    }

    @Test
    void returnsFalseForMissingBoolean() {
        assertFalse(NativeJsonParser.boolField("{\"other\":true}", "signed"));
        assertFalse(NativeJsonParser.boolField("{\"signed\":1}", "signed"));
        assertFalse(NativeJsonParser.boolField(null, "signed"));
    }

    @Test
    void parsesUnescapedString() {
        assertEquals("hello", NativeJsonParser.stringField("{\"name\":\"hello\"}", "name"));
        assertEquals(
                "hello", NativeJsonParser.stringField("{\"name\" : \"hello\"}", "name"));
    }

    @Test
    void parsesEmptyAndMissingString() {
        assertEquals("", NativeJsonParser.stringField("{\"name\":\"\"}", "name"));
        assertEquals("", NativeJsonParser.stringField("{\"other\":\"x\"}", "name"));
        assertEquals("", NativeJsonParser.stringField("{\"name\":5}", "name"));
        assertEquals("", NativeJsonParser.stringField(null, "name"));
    }

    @Test
    void decodesStringEscapes() {
        assertEquals(
                "a\nb\tc\rd\be\ff",
                NativeJsonParser.stringField("{\"s\":\"a\\nb\\tc\\rd\\be\\ff\"}", "s"));
        assertEquals(
                "quote\"slash\\solidus/",
                NativeJsonParser.stringField("{\"s\":\"quote\\\"slash\\\\solidus\\/\"}", "s"));
        assertEquals("A\u00e9", NativeJsonParser.stringField("{\"s\":\"\\u0041\\u00e9\"}", "s"));
    }

    @Test
    void keepsStringCommasAndColons() {
        assertEquals(
                "a,b:c", NativeJsonParser.stringField("{\"s\":\"a,b:c\",\"n\":1}", "s"));
    }

    @Test
    void ignoresStringKeyInsideOtherValue() {
        assertEquals(
                "real",
                NativeJsonParser.stringField("{\"note\":\"name: \\\"fake\\\"\",\"name\":\"real\"}", "name"));
    }

    @Test
    void decodesUnterminatedString() {
        assertEquals("abc", NativeJsonParser.stringField("{\"s\":\"abc", "s"));
    }
}
