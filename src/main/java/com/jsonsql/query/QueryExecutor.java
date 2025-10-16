package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jsonsql.config.MappingManager;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Executes parsed SQL queries against JSON data.
 */
public class QueryExecutor implements FieldAccessor {
    private final MappingManager mappingManager;
    private final File dataDirectory;
    private final ObjectMapper objectMapper;
    private final QueryParser queryParser;
    private final Configuration jsonPathConfig;

    public QueryExecutor(MappingManager mappingManager, File dataDirectory) {
        this.mappingManager = mappingManager;
        this.dataDirectory = dataDirectory;
        this.objectMapper = new ObjectMapper();
        this.queryParser = new QueryParser();
        this.jsonPathConfig = Configuration.builder()
            .options(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS)
            .build();
    }

    /**
     * Execute a SQL query and return JSON result.
     */
    public String execute(String sql) throws Exception {
        // Parse the query
        ParsedQuery parsedQuery = queryParser.parse(sql);

        // Load data from FROM table
        List<JsonNode> fromData = loadTableData(parsedQuery.getFromTable());

        // Apply JOINs if any
        List<JsonNode> joinedData = fromData;
        if (parsedQuery.hasJoins()) {
            joinedData = executeJoins(fromData, parsedQuery);
        }

        // Apply UNNEST operations if any
        List<JsonNode> unnestedData = joinedData;
        if (parsedQuery.hasUnnests()) {
            unnestedData = executeUnnests(joinedData, parsedQuery.getUnnests());
        }

        // Apply WHERE clause
        List<JsonNode> filteredData = unnestedData;
        if (parsedQuery.hasWhere()) {
            filteredData = applyWhere(unnestedData, parsedQuery.getWhereExpression());
        }

        // Apply ORDER BY
        if (parsedQuery.hasOrderBy()) {
            filteredData = applyOrderBy(filteredData, parsedQuery.getOrderBy());
        }

        // Apply TOP/LIMIT
        if (parsedQuery.getEffectiveLimit() != null) {
            int limit = parsedQuery.getEffectiveLimit().intValue();
            filteredData = filteredData.subList(0, Math.min(limit, filteredData.size()));
        }

        // Project SELECT columns
        List<JsonNode> projectedData = projectColumns(filteredData, parsedQuery.getSelectColumns());

        // Convert to JSON array string
        ArrayNode resultArray = objectMapper.createArrayNode();
        projectedData.forEach(resultArray::add);

        return objectMapper.writeValueAsString(resultArray);
    }

    /**
     * Load data for a table using its JSONPath mapping.
     */
    private List<JsonNode> loadTableData(TableInfo tableInfo) throws IOException {
        String tableName = tableInfo.getTableName();
        
        if (!mappingManager.hasMapping(tableName)) {
            throw new IllegalArgumentException(
                "No mapping found for table: " + tableName + ". Use --add-mapping to define it."
            );
        }

        // Get the JSONPath (without filename prefix if present)
        String jsonPathExpression = mappingManager.getJsonPathOnly(tableName);
        
        // Check if mapping specifies a filename/path, otherwise use table name
        String fileName = mappingManager.getFileName(tableName);
        
        List<File> jsonFiles = new ArrayList<>();
        
        if (fileName != null) {
            // Use filename/path from mapping - could be file, directory, or relative path
            File fileOrDir = new File(dataDirectory, fileName);
            
            // Handle absolute paths
            if (!fileOrDir.isAbsolute() && new File(fileName).isAbsolute()) {
                fileOrDir = new File(fileName);
            }
            
            if (fileOrDir.isDirectory()) {
                // Load all .json files from directory
                File[] files = fileOrDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                if (files != null && files.length > 0) {
                    jsonFiles.addAll(Arrays.asList(files));
                } else {
                    throw new IOException("No JSON files found in directory: " + fileOrDir.getAbsolutePath());
                }
            } else {
                // Single file
                jsonFiles.add(fileOrDir);
            }
        } else {
            // Fall back to using table name as filename
            jsonFiles.add(findJsonFile(tableName));
        }
        
        // Load and combine data from all files
        List<JsonNode> dataList = new ArrayList<>();
        
        for (File jsonFile : jsonFiles) {
            if (!jsonFile.exists()) {
                throw new IOException("JSON file not found: " + jsonFile.getAbsolutePath());
            }
            
            // Read JSON file
            String jsonContent = Files.readString(jsonFile.toPath());
            
            // Apply JSONPath
            Object result = JsonPath.using(jsonPathConfig).parse(jsonContent).read(jsonPathExpression);
            
            // Convert to list of JsonNodes
            JsonNode resultNode = objectMapper.valueToTree(result);
            
            if (resultNode.isArray()) {
                resultNode.forEach(node -> {
                    // Add table alias/name to each row for qualified column access
                    if (node.isObject()) {
                        ObjectNode objNode = (ObjectNode) node;
                        ObjectNode wrappedNode = objectMapper.createObjectNode();
                        wrappedNode.set(tableInfo.getEffectiveName(), objNode);
                        dataList.add(wrappedNode);
                    }
                });
            } else if (resultNode.isObject()) {
                // Single object, wrap it
                ObjectNode wrappedNode = objectMapper.createObjectNode();
                wrappedNode.set(tableInfo.getEffectiveName(), resultNode);
                dataList.add(wrappedNode);
            }
        }

        return dataList;
    }

