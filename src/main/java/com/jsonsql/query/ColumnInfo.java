package com.jsonsql.query;

import net.sf.jsqlparser.expression.Expression;

/**
 * Represents information about a column in SELECT clause, including optional alias.
 *
 * A column that is calculated, such as {@code price * 2}, also holds its expression. Its
 * value is worked out once per row and kept in the row under {@link #getExpression()}, so
 * ORDER BY and projection read it like any field. Its alias is always set: to the one
 * given, or to the expression's text.
 */
public class ColumnInfo {
    // Not a character a field name would start with, and not one that trim() removes
    private static final String COMPUTED_PREFIX = "￿calculated";

    private final String expression;
    private final String alias;
    private final Expression computed;

    public ColumnInfo(String expression, String alias) {
        this.expression = expression;
        this.alias = alias;
        this.computed = null;
    }

    private ColumnInfo(String expression, String alias, Expression computed) {
        this.expression = expression;
        this.alias = alias;
        this.computed = computed;
    }

    /**
     * A calculated column.
     *
     * @param position its position in the SELECT list, which keeps its row key unique
     * @param name     its alias, or null to name it after the expression
     */
    static ColumnInfo computed(int position, Expression expression, String name) {
        return new ColumnInfo(COMPUTED_PREFIX + position, name == null ? expression.toString() : name, expression);
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

    public boolean isComputed() {
        return computed != null;
    }

    /** The expression to calculate, or null for a plain field. */
    public Expression getComputed() {
        return computed;
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
