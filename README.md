# JsonSQL

A powerful command-line tool that enables SQL-like querying of JSON files without requiring strongly-typed models. Built with Java 21 and designed for performance with large, complex JSON documents.

## Features

- **SQL-like Query Syntax**: Write familiar SQL queries against JSON data
  - `SELECT` / `SELECT DISTINCT` - Project specific fields or all fields with `*`, with `AS` aliases
  - `FROM` - Specify data sources with table aliases
  - `WHERE` - Filter results with complex conditions (AND, OR, NOT, parentheses, LIKE, ILIKE, IN, IS NULL)
  - `JOIN` / `LEFT JOIN` - Combine data from multiple sources
  - `WITH` - Common Table Expressions (CTEs) for composing queries
  - `UNNEST` - Flatten arrays into individual rows
  - `TOP x` / `LIMIT` - Limit result sets
  - `ORDER BY` - Sort results ascending or descending
- **Schema-less Design**: Works with any JSON structure without predefined models
- **JSONPath Mapping**: Define shortcuts for complex JSONPath expressions
- **Saved Queries**: Save and reuse frequently-used queries by name, including parameterized queries
- **Flexible Output**: JSON (default) or CSV with header row; write to stdout, file, or clipboard (clean stdout/stderr separation for piping)
- **Schema Introspection**: `--describe <table>` shows field paths, types, and sample values
- **Dry-Run Validation**: `--dry-run` checks syntax, mappings, and data files without executing
- **Optional Disk Caching**: Persist parsed JSON between runs with `--enable-cache`; auto-invalidated when sources change
- **Declared Indexes**: Build per-file value summaries with `--add-index <table> <field>` so selective queries skip backing files that cannot match (file-level pruning; see [Indexing](INDEXING.md))
- **Recursive Directory Loading**: Map a directory to load and combine all `.json` files within it (including subdirectories)
- **Table Aliases**: Use aliases for cleaner queries (e.g., `FROM orders o`)

## Requirements

- Java 21 or higher
- Maven 3.6+ (for building from source)

## Installation

### Build from Source

```bash
git clone <repository-url>
cd jsonsql
mvn clean package
```

This creates an executable JAR at `target/jsonsql-1.3.0.jar`.

### Running

```bash
java -jar target/jsonsql-1.3.0.jar [options]
```

Or create an alias for convenience:

```bash
alias jsonsql='java -jar /path/to/jsonsql-1.3.0.jar'
```

## Quick Start

### 1. Configure JSONPath Mappings

JsonSQL uses a configuration file (`.jsonsql-mappings.json`) to map table names to JSONPath expressions within your JSON files.

```bash
# Add a mapping for products
jsonsql --add-mapping products "$.data.products"

# Add a mapping for orders
jsonsql --add-mapping orders "$.orders"

# List all configured mappings
jsonsql --list-tables
```

**Example JSON file** (`products.json`):

```json
{
  "data": {
    "products": [
      {"id": 1, "name": "Widget", "price": 19.99, "category": "Tools"},
      {"id": 2, "name": "Gadget", "price": 29.99, "category": "Electronics"}
    ]
  }
}
```

### 2. Run Queries

```bash
# Simple SELECT
jsonsql --query "SELECT * FROM products"

# SELECT specific columns
jsonsql --query "SELECT name, price FROM products"

# WHERE clause
jsonsql --query "SELECT * FROM products WHERE category = 'Tools'"

# TOP/LIMIT
jsonsql --query "SELECT TOP 5 * FROM products"

# JOIN
jsonsql --query "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id"

# LEFT JOIN with WHERE
jsonsql --query "SELECT p.name, o.quantity FROM orders o LEFT JOIN products p ON o.productId = p.id WHERE o.status = 'active'"
```

## Usage

### Command Line Options

```
Usage: jsonsql [-hV] [--clear-cache] [--clipboard] [--enable-cache]
               [--list-queries] [--list-tables] [--pretty]
               [--format=<format>]
               [-c=<configFile>] [-d=<dataDirectory>]
               [--delete-query=<name>] [-o=<outputFile>] [-q=<query>]
               [--queries-file=<queriesFile>] [--run-query=<name>]
               [--save-query=<name>] [--param=<key=value>]...
               [--add-mapping=<alias> <jsonpath>]...

Options:
  -q, --query=<query>        SQL query to execute
  -d, --data-dir=<dataDirectory>
                             Directory containing JSON files (default: .)
  -c, --config=<configFile>  Path to mapping configuration file
                             (default: .jsonsql-mappings.json)
      --queries-file=<queriesFile>
                             Path to saved queries file
                             (default: .jsonsql-queries.json)
  -o, --output=<outputFile>  Output file path (default: stdout)
      --clipboard            Copy output to clipboard
      --pretty               Pretty-print JSON output
      --format=<format>      Output format: json (default) or csv
      --list-tables          Show all configured JSONPath shortcuts
      --describe=<table>     Show field names, types, and sample values for a table
      --dry-run              Validate query syntax and mappings without executing
      --add-mapping=<alias> <jsonpath>
                             Add a new JSONPath mapping
      --save-query=<name>    Save a query with a name (requires --query)
      --run-query=<name>     Execute a saved query by name
      --list-queries         Show all saved queries
      --delete-query=<name>  Delete a saved query
      --param=<key=value>    Parameter value for parameterized queries
                             (can be used multiple times)
      --enable-cache         Enable disk-based caching of parsed JSON data for
                             faster subsequent queries
      --clear-cache          Clear all cached data for mapped tables
      --indexes-file=<file>  Path to declared-index definitions
                             (default: .jsonsql-indexes.json)
      --add-index <table> <field>
                             Declare and build an index on a table field
      --drop-index <table> <field>
                             Remove a declared index
      --list-indexes         List declared indexes with fresh/stale status
      --rebuild-index <table> <field>
                             Rebuild one declared index
      --rebuild-indexes      Rebuild all declared indexes
      --no-index             Bypass declared indexes for this query (full scan)
  -h, --help                 Show this help message and exit
  -V, --version              Print version information and exit
```

