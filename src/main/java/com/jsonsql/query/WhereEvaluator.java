package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;

import java.util.regex.Pattern;

/**
 * Evaluates WHERE clause expressions against JSON data.
 * Supports complex boolean logic (AND, OR, NOT, parentheses) and pattern matching (LIKE).
 */
public class WhereEvaluator {
    
    private final FieldAccessor fieldAccessor;
    
    public WhereEvaluator(FieldAccessor fieldAccessor) {
        this.fieldAccessor = fieldAccessor;
    }
    
    /**
     * Evaluate an expression against a JSON row and return the boolean result.
     */
    public boolean evaluate(JsonNode row, Expression expression) {
        if (expression == null) {
            return true;
        }
        
        // Handle AND
        if (expression instanceof AndExpression) {
            AndExpression andExpr = (AndExpression) expression;
            return evaluate(row, andExpr.getLeftExpression()) && 
                   evaluate(row, andExpr.getRightExpression());
        }
        
        // Handle OR
        if (expression instanceof OrExpression) {
            OrExpression orExpr = (OrExpression) expression;
            return evaluate(row, orExpr.getLeftExpression()) || 
                   evaluate(row, orExpr.getRightExpression());
        }
        
        // Handle NOT
        if (expression instanceof NotExpression) {
            NotExpression notExpr = (NotExpression) expression;
            return !evaluate(row, notExpr.getExpression());
        }
        
        // Handle Parentheses
        if (expression instanceof Parenthesis) {
            Parenthesis paren = (Parenthesis) expression;
            return evaluate(row, paren.getExpression());
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
            // Check if this is ILIKE (case-insensitive LIKE)
            // JSqlParser may parse ILIKE as LikeExpression, so we check the string representation
            String exprString = expression.toString().toUpperCase();
            boolean caseInsensitive = exprString.contains("ILIKE");
            return evaluateLike(row, likeExpr, caseInsensitive);
        }
        
        // Handle IS NULL / IS NOT NULL
        if (expression instanceof IsNullExpression) {
            return evaluateIsNull(row, (IsNullExpression) expression);
        }
        
        // Handle IN / NOT IN
        if (expression instanceof InExpression) {
            return evaluateIn(row, (InExpression) expression);
        }
        
        // Unsupported expression type
        return false;
    }
    
    private boolean evaluateComparison(JsonNode row, ComparisonOperator comparison, String operator) {
        // Get field value
        String fieldPath = comparison.getLeftExpression().toString();
        JsonNode fieldValue = fieldAccessor.getFieldValue(row, fieldPath);
        
        // In SQL, any comparison with NULL returns NULL (treated as FALSE in WHERE clauses)
        // This includes both missing fields (fieldValue == null) and present but null fields (fieldValue.isNull())
        if (fieldValue == null || fieldValue.isNull()) {
            return false;
        }
        
        // Get comparison value
        String compareValue = extractLiteralValue(comparison.getRightExpression());
        
        return switch (operator) {
            case "=" -> compareEquals(fieldValue, compareValue);
            case "!=" -> !compareEquals(fieldValue, compareValue);
            case ">" -> compareGreater(fieldValue, compareValue);
            case "<" -> compareLess(fieldValue, compareValue);
            case ">=" -> compareGreater(fieldValue, compareValue) || compareEquals(fieldValue, compareValue);
            case "<=" -> compareLess(fieldValue, compareValue) || compareEquals(fieldValue, compareValue);
            default -> false;
        };
    }
    
