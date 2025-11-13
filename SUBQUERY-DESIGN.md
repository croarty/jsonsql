# Subquery & Temporary Table Design Document

## Overview

This document explores how to implement subqueries and temporary tables (CTEs) in JsonSQL, specifically focusing on the ability to define temporary result sets that can be used in JOINs.

## Use Cases

### 1. Common Table Expressions (CTEs) - WITH Clause

```sql
WITH expensive_products AS (
  SELECT * FROM products WHERE price > 100
)
SELECT o.orderId, ep.name, ep.price
FROM orders o
JOIN expensive_products ep ON o.productId = ep.id
```

### 2. Subqueries in FROM Clause

```sql
SELECT o.orderId, ep.name
FROM orders o
JOIN (
  SELECT * FROM products WHERE price > 100
) AS ep ON o.productId = ep.id
```

### 3. Multiple CTEs

```sql
WITH 
  expensive_products AS (SELECT * FROM products WHERE price > 100),
  recent_orders AS (SELECT * FROM orders WHERE orderDate >= '2024-01-01')
SELECT ep.name, ro.orderId
FROM recent_orders ro
JOIN expensive_products ep ON ro.productId = ep.id
```

## Current Architecture Analysis

### How Tables Are Currently Loaded

**Current Flow:**
1. `QueryParser.parse()` → Creates `ParsedQuery`
2. `QueryExecutor.execute()` → Calls `loadTableData(TableInfo)`
3. `loadTableData()` → Uses `MappingManager` to find JSONPath mapping
4. Loads JSON file(s) and extracts data using JSONPath
5. Wraps data in table structure: `{"tableName": {...data...}}`

**Key Components:**
- `TableInfo` - Represents a table reference (name, alias)
- `MappingManager` - Maps table names to JSONPath expressions
- `loadTableData()` - Loads data from JSON files

### Current Limitations

1. **TableInfo only supports string table names** - No support for subqueries
2. **loadTableData() expects a mapping** - Can't handle dynamic subquery results
3. **No query execution context** - Can't execute nested queries
4. **No temporary result storage** - Results are only returned, not stored

## Proposed Architecture

### Option 1: Common Table Expressions (CTEs) - Recommended

**Syntax:**
```sql
WITH cte_name AS (SELECT ...)
SELECT ... FROM cte_name JOIN ...
```

**Advantages:**
- Clean, readable syntax
- Can define multiple CTEs
- CTEs can reference other CTEs
- Standard SQL feature
- Reusable within the same query

**Implementation Approach:**

#### 1. Extend ParsedQuery

```java
public class ParsedQuery {
    // ... existing fields ...
    private Map<String, ParsedQuery> commonTableExpressions = new LinkedHashMap<>();
    
    public void addCTE(String name, ParsedQuery cteQuery) { ... }
    public Map<String, ParsedQuery> getCTEs() { ... }
    public boolean hasCTEs() { ... }
}
```

#### 2. Parse WITH Clause in QueryParser

```java
private void parseWithClause(PlainSelect plainSelect, ParsedQuery query) {
    WithItem withItem = plainSelect.getWithItemsList();
    if (withItem != null) {
        for (WithItem item : withItem.getWithItemsList()) {
            String cteName = item.getName();
            Select cteSelect = item.getSelect();
            ParsedQuery cteQuery = buildParsedQuery(getPlainSelect(cteSelect));
            query.addCTE(cteName, cteQuery);
        }
    }
}
```

#### 3. Execute CTEs Before Main Query

```java
public String execute(String sql) throws Exception {
    ParsedQuery parsedQuery = queryParser.parse(sql);
    
    // Execute CTEs first and store results
    Map<String, List<JsonNode>> cteResults = new HashMap<>();
    if (parsedQuery.hasCTEs()) {
        for (Map.Entry<String, ParsedQuery> cte : parsedQuery.getCTEs().entrySet()) {
            // Recursively execute CTE query
            String cteResultJson = executeQuery(cte.getValue());
            List<JsonNode> cteData = parseJsonArray(cteResultJson);
            cteResults.put(cte.getKey(), cteData);
        }
    }
    
    // Now execute main query, using CTE results when needed
    // ...
}
```

#### 4. Extend loadTableData to Support CTEs

