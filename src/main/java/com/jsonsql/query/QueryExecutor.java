package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jsonsql.config.CacheManager;
import com.jsonsql.config.MappingManager;
import com.jsonsql.index.IndexManager;
import com.jsonsql.index.IndexPlanner;
import com.jsonsql.index.IndexStore;
import com.jsonsql.view.MaterializedViewDefinition;
import com.jsonsql.view.MaterializedViewManager;
import com.jsonsql.view.MaterializedViewStore;
import com.jsonsql.view.SourceFingerprint;
import net.sf.jsqlparser.expression.Expression;

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
    private final CacheManager cacheManager;
    private final TableFileResolver fileResolver;
    private final IndexPlanner indexPlanner;
    private final MaterializedViewManager viewManager;
    private final MaterializedViewStore viewStore;
    private final boolean resolveMaterializedViews;

    public QueryExecutor(MappingManager mappingManager, File dataDirectory) {
        this(mappingManager, dataDirectory, null, null, null);
    }
    
    public QueryExecutor(MappingManager mappingManager, File dataDirectory, CacheManager cacheManager) {
        this(mappingManager, dataDirectory, cacheManager, null, null);
    }

    public QueryExecutor(MappingManager mappingManager, File dataDirectory,
                         CacheManager cacheManager, IndexManager indexManager) {
        this(mappingManager, dataDirectory, cacheManager, indexManager, null);
    }

    public QueryExecutor(MappingManager mappingManager, File dataDirectory,
                         CacheManager cacheManager, IndexManager indexManager,
                         MaterializedViewManager viewManager) {
        this(mappingManager, dataDirectory, cacheManager, indexManager, viewManager, true);
    }

    private QueryExecutor(MappingManager mappingManager, File dataDirectory,
                          CacheManager cacheManager, IndexManager indexManager,
                          MaterializedViewManager viewManager, boolean resolveMaterializedViews) {
        this.mappingManager = mappingManager;
        this.dataDirectory = dataDirectory;
        this.cacheManager = cacheManager;
        this.viewManager = viewManager;
        this.resolveMaterializedViews = resolveMaterializedViews;
        this.viewStore = viewManager != null ? new MaterializedViewStore(dataDirectory) : null;
        this.objectMapper = new ObjectMapper();
        this.queryParser = new QueryParser();
        this.jsonPathConfig = Configuration.builder()
            .options(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS)
            .build();
        this.fileResolver = new TableFileResolver(mappingManager, dataDirectory);
        this.indexPlanner = indexManager != null
            ? new IndexPlanner(indexManager, new IndexStore(dataDirectory), mappingManager,
                fileResolver, viewManager)
            : null;
    }

    /** Executor that resolves mapped tables only (used when building a materialized view). */
    public static QueryExecutor forMaterialization(MappingManager mappingManager, File dataDirectory,
                                                   CacheManager cacheManager, IndexManager indexManager,
                                                   MaterializedViewManager viewManager) {
        return new QueryExecutor(mappingManager, dataDirectory, cacheManager, indexManager,
            viewManager, false);
    }

    public MaterializedViewManager getViewManager() {
        return viewManager;
    }

    public File getDataDirectory() {
        return dataDirectory;
    }

    /**
     * Execute a SQL query and return JSON result.
     */
    public String execute(String sql) throws Exception {
        // Parse the query
        ParsedQuery parsedQuery = queryParser.parse(sql);
        
        // Create execution context for CTEs
        QueryExecutionContext context = new QueryExecutionContext();
        
        // Execute CTEs first if present
        if (parsedQuery.hasCTEs()) {
            if (System.getenv("DEBUG") != null) {
                System.err.println("DEBUG: Query has CTEs, count: " + parsedQuery.getCommonTableExpressions().size());
            }
            executeCTEs(parsedQuery, context);
        } else if (System.getenv("DEBUG") != null) {
            System.err.println("DEBUG: Query has no CTEs");
        }
        
        // Execute main query with context
        return executeQuery(parsedQuery, context);
    }

    /**
     * Parse and validate a query without loading data or producing results.
     * Checks SQL syntax, table mappings, and that backing JSON files exist.
     */
    public DryRunResult dryRunValidate(String sql) throws Exception {
        ParsedQuery parsedQuery = queryParser.parse(sql);
        Set<String> resolvedTables = new LinkedHashSet<>();
        validateResolvedTables(parsedQuery, resolvedTables);
        return new DryRunResult(parsedQuery, resolvedTables);
    }

    private void validateResolvedTables(ParsedQuery parsedQuery, Set<String> resolvedTables) throws IOException {
        Set<String> availableCtes = new LinkedHashSet<>();
        for (Map.Entry<String, ParsedQuery> cte : parsedQuery.getCommonTableExpressions().entrySet()) {
            validateTableReferences(cte.getValue(), availableCtes, resolvedTables);
            availableCtes.add(cte.getKey());
        }
        validateTableReferences(parsedQuery, availableCtes, resolvedTables);
    }

    private void validateTableReferences(ParsedQuery query, Set<String> availableCtes,
                                         Set<String> resolvedTables) throws IOException {
        if (query.getFromTable() != null) {
            ensureTableAccessible(query.getFromTable().getTableName(), availableCtes, resolvedTables);
        }
        if (query.hasJoins()) {
            for (JoinInfo join : query.getJoins()) {
                if (join.getTable() != null) {
                    ensureTableAccessible(join.getTable().getTableName(), availableCtes, resolvedTables);
                }
            }
        }
    }

    private void ensureTableAccessible(String tableName, Set<String> availableCtes,
                                       Set<String> resolvedTables) throws IOException {
        if (CteNames.contains(availableCtes, tableName)) {
            return;
        }
        if (isMaterializedView(tableName)) {
            if (viewStore.storeFile(tableName).exists()) {
                resolvedTables.add(tableName);
            } else {
                throw new IOException("Materialized view data not found: " + tableName
                    + ". Run --rebuild-view " + tableName);
            }
            return;
        }
        if (!mappingManager.hasMapping(tableName)) {
            throw new IllegalArgumentException(
                "No mapping or materialized view found for table: " + tableName
                    + ". Use --add-mapping or --materialize-view to define it.");
        }
        for (File file : resolveJsonFiles(tableName)) {
            if (!file.exists()) {
                throw new IOException("JSON file not found: " + file.getAbsolutePath());
            }
        }
        resolvedTables.add(tableName);
    }

    private boolean isMaterializedView(String tableName) {
        return resolveMaterializedViews && viewManager != null && viewManager.hasView(tableName)
            && !mappingManager.hasMapping(tableName);
    }

    /**
     * Execute a parsed query and return the result rows as a list (for materialization).
     */
    public List<JsonNode> executeToRowList(ParsedQuery parsedQuery) throws Exception {
        QueryExecutionContext context = new QueryExecutionContext();
        String json = executeQuery(parsedQuery, context);
        JsonNode array = objectMapper.readTree(json);
        List<JsonNode> rows = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(rows::add);
        }
        return rows;
    }
    
    /**
     * Execute a parsed query with execution context (supports recursive execution for CTEs).
     */
    private String executeQuery(ParsedQuery parsedQuery, QueryExecutionContext context) throws Exception {
        // Load data from FROM table (checks CTE context first). The query is passed so the
        // index planner can prune backing files for the FROM table before loading.
        List<JsonNode> fromData = loadTableData(parsedQuery.getFromTable(), context, parsedQuery);

        // Apply UNNEST operations if any (must happen before JOINs so UNNEST columns can be used in JOIN conditions)
        List<JsonNode> unnestedData = fromData;
        if (parsedQuery.hasUnnests()) {
            unnestedData = executeUnnests(fromData, parsedQuery.getUnnests());
        }

        // Apply JOINs if any
        List<JsonNode> joinedData = unnestedData;
        if (parsedQuery.hasJoins()) {
            joinedData = executeJoins(unnestedData, parsedQuery, context);
        }

        // Apply WHERE clause
        List<JsonNode> filteredData = joinedData;
        if (parsedQuery.hasWhere()) {
            filteredData = applyWhere(joinedData, parsedQuery.getWhereExpression());
        }

        // Apply ORDER BY before projection so sort keys not present in the SELECT list
        // (e.g. ORDER BY id while selecting only name) remain available.
        if (parsedQuery.hasOrderBy()) {
            filteredData = applyOrderBy(filteredData, parsedQuery.getOrderBy());
        }

        // Project SELECT columns
        List<JsonNode> projectedData = projectColumns(filteredData, parsedQuery.getSelectColumns());

        // Apply DISTINCT after projection (dedupe the projected rows, preserving sorted order)
        if (parsedQuery.isDistinct()) {
            projectedData = applyDistinct(projectedData);
        }

        // Apply TOP/LIMIT last, so it operates on the final ordered/projected/deduped result set
        if (parsedQuery.getEffectiveLimit() != null) {
            int limit = parsedQuery.getEffectiveLimit().intValue();
            projectedData = projectedData.subList(0, Math.min(limit, projectedData.size()));
        }

        // Convert to JSON array string
        ArrayNode resultArray = objectMapper.createArrayNode();
        projectedData.forEach(resultArray::add);

        return objectMapper.writeValueAsString(resultArray);
    }
    
    /**
     * Execute all CTEs and store results in context.
     * Checks cache first if caching is enabled.
     */
    private void executeCTEs(ParsedQuery parsedQuery, QueryExecutionContext context) throws Exception {
        if (System.getenv("DEBUG") != null) {
            System.err.println("DEBUG: executeCTEs called, CTE count: " + parsedQuery.getCommonTableExpressions().size());
        }
        for (Map.Entry<String, ParsedQuery> cteEntry : parsedQuery.getCommonTableExpressions().entrySet()) {
            String cteName = cteEntry.getKey();
            ParsedQuery cteQuery = cteEntry.getValue();
            
            if (System.getenv("DEBUG") != null) {
                System.err.println("DEBUG: Processing CTE: " + cteName);
            }
            
            List<JsonNode> cteData = null;
            
            // Check cache first if enabled
            if (cacheManager != null) {
                String cteCacheKey = generateCTECacheKey(cteName, cteQuery);
                cteData = cacheManager.getCachedCTE(cteCacheKey);
                
                if (cteData != null) {
                    // Use cached CTE result
                    context.setCTEResult(cteName, cteData);
                    continue; // Skip execution, use cached data
                }
            }
            
            // Not cached (or caching disabled), execute the CTE query
            String cteResultJson = executeQuery(cteQuery, context);
            
            // Parse the JSON result into List<JsonNode>
            JsonNode cteResultArray = objectMapper.readTree(cteResultJson);
            cteData = new ArrayList<>();
            if (cteResultArray.isArray()) {
                cteResultArray.forEach(cteData::add);
            }
            
            // Save to cache if enabled
            if (cacheManager != null && cteData != null && !cteData.isEmpty()) {
                try {
                    String cteCacheKey = generateCTECacheKey(cteName, cteQuery);
                    if (System.getenv("DEBUG") != null) {
                        System.err.println("DEBUG: Caching CTE " + cteName + " with key: " + cteCacheKey + ", data size: " + cteData.size());
                    }
                    cacheManager.saveCTEToCache(cteCacheKey, cteData);
                    if (System.getenv("DEBUG") != null) {
                        System.err.println("DEBUG: CTE cache saved successfully");
                    }
                } catch (Exception e) {
                    // If cache save fails, continue without caching
                    // This shouldn't break the query execution
                    // Log error in debug mode
                    if (System.getenv("DEBUG") != null) {
                        System.err.println("Failed to cache CTE " + cteName + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            } else if (System.getenv("DEBUG") != null) {
                System.err.println("DEBUG: Not caching CTE " + cteName + " - cacheManager=" + (cacheManager != null) + ", cteData=" + (cteData != null) + ", isEmpty=" + (cteData == null ? "N/A" : cteData.isEmpty()));
            }
            
            // Store CTE result in context
            context.setCTEResult(cteName, cteData);
        }
    }
    
    /**
     * Generate a deterministic cache key for a CTE based on its query structure.
     * The key includes: CTE name, table name, WHERE clause, and other relevant parts.
     */
    private String generateCTECacheKey(String cteName, ParsedQuery cteQuery) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append("CTE:").append(cteName).append(":");
        
        // Add table name
        if (cteQuery.getFromTable() != null) {
            keyBuilder.append("TABLE:").append(cteQuery.getFromTable().getTableName()).append(":");
        }
        
        // Add WHERE clause (normalized string representation)
        if (cteQuery.hasWhere() && cteQuery.getWhereExpression() != null) {
            // Use toString() which gives a normalized representation
            String whereStr = cteQuery.getWhereExpression().toString();
            // Normalize whitespace for consistency
            whereStr = whereStr.replaceAll("\\s+", " ").trim();
            keyBuilder.append("WHERE:").append(whereStr).append(":");
        }
        
        // Add JOINs if any
        if (cteQuery.hasJoins()) {
            for (var join : cteQuery.getJoins()) {
                keyBuilder.append("JOIN:").append(join.getTable().getTableName())
                    .append(":").append(join.getOnCondition()).append(":");
            }
        }
        
        // Add UNNESTs if any
        if (cteQuery.hasUnnests()) {
            for (var unnest : cteQuery.getUnnests()) {
                keyBuilder.append("UNNEST:").append(unnest.getArrayExpression())
                    .append(":").append(unnest.getAlias()).append(":");
            }
        }
        
        // Add SELECT column list (projection affects the cached result)
        if (cteQuery.getSelectColumns() != null && !cteQuery.getSelectColumns().isEmpty()) {
            keyBuilder.append("SELECT:");
            for (var col : cteQuery.getSelectColumns()) {
                keyBuilder.append(col.getExpression());
                if (col.hasAlias()) {
                    keyBuilder.append(" AS ").append(col.getAlias());
                }
                keyBuilder.append(",");
            }
            keyBuilder.append(":");
        }

        // Add ORDER BY (ordering affects the cached result, especially combined with LIMIT)
        if (cteQuery.hasOrderBy()) {
            keyBuilder.append("ORDERBY:");
            for (OrderByInfo ob : cteQuery.getOrderBy()) {
                keyBuilder.append(ob.getColumn()).append(ob.isAscending() ? " ASC" : " DESC").append(",");
            }
            keyBuilder.append(":");
        }

        // Add LIMIT / TOP (row limiting affects the cached result)
        if (cteQuery.getEffectiveLimit() != null) {
            keyBuilder.append("LIMIT:").append(cteQuery.getEffectiveLimit()).append(":");
        }

        // Add DISTINCT flag
        if (cteQuery.isDistinct()) {
            keyBuilder.append("DISTINCT:");
        }

        // Add source freshness fingerprint so editing the underlying JSON invalidates the CTE cache
        String fromFingerprint = sourceFingerprint(cteQuery.getFromTable());
        if (!fromFingerprint.isEmpty()) {
            keyBuilder.append("SRC:").append(fromFingerprint).append(":");
        }
        if (cteQuery.hasJoins()) {
            for (var join : cteQuery.getJoins()) {
                String joinFingerprint = sourceFingerprint(join.getTable());
                if (!joinFingerprint.isEmpty()) {
                    keyBuilder.append("JOINSRC:").append(joinFingerprint).append(":");
                }
            }
        }

        return keyBuilder.toString();
    }

    /**
     * Load raw row objects for a mapped table (unwrapped, without the table-name envelope).
     * Used for schema introspection and other tooling that needs source rows.
     */
    public List<JsonNode> loadMappedTableRows(String tableName) throws IOException {
        if (isMaterializedView(tableName)) {
            warnIfStaleView(tableName);
            List<JsonNode> rows = viewStore.load(tableName);
            return rows != null ? rows : List.of();
        }
        TableInfo tableInfo = new TableInfo();
        tableInfo.setTableName(tableName);
        List<JsonNode> wrapped = loadTableData(tableInfo, new QueryExecutionContext());
        String effectiveName = tableInfo.getEffectiveName();
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : wrapped) {
            if (row.isObject() && row.has(effectiveName)) {
                rows.add(row.get(effectiveName));
            } else {
                rows.add(row);
            }
        }
        return rows;
    }

    private List<JsonNode> loadTableData(TableInfo tableInfo, QueryExecutionContext context) throws IOException {
        return loadTableData(tableInfo, context, null);
    }

    /**
     * Load data for a table using its JSONPath mapping or CTE context.
     *
     * @param pruningQuery the query whose WHERE/UNNEST clauses apply to this table, or null.
     *                     When provided (and an index manager is configured), the index
     *                     planner may skip backing files that cannot contain a match.
     */
    private List<JsonNode> loadTableData(TableInfo tableInfo, QueryExecutionContext context,
                                         ParsedQuery pruningQuery) throws IOException {
        String tableName = tableInfo.getTableName();
        
        // Check if this is a CTE first
        if (context != null && context.hasCTE(tableName)) {
            // Return CTE result
            List<JsonNode> cteData = context.getCTEResult(tableName);
            // Wrap in table structure for consistency
            return wrapTableData(cteData, tableInfo);
        }

        // Materialized view (persisted CTE)
        if (isMaterializedView(tableName)) {
            return loadMaterializedViewData(tableInfo, pruningQuery);
        }
        
        // Otherwise, load from JSON file
        if (!mappingManager.hasMapping(tableName)) {
            throw new IllegalArgumentException(
                "No mapping or materialized view found for table: " + tableName
                    + ". Use --add-mapping or --materialize-view to define it."
            );
        }

        // Get the JSONPath (without filename prefix if present)
        String jsonPathExpression = mappingManager.getJsonPathOnly(tableName);
        
        // Resolve the JSON file(s) backing this table
        List<File> jsonFiles = resolveJsonFiles(tableName);

        // Index-based file pruning (correctness-preserving: only provably-empty files are skipped).
        if (indexPlanner != null && pruningQuery != null) {
            jsonFiles = indexPlanner.prune(tableInfo, pruningQuery, jsonFiles);
        }
        
        // Load and combine data from all files
        List<JsonNode> dataList = new ArrayList<>();
        
        for (File jsonFile : jsonFiles) {
            if (!jsonFile.exists()) {
                throw new IOException("JSON file not found: " + jsonFile.getAbsolutePath());
            }
            
            List<JsonNode> fileData;
            
            // Check cache first if enabled
            if (cacheManager != null) {
                List<JsonNode> cachedData = cacheManager.getCachedData(jsonFile, jsonPathExpression);
                if (cachedData != null) {
                    // Use cached data
                    fileData = cachedData;
                } else {
                    // Load from file
                    fileData = loadFromFile(jsonFile, jsonPathExpression);
                    
                    // Save to cache (with JSONPath for unique cache key)
                    try {
                        cacheManager.saveToCache(jsonFile, jsonPathExpression, fileData);
                    } catch (IOException e) {
                        // If cache save fails, continue without caching
                        // This shouldn't break the query execution
                    }
                }
            } else {
                // No caching, load from file
                fileData = loadFromFile(jsonFile, jsonPathExpression);
            }
            
            // Wrap each row with table name for qualified column access
            for (JsonNode node : fileData) {
                if (node.isObject()) {
                    ObjectNode wrappedNode = objectMapper.createObjectNode();
                    wrappedNode.set(tableInfo.getEffectiveName(), (ObjectNode) node);
                    dataList.add(wrappedNode);
                } else {
                    dataList.add(node);
                }
            }
        }

        return dataList;
    }

    private List<JsonNode> loadMaterializedViewData(TableInfo tableInfo, ParsedQuery pruningQuery)
            throws IOException {
        String tableName = tableInfo.getTableName();
        warnIfStaleView(tableName);

        File storeFile = viewStore.storeFile(tableName);
        if (!storeFile.exists()) {
            throw new IOException("Materialized view data not found: " + tableName
                + ". Run --rebuild-view " + tableName);
        }

        if (indexPlanner != null && pruningQuery != null) {
            List<File> files = List.of(storeFile);
            List<File> kept = indexPlanner.prune(tableInfo, pruningQuery, files);
            if (kept.isEmpty()) {
                return new ArrayList<>();
            }
        }

        List<JsonNode> rows = viewStore.load(tableName);
        if (rows == null) {
            rows = new ArrayList<>();
        }
        return wrapTableData(rows, tableInfo);
    }

    private void warnIfStaleView(String tableName) {
        if (viewManager == null) {
            return;
        }
        MaterializedViewDefinition def = viewManager.getView(tableName);
        if (def == null) {
            return;
        }
        if (!SourceFingerprint.isFresh(def, mappingManager, dataDirectory)) {
            System.err.println("Warning: materialized view '" + tableName
                + "' is STALE (source data changed since last build). "
                + "Serving stored data. Run --rebuild-view " + tableName + " to refresh.");
        }
    }

    /**
     * Resolve the JSON file(s) backing a mapped table. Handles single files,
     * relative/absolute paths, and directories (recursively). Delegates to the shared
     * {@link TableFileResolver} so the index subsystem resolves files identically.
     */
    private List<File> resolveJsonFiles(String tableName) throws IOException {
        return fileResolver.resolveFiles(tableName);
    }

    /**
     * Build a freshness fingerprint (path + last-modified + size) for a table's backing
     * file(s). Returns an empty string for unmapped tables (e.g. CTE references), which
     * have no source file. Used to make CTE cache keys sensitive to source changes.
     */
    private String sourceFingerprint(TableInfo tableInfo) {
        if (tableInfo == null || tableInfo.getTableName() == null) {
            return "";
        }
        try {
            ParsedQuery probe = new ParsedQuery();
            probe.setFromTable(tableInfo);
            return SourceFingerprint.forParsedQuery(probe, mappingManager, fileResolver);
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Load data from a JSON file and apply JSONPath.
     * Returns unwrapped data (without table name wrapper).
     */
    private List<JsonNode> loadFromFile(File jsonFile, String jsonPathExpression) throws IOException {
        // Read JSON file
        String jsonContent = Files.readString(jsonFile.toPath());
        
        // Apply JSONPath
        Object result = JsonPath.using(jsonPathConfig).parse(jsonContent).read(jsonPathExpression);
        
        // Convert to list of JsonNodes
        JsonNode resultNode = objectMapper.valueToTree(result);
        
        List<JsonNode> dataList = new ArrayList<>();
        
        if (resultNode.isArray()) {
            resultNode.forEach(node -> {
                if (node.isObject()) {
                    dataList.add(node);
                }
            });
        } else if (resultNode.isObject()) {
            // Single object
            dataList.add(resultNode);
        }
        
        return dataList;
    }
    
    /**
     * Wrap data in table structure for qualified column access.
     */
    private List<JsonNode> wrapTableData(List<JsonNode> data, TableInfo tableInfo) {
        List<JsonNode> wrappedData = new ArrayList<>();
        String effectiveName = tableInfo.getEffectiveName();
        
        for (JsonNode node : data) {
            if (node.isObject()) {
                ObjectNode wrappedNode = objectMapper.createObjectNode();
                wrappedNode.set(effectiveName, node);
                wrappedData.add(wrappedNode);
            } else {
                wrappedData.add(node);
            }
        }
        
        return wrappedData;
    }

    /**
     * Execute JOIN operations.
     */
    private List<JsonNode> executeJoins(List<JsonNode> leftData, ParsedQuery parsedQuery, QueryExecutionContext context) throws IOException {
        List<JsonNode> result = leftData;

        for (JoinInfo joinInfo : parsedQuery.getJoins()) {
            List<JsonNode> rightData = loadTableData(joinInfo.getTable(), context);
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
        // Only a single equi-join "a.b = c.d" is supported.
        String normalized = onCondition.trim();
        String upper = normalized.toUpperCase();

        if (upper.contains(" AND ") || upper.contains(" OR ")) {
            throw new IllegalArgumentException(
                "Only a single equi-join condition is supported in JOIN ON, got: " + onCondition);
        }

        // Reject non-equality comparison operators (>=, <=, <>, !=, >, <).
        if (normalized.contains(">=") || normalized.contains("<=") || normalized.contains("<>")
            || normalized.contains("!=") || normalized.contains(">") || normalized.contains("<")) {
            throw new IllegalArgumentException(
                "Only equi-join (=) conditions are supported in JOIN ON, got: " + onCondition);
        }

        String[] parts = normalized.split("=");
        if (parts.length != 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
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

        return joinValuesMatch(leftValue, rightValue);
    }

    /**
     * Compare two join key values with type coercion.
     * SQL NULL (missing or explicit null) never matches. Numbers compare numerically,
     * and a number matches a numeric string (e.g. 1 matches "1").
     */
    private boolean joinValuesMatch(JsonNode leftValue, JsonNode rightValue) {
        // Treat missing and explicit JSON null as SQL NULL, which never matches.
        if (leftValue == null || leftValue.isNull() || rightValue == null || rightValue.isNull()) {
            return false;
        }

        // Numeric comparison when both are numbers.
        if (leftValue.isNumber() && rightValue.isNumber()) {
            return leftValue.asDouble() == rightValue.asDouble();
        }

        // Mixed number/string: compare numerically if the string parses as a number.
        if (leftValue.isNumber() || rightValue.isNumber()) {
            Double l = parseDoubleOrNull(leftValue.asText());
            Double r = parseDoubleOrNull(rightValue.asText());
            if (l != null && r != null) {
                return l.doubleValue() == r.doubleValue();
            }
        }

        // Booleans compare by value.
        if (leftValue.isBoolean() && rightValue.isBoolean()) {
            return leftValue.asBoolean() == rightValue.asBoolean();
        }

        // Fall back to textual comparison.
        return leftValue.asText().equals(rightValue.asText());
    }

    private Double parseDoubleOrNull(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
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
                
                // Explicitly selected columns are always present in the output; a missing
                // or null source value is emitted as JSON null rather than omitted.
                if (value != null) {
                    projectedRow.set(outputName, value);
                } else {
                    projectedRow.putNull(outputName);
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
     * Apply DISTINCT to remove duplicate rows.
     * Rows are considered duplicates if all their fields have the same values.
     */
    private List<JsonNode> applyDistinct(List<JsonNode> data) {
        List<JsonNode> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        
        for (JsonNode row : data) {
            // Convert row to a canonical string representation for comparison
            // This handles nested objects and arrays properly
            String rowKey = rowToKey(row);
            
            if (!seen.contains(rowKey)) {
                seen.add(rowKey);
                result.add(row);
            }
        }
        
        return result;
    }
    
    /**
     * Convert a JsonNode row to a canonical string key for duplicate detection.
     * This sorts fields alphabetically to ensure consistent comparison.
     */
    private String rowToKey(JsonNode row) {
        try {
            // Create a sorted map of field names to values
            Map<String, JsonNode> fieldMap = new TreeMap<>();
            row.fields().forEachRemaining(entry -> {
                fieldMap.put(entry.getKey(), entry.getValue());
            });
            
            // Build sorted ObjectNode
            ObjectNode sortedRow = objectMapper.createObjectNode();
            fieldMap.forEach(sortedRow::set);
            
            // Serialize to JSON string for comparison
            return objectMapper.writeValueAsString(sortedRow);
        } catch (Exception e) {
            // Fallback to toString if serialization fails
            return row.toString();
        }
    }

    /**
     * Simple record for JOIN conditions.
     */
    private record JoinCondition(String leftField, String rightField) {}
}

