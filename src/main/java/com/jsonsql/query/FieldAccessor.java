package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import net.sf.jsqlparser.expression.Expression;

import java.util.List;

/**
 * Interface for accessing field values from JSON rows, and the results of the subqueries a
 * query uses as values, which run before its rows.
 */
public interface FieldAccessor {
    /**
     * Get field value from a row using flexible field path resolution.
     */
    JsonNode getFieldValue(JsonNode row, String fieldPath);

    /**
     * The value of a subquery used as a value, (SELECT ...), or null for SQL NULL.
     *
     * @param subquery the subquery as written, which identifies it
     */
    default JsonNode subqueryValue(Expression subquery) {
        throw new IllegalStateException("No result for the subquery " + subquery);
    }

    /**
     * The values a subquery after IN returned, with null for SQL NULL.
     *
     * @param subquery the subquery as written, which identifies it
     */
    default List<JsonNode> subqueryValues(Expression subquery) {
        throw new IllegalStateException("No result for the subquery " + subquery);
    }
}
