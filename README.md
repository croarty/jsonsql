# JsonSQL

A powerful command-line tool that enables SQL-like querying of JSON files without requiring strongly-typed models. Built with Java 21 and designed for performance with large, complex JSON documents.

## Features

- **SQL-like Query Syntax**: Write familiar SQL queries against JSON data
  - `SELECT` - Project specific fields or all fields with `*`
  - `FROM` - Specify data sources
  - `WHERE` - Filter results with conditions
  - `JOIN` / `LEFT JOIN` - Combine data from multiple sources
  - `TOP x` / `LIMIT` - Limit result sets
- **Schema-less Design**: Works with any JSON structure without predefined models
- **JSONPath Mapping**: Define shortcuts for complex JSONPath expressions
- **Flexible Output**: Write to stdout, file, or clipboard
- **Performance Optimized**: Designed for large and complex JSON documents
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

This creates an executable JAR at `target/jsonsql-1.0.0.jar`.

### Running

```bash
java -jar target/jsonsql-1.0.0.jar [options]
```

Or create an alias for convenience:

```bash
alias jsonsql='java -jar /path/to/jsonsql-1.0.0.jar'
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
Usage: jsonsql [-chV] [--clipboard] [--list-tables] [--pretty]
               [--add-mapping=<addMapping> <addMapping>]
               [-c=<configFile>] [-d=<dataDirectory>] [-o=<outputFile>]
               [-q=<query>]

Options:
  -q, --query=<query>        SQL query to execute
  -d, --data-dir=<dataDirectory>
                             Directory containing JSON files (default: .)
  -c, --config=<configFile>  Path to mapping configuration file
                             (default: .jsonsql-mappings.json)
  -o, --output=<outputFile>  Output file path (default: stdout)
      --clipboard            Copy output to clipboard
      --pretty               Pretty-print JSON output
      --list-tables          Show all configured JSONPath shortcuts
      --add-mapping=<alias> <jsonpath>
                             Add a new JSONPath mapping
  -h, --help                 Show this help message and exit
  -V, --version              Print version information and exit
```

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
```

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

#### Combine Options
```bash
jsonsql --query "SELECT * FROM products" --output results.json --pretty
```

## SQL Syntax Support

### SELECT Clause
- `SELECT *` - All fields
- `SELECT field1, field2` - Specific fields
- `SELECT table.field` - Qualified field names

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

Examples:
```sql
WHERE price > 20
WHERE category = 'Tools'
WHERE quantity >= 5
WHERE status != 'cancelled'
```

### JOIN Clause
- `JOIN table ON condition` - Inner join
- `LEFT JOIN table ON condition` - Left outer join

Examples:
```sql
JOIN products p ON o.productId = p.id
LEFT JOIN customers c ON o.customerId = c.id
```

### TOP / LIMIT
- `SELECT TOP n` - Limit to first n results
- `SELECT ... LIMIT n` - Alternative syntax

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

### Working with Multiple Files

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

### Piping and Scripting

```bash
# Pipe to jq for further processing
jsonsql --query "SELECT * FROM products" | jq '.[] | select(.price > 20)'

# Save to file and process
jsonsql --query "SELECT * FROM products WHERE price > 20" --output high-value.json

# Use in scripts
#!/bin/bash
RESULT=$(jsonsql --query "SELECT COUNT(*) FROM products")
echo "Total products: $RESULT"
```

## Performance Considerations

JsonSQL is designed for performance with large JSON documents:

- **Streaming**: Uses efficient JSON parsing strategies
- **Early Termination**: `TOP`/`LIMIT` stops processing once limit is reached
- **Lazy Evaluation**: Only loads necessary data

For very large files (>100MB), consider:
- Using `TOP`/`LIMIT` to reduce result set size
- Adding specific `WHERE` clauses to filter early
- Splitting large JSON files into smaller chunks

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

### Debug Mode

Set the `DEBUG` environment variable for detailed error messages:

```bash
export DEBUG=1
jsonsql --query "SELECT * FROM products"
```

## Future Enhancements

Planned features for future releases:
- `ORDER BY` clause
- `GROUP BY` clause with aggregation functions
- `DISTINCT` keyword
- Subqueries
- `IN` and `LIKE` operators
- Nested field access in WHERE clauses

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

