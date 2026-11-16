package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsonsql.DebugMode;
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
import java.util.*;
import java.util.function.Consumer;

/**
 * Executes parsed SQL queries against JSON data.
 */
public class QueryExecutor implements FieldAccessor {
    private final MappingManager mappingManager;
    private final File dataDirectory;
    private final ObjectMapper objectMapper;
    private final QueryParser queryParser;
    private final DataFileReader dataFileReader = new DataFileReader();
    private final CacheManager cacheManager;
    private final TableFileResolver fileResolver;
    private final IndexPlanner indexPlanner;
    private final MaterializedViewManager viewManager;
    private final MaterializedViewStore viewStore;
    private final boolean resolveMaterializedViews;
    private boolean strict;
    private Consumer<String> warningSink = message -> System.err.println("Warning: " + message);

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
        this.objectMapper = Json.newMapper();
        this.queryParser = new QueryParser();
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

    /**
     * In strict mode, data that would otherwise produce a warning fails the query instead.
     */
    public void setStrict(boolean strict) {
        this.strict = strict;
    }

    /**
     * Where warnings about the data go. By default they are written to stderr.
     */
    public void setWarningSink(Consumer<String> warningSink) {
        this.warningSink = warningSink;
    }

    public File getDataDirectory() {
        return dataDirectory;
    }

    /**
     * Execute a SQL query and return JSON result.
     */
    public String execute(String sql) throws Exception {
        try {
            return executeStatement(sql);
        } finally {
            forgetSubqueries();
        }
    }

