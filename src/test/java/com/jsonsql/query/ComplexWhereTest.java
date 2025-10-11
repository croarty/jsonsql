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
 * Tests for complex WHERE clause support (AND, OR, NOT, parentheses).
 */
class ComplexWhereTest {
    
    private File testDir;
    private QueryExecutor executor;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("complex-where-test").toFile();
        
        // Create test data
        createProductsFile();
        createOrdersFile();
        
        // Set up mappings
        File mappingFile = new File(testDir, ".jsonsql-mappings.json");
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "$.products");
        mappingManager.addMapping("orders", "$.orders");
        
        executor = new QueryExecutor(mappingManager, testDir);
    }
    
    @AfterEach
    void tearDown() {
        // Clean up test directory
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
    
    private void createProductsFile() throws IOException {
        String json = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "category": "Electronics", "price": 1200.00, "inStock": true},
                {"id": 2, "name": "Mouse", "category": "Electronics", "price": 25.00, "inStock": true},
                {"id": 3, "name": "Keyboard", "category": "Electronics", "price": 75.00, "inStock": false},
                {"id": 4, "name": "Desk", "category": "Furniture", "price": 300.00, "inStock": true},
                {"id": 5, "name": "Chair", "category": "Furniture", "price": 150.00, "inStock": true},
                {"id": 6, "name": "Monitor", "category": "Electronics", "price": 400.00, "inStock": false}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), json);
    }
    
    private void createOrdersFile() throws IOException {
        String json = """
            {
              "orders": [
                {"id": 1, "productId": 1, "quantity": 2, "status": "completed"},
                {"id": 2, "productId": 2, "quantity": 5, "status": "pending"},
                {"id": 3, "productId": 3, "quantity": 1, "status": "cancelled"},
                {"id": 4, "productId": 4, "quantity": 3, "status": "completed"}
              ]
            }
            """;
        Files.writeString(new File(testDir, "orders.json").toPath(), json);
    }
    
    @Test
    void testAndCondition() throws Exception {
        String sql = "SELECT * FROM products WHERE category = 'Electronics' AND price > 50";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Should return Laptop (1200), Keyboard (75), Monitor (400)
        assertTrue(result.get(0).has("name"));
    }
    
    @Test
    void testOrCondition() throws Exception {
        String sql = "SELECT * FROM products WHERE category = 'Furniture' OR price < 30";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Should return Mouse (25), Desk (300), Chair (150)
    }
    
    @Test
    void testNotCondition() throws Exception {
        String sql = "SELECT * FROM products WHERE NOT inStock = false";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(4, result.size());
        // Should return all products that are in stock
        for (int i = 0; i < result.size(); i++) {
            assertTrue(result.get(i).get("inStock").asBoolean());
        }
    }
    
    @Test
    void testParentheses() throws Exception {
        String sql = "SELECT * FROM products WHERE (category = 'Electronics' AND price > 100) OR category = 'Furniture'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(4, result.size());
        // Should return Laptop, Monitor, Desk, Chair
    }
    
    @Test
    void testComplexNestedConditions() throws Exception {
        String sql = "SELECT * FROM products WHERE (category = 'Electronics' AND (price < 100 OR price > 1000)) OR (category = 'Furniture' AND inStock = true)";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(5, result.size());
        // Should return Mouse (25), Keyboard (75), Laptop (1200), Desk (300), Chair (150)
    }
    
    @Test
    void testMultipleAnds() throws Exception {
        String sql = "SELECT * FROM products WHERE category = 'Electronics' AND price > 50 AND inStock = true";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
    }
    
    @Test
    void testMultipleOrs() throws Exception {
        String sql = "SELECT * FROM products WHERE id = 1 OR id = 3 OR id = 5";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
    }
    
    @Test
    void testAndWithOr() throws Exception {
        String sql = "SELECT * FROM products WHERE category = 'Electronics' AND (price < 50 OR price > 1000)";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Should return Laptop and Mouse
    }
    
    @Test
    void testComplexWithJoin() throws Exception {
        String sql = """
            SELECT p.name, o.quantity, o.status
            FROM orders o
            JOIN products p ON o.productId = p.id
            WHERE (o.status = 'completed' AND o.quantity > 1) OR p.category = 'Electronics'
            """;
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.size() >= 2);
        // Should include orders with completed status AND quantity > 1, OR electronics products
    }
    
    @Test
    void testNotWithAnd() throws Exception {
        String sql = "SELECT * FROM products WHERE NOT (category = 'Electronics' AND price > 500)";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(5, result.size());
        // Should exclude Laptop and Monitor
    }
    
    @Test
    void testNotWithOr() throws Exception {
        String sql = "SELECT * FROM products WHERE NOT (category = 'Furniture' OR price < 50)";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Should exclude Furniture items and items under $50
    }
    
    @Test
    void testDeepNesting() throws Exception {
        String sql = "SELECT * FROM products WHERE ((category = 'Electronics' AND price > 100) OR (category = 'Furniture' AND price < 200)) AND inStock = true";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Should return Laptop and Chair
    }
    
    @Test
    void testAllOperatorsWithParentheses() throws Exception {
        String sql = "SELECT * FROM products WHERE (category = 'Electronics' OR category = 'Furniture') AND (price >= 100 AND price <= 500) AND inStock = true";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Should return Desk, Chair (Laptop is > 500, Monitor not inStock)
    }
    
    @Test
    void testEqualityWithBooleans() throws Exception {
        String sql = "SELECT * FROM products WHERE inStock = true AND price > 100";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Laptop, Desk, Chair
    }
    
    @Test
    void testComparisonOperators() throws Exception {
        String sql = "SELECT * FROM products WHERE price >= 100 AND price <= 400";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Desk (300), Chair (150), Monitor (400) - Laptop is 1200 which is > 400
    }
    
    @Test
    void testEmptyResult() throws Exception {
        String sql = "SELECT * FROM products WHERE category = 'Electronics' AND category = 'Furniture'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(0, result.size());
        // Impossible condition
    }
    
    @Test
    void testWithOrderBy() throws Exception {
        String sql = "SELECT * FROM products WHERE (category = 'Electronics' OR category = 'Furniture') AND inStock = true ORDER BY price DESC";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.size() > 0);
        // Should be ordered by price descending
        if (result.size() > 1) {
            double firstPrice = result.get(0).get("price").asDouble();
            double secondPrice = result.get(1).get("price").asDouble();
            assertTrue(firstPrice >= secondPrice);
        }
    }
    
    @Test
    void testWithTop() throws Exception {
        String sql = "SELECT TOP 2 * FROM products WHERE category = 'Electronics' OR category = 'Furniture'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
    }
    
    @Test
    void testWithLimit() throws Exception {
        String sql = "SELECT * FROM products WHERE price > 50 LIMIT 3";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
    }
    
    @Test
    void testNotEquals() throws Exception {
        String sql = "SELECT * FROM products WHERE category != 'Electronics' AND price > 100";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Desk and Chair
    }
}

