# JsonSQL Quick Start Guide

This guide demonstrates all the different ways to map and query JSON data using the example data included in this project.

## Setup

First, build the project:
```bash
mvn clean package
```

Create an alias for easier usage:
```bash
alias jsonsql='java -jar target/jsonsql-1.0.0.jar'
```

## Scenario 1: Simple Auto-Detect (Default)

**When:** You have separate files named the same as your table names.

**Files:**
- `example-data/products.json` with structure `$.data.products`
- `example-data/orders.json` with structure `$.orders`

**Setup:**
```bash
jsonsql --add-mapping products "$.data.products"
jsonsql --add-mapping orders "$.orders"
```

**Query:**
```bash
# Query products
jsonsql --query "SELECT * FROM products WHERE category = 'Tools'" --data-dir example-data --pretty

# Query orders  
jsonsql --query "SELECT * FROM orders WHERE status = 'active'" --data-dir example-data --pretty

# JOIN them
jsonsql --query "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id" --data-dir example-data --pretty
```

## Scenario 2: Explicit File Specification

**When:** You want to be explicit about which file to use.

**Setup:**
```bash
jsonsql --add-mapping specified_file_products "products.json:$.data.products"
jsonsql --add-mapping specified_file_orders "orders.json:$.orders"
```

**Query:**
```bash
jsonsql --query "SELECT name, price FROM specified_file_products ORDER BY price DESC" --data-dir example-data --pretty
```

## Scenario 3: Multiple Tables from One File

**When:** You have several data collections in a single JSON file.

**File:** `example-data/ecommerce.json` contains both products and orders

**Setup:**
```bash
jsonsql --add-mapping ecommerce_products "ecommerce.json:$.store.data.products"
jsonsql --add-mapping ecommerce_orders "ecommerce.json:$.store.data.orders"
```

**Query:**
```bash
# Query products from ecommerce.json
jsonsql --query "SELECT TOP 5 name, price, brand FROM ecommerce_products ORDER BY price DESC" --data-dir example-data --pretty

# Query orders from same file
jsonsql --query "SELECT orderId, status, totalPrice FROM ecommerce_orders WHERE status = 'processing'" --data-dir example-data --pretty

# JOIN tables from the same file
jsonsql --query "SELECT p.name, p.brand, o.quantity, o.totalPrice FROM ecommerce_orders o JOIN ecommerce_products p ON o.productId = p.id WHERE o.status = 'delivered' ORDER BY o.totalPrice DESC" --data-dir example-data --pretty
```

## Scenario 4: Partitioned Data (Multiple Files per Table)

**When:** Your data is split across multiple files for scalability.

**Directory Structure:**
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

**Setup:**
```bash
# Map to directory - automatically loads ALL .json files
jsonsql --add-mapping products_multi "products-multi:$.products"
jsonsql --add-mapping orders_multi "orders-multi:$.orders"
```

**Query:**
```bash
# Query all products from all 3 files (combines them)
jsonsql --query "SELECT * FROM products_multi ORDER BY year, price" --data-dir example-data --pretty

# Query all orders from both Q1 and Q2 files
jsonsql --query "SELECT * FROM orders_multi WHERE status = 'delivered'" --data-dir example-data --pretty

# JOIN across partitioned files
jsonsql --query "SELECT p.name, p.year, o.quantity, o.quarter FROM orders_multi o JOIN products_multi p ON o.productId = p.id ORDER BY p.year, o.quarter" --data-dir example-data --pretty

# Complex query with filters
jsonsql --query "SELECT TOP 5 p.name, p.price, o.quantity FROM orders_multi o JOIN products_multi p ON o.productId = p.id WHERE p.year >= 2024 ORDER BY p.price DESC" --data-dir example-data --pretty
```

## View All Configured Mappings

```bash
jsonsql --list-tables
```

Output:
```
Configured JSONPath Mappings:
────────────────────────────────────────────────────────────────────────────────
  ecommerce_orders           ->  ecommerce.json:$.store.data.orders
  ecommerce_products         ->  ecommerce.json:$.store.data.products
  orders                     ->  $.orders
  orders_multi               ->  orders-multi:$.orders
  products                   ->  $.data.products
  products_multi             ->  products-multi:$.products
  specified_file_orders      ->  orders.json:$.orders
  specified_file_products    ->  products.json:$.data.products
────────────────────────────────────────────────────────────────────────────────
Total: 8 mapping(s)
```

## Comparison of Mapping Formats

| Mapping Name | Format | Description | Files Loaded |
|--------------|--------|-------------|--------------|
| `products` | `$.data.products` | Auto-detect filename | `products.json` |
| `specified_file_products` | `products.json:$.data.products` | Explicit single file | `products.json` |
| `ecommerce_products` | `ecommerce.json:$.store.data.products` | Specific file, different from table name | `ecommerce.json` |
| `products_multi` | `products-multi:$.products` | Directory of files | All `.json` in `products-multi/` (3 files) |

## Real-World Examples

### Example 1: Find Top Products by Orders

```bash
jsonsql --query "SELECT TOP 10 p.name, COUNT(*) as order_count FROM ecommerce_orders o JOIN ecommerce_products p ON o.productId = p.id ORDER BY order_count DESC" --data-dir example-data --pretty
```

### Example 2: Analyze Multi-Year Data

```bash
jsonsql --query "SELECT year, name, price FROM products_multi WHERE price > 30 ORDER BY year DESC, price DESC" --data-dir example-data --pretty
```

### Example 3: Cross-Quarter Order Analysis

```bash
jsonsql --query "SELECT p.name, p.year, o.quarter, o.quantity FROM orders_multi o JOIN products_multi p ON o.productId = p.id WHERE o.status = 'delivered' ORDER BY o.quarter, p.year" --data-dir example-data --pretty
```

### Example 4: Combining Different Scenarios

You can even mix mappings from different scenarios in one query!

```bash
# JOIN simple products with multi-file orders
jsonsql --query "SELECT p.name, o.quarter FROM orders_multi o JOIN products p ON o.productId = p.id" --data-dir example-data --pretty

# JOIN ecommerce products with simple orders
jsonsql --query "SELECT p.brand, o.status FROM orders o JOIN ecommerce_products p ON o.productId = p.id" --data-dir example-data --pretty
```

## Tips

1. **Choose the right format for your use case:**
   - Use simple format (`$.path`) when filename = table name
   - Use explicit format (`file.json:$.path`) for clarity or when names differ
   - Use directory format (`directory:$.path`) for partitioned data

2. **Name your mappings descriptively:**
   - `products` for the main/default products table
   - `products_multi` for partitioned products
   - `ecommerce_products` for products from ecommerce.json

3. **Test mappings before complex queries:**
   ```bash
   jsonsql --list-tables
   jsonsql --query "SELECT TOP 1 * FROM <table_name>" --data-dir example-data
   ```

4. **Use ORDER BY with multi-file queries:**
   Results from multiple files may not be in a predictable order without explicit sorting.

## Next Steps

- Try modifying the example data
- Add your own JSON files
- Experiment with different JSONPath expressions
- Build more complex queries

For more details, see:
- `README.md` - Complete documentation
- `EXAMPLES.md` - Basic usage examples
- `MULTI-FILE-EXAMPLES.md` - Advanced multi-file scenarios