```java
private List<JsonNode> loadTableData(TableInfo tableInfo, Map<String, List<JsonNode>> cteResults) {
    String tableName = tableInfo.getTableName();
    
    // Check if this is a CTE
    if (cteResults.containsKey(tableName)) {
        // Return pre-computed CTE result
        return cteResults.get(tableName);
    }
    
    // Otherwise, load from JSON file as before
    // ... existing loadTableData logic ...
}
```

### Option 2: Subqueries in FROM Clause

**Syntax:**
```sql
SELECT ... FROM (SELECT ...) AS alias JOIN ...
```

**Advantages:**
- More flexible placement
- Can be used anywhere a table is expected
- Standard SQL feature

**Implementation Approach:**

#### 1. Extend TableInfo to Support Subqueries

```java
public class TableInfo {
    private String tableName;
    private String alias;
    private ParsedQuery subquery;  // NEW: If not null, this is a subquery
    
    public boolean isSubquery() {
        return subquery != null;
    }
}
```

#### 2. Parse Subquery in FROM Clause

```java
private TableInfo extractTableInfo(FromItem fromItem) {
    TableInfo tableInfo = new TableInfo();
    
    if (fromItem instanceof ParenthesedSelect) {
        // This is a subquery: (SELECT ...) AS alias
        ParenthesedSelect subquerySelect = (ParenthesedSelect) fromItem;
        Select subquery = subquerySelect.getSelect();
        ParsedQuery subqueryParsed = buildParsedQuery(getPlainSelect(subquery));
        tableInfo.setSubquery(subqueryParsed);
        tableInfo.setAlias(fromItem.getAlias().getName());
    } else {
        // Regular table
        // ... existing logic ...
    }
    
    return tableInfo;
}
```

#### 3. Execute Subquery When Loading Table Data

```java
private List<JsonNode> loadTableData(TableInfo tableInfo) {
    if (tableInfo.isSubquery()) {
        // Execute the subquery
        return executeSubquery(tableInfo.getSubquery());
    }
    
    // ... existing loadTableData logic ...
}

private List<JsonNode> executeSubquery(ParsedQuery subquery) throws Exception {
    // Execute subquery using same execution logic
    String resultJson = executeQuery(subquery);
    return parseJsonArray(resultJson);
}
```

## Recommended Approach: Hybrid Solution

Support **both** CTEs and subqueries in FROM clause, as they serve different use cases:

- **CTEs**: Better for readability, multiple temporary tables, reusability
- **Subqueries in FROM**: More flexible, can be used inline

## Implementation Challenges

### 1. Recursive Query Execution

**Challenge:** CTEs and subqueries need to execute queries recursively.

**Solution:** 
- Refactor `QueryExecutor.execute()` to be more modular
- Create `executeQuery(ParsedQuery)` method that can be called recursively
- Handle execution context (CTE results, variable scoping)

### 2. CTE Result Storage

**Challenge:** CTE results need to be available throughout query execution.

**Solution:**
- Store CTE results in a Map<String, List<JsonNode>>
- Pass this map through execution context
- Make available to `loadTableData()` method

### 3. Circular Dependencies

**Challenge:** CTEs might reference each other in cycles.

**Solution:**
- Detect cycles during parsing
- Throw clear error message
- Example: `WITH a AS (SELECT * FROM b), b AS (SELECT * FROM a)` - invalid

### 4. Variable Scoping

**Challenge:** CTEs should be able to reference outer query columns (correlated subqueries).

**Initial Approach:** Start with **non-correlated** subqueries only (simpler).

**Future Enhancement:** Add correlated subquery support later.

### 5. Performance Considerations

**Challenge:** Subqueries execute independently, potentially multiple times.

**Optimization Opportunities:**
- Cache CTE results (already done by design)
- Detect when subquery is used multiple times
- Consider materializing results

## Proposed Implementation Plan

### Phase 1: CTEs (WITH Clause) - Foundation

1. **Extend ParsedQuery**
   - Add `Map<String, ParsedQuery> cteQueries`
   - Add methods: `addCTE()`, `getCTEs()`, `hasCTEs()`

2. **Extend QueryParser**
   - Add `parseWithClause()` method
   - Parse WITH items and create ParsedQuery for each
   - Handle CTE aliases

3. **Refactor QueryExecutor**
   - Create `executeQuery(ParsedQuery, Map<String, List<JsonNode>> cteContext)` method
   - Execute CTEs first, store in context
   - Pass context to `loadTableData()`
   - Modify `loadTableData()` to check CTE context before JSON files