    private String extractLiteralValue(Expression expr) {
        if (expr instanceof StringValue) {
            return ((StringValue) expr).getValue();
        } else if (expr instanceof LongValue) {
            return String.valueOf(((LongValue) expr).getValue());
        } else if (expr instanceof DoubleValue) {
            return String.valueOf(((DoubleValue) expr).getValue());
        } else {
            // Fallback to string representation
            String value = expr.toString();
            // Remove quotes if present
            if (value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            } else if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
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
        return false;
    }
    
    /**
     * Evaluate LIKE expression for pattern matching.
     * Supports SQL wildcards: % (any characters) and _ (single character).
     * @param caseInsensitive If true, performs case-insensitive matching (ILIKE behavior)
     */
    private boolean evaluateLike(JsonNode row, LikeExpression likeExpr, boolean caseInsensitive) {
        // Get field value
        String fieldPath = likeExpr.getLeftExpression().toString();
        JsonNode fieldValue = fieldAccessor.getFieldValue(row, fieldPath);
        
        if (fieldValue == null || !fieldValue.isTextual()) {
            return false;
        }
        
        String fieldText = fieldValue.asText();
        String pattern = extractLiteralValue(likeExpr.getRightExpression());
        
        // Convert to lowercase for case-insensitive matching
        if (caseInsensitive) {
            fieldText = fieldText.toLowerCase();
            pattern = pattern.toLowerCase();
        }
        
        // Convert SQL LIKE pattern to regex
        String regexPattern = convertLikePatternToRegex(pattern);
        
        // Evaluate pattern match
        // Use CASE_INSENSITIVE flag if needed (though we already lowercased)
        int flags = 0;
        boolean matches = Pattern.compile(regexPattern, flags).matcher(fieldText).matches();
        
        // Handle NOT LIKE / NOT ILIKE
        return likeExpr.isNot() ? !matches : matches;
    }
    
    /**
     * Evaluate IS NULL / IS NOT NULL expression.
     * A field is considered NULL if:
     * 1. The field is missing from the JSON object (fieldValue == null)
     * 2. The field exists but is explicitly set to null (fieldValue.isNull())
     */
    private boolean evaluateIsNull(JsonNode row, IsNullExpression isNullExpr) {
        // Get field value
        String fieldPath = isNullExpr.getLeftExpression().toString();
        JsonNode fieldValue = fieldAccessor.getFieldValue(row, fieldPath);
        
        // Check if field is null (missing or explicitly null)
        boolean isNull = (fieldValue == null || fieldValue.isNull());
        
        // Handle IS NOT NULL
        return isNullExpr.isNot() ? !isNull : isNull;
    }
    
    /**
     * Evaluate IN / NOT IN expression.
     * Checks if a field value matches any value in a list.
     * Example: WHERE category IN ('Electronics', 'Tools', 'Furniture')
     */
    private boolean evaluateIn(JsonNode row, InExpression inExpr) {
        // Get field value
        String fieldPath = inExpr.getLeftExpression().toString();
        JsonNode fieldValue = fieldAccessor.getFieldValue(row, fieldPath);
        
        // In SQL, NULL IN (...) returns UNKNOWN (treated as FALSE)
        // NULL NOT IN (...) also returns UNKNOWN (treated as FALSE)
        // This means NULL values are ALWAYS excluded from IN/NOT IN results
        if (fieldValue == null || fieldValue.isNull()) {
            return false;
        }
        
        // Get the list of values to check against
        var rightExpression = inExpr.getRightExpression();
        
        // Handle ExpressionList (the typical case)
        if (rightExpression instanceof ParenthesedExpressionList) {
            ParenthesedExpressionList<?> expressionList = (ParenthesedExpressionList<?>) rightExpression;
            var expressions = expressionList.getExpressions();
            
            // Check if field value matches any value in the list
            for (var expr : expressions) {
                String listValue = extractLiteralValue(expr);
                if (compareEqualsForIn(fieldValue, listValue)) {
                    // Found a match
                    return !inExpr.isNot(); // IN returns true, NOT IN returns false
                }
            }
            
            // No match found
            return inExpr.isNot(); // IN returns false, NOT IN returns true
        }
        
        // Unsupported right expression type
        return false;
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
     */
    private String convertLikePatternToRegex(String likePattern) {
        StringBuilder regex = new StringBuilder();
        
        for (int i = 0; i < likePattern.length(); i++) {
            char c = likePattern.charAt(i);
            
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

