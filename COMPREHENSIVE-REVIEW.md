# JsonSQL Comprehensive Functionality Review & Enhancement Suggestions

## Executive Summary

JsonSQL is a well-architected SQL-like query engine for JSON data. This document provides a thorough review of existing functionality and prioritized suggestions for new features.

**Current Status:**
- **Test Coverage**: 570 tests, all passing ✅
- **Core Features**: Fully functional SQL-like query engine
- **Recent Additions**: DISTINCT, ILIKE, CTEs, CSV output, schema introspection (`--describe`), dry-run validation (`--dry-run`), declared indexes / file-level pruning (`--add-index`)
- **Architecture**: Clean separation of concerns, well-structured, maintainable

---

## Part 1: Current Functionality Review

### 1.1 Core Query Features ✅

#### SELECT Clause
- **Status**: Fully Implemented
- **Features**:
  - `SELECT *` - All fields
  - `SELECT field1, field2` - Specific fields
  - `SELECT table.field` - Qualified field names
  - `SELECT field AS alias` - Column aliases
  - Implicit aliases (space-separated)
  - `SELECT DISTINCT field` - Unique values (✅ **IMPLEMENTED**)
  - `SELECT DISTINCT field1, field2` - Unique combinations (✅ **IMPLEMENTED**)
  - `SELECT DISTINCT *` - Unique rows (✅ **IMPLEMENTED**)
- **Limitations**:
  - No calculated fields (e.g., `SELECT price * 1.1 AS total`)
  - No functions in SELECT (e.g., `SELECT UPPER(name)`)
  - No aggregation functions (COUNT, SUM, AVG, etc.)

#### FROM Clause
- **Status**: Fully Implemented
- **Features**:
  - Table names with JSONPath mappings
  - Table aliases (e.g., `FROM orders o`)
  - Multiple tables from single file
  - Directory-based multi-file loading
  - Relative path support
  - CTE references (✅ **IMPLEMENTED** - via WITH clause)
- **Strengths**: Excellent flexibility for different JSON structures

#### WHERE Clause
- **Status**: Fully Implemented with Advanced Features
- **Comparison Operators**: `=`, `!=`, `>`, `<`, `>=`, `<=`
- **Logical Operators**: `AND`, `OR`, `NOT`
- **Pattern Matching**: 
  - `LIKE`, `NOT LIKE` with `%` and `_` wildcards
  - `ILIKE`, `NOT ILIKE` (✅ **IMPLEMENTED** - case-insensitive LIKE)
- **Null Handling**: `IS NULL`, `IS NOT NULL`
- **List Matching**: `IN`, `NOT IN`
- **Grouping**: Parentheses `()` for complex conditions
- **Nested Field Access**: `WHERE profile.level = 'Gold'`
- **Limitations**:
  - No `BETWEEN` operator
  - No subqueries in WHERE

#### JOIN Operations
- **Status**: Partially Implemented
- **Features**:
  - `INNER JOIN` (via `JOIN`)
  - `LEFT JOIN` (via `LEFT JOIN`)
  - Multiple JOINs in single query
  - JOIN with WHERE clauses
  - JOIN with UNNEST (execution order fixed)
  - JOIN with CTEs (✅ **IMPLEMENTED**)
- **Limitations**:
  - No `RIGHT JOIN`
  - No `FULL OUTER JOIN`
  - No `CROSS JOIN` (explicit)
  - No self-joins with same table (untested)

#### UNNEST Operations
- **Status**: Fully Implemented
- **Features**:
  - `UNNEST(array) AS alias(column)` syntax
  - Works with string arrays
  - Works with object arrays
  - Multiple UNNESTs (comma-separated)
  - UNNEST with WHERE filtering
  - UNNEST with JOIN (execution order fixed)
- **Strengths**: Well-tested and robust

#### ORDER BY Clause
- **Status**: Fully Implemented
- **Features**:
  - Single column sorting
  - Multi-column sorting
  - `ASC` (default) and `DESC`
  - Qualified column names
  - Nested field sorting
  - Numeric, text, and boolean sorting
- **Strengths**: Handles mixed types intelligently

#### TOP / LIMIT
- **Status**: Fully Implemented
- **Features**:
  - `SELECT TOP n`
  - `LIMIT n`
  - Works with ORDER BY
  - Works with JOINs
  - Works with DISTINCT
- **Limitations**:
  - No `OFFSET` support for pagination

