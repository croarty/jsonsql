package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Finds the rows whose join key matches a value, without comparing every row.
 *
 * Matching isn't a single kind of equality. Numbers compare numerically, a number matches
 * text that parses to the same number, and any other pair compares as text. So 1 matches
 * "1" and "1.0", but "1" does not match "1.0". Rows are therefore indexed by number and by
 * text, and a lookup combines the lists that apply. NULL never matches anything.
 */
final class JoinIndex {

    private final List<JsonNode> rows;
    private final Map<Double, List<Integer>> numbers = new HashMap<>();
    private final Map<Double, List<Integer>> nonNumbersByNumber = new HashMap<>();
    private final Map<String, List<Integer>> nonNumbersByText = new HashMap<>();

    JoinIndex(List<JsonNode> rows, Function<JsonNode, JsonNode> joinKey) {
        this.rows = rows;
        for (int i = 0; i < rows.size(); i++) {
            add(joinKey.apply(rows.get(i)), i);
        }
    }

    private void add(JsonNode key, int position) {
        if (isNull(key)) {
            return;
        }
        if (key.isNumber()) {
            addTo(numbers, numericKey(key.asDouble()), position);
            return;
        }
        nonNumbersByText.computeIfAbsent(key.asText(), k -> new ArrayList<>()).add(position);
        addTo(nonNumbersByNumber, parse(key.asText()), position);
    }

    private static void addTo(Map<Double, List<Integer>> index, Double key, int position) {
        if (key != null) {
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(position);
        }
    }

    /**
     * The rows matching a value, in their original order.
     */
    List<JsonNode> matches(JsonNode value) {
        if (isNull(value)) {
            return List.of();
        }
        List<Integer> positions = value.isNumber()
            ? merge(lookup(numbers, numericKey(value.asDouble())), lookup(nonNumbersByNumber, numericKey(value.asDouble())))
            : merge(nonNumbersByText.getOrDefault(value.asText(), List.of()), lookup(numbers, parse(value.asText())));

        List<JsonNode> matched = new ArrayList<>(positions.size());
        for (int position : positions) {
            matched.add(rows.get(position));
        }
        return matched;
    }

    private static List<Integer> lookup(Map<Double, List<Integer>> index, Double key) {
        return key == null ? List.of() : index.getOrDefault(key, List.of());
    }

    private static List<Integer> merge(List<Integer> first, List<Integer> second) {
        if (second.isEmpty()) {
            return first;
        }
        if (first.isEmpty()) {
            return second;
        }
        List<Integer> merged = new ArrayList<>(first.size() + second.size());
        merged.addAll(first);
        merged.addAll(second);
        Collections.sort(merged);
        return merged;
    }

    private static boolean isNull(JsonNode value) {
        return value == null || value.isNull();
    }

    private static Double parse(String text) {
        try {
            return numericKey(Double.parseDouble(text.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The map key for a number. NaN equals nothing, and -0.0 equals 0.0.
     */
    private static Double numericKey(double value) {
        return Double.isNaN(value) ? null : Double.valueOf(value + 0.0);
    }
}