4. **Testing**
   - Simple CTE
   - Multiple CTEs
   - CTE in JOIN
   - CTE referencing another CTE
   - Error cases (circular dependencies, invalid syntax)

### Phase 2: Subqueries in FROM Clause

1. **Extend TableInfo**
   - Add `ParsedQuery subquery` field
   - Add `isSubquery()` method

2. **Extend QueryParser**
   - Detect `ParenthesedSelect` in FROM clause
   - Parse subquery and store in TableInfo

3. **Extend QueryExecutor**
   - Modify `loadTableData()` to execute subquery if present
   - Handle subquery aliases

4. **Testing**
   - Subquery in FROM
   - Subquery in JOIN
   - Multiple subqueries
   - Subquery with WHERE, ORDER BY, etc.

### Phase 3: Advanced Features (Future)

1. **Correlated Subqueries**
   - Allow CTEs/subqueries to reference outer query columns
   - More complex execution model

2. **Subqueries in WHERE Clause**
   - `WHERE price > (SELECT AVG(price) FROM products)`
   - Requires scalar subquery support

3. **EXISTS / NOT EXISTS**
   - `WHERE EXISTS (SELECT 1 FROM orders WHERE ...)`
   - Boolean subquery support

## Code Structure Changes

### New Classes

```java
// Execution context for subqueries/CTEs
public class QueryExecutionContext {
    private Map<String, List<JsonNode>> cteResults = new HashMap<>();
    private QueryExecutor executor;
    
    public List<JsonNode> getCTEResult(String name) { ... }
    public void setCTEResult(String name, List<JsonNode> data) { ... }
    public List<JsonNode> executeSubquery(ParsedQuery subquery) { ... }
}
```

### Modified Classes

**QueryParser:**
- Add `parseWithClause()` method
- Modify `extractTableInfo()` to handle subqueries

**QueryExecutor:**
- Refactor `execute()` to use `QueryExecutionContext`
- Add `executeQuery(ParsedQuery, QueryExecutionContext)` method
- Modify `loadTableData()` to accept context

**ParsedQuery:**
- Add CTE storage
- Add subquery support in TableInfo

## Example Implementation Flow

### Query: WITH expensive AS (SELECT * FROM products WHERE price > 100) SELECT * FROM expensive

1. **Parse Phase:**
   - Parse WITH clause → Create CTE `expensive` with ParsedQuery
   - Parse main SELECT → Create main ParsedQuery
   - Store CTE in main ParsedQuery

2. **Execution Phase:**
   - Create QueryExecutionContext
   - Execute CTE `expensive`:
     - Load products data
     - Apply WHERE price > 100
     - Store result in context
   - Execute main query:
     - Load table "expensive" → Check context → Return CTE result
     - Project columns
     - Return result

## Benefits of This Approach

1. **Clean Separation:** CTEs are parsed and stored separately
2. **Reusability:** CTE results computed once, used multiple times
3. **Readability:** WITH clause makes queries more readable
4. **Extensibility:** Easy to add correlated subqueries later
5. **Performance:** CTE results cached, not recomputed

## Potential Issues & Solutions

### Issue 1: CTE Name Collision with Real Tables

**Problem:** What if CTE name matches a real table name?

**Solution:** CTEs take precedence. Check CTE context first, then real tables.

### Issue 2: Recursive CTEs

**Problem:** `WITH RECURSIVE` for hierarchical data.

**Solution:** Not in initial implementation. Add later if needed.

### Issue 3: Subquery Performance

**Problem:** Subqueries in FROM might execute multiple times.

**Solution:** For now, accept this. Future: detect and cache.

## Testing Strategy

### Unit Tests
- CTE parsing
- Subquery parsing
- Execution context
- Error handling (circular dependencies, invalid syntax)

### Integration Tests
- Simple CTE
- CTE with JOIN
- Multiple CTEs
- CTE referencing CTE
- Subquery in FROM
- Subquery in JOIN
- Complex combinations

### Edge Cases
- Empty CTE results
- CTE with DISTINCT
- CTE with ORDER BY
- CTE with LIMIT
- Nested subqueries (subquery in CTE)

## Conclusion

The recommended approach is to implement **CTEs first** (WITH clause), as they provide:
- Better readability
- Clear separation of concerns
- Standard SQL feature
- Foundation for more complex subquery features

Then add **subqueries in FROM clause** for inline flexibility.

This design provides a solid foundation that can be extended to support correlated subqueries and other advanced features in the future.

