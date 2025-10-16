# Complex Query Examples

This document demonstrates advanced JsonSQL capabilities using complex customer, order, and product data with nested structures and relationships.

## Data Setup

First, set up the mappings for the complex data:

```bash
# Add mappings for complex data
jsonsql --add-mapping customers "complex-customers.json:$.customers[*]"
jsonsql --add-mapping orders "complex-orders.json:$.orders[*]" 
jsonsql --add-mapping products "complex-products.json:$.products[*]"
```

## Customer Queries

### Find All Customers with Multiple Addresses

```sql
SELECT name, COUNT(*) as addressCount
FROM customers, UNNEST(addresses) AS a(address)
GROUP BY name
HAVING COUNT(*) > 1
```

### Get All Customers in New York State

```sql
SELECT name, email, phone
FROM customers, UNNEST(addresses) AS a(address)
WHERE address.state = 'NY' AND address.isDefault = true
```

### Find Gold VIP Customers

```sql
SELECT name, vipStatus.level, vipStatus.points
FROM customers
WHERE vipStatus.level = 'Gold'
ORDER BY vipStatus.points DESC
```

### Get Customer Preferences

```sql
SELECT name, 
       preferences.newsletter,
       preferences.smsUpdates,
       preferences.language,
       preferences.currency
FROM customers
WHERE preferences.newsletter = true
```

## Order Queries

### Orders Shipped to New York

```sql
SELECT o.orderId, c.name, o.orderDate, o.status
FROM orders o
JOIN customers c ON o.customerId = c.id
WHERE o.shippingAddress.state = 'NY'
```

### Orders with Free Shipping (VIP Benefit)

```sql
SELECT o.orderId, c.name, c.vipStatus.level, o.totals.shipping
FROM orders o
JOIN customers c ON o.customerId = c.id
WHERE c.vipStatus.benefits LIKE '%free-shipping%'
```

### High-Value Orders (Over $100)

```sql
SELECT o.orderId, c.name, o.totals.total, o.status
FROM orders o
JOIN customers c ON o.customerId = c.id
WHERE o.totals.total > 100
ORDER BY o.totals.total DESC
```

### Orders with Tracking Information

```sql
SELECT o.orderId, 
       o.tracking.trackingNumber,
       o.tracking.carrier,
       o.tracking.estimatedDelivery
FROM orders
WHERE o.tracking.trackingNumber IS NOT NULL
```

## Product Queries

### Electronics with High Ratings

```sql
SELECT p.name, p.price, p.category, review.rating
FROM products p, UNNEST(p.reviews) AS r(review)
WHERE p.category = 'Electronics' AND review.rating = 5
```

### Out of Stock Products

```sql
SELECT name, price, stock, category
FROM products
WHERE inStock = false OR stock = 0
```

### Products by Brand

```sql
SELECT brand, COUNT(*) as productCount, AVG(price) as avgPrice
FROM products
GROUP BY brand
ORDER BY productCount DESC
```

### Products with Specific Tags

```sql
SELECT name, price, tag
FROM products, UNNEST(tags) AS t(tag)
WHERE tag IN ('wireless', 'gaming', 'rgb')
```

## Complex Join Queries

### Complete Order Details

```sql
SELECT o.orderId,
       c.name as customerName,
       c.email as customerEmail,
       o.shippingAddress.city as shipCity,
       o.shippingAddress.state as shipState,
       o.totals.total as orderTotal,
       o.status
FROM orders o
JOIN customers c ON o.customerId = c.id
ORDER BY o.totals.total DESC
```

### Customer Order History with VIP Benefits

```sql
SELECT c.name,
       c.vipStatus.level,
       COUNT(o.orderId) as orderCount,
       SUM(o.totals.total) as totalSpent,
       c.vipStatus.benefits
FROM customers c
LEFT JOIN orders o ON c.id = o.customerId
GROUP BY c.id, c.name, c.vipStatus.level, c.vipStatus.benefits
ORDER BY totalSpent DESC
```

### Product Performance by Category

```sql
SELECT p.category,
       p.name,
       COUNT(o.orderId) as orderCount,
       SUM(oi.quantity) as totalQuantity,
       SUM(oi.totalPrice) as totalRevenue
FROM products p
LEFT JOIN orders o ON JSON_CONTAINS(o.items, JSON_OBJECT('productId', p.id))
LEFT JOIN UNNEST(o.items) AS oi(item) ON item.productId = p.id
GROUP BY p.category, p.name
ORDER BY totalRevenue DESC
```

