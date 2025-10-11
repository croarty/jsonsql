package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Interface for accessing field values from JSON rows.
 */
public interface FieldAccessor {
    /**
     * Get field value from a row using flexible field path resolution.
     */
    JsonNode getFieldValue(JsonNode row, String fieldPath);
}