    private String executeStatement(String sql) throws Exception {
        // Parse the query
        ParsedQuery parsedQuery = queryParser.parse(sql);

        // Create execution context for CTEs
        QueryExecutionContext context = new QueryExecutionContext();

        // Execute CTEs first if present
        if (parsedQuery.hasCTEs()) {
            if (DebugMode.isEnabled()) {
                System.err.println("DEBUG: Query has CTEs, count: " + parsedQuery.getCommonTableExpressions().size());
            }
            executeCTEs(parsedQuery, context);
        } else if (DebugMode.isEnabled()) {
            System.err.println("DEBUG: Query has no CTEs");
        }
        
        // Execute UNION query if present
        if (parsedQuery.isUnionQuery()) {
            return executeUnionQuery(parsedQuery.getUnionQuery(), context);
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
        // Handle UNION query - validate all sub-queries
        if (parsedQuery.isUnionQuery()) {
            UnionQuery unionQuery = parsedQuery.getUnionQuery();
            for (ParsedQuery subQuery : unionQuery.getSubQueries()) {
                validateResolvedTables(subQuery, resolvedTables);
            }
            return;
        }
        
        Set<String> availableCtes = new LinkedHashSet<>();
        for (Map.Entry<String, ParsedQuery> cte : parsedQuery.getCommonTableExpressions().entrySet()) {
            validateTableReferences(cte.getValue(), availableCtes, resolvedTables);
            availableCtes.add(cte.getKey());
        }
        validateTableReferences(parsedQuery, availableCtes, resolvedTables);
    }

    private void validateTableReferences(ParsedQuery query, Set<String> availableCtes,
                                         Set<String> resolvedTables) throws IOException {
        if (query.isUnionQuery()) {
            // A set operation inside a derived table
            for (ParsedQuery branch : query.getUnionQuery().getSubQueries()) {
                validateTableReferences(branch, availableCtes, resolvedTables);
            }
            return;
        }
        if (query.getFromTable() != null && query.getFromTable().getSubquery() == null) {
            ensureTableAccessible(query.getFromTable().getTableName(), availableCtes, resolvedTables);
        }
        if (query.hasJoins()) {
            for (JoinInfo join : query.getJoins()) {
                if (join.getTable() != null && join.getTable().getSubquery() == null) {
                    ensureTableAccessible(join.getTable().getTableName(), availableCtes, resolvedTables);
                }
            }
        }
        // A derived table reads its own tables, and the same CTEs
        for (ParsedQuery nested : query.nestedQueries()) {
            validateTableReferences(nested, availableCtes, resolvedTables);
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
        try {
            return runToRows(parsedQuery, new QueryExecutionContext());
        } finally {
            forgetSubqueries();
        }
    }

    /**
     * Run a plain query or a set operation and return its rows, reading the CTEs already in
     * the context.
     */
    private List<JsonNode> runToRows(ParsedQuery query, QueryExecutionContext context) throws Exception {
        String json = query.isUnionQuery()
            ? executeUnionQuery(query.getUnionQuery(), context)
            : executeQuery(query, context);
        JsonNode array = objectMapper.readTree(json);
        List<JsonNode> rows = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(rows::add);
        }
        return rows;
    }

    /**
     * The rows of a table in FROM or a join: a derived table's rows, run now, or else a CTE's,
     * a view's or a mapped table's, wrapped under the table's name.
     */
    private List<JsonNode> loadSource(TableInfo tableInfo, QueryExecutionContext context,
                                      ParsedQuery pruningQuery) throws Exception {
        if (tableInfo.getSubquery() != null) {
            return wrapTableData(runToRows(tableInfo.getSubquery(), context), tableInfo);
        }
        return loadTableData(tableInfo, context, pruningQuery);
    }
    
    /**
     * Execute a parsed query with execution context (supports recursive execution for CTEs).
     */
    private String executeQuery(ParsedQuery parsedQuery, QueryExecutionContext context) throws Exception {
        // Subqueries used as values run once, before any row
        runValueSubqueries(parsedQuery, context);

        // Load data from FROM table (checks CTE context first). The query is passed so the
        // index planner can prune backing files for the FROM table before loading.
        List<JsonNode> fromData = loadSource(parsedQuery.getFromTable(), context, parsedQuery);

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

        // Group, then apply HAVING, ORDER BY and projection to the groups
        List<JsonNode> projectedData;
        if (parsedQuery.isAggregate()) {
            projectedData = aggregate(filteredData, parsedQuery);
        } else {
            // Work out calculated columns and sort keys first, so ORDER BY can sort by them
            filteredData = calculateColumns(filteredData, parsedQuery);

            // Apply ORDER BY before projection so sort keys not present in the SELECT list
            // (e.g. ORDER BY id while selecting only name) remain available.
            if (parsedQuery.hasOrderBy()) {
                filteredData = applyOrderBy(filteredData, parsedQuery.getOrderBy(), this);
            }

            // Project SELECT columns
            projectedData = projectColumns(filteredData, parsedQuery.getSelectColumns());
        }

        // Apply DISTINCT after projection (dedupe the projected rows, preserving sorted order)
        if (parsedQuery.isDistinct()) {
            projectedData = applyDistinct(projectedData);
        }

        // Apply OFFSET and TOP/LIMIT last, so they operate on the final ordered/projected/deduped result set
        projectedData = applyOffsetAndLimit(projectedData, parsedQuery.getOffset(), parsedQuery.getEffectiveLimit());

        // Convert to JSON array string
        ArrayNode resultArray = objectMapper.createArrayNode();
        projectedData.forEach(resultArray::add);

        return objectMapper.writeValueAsString(resultArray);
    }
    
    /**
     * Execute a set operation (UNION, UNION ALL, INTERSECT, EXCEPT) and return the combined results.
     */
    private String executeUnionQuery(UnionQuery unionQuery, QueryExecutionContext context) throws Exception {
        List<JsonNode> finalResults = new ArrayList<>();
        List<ParsedQuery> branches = unionQuery.getSubQueries();
        List<String> outputNames = outputNames(branches.get(0));

        // Execute each sub-query and combine results. Operators are left-associative, so
        // "A UNION ALL B UNION C" de-duplicates (A + B + C) while "A UNION B UNION ALL C"
        // keeps C's rows even if they repeat earlier ones.
        for (int i = 0; i < unionQuery.getSubQueries().size(); i++) {
            ParsedQuery subQuery = unionQuery.getSubQueries().get(i);
            
            if (DebugMode.isEnabled()) {
                System.err.println("DEBUG: Executing set operation query part " + (i + 1));
            }
            
            String resultJson = executeQuery(subQuery, context);
            JsonNode resultArray = objectMapper.readTree(resultJson);
            List<JsonNode> branchRows = new ArrayList<>();
            if (resultArray.isArray()) {
                for (JsonNode node : resultArray) {
                    branchRows.add(renameColumns(node, outputNames(subQuery), outputNames));
                }
            }
            if (i == 0) {
                finalResults.addAll(branchRows);
                continue;
            }

            // Rows are equal as DISTINCT counts them, through rowToKey
            switch (unionQuery.operatorAt(i - 1)) {
                case UNION_ALL -> finalResults.addAll(branchRows);
                case UNION -> {
                    finalResults.addAll(branchRows);
                    finalResults = applyDistinct(finalResults);
                }
                case INTERSECT -> finalResults = applyDistinct(keepRows(finalResults, branchRows, true));
                case EXCEPT -> finalResults = applyDistinct(keepRows(finalResults, branchRows, false));
            }
        }
        
        // Apply ORDER BY if specified (before LIMIT)
        if (unionQuery.hasOrderBy()) {
            finalResults = applyOrderBy(finalResults, unionOrderBy(unionQuery, outputNames), this);
        }

        // Apply OFFSET and LIMIT/TOP last
        finalResults = applyOffsetAndLimit(finalResults, unionQuery.getOffset(), unionQuery.getEffectiveLimit());
        
        // Convert to JSON array string
        ArrayNode resultArray = objectMapper.createArrayNode();
        finalResults.forEach(resultArray::add);
        
        return objectMapper.writeValueAsString(resultArray);
    }
    
    /**
     * The rows that are (for INTERSECT) or are not (for EXCEPT) among {@code others}, in order.
     */
    private List<JsonNode> keepRows(List<JsonNode> rows, List<JsonNode> others, boolean whenFound) {
        Set<String> otherKeys = new HashSet<>();
        for (JsonNode other : others) {
            otherKeys.add(rowToKey(other));
        }
        List<JsonNode> kept = new ArrayList<>();
        for (JsonNode row : rows) {
            if (otherKeys.contains(rowToKey(row)) == whenFound) {
                kept.add(row);
            }
        }
        return kept;
    }

    /**
     * Add the value of each calculated SELECT column and ORDER BY key to its row, under the
     * key it was given.
     */
    private List<JsonNode> calculateColumns(List<JsonNode> rows, ParsedQuery query) {
        List<ColumnInfo> columns = query.getSelectColumns().stream().filter(ColumnInfo::isComputed).toList();
        List<OrderByInfo> sortKeys = query.getOrderBy().stream().filter(order -> order.getCalculation() != null).toList();
        if (columns.isEmpty() && sortKeys.isEmpty()) {
            return rows;
        }

        // As for a plain ORDER BY, a name in a sort key means a SELECT alias before a field
        FieldAccessor aliasesThenFields = (row, name) -> {
            for (ColumnInfo column : query.getSelectColumns()) {
                if (column.hasAlias() && column.getAlias().equalsIgnoreCase(name)) {
                    return getFieldValue(row, column.getExpression());
                }
            }
            return getFieldValue(row, name);
        };
        ExpressionEvaluator columnValues = new ExpressionEvaluator(this);
        ExpressionEvaluator sortValues = new ExpressionEvaluator(withSubqueries(aliasesThenFields));

        List<JsonNode> calculated = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            ObjectNode withValues = objectMapper.createObjectNode();
            withValues.setAll((ObjectNode) row);
            for (ColumnInfo column : columns) {
                withValues.set(column.getExpression(), orNullNode(columnValues.evaluate(row, column.getComputed())));
            }
            for (OrderByInfo sortKey : sortKeys) {
                withValues.set(sortKey.getColumn(), orNullNode(sortValues.evaluate(withValues, sortKey.getCalculation())));
            }
            calculated.add(withValues);
        }

        List<String> found = new ArrayList<>(columnValues.leftOut("found"));
        found.addAll(sortValues.leftOut("found"));
        List<String> leftOut = new ArrayList<>(columnValues.leftOut("left out"));
        leftOut.addAll(sortValues.leftOut("left out"));
        reportLeftOut(found, leftOut);
        return calculated;
    }

