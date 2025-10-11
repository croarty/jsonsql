# Multi-File Querying Examples

JsonSQL supports powerful multi-file querying scenarios, allowing you to work with partitioned data spread across multiple files or directories.

## Scenario 1: Multiple Tables in One File

When you have several data collections in a single JSON file:

**File: `ecommerce.json`**
```json
{
  "store": {
    "data": {
      "products": [...],
      "orders": [...],
      "customers": [...]
    }
  }
}
```

**Setup:**
```bash
jsonsql --add-mapping products "ecommerce.json:$.store.data.products"
jsonsql --add-mapping orders "ecommerce.json:$.store.data.orders"
jsonsql --add-mapping customers "ecommerce.json:$.store.data.customers"
```

**Query:**
```bash
jsonsql --query "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id" --data-dir data
```

## Scenario 2: Partitioned Data by Year

When your data is split across multiple files for scalability:

**Directory Structure:**
```
data/
  └── products/
      ├── products_2023.json
      ├── products_2024.json
      └── products_2025.json
```

Each file has the same structure:
```json
{
  "year": 2023,
  "products": [
    {"id": 1, "name": "Widget 2023", "price": 19.99, "year": 2023},
    {"id": 2, "name": "Gadget 2023", "price": 29.99, "year": 2023}
  ]
}
```

**Setup:**
```bash
# Map to the directory - automatically loads ALL .json files
jsonsql --add-mapping all_products "products:$.products" --data-dir data
```

**Queries:**
```bash
# Get all products from all years
jsonsql --query "SELECT * FROM all_products ORDER BY year, price" --data-dir data

# Filter across all files
jsonsql --query "SELECT * FROM all_products WHERE year >= 2024 ORDER BY price DESC" --data-dir data

# Count products by year
jsonsql --query "SELECT year, name FROM all_products ORDER BY year" --data-dir data
```

## Scenario 3: Partitioned Data by Quarter

**Directory Structure:**
```
data/
  ├── products/
  │   ├── products_2023.json
  │   ├── products_2024.json
  │   └── products_2025.json
  └── orders/
      ├── orders_q1.json
      ├── orders_q2.json
      ├── orders_q3.json
      └── orders_q4.json
```

**Setup:**
```bash
jsonsql --add-mapping products "products:$.products" --data-dir data
jsonsql --add-mapping orders "orders:$.orders" --data-dir data
```

**Queries:**
```bash
# JOIN across partitioned files
jsonsql --query "SELECT p.name, o.quantity, o.quarter FROM orders o JOIN products p ON o.productId = p.id ORDER BY o.quarter" --data-dir data

# Filter orders and join with products
jsonsql --query "SELECT p.name, p.price, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE o.status = 'delivered' ORDER BY p.price DESC" --data-dir data

# Get summary data
jsonsql --query "SELECT TOP 10 p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id ORDER BY o.quantity DESC" --data-dir data
```

## Scenario 4: Different Tables in Different Directories

**Directory Structure:**
```
data/
  ├── customer-data/
  │   └── customers.json
  ├── product-catalog/
  │   ├── electronics.json
  │   ├── furniture.json
  │   └── accessories.json
  └── transaction-logs/
      └── orders.json
```

**Setup:**
```bash
# Single file with relative path
jsonsql --add-mapping customers "customer-data/customers.json:$.customers" --data-dir data

# Directory with multiple files (combines all)
jsonsql --add-mapping products "product-catalog:$.products" --data-dir data

# Another single file
jsonsql --add-mapping orders "transaction-logs/orders.json:$.orders" --data-dir data
```

**Queries:**
```bash
# Query from specific subdirectory
jsonsql --query "SELECT * FROM customers" --data-dir data

# Query combining multiple files from product-catalog/
jsonsql --query "SELECT * FROM products ORDER BY category, name" --data-dir data

# JOIN across different directories
jsonsql --query "SELECT c.name, o.orderDate, p.name FROM orders o JOIN customers c ON o.customerId = c.id JOIN products p ON o.productId = p.id" --data-dir data
```

## Scenario 5: Large-Scale Partitioned Data

For very large datasets split across many files:

**Directory Structure:**
```
sales-data/
  ├── 2024/
  │   ├── products/
  │   │   ├── products_jan.json
  │   │   ├── products_feb.json
  │   │   └── ... (12 files)
  │   └── orders/
  │       ├── orders_jan.json
  │       ├── orders_feb.json
  │       └── ... (12 files)
  └── 2025/
      └── ...
```

**Setup:**
```bash
# Load all products from 2024
jsonsql --add-mapping products_2024 "2024/products:$.products" --data-dir sales-data

# Load all orders from 2024
jsonsql --add-mapping orders_2024 "2024/orders:$.orders" --data-dir sales-data

# Load all products from 2025
jsonsql --add-mapping products_2025 "2025/products:$.products" --data-dir sales-data
```

**Queries:**
```bash
# Analyze 2024 sales
jsonsql --query "SELECT p.name, SUM(o.quantity) as total_sold FROM orders_2024 o JOIN products_2024 p ON o.productId = p.id ORDER BY total_sold DESC" --data-dir sales-data

# Get top products by revenue
jsonsql --query "SELECT TOP 20 p.name, o.quantity, o.totalPrice FROM orders_2024 o JOIN products_2024 p ON o.productId = p.id ORDER BY o.totalPrice DESC" --data-dir sales-data --pretty
```

## Key Benefits

### 1. **Scalability**
Split large datasets across multiple files for easier management and faster loading.

### 2. **Flexibility**
- Query individual files or entire directories
- Mix and match different file organizations
- No need to merge files manually

### 3. **Performance**
- Only loads files you need
- Same JSONPath applied to all files in a directory
- Efficient combination of data from multiple sources

### 4. **Organization**
- Keep data organized by time period (year, quarter, month)
- Separate by category, region, or any logical partition
- Maintain clean directory structures

## Tips for Multi-File Setups

1. **Consistent Structure**: Ensure all files in a directory have the same JSON structure
2. **Same JSONPath**: Use the same JSONPath expression for all files in a directory mapping
3. **Naming Convention**: Use clear file naming (e.g., `products_2024_01.json`)
4. **Test First**: Test with a small subset of files before running on all data
5. **Use ORDER BY**: Sort results from multiple files for predictable output
6. **Use TOP/LIMIT**: Limit results when querying large partitioned datasets

## Example Workflow

```bash
# 1. Set up your directory structure
mkdir -p data/products data/orders

# 2. Place your JSON files
# (copy product files to data/products/)
# (copy order files to data/orders/)

# 3. Configure mappings
jsonsql --add-mapping products "products:$.products" --data-dir data
jsonsql --add-mapping orders "orders:$.orders" --data-dir data

# 4. Verify mappings
jsonsql --list-tables

# 5. Run queries
jsonsql --query "SELECT * FROM products ORDER BY id" --data-dir data --pretty

# 6. JOIN across partitioned data
jsonsql --query "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE o.status = 'active' ORDER BY o.orderDate DESC" --data-dir data --pretty
```

## Mapping Format Summary

| Format | Description | Example |
|--------|-------------|---------|
| `$.path` | Single file, filename = table name | `"$.products"` → looks for `products.json` |
| `file.json:$.path` | Specific file | `"ecommerce.json:$.data.products"` |
| `directory:$.path` | All .json files in directory | `"products-archive:$.products"` |
| `path/file.json:$.path` | Relative path to file | `"2024/products.json:$.data"` |
| `path/dir:$.path` | Relative path to directory | `"archive/products:$.items"` |

All paths are relative to the `--data-dir` directory (default: current directory).