## Address-Based Queries

### Orders by Geographic Region

```sql
SELECT o.shippingAddress.state,
       COUNT(*) as orderCount,
       AVG(o.totals.total) as avgOrderValue
FROM orders o
GROUP BY o.shippingAddress.state
ORDER BY orderCount DESC
```

### Customers with Work Addresses

```sql
SELECT c.name, 
       workAddress.street,
       workAddress.city,
       workAddress.state
FROM customers c, UNNEST(c.addresses) AS a(address)
WHERE address.type = 'work'
```

### Shipping Address Analysis

```sql
SELECT shippingAddress.city,
       shippingAddress.state,
       COUNT(*) as orderCount,
       SUM(totals.total) as totalValue
FROM orders
WHERE status = 'delivered'
GROUP BY shippingAddress.city, shippingAddress.state
ORDER BY totalValue DESC
```

## VIP Customer Analysis

### VIP Customer Spending Patterns

```sql
SELECT c.vipStatus.level,
       COUNT(DISTINCT c.id) as customerCount,
       AVG(c.vipStatus.points) as avgPoints,
       COUNT(o.orderId) as totalOrders,
       AVG(o.totals.total) as avgOrderValue
FROM customers c
LEFT JOIN orders o ON c.id = o.customerId
GROUP BY c.vipStatus.level
ORDER BY avgPoints DESC
```

### VIP Benefits Usage

```sql
SELECT c.name,
       c.vipStatus.level,
       benefit
FROM customers c, UNNEST(c.vipStatus.benefits) AS b(benefit)
WHERE c.vipStatus.level = 'Gold'
ORDER BY c.name, benefit
```

## Product Review Analysis

### Average Ratings by Category

```sql
SELECT p.category,
       AVG(review.rating) as avgRating,
       COUNT(review.rating) as reviewCount
FROM products p, UNNEST(p.reviews) AS r(review)
GROUP BY p.category
ORDER BY avgRating DESC
```

### Products with No Reviews

```sql
SELECT name, price, category
FROM products
WHERE reviews IS NULL OR JSON_LENGTH(reviews) = 0
```

## Advanced Filtering Examples

### Recent Orders from VIP Customers

```sql
SELECT o.orderId,
       c.name,
       c.vipStatus.level,
       o.orderDate,
       o.totals.total
FROM orders o
JOIN customers c ON o.customerId = c.id
WHERE o.orderDate >= '2024-01-01' 
  AND c.vipStatus.level IN ('Gold', 'Silver')
ORDER BY o.orderDate DESC
```

### High-Value Electronics Orders

```sql
SELECT o.orderId,
       c.name,
       p.name as productName,
       p.category,
       oi.quantity,
       oi.totalPrice
FROM orders o
JOIN customers c ON o.customerId = c.id
JOIN UNNEST(o.items) AS oi(item)
JOIN products p ON item.productId = p.id
WHERE p.category = 'Electronics' 
  AND item.totalPrice > 50
ORDER BY item.totalPrice DESC
```

### Customers with Multiple Order Types

```sql
SELECT c.name,
       COUNT(DISTINCT o.status) as statusTypes,
       GROUP_CONCAT(DISTINCT o.status) as orderStatuses
FROM customers c
JOIN orders o ON c.id = o.customerId
GROUP BY c.id, c.name
HAVING COUNT(DISTINCT o.status) > 1
```

## Summary Queries

### Monthly Sales Summary

```sql
SELECT DATE_FORMAT(orderDate, '%Y-%m') as month,
       COUNT(*) as orderCount,
       SUM(totals.total) as totalRevenue,
       AVG(totals.total) as avgOrderValue
FROM orders
GROUP BY DATE_FORMAT(orderDate, '%Y-%m')
ORDER BY month DESC
```

### Customer Segmentation

```sql
SELECT CASE 
         WHEN vipStatus.level = 'Gold' THEN 'High Value'
         WHEN vipStatus.level = 'Silver' THEN 'Medium Value'
         WHEN vipStatus.level = 'Bronze' THEN 'Low Value'
         ELSE 'New Customer'
       END as segment,
       COUNT(*) as customerCount,
       AVG(vipStatus.points) as avgPoints
FROM customers
GROUP BY segment
ORDER BY avgPoints DESC
```

These examples demonstrate the power of JsonSQL for complex data analysis, including:

- Nested object navigation
- Array flattening with UNNEST
- Complex JOINs across multiple data sources
- Geographic and demographic analysis
- Business intelligence queries
- Customer segmentation and behavior analysis
