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
            return evaluateLike(row, (LikeExpression) expression);
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
     */
    private boolean evaluateLike(JsonNode row, LikeExpression likeExpr) {
        // Get field value
        String fieldPath = likeExpr.getLeftExpression().toString();
        JsonNode fieldValue = fieldAccessor.getFieldValue(row, fieldPath);
        
        if (fieldValue == null || !fieldValue.isTextual()) {
            return false;
        }
        
        String fieldText = fieldValue.asText();
        String pattern = extractLiteralValue(likeExpr.getRightExpression());
        
        // Convert SQL LIKE pattern to regex
        String regexPattern = convertLikePatternToRegex(pattern);
        
        // Evaluate pattern match
        boolean matches = Pattern.matches(regexPattern, fieldText);
        
        // Handle NOT LIKE
        return likeExpr.isNot() ? !matches : matches;
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

