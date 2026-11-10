package com.jsonsql.query;

import net.sf.jsqlparser.expression.Expression;

/**
 * Represents information about an ORDER BY clause.
 *
 * A sort key that is calculated, such as {@code price * qty}, also holds its expression.
 * Its value is worked out once per row and kept in the row under {@link #getColumn()}.
 */
public class OrderByInfo {
    private String column;
    private boolean ascending;
    private final Expression calculation;

    public OrderByInfo(String column, boolean ascending) {
        this(column, ascending, null);
    }

    OrderByInfo(String column, boolean ascending, Expression calculation) {
        this.column = column;
        this.ascending = ascending;
        this.calculation = calculation;
    }

    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public boolean isAscending() {
        return ascending;
    }

    public void setAscending(boolean ascending) {
        this.ascending = ascending;
    }

    /** The expression to calculate, or null when sorting by a field. */
    public Expression getCalculation() {
        return calculation;
    }
}

