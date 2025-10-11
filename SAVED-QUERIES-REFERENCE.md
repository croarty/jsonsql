# Saved Queries Reference

This document describes all 32 saved queries included with JsonSQL, organized by the features they demonstrate.

## Quick Test

Run any query with:
```bash
jsonsql --run-query <query_name> --data-dir example-data --pretty
```

## Query Catalog

### Basic Queries (SELECT fundamentals)

#### 01_all_products
**Demonstrates:** Simple SELECT *
```sql
SELECT * FROM ecommerce_products
```
Returns all products with all fields.

#### 02_product_summary
**Demonstrates:** SELECT specific columns + AS aliases
```sql
SELECT id, name, price AS cost, category AS type FROM ecommerce_products
```
Returns selected fields with renamed columns.

---

### WHERE Clause - Equality & Comparison

#### 03_electronics_only
**Demonstrates:** WHERE with equality (=)
```sql
SELECT name, price, brand FROM ecommerce_products WHERE category = 'Electronics'
```
Returns only Electronics products.

#### 04_expensive_items
**Demonstrates:** WHERE with greater than (>) + ORDER BY DESC
```sql
SELECT name, price, category FROM ecommerce_products WHERE price > 100 ORDER BY price DESC
```
Returns products over $100, sorted by price descending.

#### 05_affordable_electronics
**Demonstrates:** WHERE with AND operator
```sql
SELECT name, price FROM ecommerce_products WHERE category = 'Electronics' AND price < 50
```
Returns affordable Electronics items under $50.

#### 06_furniture_or_office
**Demonstrates:** WHERE with OR + ORDER BY multiple columns
```sql
SELECT name, category, price FROM ecommerce_products WHERE category = 'Furniture' OR category = 'Office' ORDER BY category, name
```
Returns Furniture OR Office items, sorted by category then name.

---

### WHERE Clause - IN Operator

#### 07_multiple_categories
**Demonstrates:** IN operator with strings
```sql
SELECT name, category, price FROM ecommerce_products WHERE category IN ('Electronics', 'Appliances', 'Furniture') ORDER BY price DESC
```
Returns products in multiple categories.

#### 08_exclude_stationery
**Demonstrates:** NOT IN operator
```sql
SELECT name, category FROM ecommerce_products WHERE category NOT IN ('Stationery', 'Office') ORDER BY name
```
Returns products excluding specific categories.

---

### WHERE Clause - LIKE Pattern Matching

#### 09_products_with_desk
**Demonstrates:** LIKE with % wildcard
```sql
SELECT name, category, price FROM ecommerce_products WHERE name LIKE '%Desk%' ORDER BY price
```
Returns products with "Desk" anywhere in the name.

#### 10_no_wireless
**Demonstrates:** NOT LIKE operator
```sql
SELECT name, price FROM ecommerce_products WHERE category = 'Electronics' AND name NOT LIKE '%Wireless%'
```
Returns Electronics items that are NOT wireless.

#### 23_brands_starting_with_a
**Demonstrates:** LIKE with start pattern (%)
```sql
SELECT name, brand, price FROM ecommerce_products WHERE brand LIKE 'A%' ORDER BY brand, price
```
Returns products from brands starting with 'A'.

#### 26_desk_or_monitor_items
**Demonstrates:** Multiple LIKE patterns with OR
```sql
SELECT name, category, price FROM ecommerce_products WHERE name LIKE '%Desk%' OR name LIKE '%Monitor%' ORDER BY name
```
Returns items matching multiple patterns.

---

### WHERE Clause - NULL Handling

#### 11_no_delivery_date
**Demonstrates:** IS NULL operator
```sql
SELECT orderId, productId, status FROM ecommerce_orders WHERE deliveryDate IS NULL
```
Returns orders not yet delivered (deliveryDate missing or null).

#### 12_delivered_orders
**Demonstrates:** IS NOT NULL + ORDER BY
```sql
SELECT orderId, status, deliveryDate FROM ecommerce_orders WHERE deliveryDate IS NOT NULL ORDER BY deliveryDate
```
Returns only delivered orders sorted by delivery date.

