package com.jsonsql.query;

/**
 * Represents information about a column in SELECT clause, including optional alias.
 */
public class ColumnInfo {
    private final String expression;
    private final String alias;

    public ColumnInfo(String expression, String alias) {
        this.expression = expression;
        this.alias = alias;
    }

    public String getExpression() {
        return expression;
    }

    public String getAlias() {
        return alias;
    }

    public boolean hasAlias() {
        return alias != null && !alias.isEmpty();
    }

    /**
     * Get the output name to use - either the alias or the simple field name.
     */
    public String getOutputName() {
        if (hasAlias()) {
            return alias;
        }
        // Extract simple name from expression (last part after final dot)
        return expression.contains(".") 
            ? expression.substring(expression.lastIndexOf('.') + 1)
            : expression;
    }
}

