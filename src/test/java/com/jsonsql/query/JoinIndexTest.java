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

    /** Comparing row by row, as the reference the index is checked against. */
    private static boolean matchesByComparison(JsonNode left, JsonNode right) {
        return !Values.isNull(left) && !Values.isNull(right) && Values.equal(left, right);
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
    void matchesKeepTheirOriginalOrder() throws Exception {
        List<JsonNode> rows = List.of(
            row(0, MAPPER.readTree("\"1\"")), row(1, MAPPER.readTree("1")), row(2, MAPPER.readTree("\"1.0\"")),
            row(3, MAPPER.readTree("1.0")), row(4, MAPPER.readTree("2")), row(5, MAPPER.readTree("1.00")));

        List<JsonNode> matched = indexOf(rows).matches(MAPPER.readTree("1"));

        assertEquals(List.of(rows.get(1), rows.get(3), rows.get(5)), matched);
    }

    @Test
    void aNumberNeverMatchesText() throws Exception {
        JoinIndex index = indexOf(List.of(row(0, MAPPER.readTree("1")), row(1, MAPPER.readTree("\"1\""))));

        assertEquals(List.of(0), ids(index.matches(MAPPER.readTree("1.0"))));
        assertEquals(List.of(1), ids(index.matches(MAPPER.readTree("\"1\""))));
        assertTrue(index.matches(MAPPER.readTree("\"1.0\"")).isEmpty());
    }

    @Test
    void aBooleanMatchesItsText() throws Exception {
        JoinIndex index = indexOf(List.of(row(0, MAPPER.readTree("true")), row(1, MAPPER.readTree("\"true\"")),
            row(2, MAPPER.readTree("false")), row(3, MAPPER.readTree("1"))));

        assertEquals(List.of(0, 1), ids(index.matches(MAPPER.readTree("true"))));
        assertEquals(List.of(0, 1), ids(index.matches(MAPPER.readTree("\"true\""))));
        assertEquals(List.of(2), ids(index.matches(MAPPER.readTree("\"false\""))));
    }

    private static List<Integer> ids(List<JsonNode> rows) {
        return rows.stream().map(row -> row.get("id").asInt()).toList();
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
