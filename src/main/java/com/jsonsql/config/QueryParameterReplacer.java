package com.jsonsql.config;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces parameter placeholders in SQL queries with actual values.
 * Supports placeholders in the format: ${variable} or ${variable:default}
 */
public class QueryParameterReplacer {
    
    // Pattern to match ${variable} or ${variable:default}
    // Matches: ${var}, ${var:default}, ${var_name}, ${var:default value}
    private static final Pattern PARAMETER_PATTERN = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}");
    
    /**
     * Replace all parameter placeholders in a query string with their values.
     * 
     * @param query The SQL query string containing placeholders
     * @param parameters Map of parameter names to values
     * @return The query with placeholders replaced
     * @throws IllegalArgumentException if a required parameter (without default) is missing
     */
    public static String replaceParameters(String query, Map<String, String> parameters) {
        if (query == null || query.isEmpty()) {
            return query;
        }
        
        if (parameters == null) {
            parameters = Collections.emptyMap();
        }
        
        // Find all placeholders and collect required parameters
        Set<String> requiredParams = new HashSet<>();
        Matcher matcher = PARAMETER_PATTERN.matcher(query);
        
        while (matcher.find()) {
            String paramName = matcher.group(1);
            String defaultValue = matcher.group(2); // May be null
            
            // If no default value, this parameter is required
            if (defaultValue == null && !parameters.containsKey(paramName)) {
                requiredParams.add(paramName);
            }
        }
        
        // Check if all required parameters are provided
        if (!requiredParams.isEmpty()) {
            throw new IllegalArgumentException(
                "Missing required parameters: " + String.join(", ", requiredParams) +
                ". Use --param <name>=<value> to provide values."
            );
        }
        
        // Replace all placeholders
        StringBuffer result = new StringBuffer();
        matcher = PARAMETER_PATTERN.matcher(query);
        
        while (matcher.find()) {
            String paramName = matcher.group(1);
            String defaultValue = matcher.group(2);
            
            String replacement;
            if (parameters.containsKey(paramName)) {
                // Use provided value
                replacement = parameters.get(paramName);
            } else if (defaultValue != null) {
                // Use default value
                replacement = defaultValue;
            } else {
                // This shouldn't happen due to earlier check, but handle gracefully
                replacement = "";
            }
            
            // Escape any special regex characters in the replacement
            replacement = Matcher.quoteReplacement(replacement);
            matcher.appendReplacement(result, replacement);
        }
        
        matcher.appendTail(result);
        return result.toString();
    }
    
    /**
     * Extract all parameter names from a query string (without defaults).
     * Useful for validation or listing required parameters.
     * 
     * @param query The SQL query string
     * @return Set of parameter names found in the query
     */
    public static Set<String> extractParameterNames(String query) {
        Set<String> paramNames = new HashSet<>();
        
        if (query == null || query.isEmpty()) {
            return paramNames;
        }
        
        Matcher matcher = PARAMETER_PATTERN.matcher(query);
        while (matcher.find()) {
            paramNames.add(matcher.group(1));
        }
        
        return paramNames;
    }
    
    /**
     * Check if a query string contains any parameter placeholders.
     * 
     * @param query The SQL query string
     * @return true if the query contains placeholders
     */
    public static boolean hasParameters(String query) {
        if (query == null || query.isEmpty()) {
            return false;
        }
        return PARAMETER_PATTERN.matcher(query).find();
    }
}

