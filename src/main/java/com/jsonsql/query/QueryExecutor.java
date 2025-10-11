package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jsonsql.config.MappingManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

/**
 * Executes parsed SQL queries against JSON data.
 */
public class QueryExecutor {
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

        // Apply WHERE clause
        List<JsonNode> filteredData = joinedData;
        if (parsedQuery.getWhereClause() != null) {
            filteredData = applyWhere(joinedData, parsedQuery.getWhereClause());
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
     * Get field value from a row, supporting qualified names (e.g., "p.id").
     * If fieldPath has no prefix, searches within all table wrappers.
     */
    private JsonNode getFieldValue(JsonNode row, String fieldPath) {
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
        JsonNode result = getFieldValue(row, fieldPath);
        if (result != null) {
            return result;
        }
        
        // If not found and no table prefix, search in all wrapped tables
        if (!fieldPath.contains(".")) {
            // Search within each table wrapper
            var fields = row.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode tableData = entry.getValue();
                if (tableData.isObject() && tableData.has(fieldPath)) {
                    return tableData.get(fieldPath);
                }
            }
        }
        
        return null;
    }

    /**
     * Apply WHERE clause filtering.
     */
    private List<JsonNode> applyWhere(List<JsonNode> data, String whereClause) {
        List<JsonNode> result = new ArrayList<>();

        for (JsonNode row : data) {
            if (evaluateWhereCondition(row, whereClause)) {
                result.add(row);
            }
        }

        return result;
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
        
        // Handle text (default)
        return node1.asText().compareTo(node2.asText());
    }

    /**
     * Evaluate WHERE condition for a single row.
     * This is a simplified implementation supporting basic comparisons.
     */
    private boolean evaluateWhereCondition(JsonNode row, String whereClause) {
        try {
            // Parse simple conditions like "field = 'value'" or "a.b = 123"
            WhereCondition condition = parseWhereCondition(whereClause);
            JsonNode fieldValue = getFieldValueFlexible(row, condition.field());

            if (fieldValue == null) {
                return false;
            }

            return switch (condition.operator()) {
                case "=" -> compareEquals(fieldValue, condition.value());
                case "!=" -> !compareEquals(fieldValue, condition.value());
                case ">" -> compareGreater(fieldValue, condition.value());
                case "<" -> compareLess(fieldValue, condition.value());
                case ">=" -> compareGreater(fieldValue, condition.value()) || compareEquals(fieldValue, condition.value());
                case "<=" -> compareLess(fieldValue, condition.value()) || compareEquals(fieldValue, condition.value());
                default -> false;
            };
        } catch (Exception e) {
            // If we can't parse/evaluate, skip the row
            return false;
        }
    }

    /**
     * Parse WHERE condition.
     */
    private WhereCondition parseWhereCondition(String whereClause) {
        // Support basic operators
        String[] operators = {">=", "<=", "!=", "=", ">", "<"};
        
        for (String op : operators) {
            int opIndex = whereClause.indexOf(op);
            if (opIndex > 0) {
                String field = whereClause.substring(0, opIndex).trim();
                String value = whereClause.substring(opIndex + op.length()).trim();
                
                // Remove quotes from string values
                if (value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length() - 1);
                } else if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                
                return new WhereCondition(field, op, value);
            }
        }
        
        throw new IllegalArgumentException("Cannot parse WHERE condition: " + whereClause);
    }

    private boolean compareEquals(JsonNode fieldValue, String compareValue) {
        if (fieldValue.isTextual()) {
            return fieldValue.asText().equals(compareValue);
        } else if (fieldValue.isNumber()) {
            try {
                return fieldValue.asDouble() == Double.parseDouble(compareValue);
            } catch (NumberFormatException e) {
                return false;
            }
        } else if (fieldValue.isBoolean()) {
            return fieldValue.asBoolean() == Boolean.parseBoolean(compareValue);
        }
        return false;
    }

    private boolean compareGreater(JsonNode fieldValue, String compareValue) {
        if (fieldValue.isNumber()) {
            try {
                return fieldValue.asDouble() > Double.parseDouble(compareValue);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private boolean compareLess(JsonNode fieldValue, String compareValue) {
        if (fieldValue.isNumber()) {
            try {
                return fieldValue.asDouble() < Double.parseDouble(compareValue);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
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
     * Simple record for JOIN conditions.
     */
    private record JoinCondition(String leftField, String rightField) {}

    /**
     * Simple record for WHERE conditions.
     */
    private record WhereCondition(String field, String operator, String value) {}
}

