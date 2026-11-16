package com.jsonsql.query;

import net.sf.jsqlparser.expression.Expression;

/**
 * Represents information about a JOIN clause.
 */
public class JoinInfo {
    private TableInfo table;
    private Expression on;
    private String onCondition;
    private boolean leftJoin;

    public TableInfo getTable() {
        return table;
    }

    public void setTable(TableInfo table) {
        this.table = table;
    }

    /**
     * The ON condition. It can be anything WHERE accepts.
     */
    public Expression getOn() {
        return on;
    }

    /**
     * Sets the ON condition, and its text, which the CTE cache key uses.
     */
    public void setOn(Expression on) {
        this.on = on;
        this.onCondition = on == null ? null : on.toString();
    }

    public String getOnCondition() {
        return onCondition;
    }

    public boolean isLeftJoin() {
        return leftJoin;
    }

    public void setLeftJoin(boolean leftJoin) {
        this.leftJoin = leftJoin;
    }
}

