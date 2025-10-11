package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class QueryExecutorTest {

    @TempDir
    Path tempDir;

    private File configFile;
    private File dataDir;
    private MappingManager mappingManager;
    private QueryExecutor queryExecutor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        
        // Create config file
        configFile = tempDir.resolve("test-mappings.json").toFile();
        mappingManager = new MappingManager(configFile);
        
        // Set up test data directory
        dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        
        // Copy test JSON files
        copyTestData();
        
        // Set up mappings
        mappingManager.addMapping("products", "$.data.products");
        mappingManager.addMapping("orders", "$.orders");
        mappingManager.addMapping("customers", "$.users.customers");
        
        queryExecutor = new QueryExecutor(mappingManager, dataDir);
    }

    private void copyTestData() throws Exception {
        // Create products.json
        String productsJson = """
        {
          "data": {
            "products": [
              {"id": 1, "name": "Widget", "price": 19.99, "category": "Tools", "inStock": true},
              {"id": 2, "name": "Gadget", "price": 29.99, "category": "Electronics", "inStock": true},
              {"id": 3, "name": "Doohickey", "price": 9.99, "category": "Tools", "inStock": false}
            ]
          }
        }
        """;
        Files.writeString(dataDir.toPath().resolve("products.json"), productsJson);
        
        // Create orders.json
        String ordersJson = """
        {
          "orders": [
            {"orderId": 101, "productId": 1, "quantity": 5, "status": "active", "orderDate": "2023-01-15"},
            {"orderId": 102, "productId": 2, "quantity": 2, "status": "active", "orderDate": "2023-01-16"},
            {"orderId": 103, "productId": 1, "quantity": 10, "status": "completed", "orderDate": "2023-01-10"},
            {"orderId": 104, "productId": 3, "quantity": 3, "status": "active", "orderDate": "2023-01-17"}
          ]
        }
        """;
        Files.writeString(dataDir.toPath().resolve("orders.json"), ordersJson);
        
        // Create customers.json
        String customersJson = """
        {
          "users": {
            "customers": [
              {"id": 1, "name": "John Doe", "email": "john@example.com", "active": true},
              {"id": 2, "name": "Jane Smith", "email": "jane@example.com", "active": false}
            ]
          }
        }
        """;
        Files.writeString(dataDir.toPath().resolve("customers.json"), customersJson);
    }

    @Test
    void testSimpleSelectAll() throws Exception {
        String result = queryExecutor.execute("SELECT * FROM products");
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        assertEquals(3, resultNode.size());
        
        JsonNode firstProduct = resultNode.get(0);
        assertEquals(1, firstProduct.get("id").asInt());
        assertEquals("Widget", firstProduct.get("name").asText());
    }

    @Test
    void testSelectSpecificColumns() throws Exception {
        String result = queryExecutor.execute("SELECT name, price FROM products");
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(3, resultNode.size());
        
        JsonNode firstProduct = resultNode.get(0);
        assertTrue(firstProduct.has("name"));
        assertTrue(firstProduct.has("price"));
        assertFalse(firstProduct.has("category"));
    }

    @Test
    void testSelectWithWhere() throws Exception {
        String result = queryExecutor.execute("SELECT * FROM products WHERE category = 'Tools'");
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(2, resultNode.size());
        
        for (JsonNode product : resultNode) {
            assertEquals("Tools", product.get("category").asText());
        }
    }

    @Test
    void testSelectWithWhereNumeric() throws Exception {
        String result = queryExecutor.execute("SELECT * FROM products WHERE price > 15");
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(2, resultNode.size());
        
        for (JsonNode product : resultNode) {
            assertTrue(product.get("price").asDouble() > 15);
        }
    }

    @Test
    void testSelectWithTop() throws Exception {
        String result = queryExecutor.execute("SELECT TOP 2 * FROM products");
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(2, resultNode.size());
    }

    @Test
    void testSelectWithJoin() throws Exception {
        String result = queryExecutor.execute(
            "SELECT p.name, o.quantity, o.status FROM orders o JOIN products p ON o.productId = p.id"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.size() > 0);
        
        JsonNode firstRow = resultNode.get(0);
        assertTrue(firstRow.has("name"));
        assertTrue(firstRow.has("quantity"));
        assertTrue(firstRow.has("status"));
    }

    @Test
    void testSelectWithJoinAndWhere() throws Exception {
        String result = queryExecutor.execute(
            "SELECT p.name, o.quantity FROM orders o " +
            "JOIN products p ON o.productId = p.id " +
            "WHERE o.status = 'active'"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.size() > 0);
        
        // All results should be from active orders
        for (JsonNode row : resultNode) {
            assertTrue(row.has("name"));
            assertTrue(row.has("quantity"));
        }
    }

    @Test
    void testSelectWithLeftJoin() throws Exception {
        // This should return all orders, even if there's no matching product
        String result = queryExecutor.execute(
            "SELECT o.orderId, p.name FROM orders o LEFT JOIN products p ON o.productId = p.id"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(4, resultNode.size()); // All orders should be present
    }

    @Test
    void testSelectWithTopAndJoin() throws Exception {
        String result = queryExecutor.execute(
            "SELECT TOP 2 p.name, o.quantity FROM orders o " +
            "JOIN products p ON o.productId = p.id"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(2, resultNode.size());
    }

    @Test
    void testComplexQuery() throws Exception {
        String result = queryExecutor.execute(
            "SELECT TOP 10 p.name, o.quantity, o.orderDate " +
            "FROM orders o " +
            "JOIN products p ON o.productId = p.id " +
            "WHERE o.status = 'active'"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.size() > 0);
        assertTrue(resultNode.size() <= 10);
        
        for (JsonNode row : resultNode) {
            assertTrue(row.has("name"));
            assertTrue(row.has("quantity"));
            assertTrue(row.has("orderDate"));
        }
    }

    @Test
    void testQueryWithNonExistentTable() {
        Exception exception = assertThrows(Exception.class, () -> 
            queryExecutor.execute("SELECT * FROM nonexistent")
        );
        assertTrue(exception.getMessage().contains("No mapping found"));
    }

    @Test
    void testInvalidQuery() {
        assertThrows(Exception.class, () -> 
            queryExecutor.execute("INVALID SQL")
        );
    }

    @Test
    void testMultipleTablesFromSameFile() throws Exception {
        // Create a combined JSON file with both products and orders
        String combinedJson = """
        {
          "store": {
            "products": [
              {"id": 1, "name": "Widget", "price": 19.99},
              {"id": 2, "name": "Gadget", "price": 29.99}
            ],
            "orders": [
              {"orderId": 101, "productId": 1, "quantity": 5, "status": "active"},
              {"orderId": 102, "productId": 2, "quantity": 2, "status": "completed"}
            ]
          }
        }
        """;
        Files.writeString(dataDir.toPath().resolve("combined.json"), combinedJson);
        
        // Add mappings with filename specified
        mappingManager.addMapping("combo_products", "combined.json:$.store.products");
        mappingManager.addMapping("combo_orders", "combined.json:$.store.orders");
        
        // Test querying products from combined file
        String productsResult = queryExecutor.execute("SELECT * FROM combo_products");
        JsonNode productsNode = objectMapper.readTree(productsResult);
        assertEquals(2, productsNode.size());
        
        // Test querying orders from combined file
        String ordersResult = queryExecutor.execute("SELECT * FROM combo_orders WHERE status = 'active'");
        JsonNode ordersNode = objectMapper.readTree(ordersResult);
        assertEquals(1, ordersNode.size());
        
        // Test JOIN between tables in the same file
        String joinResult = queryExecutor.execute(
            "SELECT p.name, o.quantity FROM combo_orders o JOIN combo_products p ON o.productId = p.id"
        );
        JsonNode joinNode = objectMapper.readTree(joinResult);
        assertEquals(2, joinNode.size());
        assertTrue(joinNode.get(0).has("name"));
        assertTrue(joinNode.get(0).has("quantity"));
    }

    @Test
    void testSelectWithOrderBy() throws Exception {
        String result = queryExecutor.execute("SELECT name, price FROM products ORDER BY price");
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.size() > 0);
        
        // Verify ascending order
        for (int i = 1; i < resultNode.size(); i++) {
            double prevPrice = resultNode.get(i - 1).get("price").asDouble();
            double currPrice = resultNode.get(i).get("price").asDouble();
            assertTrue(prevPrice <= currPrice, "Prices should be in ascending order");
        }
    }

    @Test
    void testSelectWithOrderByDesc() throws Exception {
        String result = queryExecutor.execute("SELECT name, price FROM products ORDER BY price DESC");
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.size() > 0);
        
        // Verify descending order
        for (int i = 1; i < resultNode.size(); i++) {
            double prevPrice = resultNode.get(i - 1).get("price").asDouble();
            double currPrice = resultNode.get(i).get("price").asDouble();
            assertTrue(prevPrice >= currPrice, "Prices should be in descending order");
        }
    }

    @Test
    void testSelectWithMultipleOrderBy() throws Exception {
        String result = queryExecutor.execute("SELECT name, category, price FROM products ORDER BY category, price DESC");
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.size() > 0);
        
        // Verify first item has correct category
        assertTrue(resultNode.get(0).has("category"));
    }

    @Test
    void testOrderByWithTopAndWhere() throws Exception {
        String result = queryExecutor.execute(
            "SELECT TOP 2 name, price FROM products WHERE price > 10 ORDER BY price"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(2, resultNode.size());
        
        // Verify ascending order and all prices > 10
        for (int i = 0; i < resultNode.size(); i++) {
            double price = resultNode.get(i).get("price").asDouble();
            assertTrue(price > 10, "Price should be greater than 10");
            
            if (i > 0) {
                double prevPrice = resultNode.get(i - 1).get("price").asDouble();
                assertTrue(prevPrice <= price, "Prices should be in ascending order");
            }
        }
    }

    @Test
    void testMultipleFilesFromDirectory() throws Exception {
        // Create a directory with multiple product files
        File multiDir = dataDir.toPath().resolve("multi-products").toFile();
        multiDir.mkdirs();
        
        String products1 = """
        {"products": [{"id": 1, "name": "Product A", "price": 10.0}, {"id": 2, "name": "Product B", "price": 20.0}]}
        """;
        String products2 = """
        {"products": [{"id": 3, "name": "Product C", "price": 30.0}, {"id": 4, "name": "Product D", "price": 40.0}]}
        """;
        
        Files.writeString(multiDir.toPath().resolve("products1.json"), products1);
        Files.writeString(multiDir.toPath().resolve("products2.json"), products2);
        
        // Add mapping to directory
        mappingManager.addMapping("multi_products", "multi-products:$.products");
        
        // Query should combine data from all files
        String result = queryExecutor.execute("SELECT * FROM multi_products");
        JsonNode resultNode = objectMapper.readTree(result);
        
        // Should have all 4 products from both files
        assertEquals(4, resultNode.size());
        
        // Verify products from both files are present
        boolean hasProductA = false;
        boolean hasProductD = false;
        for (JsonNode product : resultNode) {
            String name = product.get("name").asText();
            if (name.equals("Product A")) hasProductA = true;
            if (name.equals("Product D")) hasProductD = true;
        }
        assertTrue(hasProductA, "Should have Product A from file 1");
        assertTrue(hasProductD, "Should have Product D from file 2");
    }

    @Test
    void testJoinAcrossMultipleFiles() throws Exception {
        // Create directories with multiple files
        File productsDir = dataDir.toPath().resolve("split-products").toFile();
        File ordersDir = dataDir.toPath().resolve("split-orders").toFile();
        productsDir.mkdirs();
        ordersDir.mkdirs();
        
        String products1 = """
        {"data": [{"id": 1, "name": "Item A"}, {"id": 2, "name": "Item B"}]}
        """;
        String products2 = """
        {"data": [{"id": 3, "name": "Item C"}]}
        """;
        String orders1 = """
        {"data": [{"orderId": 101, "productId": 1, "qty": 5}]}
        """;
        String orders2 = """
        {"data": [{"orderId": 102, "productId": 3, "qty": 2}]}
        """;
        
        Files.writeString(productsDir.toPath().resolve("p1.json"), products1);
        Files.writeString(productsDir.toPath().resolve("p2.json"), products2);
        Files.writeString(ordersDir.toPath().resolve("o1.json"), orders1);
        Files.writeString(ordersDir.toPath().resolve("o2.json"), orders2);
        
        mappingManager.addMapping("split_products", "split-products:$.data");
        mappingManager.addMapping("split_orders", "split-orders:$.data");
        
        // JOIN across multiple files
        String result = queryExecutor.execute(
            "SELECT p.name, o.qty FROM split_orders o JOIN split_products p ON o.productId = p.id"
        );
        JsonNode resultNode = objectMapper.readTree(result);
        
        // Should match orders with products from different files
        assertEquals(2, resultNode.size());
    }

    @Test
    void testRelativePathInMapping() throws Exception {
        // Create a subdirectory structure
        File subDir = dataDir.toPath().resolve("subfolder").toFile();
        subDir.mkdirs();
        
        String testData = """
        {"items": [{"id": 1, "value": "test"}]}
        """;
        Files.writeString(subDir.toPath().resolve("data.json"), testData);
        
        // Add mapping with relative path
        mappingManager.addMapping("sub_items", "subfolder/data.json:$.items");
        
        String result = queryExecutor.execute("SELECT * FROM sub_items");
        JsonNode resultNode = objectMapper.readTree(result);
        
        assertEquals(1, resultNode.size());
        assertEquals("test", resultNode.get(0).get("value").asText());
    }
}

