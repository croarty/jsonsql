package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The functions that work on one value at a time, such as UPPER and ROUND. They follow
 * SQLite's definitions, within JsonSQL's rule that text is never read as a number.
 *
 * A Java null stands for SQL NULL. Apart from COALESCE, IFNULL and NULLIF, which exist to
 * deal with it, a function given NULL gives NULL. Text functions take a number or boolean
 * as it prints. A function that needs a number and is given anything else gives NULL and
 * reports the value, so the caller can warn about it.
 */
final class Functions {

    /** How many arguments a function takes. */
    private record Arity(int min, int max) {
    }

    private static final int ANY_NUMBER = Integer.MAX_VALUE;

    /** SQLite's limit on the decimal places ROUND keeps. */
    private static final int MAX_ROUND_DIGITS = 30;

    private static final Map<String, Arity> FUNCTIONS = new TreeMap<>(Map.ofEntries(
        Map.entry("UPPER", new Arity(1, 1)),
        Map.entry("LOWER", new Arity(1, 1)),
        Map.entry("LENGTH", new Arity(1, 1)),
        Map.entry("SUBSTR", new Arity(2, 3)),
        Map.entry("SUBSTRING", new Arity(2, 3)),
        Map.entry("TRIM", new Arity(1, 2)),
        Map.entry("LTRIM", new Arity(1, 2)),
        Map.entry("RTRIM", new Arity(1, 2)),
        Map.entry("REPLACE", new Arity(3, 3)),
        Map.entry("ROUND", new Arity(1, 2)),
        Map.entry("ABS", new Arity(1, 1)),
        Map.entry("COALESCE", new Arity(2, ANY_NUMBER)),
        Map.entry("IFNULL", new Arity(2, 2)),
        Map.entry("NULLIF", new Arity(2, 2))));

    private Functions() {
    }

    static boolean isFunction(String name) {
        return FUNCTIONS.containsKey(name.toUpperCase(Locale.ROOT));
    }

    /** The names of every function, for an error message. */
    static String names() {
        return String.join(", ", FUNCTIONS.keySet());
    }

    /**
     * What is wrong with calling a function with this many arguments, such as
     * {@code UPPER takes 1 argument, got 2}, or null if nothing is.
     */
    static String arityProblem(String name, int count) {
        Arity arity = FUNCTIONS.get(name.toUpperCase(Locale.ROOT));
        if (count >= arity.min() && count <= arity.max()) {
            return null;
        }
        String takes;
        if (arity.max() == ANY_NUMBER) {
            takes = arity.min() + " or more arguments";
        } else if (arity.min() == arity.max()) {
            takes = arity.min() + (arity.min() == 1 ? " argument" : " arguments");
        } else {
            takes = arity.min() + " or " + arity.max() + " arguments";
        }
        return name.toUpperCase(Locale.ROOT) + " takes " + takes + ", got " + count;
    }

    /**
     * @param notANumber told each value that had to be a number and wasn't
     */
    static JsonNode call(String name, List<JsonNode> arguments, Consumer<JsonNode> notANumber) {
        String function = name.toUpperCase(Locale.ROOT);
        switch (function) {
            case "COALESCE", "IFNULL" -> {
                return arguments.stream().filter(argument -> argument != null).findFirst().orElse(null);
            }
            case "NULLIF" -> {
                JsonNode value = arguments.get(0);
                JsonNode other = arguments.get(1);
                return value != null && other != null && Values.equal(value, other) ? null : value;
            }
            default -> {
                if (arguments.contains(null)) {
                    return null;
                }
            }
        }
        JsonNode first = arguments.get(0);
        return switch (function) {
            case "UPPER" -> TextNode.valueOf(ExpressionEvaluator.text(first).toUpperCase(Locale.ROOT));
            case "LOWER" -> TextNode.valueOf(ExpressionEvaluator.text(first).toLowerCase(Locale.ROOT));
            case "LENGTH" -> {
                String text = ExpressionEvaluator.text(first);
                yield LongNode.valueOf(text.codePointCount(0, text.length()));
            }
            case "SUBSTR", "SUBSTRING" -> substr(arguments, notANumber);
            case "TRIM" -> trim(arguments, true, true);
            case "LTRIM" -> trim(arguments, true, false);
            case "RTRIM" -> trim(arguments, false, true);
            case "REPLACE" -> replace(arguments);
            case "ROUND" -> round(arguments, notANumber);
            default -> abs(first, notANumber);
        };
    }

