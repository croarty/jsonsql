# JsonSQL

A powerful command-line tool that enables SQL-like querying of JSON files without requiring strongly-typed models. Built with Java 21 and designed for performance with large, complex JSON documents.

## Features

- **SQL-like Query Syntax**: Write familiar SQL queries against JSON data
  - `SELECT` / `SELECT DISTINCT` - Project specific fields or all fields with `*`, with `AS` aliases
  - `FROM` - Specify data sources with table aliases
  - `WHERE` - Filter results with complex conditions (AND, OR, NOT, parentheses, LIKE, ILIKE, IN, IS NULL)
  - `JOIN` / `LEFT JOIN` - Combine data from multiple sources (single equi-join condition)
  - `UNION` / `UNION ALL` - Combine result sets from multiple queries
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
- **Optional Disk Caching**: Persist parsed JSON between runs with `--enable-cache`; auto-invalidated when source files change
- **Declared Indexes**: Build per-file value summaries with `--add-index <table> <field>` so selective queries skip backing files that cannot match (file-level pruning; see [Indexing](INDEXING.md))
- **Materialized Views**: Persist the result of a `WITH` query with `--materialize-view <name>` and query it later like a table (see [Materialized Views](MATERIALIZED-VIEWS.md))
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

This creates an executable JAR at `target/jsonsql-1.6.0.jar`.

### Running

```bash
java -jar target/jsonsql-1.6.0.jar [options]
```

Or create an alias for convenience:

```bash
alias jsonsql='java -jar /path/to/jsonsql-1.6.0.jar'
```

## Continuous Integration & Security

The project ships with GitHub Actions workflows (in `.github/`) that build,
test, and continuously monitor dependencies for security issues.

### Automated build (CI)

`.github/workflows/ci.yml` runs on every push and pull request to `master`
(and can be triggered manually via the **Actions** tab). It:

- Builds and runs the full test suite with `./mvnw -B -ntp clean verify`
- Tests against JDK 21 (the project target) and JDK 25 (the newest LTS)
- Caches Maven downloads for faster runs
- Uploads the Surefire test reports and the packaged JAR as build artifacts

### Dependency security monitoring

Three layers keep an eye on vulnerable dependencies:

1. **Dependabot** (`.github/dependabot.yml`) — watches `pom.xml` and the GitHub
   Actions used in the workflows. It opens PRs for outdated dependencies
   (grouped by minor/patch) and raises an immediate PR whenever a dependency
   has a known security advisory. Enable **Dependabot alerts** and
   **Dependabot security updates** under *Settings > Code security* to get the
   most out of it.
2. **Dependency Review** (`.github/workflows/dependency-review.yml`) — runs on
   pull requests and fails the check if the PR introduces a dependency with a
   known high-severity vulnerability, blocking risky changes before merge.
3. **OWASP Dependency-Check** (`.github/workflows/security-scan.yml`) — a
   weekly scheduled scan of the full dependency tree against the NVD CVE
   database. Results are published to the **Security > Code scanning** tab.

### Static analysis

`.github/workflows/codeql.yml` runs GitHub CodeQL (`security-and-quality`
queries) on pushes, pull requests, and a weekly schedule, reporting findings
under **Security > Code scanning**.

### Running the security scan locally

The OWASP Dependency-Check scan is also available as a Maven profile:

```bash
# Optional but recommended – avoids NVD rate limiting.
# Free key: https://nvd.nist.gov/developers/request-an-api-key
export NVD_API_KEY=your-key

mvn -Psecurity verify
```

The build fails on any dependency with a CVSS score of 7.0 or higher, and a
report is written to `target/dependency-check-report.html`. The first run
downloads the NVD data set and may take several minutes.

## Quick Start

### 1. Configure JSONPath Mappings

JsonSQL uses a configuration file (`.jsonsql-mappings.json`) to map table names to JSONPath expressions within your JSON files.

> **Where config files live:** `.jsonsql-mappings.json`, `.jsonsql-queries.json`, `.jsonsql-indexes.json`, and `.jsonsql-views.json` are resolved relative to the **current working directory** (override with `-c`, `--queries-file`, `--indexes-file`, `--views-file`), not relative to `--data-dir`. `.jsonsql-mappings.json` is git-ignored, so a fresh clone starts with **no mappings** — run the `--add-mapping` commands shown in each guide first. Re-adding an existing alias overwrites it, and several guides reuse the names `products` / `orders` / `customers` for different fixture files, so consider a separate `-c <file>` per guide.

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
Usage: jsonsql [-hV] [--clear-cache] [--clipboard] [--dry-run] [--enable-cache]
               [--list-indexes] [--list-materialized-views] [--list-queries]
               [--list-tables] [--no-index] [--pretty] [--rebuild-indexes]
               [--rebuild-views] [-c=<configFile>] [-d=<dataDirectory>]
               [--delete-query=<deleteQueryName>] [--describe=<describeTable>]
               [--drop-materialized-view=<dropMaterializedViewName>]
               [--format=<outputFormat>] [--indexes-file=<indexesFile>]
               [--materialize-view=<materializeViewName>] [-o=<outputFile>]
               [-q=<query>] [--queries-file=<queriesFile>]
               [--rebuild-view=<rebuildViewName>] [--run-query=<runQueryName>]
               [--save-query=<saveQueryName>] [--show-view=<showViewName>]
               [--views-file=<viewsFile>] [--param=<parameters>]...
               [--add-index=<addIndex> <addIndex>]...
               [--add-mapping=<addMapping> <addMapping>]...
               [--drop-index=<dropIndex> <dropIndex>]...
               [--rebuild-index=<rebuildIndex> <rebuildIndex>]...