---

### WHERE Clause - Complex Conditions

#### 13_complex_filter
**Demonstrates:** Parentheses for grouping with OR
```sql
SELECT name, price, category FROM ecommerce_products WHERE (category = 'Electronics' AND price > 100) OR (category = 'Furniture' AND price < 100)
```
Returns expensive Electronics OR affordable Furniture.

#### 20_not_cheap_electronics
**Demonstrates:** NOT operator with complex condition
```sql
SELECT name, price FROM ecommerce_products WHERE NOT (category = 'Electronics' AND price < 30) AND category IS NOT NULL
```
Returns products that are NOT cheap Electronics.

#### 27_complex_nested
**Demonstrates:** Deep nesting with multiple levels
```sql
SELECT name, price, category FROM ecommerce_products WHERE ((category = 'Electronics' AND price < 50) OR (category = 'Stationery' AND price < 20)) AND inStock = true
```
Returns affordable items in stock from specific categories.

#### 29_mid_price_range
**Demonstrates:** Range filtering with >= and <=
```sql
SELECT name, category, price, brand FROM ecommerce_products WHERE price >= 50 AND price <= 150 ORDER BY category, price DESC
```
Returns mid-priced items sorted by category and price.

---

### JOIN Queries

#### 14_orders_with_products
**Demonstrates:** Simple INNER JOIN
```sql
SELECT o.orderId, p.name, p.price, o.quantity, o.status FROM ecommerce_orders o JOIN ecommerce_products p ON o.productId = p.id
```
Returns all orders with product details.

#### 15_high_value_delivered
**Demonstrates:** JOIN + WHERE + ORDER BY
```sql
SELECT o.orderId, p.name, p.price, o.quantity FROM ecommerce_orders o JOIN ecommerce_products p ON o.productId = p.id WHERE o.status = 'delivered' AND p.price > 100 ORDER BY p.price DESC
```
Returns delivered orders for expensive products.

#### 24_cancelled_orders
**Demonstrates:** JOIN with specific status filtering
```sql
SELECT o.orderId, p.name, o.orderDate, o.totalPrice FROM ecommerce_orders o JOIN ecommerce_products p ON o.productId = p.id WHERE o.status = 'cancelled'
```
Returns cancelled orders with product info.

#### 25_bulk_orders
**Demonstrates:** JOIN with quantity filtering
```sql
SELECT o.orderId, p.name, o.quantity, o.totalPrice FROM ecommerce_orders o JOIN ecommerce_products p ON o.productId = p.id WHERE o.quantity >= 5 ORDER BY o.quantity DESC
```
Returns large quantity orders.

#### 28_pending_deliveries
**Demonstrates:** JOIN + IS NULL + != operator
```sql
SELECT o.orderId, p.name, o.orderDate, o.status FROM ecommerce_orders o JOIN ecommerce_products p ON o.productId = p.id WHERE o.deliveryDate IS NULL AND o.status != 'cancelled' ORDER BY o.orderDate
```
Returns orders awaiting delivery (not cancelled).

---

### Advanced Multi-Operator Queries

#### 18_advanced_filter
**Demonstrates:** IN + AND + LIKE + IS NOT NULL
```sql
SELECT name, price, brand FROM ecommerce_products WHERE category IN ('Electronics', 'Appliances') AND price > 50 AND name LIKE '%e%' AND brand IS NOT NULL ORDER BY price
```
Returns tech products over $50 with 'e' in name and a known brand.

#### 19_active_electronics_orders
**Demonstrates:** JOIN + IN + multiple conditions + AS alias
```sql
SELECT o.orderId, p.name AS product, o.status, o.orderDate FROM ecommerce_orders o JOIN ecommerce_products p ON o.productId = p.id WHERE o.status IN ('shipped', 'processing') AND p.category = 'Electronics' ORDER BY o.orderDate DESC
```
Returns active Electronics orders.