Only one primary action may be specified per invocation (for example, you cannot
combine `--query` with `--list-tables`, or `--run-query` with `--query`); doing so
exits with an error. The tool returns exit code `0` on success and `1` on error.
Result data is written to stdout; informational and error messages are written to
stderr (set `DEBUG` to a truthy value for full stack traces).

### Configuration Management

#### Add a Mapping

```bash
jsonsql --add-mapping products "$.data.products"
jsonsql --add-mapping orders "$.orders"
jsonsql --add-mapping customers "$.users.customers"
```

#### List All Mappings

```bash
jsonsql --list-tables
```

Output:

```
Configured JSONPath Mappings:
────────────────────────────────────────────────────────────────────────────────
  customers  ->  $.users.customers
  orders     ->  $.orders
  products   ->  $.data.products
────────────────────────────────────────────────────────────────────────────────
Total: 3 mapping(s)
```

#### Describe a Table

```bash
jsonsql --describe products --data-dir example-data
```

Shows the mapping, row count, and a column listing of every queryable field path (nested objects use dot notation), inferred JSON type, and a sample value from the data:

```
Table: products
Mapping: complex-products.json:$.products[*]
Rows: 6

  Field          Type              Sample
  id             number            1
  name           string            Wireless Mouses
  tags           array             ["wireless","mouse","ergonomic","electronics"]
  specifications.connectivity  string  Bluetooth 5.0
```

Use this before writing queries to discover field names and nesting. Schema is inferred from up to 200 rows; fields only seen as `null` are typed `null`, and fields with mixed null/non-null values are marked `(nullable)`.

#### Dry-Run a Query

```bash
jsonsql --query "SELECT p.name, o.orderId FROM orders o JOIN products p ON o.productId = p.id" --data-dir example-data --dry-run
```

Validates SQL syntax, confirms every referenced table has a mapping (or is a CTE), and checks that backing JSON files exist — without loading data or returning results:

```
Dry run OK.
  SQL parsed successfully.
  Tables resolved: orders, products
    orders -> complex-orders.json:$.orders[*]
    products -> complex-products.json:$.products[*]
```

Works with `--run-query` and parameterized queries (`--param` is applied before validation). `--dry-run` ignores `--output`, `--clipboard`, and `--format`.

### Saved Queries

JsonSQL allows you to save frequently-used queries for easy reuse.

#### Save a Query

```bash
# Save a simple query
jsonsql --save-query all_electronics --query "SELECT * FROM products WHERE category = 'Electronics'"

# Save a complex query
jsonsql --save-query top_orders --query "SELECT o.orderId, p.name, p.price FROM orders o JOIN products p ON o.productId = p.id WHERE o.status IN ('completed', 'shipped') ORDER BY p.price DESC LIMIT 10"
```

#### List Saved Queries

```bash
jsonsql --list-queries
```

Output:

```
Saved Queries:
────────────────────────────────────────────────────────────────────────────────
  all_electronics  -> SELECT * FROM products WHERE category = 'Electronics'
  top_orders       -> SELECT o.orderId, p.name, p.price FROM orders o...
────────────────────────────────────────────────────────────────────────────────
Total: 2 saved queries
```

#### Run a Saved Query

```bash
# Run a saved query
jsonsql --run-query all_electronics --data-dir example-data --pretty

# Run saved query with output to file
jsonsql --run-query top_orders --data-dir example-data --output results.json --pretty

# Run saved query to clipboard
jsonsql --run-query all_electronics --data-dir example-data --clipboard
```

#### Parameterized Queries

JsonSQL supports parameterized queries, allowing you to save queries with placeholders that can be filled at runtime.

**Syntax:**

- Placeholders: `${variable}` or `${variable:default_value}`
- Provide values: `--param key=value` (can be used multiple times)

**Examples:**