#### DISTINCT Clause
- **Status**: ✅ **FULLY IMPLEMENTED**
- **Features**:
  - `SELECT DISTINCT column` - Unique values for a column
  - `SELECT DISTINCT column1, column2` - Unique combinations
  - `SELECT DISTINCT *` - Unique rows (all columns must match)
  - Works with WHERE, ORDER BY, TOP/LIMIT
  - Handles complex nested objects correctly
- **Implementation**: Uses canonical JSON string representation with sorted field names for duplicate detection
- **Test Coverage**: Comprehensive test suite in `DistinctTest.java`

#### Common Table Expressions (CTEs)
- **Status**: ✅ **FULLY IMPLEMENTED**
- **Features**:
  - `WITH cte_name AS (SELECT ...)` syntax
  - Multiple CTEs in single query
  - CTEs can reference other CTEs (forward references)
  - CTEs can be used in FROM clause
  - CTEs can be used in JOINs
  - CTEs with WHERE, ORDER BY, DISTINCT, LIMIT
- **Implementation**: 
  - Recursive query execution via `QueryExecutionContext`
  - CTE results stored in execution context
  - Supports nested CTE definitions
- **Test Coverage**: Comprehensive test suite in `CteTest.java` (9 test cases)

### 1.2 Data Management Features ✅

#### JSONPath Mappings
- **Status**: Fully Implemented
- **Features**:
  - Persistent storage in `.jsonsql-mappings.json`
  - Add mappings via CLI
  - List all mappings
  - Support for filename:jsonpath format
  - Directory-based multi-file support
  - Relative path support
- **Strengths**: Flexible and well-designed

#### Saved Queries
- **Status**: Fully Implemented
- **Features**:
  - Save queries with names
  - List saved queries
  - Run saved queries
  - Delete saved queries
  - Persistent storage in `.jsonsql-queries.json`
  - 39 pre-configured example queries included
  - Parameterized queries with `${var}` and `${var:default}` syntax (substituted via `--param key=value`)
- **Limitations**:
  - No query versioning

### 1.3 Output Features ✅

#### Output Options
- **Status**: Fully Implemented (JSON + CSV)
- **Features**:
  - Stdout (default)
  - File output (`--output`)
  - Clipboard (`--clipboard`)
  - Pretty-print JSON (`--pretty`)
  - CSV output (`--format csv`) with header row and RFC 4180 escaping
- **Limitations**:
  - No TSV/Table formats
  - No XML output

### 1.4 Architecture & Code Quality ✅

#### Strengths
- Clean separation of concerns
- Well-structured packages
- Comprehensive test coverage (570 tests, all passing)
- Good error handling
- Flexible field accessor pattern
- Efficient JSONPath integration
- Recursive query execution for CTEs
- Execution context pattern for managing CTE results

#### Code Organization
- `query/` - Query parsing and execution
  - `QueryParser.java` - SQL parsing (JSqlParser integration)
  - `QueryExecutor.java` - Query execution engine
  - `WhereEvaluator.java` - WHERE clause evaluation
  - `FieldAccessor.java` - Field access interface
  - `QueryExecutionContext.java` - CTE execution context
  - Supporting classes: `ParsedQuery`, `ColumnInfo`, `TableInfo`, `JoinInfo`, `UnnestInfo`, `OrderByInfo`
- `config/` - Configuration management
  - `MappingManager.java` - JSONPath mapping management
  - `QueryManager.java` - Saved query management
- `output/` - Output handling
  - `OutputHandler.java` - Output formatting and delivery
- Clear interfaces (FieldAccessor)

#### Dependencies
- **Java 21**: Modern Java features
- **JSqlParser 4.7**: SQL parsing
- **Jackson 2.16.0**: JSON processing
- **JSONPath 2.9.0**: JSONPath expression evaluation
- **Picocli 4.7.5**: Command-line interface
- **JUnit 5.10.1**: Testing framework

---

## Part 2: Missing Core SQL Features

### 2.1 Aggregation & Grouping (HIGH PRIORITY)

**Current Status**: Not Implemented

**Missing Features**:
- `GROUP BY` clause
- Aggregation functions:
  - `COUNT(*)` / `COUNT(column)`
  - `SUM(column)`
  - `AVG(column)`
  - `MIN(column)`
  - `MAX(column)`
- `HAVING` clause (filtering grouped results)

**Use Cases**:
```sql
-- Count products by category
SELECT category, COUNT(*) as count 
FROM products 
GROUP BY category

-- Average price by category
SELECT category, AVG(price) as avg_price 
FROM products 
GROUP BY category

-- Categories with more than 5 products
SELECT category, COUNT(*) as count 
FROM products 
GROUP BY category 
HAVING COUNT(*) > 5
```

