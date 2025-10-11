# JsonSQL Examples

This file contains practical examples to help you get started with JsonSQL.

## Setup

First, build the project:
```bash
mvn clean package
```

The executable JAR will be at `target/jsonsql-1.0.0.jar`.

For convenience, create an alias:
```bash
alias jsonsql='java -jar target/jsonsql-1.0.0.jar'
```

## Example 1: Basic Setup

Configure the JSONPath mappings for the example data:

```bash
# Add mapping for products
jsonsql --add-mapping products "$.data.products" --data-dir example-data

# Add mapping for orders
jsonsql --add-mapping orders "$.orders" --data-dir example-data

# View configured mappings
jsonsql --list-tables
```

## Example 2: Simple SELECT Queries

```bash
# Select all products
jsonsql --data-dir example-data --query "SELECT * FROM products"

# Select specific columns
jsonsql --data-dir example-data --query "SELECT name, price FROM products"

# Select with WHERE clause
jsonsql --data-dir example-data --query "SELECT * FROM products WHERE category = 'Tools'"

# Filter by numeric value
jsonsql --data-dir example-data --query "SELECT * FROM products WHERE price > 20"

# Use TOP to limit results
jsonsql --data-dir example-data --query "SELECT TOP 2 * FROM products"
```

## Example 3: Queries with JOINs

```bash
# Inner JOIN to combine products and orders
jsonsql --data-dir example-data --query "SELECT p.name, o.quantity, o.orderDate FROM orders o JOIN products p ON o.productId = p.id"

# JOIN with WHERE clause
jsonsql --data-dir example-data --query "SELECT p.name, p.price, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE o.status = 'active'"

# LEFT JOIN (includes orders even if product not found)
jsonsql --data-dir example-data --query "SELECT o.orderId, p.name FROM orders o LEFT JOIN products p ON o.productId = p.id"

# Complex query with multiple filters
jsonsql --data-dir example-data --query "SELECT TOP 5 p.name, p.category, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE p.inStock = true"
```

## Example 4: Output Options

```bash
# Default output (stdout)
jsonsql --data-dir example-data --query "SELECT * FROM products"

# Pretty-print output
jsonsql --data-dir example-data --query "SELECT * FROM products" --pretty

# Save to file
jsonsql --data-dir example-data --query "SELECT * FROM products" --output results.json

# Save with pretty printing
jsonsql --data-dir example-data --query "SELECT * FROM products" --output results.json --pretty

# Copy to clipboard (on supported systems)
jsonsql --data-dir example-data --query "SELECT * FROM products" --clipboard
```

## Example 5: Working with Your Own Data

Let's say you have a complex JSON file `data.json`:

```json
{
  "api": {
    "version": "2.0",
    "response": {
      "users": [
        {"id": 1, "username": "alice", "email": "alice@example.com"},
        {"id": 2, "username": "bob", "email": "bob@example.com"}
      ]
    }
  }
}
```

Setup and query:

```bash
# Add mapping for the deeply nested users array
jsonsql --add-mapping users "$.api.response.users"

# Query the users
jsonsql --query "SELECT * FROM users"

# Filter users
jsonsql --query "SELECT username, email FROM users WHERE id = 1"
```

## Example 6: Real-World Scenario

Imagine you have an e-commerce system with products and orders:

```bash
# Find all active orders with product details and total value
jsonsql --data-dir example-data \
  --query "SELECT p.name, p.price, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE o.status = 'active'" \
  --pretty

# Find products that have been ordered
jsonsql --data-dir example-data \
  --query "SELECT p.name, p.category FROM products p JOIN orders o ON p.id = o.productId" \
  --output popular-products.json

# Find high-value products (price > 20) that are in stock
jsonsql --data-dir example-data \
  --query "SELECT name, price, category FROM products WHERE price > 20 AND inStock = true" \
  --pretty
```

## Example 7: Piping and Integration

JsonSQL integrates well with other command-line tools:

```bash
# Use with jq for further processing
jsonsql --data-dir example-data --query "SELECT * FROM products" | jq '.[] | select(.price > 20)'

# Count results
jsonsql --data-dir example-data --query "SELECT * FROM products" | jq 'length'

# Extract specific fields
jsonsql --data-dir example-data --query "SELECT name, price FROM products" | jq '.[].name'

# Format for CSV (requires jq)
jsonsql --data-dir example-data --query "SELECT name, price FROM products" | \
  jq -r '.[] | [.name, .price] | @csv'
```

## Tips and Best Practices

1. **Always configure mappings first** - Use `--add-mapping` before querying
2. **Use --pretty for readability** - Makes output easier to read during development
3. **Test queries with TOP** - Limit results while developing complex queries
4. **Use table aliases** - Makes queries more readable (e.g., `products p`)
5. **Start simple** - Test basic queries before adding JOINs and complex WHERE clauses
6. **Check mappings** - Use `--list-tables` to verify your configuration
7. **Use quotes** - Always quote your SQL queries on the command line
8. **Path matters** - Use `--data-dir` to specify where your JSON files are located

## Troubleshooting

### "No mapping found for table"
```bash
# Solution: Add the mapping
jsonsql --add-mapping <table_name> "<jsonpath>"
```

### "JSON file not found"
```bash
# Solution: Specify the data directory
jsonsql --data-dir /path/to/json/files --query "SELECT * FROM products"
```

### Empty results
```bash
# Check your JSONPath returns an array
jsonsql --list-tables  # Verify mappings

# Verify the JSONPath expression in your data
```

### Query syntax error
```bash
# Check for:
# - Proper quoting of query
# - Correct SQL syntax
# - Table aliases match in ON conditions
```