#### 30_showcase_all_operators
**Demonstrates:** ALL operators in one query (IN, NOT IN, LIKE, IS NOT NULL, AND, >, JOIN, ORDER BY, LIMIT)
```sql
SELECT o.orderId, p.name, p.category, o.status FROM ecommerce_orders o JOIN ecommerce_products p ON o.productId = p.id WHERE o.status IN ('delivered', 'shipped') AND p.category NOT IN ('Stationery') AND p.price > 20 AND p.name LIKE '%e%' AND o.deliveryDate IS NOT NULL ORDER BY p.price DESC LIMIT 20
```
The ultimate showcase query combining every WHERE operator!

---

### TOP and LIMIT

#### 16_top_10_electronics
**Demonstrates:** TOP keyword
```sql
SELECT TOP 10 name, price, brand FROM ecommerce_products WHERE category = 'Electronics' ORDER BY price DESC
```
Returns top 10 most expensive Electronics.

#### 17_sample_mid_range
**Demonstrates:** LIMIT keyword
```sql
SELECT name, price, category FROM ecommerce_products WHERE price >= 20 AND price <= 100 ORDER BY price LIMIT 15
```
Returns first 15 mid-priced items.

---

### Business Logic Queries

#### completed_orders
**Demonstrates:** JOIN for delivered orders analysis
```sql
SELECT o.orderId, p.name, p.price FROM ecommerce_orders o JOIN ecommerce_products p ON o.productId = p.id WHERE o.status = 'delivered' ORDER BY p.price DESC
```
Returns all delivered orders sorted by product value.

#### expensive_electronics
**Demonstrates:** Complex filtering with multiple operators
```sql
SELECT name, price FROM ecommerce_products WHERE category IN ('Electronics', 'Appliances') AND price > 100 AND name LIKE '%e%'
```
Returns expensive tech items with 'e' in name.

#### 21_out_of_stock
**Demonstrates:** Boolean field + numeric comparison with OR
```sql
SELECT name, category, brand FROM ecommerce_products WHERE inStock = false OR stock = 0
```
Returns unavailable products.

#### 22_low_stock_alert
**Demonstrates:** Range checking for inventory management
```sql
SELECT name, stock, category FROM ecommerce_products WHERE stock > 0 AND stock < 50 ORDER BY stock
```
Returns low-stock items needing reorder.

---

## Feature Coverage Matrix

| Query | SELECT | WHERE | JOIN | LIKE | IN | IS NULL | AND/OR/NOT | ORDER BY | TOP/LIMIT | AS |
|-------|--------|-------|------|------|----|---------|-----------| ---------|-----------|-----|
| 01 | ✅ * | | | | | | | | | |
| 02 | ✅ cols | | | | | | | | | ✅ |
| 03 | ✅ cols | ✅ = | | | | | | | | |
| 04 | ✅ cols | ✅ > | | | | | | ✅ DESC | | |
| 05 | ✅ cols | ✅ <, = | | | | | ✅ AND | | | |
| 06 | ✅ cols | ✅ = | | | | | ✅ OR | ✅ multi | | |
| 07 | ✅ cols | | | | ✅ IN | | | ✅ DESC | | |
| 08 | ✅ cols | | | | ✅ NOT IN | | | ✅ | | |
| 09 | ✅ cols | | | ✅ LIKE | | | | ✅ | | |
| 10 | ✅ cols | ✅ = | | ✅ NOT LIKE | | | ✅ AND | | | |
| 11 | ✅ cols | | | | | ✅ IS NULL | | | | |
| 12 | ✅ cols | | | | | ✅ IS NOT NULL | | ✅ | | |
| 13 | ✅ cols | ✅ >, = | | | | | ✅ (), OR | | | |
| 14 | ✅ cols | | ✅ JOIN | | | | | | | |
| 15 | ✅ cols | ✅ >, = | ✅ JOIN | | | | ✅ AND | ✅ DESC | | |
| 16 | ✅ cols | ✅ = | | | | | | ✅ DESC | ✅ TOP | |
| 17 | ✅ cols | ✅ >=, <= | | | | | ✅ AND | ✅ | ✅ LIMIT | |
| 18 | ✅ cols | ✅ > | | ✅ LIKE | ✅ IN | ✅ IS NOT NULL | ✅ AND | ✅ | | |
| 19 | ✅ cols | ✅ = | ✅ JOIN | | ✅ IN | | ✅ AND | ✅ DESC | | ✅ |
| 20 | ✅ cols | | | | | ✅ IS NOT NULL | ✅ NOT, AND | | | |
| 21 | ✅ cols | ✅ =, bool | | | | | ✅ OR | | | |
| 22 | ✅ cols | ✅ >, < | | | | | ✅ AND | ✅ | | |
| 23 | ✅ cols | | | ✅ LIKE | | | | ✅ multi | | |
| 24 | ✅ cols | ✅ = | ✅ JOIN | | | | | | | |
| 25 | ✅ cols | ✅ >= | ✅ JOIN | | | | | ✅ DESC | | |
| 26 | ✅ cols | | | ✅ LIKE | | | ✅ OR | ✅ | | |
| 27 | ✅ cols | ✅ <, =, bool | | | | | ✅ (), AND, OR | | | |
| 28 | ✅ cols | ✅ != | ✅ JOIN | | | ✅ IS NULL | ✅ AND | ✅ | | |
| 29 | ✅ cols | ✅ >=, <= | | | | | ✅ AND | ✅ multi | | |
| 30 | ✅ cols | ✅ > | ✅ JOIN | ✅ LIKE | ✅ IN, NOT IN | ✅ IS NOT NULL | ✅ AND | ✅ DESC | ✅ LIMIT | |