**Implementation Complexity**: Medium-High
**Business Value**: Very High (essential for analytics)

### 2.2 Calculated Fields & Functions (HIGH PRIORITY)

**Current Status**: Not Implemented

**Missing Features**:
- Arithmetic in SELECT: `SELECT price * 1.1 AS total`
- String functions: `UPPER()`, `LOWER()`, `CONCAT()`, `SUBSTRING()`, `LENGTH()`, `TRIM()`
- Numeric functions: `ROUND()`, `FLOOR()`, `CEIL()`, `ABS()`, `POWER()`
- Date/Time functions: `YEAR()`, `MONTH()`, `DAY()`, `DATE()`, `DATEADD()`, `DATEDIFF()`
- Null handling: `COALESCE()`, `IFNULL()`
- Conditional: `CASE WHEN ... THEN ... ELSE ... END` (attempted but not implemented)

**Use Cases**:
```sql
-- Calculate total with tax
SELECT name, price, price * 1.1 AS price_with_tax FROM products

-- String manipulation
SELECT UPPER(name) AS name_upper, CONCAT(name, ' - ', category) AS full_name FROM products

-- Date extraction
SELECT orderDate, YEAR(orderDate) AS order_year FROM orders

-- Null handling
SELECT name, COALESCE(description, 'No description') AS desc FROM products
```

**Implementation Complexity**: Medium
**Business Value**: High (very common in real queries)

### 2.3 WHERE Clause Enhancements (MEDIUM PRIORITY)

**Current Status**: Partially Implemented

**Missing Features**:
- `BETWEEN` operator: `WHERE price BETWEEN 100 AND 500`
- Subqueries: `WHERE price > (SELECT AVG(price) FROM products)`
- `EXISTS` / `NOT EXISTS`

**Use Cases**:
```sql
-- Range checking
SELECT * FROM products WHERE price BETWEEN 50 AND 200

-- Subquery
SELECT * FROM products 
WHERE price > (SELECT AVG(price) FROM products)
```

**Implementation Complexity**: Low-Medium (BETWEEN), Medium-High (Subqueries)
**Business Value**: Medium-High

### 2.4 JOIN Enhancements (MEDIUM PRIORITY)

**Current Status**: Partially Implemented

**Missing Features**:
- `RIGHT JOIN`
- `FULL OUTER JOIN`
- `CROSS JOIN` (explicit)
- Self-joins (same table, different aliases)

**Use Cases**:
```sql
-- Right join
SELECT * FROM products p RIGHT JOIN orders o ON p.id = o.productId

-- Full outer join
SELECT * FROM customers c FULL OUTER JOIN orders o ON c.id = o.customerId

-- Self-join (e.g., employee hierarchy)
SELECT e1.name AS employee, e2.name AS manager 
FROM employees e1 
LEFT JOIN employees e2 ON e1.managerId = e2.id
```

**Implementation Complexity**: Low-Medium
**Business Value**: Medium

### 2.5 Query Composition (MEDIUM PRIORITY)

**Current Status**: Partially Implemented

**Implemented**:
- ✅ Common Table Expressions (CTEs / WITH clause)

**Missing Features**:
- `UNION` / `UNION ALL`
- Subqueries in FROM clause (derived tables)
- Subqueries in SELECT clause

**Use Cases**:
```sql
-- Union
SELECT name FROM products WHERE category = 'Electronics'
UNION
SELECT name FROM products WHERE category = 'Furniture'

-- Subquery in FROM
SELECT * FROM (
  SELECT category, AVG(price) as avg_price 
  FROM products 
  GROUP BY category
) AS category_avg
```

**Implementation Complexity**: Medium-High
**Business Value**: Medium

### 2.6 Pagination (LOW PRIORITY)

**Current Status**: Partially Implemented

**Missing Features**:
- `OFFSET` clause: `LIMIT 10 OFFSET 20`

**Use Cases**:
```sql
-- Pagination
SELECT * FROM products ORDER BY price LIMIT 10 OFFSET 20
```

**Implementation Complexity**: Low
**Business Value**: Medium

---

## Part 3: Enhanced Features & Quality of Life

### 3.1 Parameterized Queries (IMPLEMENTED)

**Current Status**: Implemented

Saved (and inline) queries can have placeholders that are filled at runtime.

**Syntax**:
```bash
# Save query with parameters
jsonsql --save-query filtered_products \
  --query "SELECT * FROM products WHERE price > ${min_price} AND category = '${category}'"

# Run with parameters
jsonsql --run-query filtered_products \
  --param min_price=100 \
  --param category=Electronics
```

