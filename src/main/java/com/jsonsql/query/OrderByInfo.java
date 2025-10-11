package com.jsonsql.query;

/**
 * Represents information about an ORDER BY clause.
 */
public class OrderByInfo {
    private String column;
    private boolean ascending;

    public OrderByInfo(String column, boolean ascending) {
        this.column = column;
        this.ascending = ascending;
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
}