    /**
     * Find JSON file for a table name.
     */
    private File findJsonFile(String tableName) {
        // Try exact match first
        File exactMatch = new File(dataDirectory, tableName + ".json");
        if (exactMatch.exists()) {
            return exactMatch;
        }

        // Try case-insensitive search
        File[] files = dataDirectory.listFiles((dir, name) -> 
            name.toLowerCase().equals(tableName.toLowerCase() + ".json")
        );

        if (files != null && files.length > 0) {
            return files[0];
        }

        // If not found, return the exact match path anyway (will fail with clear error)
        return exactMatch;
    }

    /**
     * Execute JOIN operations.
     */
    private List<JsonNode> executeJoins(List<JsonNode> leftData, ParsedQuery parsedQuery) throws IOException {
        List<JsonNode> result = leftData;

        for (JoinInfo joinInfo : parsedQuery.getJoins()) {
            List<JsonNode> rightData = loadTableData(joinInfo.getTable());
            result = performJoin(result, rightData, joinInfo, parsedQuery.getFromTable());
        }

        return result;
    }

    /**
     * Perform a single JOIN operation.
     */
    private List<JsonNode> performJoin(
        List<JsonNode> leftData,
        List<JsonNode> rightData,
        JoinInfo joinInfo,
        TableInfo leftTableInfo
    ) {
        List<JsonNode> result = new ArrayList<>();
        String onCondition = joinInfo.getOnCondition();

        // Parse ON condition (e.g., "o.productId = p.id")
        JoinCondition condition = parseJoinCondition(onCondition);

        for (JsonNode leftRow : leftData) {
            boolean matchFound = false;

            for (JsonNode rightRow : rightData) {
                if (matchesJoinCondition(leftRow, rightRow, condition)) {
                    // Merge left and right rows
                    ObjectNode mergedRow = objectMapper.createObjectNode();
                    mergedRow.setAll((ObjectNode) leftRow);
                    mergedRow.setAll((ObjectNode) rightRow);
                    result.add(mergedRow);
                    matchFound = true;
                }
            }

            // For LEFT JOIN, include left row even if no match found
            if (joinInfo.isLeftJoin() && !matchFound) {
                result.add(leftRow);
            }
        }

        return result;
    }

    /**
     * Parse JOIN ON condition into left and right field paths.
     */
    private JoinCondition parseJoinCondition(String onCondition) {
        // Simple parser for "a.b = c.d" format
        String[] parts = onCondition.split("=");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid JOIN ON condition: " + onCondition);
        }