```bash
# Save a parameterized query
jsonsql --save-query filtered_products \
  --query "SELECT * FROM products WHERE price > ${min_price} AND category = '${category}'"

# Run with parameters
jsonsql --run-query filtered_products \
  --data-dir example-data \
  --param min_price=100 \
  --param category=Electronics

# Using default values
jsonsql --save-query expensive_items \
  --query "SELECT * FROM products WHERE price > ${min_price:100} ORDER BY price DESC"

# Run with default (uses 100)
jsonsql --run-query expensive_items --data-dir example-data

# Override default
jsonsql --run-query expensive_items \
  --data-dir example-data \
  --param min_price=500

# Multiple parameters
jsonsql --save-query search_products \
  --query "SELECT * FROM products WHERE name LIKE '${pattern:%laptop%}' AND price < ${max_price:1000}"

jsonsql --run-query search_products \
  --data-dir example-data \
  --param pattern=%desk% \
  --param max_price=500

# Parameters in direct queries
jsonsql --query "SELECT * FROM products WHERE price > ${min_price}" \
  --data-dir example-data \
  --param min_price=200
```

**Parameter Rules:**

- Required parameters (no default): Must be provided with `--param` or an error is thrown
- Optional parameters (with default): Use default value if not provided
- Parameter names: Can contain letters, numbers, and underscores
- Parameter values: Inserted literally into the SQL text before parsing (no SQL escaping is performed). Treat saved queries and parameter values as trusted input — untrusted values can alter the query (SQL-injection style).

#### Delete a Saved Query

```bash
jsonsql --delete-query old_query
```

**Saved Query Storage:**

- Queries are stored in `.jsonsql-queries.json` (configurable with `--queries-file`)
- Stored in JSON format with query names as keys
- Persists across sessions
- Can be version controlled with your project

**Pre-configured Queries:**

This repository ships 39 pre-configured example queries demonstrating all features (36 feature-numbered `01_`–`36_` queries, the named `completed_orders` and `expensive_electronics` examples, and the parameterized `filtered_products`):

- See [SAVED-QUERIES-REFERENCE.md](SAVED-QUERIES-REFERENCE.md) for complete documentation
- The numbered queries (`01_`–`36_`) each showcase a specific feature
- Covers: SELECT, DISTINCT, WHERE (all operators), JOIN, LEFT JOIN, LIKE, ILIKE, IN, IS NULL, UNNEST, WITH/CTE, multi-file tables, ORDER BY, TOP/LIMIT, and parameterized queries
- Run any query: `jsonsql --run-query <name> --data-dir example-data --pretty`

### Query Examples

#### Simple Queries

```bash
# Select all fields
jsonsql --query "SELECT * FROM products"

# Select specific fields
jsonsql --query "SELECT name, price, category FROM products"

# Filter with WHERE
jsonsql --query "SELECT * FROM products WHERE price > 20"

# String comparison
jsonsql --query "SELECT * FROM products WHERE category = 'Electronics'"

# Limit results
jsonsql --query "SELECT TOP 10 * FROM products"

# DISTINCT - unique values
jsonsql --query "SELECT DISTINCT category FROM products"

# DISTINCT with multiple columns
jsonsql --query "SELECT DISTINCT name, category FROM products"

# DISTINCT with WHERE and ORDER BY
jsonsql --query "SELECT DISTINCT category FROM products WHERE price > 50 ORDER BY category"
```

#### Complex WHERE Clauses

JsonSQL now supports complex WHERE clauses with `AND`, `OR`, `NOT`, and parentheses for grouping:

```bash
# AND conditions
jsonsql --query "SELECT * FROM products WHERE category = 'Electronics' AND price > 50"

# OR conditions
jsonsql --query "SELECT * FROM products WHERE category = 'Furniture' OR price < 30"

# NOT condition
jsonsql --query "SELECT * FROM products WHERE NOT inStock = false"

# Parentheses for grouping
jsonsql --query "SELECT * FROM products WHERE (category = 'Electronics' AND price > 100) OR category = 'Furniture'"

# Complex nested conditions
jsonsql --query "SELECT * FROM products WHERE (category = 'Electronics' AND (price < 100 OR price > 1000)) OR (category = 'Furniture' AND inStock = true)"

# Multiple ANDs
jsonsql --query "SELECT * FROM products WHERE category = 'Electronics' AND price > 50 AND inStock = true"

# Combining AND with OR
jsonsql --query "SELECT * FROM products WHERE category = 'Electronics' AND (price < 50 OR price > 1000)"

# NOT with complex conditions
jsonsql --query "SELECT * FROM products WHERE NOT (category = 'Electronics' AND price > 500)"

# Deep nesting
jsonsql --query "SELECT * FROM products WHERE ((category = 'Electronics' AND price > 100) OR (category = 'Furniture' AND price < 200)) AND inStock = true"
```

**Supported Operators:**

- **Logical:** `AND`, `OR`, `NOT`
- **Comparison:** `=`, `!=`, `>`, `<`, `>=`, `<=`
- **List Matching:** `IN`, `NOT IN` - check if value matches any in a list
- **Pattern Matching:** `LIKE`, `NOT LIKE` with `%` (any characters) and `_` (single character) wildcards
- **Case-Insensitive Pattern Matching:** `ILIKE`, `NOT ILIKE` - same as LIKE but case-insensitive
- **Null Checking:** `IS NULL`, `IS NOT NULL` (treats both missing and explicitly null fields as NULL)
- **Grouping:** Parentheses `()`

**Boolean Logic:**

- Short-circuit evaluation for `AND` and `OR`
- Full support for nested conditions with any level of complexity
- Operator precedence follows standard SQL rules

