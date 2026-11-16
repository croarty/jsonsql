package com.jsonsql.query;

import net.sf.jsqlparser.expression.Expression;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a parsed SQL query with all its components.
 */
public class ParsedQuery {
    private List<ColumnInfo> selectColumns = new ArrayList<>();
    private TableInfo fromTable;
    private List<JoinInfo> joins = new ArrayList<>();
    private List<UnnestInfo> unnests = new ArrayList<>();
    private Expression whereExpression;
    private List<OrderByInfo> orderBy = new ArrayList<>();
    private Long limit;
    private Long top;
    private Long offset;
    private List<String> groupBy = new ArrayList<>();
    private final Map<String, Expression> groupCalculations = new LinkedHashMap<>();
    private Expression having;
    private final Map<String, AggregateCall> aggregates = new LinkedHashMap<>();
    private boolean distinct = false;
    private java.util.Map<String, ParsedQuery> commonTableExpressions = new java.util.LinkedHashMap<>();

    // UNION query support
    private UnionQuery unionQuery;

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

    public List<String> getGroupBy() {
        return groupBy;
    }

    public void setGroupBy(List<String> groupBy) {
        this.groupBy = groupBy;
    }

    /**
     * The GROUP BY keys that are calculations, such as {@code qty % 2}, by their text. A key
     * that isn't here is a field.
     */
    Map<String, Expression> getGroupCalculations() {
        return Collections.unmodifiableMap(groupCalculations);
    }

    /**
     * Record a GROUP BY key that is a calculation.
     *
     * @return its key, which is the calculation's text
     */
    String addGroupCalculation(Expression calculation) {
        groupCalculations.put(calculation.toString(), calculation);
        return calculation.toString();
    }

    public Expression getHaving() {
        return having;
    }

    public void setHaving(Expression having) {
        this.having = having;
    }

    Collection<AggregateCall> getAggregates() {
        return aggregates.values();
    }

    void addAggregate(AggregateCall call) {
        aggregates.put(call.key(), call);
    }

    boolean hasAggregate(String key) {
        return aggregates.containsKey(key);
    }

    /** True if the query produces one row per group rather than one per source row. */
    public boolean isAggregate() {
        return !groupBy.isEmpty() || !aggregates.isEmpty();
    }

    public Long getOffset() {
        return offset;
    }

    public void setOffset(Long offset) {
        this.offset = offset;
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

    public List<UnnestInfo> getUnnests() {
        return unnests;
    }

    public void setUnnests(List<UnnestInfo> unnests) {
        this.unnests = unnests;
    }

    public boolean hasUnnests() {
        return unnests != null && !unnests.isEmpty();
    }

    public boolean isDistinct() {
        return distinct;
    }

    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }

    public java.util.Map<String, ParsedQuery> getCommonTableExpressions() {
        return commonTableExpressions;
    }

    public void setCommonTableExpressions(java.util.Map<String, ParsedQuery> commonTableExpressions) {
        this.commonTableExpressions = commonTableExpressions;
    }

    public void addCTE(String name, ParsedQuery cteQuery) {
        this.commonTableExpressions.put(name, cteQuery);
    }

    public boolean hasCteNamed(String name) {
        return CteNames.resolveKey(commonTableExpressions, name) != null;
    }

    public ParsedQuery getCteNamed(String name) {
        String key = CteNames.resolveKey(commonTableExpressions, name);
        return key != null ? commonTableExpressions.get(key) : null;
    }

    public boolean hasCTEs() {
        return commonTableExpressions != null && !commonTableExpressions.isEmpty();
    }

    /**
     * A subquery used as a value, (SELECT ...), or after IN.
     *
     * @param node  where it is written; its result is looked up by this node, by identity
     * @param query the subquery: a plain query or a set operation
     * @param list  true after IN, where every row counts; false for one value
     */
    public record ValueSubquery(Expression node, ParsedQuery query, boolean list) {}

    private final List<ValueSubquery> valueSubqueries = new ArrayList<>();

    /** Record a subquery written in this query, which runs before this query's rows. */
    public void addValueSubquery(Expression node, ParsedQuery query, boolean list) {
        // The same node seen twice, such as a GROUP BY key written out again, runs once
        if (valueSubqueries.stream().noneMatch(subquery -> subquery.node() == node)) {
            valueSubqueries.add(new ValueSubquery(node, query, list));
        }
    }

    /** The subqueries written in this query as values or after IN, in the order parsed. */
    public List<ValueSubquery> getValueSubqueries() {
        return Collections.unmodifiableList(valueSubqueries);
    }

    /**
     * The queries nested in this one: the derived tables in its FROM and joins, and the
     * subqueries it uses as values or after IN, in that order. Each is a plain query or a
     * set operation, and may hold nested queries of its own.
     */
    public List<ParsedQuery> nestedQueries() {
        List<ParsedQuery> nested = new ArrayList<>();
        if (fromTable != null && fromTable.getSubquery() != null) {
            nested.add(fromTable.getSubquery());
        }
        if (joins != null) {
            for (JoinInfo join : joins) {
                if (join.getTable() != null && join.getTable().getSubquery() != null) {
                    nested.add(join.getTable().getSubquery());
                }
            }
        }
        for (ValueSubquery subquery : valueSubqueries) {
            nested.add(subquery.query());
        }
        return nested;
    }

    public UnionQuery getUnionQuery() {
        return unionQuery;
    }

    public void setUnionQuery(UnionQuery unionQuery) {
        this.unionQuery = unionQuery;
    }

    public boolean isUnionQuery() {
        return unionQuery != null;
    }
}

