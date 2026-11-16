package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Finds the rows whose join key matches a value, without comparing every row.
 *
 * Keys match when {@link Values} says they are equal, so 1 matches 1.0 but not "1".
 * NULL never matches anything. A key can have several parts, such as {@code a.x = b.x AND
 * a.y = b.y}; then every part must match, and a NULL in any part matches nothing.
 */
final class JoinIndex {

    private final Map<List<String>, List<JsonNode>> byKey = new HashMap<>();

    JoinIndex(List<JsonNode> rows, Function<JsonNode, JsonNode> joinKey) {
        this(rows, List.of(joinKey));
    }

    JoinIndex(List<JsonNode> rows, List<Function<JsonNode, JsonNode>> keyParts) {
        for (JsonNode row : rows) {
            List<String> key = identity(keyParts.stream().map(part -> part.apply(row)).toList());
            if (key != null) {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
    }

    /**
     * The rows matching a value, in their original order.
     */
    List<JsonNode> matches(JsonNode value) {
        List<JsonNode> values = new ArrayList<>();
        values.add(value);
        return matchesAll(values);
    }

    /**
     * The rows matching every part of a key, in their original order.
     */
    List<JsonNode> matchesAll(List<JsonNode> values) {
        List<String> key = identity(values);
        return key == null ? List.of() : byKey.getOrDefault(key, List.of());
    }

    /** Each value's identity, or null if any value is NULL. */
    private static List<String> identity(List<JsonNode> values) {
        List<String> key = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            if (Values.isNull(value)) {
                return null;
            }
            key.add(Values.identity(value));
        }
        return key;
    }
}