**Pattern Matching with LIKE:**

```bash
# Starts with pattern
jsonsql --query "SELECT * FROM products WHERE name LIKE 'Laptop%'"

# Ends with pattern
jsonsql --query "SELECT * FROM products WHERE name LIKE '%Cable'"

# Contains pattern
jsonsql --query "SELECT * FROM products WHERE name LIKE '%Desk%'"

# Single character wildcard (_)
jsonsql --query "SELECT * FROM products WHERE category LIKE 'F__niture'"

# NOT LIKE
jsonsql --query "SELECT * FROM products WHERE name NOT LIKE '%Monitor%'"

# LIKE with complex conditions
jsonsql --query "SELECT * FROM products WHERE name LIKE '%e%' AND category = 'Electronics' AND price > 100"
```

**Case-Insensitive Pattern Matching with ILIKE:**

`ILIKE` works exactly like `LIKE` but performs case-insensitive matching:

```bash
# Case-insensitive starts with
jsonsql --query "SELECT * FROM products WHERE name ILIKE 'laptop%'"

# Case-insensitive contains
jsonsql --query "SELECT * FROM products WHERE name ILIKE '%desk%'"

# Case-insensitive exact match
jsonsql --query "SELECT * FROM products WHERE category ILIKE 'electronics'"

# NOT ILIKE
jsonsql --query "SELECT * FROM products WHERE name NOT ILIKE '%laptop%'"

# ILIKE with wildcards
jsonsql --query "SELECT * FROM products WHERE name ILIKE 'w%'"

# ILIKE with complex conditions
jsonsql --query "SELECT * FROM products WHERE name ILIKE '%e%' AND category ILIKE 'electronics'"
```

**Difference between LIKE and ILIKE:**

- `LIKE` is case-sensitive: `'laptop%'` only matches strings starting with lowercase "laptop"
- `ILIKE` is case-insensitive: `'laptop%'` matches "Laptop", "laptop", "LAPTOP", etc.

**Null Value Handling with IS NULL / IS NOT NULL:**

Both missing fields and explicitly null fields are treated as NULL:

```bash
# Find products with no description (missing or null)
jsonsql --query "SELECT * FROM products WHERE description IS NULL"

# Find products that have a category
jsonsql --query "SELECT * FROM products WHERE category IS NOT NULL"

# Combine with other operators
jsonsql --query "SELECT * FROM products WHERE description IS NOT NULL AND price > 100"

# Multiple null checks
jsonsql --query "SELECT * FROM products WHERE category IS NULL OR description IS NULL"

# Find complete records (no null fields)
jsonsql --query "SELECT * FROM products WHERE name IS NOT NULL AND category IS NOT NULL AND price IS NOT NULL"
```

**List Matching with IN / NOT IN:**

Check if a value matches any value in a list:

```bash
# Multiple categories
jsonsql --query "SELECT * FROM products WHERE category IN ('Electronics', 'Furniture', 'Tools')"

# Numeric IN list
jsonsql --query "SELECT * FROM products WHERE id IN (1, 5, 10, 15)"

# NOT IN to exclude values
jsonsql --query "SELECT * FROM products WHERE category NOT IN ('Electronics')"

# IN with other conditions
jsonsql --query "SELECT * FROM products WHERE category IN ('Electronics', 'Appliances') AND price > 100"

# IN with LIKE
jsonsql --query "SELECT * FROM products WHERE category IN ('Electronics') AND name LIKE '%o%'"

# Combining IN with IS NOT NULL
jsonsql --query "SELECT * FROM products WHERE category IN ('Electronics', 'Tools') AND description IS NOT NULL"
```

**Note on NULL behavior with IN:**

- `NULL IN (...)` returns FALSE (excludes row)
- `NULL NOT IN (...)` returns FALSE (excludes row)
- Use `OR category IS NULL` if you want to include null values

#### Queries with JOINs

```bash
# Inner JOIN
jsonsql --query "SELECT p.name, o.quantity, o.orderDate FROM orders o JOIN products p ON o.productId = p.id"

# LEFT JOIN
jsonsql --query "SELECT o.orderId, p.name FROM orders o LEFT JOIN products p ON o.productId = p.id"

# JOIN with WHERE
jsonsql --query "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE o.status = 'active'"

# Multiple JOINs
jsonsql --query "SELECT o.orderId, p.name, c.name FROM orders o JOIN products p ON o.productId = p.id JOIN customers c ON o.customerId = c.id"

# JOIN with TOP
jsonsql --query "SELECT TOP 5 p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id"

# UNNEST - Flatten arrays
jsonsql --query "SELECT name, tag FROM products, UNNEST(tags) AS t(tag)"

# UNNEST with complex objects
jsonsql --query "SELECT name, review.user, review.rating FROM products, UNNEST(reviews) AS r(review)"

# UNNEST with WHERE filtering
jsonsql --query "SELECT name, tag FROM products, UNNEST(tags) AS t(tag) WHERE tag = 'android'"
```

### Output Options

#### Write to File

```bash
jsonsql --query "SELECT * FROM products" --output results.json
```

#### Copy to Clipboard

```bash
jsonsql --query "SELECT * FROM products" --clipboard
```

#### Pretty Print

