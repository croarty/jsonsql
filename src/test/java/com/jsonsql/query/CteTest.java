package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Common Table Expressions (CTEs) using WITH syntax.
 */
class CteTest {
    
    private File testDir;
    private QueryExecutor executor;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("cte-test").toFile();
        
        // Create test data
        createTestFiles();
        
        // Set up mappings
        File mappingFile = new File(testDir, ".jsonsql-mappings.json");
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "$.products");
        mappingManager.addMapping("orders", "$.orders");
        
        executor = new QueryExecutor(mappingManager, testDir);
    }
    
    @AfterEach
    void tearDown() {
        deleteDirectory(testDir);
    }
    
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                }
                file.delete();
            }
        }
        dir.delete();
    }
    
    private void createTestFiles() throws IOException {
        String productsJson = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "price": 999.99, "category": "Electronics"},
                {"id": 2, "name": "Mouse", "price": 29.99, "category": "Electronics"},
                {"id": 3, "name": "Desk", "price": 299.99, "category": "Furniture"},
                {"id": 4, "name": "Chair", "price": 199.99, "category": "Furniture"},
                {"id": 5, "name": "Monitor", "price": 399.99, "category": "Electronics"}
              ]
            }
            """;
        
        String ordersJson = """
            {
              "orders": [
                {"id": 1, "productId": 1, "quantity": 2, "status": "completed"},
                {"id": 2, "productId": 2, "quantity": 5, "status": "pending"},
                {"id": 3, "productId": 3, "quantity": 1, "status": "completed"},
                {"id": 4, "productId": 1, "quantity": 1, "status": "pending"}
              ]
            }
            """;
        
        Files.writeString(new File(testDir, "products.json").toPath(), productsJson);
        Files.writeString(new File(testDir, "orders.json").toPath(), ordersJson);
    }
    
    @Test
    void testSimpleCTE() throws Exception {
        String sql = """
            WITH expensive_products AS (
              SELECT * FROM products WHERE price > 100
            )
            SELECT * FROM expensive_products
            """;
        
        String result = executor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(4, resultArray.size()); // Laptop, Desk, Chair, Monitor
    }
    
    @Test
    void testCTEWithJoin() throws Exception {
        String sql = """
            WITH expensive_products AS (
              SELECT * FROM products WHERE price > 100
            )
            SELECT o.id AS orderId, ep.name, ep.price, o.quantity
            FROM orders o
            JOIN expensive_products ep ON o.productId = ep.id
            """;
        
        String result = executor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(3, resultArray.size()); // Orders for Laptop (id 1, appears twice) and Desk (id 3)
        
        // Verify structure
        JsonNode firstRow = resultArray.get(0);
        assertTrue(firstRow.has("orderId"));
        assertTrue(firstRow.has("name"));
        assertTrue(firstRow.has("price"));
        assertTrue(firstRow.has("quantity"));
    }
    
    @Test
    void testCTEWithWhere() throws Exception {
        String sql = """
            WITH electronics AS (
              SELECT * FROM products WHERE category = 'Electronics'
            )
            SELECT * FROM electronics WHERE price > 50
            """;
        
        String result = executor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size()); // Laptop (999.99), Monitor (399.99) - Mouse (29.99) is excluded
    }
    
    @Test
    void testCTEWithOrderBy() throws Exception {
        String sql = """
            WITH expensive_products AS (
              SELECT * FROM products WHERE price > 100
            )
            SELECT * FROM expensive_products ORDER BY price DESC
            """;
        
        String result = executor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertTrue(resultArray.size() > 1);
        
        // Verify descending order
        for (int i = 0; i < resultArray.size() - 1; i++) {
            double current = resultArray.get(i).get("price").asDouble();
            double next = resultArray.get(i + 1).get("price").asDouble();
            assertTrue(current >= next, "Prices should be in descending order");
        }
    }
    
    @Test
    void testMultipleCTEs() throws Exception {
        String sql = """
            WITH 
              expensive_products AS (SELECT * FROM products WHERE price > 100),
              completed_orders AS (SELECT * FROM orders WHERE status = 'completed')
            SELECT o.id AS orderId, ep.name, ep.price
            FROM completed_orders o
            JOIN expensive_products ep ON o.productId = ep.id
            """;
        
        String result = executor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size()); // Completed orders for expensive products
    }
    
    @Test
    void testCTEWithDistinct() throws Exception {
        String sql = """
            WITH categories AS (
              SELECT DISTINCT category FROM products
            )
            SELECT * FROM categories
            """;
        
        String result = executor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size()); // Electronics and Furniture
    }
    
    @Test
    void testCTEWithLimit() throws Exception {
        String sql = """
            WITH expensive_products AS (
              SELECT * FROM products WHERE price > 100 ORDER BY price DESC
            )
            SELECT * FROM expensive_products LIMIT 2
            """;
        
        String result = executor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size());
    }
    
    @Test
    void testCTEWithAlias() throws Exception {
        String sql = """
            WITH ep AS (
              SELECT * FROM products WHERE price > 100
            )
            SELECT ep.name, ep.price FROM ep
            """;
        
        String result = executor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(4, resultArray.size());
        
        JsonNode firstRow = resultArray.get(0);
        assertTrue(firstRow.has("name"));
        assertTrue(firstRow.has("price"));
    }
    
    @Test
    void testCTEInFromClause() throws Exception {
        String sql = """
            WITH expensive_products AS (
              SELECT * FROM products WHERE price > 100
            )
            SELECT ep.name, o.quantity
            FROM expensive_products ep
            JOIN orders o ON ep.id = o.productId
            """;
        
        String result = executor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertTrue(resultArray.size() > 0);
    }
}