Query & output:
  -q, --query=<query>        SQL query to execute
  -d, --data-dir=<dir>       Directory containing JSON files (default: .)
  -o, --output=<file>        Output file path (default: stdout)
      --clipboard            Copy output to clipboard
      --pretty               Pretty-print JSON output
      --format=<format>      Output format: json (default) or csv
      --dry-run              Validate query syntax and table mappings without executing
      --param=<key=value>    Parameter value for parameterized queries (repeatable)

Mappings & introspection:
  -c, --config=<file>        Mapping configuration file (default: .jsonsql-mappings.json)
      --add-mapping <alias> <jsonpath>
                             Add (or overwrite) a JSONPath mapping
      --list-tables          Show all configured JSONPath mappings
      --describe=<table>     Show field names, types, and sample values for a
                             mapped table or materialized view

Saved queries:
      --queries-file=<file>  Saved queries file (default: .jsonsql-queries.json)
      --save-query=<name>    Save a query with a name (requires --query)
      --run-query=<name>     Execute a saved query by name
      --list-queries         Show all saved queries
      --delete-query=<name>  Delete a saved query

Caching:
      --enable-cache         Enable disk-based caching of parsed JSON data
      --clear-cache          Delete all cache files under <data-dir>/.jsonsql-cache

Indexes:
      --indexes-file=<file>  Declared-index definitions (default: .jsonsql-indexes.json)
      --add-index <table> <field>
                             Declare and build an index on a table field
      --drop-index <table> <field>
                             Remove a declared index
      --list-indexes         List declared indexes with FRESH/STALE status
      --rebuild-index <table> <field>
                             Rebuild one declared index
      --rebuild-indexes      Rebuild all declared indexes
      --no-index             Bypass declared indexes for this query (full scan)

Materialized views:
      --views-file=<file>    View definitions file (default: .jsonsql-views.json)
      --materialize-view=<name>
                             Create a materialized view from a WITH query (requires --query)
      --list-materialized-views
                             List materialized views with SQL and freshness
      --show-view=<name>     Show one materialized view definition
      --drop-materialized-view=<name>
                             Remove a materialized view (and its indexes)
      --rebuild-view=<name>  Rebuild one materialized view
      --rebuild-views        Rebuild all materialized views

  -h, --help                 Show this help message and exit
  -V, --version              Print version information and exit
```

Run `jsonsql --help` for the authoritative, alphabetized list.

Only one primary action may be specified per invocation (for example, you cannot
combine `--query` with `--list-tables`, or `--run-query` with `--query`); doing so
exits with an error. `--save-query` and `--materialize-view` take `--query` as their
payload, so those pairings are allowed. The tool returns exit code `0` on success and
`1` on error. Result data is written to stdout; informational and error messages are
written to stderr (see [Debug Mode](#debug-mode)).

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
# Assumes the mapping from COMPLEX-QUERY-EXAMPLES.md:
#   jsonsql --add-mapping products "complex-products.json:$.products[*]"
jsonsql --describe products --data-dir example-data
```

Shows the mapping, row count, and a column listing of every queryable field path (nested objects use dot notation), inferred JSON type, and a sample value from the data (excerpt):

```
Table: products
Mapping: complex-products.json:$.products[*]
Rows: 6

  Field                            Type     Sample
  id                               number   1
  name                             string   Wireless Mouses
  price                            number   24.99
  inStock                          boolean  true
  tags                             array    ["wireless","mouse","ergonomic","electronics"]
  specifications.connectivity      string   Bluetooth 5.0
  reviews                          array    [{"user":"TechReviewer","rating":5,"comment":"Excellent battery life and smoo...
```

Use this before writing queries to discover field names and nesting. Schema is inferred from up to 200 rows; fields only seen as `null` are typed `null`, fields with mixed null/non-null values are marked `(nullable)`, and samples longer than 80 characters are truncated. Arrays are reported as a single `array` field (use `UNNEST` to reach their elements). `--describe` also works on materialized views and honors `--enable-cache`.

#### Dry-Run a Query

```bash
# Assumes orders/products are mapped as in COMPLEX-QUERY-EXAMPLES.md
jsonsql --query "SELECT p.name, o.orderId FROM orders o JOIN products p ON o.productId = p.id" --data-dir example-data --dry-run
```

