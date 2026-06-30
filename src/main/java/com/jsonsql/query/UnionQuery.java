package com.jsonsql.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a UNION or UNION ALL query that combines multiple SELECT queries.
 */
public class UnionQuery {
    private List<ParsedQuery> subQueries = new ArrayList<>();
    private boolean isAll;  // true = UNION ALL (keep duplicates), false = UNION (dedupe)
    private List<OrderByInfo> orderBy = new ArrayList<>();
    private Long limit;
    private Long top;

    public List<ParsedQuery> getSubQueries() {
        return subQueries;
    }

    public void setSubQueries(List<ParsedQuery> subQueries) {
        this.subQueries = subQueries;
    }

    public boolean isAll() {
        return isAll;
    }

    public void setIsAll(boolean isAll) {
        this.isAll = isAll;
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
     * Validate that all sub-queries have the same column count.
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
                    "UNION queries must have the same number of columns. " +
                    "Query 1 has " + expectedColumns + " columns, query " + (i + 1) + 
                    " has " + columnCount + " columns."
                );
            }
        }
    }
}
