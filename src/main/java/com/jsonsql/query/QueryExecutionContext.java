package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution context for query execution, storing CTE results and providing
 * access to temporary tables during query execution.
 */
public class QueryExecutionContext {
    private final Map<String, List<JsonNode>> cteResults = new HashMap<>();
    
    /**
     * Get the result of a CTE by name.
     * @param cteName The name of the CTE
     * @return The CTE result data, or null if not found
     */
    public List<JsonNode> getCTEResult(String cteName) {
        return cteResults.get(cteName);
    }
    
    /**
     * Check if a CTE result exists.
     * @param cteName The name of the CTE
     * @return true if the CTE result exists
     */
    public boolean hasCTE(String cteName) {
        return cteResults.containsKey(cteName);
    }
    
    /**
     * Store the result of a CTE.
     * @param cteName The name of the CTE
     * @param data The result data
     */
    public void setCTEResult(String cteName, List<JsonNode> data) {
        cteResults.put(cteName, data);
    }
    
    /**
     * Get all CTE names that have been computed.
     * @return Set of CTE names
     */
    public java.util.Set<String> getCTENames() {
        return cteResults.keySet();
    }
}

