package com.jsonsql.config;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces parameter placeholders in SQL queries with actual values.
 * Supports placeholders in the format: ${variable} or ${variable:default}
 *
 * A value from --param can't change the shape of the query. Inside single quotes its
 * apostrophes are escaped. Outside them it must be a literal or a list of literals, or, where
 * the query names something (after SELECT, FROM, JOIN or ORDER BY), a name or a list of names.
 * Default values are part of the query's own text, so they are used as written.
 */
public class QueryParameterReplacer {
    
    // Pattern to match ${variable} or ${variable:default}
    // Matches: ${var}, ${var:default}, ${var_name}, ${var:default value}
    private static final Pattern PARAMETER_PATTERN = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?\\}");

    // A number, a boolean or a single-quoted string, or a comma-separated list of them for IN (...)
    private static final String LITERAL = "(?:[-+]?\\d+(?:\\.\\d+)?(?:e[-+]?\\d+)?|true|false|'(?:[^']|'')*')";
    private static final Pattern BARE_VALUE_PATTERN = Pattern.compile(
        "(?i)" + LITERAL + "(?:\\s*,\\s*" + LITERAL + ")*");

    // A field or table name such as price or p.price, or a comma-separated list of them
    private static final String NAME = "[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*";
    private static final Pattern NAME_LIST_PATTERN = Pattern.compile(NAME + "(?:\\s*,\\s*" + NAME + ")*");

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern CLAUSE_KEYWORD = Pattern.compile(
        "(?i)\\b(SELECT|FROM|JOIN|ON|WHERE|GROUP\\s+BY|HAVING|ORDER\\s+BY|LIMIT|OFFSET)\\b");
    
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
            
            // If no default value, this parameter is required. A missing, null, or empty
            // provided value all count as "not supplied" for a required parameter.
            if (defaultValue == null) {
                String provided = parameters.get(paramName);
                if (provided == null || provided.isEmpty()) {
                    requiredParams.add(paramName);
                }
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
            String provided = parameters.get(paramName);
            if (provided != null) {
                // Use provided value (guard against null to avoid NPE in quoteReplacement)
                replacement = safeValue(paramName, provided, query, matcher.start());
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
    
    private static boolean isInsideString(String query, int position) {
        // Every quote toggles the state, and an escaped '' toggles it twice
        return query.substring(0, position).chars().filter(c -> c == '\'').count() % 2 == 1;
    }

    private static String safeValue(String name, String value, String query, int position) {
        if (isInsideString(query, position)) {
            return value.replace("'", "''");
        }
        String trimmed = value.trim();
        if (BARE_VALUE_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }
        boolean nameAllowed = isNamePosition(query, position);
        if (nameAllowed && NAME_LIST_PATTERN.matcher(trimmed).matches()) {
            return trimmed;
        }
        if (nameAllowed) {
            throw new IllegalArgumentException(
                "Parameter '" + name + "' must be a name or a comma-separated list of names here, got: " + value
            );
        }
        throw new IllegalArgumentException(
            "Parameter '" + name + "' must be a number, a boolean or a quoted string, got: " + value +
            ". For text, put the placeholder in single quotes in the query: '${" + name + "}'."
        );
    }

    /**
     * Names are allowed where the query names something. In a condition an unquoted word
     * would be read as a field, so forgotten quotes would silently match nothing.
     */
    private static boolean isNamePosition(String query, int position) {
        String before = STRING_LITERAL.matcher(query.substring(0, position)).replaceAll("''");
        Matcher keyword = CLAUSE_KEYWORD.matcher(before);
        String clause = "";
        while (keyword.find()) {
            clause = keyword.group(1).toUpperCase().replaceAll("\\s+", " ");
        }
        return switch (clause) {
            case "SELECT", "FROM", "JOIN", "ORDER BY" -> true;
            default -> false;
        };
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

