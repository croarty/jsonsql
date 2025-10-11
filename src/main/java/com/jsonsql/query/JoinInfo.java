package com.jsonsql.query;

/**
 * Represents information about a JOIN clause.
 */
public class JoinInfo {
    private TableInfo table;
    private String onCondition;
    private boolean leftJoin;

    public TableInfo getTable() {
        return table;
    }

    public void setTable(TableInfo table) {
        this.table = table;
    }

    public String getOnCondition() {
        return onCondition;
    }

    public void setOnCondition(String onCondition) {
        this.onCondition = onCondition;
    }

    public boolean isLeftJoin() {
        return leftJoin;
    }

    public void setLeftJoin(boolean leftJoin) {
        this.leftJoin = leftJoin;
    }
}