    private static JsonNode orNullNode(JsonNode value) {
        return value == null ? NullNode.getInstance() : value;
    }

    // -- subqueries used as values ------------------------------------------------------

    /** The result of each subquery used as a value, by the node it is written as. */
    private final Map<Expression, JsonNode> subqueryValues = new IdentityHashMap<>();
    /** The rows of each subquery after IN, by the node it is written as. */
    private final Map<Expression, List<JsonNode>> subqueryLists = new IdentityHashMap<>();

    /**
     * Run the subqueries a query uses as values or after IN, once, before its rows. So they
     * run whether or not a row needs them, and their warnings and errors don't depend on the
     * outer data.
     */
    private void runValueSubqueries(ParsedQuery query, QueryExecutionContext context) throws Exception {
        for (ParsedQuery.ValueSubquery subquery : query.getValueSubqueries()) {
            List<JsonNode> values = new ArrayList<>();
            for (JsonNode row : runToRows(subquery.query(), context)) {
                // The subquery selects one column
                JsonNode value = row.isObject() && row.size() > 0 ? row.elements().next() : null;
                values.add(Values.isNull(value) ? null : value);
            }
            if (subquery.list()) {
                subqueryLists.put(subquery.node(), values);
                continue;
            }
            if (values.size() > 1) {
                throw new QueryExecutionException("Subquery returned " + values.size()
                    + " rows where one value is expected: " + subquery.node()
                    + " (add LIMIT 1 or use an aggregate such as MAX)");
            }
            subqueryValues.put(subquery.node(), values.isEmpty() ? null : values.get(0));
        }
    }

    @Override
    public JsonNode subqueryValue(Expression subquery) {
        if (!subqueryValues.containsKey(subquery)) {
            throw new IllegalStateException("The subquery has not been run: " + subquery);
        }
        return subqueryValues.get(subquery);
    }

    @Override
    public List<JsonNode> subqueryValues(Expression subquery) {
        List<JsonNode> values = subqueryLists.get(subquery);
        if (values == null) {
            throw new IllegalStateException("The subquery has not been run: " + subquery);
        }
        return values;
    }