**Behavior**:
1. `${variable}` placeholders are parsed in saved/inline queries
2. `--param key=value` supplies values (repeatable)
3. Placeholders are substituted before query execution
4. Default values are supported: `${min_price:0}`; a placeholder without a default or a supplied value raises an error
5. Values are spliced in literally (no SQL escaping) — treat parameter input as trusted

Backed by `QueryParameterReplacer` and covered by `QueryParameterReplacerTest` and `ParameterizedQueryIntegrationTest`.

### 3.2 Output Format Options

**Current Status**: JSON (default) and CSV (`--format csv`) implemented

**CSV behavior** (shipped):
- Header row from projected field names
- RFC 4180 escaping for commas, quotes, and newlines
- `NULL` → empty cell; nested values → compact JSON in the cell
- Works with `--output` and `--clipboard` like JSON

```bash
jsonsql --query "SELECT name, price FROM products" --format csv --output products.csv
```

**Still proposed**:
- TSV output: `--format tsv`
- ASCII table: `--format table`
- XML output: `--format xml`

### 3.3 Developer Tools (MEDIUM PRIORITY)

**Proposed Features**:

#### Query Validation / Dry-Run (IMPLEMENTED)

```bash
jsonsql --query "SELECT * FROM products" --data-dir example-data --dry-run
```

Validates SQL syntax, table mappings, and backing file existence without loading data or returning query results. Supports CTEs, JOINs, `--run-query`, and parameterized queries.

Backed by `QueryExecutor.dryRunValidate`, `DryRunReporter`, and `DryRunTest` / `JsonSqlCliTest`.

#### Query Explain / Profile
```bash
jsonsql --query "SELECT * FROM products" --explain
# Shows execution plan, row counts, performance metrics
```

#### Schema Introspection (IMPLEMENTED)

```bash
jsonsql --describe products --data-dir example-data
```

Shows mapping, row count, and a table of field paths (dot notation for nested objects), inferred JSON types, and sample values. Schema is sampled from up to 200 rows; nullable fields are marked.

Backed by `TableDescriber` and covered by `TableDescriberTest` / `JsonSqlCliTest`.

### 3.4 Array Operations (LOW PRIORITY)

**Current Status**: UNNEST only

**Proposed Features**:
- Array length: `SELECT JSON_LENGTH(tags) AS tag_count`
- Array contains: `WHERE JSON_CONTAINS(tags, 'electronics')`
- Array indexing: `SELECT tags[0] AS first_tag`

**Implementation Complexity**: Low
**Business Value**: Low-Medium

### 3.5 Performance Optimizations (MEDIUM PRIORITY)

**Current state**: Optional disk caching of parsed JSON is implemented (`--enable-cache`, auto-invalidated on source change). **Declared indexes are implemented** (`--add-index`): per-file value summaries enable file-level pruning of multi-file tables (`=`, `IN`, range predicates, and `UNNEST`ed arrays), with a safe full-scan fallback. Queries otherwise load matched files fully into memory.

**Proposed Features**:
- Early termination optimization (currently `TOP`/`LIMIT` is applied last and does not short-circuit loading)
- Streaming mode for very large files
- ✅ File-level pruning via declared indexes — **IMPLEMENTED** (see `INDEXING.md`); row-level / NDJSON byte-offset indexing for single large documents remains future work

**Implementation Complexity**: High
**Business Value**: High (for large datasets)

---

## Part 4: Prioritized Implementation Roadmap

### Phase 1: Core SQL Features (High Impact)
1. **Aggregation & GROUP BY** ⭐⭐⭐
   - Essential for analytics
   - High user demand
   - Medium-high complexity

2. **Parameterized Queries** ✅ DONE
   - Implemented via `${var}`/`${var:default}` + `--param`

3. **Calculated Fields** ⭐⭐
   - Very common in real queries
   - Medium complexity
   - High value

### Phase 2: Enhanced Functionality (Medium Impact)
4. **String & Numeric Functions** ⭐⭐
   - Complements calculated fields
   - Medium complexity
   - High value

5. **BETWEEN Operator** ⭐
   - Easy to implement
   - Common SQL pattern
   - Low complexity

6. **Output Formats (TSV/Table)** ⭐⭐
   - CSV shipped; TSV/table/XML remain
   - Low-medium complexity
   - Medium value

### Phase 3: Advanced Features (Lower Priority)
7. **RIGHT JOIN & FULL OUTER JOIN** ⭐
   - Less commonly used
   - Low-medium complexity
   - Medium value

