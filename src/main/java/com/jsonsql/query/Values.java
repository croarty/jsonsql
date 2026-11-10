package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * The one definition of equal and less than for JSON values, shared by WHERE, ORDER BY,
 * MIN and MAX, GROUP BY, DISTINCT, joins and index pruning.
 *
 * Numbers compare exactly, so 10 equals 10.0 and nothing else. Text never converts to a
 * number: 10 and "10" are different, and every number sorts before every text value. A
 * boolean compares as the text true or false, so it equals the text "true" or "false" in a
 * data file or a query. Arrays and objects sort last, by their JSON text.
 *
 * NULL isn't a value here. Callers decide what a NULL or missing field means for them.
 */
public final class Values {

    private static final int NUMBER = 0;
    private static final int TEXT = 1;
    private static final int CONTAINER = 2;

    private Values() {
    }

    public static boolean isNull(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode();
    }

    public static boolean equal(JsonNode a, JsonNode b) {
        return compare(a, b) == 0;
    }

    /**
     * Compare two values that are not NULL.
     */
    public static int compare(JsonNode a, JsonNode b) {
        int kind = kind(a);
        int byKind = Integer.compare(kind, kind(b));
        if (byKind != 0) {
            return byKind;
        }
        return switch (kind) {
            case NUMBER -> compareNumbers(a, b);
            case TEXT -> a.asText().compareTo(b.asText());
            default -> a.toString().compareTo(b.toString());
        };
    }

    /**
     * A key that is the same for two values exactly when they are equal, for hash lookups.
     */
    public static String identity(JsonNode value) {
        return switch (kind(value)) {
            case NUMBER -> "n" + (isFinite(value) ? plain(value.decimalValue()) : String.valueOf(value.asDouble()));
            case TEXT -> "s" + value.asText();
            default -> "o" + value;
        };
    }

    private static int kind(JsonNode value) {
        if (value.isNumber()) {
            return NUMBER;
        }
        return value.isContainerNode() ? CONTAINER : TEXT;
    }

    private static int compareNumbers(JsonNode a, JsonNode b) {
        if (a.isIntegralNumber() && b.isIntegralNumber() && a.canConvertToLong() && b.canConvertToLong()) {
            return Long.compare(a.longValue(), b.longValue());
        }
        if (!isFinite(a) || !isFinite(b)) {
            // Any finite number, however large, sits between the infinities
            return Double.compare(isFinite(a) ? 0.0 : a.asDouble(), isFinite(b) ? 0.0 : b.asDouble());
        }
        return a.decimalValue().compareTo(b.decimalValue());
    }

    /** False for NaN and the infinities, which have no decimal form. */
    private static boolean isFinite(JsonNode number) {
        return !number.isFloatingPointNumber() || number.isBigDecimal() || Double.isFinite(number.asDouble());
    }

    private static String plain(BigDecimal number) {
        return number.signum() == 0 ? "0" : number.stripTrailingZeros().toString();
    }
}