---

## Testing Each Query

### Run All Queries
```bash
# Products
jsonsql --run-query 01_all_products --data-dir example-data --pretty
jsonsql --run-query 02_product_summary --data-dir example-data --pretty
jsonsql --run-query 03_electronics_only --data-dir example-data --pretty
jsonsql --run-query 04_expensive_items --data-dir example-data --pretty
jsonsql --run-query 05_affordable_electronics --data-dir example-data --pretty
jsonsql --run-query 06_furniture_or_office --data-dir example-data --pretty
jsonsql --run-query 07_multiple_categories --data-dir example-data --pretty
jsonsql --run-query 08_exclude_stationery --data-dir example-data --pretty
jsonsql --run-query 09_products_with_desk --data-dir example-data --pretty
jsonsql --run-query 10_no_wireless --data-dir example-data --pretty

# NULL handling
jsonsql --run-query 11_no_delivery_date --data-dir example-data --pretty
jsonsql --run-query 12_delivered_orders --data-dir example-data --pretty

# Complex WHERE
jsonsql --run-query 13_complex_filter --data-dir example-data --pretty
jsonsql --run-query 18_advanced_filter --data-dir example-data --pretty
jsonsql --run-query 20_not_cheap_electronics --data-dir example-data --pretty
jsonsql --run-query 27_complex_nested --data-dir example-data --pretty
jsonsql --run-query 29_mid_price_range --data-dir example-data --pretty

# JOINs
jsonsql --run-query 14_orders_with_products --data-dir example-data --pretty
jsonsql --run-query 15_high_value_delivered --data-dir example-data --pretty
jsonsql --run-query 19_active_electronics_orders --data-dir example-data --pretty
jsonsql --run-query 24_cancelled_orders --data-dir example-data --pretty
jsonsql --run-query 25_bulk_orders --data-dir example-data --pretty
jsonsql --run-query 28_pending_deliveries --data-dir example-data --pretty

# TOP/LIMIT
jsonsql --run-query 16_top_10_electronics --data-dir example-data --pretty
jsonsql --run-query 17_sample_mid_range --data-dir example-data --pretty

# Business Logic
jsonsql --run-query 21_out_of_stock --data-dir example-data --pretty
jsonsql --run-query 22_low_stock_alert --data-dir example-data --pretty
jsonsql --run-query 23_brands_starting_with_a --data-dir example-data --pretty
jsonsql --run-query 26_desk_or_monitor_items --data-dir example-data --pretty

# Ultimate Showcase
jsonsql --run-query 30_showcase_all_operators --data-dir example-data --pretty
jsonsql --run-query completed_orders --data-dir example-data --pretty
jsonsql --run-query expensive_electronics --data-dir example-data --pretty
```

