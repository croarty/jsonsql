package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Modulo;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.schema.Column;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out the value of an expression for one row: a field, a literal, arithmetic, text
 * joined with ||, CASE, CAST or a condition. A Java null stands for SQL NULL.
 *
 * NULL passes through arithmetic and ||, as in SQL. Arithmetic on a value that isn't a
 * number also gives NULL, because text is never read as a number, and the value is
 * recorded in {@link #leftOut()} so the caller can warn about it. CAST is the way to ask
 * for a conversion.
 */
final class ExpressionEvaluator {

    static final Set<String> INTEGER_TYPES = Set.of("INTEGER", "INT", "BIGINT");
    static final Set<String> DECIMAL_TYPES = Set.of("REAL", "DECIMAL", "NUMERIC", "DOUBLE", "FLOAT", "NUMBER");
    static final Set<String> TEXT_TYPES = Set.of("TEXT", "VARCHAR", "CHAR", "STRING");

    /** Beyond this many digits either side of the point, a number is handled approximately. */
    private static final int MAX_EXACT_SCALE = 10_000;

    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private final FieldAccessor fields;
    private final WhereEvaluator conditions;
    private final LeftOut leftOut = new LeftOut();

    ExpressionEvaluator(FieldAccessor fields) {
        this.fields = fields;
        this.conditions = new WhereEvaluator(fields, this);
    }

    ExpressionEvaluator(FieldAccessor fields, WhereEvaluator conditions) {
        this.fields = fields;
        this.conditions = conditions;
    }

    /** The values arithmetic could not use, across every row evaluated so far. */
    LeftOut leftOut() {
        return leftOut;
    }

    JsonNode evaluate(JsonNode row, Expression expression) {
        if (expression instanceof Parenthesis parenthesis) {
            return evaluate(row, parenthesis.getExpression());
        }
        if (expression instanceof NullValue) {
            return null;
        }
        if (expression instanceof StringValue text) {
            return TextNode.valueOf(text.getNotExcapedValue());
        }
        if (expression instanceof LongValue number) {
            return integer(number.getBigIntegerValue());
        }
        if (expression instanceof DoubleValue) {
            return Json.number(expression.toString());
        }
        if (expression instanceof SignedExpression signed) {
            return sign(row, signed);
        }
        if (expression instanceof Column column) {
            return column(row, column);
        }
        if (expression instanceof Concat concat) {
            return concat(evaluate(row, concat.getLeftExpression()), evaluate(row, concat.getRightExpression()));
        }
        if (isArithmetic(expression)) {
            BinaryExpression binary = (BinaryExpression) expression;
            return arithmetic(binary, evaluate(row, binary.getLeftExpression()), evaluate(row, binary.getRightExpression()));
        }
        if (expression instanceof CaseExpression caseExpression) {
            return caseValue(row, caseExpression);
        }
        if (expression instanceof CastExpression cast) {
            return cast(evaluate(row, cast.getLeftExpression()), castType(cast));
        }
        if (WhereEvaluator.isCondition(expression)) {
            return conditions.value(row, expression);
        }
        throw new UnsupportedExpressionException(
            "Unsupported expression: " + expression + " (type: " + expression.getClass().getSimpleName() + ")");
    }

    static boolean isArithmetic(Expression expression) {
        return expression instanceof Addition || expression instanceof Subtraction
            || expression instanceof Multiplication || expression instanceof Division
            || expression instanceof Modulo;
    }

    static String castType(CastExpression cast) {
        return cast.getColDataType() == null ? "" : cast.getColDataType().getDataType().toUpperCase(Locale.ROOT);
    }

    /**
     * A field's value. As in SQLite, a double-quoted name that isn't a field is read as text.
     */
    private JsonNode column(JsonNode row, Column column) {
        String name = QueryParser.fieldName(column);
        if (column.getTable() == null) {
            if (name.equalsIgnoreCase("true")) {
                return BooleanNode.TRUE;
            }
            if (name.equalsIgnoreCase("false")) {
                return BooleanNode.FALSE;
            }
        }
        JsonNode value = fields.getFieldValue(row, name);
        if (value == null && column.toString().startsWith("\"")) {
            return TextNode.valueOf(name);
        }
        return Values.isNull(value) ? null : value;
    }

    private JsonNode sign(JsonNode row, SignedExpression signed) {
        JsonNode value = evaluate(row, signed.getExpression());
        if (value == null || signed.getSign() != '-') {
            return number(value);
        }
        if (number(value) == null) {
            return null;
        }
        if (isNotFinite(value)) {
            return DoubleNode.valueOf(-value.asDouble());
        }
        return value.isIntegralNumber() ? integer(value.bigIntegerValue().negate()) : Json.number(value.decimalValue().negate());
    }

    /** The value if it is a number. Otherwise NULL, noting any value that had to be left out. */
    private JsonNode number(JsonNode value) {
        if (value == null || value.isNumber()) {
            return value;
        }
        leftOut.add(value);
        return null;
    }

    private JsonNode arithmetic(BinaryExpression operator, JsonNode left, JsonNode right) {
        left = number(left);
        right = number(right);
        if (left == null || right == null) {
            return null;
        }
        if (isNotFinite(left) || isNotFinite(right) || isHuge(left) || isHuge(right)) {
            return approximate(operator, left.asDouble(), right.asDouble());
        }
        try {
            if (left.isIntegralNumber() && right.isIntegralNumber() && !(operator instanceof Division)) {
                return integers(operator, left.bigIntegerValue(), right.bigIntegerValue());
            }
            return decimals(operator, left.decimalValue(), right.decimalValue());
        } catch (ArithmeticException tooLarge) {
            return approximate(operator, left.asDouble(), right.asDouble());
        }
    }

    private static JsonNode integers(BinaryExpression operator, BigInteger left, BigInteger right) {
        if (operator instanceof Addition) {
            return integer(left.add(right));
        }
        if (operator instanceof Subtraction) {
            return integer(left.subtract(right));
        }
        if (operator instanceof Multiplication) {
            return integer(left.multiply(right));
        }
        return right.signum() == 0 ? null : integer(left.remainder(right));
    }

    private static JsonNode decimals(BinaryExpression operator, BigDecimal left, BigDecimal right) {
        if (operator instanceof Addition) {
            return Json.number(left.add(right));
        }
        if (operator instanceof Subtraction) {
            return Json.number(left.subtract(right));
        }
        if (operator instanceof Multiplication) {
            return Json.number(left.multiply(right));
        }
        if (right.signum() == 0) {
            return null;
        }
        return Json.number(operator instanceof Division ? Json.divide(left, right) : left.remainder(right));
    }

    /** For NaN, the infinities, and numbers too long to work on exactly, such as 1e999999999. */
    private static JsonNode approximate(BinaryExpression operator, double left, double right) {
        double result;
        if (operator instanceof Addition) {
            result = left + right;
        } else if (operator instanceof Subtraction) {
            result = left - right;
        } else if (operator instanceof Multiplication) {
            result = left * right;
        } else if (right == 0) {
            return null;
        } else {
            result = operator instanceof Division ? left / right : left % right;
        }
        return Double.isFinite(result) ? Json.number(BigDecimal.valueOf(result)) : DoubleNode.valueOf(result);
    }

    private static boolean isNotFinite(JsonNode number) {
        return number.isFloatingPointNumber() && !number.isBigDecimal() && !Double.isFinite(number.asDouble());
    }

    private static boolean isHuge(JsonNode number) {
        return number.isBigDecimal() && Math.abs((long) number.decimalValue().scale()) > MAX_EXACT_SCALE;
    }

    private static JsonNode integer(BigInteger value) {
        return value.bitLength() < Long.SIZE ? LongNode.valueOf(value.longValue()) : BigIntegerNode.valueOf(value);
    }

    /** Joins two values as text. Numbers and booleans join as they print; NULL gives NULL. */
    private static JsonNode concat(JsonNode left, JsonNode right) {
        return left == null || right == null ? null : TextNode.valueOf(text(left) + text(right));
    }

    private static String text(JsonNode value) {
        return value.isContainerNode() ? value.toString() : value.asText();
    }

    private JsonNode caseValue(JsonNode row, CaseExpression caseExpression) {
        Expression subject = caseExpression.getSwitchExpression();
        JsonNode subjectValue = subject == null ? null : evaluate(row, subject);

        for (WhenClause when : caseExpression.getWhenClauses()) {
            boolean matches;
            if (subject == null) {
                matches = conditions.evaluate(row, when.getWhenExpression());
            } else {
                JsonNode candidate = evaluate(row, when.getWhenExpression());
                matches = subjectValue != null && candidate != null && Values.equal(subjectValue, candidate);
            }
            if (matches) {
                return evaluate(row, when.getThenExpression());
            }
        }
        return caseExpression.getElseExpression() == null ? null : evaluate(row, caseExpression.getElseExpression());
    }

    /**
     * Converts a value because the query asked for it. Text becomes its leading number, so
     * "15.6 inch" is 15.6, and text with no leading number becomes NULL.
     */
    static JsonNode cast(JsonNode value, String type) {
        if (value == null) {
            return null;
        }
        if (TEXT_TYPES.contains(type)) {
            return value.isTextual() ? value : TextNode.valueOf(text(value));
        }
        BigDecimal number = toDecimal(value);
        if (number == null) {
            return value.isNumber() && DECIMAL_TYPES.contains(type) ? value : null;
        }
        if (DECIMAL_TYPES.contains(type)) {
            return Json.number(number);
        }
        // A whole number with thousands of digits isn't one anybody meant to ask for
        return (long) number.precision() - number.scale() > MAX_EXACT_SCALE
            ? null
            : integer(number.setScale(0, RoundingMode.DOWN).toBigInteger());
    }

    private static BigDecimal toDecimal(JsonNode value) {
        if (value.isNumber()) {
            return isNotFinite(value) ? null : value.decimalValue();
        }
        if (!value.isTextual()) {
            return null;
        }
        Matcher leading = LEADING_NUMBER.matcher(value.asText());
        if (!leading.find()) {
            return null;
        }
        try {
            return new BigDecimal(leading.group().trim().replaceFirst("\\.([eE]|$)", "$1"));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
