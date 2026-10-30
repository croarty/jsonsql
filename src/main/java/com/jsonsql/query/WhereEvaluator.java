package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.TextNode;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.schema.Column;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Evaluates WHERE clause expressions against JSON data.
 * Supports complex boolean logic (AND, OR, NOT, parentheses) and pattern matching (LIKE).
 *
 * Uses SQL three-valued logic: a condition on a NULL or missing value is UNKNOWN rather
 * than FALSE, NOT UNKNOWN stays UNKNOWN, and a row is returned only when the WHERE
 * clause as a whole is TRUE.
 */
public class WhereEvaluator {

    private enum Truth {
        TRUE, FALSE, UNKNOWN;

        static Truth of(boolean value) {
            return value ? TRUE : FALSE;
        }

        Truth and(Supplier<Truth> other) {
            if (this == FALSE) {
                return FALSE;
            }
            Truth right = other.get();
            if (right == FALSE) {
                return FALSE;
            }
            return this == TRUE && right == TRUE ? TRUE : UNKNOWN;
        }

        Truth or(Supplier<Truth> other) {
            if (this == TRUE) {
                return TRUE;
            }
            Truth right = other.get();
            if (right == TRUE) {
                return TRUE;
            }
            return this == FALSE && right == FALSE ? FALSE : UNKNOWN;
        }

        Truth not() {
            return switch (this) {
                case TRUE -> FALSE;
                case FALSE -> TRUE;
                case UNKNOWN -> UNKNOWN;
            };
        }
    }

    /** One side of a comparison: its value (null for SQL NULL), and whether it came from a field. */
    private record Operand(JsonNode value, boolean isField) {
    }

    private final FieldAccessor fieldAccessor;
    // Cache of compiled LIKE/ILIKE patterns, keyed by regex + case-insensitivity, so the
    // pattern is compiled once per evaluator (one per WHERE filter) instead of once per row.
    private final Map<String, Pattern> patternCache = new HashMap<>();
    
    public WhereEvaluator(FieldAccessor fieldAccessor) {
        this.fieldAccessor = fieldAccessor;
    }
    
    /**
     * Evaluate an expression against a JSON row. Returns true only if the expression is
     * TRUE; both FALSE and UNKNOWN exclude the row.
     */
    public boolean evaluate(JsonNode row, Expression expression) {
        if (expression == null) {
            return true;
        }
        return test(row, expression) == Truth.TRUE;
    }

    private Truth test(JsonNode row, Expression expression) {
        // Handle AND
        if (expression instanceof AndExpression) {
            AndExpression andExpr = (AndExpression) expression;
            return test(row, andExpr.getLeftExpression())
                .and(() -> test(row, andExpr.getRightExpression()));
        }

        // Handle OR
        if (expression instanceof OrExpression) {
            OrExpression orExpr = (OrExpression) expression;
            return test(row, orExpr.getLeftExpression())
                .or(() -> test(row, orExpr.getRightExpression()));
        }

        // Handle NOT
        if (expression instanceof NotExpression) {
            NotExpression notExpr = (NotExpression) expression;
            return test(row, notExpr.getExpression()).not();
        }

        // Handle Parentheses
        if (expression instanceof Parenthesis) {
            Parenthesis paren = (Parenthesis) expression;
            return test(row, paren.getExpression());
        }

        // Handle comparison operators
        if (expression instanceof EqualsTo) {
            return evaluateComparison(row, (EqualsTo) expression, "=");
        }
        if (expression instanceof NotEqualsTo) {
            return evaluateComparison(row, (NotEqualsTo) expression, "!=");
        }
        if (expression instanceof GreaterThan) {
            return evaluateComparison(row, (GreaterThan) expression, ">");
        }
        if (expression instanceof GreaterThanEquals) {
            return evaluateComparison(row, (GreaterThanEquals) expression, ">=");
        }
        if (expression instanceof MinorThan) {
            return evaluateComparison(row, (MinorThan) expression, "<");
        }
        if (expression instanceof MinorThanEquals) {
            return evaluateComparison(row, (MinorThanEquals) expression, "<=");
        }
        
        // Handle LIKE operator
        if (expression instanceof LikeExpression) {
            LikeExpression likeExpr = (LikeExpression) expression;
            // Detect ILIKE (case-insensitive LIKE) directly from the parsed AST keyword rather
            // than inspecting the toString(), which is fragile when a value contains "ILIKE".
            boolean caseInsensitive = likeExpr.getLikeKeyWord() == LikeExpression.KeyWord.ILIKE;
            return evaluateLike(row, likeExpr, caseInsensitive);
        }
        
        // Handle IS NULL / IS NOT NULL
        if (expression instanceof IsNullExpression) {
            return evaluateIsNull(row, (IsNullExpression) expression);
        }
        
        // Handle BETWEEN / NOT BETWEEN
        if (expression instanceof Between) {
            return evaluateBetween(row, (Between) expression);
        }

        // Handle IN / NOT IN
        if (expression instanceof InExpression) {
            return evaluateIn(row, (InExpression) expression);
        }
        
        // Unsupported expression type - fail loudly instead of silently excluding all rows
        throw new UnsupportedExpressionException(
            "Unsupported WHERE expression: " + expression +
            " (type: " + expression.getClass().getSimpleName() + ")"
        );
    }
    
