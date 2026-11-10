package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns rows into one row per group for GROUP BY and aggregates. Each grouped row holds the
 * group fields and aggregate results under their keys, plus any aliases, ready for HAVING,
 * ORDER BY and projection. Without GROUP BY, all rows form one group, even if there are none.
 *
 * Following SQL, NULL and missing values form one group, aggregates ignore them, and SUM
 * and AVG of no values are NULL. SUM and AVG also ignore values that aren't numbers, because
 * text is never read as a number, and {@link #leftOut} reports them.
 */
final class Aggregator {

    private static final int EXAMPLE_LENGTH = 40;
    private static final int MAX_EXACT_SCALE = 10_000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, LeftOut> leftOutByAggregate = new LinkedHashMap<>();

    /** The values one SUM or AVG could not use, across every group. */
    private static final class LeftOut {
        private long count;
        private JsonNode example;

        void add(JsonNode value) {
            count++;
            if (example == null) {
                example = value;
            }
        }
    }

    /**
     * One sentence for each SUM or AVG that met values that aren't numbers, such as
     * {@code SUM(price) left out 3 values that are not numbers, such as "none"}.
     *
     * @param verb what happened to them: "left out" or "found"
     */
    List<String> leftOut(String verb) {
        List<String> sentences = new ArrayList<>();
        leftOutByAggregate.forEach((aggregate, leftOut) -> {
            String example = leftOut.example.toString();
            if (example.length() > EXAMPLE_LENGTH) {
                example = example.substring(0, EXAMPLE_LENGTH) + "...";
            }
            sentences.add(aggregate + " " + verb + (leftOut.count == 1
                ? " 1 value that is not a number: "
                : " " + leftOut.count + " values that are not numbers, such as ") + example);
        });
        return sentences;
    }

    List<JsonNode> group(List<JsonNode> rows, ParsedQuery query, FieldAccessor fields) {
        Map<String, Group> groups = new LinkedHashMap<>();
        if (query.getGroupBy().isEmpty()) {
            groups.put("", new Group(query));
        }
        for (JsonNode row : rows) {
            groups.computeIfAbsent(groupKey(row, query, fields), key -> new Group(query)).add(row, query, fields);
        }

        List<JsonNode> result = new ArrayList<>();
        for (Group group : groups.values()) {
            result.add(group.toRow(query));
        }
        return result;
    }

    private static String groupKey(JsonNode row, ParsedQuery query, FieldAccessor fields) {
        StringBuilder key = new StringBuilder();
        for (String field : query.getGroupBy()) {
            key.append(identity(fields.getFieldValue(row, field))).append('\u0000');
        }
        return key.toString();
    }

    /** A text form that's equal for equal values: 1 and 1.0 match, 1 and "1" don't. */
    private static String identity(JsonNode value) {
        return isNull(value) ? "null" : Values.identity(value);
    }

    private static boolean isNull(JsonNode value) {
        return Values.isNull(value);
    }

    private final class Group {
        private final Map<String, JsonNode> fieldValues = new LinkedHashMap<>();
        private final List<Accumulator> accumulators = new ArrayList<>();

        Group(ParsedQuery query) {
            for (AggregateCall call : query.getAggregates()) {
                accumulators.add(new Accumulator(call));
            }
        }

        void add(JsonNode row, ParsedQuery query, FieldAccessor fields) {
            if (fieldValues.isEmpty()) {
                for (String field : query.getGroupBy()) {
                    fieldValues.put(field, fields.getFieldValue(row, field));
                }
            }
            for (Accumulator accumulator : accumulators) {
                String field = accumulator.call.field();
                accumulator.add(field == null ? null : fields.getFieldValue(row, field));
            }
        }

        JsonNode toRow(ParsedQuery query) {
            ObjectNode row = objectMapper.createObjectNode();
            fieldValues.forEach((field, value) -> {
                JsonNode stored = isNull(value) ? NullNode.getInstance() : value;
                row.set(field, stored);
                row.set(field.substring(field.lastIndexOf('.') + 1), stored);
            });
            for (Accumulator accumulator : accumulators) {
                row.set(accumulator.call.key(), accumulator.result());
            }
            for (ColumnInfo column : query.getSelectColumns()) {
                if (column.hasAlias()) {
                    row.set(column.getAlias(), row.get(column.getExpression()));
                }
            }
            return row;
        }
    }

    private final class Accumulator {
        private final AggregateCall call;
        private final Set<String> seen = new HashSet<>();
        private long count;
        private BigDecimal sum = BigDecimal.ZERO;
        private boolean allIntegers = true;
        private Double nonFinite;
        private JsonNode min;
        private JsonNode max;

        Accumulator(AggregateCall call) {
            this.call = call;
        }

        void add(JsonNode value) {
            if (call.field() == null) {
                count++;
                return;
            }
            if (isNull(value)) {
                return;
            }
            if (needsNumbers() && !value.isNumber()) {
                leftOutByAggregate.computeIfAbsent(call.key(), key -> new LeftOut()).add(value);
                return;
            }
            if (call.distinct() && !seen.add(identity(value))) {
                return;
            }
            count++;
            if (needsNumbers()) {
                addToSum(value);
            }
            min = min == null || Values.compare(value, min) < 0 ? value : min;
            max = max == null || Values.compare(value, max) > 0 ? value : max;
        }

        private boolean needsNumbers() {
            return call.function().equals("SUM") || call.function().equals("AVG");
        }

        private void addToSum(JsonNode value) {
            allIntegers &= value.isIntegralNumber();
            if (value.isFloatingPointNumber() && !value.isBigDecimal() && !Double.isFinite(value.asDouble())) {
                addNonFinite(value.asDouble());
                return;
            }
            BigDecimal decimal = value.decimalValue();
            if (Math.abs((long) decimal.scale()) > MAX_EXACT_SCALE) {
                // Adding 1e999999999 to 1 exactly would need a billion digits
                double approximate = value.asDouble();
                if (!Double.isFinite(approximate)) {
                    addNonFinite(approximate);
                    return;
                }
                decimal = BigDecimal.valueOf(approximate);
            }
            sum = sum.add(decimal);
        }

        private void addNonFinite(double value) {
            nonFinite = nonFinite == null ? value : nonFinite + value;
        }

        JsonNode result() {
            return switch (call.function()) {
                case "COUNT" -> LongNode.valueOf(count);
                case "MIN" -> min == null ? NullNode.getInstance() : min;
                case "MAX" -> max == null ? NullNode.getInstance() : max;
                case "AVG" -> average();
                default -> sumResult();
            };
        }

        private JsonNode average() {
            if (count == 0) {
                return NullNode.getInstance();
            }
            return nonFinite != null ? DoubleNode.valueOf(nonFinite) : Json.number(Json.divide(sum, BigDecimal.valueOf(count)));
        }

        private JsonNode sumResult() {
            if (count == 0) {
                return NullNode.getInstance();
            }
            if (nonFinite != null) {
                return DoubleNode.valueOf(nonFinite);
            }
            return allIntegers ? BigIntegerNode.valueOf(sum.toBigInteger()) : Json.number(sum);
        }
    }
}
