package com.jsonsql.query;

/**
 * Represents information about a table (or table alias) in a query. For a derived table,
 * FROM (SELECT ...) t, the name is the subquery's SQL text and the alias is required.
 */
public class TableInfo {
    private String tableName;
    private String alias;
    private ParsedQuery subquery;

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

    /**
     * The query of a derived table, FROM (SELECT ...) t, or null for a mapped table, a CTE
     * or a materialized view.
     */
    public ParsedQuery getSubquery() {
        return subquery;
    }

    public void setSubquery(ParsedQuery subquery) {
        this.subquery = subquery;
    }
}