    private Truth evaluateComparison(JsonNode row, ComparisonOperator comparison, String operator) {
        return compareOperands(resolve(row, comparison.getLeftExpression()), operator,
            resolve(row, comparison.getRightExpression()));
    }

    /**
     * x BETWEEN a AND b means x >= a AND x <= b, including how NULL behaves.
     */
    private Truth evaluateBetween(JsonNode row, Between between) {
        Operand value = resolve(row, between.getLeftExpression());
        Truth inRange = compareOperands(value, ">=", resolve(row, between.getBetweenExpressionStart()))
            .and(() -> compareOperands(value, "<=", resolve(row, between.getBetweenExpressionEnd())));
        return between.isNot() ? inRange.not() : inRange;
    }

    private Truth compareOperands(Operand left, String operator, Operand right) {
        // In SQL, any comparison with NULL is UNKNOWN, whichever side the NULL is on.
        // This includes both missing fields and present but null fields.
        if (left.value() == null || right.value() == null) {
            return Truth.UNKNOWN;
        }

        // Compare with the field on the left, so 20 < price behaves like price > 20
        if (!left.isField() && right.isField()) {
            return compare(right.value(), flip(operator), left.value());
        }
        return compare(left.value(), operator, right.value());
    }

    private Truth compare(JsonNode left, String operator, JsonNode right) {
        if (right.isContainerNode()) {
            return Truth.FALSE;
        }
        String compareValue = right.asText();

        return Truth.of(switch (operator) {
            case "=" -> compareEquals(left, compareValue);
            case "!=" -> !compareEquals(left, compareValue);
            case ">" -> compareGreater(left, compareValue);
            case "<" -> compareLess(left, compareValue);
            case ">=" -> compareGreater(left, compareValue) || compareEquals(left, compareValue);
            case "<=" -> compareLess(left, compareValue) || compareEquals(left, compareValue);
            default -> false;
        });
    }

    private static String flip(String operator) {
        return switch (operator) {
            case ">" -> "<";
            case "<" -> ">";
            case ">=" -> "<=";
            case "<=" -> ">=";
            default -> operator;
        };
    }

    /**
     * Resolve one side of a condition. The parser has already rejected anything that isn't
     * a field or a literal. As in SQLite, a double-quoted name that isn't a field is read as text.
     */
    private Operand resolve(JsonNode row, Expression expr) {
        if (expr instanceof Parenthesis) {
            return resolve(row, ((Parenthesis) expr).getExpression());
        }
        if (expr instanceof NullValue) {
            return new Operand(null, false);
        }
        if (expr instanceof StringValue) {
            return new Operand(TextNode.valueOf(((StringValue) expr).getNotExcapedValue()), false);
        }
        if (expr instanceof LongValue) {
            return new Operand(BigIntegerNode.valueOf(((LongValue) expr).getBigIntegerValue()), false);
        }
        if (expr instanceof DoubleValue) {
            return new Operand(DoubleNode.valueOf(((DoubleValue) expr).getValue()), false);
        }
        if (expr instanceof SignedExpression) {
            return new Operand(DecimalNode.valueOf(new BigDecimal(expr.toString().replace(" ", ""))), false);
        }
        if (expr instanceof Column) {
            return resolveColumn(row, (Column) expr);
        }
        if (AggregateCall.isAggregate(expr)) {
            // Only reachable from HAVING, where each row already holds its aggregate results
            JsonNode value = fieldAccessor.getFieldValue(row, QueryParser.fieldName(expr));
            return new Operand(value == null || value.isNull() ? null : value, true);
        }
        throw new UnsupportedExpressionException(
            "Unsupported WHERE expression: " + expr + " (type: " + expr.getClass().getSimpleName() + ")"
        );
    }

