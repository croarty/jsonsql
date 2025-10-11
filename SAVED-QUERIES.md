# Saved Queries Guide

JsonSQL allows you to save frequently-used queries with descriptive names for easy reuse. This feature is perfect for complex queries, recurring reports, and sharing common queries across a team.

**✨ This repository includes 32 pre-configured example queries!**  
See [SAVED-QUERIES-REFERENCE.md](SAVED-QUERIES-REFERENCE.md) for complete documentation of all included queries.

## Table of Contents

- [Quick Start](#quick-start)
- [Pre-configured Queries](#pre-configured-queries)
- [Saving Queries](#saving-queries)
- [Running Saved Queries](#running-saved-queries)
- [Managing Saved Queries](#managing-saved-queries)
- [Best Practices](#best-practices)
- [Use Cases](#use-cases)
- [Storage and Sharing](#storage-and-sharing)

## Quick Start

```bash
# 1. List pre-configured queries (32 included!)
jsonsql --list-queries

# 2. Run an example query
jsonsql --run-query 16_top_10_electronics --data-dir example-data --pretty

# 3. Save your own query
jsonsql --save-query my_report --query "SELECT * FROM products WHERE price > 100"

# 4. Run your saved query
jsonsql --run-query my_report --data-dir example-data --pretty

# 5. Delete when no longer needed
jsonsql --delete-query my_report
```

## Pre-configured Queries

This repository includes **32 example queries** demonstrating all JsonSQL features:

| Category | Queries | Features Demonstrated |
|----------|---------|----------------------|
| **Basic SELECT** | 01-02 | SELECT *, columns, AS aliases |
| **WHERE Filtering** | 03-06 | =, <, >, AND, OR |
| **IN Operator** | 07-08 | IN, NOT IN |
| **LIKE Patterns** | 09-10, 23, 26 | LIKE, NOT LIKE, % wildcard |
| **NULL Handling** | 11-12 | IS NULL, IS NOT NULL |
| **Complex WHERE** | 13, 18, 20, 27, 29 | Parentheses, nested conditions |
| **JOINs** | 14-15, 19, 24-25, 28 | INNER JOIN, multiple tables |
| **TOP/LIMIT** | 16-17 | TOP, LIMIT |
| **Business Logic** | 21-22, 30 | Real-world scenarios |
| **Ultimate Showcase** | 30 | ALL operators combined |

**View complete reference:** [SAVED-QUERIES-REFERENCE.md](SAVED-QUERIES-REFERENCE.md)

**Try them out:**
```bash
# List all
jsonsql --list-queries

# Run any example
jsonsql --run-query 16_top_10_electronics --data-dir example-data --pretty
jsonsql --run-query 30_showcase_all_operators --data-dir example-data --pretty
```

## Saving Queries

### Simple Queries

```bash
# Save a basic SELECT query
jsonsql --save-query all_products --query "SELECT * FROM products"

# Save a filtered query
jsonsql --save-query electronics --query "SELECT * FROM products WHERE category = 'Electronics'"

# Save a query with sorting
jsonsql --save-query products_by_price --query "SELECT * FROM products ORDER BY price DESC"
```

### Complex Queries

```bash
# Save a query with JOIN
jsonsql --save-query product_orders --query "SELECT p.name, o.quantity, o.orderDate FROM orders o JOIN products p ON o.productId = p.id"

# Save a query with multiple conditions
jsonsql --save-query expensive_electronics --query "SELECT name, price FROM products WHERE category IN ('Electronics', 'Appliances') AND price > 100 AND name LIKE '%e%'"

# Save a complex analytics query
jsonsql --save-query top_sellers --query "SELECT o.orderId, p.name, p.price, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE o.status IN ('completed', 'shipped') AND p.price > 50 ORDER BY o.quantity DESC LIMIT 10"
```

### Queries with All Features

```bash
# Complex WHERE with IS NULL, IN, LIKE
jsonsql --save-query incomplete_products --query "SELECT * FROM products WHERE (description IS NULL OR category IS NULL) AND price IS NOT NULL AND name NOT LIKE '%Test%'"

# Multi-table JOIN with complex filtering
jsonsql --save-query premium_customers --query "SELECT c.name, COUNT(o.id) as order_count FROM customers c JOIN orders o ON c.id = o.customerId WHERE c.VIP.status IN ('Gold', 'Platinum') AND o.status != 'cancelled' GROUP BY c.id, c.name"
```

## Running Saved Queries

### Basic Execution

```bash
# Run with default options
jsonsql --run-query all_products --data-dir example-data

# Run with pretty printing
jsonsql --run-query electronics --data-dir example-data --pretty

# Run and save to file
jsonsql --run-query top_sellers --data-dir example-data --output report.json --pretty

# Run and copy to clipboard
jsonsql --run-query expensive_electronics --data-dir example-data --clipboard --pretty
```

### Running with Different Data

Saved queries can be run against different data directories:

```bash
# Development data
jsonsql --run-query product_orders --data-dir ./dev-data --pretty

# Production data
jsonsql --run-query product_orders --data-dir ./prod-data --pretty

# Historical data
jsonsql --run-query product_orders --data-dir ./archive/2024-01 --pretty
```

### Combining with Mappings

Saved queries work with your JSONPath mappings:

```bash
# Query uses 'products' mapping from .jsonsql-mappings.json
jsonsql --run-query all_products --data-dir data --pretty

# Use custom mapping file
jsonsql --run-query electronics --data-dir data --config custom-mappings.json --pretty
```

## Managing Saved Queries

### List All Queries

```bash
jsonsql --list-queries
```

Output:
```
Saved Queries:
────────────────────────────────────────────────────────────────────────────────
  all_products         -> SELECT * FROM products
  electronics          -> SELECT * FROM products WHERE category = 'Electronics'
  expensive_electronics -> SELECT name, price FROM products WHERE category IN...
  product_orders       -> SELECT p.name, o.quantity FROM orders o JOIN products p...
────────────────────────────────────────────────────────────────────────────────
Total: 4 saved queries
```

### Update a Query

To update a saved query, simply save it again with the same name:

```bash
# Original query
jsonsql --save-query electronics --query "SELECT * FROM products WHERE category = 'Electronics'"

# Update it
jsonsql --save-query electronics --query "SELECT * FROM products WHERE category = 'Electronics' ORDER BY price DESC"
```

### Delete a Query

```bash
jsonsql --delete-query old_query
```

### View Query Details

When running a query, the SQL is displayed:

```bash
$ jsonsql --run-query electronics --data-dir example-data
Running saved query: electronics
SQL: SELECT * FROM products WHERE category = 'Electronics' ORDER BY price DESC

[results...]
```

## Best Practices

### 1. Use Descriptive Names

```bash
# Good - clear purpose
jsonsql --save-query high_value_electronics --query "..."
jsonsql --save-query monthly_sales_report --query "..."
jsonsql --save-query incomplete_customer_records --query "..."

# Avoid - unclear
jsonsql --save-query q1 --query "..."
jsonsql --save-query temp --query "..."
```

### 2. Organize by Category

Use prefixes or naming conventions:

```bash
# Reports
jsonsql --save-query report_daily_sales --query "..."
jsonsql --save-query report_inventory --query "..."

# Analytics
jsonsql --save-query analytics_top_products --query "..."
jsonsql --save-query analytics_customer_segments --query "..."

# Admin
jsonsql --save-query admin_orphaned_orders --query "..."
jsonsql --save-query admin_data_quality --query "..."
```

### 3. Document Complex Queries

For complex queries, consider adding comments in documentation:

```bash
# Save the query
jsonsql --save-query quarterly_revenue --query "SELECT..."

# Document in team wiki or README
# quarterly_revenue: Calculates total revenue by product category for Q1 2024
# Filters out cancelled orders and includes only paid transactions
```

### 4. Version Control

Commit `.jsonsql-queries.json` to git:

```bash
git add .jsonsql-queries.json
git commit -m "Add standard analytics queries"
```

## Use Cases

### 1. Recurring Reports

```bash
# Save daily reports
jsonsql --save-query daily_sales --query "SELECT orderDate, SUM(total) FROM orders WHERE orderDate = TODAY() GROUP BY orderDate"

# Run every day
jsonsql --run-query daily_sales --output daily-$(date +%Y%m%d).json
```

### 2. Data Quality Checks

```bash
# Check for incomplete records
jsonsql --save-query incomplete_products --query "SELECT id, name FROM products WHERE description IS NULL OR category IS NULL OR price IS NULL"

# Run regularly
jsonsql --run-query incomplete_products --pretty
```

### 3. Complex Analytics

```bash
# Customer lifetime value
jsonsql --save-query customer_ltv --query "SELECT c.id, c.name, SUM(o.total) as lifetime_value FROM customers c JOIN orders o ON c.id = o.customerId WHERE o.status = 'completed' GROUP BY c.id, c.name ORDER BY lifetime_value DESC"

# Top products by revenue
jsonsql --save-query top_revenue_products --query "SELECT p.name, SUM(o.quantity * p.price) as revenue FROM orders o JOIN products p ON o.productId = p.id GROUP BY p.id, p.name ORDER BY revenue DESC LIMIT 10"
```

### 4. Development Shortcuts

```bash
# Quick data exploration
jsonsql --save-query sample_products --query "SELECT TOP 5 * FROM products"
jsonsql --save-query sample_orders --query "SELECT TOP 5 * FROM orders"

# Debug queries
jsonsql --save-query orphaned_orders --query "SELECT o.* FROM orders o LEFT JOIN products p ON o.productId = p.id WHERE p.id IS NULL"
```

### 5. Team Collaboration

Share common queries across the team:

```bash
# Everyone saves and uses the same queries
jsonsql --save-query active_customers --query "SELECT * FROM customers WHERE lastOrderDate > DATE_SUB(NOW(), INTERVAL 30 DAY)"

# Team members can run the standard query
jsonsql --run-query active_customers --data-dir /path/to/data --pretty
```

## Storage and Sharing

### Storage Location

Saved queries are stored in `.jsonsql-queries.json` by default:

```json
{
  "all_electronics": "SELECT * FROM products WHERE category = 'Electronics'",
  "completed_orders": "SELECT o.orderId, p.name FROM orders o JOIN products p...",
  "expensive_electronics": "SELECT name, price FROM products WHERE..."
}
```

### Custom Storage Location

```bash
# Use a different file
jsonsql --save-query my_query --query "..." --queries-file custom-queries.json

# Run from custom file
jsonsql --run-query my_query --queries-file custom-queries.json
```

### Sharing with Team

**Option 1: Version Control**
```bash
# Commit to git
git add .jsonsql-queries.json
git commit -m "Add standard product queries"
git push

# Team members pull
git pull
jsonsql --list-queries  # See all shared queries
```

**Option 2: Shared Configuration**
```bash
# Use a shared network location
jsonsql --save-query team_query --query "..." --queries-file //shared/queries.json

# Team uses shared file
jsonsql --run-query team_query --queries-file //shared/queries.json
```

**Option 3: Project-Specific**
```bash
# Different query sets per project
project-a/
  .jsonsql-queries.json
  .jsonsql-mappings.json
  data/

project-b/
  .jsonsql-queries.json
  .jsonsql-mappings.json
  data/
```

## Advanced Features

### Query Composition

Build complex queries step by step:

```bash
# 1. Start with base query
jsonsql --save-query base_products --query "SELECT * FROM products WHERE category = 'Electronics'"

# 2. Create variations
jsonsql --save-query affordable_electronics --query "SELECT * FROM products WHERE category = 'Electronics' AND price < 100"

jsonsql --save-query premium_electronics --query "SELECT * FROM products WHERE category = 'Electronics' AND price > 500"
```

### Query Families

Group related queries:

```bash
# Product queries
jsonsql --save-query products_all --query "SELECT * FROM products"
jsonsql --save-query products_in_stock --query "SELECT * FROM products WHERE inStock = true"
jsonsql --save-query products_low_stock --query "SELECT * FROM products WHERE stock < 10"

# Order queries  
jsonsql --save-query orders_all --query "SELECT * FROM orders"
jsonsql --save-query orders_pending --query "SELECT * FROM orders WHERE status = 'pending'"
jsonsql --save-query orders_completed --query "SELECT * FROM orders WHERE status = 'completed'"
```

## Tips and Tricks

### 1. Test Before Saving

```bash
# Test the query first
jsonsql --query "SELECT * FROM products WHERE..." --data-dir data --pretty

# If results look good, save it
jsonsql --save-query my_query --query "SELECT * FROM products WHERE..." 
```

### 2. Use Saved Queries in Scripts

```bash
#!/bin/bash
# Generate daily report

DATE=$(date +%Y%m%d)
jsonsql --run-query daily_sales --data-dir /data --output "reports/sales_${DATE}.json" --pretty

# Email the report
mail -s "Daily Sales Report" team@example.com < "reports/sales_${DATE}.json"
```

### 3. Backup Your Queries

```bash
# Backup
cp .jsonsql-queries.json .jsonsql-queries.backup.json

# Or use git
git add .jsonsql-queries.json
git commit -m "Backup queries"
```

### 4. Export/Import Queries

Since queries are stored in JSON, you can easily export/import:

```bash
# Export specific queries
cat .jsonsql-queries.json

# Import from another project
cp ../other-project/.jsonsql-queries.json .
```

## Common Query Patterns to Save

### Data Exploration
```bash
jsonsql --save-query explore_schema --query "SELECT TOP 1 * FROM products"
jsonsql --save-query count_records --query "SELECT COUNT(*) FROM products"
```

### Data Quality
```bash
jsonsql --save-query find_nulls --query "SELECT * FROM products WHERE description IS NULL OR category IS NULL"
jsonsql --save-query find_duplicates --query "SELECT name, COUNT(*) FROM products GROUP BY name HAVING COUNT(*) > 1"
```

### Common Filters
```bash
jsonsql --save-query active_items --query "SELECT * FROM products WHERE inStock = true"
jsonsql --save-query recent_orders --query "SELECT * FROM orders WHERE orderDate > DATE_SUB(NOW(), INTERVAL 7 DAY)"
```

### Aggregations
```bash
jsonsql --save-query category_summary --query "SELECT category, COUNT(*) as count, AVG(price) as avg_price FROM products GROUP BY category"
jsonsql --save-query sales_by_month --query "SELECT MONTH(orderDate) as month, SUM(total) as revenue FROM orders GROUP BY MONTH(orderDate)"
```

## Summary

Saved queries in JsonSQL provide:

✅ **Reusability**: Define complex queries once, use many times
✅ **Maintainability**: Update queries in one place
✅ **Shareability**: Version control and share with team
✅ **Documentation**: Query names serve as self-documentation
✅ **Productivity**: Faster than retyping complex queries
✅ **Consistency**: Everyone uses the same standard queries

**Storage:** `.jsonsql-queries.json` (JSON format, version control friendly)

**Commands:**
- `--save-query <name>` - Save a query
- `--run-query <name>` - Execute a saved query
- `--list-queries` - List all saved queries
- `--delete-query <name>` - Remove a saved query

For more information, see:
- [README.md](README.md) - Main documentation
- [EXAMPLES.md](EXAMPLES.md) - Basic query examples
- [COMPLEX-WHERE-EXAMPLES.md](COMPLEX-WHERE-EXAMPLES.md) - Complex WHERE clause examples

