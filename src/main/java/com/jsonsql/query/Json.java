package com.jsonsql.query;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ValueNode;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * How JsonSQL reads, holds and writes numbers. A decimal is kept exactly as a decimal from
 * the data file to the output, and never becomes a floating-point number on the way.
 *
 * Every mapper that reads or writes rows must come from {@link #newMapper}. A plain
 * ObjectMapper would read 0.1 as a double, and the digits beyond a double's precision
 * would be lost at that point.
 */
public final class Json {

    /** Above this many digits, a whole number written as a decimal stays in exponent form. */
    private static final int PLAIN_DIGITS = 20;

    /** The significant digits kept by a division that never ends, such as 10 / 3. */
    private static final int DIVISION_DIGITS = 16;

    private static final BigDecimal ZERO = new BigDecimal("0.0");

    private static final JsonNodeFactory NODES = new JsonNodeFactory(true) {
        @Override
        public ValueNode numberNode(BigDecimal value) {
            return value == null ? nullNode() : DecimalNode.valueOf(normalize(value));
        }
    };

    private Json() {
    }

    public static ObjectMapper newMapper() {
        return JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(NODES)
            .build();
    }

    public static JsonNode number(BigDecimal value) {
        return NODES.numberNode(value);
    }

    /**
     * The value of a number written in a query, such as 0.1 or 2e1.
     */
    public static JsonNode number(String text) {
        try {
            return number(new BigDecimal(text.replace(" ", "")));
        } catch (NumberFormatException e) {
            return DoubleNode.valueOf(Double.parseDouble(text));
        }
    }

    /**
     * Divide exactly when the answer ends, as 1 / 8 does. When it never ends, keep 16
     * significant digits, or the dividend's decimal places if that is more.
     */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        try {
            return dividend.divide(divisor);
        } catch (ArithmeticException neverEnds) {
            BigDecimal rounded = dividend.divide(divisor, new MathContext(DIVISION_DIGITS, RoundingMode.HALF_EVEN));
            return rounded.scale() >= dividend.scale()
                ? rounded
                : dividend.divide(divisor, dividend.scale(), RoundingMode.HALF_EVEN);
        }
    }

    /**
     * One spelling for each decimal, so 20.5 and 20.50 print the same. Trailing zeros go,
     * and a whole number keeps one decimal place to show it was written as a decimal.
     */
    private static BigDecimal normalize(BigDecimal value) {
        if (value.signum() == 0) {
            return ZERO;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > 0) {
            return stripped;
        }
        long digits = (long) stripped.precision() - stripped.scale();
        return digits <= PLAIN_DIGITS ? stripped.setScale(1) : stripped;
    }
}
