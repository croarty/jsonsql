package com.jsonsql.query;

/**
 * Represents information about a table (or table alias) in a query.
 */
public class TableInfo {
    private String tableName;
    private String alias;

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getEffectiveName() {
        return alias != null ? alias : tableName;
    }
}

