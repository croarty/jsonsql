# Complex WHERE Clause Examples

JsonSQL now supports full boolean logic in WHERE clauses, including `AND`, `OR`, `NOT`, and parentheses for grouping. This document provides comprehensive examples of the complex WHERE clause functionality.

## Table of Contents

- [Simple Conditions](#simple-conditions)
- [AND Operator](#and-operator)
- [OR Operator](#or-operator)
- [NOT Operator](#not-operator)
- [Parentheses for Grouping](#parentheses-for-grouping)
- [Complex Nested Conditions](#complex-nested-conditions)
- [WHERE with JOINs](#where-with-joins)
- [WHERE with ORDER BY](#where-with-order-by)
- [WHERE with TOP/LIMIT](#where-with-toplimit)
- [Best Practices](#best-practices)

## Simple Conditions

Basic WHERE clauses with single conditions:

```bash
# Equality
jsonsql --query "SELECT * FROM products WHERE category = 'Electronics'"

# Greater than
jsonsql --query "SELECT * FROM products WHERE price > 100"

# Less than or equal
jsonsql --query "SELECT * FROM products WHERE stock <= 10"

# Not equal
jsonsql --query "SELECT * FROM products WHERE status != 'discontinued'"

# Boolean field
jsonsql --query "SELECT * FROM products WHERE inStock = true"
```

## AND Operator

Combine multiple conditions that must ALL be true:

```bash
# Two conditions
jsonsql --query "SELECT * FROM products WHERE category = 'Electronics' AND price > 50"

# Three conditions
jsonsql --query "SELECT * FROM products WHERE category = 'Electronics' AND price > 50 AND inStock = true"

# Multiple ANDs with different data types
jsonsql --query "SELECT * FROM products WHERE category = 'Furniture' AND price >= 100 AND stock > 0"
```

## OR Operator

Combine conditions where ANY condition can be true:

```bash
# Two alternatives
jsonsql --query "SELECT * FROM products WHERE category = 'Furniture' OR category = 'Electronics'"

# Multiple ORs
jsonsql --query "SELECT * FROM products WHERE id = 1 OR id = 3 OR id = 5"

# OR with different field comparisons
jsonsql --query "SELECT * FROM products WHERE price < 30 OR stock < 5"
```

## NOT Operator

Negate a condition or expression:

```bash
# Simple NOT
jsonsql --query "SELECT * FROM products WHERE NOT inStock = false"

# NOT with comparison
jsonsql --query "SELECT * FROM products WHERE NOT price > 1000"

# NOT with complex expression
jsonsql --query "SELECT * FROM products WHERE NOT (category = 'Electronics' AND price > 500)"

# NOT with OR
jsonsql --query "SELECT * FROM products WHERE NOT (category = 'Furniture' OR price < 50)"
```

## Parentheses for Grouping

Control evaluation order and create complex logical expressions:

```bash
# Basic grouping
jsonsql --query "SELECT * FROM products WHERE (category = 'Electronics' AND price > 100) OR category = 'Furniture'"

# Multiple groups
jsonsql --query "SELECT * FROM products WHERE (category = 'Electronics' OR category = 'Furniture') AND (price >= 100 AND price <= 500)"

# Nested groups
jsonsql --query "SELECT * FROM products WHERE ((category = 'Electronics' AND price > 100) OR (category = 'Furniture' AND price < 200)) AND inStock = true"
```

## Complex Nested Conditions

Real-world examples with deep nesting:

```bash
# Nested ANDs and ORs
jsonsql --query "SELECT * FROM products WHERE (category = 'Electronics' AND (price < 100 OR price > 1000)) OR (category = 'Furniture' AND inStock = true)"

# Triple nesting with NOT
jsonsql --query "SELECT * FROM products WHERE NOT ((category = 'Electronics' AND price < 50) OR (category = 'Furniture' AND stock < 10))"

# Complex business logic
jsonsql --query "SELECT * FROM products WHERE (category = 'Electronics' AND ((price >= 100 AND price <= 500) OR (stock > 50))) AND inStock = true"

# Multiple NOTs
jsonsql --query "SELECT * FROM products WHERE NOT (category = 'Electronics') AND NOT (price > 500) AND inStock = true"
```

## WHERE with JOINs

Combine complex WHERE clauses with JOIN operations:

```bash
# JOIN with AND conditions
jsonsql --query "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE (o.status = 'completed' AND o.quantity > 1) OR p.category = 'Electronics'"

# Multiple JOINs with complex WHERE
jsonsql --query "SELECT o.orderId, p.name, c.name FROM orders o JOIN products p ON o.productId = p.id JOIN customers c ON o.customerId = c.id WHERE (o.status = 'completed' OR o.status = 'processing') AND p.price > 50"

# LEFT JOIN with nested conditions
jsonsql --query "SELECT p.name, o.quantity FROM products p LEFT JOIN orders o ON p.id = o.productId WHERE (p.category = 'Electronics' AND p.inStock = true) OR (o.status = 'pending' AND o.quantity > 2)"
```

## WHERE with ORDER BY

Sort results after complex filtering:

```bash
# Complex WHERE with ORDER BY
jsonsql --query "SELECT * FROM products WHERE (category = 'Electronics' OR category = 'Furniture') AND inStock = true ORDER BY price DESC"

# Multiple conditions sorted
jsonsql --query "SELECT * FROM products WHERE (price > 50 AND price < 500) OR (stock < 10 AND inStock = true) ORDER BY category, price"

# NOT condition with ORDER BY
jsonsql --query "SELECT * FROM products WHERE NOT (category = 'Electronics' AND price > 500) ORDER BY price ASC"
```

## WHERE with TOP/LIMIT

Limit results after complex filtering:

```bash
# TOP with complex WHERE
jsonsql --query "SELECT TOP 5 * FROM products WHERE (category = 'Electronics' OR category = 'Furniture') AND price > 100"

# LIMIT with nested conditions
jsonsql --query "SELECT * FROM products WHERE (category = 'Electronics' AND (price < 100 OR price > 1000)) LIMIT 10"

# Combining TOP, ORDER BY, and complex WHERE
jsonsql --query "SELECT TOP 3 * FROM products WHERE (price >= 100 AND price <= 500) AND inStock = true ORDER BY price DESC"
```

## Best Practices

### 1. Use Parentheses for Clarity

Even when not strictly required, parentheses make complex queries easier to read:

```bash
# Good - clear intent
jsonsql --query "SELECT * FROM products WHERE (category = 'Electronics' AND price > 50) OR (category = 'Furniture')"

# Less clear - same result but harder to read
jsonsql --query "SELECT * FROM products WHERE category = 'Electronics' AND price > 50 OR category = 'Furniture'"
```

### 2. Group Related Conditions

Keep logically related conditions together:

```bash
# Good - related price conditions grouped
jsonsql --query "SELECT * FROM products WHERE category = 'Electronics' AND (price >= 100 AND price <= 500)"
```

### 3. Use NOT Sparingly

Sometimes a positive condition is clearer:

```bash
# Consider if this is clearer for your use case
jsonsql --query "SELECT * FROM products WHERE inStock = true"

# vs
jsonsql --query "SELECT * FROM products WHERE NOT inStock = false"
```

### 4. Test Complex Queries Incrementally

Build complex queries step by step:

```bash
# Start simple
jsonsql --query "SELECT * FROM products WHERE category = 'Electronics'"

# Add conditions gradually
jsonsql --query "SELECT * FROM products WHERE category = 'Electronics' AND price > 50"

# Add more complexity
jsonsql --query "SELECT * FROM products WHERE (category = 'Electronics' AND price > 50) OR inStock = false"
```

### 5. Consider Performance

While JsonSQL evaluates conditions efficiently with short-circuit logic, consider the order of conditions:

```bash
# Better - likely false condition first (short-circuits faster)
jsonsql --query "SELECT * FROM products WHERE category = 'RareCategory' AND price > 50"

# vs checking common condition first
jsonsql --query "SELECT * FROM products WHERE price > 50 AND category = 'RareCategory'"
```

## Supported Operators

### Logical Operators
- `AND` - Both conditions must be true
- `OR` - At least one condition must be true
- `NOT` - Negates a condition

### Comparison Operators
- `=` - Equals
- `!=` - Not equals
- `>` - Greater than
- `<` - Less than
- `>=` - Greater than or equal to
- `<=` - Less than or equal to

### Pattern Matching
- `LIKE` - Pattern matching with wildcards
  - `%` - Matches zero or more characters
  - `_` - Matches exactly one character
- `NOT LIKE` - Negated pattern matching

### Grouping
- `()` - Parentheses for grouping and precedence

## LIKE Pattern Matching Examples

### Basic LIKE Patterns

```bash
# Starts with "Laptop"
jsonsql --query "SELECT * FROM products WHERE name LIKE 'Laptop%'"

# Ends with "Cable"
jsonsql --query "SELECT * FROM products WHERE name LIKE '%Cable'"

# Contains "Desk" anywhere
jsonsql --query "SELECT * FROM products WHERE name LIKE '%Desk%'"

# Exact match (no wildcards)
jsonsql --query "SELECT * FROM products WHERE name LIKE 'Laptop'"
```

### Single Character Wildcard (_)

```bash
# Matches "Desk Lamp" (one character between "Desk" and "amp")
jsonsql --query "SELECT * FROM products WHERE name LIKE 'Desk _amp'"

# Match 3-letter category starting with 'F' ending with 'rniture'
jsonsql --query "SELECT * FROM products WHERE category LIKE 'F__niture'"
```

### Combined Wildcards

```bash
# Complex pattern with both % and _
jsonsql --query "SELECT * FROM products WHERE name LIKE 'L%p_o%'"

# Multiple wildcards
jsonsql --query "SELECT * FROM products WHERE name LIKE '%a%a%'"
```

### NOT LIKE

```bash
# Exclude products containing "Monitor"
jsonsql --query "SELECT * FROM products WHERE name NOT LIKE '%Monitor%'"

# Exclude items starting with "Wireless"
jsonsql --query "SELECT * FROM products WHERE name NOT LIKE 'Wireless%'"
```

### LIKE with Complex WHERE Clauses

```bash
# LIKE with AND
jsonsql --query "SELECT * FROM products WHERE name LIKE '%Desk%' AND category = 'Furniture'"

# LIKE with OR
jsonsql --query "SELECT * FROM products WHERE name LIKE 'Laptop%' OR name LIKE '%Mouse%'"

# Multiple LIKE conditions
jsonsql --query "SELECT * FROM products WHERE (name LIKE '%Computer%' OR name LIKE '%Laptop%') AND category = 'Electronics'"

# NOT LIKE with AND
jsonsql --query "SELECT * FROM products WHERE name NOT LIKE '%Laptop%' AND category = 'Electronics'"

# LIKE with NOT and parentheses
jsonsql --query "SELECT * FROM products WHERE NOT (name LIKE '%Monitor%' OR name LIKE '%Display%')"
```

### LIKE with Other SQL Features

```bash
# LIKE with ORDER BY
jsonsql --query "SELECT * FROM products WHERE name LIKE '%e%' ORDER BY name ASC"

# LIKE with TOP/LIMIT
jsonsql --query "SELECT TOP 5 * FROM products WHERE name LIKE '%a%'"

# LIKE with JOIN
jsonsql --query "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE p.name LIKE '%Chair%'"
```

### Special Characters in LIKE

```bash
# Match special characters (they are automatically escaped)
jsonsql --query "SELECT * FROM products WHERE name LIKE '%2.0%'"

# Match hyphens
jsonsql --query "SELECT * FROM products WHERE name LIKE '%USB-C%'"

# Match parentheses
jsonsql --query "SELECT * FROM products WHERE name LIKE '%(%)%'"
```

### LIKE Pattern Tips

1. **% matches everything**: `LIKE '%'` matches all non-null strings
2. **Case-sensitive**: `LIKE 'laptop%'` will NOT match "Laptop"
3. **Empty pattern**: `LIKE ''` matches only empty strings
4. **Wildcard positioning matters**:
   - `'%text'` - ends with text
   - `'text%'` - starts with text
   - `'%text%'` - contains text
   - `'%te%xt%'` - contains "te" followed by "xt"
5. **Single vs multiple wildcards**:
   - `'_ext'` - exactly 4 characters ending in "ext"
   - `'%ext'` - any number of characters ending in "ext"

## Technical Details

### Short-Circuit Evaluation

JsonSQL uses short-circuit evaluation for efficiency:

- **AND**: If the left condition is false, the right condition is not evaluated
- **OR**: If the left condition is true, the right condition is not evaluated

### Operator Precedence

Following standard SQL rules:
1. Parentheses `()`
2. `NOT`
3. `AND`
4. `OR`

### Data Type Handling

- **Numbers**: Compared numerically (e.g., `10 < 100`)
- **Strings**: Compared lexicographically (e.g., `'Apple' < 'Banana'`)
- **Booleans**: `true` and `false` values
- **Null**: Null values evaluate to false in comparisons

## Examples with Real Data

Using the example data from the JsonSQL repository:

```bash
# Electronics over $100 or Tools under $50
jsonsql --query "SELECT * FROM ecommerce_products WHERE (category = 'Electronics' AND price > 100) OR (category = 'Tools' AND price < 50)" --data-dir example-data --pretty

# Non-Electronics items in price range
jsonsql --query "SELECT name, price, category FROM ecommerce_products WHERE NOT (category = 'Electronics') AND (price >= 100 AND price <= 500) ORDER BY price DESC" --data-dir example-data --pretty

# Complex order query with JOIN
jsonsql --query "SELECT o.orderId, p.name, p.price FROM ecommerce_orders o JOIN ecommerce_products p ON o.productId = p.id WHERE (o.status = 'completed' OR o.status = 'processing') AND p.price > 50 ORDER BY p.price DESC" --data-dir example-data --pretty
```

## Summary

Complex WHERE clauses in JsonSQL provide powerful filtering capabilities:

✅ **Full Boolean Logic**: AND, OR, NOT operators
✅ **Pattern Matching**: LIKE and NOT LIKE with % and _ wildcards
✅ **Unlimited Nesting**: Parentheses for any complexity level
✅ **Short-Circuit Evaluation**: Efficient query execution
✅ **Standard SQL Semantics**: Familiar operator precedence
✅ **Works with All Features**: JOINs, ORDER BY, TOP/LIMIT
✅ **Type-Safe**: Proper handling of numbers, strings, and booleans
✅ **Special Character Handling**: Automatic escaping of regex special characters in LIKE patterns

For more examples and documentation, see:
- [README.md](README.md) - Main documentation
- [EXAMPLES.md](EXAMPLES.md) - Basic query examples
- [MULTI-FILE-EXAMPLES.md](MULTI-FILE-EXAMPLES.md) - Multi-file data examples

