package com.jsonsql.query;

import java.util.ArrayList;
import java.util.List;

/**
 * A set operation: SELECT queries joined by UNION, UNION ALL, INTERSECT or EXCEPT, which run
 * left to right.
 */
public class UnionQuery {

    /** An operator between two branches. */
    public enum Operator {
        UNION("UNION"), UNION_ALL("UNION ALL"), INTERSECT("INTERSECT"), EXCEPT("EXCEPT");

        private final String sql;

        Operator(String sql) {
            this.sql = sql;
        }

        /** How the operator is written in SQL. */
        public String sql() {
            return sql;
        }
    }

    private List<ParsedQuery> subQueries = new ArrayList<>();
    // One entry per operator between consecutive sub-queries
    private final List<Operator> operators = new ArrayList<>();
    private List<OrderByInfo> orderBy = new ArrayList<>();
    private Long limit;
    private Long top;
    private Long offset;

    public List<ParsedQuery> getSubQueries() {
        return subQueries;
    }

    public void setSubQueries(List<ParsedQuery> subQueries) {
        this.subQueries = subQueries;
    }

    /** Record the operator joining the next sub-query to the ones before it. */
    public void addOperation(Operator operator) {
        operators.add(operator);
    }

    /**
     * The operator between sub-query {@code index} and {@code index + 1}.
     */
    public Operator operatorAt(int index) {
        return operators.get(index);
    }

    public List<OrderByInfo> getOrderBy() {
        return orderBy;
    }

    public void setOrderBy(List<OrderByInfo> orderBy) {
        this.orderBy = orderBy;
    }

    public Long getLimit() {
        return limit;
    }

    public void setLimit(Long limit) {
        this.limit = limit;
    }

    public Long getOffset() {
        return offset;
    }

    public void setOffset(Long offset) {
        this.offset = offset;
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

    public boolean hasOrderBy() {
        return orderBy != null && !orderBy.isEmpty();
    }

    /**
     * Add a sub-query to the UNION.
     */
    public void addSubQuery(ParsedQuery query) {
        subQueries.add(query);
    }

    /**
     * Validate that all sub-queries have the same column count. The message names the
     * operator before the branch that differs, and says UNION, as it always has, when every
     * operator is a UNION.
     */
    public void validateColumnConsistency() throws QueryParseException {
        if (subQueries.isEmpty()) {
            throw new QueryParseException("UNION must have at least two queries");
        }

        // Get column count from first query
        int expectedColumns = -1;
        for (int i = 0; i < subQueries.size(); i++) {
            ParsedQuery query = subQueries.get(i);
            if (query.getSelectColumns().isEmpty()) {
                throw new QueryParseException("UNION query " + (i + 1) + " has no SELECT columns");
            }
            
            int columnCount = query.getSelectColumns().size();
            if (expectedColumns == -1) {
                expectedColumns = columnCount;
            } else if (columnCount != expectedColumns) {
                throw new QueryParseException(
                    operatorWord(i - 1) + " queries must have the same number of columns. " +
                    "Query 1 has " + expectedColumns + " columns, query " + (i + 1) + 
                    " has " + columnCount + " columns."
                );
            }
        }
    }

    private String operatorWord(int index) {
        boolean allUnions = operators.stream().allMatch(o -> o == Operator.UNION || o == Operator.UNION_ALL);
        if (allUnions || index >= operators.size()) {
            return "UNION";
        }
        Operator operator = operators.get(index);
        return operator == Operator.UNION_ALL ? "UNION" : operator.sql();
    }
}