```bash
jsonsql --query "SELECT * FROM products" --pretty
```

Output:

```json
[
  {
    "id": 1,
    "name": "Widget",
    "price": 19.99,
    "category": "Tools"
  },
  {
    "id": 2,
    "name": "Gadget",
    "price": 29.99,
    "category": "Electronics"
  }
]
```

#### CSV Output

```bash
jsonsql --query "SELECT name, price FROM products" --format csv
```

Output (header row plus one row per result):

```csv
name,price
Widget,19.99
Gadget,29.99
```

`--format csv` writes RFC 4180–style CSV with a header row of field names. `NULL` values appear as empty cells; nested objects and arrays are serialized as compact JSON in the cell. `--pretty` applies only to JSON output.

```bash
# Spreadsheet-friendly export
jsonsql --query "SELECT name, price, category FROM products" --format csv --output products.csv
```

#### Combine Options

```bash
jsonsql --query "SELECT * FROM products" --output results.json --pretty
```

## SQL Syntax Support

> **Query phase order:** clauses are applied in the order **WHERE → ORDER BY → projection (SELECT list) → DISTINCT → LIMIT/TOP**. This means sorting happens against the full source rows (you can `ORDER BY` a column you do not select), `DISTINCT` is applied to the projected columns, and `LIMIT`/`TOP` is applied last (after de-duplication).

### SELECT Clause

- `SELECT `* - All fields
- `SELECT field1, field2` - Specific fields
- `SELECT table.field` - Qualified field names
- `SELECT DISTINCT field` - Unique values only
- `SELECT DISTINCT field1, field2` - Unique combinations

**Projection and missing values:** When you list explicit columns, every selected column is always present in each output row. If a row has no value for a selected field (the field is absent or JSON `null`), it is emitted as JSON `null` rather than omitted, so all rows share the same keys. `SELECT `* behaves differently — it returns each row's fields as-is and does **not** add keys for absent fields.

### FROM Clause

- `FROM table` - Table name (mapped to JSONPath)
- `FROM table alias` - Table with alias

### WHERE Clause

Supported operators:

- `=` - Equality
- `!=` - Inequality
- `>` - Greater than
- `<` - Less than
- `>=` - Greater than or equal
- `<=` - Less than or equal
- `LIKE` / `NOT LIKE`, `ILIKE` - Pattern matching (`%` and `_` wildcards; `ILIKE` is case-insensitive)
- `IN (...)` / `NOT IN (...)` - Value lists
- `IS NULL` / `IS NOT NULL` - Null checks
- `AND` / `OR` / `NOT` - Boolean combinations

Examples:

```sql
WHERE price > 20
WHERE category = 'Tools'
WHERE quantity >= 5
WHERE status != 'cancelled'
```

**Comparison behavior:**

- For `>`, `<`, `>=`, `<=`, the stored field's JSON type decides the comparison: if the field is a JSON number it is compared numerically against the literal; if the field is text it is compared lexicographically (alphabetically), e.g. `WHERE name >= 'M'`.
- A number stored as a JSON string (e.g. `"10"`) is treated as text and therefore compares lexicographically, not numerically.
- SQL `NULL` values never satisfy a comparison.

**Unsupported expressions fail loudly:** Constructs that are not yet implemented — such as `BETWEEN`, subqueries (e.g. `IN (SELECT ...)`), or other unsupported WHERE syntax — now raise a clear error instead of silently returning zero rows. This prevents misleading empty results.

### JOIN Clause

- `JOIN table ON condition` - Inner join
- `LEFT JOIN table ON condition` - Left outer join

Examples:

```sql
JOIN products p ON o.productId = p.id
LEFT JOIN customers c ON o.customerId = c.id
```

**JOIN constraints:**

- Only a single **equi-join** is supported: the `ON` condition must be one `left = right` equality. Compound conditions (`... AND ...`) and range operators (`>`, `>=`, `<`, `<=`, etc.) in `ON` raise a clear error.
- Join keys use light type coercion: a numeric value matches its string representation (e.g. `5` matches `"5"`), so mismatched JSON types still join as expected.
- SQL `NULL` join keys never match anything (including other nulls).

### DISTINCT Clause

- `SELECT DISTINCT column` - Return unique values for a column
- `SELECT DISTINCT column1, column2` - Return unique combinations of columns
- `SELECT DISTINCT `* - Return unique rows (all columns must match)

Examples:

```sql
SELECT DISTINCT category FROM products
SELECT DISTINCT name, category FROM products
SELECT DISTINCT * FROM products
SELECT DISTINCT category FROM products WHERE price > 100
SELECT DISTINCT category FROM products ORDER BY category
```

**Note:** DISTINCT removes duplicate rows based on all selected columns. Rows are considered duplicates if all their field values are identical. Because DISTINCT runs **before** `LIMIT`/`TOP`, a query like `SELECT DISTINCT category FROM products LIMIT 5` returns up to 5 *distinct* categories (de-duplication happens first, then the limit is applied).

### ORDER BY Clause

- `ORDER BY column` - Sort ascending (default)
- `ORDER BY column ASC` - Sort ascending (explicit)
- `ORDER BY column DESC` - Sort descending
- `ORDER BY column1, column2 DESC` - Multi-column sort