    /**
     * SQLite's SUBSTR. Characters are counted from 1. A negative start counts back from the
     * end, and a negative length takes the characters before the start.
     */
    private static JsonNode substr(List<JsonNode> arguments, Consumer<JsonNode> notANumber) {
        Long start = whole(arguments.get(1), notANumber);
        Long length = arguments.size() > 2 ? whole(arguments.get(2), notANumber) : Long.valueOf(Long.MAX_VALUE);
        if (start == null || length == null) {
            return null;
        }
        int[] characters = ExpressionEvaluator.text(arguments.get(0)).codePoints().toArray();

        // Work in BigInteger terms without the cost: every value below stays within a few
        // multiples of Long.MAX_VALUE, so doubles of that size are ample for comparing
        double from = start;
        double count = Math.abs((double) length);
        boolean before = length < 0;
        if (from < 0) {
            from += characters.length;
            if (from < 0) {
                count = Math.max(0, count + from);
                from = 0;
            }
        } else if (from > 0) {
            from--;
        } else if (count > 0) {
            count--;
        }
        if (before) {
            from -= count;
            if (from < 0) {
                count += from;
                from = 0;
            }
        }
        int begin = (int) Math.min(from, characters.length);
        int end = (int) Math.min(from + Math.max(0, count), characters.length);
        return TextNode.valueOf(new String(characters, begin, Math.max(0, end - begin)));
    }

    /** The whole-number part of a number, or null, having reported it, if it isn't one. */
    private static Long whole(JsonNode value, Consumer<JsonNode> notANumber) {
        if (!value.isNumber()) {
            notANumber.accept(value);
            return null;
        }
        if (value.isFloatingPointNumber() && !value.isBigDecimal()) {
            // NaN and the infinities: Java gives 0 for NaN and the nearest long otherwise
            return (long) value.asDouble();
        }
        BigDecimal number = value.decimalValue();
        if (number.precision() - number.scale() > 19) {
            return number.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        if (number.precision() - number.scale() <= 0) {
            return 0L;
        }
        BigInteger truncated = number.toBigInteger();
        return truncated.bitLength() < Long.SIZE ? truncated.longValue()
            : truncated.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }

    /** Removes spaces, or each of the characters given, from one end or both. */
    private static JsonNode trim(List<JsonNode> arguments, boolean left, boolean right) {
        int[] characters = ExpressionEvaluator.text(arguments.get(0)).codePoints().toArray();
        String remove = arguments.size() > 1 ? ExpressionEvaluator.text(arguments.get(1)) : " ";
        int begin = 0;
        int end = characters.length;
        while (left && begin < end && remove.indexOf(characters[begin]) >= 0) {
            begin++;
        }
        while (right && end > begin && remove.indexOf(characters[end - 1]) >= 0) {
            end--;
        }
        return TextNode.valueOf(new String(characters, begin, end - begin));
    }

    private static JsonNode replace(List<JsonNode> arguments) {
        String text = ExpressionEvaluator.text(arguments.get(0));
        String find = ExpressionEvaluator.text(arguments.get(1));
        // As in SQLite, replacing nothing changes nothing
        return TextNode.valueOf(find.isEmpty() ? text : text.replace(find, ExpressionEvaluator.text(arguments.get(2))));
    }

    /**
     * Rounds to a number of decimal places, a half going away from zero. The result is always
     * a decimal, so ROUND(5) is 5.0.
     */
    private static JsonNode round(List<JsonNode> arguments, Consumer<JsonNode> notANumber) {
        JsonNode value = arguments.get(0);
        Long places = arguments.size() > 1 ? whole(arguments.get(1), notANumber) : Long.valueOf(0);
        if (!value.isNumber()) {
            notANumber.accept(value);
            return null;
        }
        if (places == null) {
            return null;
        }
        if (isNotFinite(value)) {
            return value;
        }
        int digits = (int) Math.max(0, Math.min(places, MAX_ROUND_DIGITS));
        BigDecimal number = value.decimalValue();
        if (number.scale() <= digits) {
            return Json.number(number);
        }
        if (number.precision() - number.scale() < -digits) {
            // Too small to reach the last place kept; and far cheaper than rounding 1e-999999999
            return Json.number(BigDecimal.ZERO);
        }
        return Json.number(number.setScale(digits, RoundingMode.HALF_UP));
    }

    private static JsonNode abs(JsonNode value, Consumer<JsonNode> notANumber) {
        if (!value.isNumber()) {
            notANumber.accept(value);
            return null;
        }
        if (isNotFinite(value)) {
            return DoubleNode.valueOf(Math.abs(value.asDouble()));
        }
        if (value.isIntegralNumber()) {
            BigInteger absolute = value.bigIntegerValue().abs();
            return absolute.bitLength() < Long.SIZE ? LongNode.valueOf(absolute.longValue()) : BigIntegerNode.valueOf(absolute);
        }
        return Json.number(value.decimalValue().abs());
    }

    private static boolean isNotFinite(JsonNode number) {
        return number.isFloatingPointNumber() && !number.isBigDecimal() && !Double.isFinite(number.asDouble());
    }
}