    /** An accessor that reads fields its own way, and subquery results from this executor. */
    private FieldAccessor withSubqueries(FieldAccessor fields) {
        return new FieldAccessor() {
            @Override
            public JsonNode getFieldValue(JsonNode row, String fieldPath) {
                return fields.getFieldValue(row, fieldPath);
            }

            @Override
            public JsonNode subqueryValue(Expression subquery) {
                return QueryExecutor.this.subqueryValue(subquery);
            }

            @Override
            public List<JsonNode> subqueryValues(Expression subquery) {
                return QueryExecutor.this.subqueryValues(subquery);
            }
        };
    }

    /** A statement's subquery results are dropped when it ends. */
    private void forgetSubqueries() {
        subqueryValues.clear();
        subqueryLists.clear();
    }

    /**
     * Values a calculation could not use are a warning, or an error in strict mode.
     */
    private void reportLeftOut(List<String> found, List<String> leftOut) {
        if (strict && !found.isEmpty()) {
            throw new StrictModeException(String.join("; ", found));
        }
        leftOut.forEach(warningSink);
    }

    private List<JsonNode> aggregate(List<JsonNode> rows, ParsedQuery query) {
        // A grouped row holds each value directly under its key, which can contain dots. A
        // field grouped as cat can also be written p.cat.
        FieldAccessor groupFields = (row, name) -> {
            JsonNode value = row.get(name);
            return value == null && name.contains(".") ? row.get(name.substring(name.lastIndexOf('.') + 1)) : value;
        };

        Aggregator aggregator = new Aggregator(query, this);
        List<JsonNode> groups = aggregator.group(rows);
        reportLeftOut(aggregator.leftOut("found"), aggregator.leftOut("left out"));

        // Calculations on groups, such as SUM(price) * 2: first the SELECT columns, so that
        // HAVING and ORDER BY can use their aliases
        ExpressionEvaluator groupValues = new ExpressionEvaluator(withSubqueries(groupFields), query.getGroupCalculations().keySet());
        for (JsonNode group : groups) {
            for (ColumnInfo column : query.getSelectColumns()) {
                if (column.isComputed()) {
                    JsonNode value = orNullNode(groupValues.evaluate(group, column.getComputed()));
                    ((ObjectNode) group).set(column.getExpression(), value);
                    ((ObjectNode) group).set(column.getAlias(), value);
                }
            }
        }
        if (query.getHaving() != null) {
            groups = groups.stream().filter(group -> groupValues.conditions().evaluate(group, query.getHaving())).toList();
        }
        if (query.hasOrderBy()) {
            for (JsonNode group : groups) {
                for (OrderByInfo sortKey : query.getOrderBy()) {
                    if (sortKey.getCalculation() != null) {
                        ((ObjectNode) group).set(sortKey.getColumn(), orNullNode(groupValues.evaluate(group, sortKey.getCalculation())));
                    }
                }
            }
            groups = applyOrderBy(groups, query.getOrderBy(), groupFields);
        }
        reportLeftOut(groupValues.leftOut("found"), groupValues.leftOut("left out"));

        List<String> names = outputNames(query);
        List<ColumnInfo> columns = query.getSelectColumns();
        List<JsonNode> projected = new ArrayList<>();
        for (JsonNode group : groups) {
            ObjectNode row = objectMapper.createObjectNode();
            for (int i = 0; i < columns.size(); i++) {
                row.set(names.get(i), group.get(columns.get(i).getExpression()));
            }
            projected.add(row);
        }
        return projected;
    }

    private List<JsonNode> applyOffsetAndLimit(List<JsonNode> rows, Long offset, Long limit) {
        int from = offset == null ? 0 : (int) Math.min(offset, rows.size());
        int to = limit == null ? rows.size() : (int) Math.min(from + limit, rows.size());
        return rows.subList(from, to);
    }