Validates SQL syntax, confirms every referenced table has a mapping (or is a CTE or materialized view), and checks that backing JSON files exist — without loading data or returning results. Unsupported syntax that is rejected at parse time (`GROUP BY`, `OFFSET`, `RIGHT JOIN`, functions in SELECT, ...) also fails the dry run. It does **not** check that referenced *fields* exist, and it cannot flag the few constructs that are still accepted but ineffective (see [Known Limitations](#known-limitations)):

```
Dry run OK.
  SQL parsed successfully.
  Tables resolved: orders, products
    orders -> complex-orders.json:$.orders[*]
    products -> complex-products.json:$.products[*]
```

Works with `--run-query` and parameterized queries (`--param` is applied before validation). `--dry-run` ignores `--output`, `--clipboard` (with a warning), and `--format`.

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
  all_electronics      -> SELECT * FROM products WHERE category = 'Electronics'
  top_orders           -> SELECT o.orderId, p.name, p.price FROM orders o JOIN products p ON o.productId = p.id WHERE o.status IN ('completed', 'shipped') ORDER BY p.price DESC LIMIT 10
────────────────────────────────────────────────────────────────────────────────
Total: 2 saved queries
```

Names are padded to 20 characters and the full SQL is printed (not truncated). When you `--run-query`, the "Running saved query" / "SQL:" lines go to stderr, so stdout stays pipe-friendly.

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

> **Shell quoting:** in bash/zsh, `${name}` inside double quotes is expanded by the *shell* (usually to an empty string) before JsonSQL sees it. Escape the dollar sign as `\${name}` (as below), or wrap the query in single quotes. In PowerShell, use single quotes around the query (doubling any embedded single quotes) or escape with a backtick: `` `${name} ``.

**Examples:**

```bash
# Save a parameterized query
jsonsql --save-query filtered_products \
  --query "SELECT * FROM products WHERE price > \${min_price} AND category = '\${category}'"

# Run with parameters
jsonsql --run-query filtered_products \
  --data-dir example-data \
  --param min_price=100 \
  --param category=Electronics

# Using default values
jsonsql --save-query expensive_items \
  --query "SELECT * FROM products WHERE price > \${min_price:100} ORDER BY price DESC"

# Run with default (uses 100)
jsonsql --run-query expensive_items --data-dir example-data

# Override default
jsonsql --run-query expensive_items \
  --data-dir example-data \
  --param min_price=500

# Multiple parameters
jsonsql --save-query search_products \
  --query "SELECT * FROM products WHERE name LIKE '\${pattern:%laptop%}' AND price < \${max_price:1000}"

jsonsql --run-query search_products \
  --data-dir example-data \
  --param pattern=%desk% \
  --param max_price=500

# Parameters in direct queries
jsonsql --query "SELECT * FROM products WHERE price > \${min_price}" \
  --data-dir example-data \
  --param min_price=200
```

**Parameter Rules:**

- Required parameters (no default): Must be provided with a non-empty `--param` value or an error is thrown (`Missing required parameters: ...`)
- Optional parameters (with default): Use default value if not provided
- Parameter names: Any characters except `}` and `:` (keys and values are trimmed); `--param` must be `key=value`
- Extra `--param` values that don't match a placeholder are ignored
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

This repository ships 39 pre-configured example queries in `.jsonsql-queries.json` (36 feature-numbered `01_`–`36_` queries, the named `completed_orders` and `expensive_electronics` examples, and the parameterized `filtered_products`). They reference four tables that must be mapped first (the mappings file is not committed):

```bash
jsonsql --add-mapping ecommerce_products "ecommerce.json:$.store.data.products"
jsonsql --add-mapping ecommerce_orders   "ecommerce.json:$.store.data.orders"
jsonsql --add-mapping complex_products   "complex-products.json:$.products[*]"
jsonsql --add-mapping products_multi     "products-multi:$.products"
```

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
- **Comparison:** `=`, `!=` (or `<>`), `>`, `<`, `>=`, `<=`
- **List Matching:** `IN`, `NOT IN` - check if value matches any in a literal list
- **Pattern Matching:** `LIKE`, `NOT LIKE` with `%` (any characters) and `_` (single character) wildcards
- **Case-Insensitive Pattern Matching:** `ILIKE`, `NOT ILIKE` - same as LIKE but case-insensitive
- **Null Checking:** `IS NULL`, `IS NOT NULL` (treats both missing and explicitly null fields as NULL)
- **Grouping:** Parentheses `()`

**Boolean Logic:**

- Short-circuit evaluation for `AND` and `OR`
- Full support for nested conditions with any level of complexity
- Operator precedence follows standard SQL rules
- SQL comments (`-- ...` and `/* ... */`) are accepted anywhere in the query

**Operand shape:** every predicate must have the **field on the left and a literal on the right** (`price > 20`, not `20 < price`). Reversed comparisons and field-to-field comparisons (`price > stock`) are not rejected — they simply match no rows.

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

**LIKE / ILIKE details:**

- Only JSON string fields can match. Numbers, booleans, arrays, and NULLs never match `LIKE` — and are also excluded by `NOT LIKE`.
- Regex metacharacters in the pattern (`.`, `(`, `[`, ...) are matched literally.
- `%` and `_` do not match newline characters.
- An `ESCAPE` clause is accepted by the parser but ignored, so there is no way to match a literal `%` or `_`.

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

- `NULL IN (...)` is unknown (excludes row)
- `NULL NOT IN (...)` is unknown (excludes row)
- A `NULL` inside the list follows the same rule: `x NOT IN ('A', NULL)` never matches, because any value might equal the `NULL`
- Use `OR category IS NULL` if you want to include null values

**Note on NULL behavior with NOT:** JsonSQL follows SQL three-valued logic. A condition on a NULL or missing field is *unknown*, `NOT` leaves it unknown, and a row is returned only when the whole `WHERE` clause is true. So `WHERE NOT price > 20` leaves out rows with no `price`, and agrees with `WHERE price <= 20`. Add `OR price IS NULL` to include those rows.

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
```

#### UNION Queries

Combine results from multiple SELECT queries using `UNION` or `UNION ALL`:

```bash
# UNION - combine results and remove duplicates
jsonsql --query "SELECT name FROM products WHERE category = 'Office' UNION SELECT name FROM products WHERE price > 80"

# UNION ALL - combine all results including duplicates
jsonsql --query "SELECT name FROM products WHERE category = 'Office' UNION ALL SELECT name FROM products WHERE category = 'Electronics'"

# ORDER BY applies to the entire result set
jsonsql --query "SELECT name FROM products WHERE category = 'Office' UNION ALL SELECT name FROM products WHERE category = 'Electronics' ORDER BY name"
```

#### UNNEST - Flatten Arrays

```bash
# UNNEST a string array (one row per tag)
jsonsql --query "SELECT name, tag FROM products, UNNEST(tags) AS t(tag)"

# UNNEST with complex objects
jsonsql --query "SELECT name, review.user, review.rating FROM products, UNNEST(reviews) AS r(review)"

# UNNEST with WHERE filtering
jsonsql --query "SELECT name, tag FROM products, UNNEST(tags) AS t(tag) WHERE tag = 'wireless'"

# JOIN UNNEST(...) is equivalent to the comma form, and the element can be used as a join key
jsonsql --query "SELECT o.orderId, p.name FROM orders o JOIN UNNEST(o.items) AS oi(item) JOIN products p ON item.productId = p.id"
```

These examples assume `products`, `orders`, and `customers` are mapped to the `complex-*.json` fixtures (see [COMPLEX-QUERY-EXAMPLES.md](COMPLEX-QUERY-EXAMPLES.md#data-setup)). The syntax is `UNNEST(<array field>) AS <alias>(<column>)`; the element is exposed under `<column>` (if you omit `(<column>)` it is named `value`). Rows whose field is missing, null, or not an array are dropped (there is no "outer" UNNEST).

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

> **Query phase order:** clauses are applied in the order **FROM → UNNEST → JOIN → WHERE → ORDER BY → projection (SELECT list) → DISTINCT → LIMIT/TOP**. This means sorting happens against the full source rows (you can `ORDER BY` a column you do not select, but not a SELECT alias), `DISTINCT` is applied to the projected columns, and `LIMIT`/`TOP` is applied last (after de-duplication).

### SELECT Clause

- `SELECT *` - All fields
- `SELECT field1, field2` - Specific fields
- `SELECT table.field` - Qualified field names
- `SELECT nested.path` - Dotted paths into nested objects (output key is the last segment, e.g. `level`)
- `SELECT field AS alias` / `SELECT field alias` - Rename an output column
- `SELECT DISTINCT field` - Unique values only
- `SELECT DISTINCT field1, field2` - Unique combinations

**Projection and missing values:** When you list explicit columns, every selected column is always present in each output row. If a row has no value for a selected field (the field is absent or JSON `null`), it is emitted as JSON `null` rather than omitted, so all rows share the same keys. `SELECT *` behaves differently — it returns each row's fields as-is and does **not** add keys for absent fields.

**Output column names:** an unaliased column uses its last path segment (`p.name` → `name`). If two selected columns would produce the same name, both fall back to their full expression (`SELECT o.id, p.id` → keys `o.id` and `p.id`). With `SELECT *` over a JOIN, all tables' fields are merged into one object and a later table's field overwrites an earlier one with the same name — alias the columns explicitly if names collide.

**Only plain field references and `*` are supported in the SELECT list.** `t.*`, literals, arithmetic (`price * 2`), string concatenation, functions (`UPPER(name)`), aggregates (`COUNT(*)`), and `CASE` are rejected with an error such as `Function or aggregate in SELECT list: COUNT(*) is not supported`.

### FROM Clause

- `FROM table` - Table name (a mapping, a materialized view, or a CTE)
- `FROM table alias` / `FROM table AS alias` - Table with alias
- Mapping and view names are **case-sensitive** (`FROM Products` does not find `products`); CTE names are case-insensitive.
- Derived tables (`FROM (SELECT ...) AS t`) are not supported and fail with a "No mapping or materialized view found" error — use a `WITH` clause instead.

### WHERE Clause

Supported operators:

- `=` - Equality
- `!=` / `<>` - Inequality
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

- For `>`, `<`, `>=`, `<=`, the stored field's JSON type decides the comparison: if the field is a JSON number it is compared numerically against the literal; if the field is text it is compared lexicographically (alphabetically), e.g. `WHERE name >= 'M'`. Boolean, array, and object fields never satisfy a range comparison.
- A number stored as a JSON string (e.g. `"10"`) is treated as text and therefore compares lexicographically, not numerically.
- Numeric equality (`=`, `!=`, `IN`) uses a tolerance of `0.0001`.
- Boolean fields compare against `true`/`false` (the literal `'true'` also matches).
- String equality is case-sensitive (use `ILIKE` for case-insensitive matching).
- SQL `NULL` values never satisfy a comparison, including inside `NOT`. Comparing with a literal `NULL` (`price = NULL`) never matches, so use `IS NULL`.

**Unsupported WHERE expressions fail loudly:** WHERE constructs that are not implemented — such as `BETWEEN` and subqueries (`IN (SELECT ...)`, `EXISTS (...)`) — raise a clear `Unsupported ...expression` error instead of silently returning zero rows. The check is on the operator, not its operands: a supported operator with an unsupported operand (`UPPER(name) = 'X'`, `20 < price`, `price > stock`) is evaluated as a field lookup that finds nothing and matches no rows (see [Known Limitations](#known-limitations)).

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
- Unmatched `LEFT JOIN` rows return `null` for every selected right-hand column.
- `INNER JOIN` and `LEFT OUTER JOIN` are accepted as synonyms for `JOIN` and `LEFT JOIN`.
- `RIGHT JOIN`, `FULL [OUTER] JOIN`, `CROSS JOIN`, and `NATURAL JOIN` are rejected (`RIGHT JOIN is not supported`, ...). Comma joins between tables (`FROM a, b`) and `JOIN ... USING (...)` are rejected with `JOIN requires ON condition`. The comma and `CROSS JOIN` forms are only valid with `UNNEST(...)`.
- Self-joins work as long as each side has its own alias (`FROM products a JOIN products b ON a.id = b.id`).
- Joins are nested-loop (every left row is compared with every right row), so cost grows with the product of the two table sizes.

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
- Text (sorted alphabetically, case-sensitive). If both values start with a number, they are compared numerically by that leading number (e.g. `"15.6 inch"` sorts after `"9 inch"`).
- Booleans (false < true)
- A **missing** field sorts first in ascending order (last with `DESC`). An explicit JSON `null` is compared as the text `"null"`, so it usually lands after numbers and among strings — filter with `IS NOT NULL` if placement matters
- Qualified column names (`p.price`, `o.quantity`) and nested paths (`vipStatus.points`)
- Sorting is stable: rows that compare equal keep their source order

Because sorting runs *before* projection, `ORDER BY` must name a **source field**. A SELECT alias (`SELECT price AS cost ... ORDER BY cost`) or a column position (`ORDER BY 2`) is not rejected but has no effect — the rows come back unsorted. (In a `UNION`, the opposite applies: see below.)

### TOP / LIMIT

- `SELECT TOP n` - Limit to first n results
- `SELECT ... LIMIT n` - Alternative syntax

**Note:** `n` must be a non-negative integer; a negative `LIMIT` is rejected with an error. The limit is applied **last** (after WHERE, ORDER BY, projection, and DISTINCT) and does not short-circuit data loading. `OFFSET` (`LIMIT 10 OFFSET 20`, MySQL-style `LIMIT 20, 10`, or `OFFSET ... FETCH ...`) is not supported and is rejected with `OFFSET / FETCH is not supported`.

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

CTE results are computed in memory (once per query) and can be referenced like any other table, including in JOINs. When `--enable-cache` is active, CTE results are also cached on disk (see [Caching](#caching)). To persist a CTE across runs as a named table, see [Materialized Views](MATERIALIZED-VIEWS.md).

CTE rules:

- CTEs are evaluated in order; a CTE may reference mapped tables, materialized views, and CTEs defined **earlier** in the same `WITH` list (a reference to a later CTE fails with "No mapping or materialized view found").
- A CTE body must be a single plain `SELECT` — no `UNION` inside a CTE and no nested `WITH`.
- `WITH RECURSIVE` is not supported.
- A CTE with the same name as a mapping or materialized view shadows it for that query.

### UNION Queries

Combine result sets from multiple SELECT queries:

```sql
-- UNION: Combine results and remove duplicates
SELECT name FROM products WHERE category = 'Tools'
UNION
SELECT name FROM products WHERE price > 100

-- UNION ALL: Keep all rows including duplicates
SELECT name FROM products WHERE category = 'Tools'
UNION ALL
SELECT name FROM products WHERE category = 'Electronics'

-- ORDER BY applies to the entire result set
SELECT name FROM products WHERE category = 'Tools'
UNION ALL
SELECT name FROM products WHERE category = 'Electronics'
ORDER BY name ASC

-- A trailing LIMIT applies to the whole combined result (after ORDER BY)
SELECT name FROM products WHERE category = 'Tools'
UNION ALL
SELECT name FROM products WHERE category = 'Electronics'
ORDER BY name
LIMIT 3

-- Limit an individual branch with TOP, or with a parenthesized LIMIT
SELECT TOP 1 name FROM products WHERE category = 'Tools'
UNION ALL
(SELECT name FROM products ORDER BY price DESC LIMIT 1)
```

UNION rules:

- Every branch must select the **same number of columns** (checked at parse time). Column *names* are not unified: each row keeps the output names of the branch it came from, so alias the columns identically (`SELECT id AS key ... UNION SELECT orderId AS key ...`) if you want a uniform shape.
- `UNION` de-duplicates whole rows; `UNION ALL` keeps duplicates. When both appear, operators are applied left to right as in standard SQL: `A UNION ALL B UNION C` de-duplicates everything, while `A UNION B UNION ALL C` de-duplicates `A`+`B` and then appends all of `C`.
- A trailing `ORDER BY` sorts the combined output and therefore refers to **output column names** (aliases), not qualified source fields.
- A trailing `LIMIT n` (after the last branch, with or without `ORDER BY`) limits the **whole** result, after de-duplication and sorting. `TOP` and a `LIMIT` inside parentheses apply to their own branch only. `OFFSET` is rejected.
- `INTERSECT`, `EXCEPT`, and `MINUS` are rejected (`INTERSECT is not supported`).
- A `WITH` clause may precede a UNION, and its CTEs are visible to every branch. A UNION cannot be used inside a CTE.

### Known Limitations

Only `SELECT` statements are accepted. Unimplemented SQL fails with a clear error (exit code `1`, nothing written to stdout) rather than returning misleading rows:

| Construct | Error |
|---|---|
| `GROUP BY`, `HAVING` | `GROUP BY is not supported` / `HAVING is not supported` |
| Aggregates, functions, arithmetic, literals, `CASE`, `\|\|` in SELECT | `Function or aggregate in SELECT list: COUNT(*) is not supported (...)` |
| `SELECT t.*` | `SELECT t.* is not supported (use SELECT * or list the columns)` |
| `OFFSET`, `LIMIT m, n`, `FETCH` | `OFFSET / FETCH is not supported` |
| `RIGHT`, `FULL [OUTER]`, `CROSS` (except with `UNNEST`), `NATURAL` joins | `RIGHT JOIN is not supported`, ... |
| `INTERSECT`, `EXCEPT`, `MINUS` | `INTERSECT is not supported (only UNION and UNION ALL are supported)` |
| `BETWEEN`, `IN (SELECT ...)`, `EXISTS` in WHERE | `Unsupported WHERE expression: ...` |
| Derived tables (`FROM (SELECT ...) t`) | `No mapping or materialized view found for table: ...` |

These checks run at parse time, so `--dry-run` reports them too, and they apply inside CTE bodies and UNION branches as well.

A few constructs are still **accepted but have no effect**. Avoid them:

| Construct | What actually happens |
|---|---|
| `ORDER BY alias` or `ORDER BY 2` (non-UNION) | No sorting applied — order by the source field instead |
| Literal on the left (`20 < price`), field vs field (`price > stock`), function operands (`UPPER(x) = ...`) | Predicate matches no rows |
| `LIKE ... ESCAPE '...'` | Escape clause ignored |

Until aggregation lands, pipe results to a tool like `jq` for counts and sums.

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

- **JSONPath only** — `alias "$.path.to.array"`: the JSONPath must start with `$`. The data is read from `<alias>.json` in the active data directory (falling back to a case-insensitive filename match).
- **File + JSONPath** — `alias "filename.json:$.path"`: read the path from a specific file (relative to `--data-dir`, or an absolute path).
- **Directory + JSONPath** — `alias "subdir:$.path"`: load **all** `.json` files found under `subdir` **recursively** (including nested subdirectories) and combine their arrays into one table.
- **Absolute paths** (Windows, Linux, macOS) — e.g. `alias "/var/data/products.json:$.items"` or `alias "C:\data\products.json:$.items"`. Absolute paths are used as-is (not relative to `--data-dir`). The `file:path` delimiter is the colon immediately before the JSONPath (`:$`), so a Windows drive-letter colon (e.g. `C:`) is not mistaken for the separator.

Validation:

- The JSONPath portion **must** begin with `$`; mappings that don't are rejected when added.
- Invalid mappings fail at `--add-mapping` time with a clear error, rather than silently failing later at query time. (Whether the file exists is only checked at query time.)

Rows:

- The JSONPath should select an **array of objects** (or a single object, which becomes one row). Non-object elements (strings, numbers, nested arrays) are skipped, and a path that matches nothing yields zero rows rather than an error.
- `$.items` and `$.items[*]` are equivalent for this purpose.
- Directory walks skip JsonSQL's own metadata (`.jsonsql-cache/`, `.jsonsql-index/`, `.jsonsql-views/`, and `.jsonsql*` files); a mapped directory with no `.json` files is an error.

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
jsonsql --query "SELECT * FROM all_products" --data-dir example-data

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

# Delete every cache file for this data directory
jsonsql --clear-cache --data-dir example-data
```

How it works:

- When `--enable-cache` is set, the rows extracted from each source file (per file + JSONPath) are stored under `<data-dir>/.jsonsql-cache/`. The flag must be passed on every run that should use the cache.
- The cache is **freshness-aware**: each entry's key includes the source file's last-modified time and size, so a changed source file simply misses the cache and is re-read. Superseded entries are not deleted automatically; use `--clear-cache` to reclaim disk space.
- CTE (`WITH`) results are also cached, keyed by the CTE's definition (FROM/JOIN tables, WHERE, UNNEST, SELECT list, ORDER BY, LIMIT/TOP, DISTINCT) and the freshness fingerprint of everything it reads: mapped tables' source files, a materialized view's stored row file, or — for a CTE built on an earlier CTE — that CTE's own cache key, so changes propagate through chains of CTEs. Empty CTE results are not cached.
- Caching trades disk space for speed on repeated reads; it does not reduce the per-query memory needed to run a query.
- `--clear-cache` deletes all `*.cache` files in `<data-dir>/.jsonsql-cache/` (so pass the same `--data-dir` you query with).

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
- Indexes are used automatically when present, for the **FROM table** of each `SELECT` (main query, CTE bodies, UNION branches) but not for JOINed tables; pass `--no-index` to force a full scan.
- Indexes are not rebuilt automatically when data changes — modified files simply stop being pruned until you `--rebuild-index`/`--rebuild-indexes`.

See [INDEXING.md](INDEXING.md) for the full design, supported predicates, and limitations.

## Materialized Views

A materialized view persists the result of one CTE so later queries can use it like a table, without re-reading the source files:

```bash
# Build once (the --materialize-view name must match a CTE in the query)
jsonsql --materialize-view premium --data-dir example-data \
  --query "WITH premium AS (SELECT id, name, category, price FROM catalog_large WHERE price >= 200) SELECT * FROM premium"

# Query it like a mapped table
jsonsql --query "SELECT name, price FROM premium WHERE category = 'Electronics'" --data-dir example-data

# Manage
jsonsql --list-materialized-views --data-dir example-data
jsonsql --rebuild-view premium --data-dir example-data
jsonsql --drop-materialized-view premium --data-dir example-data
```

- Only the named CTE is executed and stored; it may read **mapped tables only** (not other views or sibling CTEs).
- Definitions live in `.jsonsql-views.json`; rows live in `<data-dir>/.jsonsql-views/<name>.json`.
- When the source files change the view becomes `STALE`: queries still return the stored rows and print a warning to stderr until you `--rebuild-view`.

See [MATERIALIZED-VIEWS.md](MATERIALIZED-VIEWS.md) for the full guide (this example needs the `catalog_large` mapping created there).

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

**"No mapping or materialized view found for table: X"**

```bash
# Add the mapping first (table names are case-sensitive)
jsonsql --add-mapping products "$.data.products"
```

**"JSON file not found"**

```bash
# Verify file location and use --data-dir
jsonsql --data-dir /path/to/data --query "SELECT * FROM products"
```

**A data file fails with a JSON parse error**

Each data file must contain one complete JSON document. A file that is cut off, has text after the document, or holds several documents (including newline-delimited JSON) fails with an error instead of returning partial rows.

These non-standard forms are accepted: single quotes, unquoted keys, trailing commas, `//`, `/* */` and `#` comments, leading zeros, a trailing decimal point (`5.`), `NaN`, and unescaped control characters in strings. Bare words such as `True` or `NULL`, leading `+` or `.` on numbers, hexadecimal numbers, and a byte order mark at the start of the file are rejected.

Decimals are read as doubles, so values with more than about 17 significant digits are rounded.

**"Invalid SQL syntax"**

```bash
# Check query syntax, especially quotes
jsonsql --query "SELECT * FROM products WHERE name = 'Widget'"
```

**Empty results**

```bash
# Verify the JSONPath selects an array of objects (a non-matching path returns [] rather than an error)
jsonsql --list-tables  # Check configured paths
jsonsql --describe products --data-dir example-data  # Check field names and types
```

Also check that each WHERE predicate has the field on the left and a literal on the right, that string comparisons match case exactly, and that numbers stored as JSON strings are compared as text.

**"Data directory does not exist" / "is not a directory"**

```bash
# --data-dir must point to an existing directory
jsonsql --data-dir ./example-data --query "SELECT * FROM products"
```

**"Multiple actions specified. Use only one of ..."**

```bash
# Primary actions are mutually exclusive — run them one at a time
jsonsql --list-tables          # don't combine with --query
jsonsql --query "SELECT * FROM products"
```

### Exit Codes and Output Streams

- Exit code `0` indicates success; exit code `1` indicates an error.
- Result JSON is written to **stdout**; all informational and error messages go to **stderr**, so piping stdout never mixes in log text.

### Debug Mode

Set the `DEBUG` environment variable to a truthy value for full stack traces on errors (written to stderr):

```bash
export DEBUG=1
jsonsql --query "SELECT * FROM products"
```

When enabled, the query engine also writes `DEBUG:` trace lines about CTE and UNION execution (including CTE cache keys) to stderr. An unset or blank `DEBUG`, or the values `0`, `false`, `no`, and `off` (any case), disable all debug output.

## Documentation

- **[Quick Start Guide](QUICK-START.md)** - Get up and running quickly
- **[Basic Examples](EXAMPLES.md)** - Common query patterns
- **[Complex WHERE Examples](COMPLEX-WHERE-EXAMPLES.md)** - Boolean logic, LIKE/ILIKE, IN, IS NULL
- **[Complex Query Examples](COMPLEX-QUERY-EXAMPLES.md)** - Advanced customer/order/product analysis
- **[Multi-File Examples](MULTI-FILE-EXAMPLES.md)** - Working with multiple JSON files
- **[Indexing Guide](INDEXING.md)** - Declared indexes and file-level pruning
- **[Materialized Views Guide](MATERIALIZED-VIEWS.md)** - Persisting CTE results as queryable views
- **[Saved Queries Guide](SAVED-QUERIES.md)** - Saving, running, and sharing queries
- **[Saved Queries Reference](SAVED-QUERIES-REFERENCE.md)** - The 39 shipped example queries
- **[Comprehensive Review](COMPREHENSIVE-REVIEW.md)** - Capability status and roadmap
- **[Subquery Design](SUBQUERY-DESIGN.md)** - Design notes for CTEs and (future) subqueries

## Future Enhancements

Planned features for future releases, organized by priority:

### High Priority (Core SQL Features)

**WHERE Clause Operators:**

- `BETWEEN` operator - Range checking (e.g., `WHERE price BETWEEN 100 AND 500`). Currently raises a clear "unsupported expression" error rather than being silently ignored.

**Aggregation & Grouping:**

- `GROUP BY` clause with aggregation functions (currently rejected with an error)
- `COUNT`, `SUM`, `AVG`, `MIN`, `MAX` functions (currently rejected with an error)
- `HAVING` clause for filtering grouped results

**Query Features:**

- Subqueries (e.g., `WHERE price > (SELECT AVG(price) FROM products)`) and derived tables in `FROM`
- Calculated fields in SELECT (e.g., `SELECT price * 1.1 AS price_with_tax`)
- ✅ **Unsupported SQL fails loudly** — `GROUP BY`, `HAVING`, `OFFSET`, `RIGHT`/`FULL`/`CROSS`/`NATURAL` joins, `INTERSECT`/`EXCEPT`, and non-column SELECT items are rejected at parse time instead of being silently ignored - **IMPLEMENTED**
- `ORDER BY` on a SELECT alias or column position

### Medium Priority (Advanced Features)

**JOIN Enhancements:**

- `RIGHT JOIN` and `FULL OUTER JOIN` (currently rejected with an error)
- Compound (`AND`) and non-equality `ON` conditions
- Hash joins instead of nested-loop joins

**String & Data Functions:**

- String functions: `UPPER`, `LOWER`, `CONCAT`, `SUBSTRING`, `LENGTH`, `TRIM`
- Date/Time functions: `YEAR`, `MONTH`, `DAY`, `DATE`, `DATEADD`, `DATEDIFF`
- Numeric functions: `ROUND`, `FLOOR`, `CEIL`, `ABS`, `POWER`
- `COALESCE` / `IFNULL` for null value handling

**Conditional Logic:**

- `CASE/WHEN/ELSE/END` expressions for conditional values
- `IF` function for simple conditionals

**Pagination & Limiting:**

- `OFFSET` support for pagination (e.g., `LIMIT 10 OFFSET 20`; currently rejected with an error)
- ✅ `LIMIT` applied to a whole `UNION` - **IMPLEMENTED**
- `INTERSECT` / `EXCEPT` set operations (currently rejected with an error)

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
- ✅ **Materialized views** (`--materialize-view`) — persisted CTE results queryable as tables, with freshness tracking and optional indexes - **IMPLEMENTED** (see [Materialized Views](MATERIALIZED-VIEWS.md))
- Streaming mode for very large files

**SQL Compatibility:**

- ✅ Full three-valued logic (TRUE/FALSE/UNKNOWN) for NULL handling - **IMPLEMENTED**
- ✅ `<>` as a synonym for `!=` - **IMPLEMENTED**
- ✅ Comment support in SQL (`--` and `/* */`) - **IMPLEMENTED**
- Comparisons with the literal on the left, and field-to-field comparisons
- `BETWEEN` (listed above), `LIKE ... ESCAPE`
- Case-insensitive table and view names
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

Every pull request is automatically built and tested by the
[CI workflow](.github/workflows/ci.yml), scanned for vulnerable dependencies by
[Dependency Review](.github/workflows/dependency-review.yml), and analyzed by
[CodeQL](.github/workflows/codeql.yml). Please keep these checks green.

## License

[Add your license here]

## Support

For issues, questions, or feature requests, please [open an issue](link-to-issues).