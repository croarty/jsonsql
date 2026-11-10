package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The values a calculation could not use because they aren't numbers: how many, and the
 * first of them as an example for the warning.
 */
final class LeftOut {

    private static final int EXAMPLE_LENGTH = 40;

    private long count;
    private JsonNode example;

    void add(JsonNode value) {
        count++;
        if (example == null) {
            example = value;
        }
    }

    boolean isEmpty() {
        return count == 0;
    }

    /**
     * One sentence, such as {@code SUM(price) left out 3 values that are not numbers, such as "none"}.
     *
     * @param calculation the calculation as the user wrote it
     * @param verb        what happened to the values: "left out" or "found"
     */
    String describe(String calculation, String verb) {
        String text = example.toString();
        if (text.length() > EXAMPLE_LENGTH) {
            text = text.substring(0, EXAMPLE_LENGTH) + "...";
        }
        return calculation + " " + verb + (count == 1
            ? " 1 value that is not a number: "
            : " " + count + " values that are not numbers, such as ") + text;
    }
}
