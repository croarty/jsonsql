package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValuesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @ParameterizedTest(name = "{0} equals {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "1                    | 1",
        "1                    | 1.0",
        "1                    | 1.000",
        "100                  | 1e2",
        "0                    | -0.0",
        "0.1                  | 0.10",
        "123456789012345678901234567890 | 123456789012345678901234567890",
        "\"abc\"              | \"abc\"",
        "\"\"                 | \"\"",
        "true                 | true",
        "true                 | \"true\"",
        "false                | \"false\"",
        "[1, 2]               | [1,2]",
        "{\"a\": 1}           | {\"a\":1}",
    })
    void equalValues(String a, String b) throws Exception {
        assertTrue(Values.equal(json(a), json(b)));
        assertTrue(Values.equal(json(b), json(a)));
        assertEquals(Values.identity(json(a)), Values.identity(json(b)));
    }

    @ParameterizedTest(name = "{0} is less than {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        // Numbers compare exactly, with no tolerance and no loss for large values
        "1                    | 2",
        "1                    | 1.00001",
        "-1                   | 0",
        "1.5                  | 2",
        "9007199254740992     | 9007199254740993",
        "123456789012345678901234567890 | 123456789012345678901234567891",
        "9223372036854775807  | 9223372036854775808",
        "1e300                | 123456789012345678901234567890e300",
        // Text compares by character, and never as a number
        "\"10\"               | \"9\"",
        "\"1\"                | \"1.0\"",
        "\"ABC\"              | \"abc\"",
        "\"\"                 | \" \"",
        "\"abc\"              | \"abd\"",
        // Every number comes before every text value
        "999                  | \"1\"",
        "1                    | \"\"",
        "1                    | \"1\"",
        "1e300                | \"-5\"",
        // A boolean compares as the text true or false
        "false                | true",
        "\"false\"            | true",
        "false                | \"true\"",
        "\"apple\"            | true",
        "true                 | \"zebra\"",
        "true                 | \"truth\"",
        "\"TRUE\"             | true",
        "1                    | true",
        "0                    | false",
        // Arrays and objects come last
        "999                  | []",
        "\"zzz\"              | []",
        "true                 | {}",
        "[1]                  | [2]",
        "[]                   | {}",
    })
    void orderedValues(String smaller, String larger) throws Exception {
        assertTrue(Values.compare(json(smaller), json(larger)) < 0);
        assertTrue(Values.compare(json(larger), json(smaller)) > 0);
        assertFalse(Values.equal(json(smaller), json(larger)));
        assertNotEquals(Values.identity(json(smaller)), Values.identity(json(larger)));
    }

    @Test
    void nonFiniteNumbersHaveAnOrder() {
        JsonNode nan = DoubleNode.valueOf(Double.NaN);
        JsonNode infinity = DoubleNode.valueOf(Double.POSITIVE_INFINITY);
        JsonNode negativeInfinity = DoubleNode.valueOf(Double.NEGATIVE_INFINITY);
        JsonNode huge = DecimalNode.valueOf(new BigDecimal("1e400"));

        assertTrue(Values.compare(negativeInfinity, huge) < 0);
        assertTrue(Values.compare(huge, infinity) < 0);
        assertTrue(Values.compare(infinity, nan) < 0);
        assertTrue(Values.equal(nan, nan));
        assertEquals(Values.identity(infinity), Values.identity(DoubleNode.valueOf(Double.POSITIVE_INFINITY)));
        assertNotEquals(Values.identity(infinity), Values.identity(negativeInfinity));
        assertNotEquals(Values.identity(infinity), Values.identity(huge));
    }

    @Test
    void identityMatchesEqualityForEveryPair() throws Exception {
        List<JsonNode> values = new ArrayList<>();
        for (String text : List.of("1", "1.0", "1.00", "1e0", "10", "1E+1", "0", "-0.0", "0.0", "-1", "1.5", "2.5e-3", "0.0025",
                "9007199254740993", "9007199254740992", "123456789012345678901234567890", "1e30",
                "\"1\"", "\"1.0\"", "\" 1 \"", "\"n1\"", "\"s1\"", "\"abc\"", "\"ABC\"", "\"\"", "\"true\"", "\"false\"", "\"null\"",
                "true", "false", "[]", "[1]", "[1.0]", "{}", "{\"a\": 1}", "\"[]\"", "\"{}\"")) {
            values.add(json(text));
        }
        values.add(DoubleNode.valueOf(Double.NaN));
        values.add(DoubleNode.valueOf(Double.POSITIVE_INFINITY));

        for (JsonNode a : values) {
            for (JsonNode b : values) {
                assertEquals(Values.equal(a, b), Values.identity(a).equals(Values.identity(b)), a + " and " + b);
                assertEquals(Integer.signum(Values.compare(a, b)), -Integer.signum(Values.compare(b, a)), a + " and " + b);
            }
        }
    }

    @Test
    void comparisonIsTransitive() throws Exception {
        List<JsonNode> values = new ArrayList<>();
        for (String text : List.of("-1", "0", "1", "1.0", "1.5", "10", "\"\"", "\"1\"", "\"10\"", "\"9\"", "\"false\"", "false",
                "\"true\"", "true", "\"truth\"", "\"zebra\"", "[]", "{}")) {
            values.add(json(text));
        }
        for (JsonNode a : values) {
            for (JsonNode b : values) {
                for (JsonNode c : values) {
                    if (Values.compare(a, b) <= 0 && Values.compare(b, c) <= 0) {
                        assertTrue(Values.compare(a, c) <= 0, a + " <= " + b + " <= " + c);
                    }
                }
            }
        }
    }

    @Test
    void nullMissingAndAbsentAreAllNull() throws Exception {
        assertTrue(Values.isNull(null));
        assertTrue(Values.isNull(json("null")));
        assertTrue(Values.isNull(MissingNode.getInstance()));
        assertFalse(Values.isNull(json("0")));
        assertFalse(Values.isNull(json("\"\"")));
        assertFalse(Values.isNull(json("false")));
        assertFalse(Values.isNull(json("\"null\"")));
    }
}