    /**
     * The column names a query produces, or null for SELECT *, where they depend on the data.
     */
    private List<String> outputNames(ParsedQuery query) {
        List<ColumnInfo> columns = query.getSelectColumns();
        if (columns.size() == 1 && "*".equals(columns.get(0).getExpression())) {
            return null;
        }
        if (query.isAggregate()) {
            return groupedOutputNames(columns);
        }
        List<String> names = new ArrayList<>();
        projectColumns(List.of(objectMapper.createObjectNode()), columns).get(0).fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** As for plain queries, two columns with the same name fall back to their full expression. */
    private List<String> groupedOutputNames(List<ColumnInfo> columns) {
        Map<String, Integer> counts = new HashMap<>();
        columns.forEach(column -> counts.merge(column.getOutputName(), 1, Integer::sum));
        return columns.stream()
            .map(column -> counts.get(column.getOutputName()) > 1 && !column.hasAlias()
                ? column.getExpression() : column.getOutputName())
            .toList();
    }

    /**
     * As in SQL, a UNION's columns are matched by position and named by its first branch.
     */
    private JsonNode renameColumns(JsonNode row, List<String> names, List<String> targetNames) {
        if (names == null || targetNames == null || names.equals(targetNames) || names.size() != targetNames.size()) {
            return row;
        }
        ObjectNode renamed = objectMapper.createObjectNode();
        for (int i = 0; i < names.size(); i++) {
            renamed.set(targetNames.get(i), row.get(names.get(i)));
        }
        return renamed;
    }

    /**
     * ORDER BY on a UNION can use a column name from any branch, which is then the
     * first branch's column in the same position.
     */
    private List<OrderByInfo> unionOrderBy(UnionQuery unionQuery, List<String> outputNames) {
        if (outputNames == null) {
            return unionQuery.getOrderBy();
        }
        List<OrderByInfo> orderBy = new ArrayList<>();
        for (OrderByInfo order : unionQuery.getOrderBy()) {
            orderBy.add(new OrderByInfo(firstBranchName(order.getColumn(), unionQuery, outputNames), order.isAscending()));
        }
        return orderBy;
    }

    private String firstBranchName(String column, UnionQuery unionQuery, List<String> outputNames) {
        for (ParsedQuery branch : unionQuery.getSubQueries()) {
            List<String> names = outputNames(branch);
            if (names != null && names.contains(column) && names.size() == outputNames.size()) {
                return outputNames.get(names.indexOf(column));
            }
        }
        return column;
    }

    /**
     * Execute all CTEs and store results in context.
     * Checks cache first if caching is enabled.
     */
    private void executeCTEs(ParsedQuery parsedQuery, QueryExecutionContext context) throws Exception {
        if (DebugMode.isEnabled()) {
            System.err.println("DEBUG: executeCTEs called, CTE count: " + parsedQuery.getCommonTableExpressions().size());
        }
        // Cache keys of the CTEs computed so far, so a CTE reading an earlier CTE inherits its
        // source fingerprint (and is invalidated when that CTE's sources change).
        Map<String, String> cteCacheKeys = new LinkedHashMap<>();
        for (Map.Entry<String, ParsedQuery> cteEntry : parsedQuery.getCommonTableExpressions().entrySet()) {
            String cteName = cteEntry.getKey();
            ParsedQuery cteQuery = cteEntry.getValue();
            
            if (DebugMode.isEnabled()) {
                System.err.println("DEBUG: Processing CTE: " + cteName);
            }
            
            List<JsonNode> cteData = null;
            String cteCacheKey = null;
            
            // Check cache first if enabled
            if (cacheManager != null) {
                cteCacheKey = generateCTECacheKey(cteName, cteQuery, cteCacheKeys);
                cteCacheKeys.put(cteName, cteCacheKey);
                // A cached result can't repeat its warnings, so strict mode always runs the query
                cteData = strict ? null : cacheManager.getCachedCTE(cteCacheKey);

                if (cteData != null) {
                    // Use cached CTE result
                    context.setCTEResult(cteName, cteData);
                    continue; // Skip execution, use cached data
                }
            }
            
            // Not cached (or caching disabled), execute the CTE query
            cteData = runToRows(cteQuery, context);
            
            // Save to cache if enabled
            if (cacheManager != null && !cteData.isEmpty()) {
                try {
                    if (DebugMode.isEnabled()) {
                        System.err.println("DEBUG: Caching CTE " + cteName + " with key: " + cteCacheKey + ", data size: " + cteData.size());
                    }
                    cacheManager.saveCTEToCache(cteCacheKey, cteData);
                    if (DebugMode.isEnabled()) {
                        System.err.println("DEBUG: CTE cache saved successfully");
                    }
                } catch (Exception e) {
                    // If cache save fails, continue without caching
                    // This shouldn't break the query execution
                    // Log error in debug mode
                    if (DebugMode.isEnabled()) {
                        System.err.println("Failed to cache CTE " + cteName + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            } else if (DebugMode.isEnabled()) {
                System.err.println("DEBUG: Not caching CTE " + cteName + " - cacheManager=" + (cacheManager != null) + ", isEmpty=" + cteData.isEmpty());
            }
            
            // Store CTE result in context
            context.setCTEResult(cteName, cteData);
        }
    }
    
    /**
     * Generate a deterministic cache key for a CTE based on its query structure.
     * The key includes: CTE name, table name, WHERE clause, and other relevant parts.
     *
     * @param upstreamCteKeys cache keys of CTEs defined earlier in the same WITH list
     */
    private String generateCTECacheKey(String cteName, ParsedQuery cteQuery,
                                       Map<String, String> upstreamCteKeys) {
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
                // INNER and LEFT keep their 3.0 keys: JOIN: and LEFTJOIN:
                String type = join.getType() == JoinInfo.Type.INNER ? "" : join.getType().name();
                keyBuilder.append(type).append("JOIN:").append(join.getTable().getTableName())
                    .append(":").append(join.getOnCondition()).append(":");
            }
        }
        
        // Add UNNESTs if any
        if (cteQuery.hasUnnests()) {
            for (var unnest : cteQuery.getUnnests()) {
                // A plain UNNEST keeps its 3.0 key
                keyBuilder.append(unnest.isOuter() ? "LEFTUNNEST:" : "UNNEST:").append(unnest.getArrayExpression())
                    .append(":").append(unnest.getAlias()).append(":");
            }
        }
        
        // Add SELECT column list (projection affects the cached result)
        if (cteQuery.getSelectColumns() != null && !cteQuery.getSelectColumns().isEmpty()) {
            keyBuilder.append("SELECT:");
            for (var col : cteQuery.getSelectColumns()) {
                // A calculated column's key is only its position, so the calculation goes in too
                keyBuilder.append(col.isComputed() ? col.getComputed().toString() : col.getExpression());
                if (col.hasAlias()) {
                    keyBuilder.append(" AS ").append(col.getAlias());
                }
                keyBuilder.append(",");
            }
            keyBuilder.append(":");
        }

        // Add GROUP BY and HAVING (grouping affects the cached result)
        if (!cteQuery.getGroupBy().isEmpty()) {
            keyBuilder.append("GROUPBY:").append(String.join(",", cteQuery.getGroupBy())).append(":");
        }
        if (cteQuery.getHaving() != null) {
            keyBuilder.append("HAVING:").append(cteQuery.getHaving()).append(":");
        }

        // Add ORDER BY (ordering affects the cached result, especially combined with LIMIT)
        if (cteQuery.hasOrderBy()) {
            keyBuilder.append("ORDERBY:");
            for (OrderByInfo ob : cteQuery.getOrderBy()) {
                // A calculated sort key's column is only its position, so the calculation goes in
                keyBuilder.append(ob.getCalculation() != null ? ob.getCalculation().toString() : ob.getColumn())
                    .append(ob.isAscending() ? " ASC" : " DESC").append(",");
            }
            keyBuilder.append(":");
        }

        // Add LIMIT / TOP / OFFSET (row limiting affects the cached result)
        if (cteQuery.getEffectiveLimit() != null) {
            keyBuilder.append("LIMIT:").append(cteQuery.getEffectiveLimit()).append(":");
        }
        if (cteQuery.getOffset() != null) {
            keyBuilder.append("OFFSET:").append(cteQuery.getOffset()).append(":");
        }

        // Add DISTINCT flag
        if (cteQuery.isDistinct()) {
            keyBuilder.append("DISTINCT:");
        }

        // Add source freshness fingerprint so editing the underlying JSON invalidates the CTE cache
        appendSourceFingerprint(keyBuilder, "SRC", cteQuery.getFromTable(), upstreamCteKeys);
        if (cteQuery.hasJoins()) {
            for (var join : cteQuery.getJoins()) {
                appendSourceFingerprint(keyBuilder, "JOINSRC", join.getTable(), upstreamCteKeys);
            }
        }
        appendSubquerySources(keyBuilder, cteQuery, upstreamCteKeys);

        return keyBuilder.toString();
    }

    /** What the subqueries a query uses as values read; their text is already in the key. */
    private void appendSubquerySources(StringBuilder keyBuilder, ParsedQuery query, Map<String, String> upstreamCteKeys) {
        for (ParsedQuery.ValueSubquery subquery : query.getValueSubqueries()) {
            StringBuilder sources = new StringBuilder();
            appendQuerySources(sources, subquery.query(), upstreamCteKeys);
            keyBuilder.append("SUBQUERYSRC[").append(sources).append("]:");
        }
    }

    /**
     * The source fingerprints of a derived table's query: its FROM and joins, or each branch
     * of a set operation. A derived table inside it adds its own sources the same way.
     */
    private void appendQuerySources(StringBuilder keyBuilder, ParsedQuery query, Map<String, String> upstreamCteKeys) {
        if (query.isUnionQuery()) {
            for (ParsedQuery branch : query.getUnionQuery().getSubQueries()) {
                appendQuerySources(keyBuilder, branch, upstreamCteKeys);
            }
            return;
        }
        appendSourceFingerprint(keyBuilder, "SRC", query.getFromTable(), upstreamCteKeys);
        for (JoinInfo join : query.getJoins()) {
            appendSourceFingerprint(keyBuilder, "JOINSRC", join.getTable(), upstreamCteKeys);
        }
        appendSubquerySources(keyBuilder, query, upstreamCteKeys);
    }

    private void appendSourceFingerprint(StringBuilder keyBuilder, String label, TableInfo tableInfo,
                                         Map<String, String> upstreamCteKeys) {
        if (tableInfo == null || tableInfo.getTableName() == null) {
            return;
        }
        if (tableInfo.getSubquery() != null) {
            // A derived table: the fingerprints of everything it reads
            StringBuilder derived = new StringBuilder();
            appendQuerySources(derived, tableInfo.getSubquery(), upstreamCteKeys);
            keyBuilder.append(label).append(":DERIVED[").append(derived).append("]:");
            return;
        }
        // CTE references shadow mappings/views, mirroring loadTableData's resolution order.
        String upstream = CteNames.resolveKey(upstreamCteKeys, tableInfo.getTableName());
        String fingerprint = upstream != null
            ? "CTE[" + upstreamCteKeys.get(upstream) + "]"
            : sourceFingerprint(tableInfo);
        if (!fingerprint.isEmpty()) {
            keyBuilder.append(label).append(":").append(fingerprint).append(":");
        }
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
     * file(s), or for a materialized view's stored row file. Returns an empty string for
     * names with no backing file. Used to make CTE cache keys sensitive to source changes.
     */
    private String sourceFingerprint(TableInfo tableInfo) {
        if (tableInfo == null || tableInfo.getTableName() == null) {
            return "";
        }
        if (isMaterializedView(tableInfo.getTableName())) {
            File storeFile = viewStore.storeFile(tableInfo.getTableName());
            return storeFile.exists()
                ? "view:" + tableInfo.getTableName() + "@" + storeFile.lastModified() + ":" + storeFile.length()
                : "";
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
        return dataFileReader.readRows(jsonFile, jsonPathExpression);
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
    private List<JsonNode> executeJoins(List<JsonNode> leftData, ParsedQuery parsedQuery, QueryExecutionContext context) throws Exception {
        List<JsonNode> result = leftData;

        // The names ON can use for the rows so far
        Set<String> earlierTables = new HashSet<>();
        earlierTables.add(parsedQuery.getFromTable().getEffectiveName());
        if (parsedQuery.hasUnnests()) {
            for (UnnestInfo unnest : parsedQuery.getUnnests()) {
                earlierTables.add(unnest.getAlias());
            }
        }

        for (JoinInfo joinInfo : parsedQuery.getJoins()) {
            List<JsonNode> rightData = loadSource(joinInfo.getTable(), context, null);
            result = performJoin(result, rightData, joinInfo, earlierTables);
            earlierTables.add(joinInfo.getTable().getEffectiveName());
        }

        return result;
    }

    /**
     * Perform a single JOIN operation. Rows come out in the order of the rows so far and,
     * for each, in the order of the joined table. A RIGHT or FULL JOIN then adds the joined
     * rows that matched nothing, in their own order.
     */
    private List<JsonNode> performJoin(
        List<JsonNode> leftData,
        List<JsonNode> rightData,
        JoinInfo joinInfo,
        Set<String> earlierTables
    ) {
        List<JsonNode> result = new ArrayList<>();
        JoinCondition condition = JoinCondition.of(joinInfo.getOn(), joinInfo.getTable(), earlierTables);
        List<JoinCondition.Key> keys = condition.keys();
        Expression residual = condition.residual();

        // With keys, look the joined rows up by them; without, every joined row is a candidate
        JoinIndex rightIndex = keys.isEmpty() ? null : new JoinIndex(rightData, keys.stream()
            .<java.util.function.Function<JsonNode, JsonNode>>map(key -> row -> getFieldValue(row, key.rightField()))
            .toList());
        WhereEvaluator evaluator = new WhereEvaluator(this);
        JoinInfo.Type type = joinInfo.getType();
        // By identity: two joined rows with the same content are still two rows
        Set<JsonNode> matchedRight = Collections.newSetFromMap(new IdentityHashMap<>());

        for (JsonNode leftRow : leftData) {
            List<JsonNode> candidates = rightData;
            if (rightIndex != null) {
                List<JsonNode> leftValues = new ArrayList<>(keys.size());
                for (JoinCondition.Key key : keys) {
                    leftValues.add(getFieldValue(leftRow, key.leftField()));
                }
                candidates = rightIndex.matchesAll(leftValues);
            }

            boolean matched = false;
            for (JsonNode rightRow : candidates) {
                // Merge left and right rows
                ObjectNode mergedRow = objectMapper.createObjectNode();
                mergedRow.setAll((ObjectNode) leftRow);
                mergedRow.setAll((ObjectNode) rightRow);
                if (residual == null || evaluator.evaluate(mergedRow, residual)) {
                    result.add(mergedRow);
                    matched = true;
                    matchedRight.add(rightRow);
                }
            }

            // For LEFT and FULL JOIN, include left row even if no match found
            if (type.keepsUnmatchedLeft() && !matched) {
                result.add(leftRow);
            }
        }

        // For RIGHT and FULL JOIN, then the joined rows that matched nothing, in their order,
        // with the earlier tables' fields as null
        if (type.keepsUnmatchedRight()) {
            ObjectNode earlierAsNull = nullShape(leftData);
            for (JsonNode rightRow : rightData) {
                if (!matchedRight.contains(rightRow)) {
                    ObjectNode unmatchedRow = objectMapper.createObjectNode();
                    unmatchedRow.setAll(earlierAsNull);
                    unmatchedRow.setAll((ObjectNode) rightRow);
                    result.add(unmatchedRow);
                }
            }
        }

        reportLeftOut(evaluator.leftOut("found"), evaluator.leftOut("left out"));
        return result;
    }

    /**
     * The rows so far as one row of nulls: each table's wrapper holding every top-level field
     * any of its rows has, set to null. An unmatched joined row starts from this, so an
     * unprefixed name of an earlier table is found there, as null, rather than read from the
     * joined table. With no rows so far there are no fields to copy, and this is empty.
     */
    private ObjectNode nullShape(List<JsonNode> rows) {
        ObjectNode shape = objectMapper.createObjectNode();
        for (JsonNode row : rows) {
            for (var entry : row.properties()) {
                JsonNode value = entry.getValue();
                if (value.isObject()) {
                    JsonNode existing = shape.get(entry.getKey());
                    ObjectNode fields = existing instanceof ObjectNode object ? object : shape.putObject(entry.getKey());
                    value.fieldNames().forEachRemaining(name -> fields.set(name, FilledNull.INSTANCE));
                } else if (!shape.has(entry.getKey())) {
                    // A value at the top of the row, such as an UNNEST element
                    shape.set(entry.getKey(), FilledNull.INSTANCE);
                }
            }
        }
        return shape;
    }

    /**
     * Get field value from a row using direct path navigation.
     */
    private JsonNode getFieldValueDirect(JsonNode row, String fieldPath) {
        String[] parts = fieldPath.split("\\.");
        JsonNode current = row;

        // Navigate through the path
        for (String part : parts) {
            if (current instanceof FilledNull) {
                // A filled-in field of an unmatched row: profile.level is null here too
                return current;
            }
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
        for (var entry : row.properties()) {
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

        reportLeftOut(evaluator.leftOut("found"), evaluator.leftOut("left out"));
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
    private List<JsonNode> applyOrderBy(List<JsonNode> data, List<OrderByInfo> orderByList, FieldAccessor fields) {
        List<JsonNode> sortedData = new ArrayList<>(data);
        
        sortedData.sort((row1, row2) -> {
            for (OrderByInfo orderBy : orderByList) {
                JsonNode value1 = fields.getFieldValue(row1, orderBy.getColumn());
                JsonNode value2 = fields.getFieldValue(row2, orderBy.getColumn());
                
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
        // NULL and missing values sort together, before everything else
        boolean null1 = Values.isNull(node1);
        boolean null2 = Values.isNull(node2);
        if (null1 && null2) return 0;
        if (null1) return -1;
        if (null2) return 1;

        return Values.compare(node1, node2);
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
        
        row.properties().forEach(entry -> {
            if (ColumnInfo.isCalculatedKey(entry.getKey())) {
                // A sort key worked out for ORDER BY, which isn't part of the row
                return;
            }
            if (entry.getValue().isObject()) {
                entry.getValue().properties().forEach(innerEntry -> {
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
            
            if (arrayField != null && arrayField.isArray() && !arrayField.isEmpty()) {
                // For each element in the array, create a new row
                for (JsonNode arrayElement : arrayField) {
                    ObjectNode newRow = objectMapper.createObjectNode();

                    // Copy all original fields
                    row.properties().forEach(entry -> {
                        newRow.set(entry.getKey(), entry.getValue());
                    });

                    // Add the unnested element with the specified alias and column name
                    newRow.set(unnest.getElementColumn(), arrayElement);

                    result.add(newRow);
                }
            } else if (unnest.isOuter()) {
                // LEFT JOIN UNNEST keeps a row with no elements, once, with the element null.
                // A filled-in null, so a nested name such as item.pid reads null here rather
                // than being looked for in a later table.
                ObjectNode newRow = objectMapper.createObjectNode();
                newRow.setAll((ObjectNode) row);
                newRow.set(unnest.getElementColumn(), FilledNull.INSTANCE);
                result.add(newRow);
            }
            // Otherwise a row whose list is empty, null, missing or not a list is left out, as in SQL
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
     * This sorts fields alphabetically to ensure consistent comparison, and gives equal
     * values the same key, so 10 and 10.0 are duplicates.
     */
    private String rowToKey(JsonNode row) {
        Map<String, JsonNode> fieldMap = new TreeMap<>();
        row.properties().forEach(entry -> fieldMap.put(entry.getKey(), entry.getValue()));

        StringBuilder key = new StringBuilder();
        fieldMap.forEach((name, value) -> {
            String identity = Values.isNull(value) ? "null" : Values.identity(value);
            key.append(name.length()).append(':').append(name)
                .append(identity.length()).append(':').append(identity);
        });
        return key.toString();
    }
}

