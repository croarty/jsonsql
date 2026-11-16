package com.jsonsql.query;

import net.sf.jsqlparser.expression.Expression;

/**
 * Represents information about a JOIN clause.
 */
public class JoinInfo {

    /**
     * Which rows a join keeps. CROSS pairs every row with every row and has no ON.
     */
    public enum Type {
        INNER, LEFT, RIGHT, FULL, CROSS;

        /** Whether rows of the rows so far that match nothing are kept. */
        boolean keepsUnmatchedLeft() {
            return this == LEFT || this == FULL;
        }

        /** Whether rows of the joined table that match nothing are kept. */
        boolean keepsUnmatchedRight() {
            return this == RIGHT || this == FULL;
        }
    }

    private TableInfo table;
    private Expression on;
    private String onCondition;
    private Type type = Type.INNER;

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

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public boolean isLeftJoin() {
        return type == Type.LEFT;
    }

    public void setLeftJoin(boolean leftJoin) {
        this.type = leftJoin ? Type.LEFT : Type.INNER;
    }
}

