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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns rows into one row per group for GROUP BY and aggregates. Each grouped row holds the
 * group fields and aggregate results under their keys, plus any aliases, ready for HAVING,
 * ORDER BY and projection. Without GROUP BY, all rows form one group, even if there are none.
 *
 * Following SQL, NULL and missing values form one group, aggregates ignore them, and SUM
 * and AVG of no values are NULL. Following SQLite, text in SUM and AVG counts as its
 * leading number, or 0 if it has none.
 */
final class Aggregator {

    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        if (isNull(value)) {
            return "null";
        }
        if (value.isNumber()) {
            return "n" + (value.asDouble() + 0.0);
        }
        return value.isTextual() ? "s" + value.asText() : "o" + value;
    }

    private static boolean isNull(JsonNode value) {
        return value == null || value.isNull();
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

    private static final class Accumulator {
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
            if (isNull(value) || (call.distinct() && !seen.add(identity(value)))) {
                return;
            }
            count++;
            addToSum(value);
            min = min == null || compare(value, min) < 0 ? value : min;
            max = max == null || compare(value, max) > 0 ? value : max;
        }

        private void addToSum(JsonNode value) {
            if (value.isNumber() && !Double.isFinite(value.asDouble())) {
                nonFinite = nonFinite == null ? value.asDouble() : nonFinite + value.asDouble();
                allIntegers = false;
                return;
            }
            if (value.isNumber()) {
                sum = sum.add(value.decimalValue());
                allIntegers &= value.isIntegralNumber();
                return;
            }
            allIntegers = false;
            Matcher number = LEADING_NUMBER.matcher(value.asText());
            if (number.find()) {
                sum = sum.add(new BigDecimal(number.group().trim().replaceFirst("\\.$", "").replaceFirst("\\.([eE])", "$1")));
            }
        }

        private static int compare(JsonNode a, JsonNode b) {
            if (a.isNumber() && b.isNumber()) {
                return Double.compare(a.asDouble(), b.asDouble());
            }
            if (a.isNumber() != b.isNumber()) {
                return a.isNumber() ? -1 : 1;
            }
            return a.asText().compareTo(b.asText());
        }

        JsonNode result() {
            return switch (call.function()) {
                case "COUNT" -> LongNode.valueOf(count);
                case "MIN" -> min == null ? NullNode.getInstance() : min;
                case "MAX" -> max == null ? NullNode.getInstance() : max;
                case "AVG" -> count == 0 ? NullNode.getInstance() : DoubleNode.valueOf(total() / count);
                default -> sumResult();
            };
        }

        private double total() {
            return nonFinite == null ? sum.doubleValue() : nonFinite;
        }

        private JsonNode sumResult() {
            if (count == 0) {
                return NullNode.getInstance();
            }
            return allIntegers ? BigIntegerNode.valueOf(sum.toBigInteger()) : DoubleNode.valueOf(total());
        }
    }
}