        return new JoinCondition(parts[0].trim(), parts[1].trim());
    }

    /**
     * Check if two rows match the JOIN condition.
     */
    private boolean matchesJoinCondition(JsonNode leftRow, JsonNode rightRow, JoinCondition condition) {
        JsonNode leftValue = getFieldValue(leftRow, condition.leftField());
        JsonNode rightValue = getFieldValue(rightRow, condition.rightField());

        if (leftValue == null || rightValue == null) {
            return false;
        }

        return leftValue.equals(rightValue);
    }

    /**
     * Get field value from a row using direct path navigation.
     */
    private JsonNode getFieldValueDirect(JsonNode row, String fieldPath) {
        String[] parts = fieldPath.split("\\.");
        JsonNode current = row;

        // Navigate through the path
        for (String part : parts) {
            if (current == null || !current.has(part)) {
                return null;
            }
            current = current.get(part);
        }

        return current;
    }
    
    /**
     * Get field value from a row, trying all table wrappers if unqualified.
     */
    private JsonNode getFieldValueFlexible(JsonNode row, String fieldPath) {
        // First try direct access (for qualified names like "p.id")
        JsonNode result = getFieldValueDirect(row, fieldPath);
        if (result != null) {
            return result;
        }
        
        // Search within each table wrapper for both qualified and unqualified paths
        var fields = row.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode tableData = entry.getValue();
            
            if (tableData.isObject()) {
                // Try exact match first (for simple field names)
                if (tableData.has(fieldPath)) {
                    return tableData.get(fieldPath);
                }
                
                // Try nested path access within the table wrapper
                // This handles cases like "profile.level" where the data is wrapped
                result = getFieldValueDirect(tableData, fieldPath);
                if (result != null) {
                    return result;
                }
            }
        }
        
        return null;
    }

    /**
     * Apply WHERE clause filtering.
     */
    private List<JsonNode> applyWhere(List<JsonNode> data, Expression whereExpression) {
        List<JsonNode> result = new ArrayList<>();
        WhereEvaluator evaluator = new WhereEvaluator(this);

        for (JsonNode row : data) {
            if (evaluator.evaluate(row, whereExpression)) {
                result.add(row);
            }
        }

        return result;
    }
    
    /**
     * Implement FieldAccessor interface for WhereEvaluator.
     */
    @Override
    public JsonNode getFieldValue(JsonNode row, String fieldPath) {
        return getFieldValueFlexible(row, fieldPath);
    }

    /**
     * Apply ORDER BY sorting.
     */
    private List<JsonNode> applyOrderBy(List<JsonNode> data, List<OrderByInfo> orderByList) {
        List<JsonNode> sortedData = new ArrayList<>(data);
        
        sortedData.sort((row1, row2) -> {
            for (OrderByInfo orderBy : orderByList) {
                JsonNode value1 = getFieldValueFlexible(row1, orderBy.getColumn());
                JsonNode value2 = getFieldValueFlexible(row2, orderBy.getColumn());
                
                int comparison = compareNodes(value1, value2);
                
                if (comparison != 0) {
                    return orderBy.isAscending() ? comparison : -comparison;
                }
            }
            return 0;
        });
        
        return sortedData;
    }

    /**
     * Compare two JsonNodes for sorting.
     */
    private int compareNodes(JsonNode node1, JsonNode node2) {
        // Handle nulls
        if (node1 == null && node2 == null) return 0;
        if (node1 == null) return -1;
        if (node2 == null) return 1;
        
        // Handle numbers
        if (node1.isNumber() && node2.isNumber()) {
            return Double.compare(node1.asDouble(), node2.asDouble());
        }
        
        // Handle booleans
        if (node1.isBoolean() && node2.isBoolean()) {
            return Boolean.compare(node1.asBoolean(), node2.asBoolean());
        }
        
        // Handle text - try numeric comparison first, then lexicographic
        String text1 = node1.asText();
        String text2 = node2.asText();
        
        // Try to parse as numbers for numeric comparison
        // Extract numeric part from strings like "15.6 inch" -> "15.6"
        Double num1 = extractNumericValue(text1);
        Double num2 = extractNumericValue(text2);
        
        if (num1 != null && num2 != null) {
            return Double.compare(num1, num2);
        } else {
            // Not both numeric, use lexicographic comparison
            return text1.compareTo(text2);
        }
    }

    /**
     * Extract numeric value from a string, handling units like "15.6 inch" -> 15.6
     */
    private Double extractNumericValue(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        
        // Try to parse the entire string as a number first
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            // If that fails, try to extract a number from the beginning
            // This handles cases like "15.6 inch", "5.0 cm", etc.
            String trimmed = text.trim();
            StringBuilder numericPart = new StringBuilder();
            
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                if (Character.isDigit(c) || c == '.' || c == '-' || c == '+') {
                    numericPart.append(c);
                } else {
                    // Stop at first non-numeric character
                    break;
                }
            }
            
            if (numericPart.length() > 0) {
                try {
                    return Double.parseDouble(numericPart.toString());
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            
            return null;
        }
    }

    /**
     * Project SELECT columns from the data.
     */
    private List<JsonNode> projectColumns(List<JsonNode> data, List<ColumnInfo> selectColumns) {
        // Handle SELECT *
        if (selectColumns.size() == 1 && selectColumns.get(0).getExpression().equals("*")) {
            // Flatten the wrapped structure for output
            return data.stream()
                .map(this::flattenRow)
                .toList();
        }

        // Detect collisions - which output names would appear multiple times?
        Map<String, Integer> outputNameCounts = new HashMap<>();
        for (ColumnInfo column : selectColumns) {
            // If has alias, that's the output name; otherwise use simple name from expression
            String outputName = column.hasAlias() 
                ? column.getAlias()
                : column.getOutputName();
            outputNameCounts.put(outputName, outputNameCounts.getOrDefault(outputName, 0) + 1);
        }
        
        // Project specific columns
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode row : data) {
            ObjectNode projectedRow = objectMapper.createObjectNode();
            
            for (ColumnInfo column : selectColumns) {
                String expression = column.getExpression().trim();
                JsonNode value = getFieldValueFlexible(row, expression);
                
                if (value != null) {
                    String outputName;
                    
                    if (column.hasAlias()) {
                        // Use explicit alias
                        outputName = column.getAlias();
                    } else {
                        // Determine output name based on collision detection
                        String simpleName = expression.contains(".") 
                            ? expression.substring(expression.lastIndexOf('.') + 1)
                            : expression;
                        
                        // Use qualified name if collision detected, otherwise use simple name
                        if (outputNameCounts.get(simpleName) > 1) {
                            // Collision - use qualified name
                            outputName = expression;
                        } else {
                            // No collision - use simple name
                            outputName = simpleName;
                        }
                    }
                    
                    projectedRow.set(outputName, value);
                }
            }
            
            result.add(projectedRow);
        }

        return result;
    }

    /**
     * Flatten wrapped row structure for SELECT *.
     */
    private JsonNode flattenRow(JsonNode row) {
        ObjectNode flattened = objectMapper.createObjectNode();
        
        row.fields().forEachRemaining(entry -> {
            if (entry.getValue().isObject()) {
                entry.getValue().fields().forEachRemaining(innerEntry -> {
                    flattened.set(innerEntry.getKey(), innerEntry.getValue());
                });
            } else {
                flattened.set(entry.getKey(), entry.getValue());
            }
        });
        
        return flattened;
    }

    /**
     * Execute UNNEST operations to flatten arrays into individual rows.
     */
    private List<JsonNode> executeUnnests(List<JsonNode> data, List<UnnestInfo> unnests) {
        List<JsonNode> result = data;
        
        for (UnnestInfo unnest : unnests) {
            result = executeUnnest(result, unnest);
        }
        
        return result;
    }

    /**
     * Execute a single UNNEST operation.
     */
    private List<JsonNode> executeUnnest(List<JsonNode> data, UnnestInfo unnest) {
        List<JsonNode> result = new ArrayList<>();
        
        for (JsonNode row : data) {
            // Get the array field to unnest
            JsonNode arrayField = getFieldValue(row, unnest.getArrayExpression());
            
            if (arrayField != null && arrayField.isArray()) {
                // For each element in the array, create a new row
                for (JsonNode arrayElement : arrayField) {
                    ObjectNode newRow = objectMapper.createObjectNode();
                    
                    // Copy all original fields
                    row.fields().forEachRemaining(entry -> {
                        newRow.set(entry.getKey(), entry.getValue());
                    });
                    
                    // Add the unnested element with the specified alias and column name
                    newRow.set(unnest.getElementColumn(), arrayElement);
                    
                    result.add(newRow);
                }
            } else {
                // If the array field is null or not an array, skip this row entirely
                // This follows SQL standard behavior for UNNEST
                // No row is added to the result
            }
        }
        
        return result;
    }

    /**
     * Simple record for JOIN conditions.
     */
    private record JoinCondition(String leftField, String rightField) {}
}