    private Operand resolveColumn(JsonNode row, Column column) {
        String name = QueryParser.fieldName(column);
        if (column.getTable() == null) {
            if (name.equalsIgnoreCase("true")) {
                return new Operand(BooleanNode.TRUE, false);
            }
            if (name.equalsIgnoreCase("false")) {
                return new Operand(BooleanNode.FALSE, false);
            }
        }
        JsonNode value = fieldAccessor.getFieldValue(row, name);
        if (value == null && column.toString().startsWith("\"")) {
            return new Operand(TextNode.valueOf(name), false);
        }
        return new Operand(value == null || value.isNull() ? null : value, true);
    }

    private boolean compareEquals(JsonNode fieldValue, String compareValue) {
        // Handle null values - in SQL, NULL = anything is always false
        if (fieldValue.isNull()) {
            return false;
        }
        
        if (fieldValue.isTextual()) {
            return fieldValue.asText().equals(compareValue);
        } else if (fieldValue.isNumber()) {
            try {
                return Math.abs(fieldValue.asDouble() - Double.parseDouble(compareValue)) < 0.0001;
            } catch (NumberFormatException e) {
                return false;
            }
        } else if (fieldValue.isBoolean()) {
            return fieldValue.asBoolean() == Boolean.parseBoolean(compareValue);
        }
        return false;
    }
    
