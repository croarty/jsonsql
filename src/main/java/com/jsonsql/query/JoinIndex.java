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
 * NULL never matches anything.
 */
final class JoinIndex {

    private final Map<String, List<JsonNode>> byKey = new HashMap<>();

    JoinIndex(List<JsonNode> rows, Function<JsonNode, JsonNode> joinKey) {
        for (JsonNode row : rows) {
            JsonNode key = joinKey.apply(row);
            if (!Values.isNull(key)) {
                byKey.computeIfAbsent(Values.identity(key), k -> new ArrayList<>()).add(row);
            }
        }
    }

    /**
     * The rows matching a value, in their original order.
     */
    List<JsonNode> matches(JsonNode value) {
        if (Values.isNull(value)) {
            return List.of();
        }
        return byKey.getOrDefault(Values.identity(value), List.of());
    }
}
