package com.jsonsql.query;

import net.sf.jsqlparser.expression.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a parsed SQL query with all its components.
 */
public class ParsedQuery {
    private List<ColumnInfo> selectColumns = new ArrayList<>();
    private TableInfo fromTable;
    private List<JoinInfo> joins = new ArrayList<>();
    private Expression whereExpression;
    private List<OrderByInfo> orderBy = new ArrayList<>();
    private Long limit;
    private Long top;

    public List<ColumnInfo> getSelectColumns() {
        return selectColumns;
    }

    public void setSelectColumns(List<ColumnInfo> selectColumns) {
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

    public Expression getWhereExpression() {
        return whereExpression;
    }

    public void setWhereExpression(Expression whereExpression) {
        this.whereExpression = whereExpression;
    }
    
    public boolean hasWhere() {
        return whereExpression != null;
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

