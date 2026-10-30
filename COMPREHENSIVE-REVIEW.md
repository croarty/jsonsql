# JsonSQL Comprehensive Functionality Review & Enhancement Suggestions

## Executive Summary

JsonSQL is a well-architected SQL-like query engine for JSON data. This document provides a thorough review of existing functionality and prioritized suggestions for new features.

**Current Status:**
- **Test Coverage**: 2507 tests across 70 test classes, all passing ✅
- **Core Features**: Fully functional SQL-like query engine
- **Recent Additions**: DISTINCT, ILIKE, CTEs, `UNION` / `UNION ALL`, CSV output, schema introspection (`--describe`), dry-run validation (`--dry-run`), declared indexes / file-level pruning (`--add-index`), materialized CTE views (`--materialize-view`)
- **Architecture**: Clean separation of concerns, well-structured, maintainable
- **Error handling**: unimplemented SQL (functions in SELECT, `RIGHT`/`FULL`/`CROSS`/`NATURAL` joins, `INTERSECT`/`EXCEPT`) is rejected at parse time, as are arithmetic and functions in WHERE and ORDER BY — see [Part 2.0](#20-unsupported-syntax-handling-high-priority)

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
  - No calculated fields (e.g., `SELECT price * 1.1 AS total`) — rejected with an error
  - No functions in SELECT (e.g., `SELECT UPPER(name)`) — rejected with an error
  - No `t.*` projection — rejected with an error (use `SELECT *`)

#### FROM Clause
- **Status**: Fully Implemented
- **Features**:
  - Table names with JSONPath mappings
  - Table aliases (e.g., `FROM orders o`)
  - Multiple tables from single file
  - Directory-based multi-file loading
  - Relative and absolute path support
  - CTE references (✅ **IMPLEMENTED** - via WITH clause)
  - Materialized view references (✅ **IMPLEMENTED**)
- **Strengths**: Excellent flexibility for different JSON structures
- **Limitations**:
  - No derived tables (`FROM (SELECT ...) t`)
  - Table/view names are case-sensitive (CTE names are not)

#### WHERE Clause
- **Status**: Fully Implemented with Advanced Features
- **Comparison Operators**: `=`, `!=` / `<>`, `>`, `<`, `>=`, `<=`
- **Logical Operators**: `AND`, `OR`, `NOT`, using SQL three-valued logic for NULL and missing values
- **Pattern Matching**: 
  - `LIKE`, `NOT LIKE` with `%` and `_` wildcards
  - `ILIKE`, `NOT ILIKE` (✅ **IMPLEMENTED** - case-insensitive LIKE)
- **Null Handling**: `IS NULL`, `IS NOT NULL`
- **List Matching**: `IN`, `NOT IN`
- **Grouping**: Parentheses `()` for complex conditions
- **Nested Field Access**: `WHERE profile.level = 'Gold'`
- **Limitations**:
  - No subqueries in WHERE (raises a clear error)
  - Arithmetic and functions in WHERE are rejected

#### JOIN Operations
- **Status**: Partially Implemented
- **Features**:
  - `INNER JOIN` (via `JOIN`)
  - `LEFT JOIN` (via `LEFT JOIN`)
  - Multiple JOINs in single query
  - JOIN with WHERE clauses
  - JOIN with UNNEST (execution order fixed)
  - JOIN with CTEs (✅ **IMPLEMENTED**)
  - Self-joins with distinct aliases (works; verified manually, no dedicated test yet)
- **Limitations**:
  - No `RIGHT JOIN` / `FULL OUTER JOIN` / `NATURAL JOIN` — rejected with an error
  - No `CROSS JOIN` or comma joins between tables (rejected; both forms work only with `UNNEST`)
  - Only a single equi-join condition in `ON`

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
- **Limitations**:
  - Expressions in ORDER BY are rejected; a position needs an explicit column list
  - Explicit JSON `null` sorts as the string `"null"`; only missing fields sort first

#### TOP / LIMIT
- **Status**: Fully Implemented
- **Features**:
  - `SELECT TOP n`
  - `LIMIT n`
  - Works with ORDER BY
  - Works with JOINs
  - Works with DISTINCT
- **Limitations**:
  - `FETCH` is rejected with an error; use `LIMIT` and `OFFSET`

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
  - CTEs can reference CTEs defined **earlier** in the same `WITH` list (not later ones)
  - CTEs can be used in FROM clause
  - CTEs can be used in JOINs
  - CTEs with WHERE, ORDER BY, DISTINCT, LIMIT
  - CTE names are case-insensitive
- **Implementation**: 
  - Recursive query execution via `QueryExecutionContext`
  - CTE results stored in execution context
  - Optional on-disk CTE result cache (`--enable-cache`)
- **Limitations**:
  - No nested `WITH` inside a CTE body, no `UNION` inside a CTE, no `WITH RECURSIVE`
- **Caching**: with `--enable-cache`, a CTE's cache key includes the fingerprint of the mapped files or materialized view it reads, or the cache key of the upstream CTE it reads, so changes propagate through CTE chains
- **Test Coverage**: `CteTest.java` (10 test cases), plus `CTECacheTest`, `CteCacheKeyTest`, and `CteNamesTest`

#### UNION / UNION ALL
- **Status**: ✅ **IMPLEMENTED**
- **Features**:
  - `UNION` (de-duplicated) and `UNION ALL`
  - Column-count validation across branches
  - Trailing `ORDER BY` and `LIMIT` over the combined result
  - Per-branch `TOP` / parenthesized `LIMIT`
  - Mixed `UNION` / `UNION ALL` evaluated left to right, as in standard SQL
  - CTEs shared across branches
- **Limitations**:
  - `INTERSECT` / `EXCEPT` / `MINUS` are rejected
- **Test Coverage**: `UnionTest.java`

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
- Comprehensive test coverage (2507 tests, all passing)
- Good error handling
- Flexible field accessor pattern
- Efficient JSONPath integration
- Recursive query execution for CTEs
- Execution context pattern for managing CTE results

#### Code Organization
- `JsonSqlCli.java` - Picocli entry point and command dispatch
- `DebugMode.java` - Shared `DEBUG` environment-variable check (CLI and query engine)
- `query/` - Query parsing and execution
  - `QueryParser.java` - SQL parsing (JSqlParser integration), including `UNION` and `WITH`
  - `QueryExecutor.java` - Query execution engine (also dry-run validation)
  - `WhereEvaluator.java` - WHERE clause evaluation
  - `FieldAccessor.java` - Field access interface
  - `QueryExecutionContext.java` - CTE execution context
  - `TableFileResolver.java` - Resolves a mapping to its backing file(s), shared with indexing
  - `DryRunReporter.java` / `DryRunResult.java` - `--dry-run` output
  - `CteNames.java` - Case-insensitive CTE name lookup
  - Supporting classes: `ParsedQuery`, `UnionQuery`, `ColumnInfo`, `TableInfo`, `JoinInfo`, `UnnestInfo`, `OrderByInfo`, `QueryParseException`, `UnsupportedExpressionException`
- `config/` - Configuration management
  - `MappingManager.java` - JSONPath mapping management
  - `QueryManager.java` - Saved query management (atomic writes)
  - `QueryParameterReplacer.java` - `${var}` / `${var:default}` substitution
  - `CacheManager.java` - Disk cache for parsed rows and CTE results
- `index/` - Declared indexes: `IndexManager`, `IndexDefinition`, `IndexBuilder`, `IndexStore`, `TableIndex`, `FileSummary`, `IndexPlanner` (file pruning)
- `view/` - Materialized views: `MaterializedViewManager`, `MaterializedViewDefinition`, `MaterializedViewBuilder`, `MaterializedViewStore`, `SourceFingerprint`
- `introspection/` - `TableDescriber` (`--describe`)
- `output/` - Output handling
  - `OutputHandler.java` - Output formatting and delivery (stdout, file, clipboard)
  - `CsvFormatter.java` / `OutputFormat.java` - CSV output
- Clear interfaces (FieldAccessor)

#### Dependencies
- **Java 21**: Modern Java features
- **JSqlParser 4.9**: SQL parsing (pinned to 4.x; 5.x changes the AST API)
- **Jackson 2.22.4**: JSON processing
- **JSONPath 3.0.0**: JSONPath expression evaluation, using Jackson to parse data files (`JsonPathConfig`)
- **Picocli 4.7.7**: Command-line interface
- **JUnit 6.1.3**: Testing framework

#### Build & Security Tooling
- **GitHub Actions CI** (`.github/workflows/ci.yml`): builds and tests every push/PR on JDK 21 and 25
- **Dependabot** (`.github/dependabot.yml`): automated dependency + GitHub Actions updates and security-advisory PRs
- **Dependency Review** (`.github/workflows/dependency-review.yml`): blocks PRs introducing vulnerable dependencies
- **CodeQL** (`.github/workflows/codeql.yml`): static security/quality analysis
- **OWASP Dependency-Check** (`mvn -Psecurity verify`, `.github/workflows/security-scan.yml`): weekly CVE scan of the dependency tree

---

## Part 2: Missing Core SQL Features

### 2.0 Unsupported Syntax Handling (HIGH PRIORITY)

**Current Status**: ✅ Mostly implemented. `QueryParser` rejects SQL that JSqlParser accepts but the engine does not implement, with a `QueryParseException` such as `HAVING needs GROUP BY or an aggregate`. It does this for the main query, CTE bodies, and UNION branches, so `--dry-run` catches it too. Covered by `UnsupportedSyntaxTest`.

| Construct | Behavior |
|---|---|
| `GROUP BY`, `HAVING`, aggregates | ✅ Implemented |
| Functions, arithmetic, literals, `CASE`, `t.*` in SELECT | Rejected |
| `OFFSET` / `LIMIT m, n` | ✅ Implemented |
| `FETCH` | Rejected |
| `RIGHT`, `FULL`, `CROSS` (non-`UNNEST`), `NATURAL` joins | Rejected |
| `INTERSECT`, `EXCEPT`, `MINUS` | Rejected (previously executed as `UNION`) |
| `LIMIT` after a `UNION` | ✅ Implemented — limits the whole result |
| Mixed `UNION` / `UNION ALL` | ✅ Implemented — left-associative, as in standard SQL |
| `ORDER BY alias` / `ORDER BY 2` | ✅ Implemented |
| `20 < price`, `price > stock` in WHERE | ✅ Implemented |
| Arithmetic and functions in WHERE, expressions in ORDER BY | Rejected |
| `LIKE ... ESCAPE` | ✅ Implemented |

**Implementation Complexity**: Low
**Business Value**: Very High (prevents silently wrong answers)

### 2.1 Aggregation & Grouping

**Current Status**: ✅ Implemented: `GROUP BY`, `HAVING`, `COUNT`, `SUM`, `AVG`, `MIN`, `MAX` and `DISTINCT` inside them. Checked against SQLite by `SqliteComparisonTest`.

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
- Subqueries: `WHERE price > (SELECT AVG(price) FROM products)`
- `EXISTS` / `NOT EXISTS`

**Use Cases**:
```sql
-- Subquery
SELECT * FROM products 
WHERE price > (SELECT AVG(price) FROM products)
```

**Implementation Complexity**: Medium-High
**Business Value**: Medium-High

### 2.4 JOIN Enhancements (MEDIUM PRIORITY)

**Current Status**: Partially Implemented

**Missing Features**:
- `RIGHT JOIN` (currently rejected)
- `FULL OUTER JOIN` (currently rejected)
- `CROSS JOIN` (explicit)
- Test coverage for self-joins (same table, different aliases), which already work

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

**Current Status**: Fully Implemented

**Implemented**:
  - ✅ Common Table Expressions (CTEs / WITH clause)
  - ✅ `UNION` / `UNION ALL`
  - ✅ ORDER BY on UNION results

**Notes**: A trailing `LIMIT` limits the whole UNION result. JSqlParser attaches it either to the set operation (when there is a trailing `ORDER BY`) or to the last unparenthesized branch (when there isn't); `QueryParser` normalizes both cases to the union level. Per-branch limits use `SELECT TOP n ...` or `(SELECT ... LIMIT n)`.

**Use Cases**:
```sql
-- Union
SELECT name FROM products WHERE category = 'Electronics'
UNION
SELECT name FROM products WHERE category = 'Furniture'

-- Subquery in FROM is NOT supported (derived tables require additional parsing support)
```

**Implementation Complexity**: Medium-High
**Business Value**: Medium

### 2.6 Pagination

**Current Status**: ✅ Implemented with `LIMIT` and `OFFSET`

---

## Part 3: Enhanced Features & Quality of Life

### 3.1 Parameterized Queries (IMPLEMENTED)

**Current Status**: Implemented

Saved (and inline) queries can have placeholders that are filled at runtime.

**Syntax** (in bash, `\$` stops the shell from expanding the placeholder itself):
```bash
# Save query with parameters
jsonsql --save-query filtered_products \
  --query "SELECT * FROM products WHERE price > \${min_price} AND category = '\${category}'"

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
0. **Reject silently-ignored constructs** ✅ DONE (see 2.0)

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

### Phase 3: Advanced Features (Lower Priority)
7. **RIGHT JOIN & FULL OUTER JOIN** ⭐
   - Less commonly used
   - Low-medium complexity
   - Medium value

8. **UNION / UNION ALL** ✅ DONE (including whole-union `LIMIT` and mixed operators)
   - Remaining: column-name unification, `INTERSECT` / `EXCEPT`

9. **Subqueries** ⭐
   - Complex to implement
   - Medium-high complexity
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

Recently completed: fail-loudly parse checks for unsupported SQL, whole-union `LIMIT`, left-associative mixed `UNION`/`UNION ALL`, CTE cache keys that follow CTE chains and materialized views, `--list-indexes` freshness for view indexes, and a single `DEBUG` check (`DebugMode`) shared by the CLI and query engine.

(Parameterized queries — previously listed here — are now implemented.)

### 5.2 Medium-Term Goals
1. **String/Numeric functions** - Common use cases
2. **TSV/Table output** - Better usability (CSV already shipped)

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
- Maintain high test coverage (currently 2507 tests, all passing)
- Add integration tests for new features
- Test edge cases (nulls, empty arrays, etc.)
- Test files organized by feature:
  - `DistinctTest.java` - DISTINCT functionality
  - `CteTest.java` - CTE functionality
  - `IlikeOperatorTest.java` - ILIKE functionality
  - `UnnestJoinTest.java` - UNNEST + JOIN combinations
  - `UnionTest.java` - UNION / UNION ALL
  - `UnsupportedSyntaxTest.java` - parse-time rejection of unimplemented SQL
  - `DebugModeTest.java` - `DEBUG` environment-variable parsing
  - `IndexManagerTest.java` / `IndexBuilderTest.java` / `IndexPlannerTest.java` / `IndexPathResolutionTest.java` - declared indexes & file pruning
  - `MaterializedViewManagerTest.java` / `MaterializedViewBuilderTest.java` / `SourceFingerprintTest.java` / `QueryExecutorMaterializedViewTest.java` / `JsonSqlCliMaterializedViewTest.java` - materialized views & freshness
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
✅ UNION / UNION ALL
✅ UNNEST functionality (well-tested)
✅ DISTINCT keyword (fully implemented)
✅ ILIKE for case-insensitive pattern matching
✅ Common Table Expressions (CTEs) with WITH syntax
✅ Parameterized queries (`${var}` / `${var:default}` with `--param`)
✅ CSV output (`--format csv`) with header row
✅ Schema introspection (`--describe <table>`)
✅ Dry-run validation (`--dry-run`)
✅ Declared indexes / file-level pruning (`--add-index`, `--list-indexes`, `--rebuild-indexes`, `--no-index`)
✅ Materialized CTE views (`--materialize-view`, `--list-materialized-views`, `--rebuild-view`, indexes on views)
✅ Flexible JSONPath mappings
✅ Saved queries (39 pre-configured examples)
✅ Good test coverage (all passing)
✅ Clean, maintainable architecture

### Key Gaps
❌ No calculated fields or functions
❌ Limited output formats (JSON and CSV only; no TSV/table/XML)
❌ No subqueries or derived tables
❌ No RIGHT JOIN or FULL OUTER JOIN

### Recommended Next Steps
1. **Medium-term**: Calculated fields & functions
2. **Long-term**: Advanced features (subqueries, derived tables)

---

## Conclusion

JsonSQL is a solid foundation with excellent core functionality. Recent additions of DISTINCT, ILIKE, CTEs, UNION, indexes, and materialized views demonstrate the system's extensibility. The suggested enhancements would transform it from a good tool into a comprehensive SQL-like query engine for JSON data. The prioritized roadmap focuses on high-impact features that provide the most value to users.

**Current Test Status**: ✅ 2507 tests passing
**Code Quality**: ✅ High - Clean architecture, good separation of concerns
**Documentation**: ✅ Comprehensive - README, examples, saved queries reference