Examples:

```sql
ORDER BY price
ORDER BY price ASC
ORDER BY price DESC
ORDER BY category, price DESC
ORDER BY p.name, o.orderDate DESC
```

**Note:** ORDER BY supports:

- Numbers (sorted numerically)
- Text (sorted alphabetically)
- Booleans (false < true)
- Qualified column names (`p.price`, `o.quantity`)

### TOP / LIMIT

- `SELECT TOP n` - Limit to first n results
- `SELECT ... LIMIT n` - Alternative syntax

**Note:** `n` must be a non-negative integer; negative values are rejected with an error. The limit is applied **last** (after WHERE, ORDER BY, projection, and DISTINCT) and does not short-circuit data loading.

### Common Table Expressions (WITH)

Use a `WITH` clause to define one or more named, reusable subqueries (CTEs) that you can then select from in the main query.

```sql
-- Single CTE
WITH expensive AS (
  SELECT * FROM products WHERE price > 50
)
SELECT name, price FROM expensive ORDER BY price DESC
```

```sql
-- Multiple CTEs
WITH electronics AS (
  SELECT * FROM products WHERE category = 'Electronics'
),
in_stock AS (
  SELECT * FROM electronics WHERE inStock = true
)
SELECT name, price FROM in_stock
```

CTE results are materialized and can be referenced like any other table. When `--enable-cache` is active, CTE results are cached and invalidated based on their source files (see [Caching](#caching)).

## Advanced Usage

### Complex JSONPath Mappings

For deeply nested JSON structures:

```json
{
  "document": {
    "metadata": {
      "version": "1.0"
    },
    "data": {
      "entities": {
        "products": [
          {"id": 1, "name": "Widget"}
        ]
      }
    }
  }
}
```

```bash
jsonsql --add-mapping products "$.document.data.entities.products"
```

### Mapping Rules

A mapping associates a table name (alias) with a JSONPath expression and, optionally, a specific file or directory. The supported forms are:

- **JSONPath only** — `alias "$.path.to.array"`: the JSONPath must start with `$`. The data is read from `<alias>.json` in the active data directory.
- **File + JSONPath** — `alias "filename.json:$.path"`: read the path from a specific file (relative to `--data-dir`, or an absolute path).
- **Directory + JSONPath** — `alias "subdir:$.path"`: load **all** `.json` files found under `subdir` **recursively** (including nested subdirectories) and combine their arrays into one table.
- **Absolute paths** (Windows, Linux, macOS) — e.g. `alias "/var/data/products.json:$.items"` or `alias "C:\data\products.json:$.items"`. Absolute paths are used as-is (not relative to `--data-dir`). The `file:path` delimiter is the colon immediately before the JSONPath (`:$`), so a Windows drive-letter colon (e.g. `C:`) is not mistaken for the separator.

Validation:

- The JSONPath portion **must** begin with `$`; mappings that don't are rejected when added.
- Invalid mappings fail at `--add-mapping` time with a clear error, rather than silently failing later at query time.

```bash
# JSONPath only (reads products.json)
jsonsql --add-mapping products "$.products"

# Specific file
jsonsql --add-mapping orders "ecommerce.json:$.store.orders"

# Directory (recursive) — combines every .json file under products-multi/
jsonsql --add-mapping all_products "products-multi:$.products" --data-dir example-data

# Absolute path (Linux / macOS)
jsonsql --add-mapping items "/var/data/inventory.json:$.items"

# Absolute path (Windows)
jsonsql --add-mapping items "C:\data\inventory.json:$.items"
```

### Working with Multiple Files

#### Single File Per Table

Place your JSON files in a data directory:

```
data/
  ├── products.json
  ├── orders.json
  └── customers.json
```

```bash
jsonsql --data-dir ./data --query "SELECT * FROM products"
```

#### Multiple Tables from One File

When multiple data collections are in a single file, specify the filename in the mapping:

```bash
# Both tables from ecommerce.json
jsonsql --add-mapping products "ecommerce.json:$.store.data.products"
jsonsql --add-mapping orders "ecommerce.json:$.store.data.orders"

# Now you can JOIN them
jsonsql --query "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id"
```

#### Partitioned Data Across Multiple Files

Query data split across multiple files or directories. The shipped fixtures under `example-data/` use the `products-multi/` and `orders-multi/` directories:

```
example-data/
  ├── products-multi/
  │   ├── products_2023.json
  │   ├── products_2024.json
  │   └── products_2025.json
  └── orders-multi/
      ├── orders_q1.json
      └── orders_q2.json
```

```bash
# Map to a directory - loads ALL .json files in that directory recursively (including subdirectories)
jsonsql --add-mapping all_products "products-multi:$.products" --data-dir example-data
jsonsql --add-mapping all_orders "orders-multi:$.orders" --data-dir example-data

# Query combines data from all files automatically
jsonsql --query "SELECT * FROM all_products" --data-dir example-dataWITH

# JOIN works across partitioned files too!
jsonsql --query "SELECT p.name, o.quantity FROM all_orders o JOIN all_products p ON o.productId = p.id" --data-dir example-data
```

#### Relative Paths in Mappings

Use relative paths for files in subdirectories:

```bash
# Single file with relative path
jsonsql --add-mapping products "archive/2023/products.json:$.data.products"

# Directory with relative path
jsonsql --add-mapping products "archive/products:$.products"
```

### Piping and Scripting

Only result JSON is written to stdout — all informational messages (e.g. "Running saved query…", "Output written to…", "Output copied to clipboard") go to stderr. This means stdout is always clean and safe to pipe into other tools without filtering.

```bash
# Pipe to jq for further processing
jsonsql --query "SELECT * FROM products" | jq '.[] | select(.price > 20)'

# Save to file and process
jsonsql --query "SELECT * FROM products WHERE price > 20" --output high-value.json

# Use in scripts: count rows with jq (COUNT(*) is not yet supported)
#!/bin/bash
COUNT=$(jsonsql --query "SELECT * FROM products" | jq 'length')
echo "Total products: $COUNT"
```

## Caching

JsonSQL can optionally cache parsed JSON data on disk to speed up repeated queries against the same sources.

```bash
# Enable the disk cache for this run (and populate it)
jsonsql --enable-cache --query "SELECT * FROM products" --data-dir example-data

# Clear all cached data for the configured tables
jsonsql --clear-cache
```

How it works:

- When `--enable-cache` is set, parsed JSON is stored under a local `.jsonsql-cache/` directory.
- The cache is **freshness-aware**: each entry's key includes the source file's last-modified time and size, so the cache is automatically invalidated and rebuilt whenever a source file changes. You never need to manually clear it after editing data.
- CTE (`WITH`) results are also cached, keyed by the CTE's full definition (SELECT list, WHERE, ORDER BY, LIMIT/TOP) and the freshness fingerprint of its source files.
- Caching trades disk space for speed on repeated reads; it does not reduce the per-query memory needed to run a query.
- Use `--clear-cache` to remove all cached entries (for example, to reclaim disk space).

## Indexing

For tables backed by **many files** (e.g. a partitioned directory tree), JsonSQL can build small per-file value summaries so selective queries skip files that cannot contain a matching row. This is **file-level pruning** — the unit that is skipped is a whole file.

```bash
# Declare and build an index on a field (scalar or dotted path)
jsonsql --add-index products category --data-dir example-data
jsonsql --add-index products price    --data-dir example-data

# Array fields are indexed as a union of element values (used with UNNEST)
jsonsql --add-index products tags           --data-dir example-data
jsonsql --add-index products reviews.rating --data-dir example-data

# Inspect, rebuild, or drop
jsonsql --list-indexes --data-dir example-data
jsonsql --rebuild-indexes --data-dir example-data
jsonsql --drop-index products category --data-dir example-data
```

How it works:

- Index **definitions** live in `.jsonsql-indexes.json`; the per-file **summaries** live under `.jsonsql-index/` in the data directory.
- Each summary records, per file, the distinct values (up to a cardinality cap), `min`/`max`, value type, and a freshness fingerprint (last-modified time + size).
- Pruning is **correctness-preserving**: a file is skipped only when a fresh summary proves no row can satisfy a mandatory `AND` predicate (`=`, `IN`, or a range `>`,`>=`,`<`,`<=`). Anything uncertain (no index, an `OR`, a stale/missing summary, mixed types, a changed mapping) falls back to a full scan, so results are always identical to running without an index.
- Indexes are used automatically when present; pass `--no-index` to force a full scan.

See [INDEXING.md](INDEXING.md) for the full design, supported predicates, and limitations.

## Performance Considerations

Understanding how JsonSQL processes data helps set expectations for large inputs:

- **In-memory processing**: Each matched JSON file is read fully into memory (via `Files.readString`) and parsed before any query phases run. There is no streaming parser; peak memory scales with the total size of the loaded files.
- **No early termination**: `TOP`/`LIMIT` does **not** short-circuit loading. Queries run in the order WHERE → ORDER BY → projection → DISTINCT → LIMIT, so the entire matched dataset is loaded and filtered before the limit is applied.
- **Optional disk cache**: Use `--enable-cache` to persist parsed JSON between runs (see [Caching](#caching)). The cache speeds up repeated queries against unchanged sources but does not reduce per-query memory usage.

For very large files (>100MB), consider:

- Adding specific `WHERE` clauses to reduce the result set (note this does not reduce memory used to load the source)
- Splitting large JSON files into smaller files (directory mappings load matching files recursively)
- Enabling the disk cache (`--enable-cache`) for repeated queries over the same data

## Troubleshooting

### Common Issues

**"No mapping found for table"**

```bash
# Add the mapping first
jsonsql --add-mapping products "$.data.products"
```

**"JSON file not found"**

```bash
# Verify file location and use --data-dir
jsonsql --data-dir /path/to/data --query "SELECT * FROM products"
```

**"Invalid SQL syntax"**

```bash
# Check query syntax, especially quotes
jsonsql --query "SELECT * FROM products WHERE name = 'Widget'"
```

**Empty results**

```bash
# Verify JSONPath expression returns array
jsonsql --list-tables  # Check configured paths
```

**"Data directory does not exist" / "is not a directory"**

```bash
# --data-dir must point to an existing directory
jsonsql --data-dir ./example-data --query "SELECT * FROM products"
```

**"Only one action may be specified" (or similar)**

```bash
# Primary actions are mutually exclusive — run them one at a time
jsonsql --list-tables          # don't combine with --query
jsonsql --query "SELECT * FROM products"
```

### Exit Codes and Output Streams

- Exit code `0` indicates success; exit code `1` indicates an error.
- Result JSON is written to **stdout**; all informational and error messages go to **stderr**, so piping stdout never mixes in log text.

### Debug Mode

Set the `DEBUG` environment variable to a truthy value for detailed error messages and full stack traces (written to stderr):

```bash
export DEBUG=1
jsonsql --query "SELECT * FROM products"
```

## Documentation

- **[Quick Start Guide](QUICK-START.md)** - Get up and running quickly
- **[Basic Examples](EXAMPLES.md)** - Common query patterns
- **[Complex Query Examples](COMPLEX-QUERY-EXAMPLES.md)** - Advanced customer/order/product analysis
- **[Multi-File Examples](MULTI-FILE-EXAMPLES.md)** - Working with multiple JSON files
- **[Indexing Guide](INDEXING.md)** - Declared indexes and file-level pruning
- **[Saved Queries Reference](SAVED-QUERIES-REFERENCE.md)** - Query management features

## Future Enhancements

Planned features for future releases, organized by priority:

### High Priority (Core SQL Features)

**WHERE Clause Operators:**

- `BETWEEN` operator - Range checking (e.g., `WHERE price BETWEEN 100 AND 500`). Currently raises a clear "unsupported expression" error rather than being silently ignored.

**Aggregation & Grouping:**

- `GROUP BY` clause with aggregation functions
- `COUNT`, `SUM`, `AVG`, `MIN`, `MAX` functions
- `HAVING` clause for filtering grouped results

**Query Features:**

- Subqueries (e.g., `WHERE price > (SELECT AVG(price) FROM products)`)
- `UNION` / `UNION ALL` for combining result sets
- Calculated fields in SELECT (e.g., `SELECT price * 1.1 AS price_with_tax`)

### Medium Priority (Advanced Features)

**JOIN Enhancements:**

- `RIGHT JOIN` and `FULL OUTER JOIN`
- Self-joins with improved alias handling

**String & Data Functions:**

- String functions: `UPPER`, `LOWER`, `CONCAT`, `SUBSTRING`, `LENGTH`, `TRIM`
- Date/Time functions: `YEAR`, `MONTH`, `DAY`, `DATE`, `DATEADD`, `DATEDIFF`
- Numeric functions: `ROUND`, `FLOOR`, `CEIL`, `ABS`, `POWER`
- `COALESCE` / `IFNULL` for null value handling

**Conditional Logic:**

- `CASE/WHEN/ELSE/END` expressions for conditional values
- `IF` function for simple conditionals

**Pagination & Limiting:**

- `OFFSET` support for pagination (e.g., `LIMIT 10 OFFSET 20`)

### Lower Priority (Quality of Life)

**Array/Collection Operations:**

- ✅ `UNNEST` for flattening arrays
- Array contains/length operations
- JSONPath expressions in SELECT

**Output Formats:**

- ✅ **CSV output** (`--format csv`) — header row plus RFC 4180–escaped values - **IMPLEMENTED**
- TSV output format (`--format tsv`)
- ASCII table format (`--format table`)
- XML output format (`--format xml`)

**Saved Query Enhancements:**

- ✅ **Parameterized queries** - Variables in saved queries (e.g., `WHERE price > ${min_price}`) - **IMPLEMENTED**
- Query templates with default values (✅ supported via `${var:default}` syntax)
- Named parameters for reusability (✅ supported)

**Developer Tools:**

- ✅ **Query validation / dry-run** (`--dry-run`) — parse + mapping + file checks without executing - **IMPLEMENTED**
- Query profiling and performance analysis (`--explain`)
- ✅ **Schema introspection** (`--describe <table>`) — field paths, types, sample values - **IMPLEMENTED**

**Performance Optimizations:**

- ✅ **Declared indexes** (`--add-index`) — per-file value summaries for file-level pruning of multi-file tables - **IMPLEMENTED** (see [Indexing](INDEXING.md)); row-level/NDJSON offset indexing still planned
- Streaming mode for very large files

**SQL Compatibility:**

- Full three-valued logic (TRUE/FALSE/UNKNOWN) for NULL handling
- Additional comparison operators (`<>` as synonym for `!=`)
- Comment support in SQL (`--` and `/* */`)
- Multi-statement execution

### Community Requests

Have a feature request? Please open an issue on GitHub!

## Architecture

JsonSQL is built with:

- **Java 21**: Modern Java features for clean, efficient code
- **Picocli**: Command-line interface
- **Jackson**: JSON parsing and manipulation
- **JSONPath**: Path expression evaluation
- **JSqlParser**: SQL query parsing

## Contributing

Contributions are welcome! Please ensure:

- All tests pass: `mvn test`
- Code follows Java 21 best practices
- New features include unit tests

## License

[Add your license here]

## Support

For issues, questions, or feature requests, please [open an issue](link-to-issues).