---

## Query Categories by Use Case

### Data Exploration
- `01_all_products` - See all data
- `02_product_summary` - Overview with key fields
- `17_sample_mid_range` - Sample subset

### Inventory Management
- `21_out_of_stock` - Items needing restocking
- `22_low_stock_alert` - Low inventory warnings
- `03_electronics_only` - Category-specific inventory

### Order Analysis
- `14_orders_with_products` - Complete order details
- `15_high_value_delivered` - High-value completed sales
- `24_cancelled_orders` - Investigate cancellations
- `25_bulk_orders` - Large quantity orders
- `28_pending_deliveries` - Orders in progress
- `11_no_delivery_date` - Undelivered orders

### Product Search
- `04_expensive_items` - Premium products
- `05_affordable_electronics` - Budget options
- `09_products_with_desk` - Keyword search
- `26_desk_or_monitor_items` - Multiple keyword search

### Category Analysis
- `07_multiple_categories` - Multi-category view
- `08_exclude_stationery` - Filter out categories
- `06_furniture_or_office` - Specific category groups

### Brand Analysis
- `23_brands_starting_with_a` - Brand filtering
- `10_no_wireless` - Exclude product types

### Complex Analytics
- `18_advanced_filter` - Multi-criteria analysis
- `19_active_electronics_orders` - Active orders by category
- `27_complex_nested` - Complex business rules
- `30_showcase_all_operators` - Ultimate complex query

---

## Features Demonstrated

✅ **SELECT Variations**
- SELECT * (all columns)
- SELECT specific columns
- AS aliases for renaming

✅ **WHERE Operators**
- Comparison: =, !=, <, >, <=, >=
- List matching: IN, NOT IN
- Pattern matching: LIKE, NOT LIKE
- Null checking: IS NULL, IS NOT NULL
- Boolean logic: AND, OR, NOT
- Grouping: () parentheses

✅ **JOIN Operations**
- INNER JOIN with ON condition
- Table aliases (o, p)
- Qualified column names (o.orderId, p.name)

✅ **Sorting**
- ORDER BY single column
- ORDER BY multiple columns
- ASC and DESC

✅ **Limiting Results**
- TOP x
- LIMIT x

✅ **Data Types**
- Strings (text matching)
- Numbers (comparisons)
- Booleans (true/false)
- NULL values (missing/explicit)

---

## Quick Reference Commands

```bash
# List all queries
jsonsql --list-queries

# Run a query
jsonsql --run-query <name> --data-dir example-data --pretty

# Run with output to file
jsonsql --run-query <name> --data-dir example-data --output report.json --pretty

# Run to clipboard
jsonsql --run-query <name> --data-dir example-data --clipboard --pretty

# Delete a query
jsonsql --delete-query <name>

# Update a query (save with same name)
jsonsql --save-query <name> --query "NEW SQL..."
```

---

## Educational Path

For learning JsonSQL, run queries in this order:

**Beginner (Basic SQL):**
1. `01_all_products` - Simple SELECT
2. `02_product_summary` - Column selection
3. `03_electronics_only` - WHERE filtering
4. `04_expensive_items` - Comparisons + ORDER BY
5. `05_affordable_electronics` - AND operator

**Intermediate (Advanced Filtering):**
6. `06_furniture_or_office` - OR operator
7. `07_multiple_categories` - IN operator
8. `09_products_with_desk` - LIKE pattern matching
9. `11_no_delivery_date` - IS NULL
10. `13_complex_filter` - Parentheses grouping

**Advanced (JOINs and Complex Queries):**
11. `14_orders_with_products` - Basic JOIN
12. `15_high_value_delivered` - JOIN + filtering
13. `18_advanced_filter` - Multiple operators
14. `27_complex_nested` - Deep nesting
15. `30_showcase_all_operators` - Everything combined

---

## Summary

These 32 queries provide:
- ✅ Complete feature coverage
- ✅ Beginner to advanced examples
- ✅ Real-world business scenarios
- ✅ Educational progression
- ✅ Ready-to-use templates

All queries are production-ready and work with the example data in `example-data/ecommerce.json`.

