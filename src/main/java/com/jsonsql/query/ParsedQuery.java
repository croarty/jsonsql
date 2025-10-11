package com.jsonsql.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a parsed SQL query with all its components.
 */
public class ParsedQuery {
    private List<String> selectColumns = new ArrayList<>();
    private TableInfo fromTable;
    private List<JoinInfo> joins = new ArrayList<>();
    private String whereClause;
    private List<OrderByInfo> orderBy = new ArrayList<>();
    private Long limit;
    private Long top;

    public List<String> getSelectColumns() {
        return selectColumns;
    }

    public void setSelectColumns(List<String> selectColumns) {
        this.selectColumns = selectColumns;
    }

    public TableInfo getFromTable() {
        return fromTable;
    }

    public void setFromTable(TableInfo fromTable) {
        this.fromTable = fromTable;
    }

    public List<JoinInfo> getJoins() {
        return joins;
    }

    public void setJoins(List<JoinInfo> joins) {
        this.joins = joins;
    }

    public String getWhereClause() {
        return whereClause;
    }

    public void setWhereClause(String whereClause) {
        this.whereClause = whereClause;
    }

    public Long getLimit() {
        return limit;
    }

    public void setLimit(Long limit) {
        this.limit = limit;
    }

    public Long getTop() {
        return top;
    }

    public void setTop(Long top) {
        this.top = top;
    }

    public Long getEffectiveLimit() {
        if (top != null) {
            return top;
        }
        return limit;
    }

    public boolean hasJoins() {
        return joins != null && !joins.isEmpty();
    }

    public List<OrderByInfo> getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(List<OrderByInfo> orderBy) {
        this.orderBy = orderBy;
    }

    public boolean hasOrderBy() {
        return orderBy != null && !orderBy.isEmpty();
    }
}