    private boolean compareGreater(JsonNode fieldValue, String compareValue) {
        // Handle null values - in SQL, NULL > anything is always false
        if (fieldValue.isNull()) {
            return false;
        }
        
        if (fieldValue.isNumber()) {
            try {
                return fieldValue.asDouble() > Double.parseDouble(compareValue);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        // Fall back to lexicographic comparison for textual values
        if (fieldValue.isTextual()) {
            return fieldValue.asText().compareTo(compareValue) > 0;
        }
        return false;
    }
    
    private boolean compareLess(JsonNode fieldValue, String compareValue) {
        // Handle null values - in SQL, NULL < anything is always false
        if (fieldValue.isNull()) {
            return false;
        }
        
        if (fieldValue.isNumber()) {
            try {
                return fieldValue.asDouble() < Double.parseDouble(compareValue);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        // Fall back to lexicographic comparison for textual values
        if (fieldValue.isTextual()) {
            return fieldValue.asText().compareTo(compareValue) < 0;
        }
        return false;
    }
    
    /**
     * Evaluate LIKE expression for pattern matching.
     * Supports SQL wildcards: % (any characters) and _ (single character).
     * @param caseInsensitive If true, performs case-insensitive matching (ILIKE behavior)
     */
    private Truth evaluateLike(JsonNode row, LikeExpression likeExpr, boolean caseInsensitive) {
        // Get field value
        String fieldPath = QueryParser.fieldName(likeExpr.getLeftExpression());
        JsonNode fieldValue = fieldAccessor.getFieldValue(row, fieldPath);
        JsonNode patternValue = resolve(row, likeExpr.getRightExpression()).value();

        // LIKE only applies to strings, so anything else is UNKNOWN. That excludes it from
        // NOT LIKE as well as LIKE, and from NOT (x LIKE ...).
        if (fieldValue == null || !fieldValue.isTextual() || patternValue == null) {
            return Truth.UNKNOWN;
        }

        String fieldText = fieldValue.asText();
        String pattern = patternValue.asText();
        Character escape = QueryParser.likeEscape(likeExpr);

        // Convert to lowercase for case-insensitive matching
        if (caseInsensitive) {
            fieldText = fieldText.toLowerCase();
            pattern = pattern.toLowerCase();
        }

        // Convert SQL LIKE pattern to regex
        String regexPattern = convertLikePatternToRegex(pattern, escape);
        
        // Evaluate pattern match using a cached compiled pattern (we already lowercased above
        // when case-insensitive, so no flags are needed here).
        String cacheKey = (caseInsensitive ? "i:" : "s:") + regexPattern;
        Pattern compiled = patternCache.computeIfAbsent(cacheKey, k -> Pattern.compile(regexPattern));
        boolean matches = compiled.matcher(fieldText).matches();
        
        // Handle NOT LIKE / NOT ILIKE
        return Truth.of(likeExpr.isNot() ? !matches : matches);
    }
    
    /**
     * Evaluate IS NULL / IS NOT NULL expression.
     * A field is considered NULL if:
     * 1. The field is missing from the JSON object (fieldValue == null)
     * 2. The field exists but is explicitly set to null (fieldValue.isNull())
     */
    private Truth evaluateIsNull(JsonNode row, IsNullExpression isNullExpr) {
        // Get field value
        String fieldPath = QueryParser.fieldName(isNullExpr.getLeftExpression());
        JsonNode fieldValue = fieldAccessor.getFieldValue(row, fieldPath);
        
        // Check if field is null (missing or explicitly null)
        boolean isNull = (fieldValue == null || fieldValue.isNull());
        
        // Handle IS NOT NULL
        return Truth.of(isNullExpr.isNot() ? !isNull : isNull);
    }
    
    /**
     * Evaluate IN / NOT IN expression.
     * Checks if a field value matches any value in a list.
     * Example: WHERE category IN ('Electronics', 'Tools', 'Furniture')
     */
    private Truth evaluateIn(JsonNode row, InExpression inExpr) {
        // Get field value
        String fieldPath = QueryParser.fieldName(inExpr.getLeftExpression());
        JsonNode fieldValue = fieldAccessor.getFieldValue(row, fieldPath);
        
        // In SQL, NULL IN (...) and NULL NOT IN (...) are both UNKNOWN
        // This means NULL values are ALWAYS excluded from IN/NOT IN results
        if (fieldValue == null || fieldValue.isNull()) {
            return Truth.UNKNOWN;
        }
        
        // Get the list of values to check against
        var rightExpression = inExpr.getRightExpression();
        
        // Handle ExpressionList (the typical case)
        if (rightExpression instanceof ParenthesedExpressionList) {
            ParenthesedExpressionList<?> expressionList = (ParenthesedExpressionList<?>) rightExpression;
            
            // ExpressionList extends List directly (getExpressions() is deprecated)
            boolean listHasNull = false;
            for (var expr : expressionList) {
                JsonNode listValue = resolve(row, (Expression) expr).value();
                if (listValue == null) {
                    listHasNull = true;
                    continue;
                }
                if (compareEqualsForIn(fieldValue, listValue.asText())) {
                    // Found a match
                    return Truth.of(!inExpr.isNot()); // IN returns true, NOT IN returns false
                }
            }

            // No match found: a NULL in the list could have matched, so the answer is UNKNOWN
            return listHasNull ? Truth.UNKNOWN : Truth.of(inExpr.isNot());
        }
        
        // Unsupported right expression type - fail loudly instead of silently excluding all rows
        throw new UnsupportedExpressionException(
            "Unsupported IN expression right-hand side: " + inExpr +
            " (type: " + (rightExpression == null ? "null" : rightExpression.getClass().getSimpleName()) + ")"
        );
    }
    
    /**
     * Compare values for IN operator (similar to equals but optimized for IN).
     */
    private boolean compareEqualsForIn(JsonNode fieldValue, String compareValue) {
        if (fieldValue.isTextual()) {
            return fieldValue.asText().equals(compareValue);
        } else if (fieldValue.isNumber()) {
            try {
                // Always use double comparison for flexibility with both int and decimal
                double fieldDouble = fieldValue.asDouble();
                double compareDouble = Double.parseDouble(compareValue);
                return Math.abs(fieldDouble - compareDouble) < 0.0001;
            } catch (NumberFormatException e) {
                return false;
            }
        } else if (fieldValue.isBoolean()) {
            return fieldValue.asBoolean() == Boolean.parseBoolean(compareValue);
        }
        return false;
    }
    
    /**
     * Convert SQL LIKE pattern to Java regex pattern.
     * - % matches zero or more characters (converted to .*)
     * - _ matches exactly one character (converted to .)
     * - All other regex special characters are escaped
     * - The ESCAPE character, if any, makes the next character literal
     */
    private String convertLikePatternToRegex(String likePattern, Character escape) {
        StringBuilder regex = new StringBuilder();

        for (int i = 0; i < likePattern.length(); i++) {
            char c = likePattern.charAt(i);

            if (escape != null && c == escape && i + 1 < likePattern.length()) {
                i++;
                regex.append(Pattern.quote(String.valueOf(likePattern.charAt(i))));
                continue;
            }

            switch (c) {
                case '%':
                    // % matches zero or more characters
                    regex.append(".*");
                    break;
                case '_':
                    // _ matches exactly one character
                    regex.append(".");
                    break;
                case '.':
                case '^':
                case '$':
                case '*':
                case '+':
                case '?':
                case '(':
                case ')':
                case '[':
                case ']':
                case '{':
                case '}':
                case '\\':
                case '|':
                    // Escape regex special characters
                    regex.append("\\").append(c);
                    break;
                default:
                    regex.append(c);
            }
        }
        
        return regex.toString();
    }
}

