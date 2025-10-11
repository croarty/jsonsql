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
 * Tests for IN and NOT IN operators.
 */
class InOperatorTest {
    
    private File testDir;
    private QueryExecutor executor;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("in-operator-test").toFile();
        
        // Create test data
        createTestData();
        
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
    
    private void createTestData() throws IOException {
        String productsJson = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "category": "Electronics", "price": 1200, "brand": "Dell"},
                {"id": 2, "name": "Mouse", "category": "Electronics", "price": 25, "brand": "Logitech"},
                {"id": 3, "name": "Desk", "category": "Furniture", "price": 300, "brand": "IKEA"},
                {"id": 4, "name": "Chair", "category": "Furniture", "price": 150, "brand": "Herman Miller"},
                {"id": 5, "name": "Hammer", "category": "Tools", "price": 20, "brand": "Stanley"},
                {"id": 6, "name": "Drill", "category": "Tools", "price": 80, "brand": "DeWalt"},
                {"id": 7, "name": "Monitor", "category": "Electronics", "price": 400, "brand": "Samsung"},
                {"id": 8, "name": "Lamp", "category": null, "price": 35, "brand": "Philips"}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), productsJson);
        
        String ordersJson = """
            {
              "orders": [
                {"id": 1, "productId": 1, "status": "completed", "quantity": 2},
                {"id": 2, "productId": 2, "status": "pending", "quantity": 1},
                {"id": 3, "productId": 3, "status": "shipped", "quantity": 1},
                {"id": 4, "productId": 4, "status": "completed", "quantity": 3}
              ]
            }
            """;
        Files.writeString(new File(testDir, "orders.json").toPath(), ordersJson);
    }
    
    @Test
    void testInWithStrings() throws Exception {
        String sql = "SELECT * FROM products WHERE category IN ('Electronics', 'Tools')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(5, result.size());
        // Laptop, Mouse, Monitor (Electronics) + Hammer, Drill (Tools)
    }
    
    @Test
    void testInWithSingleValue() throws Exception {
        String sql = "SELECT * FROM products WHERE category IN ('Furniture')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Desk, Chair
    }
    
    @Test
    void testInWithNumbers() throws Exception {
        String sql = "SELECT * FROM products WHERE id IN (1, 3, 5, 7)";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(4, result.size());
        // Laptop, Desk, Hammer, Monitor
    }
    
    @Test
    void testInWithMixedNumbers() throws Exception {
        String sql = "SELECT * FROM products WHERE price IN (25, 150, 400)";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Mouse (25), Chair (150), Monitor (400)
    }
    
    @Test
    void testNotIn() throws Exception {
        String sql = "SELECT * FROM products WHERE category NOT IN ('Electronics', 'Tools')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Desk, Chair (Furniture only)
        // Lamp excluded because category is null (NULL NOT IN returns FALSE)
    }
    
    @Test
    void testNotInWithSingleValue() throws Exception {
        String sql = "SELECT * FROM products WHERE category NOT IN ('Electronics')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(4, result.size());
        // Desk, Chair, Hammer, Drill
        // Lamp excluded (null category - NULL NOT IN returns FALSE)
    }
    
    @Test
    void testInWithNoMatches() throws Exception {
        String sql = "SELECT * FROM products WHERE category IN ('Automotive', 'Sports')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(0, result.size());
    }
    
    @Test
    void testInExcludesNullValues() throws Exception {
        // Product 8 (Lamp) has null category - should be excluded
        String sql = "SELECT * FROM products WHERE category IN ('Electronics', 'Furniture', 'Tools')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(7, result.size());
        // All products except Lamp
    }
    
    @Test
    void testNotInExcludesNullValues() throws Exception {
        // NOT IN with null values - in SQL, NULL NOT IN (...) returns UNKNOWN (treated as FALSE)
        String sql = "SELECT * FROM products WHERE category NOT IN ('Electronics')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Should return Furniture and Tools, but NOT null category
        // Lamp (null category) is excluded
        assertEquals(4, result.size());
    }
    
    @Test
    void testInWithAnd() throws Exception {
        String sql = "SELECT * FROM products WHERE category IN ('Electronics', 'Tools') AND price < 100";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Mouse (25), Hammer (20), Drill (80)
    }
    
    @Test
    void testInWithOr() throws Exception {
        String sql = "SELECT * FROM products WHERE category IN ('Tools') OR price > 1000";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Laptop (1200), Hammer (20), Drill (80)
    }
    
    @Test
    void testInWithComplexConditions() throws Exception {
        String sql = "SELECT * FROM products WHERE (category IN ('Electronics', 'Furniture') AND price > 100) OR category = 'Tools'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(6, result.size());
        // Laptop, Monitor, Desk, Chair, Hammer, Drill
    }
    
    @Test
    void testInWithLike() throws Exception {
        String sql = "SELECT * FROM products WHERE category IN ('Electronics') AND name LIKE '%o%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Laptop, Mouse, Monitor (all have 'o' in name and category = Electronics)
    }
    
    @Test
    void testInWithIsNull() throws Exception {
        String sql = "SELECT * FROM products WHERE category IN ('Electronics') OR category IS NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(4, result.size());
        // Laptop, Mouse, Monitor, Lamp
    }
    
    @Test
    void testNotInWithIsNotNull() throws Exception {
        String sql = "SELECT * FROM products WHERE category NOT IN ('Electronics') AND category IS NOT NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(4, result.size());
        // Desk, Chair, Hammer, Drill
    }
    
    @Test
    void testInWithJoin() throws Exception {
        String sql = "SELECT o.id, p.name FROM orders o JOIN products p ON o.productId = p.id WHERE o.status IN ('completed', 'shipped')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Orders 1, 3, 4
    }
    
    @Test
    void testInWithOrderBy() throws Exception {
        String sql = "SELECT * FROM products WHERE category IN ('Electronics', 'Furniture') ORDER BY price DESC";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(5, result.size());
        // Should be ordered by price descending
        assertTrue(result.get(0).get("price").asDouble() >= result.get(1).get("price").asDouble());
    }
    
    @Test
    void testInWithTopLimit() throws Exception {
        String sql = "SELECT TOP 3 * FROM products WHERE category IN ('Electronics', 'Furniture', 'Tools')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
    }
    
    @Test
    void testInWithBrands() throws Exception {
        String sql = "SELECT * FROM products WHERE brand IN ('Dell', 'IKEA', 'Stanley')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Laptop, Desk, Hammer
    }
    
    @Test
    void testInEmptyList() throws Exception {
        // Edge case: empty IN list should match nothing
        String sql = "SELECT * FROM products WHERE category IN ()";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(0, result.size());
    }
    
    @Test
    void testNotWithIn() throws Exception {
        // Using NOT with IN - subtly different from NOT IN for NULL values
        String sql = "SELECT * FROM products WHERE NOT (category IN ('Electronics'))";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // NOT (IN) with NULL: IN returns FALSE for NULL, NOT FALSE = TRUE, so NULL is INCLUDED
        // This is different from NOT IN which excludes NULL
        // Returns Furniture, Tools, AND the null category (Lamp)
        assertEquals(5, result.size());
    }
    
    @Test
    void testMultipleInConditions() throws Exception {
        String sql = "SELECT * FROM products WHERE category IN ('Electronics') AND brand IN ('Dell', 'Samsung')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Laptop (Dell), Monitor (Samsung)
    }
    
    @Test
    void testInWithDecimalNumbers() throws Exception {
        String sql = "SELECT * FROM products WHERE price IN (25.0, 150.0)";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Mouse, Chair
    }
}