8. **UNION / UNION ALL** ⭐
   - Useful but less common
   - Medium complexity
   - Medium value

9. **Subqueries** ⭐
   - Complex to implement
   - Medium-high complexity
   - Medium value

10. **OFFSET for Pagination** ⭐
    - Easy to implement
    - Low complexity
    - Medium value

### Phase 4: Quality of Life
11. **Developer Tools** (--explain; `--describe` and `--dry-run` shipped) ⭐
    - Helpful for debugging
    - Medium complexity
    - Low-medium value

12. **Date/Time Functions** ⭐
    - Useful for time-series data
    - Medium complexity
    - Medium value

---

## Part 5: Implementation Recommendations

### 5.1 Quick Wins (Low Effort, High Value)
1. **OFFSET support** - Simple addition to LIMIT
2. **BETWEEN operator** - Easy WHERE clause enhancement

(Parameterized queries — previously listed here — are now implemented.)

### 5.2 Medium-Term Goals
1. **Aggregation functions** - Core SQL feature
2. **String/Numeric functions** - Common use cases
3. **TSV/Table output** - Better usability (CSV already shipped)

### 5.3 Long-Term Goals
1. **Subqueries** - Complex but powerful
2. **Performance optimizations** - For large datasets

---

## Part 6: Technical Considerations

### 6.1 Architecture Notes
- Current architecture is well-suited for adding new features
- FieldAccessor pattern allows flexible field resolution
- QueryParser uses JSqlParser (supports many SQL features)
- QueryExecutor has clear separation of concerns
- QueryExecutionContext pattern enables recursive execution (used for CTEs)
- DISTINCT implementation uses canonical JSON string representation

### 6.2 Testing Strategy
- Maintain high test coverage (currently 570 tests, all passing)
- Add integration tests for new features
- Test edge cases (nulls, empty arrays, etc.)
- Test files organized by feature:
  - `DistinctTest.java` - DISTINCT functionality
  - `CteTest.java` - CTE functionality
  - `IlikeOperatorTest.java` - ILIKE functionality
  - `UnnestJoinTest.java` - UNNEST + JOIN combinations
  - `IndexManagerTest.java` / `IndexBuilderTest.java` / `IndexPlannerTest.java` / `IndexPathResolutionTest.java` - declared indexes & file pruning
  - `QueryExecutorIndexPruningTest.java` - indexed vs. full-scan result parity
  - Plus many more comprehensive test suites

### 6.3 Backward Compatibility
- All new features should be additive
- No breaking changes to existing syntax
- Maintain support for all current features

---

## Part 7: Summary

### Current Strengths
✅ Robust WHERE clause with complex logic (AND, OR, NOT, parentheses)
✅ Excellent JOIN support (INNER, LEFT)
✅ UNNEST functionality (well-tested)
✅ DISTINCT keyword (fully implemented)
✅ ILIKE for case-insensitive pattern matching
✅ Common Table Expressions (CTEs) with WITH syntax
✅ Parameterized queries (`${var}` / `${var:default}` with `--param`)
✅ CSV output (`--format csv`) with header row
✅ Schema introspection (`--describe <table>`)
✅ Dry-run validation (`--dry-run`)
✅ Declared indexes / file-level pruning (`--add-index`, `--list-indexes`, `--rebuild-indexes`, `--no-index`)
✅ Flexible JSONPath mappings
✅ Saved queries (39 pre-configured examples)
✅ Good test coverage (all passing)
✅ Clean, maintainable architecture

### Key Gaps
❌ No aggregation (GROUP BY, COUNT, SUM, etc.)
❌ No calculated fields or functions
❌ Limited output formats (JSON and CSV only; no TSV/table/XML)
❌ No BETWEEN operator
❌ No subqueries
❌ No RIGHT JOIN or FULL OUTER JOIN
❌ No UNION / UNION ALL
❌ No OFFSET for pagination

### Recommended Next Steps
1. **Short-term**: Aggregation & GROUP BY (high value)
2. **Medium-term**: Calculated fields & functions
3. **Long-term**: Advanced features (subqueries, UNION)

---

## Conclusion

JsonSQL is a solid foundation with excellent core functionality. Recent additions of DISTINCT, ILIKE, and CTEs demonstrate the system's extensibility. The suggested enhancements would transform it from a good tool into a comprehensive SQL-like query engine for JSON data. The prioritized roadmap focuses on high-impact features that provide the most value to users.

**Current Test Status**: ✅ 570 tests passing
**Code Quality**: ✅ High - Clean architecture, good separation of concerns
**Documentation**: ✅ Comprehensive - README, examples, saved queries reference
