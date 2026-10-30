package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The index must find exactly the rows a full comparison would find, in the same order.
 */
class JoinIndexTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The original row-by-row rule, kept here as the reference the index is checked against. */
    private static boolean matchesByComparison(JsonNode left, JsonNode right) {
        if (left == null || left.isNull() || right == null || right.isNull()) {
            return false;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.asDouble() == right.asDouble();
        }
        if (left.isNumber() || right.isNumber()) {
            Double l = parse(left.asText());
            Double r = parse(right.asText());
            if (l != null && r != null) {
                return l.doubleValue() == r.doubleValue();
            }
        }
        if (left.isBoolean() && right.isBoolean()) {
            return left.asBoolean() == right.asBoolean();
        }
        return left.asText().equals(right.asText());
    }

    private static Double parse(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<JsonNode> awkwardValues() throws Exception {
        List<JsonNode> values = new ArrayList<>();
        for (String json : List.of(
                "1", "1.0", "1.00", "2", "-1", "0", "0.0", "-0.0", "1.5", "1e0", "10", "1E+2", "100",
                "9007199254740993", "9007199254740992", "123456789012345678901234567890",
                "\"1\"", "\"1.0\"", "\" 1 \"", "\"01\"", "\"1e0\"", "\"-0\"", "\"0\"", "\"2\"", "\"1.5\"", "\"abc\"", "\"ABC\"",
                "\"\"", "\" \"", "\"true\"", "\"false\"", "\"null\"", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"", "\"1e999\"",
                "\"0x10\"", "\"1f\"", "\"1d\"", "true", "false", "null", "[]", "[1]", "{}", "{\"a\": 1}")) {
            values.add(MAPPER.readTree(json));
        }
        values.add(DoubleNode.valueOf(Double.NaN));
        values.add(DoubleNode.valueOf(Double.POSITIVE_INFINITY));
        values.add(DoubleNode.valueOf(Double.NEGATIVE_INFINITY));
        values.add(null);
        return values;
    }

    private static ObjectNode row(int id, JsonNode key) {
        ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("id", id);
        if (key != null) {
            row.set("k", key);
        }
        return row;
    }

    private static JoinIndex indexOf(List<JsonNode> rows) {
        return new JoinIndex(rows, row -> row.get("k"));
    }

    private static List<JsonNode> expected(JsonNode value, List<JsonNode> rows) {
        List<JsonNode> matched = new ArrayList<>();
        for (JsonNode row : rows) {
            if (matchesByComparison(value, row.get("k"))) {
                matched.add(row);
            }
        }
        return matched;
    }

    @Test
    void everyPairOfAwkwardValuesMatchesAsAFullComparisonWould() throws Exception {
        List<JsonNode> values = awkwardValues();
        List<JsonNode> rows = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            rows.add(row(i, values.get(i)));
        }
        JoinIndex index = indexOf(rows);

        for (JsonNode value : values) {
            assertEquals(expected(value, rows), index.matches(value), "joining on " + value);
        }
    }

    @Test
    void randomTablesGiveTheSameRowsInTheSameOrder() throws Exception {
        List<JsonNode> values = awkwardValues();
        Random random = new Random(42);

        for (int round = 0; round < 200; round++) {
            List<JsonNode> rows = new ArrayList<>();
            int size = random.nextInt(60);
            for (int i = 0; i < size; i++) {
                rows.add(row(i, values.get(random.nextInt(values.size()))));
            }
            JoinIndex index = indexOf(rows);

            for (JsonNode value : values) {
                assertEquals(expected(value, rows), index.matches(value), "round " + round + " joining on " + value);
            }
        }
    }

    @Test
    void matchesKeepTheirOriginalOrderAcrossKinds() throws Exception {
        List<JsonNode> rows = List.of(
            row(0, MAPPER.readTree("\"1\"")), row(1, MAPPER.readTree("1")), row(2, MAPPER.readTree("\"1.0\"")),
            row(3, MAPPER.readTree("1.0")), row(4, MAPPER.readTree("2")));

        List<JsonNode> matched = indexOf(rows).matches(MAPPER.readTree("1"));

        assertEquals(List.of(rows.get(0), rows.get(1), rows.get(2), rows.get(3)), matched);
    }

    @Test
    void numberAndTextRulesAreNotTransitive() throws Exception {
        JoinIndex index = indexOf(List.of(row(0, MAPPER.readTree("1")), row(1, MAPPER.readTree("\"1.0\""))));

        assertEquals(2, index.matches(MAPPER.readTree("1")).size());
        assertEquals(1, index.matches(MAPPER.readTree("\"1\"")).size());
        assertEquals(0, index.matches(MAPPER.readTree("\"1\"")).get(0).get("id").asInt());
    }

    @Test
    void anEmptyTableMatchesNothing() throws Exception {
        assertTrue(indexOf(List.of()).matches(MAPPER.readTree("1")).isEmpty());
    }

    @Test
    void nullAndMissingMatchNothing() throws Exception {
        JoinIndex index = indexOf(List.of(row(0, MAPPER.readTree("null")), row(1, null)));

        assertTrue(index.matches(null).isEmpty());
        assertTrue(index.matches(MAPPER.readTree("null")).isEmpty());
    }
}
