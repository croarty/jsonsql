# Complex Query Examples

This document demonstrates advanced JsonSQL capabilities using complex customer, order, and product data with nested structures and relationships.

Every query below uses only features JsonSQL actually supports today (nested-field access, `UNNEST`, JOINs, `WHERE`, `ORDER BY`, `DISTINCT`, `TOP`/`LIMIT`) and has been run against the shipped `example-data/` fixtures. For analytics features that are **not** yet implemented (aggregation, `GROUP BY`, `CASE`, SQL functions), see [Planned / Not Yet Supported](#planned--not-yet-supported) at the end.

## Data Setup

First, set up the mappings for the complex data (these are stored in `.jsonsql-mappings.json`):

```bash
jsonsql --add-mapping customers "complex-customers.json:$.customers[*]" --data-dir example-data
jsonsql --add-mapping orders "complex-orders.json:$.orders[*]" --data-dir example-data
jsonsql --add-mapping products "complex-products.json:$.products[*]" --data-dir example-data
```

All examples below assume `--data-dir example-data`.

## Customer Queries

### Find Gold VIP Customers

Nested-object field access (`vipStatus.level`, `vipStatus.points`) with `ORDER BY` on a nested numeric field:

```sql
SELECT name, vipStatus.level, vipStatus.points
FROM customers
WHERE vipStatus.level = 'Gold'
ORDER BY vipStatus.points DESC
```

### Get Customers Who Opted into the Newsletter

```sql
SELECT name, preferences.newsletter, preferences.language, preferences.currency
FROM customers
WHERE preferences.newsletter = true
```

### List Every Customer Address (UNNEST)

Flatten the `addresses` array so each address becomes its own row:

```sql
SELECT name, address.type, address.city, address.state
FROM customers, UNNEST(addresses) AS a(address)
ORDER BY name, address.state
```

### Customers with a New York Address

Filter on a field of the unnested element:

```sql
SELECT name, address.street, address.city
FROM customers, UNNEST(addresses) AS a(address)
WHERE address.state = 'NY'
```

### VIP Benefits per Customer (UNNEST a string array)

```sql
SELECT name, vipStatus.level, benefit
FROM customers, UNNEST(vipStatus.benefits) AS b(benefit)
WHERE vipStatus.level = 'Gold'
ORDER BY name, benefit
```

## Order Queries

### Orders Shipped to New York

JOIN plus nested-field filtering:

```sql
SELECT o.orderId, c.name, o.orderDate, o.status
FROM orders o
JOIN customers c ON o.customerId = c.id
WHERE o.shippingAddress.state = 'NY'
```

### High-Value Orders (Over $100)

```sql
SELECT o.orderId, c.name, o.totals.total, o.status
FROM orders o
JOIN customers c ON o.customerId = c.id
WHERE o.totals.total > 100
ORDER BY o.totals.total DESC
```

### Orders That Have Tracking Information

`IS NOT NULL` on a nested field (order 1004 has a null tracking number and is excluded):

```sql
SELECT o.orderId, o.tracking.trackingNumber, o.tracking.carrier, o.tracking.estimatedDelivery
FROM orders o
WHERE o.tracking.trackingNumber IS NOT NULL
ORDER BY o.orderId
```

### Orders Still Awaiting a Tracking Number

```sql
SELECT orderId, status, orderDate
FROM orders
WHERE tracking.trackingNumber IS NULL
```

## Product Queries

### Electronics with a 5-Star Review

Alias the source table and `UNNEST` one of its array fields:

```sql
SELECT p.name, p.price, review.rating, review.user
FROM products p, UNNEST(p.reviews) AS r(review)
WHERE p.category = 'Electronics' AND review.rating = 5
```

### Products Matching Specific Tags

```sql
SELECT name, price, tag
FROM products, UNNEST(tags) AS t(tag)
WHERE tag IN ('wireless', 'gaming', 'rgb')
```

### Out-of-Stock Products

```sql
SELECT name, price, stock, category
FROM products
WHERE inStock = false OR stock = 0
```

### Distinct Product Categories and Brands

```sql
SELECT DISTINCT category FROM products
```

```sql
SELECT DISTINCT category, brand FROM products ORDER BY category, brand
```

## Complex Join Queries

### Complete Order Details

JOIN with several nested projections and aliases:

```sql
SELECT o.orderId,
       c.name AS customerName,
       c.email AS customerEmail,
       o.shippingAddress.city AS shipCity,
       o.shippingAddress.state AS shipState,
       o.totals.total AS orderTotal,
       o.status
FROM orders o
JOIN customers c ON o.customerId = c.id
ORDER BY o.totals.total DESC
```

### Line-Item Detail Across Three Sources

Combine an order, its `UNNEST`-ed line items, and the product catalog. `UNNEST` runs before the JOIN, so the unnested `item.productId` can be used as a join key:

```sql
SELECT o.orderId,
       p.name AS productName,
       p.category,
       item.quantity,
       item.totalPrice
FROM orders o
JOIN UNNEST(o.items) AS oi(item)
JOIN products p ON item.productId = p.id
WHERE p.category = 'Electronics' AND item.totalPrice > 50
ORDER BY item.totalPrice DESC
```

### Every Customer and Their Orders (LEFT JOIN)

A `LEFT JOIN` keeps customers even if a future dataset has none matching:

```sql
SELECT c.name, c.vipStatus.level, o.orderId, o.status
FROM customers c
LEFT JOIN orders o ON c.id = o.customerId
ORDER BY c.name
```

## Advanced Filtering Examples

### Recent Orders from VIP Customers

Date strings compare lexicographically, which sorts correctly for `YYYY-MM-DD`:

```sql
SELECT o.orderId, c.name, c.vipStatus.level, o.orderDate, o.totals.total
FROM orders o
JOIN customers c ON o.customerId = c.id
WHERE o.orderDate >= '2024-01-17'
  AND c.vipStatus.level IN ('Gold', 'Silver')
ORDER BY o.orderDate DESC
```

### Top 3 Orders by Value

```sql
SELECT TOP 3 o.orderId, c.name, o.totals.total
FROM orders o
JOIN customers c ON o.customerId = c.id
ORDER BY o.totals.total DESC
```

## What These Examples Demonstrate

- Nested-object navigation (`vipStatus.level`, `o.totals.total`, `o.shippingAddress.state`)
- Array flattening with `UNNEST` (string arrays like `tags`/`benefits` and object arrays like `reviews`/`items`)
- `INNER` and `LEFT` JOINs, including JOINs whose key comes from an unnested element
- Filtering, sorting, de-duplication, and limiting of richly nested data

## Planned / Not Yet Supported

The following analytics features are **not implemented yet** and will raise an error if used. They are listed here as a roadmap, not as runnable examples:

- Aggregation and grouping: `GROUP BY`, `HAVING`, `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `COUNT(DISTINCT ...)`
- Conditional expressions: `CASE WHEN ... THEN ... END`
- SQL functions: string (`UPPER`, `LOWER`, `CONCAT`, ...), date (`DATE_FORMAT`, `MONTH`, `NOW`, ...), and JSON (`JSON_CONTAINS`, `JSON_OBJECT`, `JSON_LENGTH`) functions
- Result-set composition: `UNION` / `UNION ALL`, `OFFSET`, and subqueries (in `WHERE`, `FROM`, or `SELECT`)
- Additional joins: `RIGHT JOIN`, `FULL OUTER JOIN`

For the authoritative list of supported syntax, see the [SQL Syntax Support](README.md#sql-syntax-support) section of the README. Until aggregation lands, you can pipe JsonSQL output to a tool like `jq` for counts and sums, e.g. `jsonsql --query "SELECT category FROM products" | jq 'length'`.
