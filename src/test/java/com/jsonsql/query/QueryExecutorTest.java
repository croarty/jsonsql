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
}

