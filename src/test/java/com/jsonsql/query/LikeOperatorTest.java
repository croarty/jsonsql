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
 * Tests for LIKE operator pattern matching.
 */
class LikeOperatorTest {
    
    private File testDir;
    private QueryExecutor executor;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("like-test").toFile();
        
        // Create test data
        createProductsFile();
        
        // Set up mappings
        File mappingFile = new File(testDir, ".jsonsql-mappings.json");
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "$.products");
        
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
                {"id": 1, "name": "Laptop", "category": "Electronics", "brand": "Dell"},
                {"id": 2, "name": "Laptop Pro", "category": "Electronics", "brand": "Apple"},
                {"id": 3, "name": "Desktop Computer", "category": "Electronics", "brand": "HP"},
                {"id": 4, "name": "Wireless Mouse", "category": "Electronics", "brand": "Logitech"},
                {"id": 5, "name": "Mechanical Keyboard", "category": "Electronics", "brand": "Corsair"},
                {"id": 6, "name": "Office Chair", "category": "Furniture", "brand": "Herman Miller"},
                {"id": 7, "name": "Standing Desk", "category": "Furniture", "brand": "Uplift"},
                {"id": 8, "name": "Desk Lamp", "category": "Furniture", "brand": "IKEA"},
                {"id": 9, "name": "USB-C Cable", "category": "Accessories", "brand": "Anker"},
                {"id": 10, "name": "HDMI Cable 2.0", "category": "Accessories", "brand": "AmazonBasics"}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), json);
    }
    
    @Test
    void testLikeStartsWith() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE 'Laptop%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
        assertEquals("Laptop Pro", result.get(1).get("name").asText());
    }
    
    @Test
    void testLikeEndsWith() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE '%Cable'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("USB-C Cable", result.get(0).get("name").asText());
    }
    
    @Test
    void testLikeContains() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE '%Desk%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Should return "Desktop Computer", "Standing Desk", and "Desk Lamp"
    }
    
    @Test
    void testLikeSingleCharacterWildcard() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE 'Desk _amp'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("Desk Lamp", result.get(0).get("name").asText());
    }
    
    @Test
    void testLikeMultipleSingleCharWildcards() throws Exception {
        String sql = "SELECT * FROM products WHERE category LIKE 'F__niture'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // All Furniture items
    }
    
    @Test
    void testLikeCombinedWildcards() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE 'L%p_o%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Matches "Laptop" (L-a-p-t-o-p) and "Laptop Pro" (L-a-p-t-o-p)
    }
    
    @Test
    void testLikeExactMatch() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE 'Laptop'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
    }
    
    @Test
    void testLikeCaseSensitive() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE 'laptop%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(0, result.size());
        // SQL LIKE is case-sensitive by default
    }
    
    @Test
    void testNotLike() throws Exception {
        String sql = "SELECT * FROM products WHERE name NOT LIKE '%Laptop%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(8, result.size());
        // All products except those containing "Laptop"
    }
    
    @Test
    void testLikeWithAnd() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE '%Desk%' AND category = 'Furniture'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // "Standing Desk" and "Desk Lamp"
    }
    
    @Test
    void testLikeWithOr() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE 'Laptop%' OR name LIKE '%Mouse%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Laptop, Laptop Pro, Wireless Mouse
    }
    
    @Test
    void testLikeInComplexExpression() throws Exception {
        String sql = "SELECT * FROM products WHERE (name LIKE '%Computer%' OR name LIKE '%Laptop%') AND category = 'Electronics'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Laptop, Laptop Pro, Desktop Computer
    }
    
    @Test
    void testLikeWithParentheses() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE '%(%)%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(0, result.size());
        // No product names contain parentheses
    }
    
    @Test
    void testLikeWithSpecialCharacters() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE '%2.0%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("HDMI Cable 2.0", result.get(0).get("name").asText());
    }
    
    @Test
    void testLikeWithHyphen() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE '%USB-C%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("USB-C Cable", result.get(0).get("name").asText());
    }
    
    @Test
    void testLikeNoMatch() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE '%XYZ%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(0, result.size());
    }
    
    @Test
    void testLikeOnMultipleFields() throws Exception {
        String sql = "SELECT * FROM products WHERE brand LIKE 'A%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Apple, Anker, AmazonBasics
    }
    
    @Test
    void testNotLikeWithAnd() throws Exception {
        String sql = "SELECT * FROM products WHERE name NOT LIKE '%Laptop%' AND category = 'Electronics'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Desktop Computer, Wireless Mouse, Mechanical Keyboard (not Laptop or Laptop Pro)
    }
    
    @Test
    void testLikeWithJoin() throws Exception {
        // Add orders file for JOIN test
        String ordersJson = """
            {
              "orders": [
                {"id": 1, "productId": 1, "quantity": 2},
                {"id": 2, "productId": 4, "quantity": 1},
                {"id": 3, "productId": 6, "quantity": 1}
              ]
            }
            """;
        Files.writeString(new File(testDir, "orders.json").toPath(), ordersJson);
        mappingManager.addMapping("orders", "$.orders");
        
        String sql = "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE p.name LIKE '%Chair%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("Office Chair", result.get(0).get("name").asText());
    }
    
    @Test
    void testLikeWithOrderBy() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE '%e%' ORDER BY name";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.size() >= 3);
        // Results should be ordered by name
    }
    
    @Test
    void testLikeWithTopLimit() throws Exception {
        String sql = "SELECT TOP 2 * FROM products WHERE name LIKE '%a%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
    }
    
    @Test
    void testLikeMultiplePercents() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE '%a%a%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.size() >= 1);
        // Products with at least 2 'a's in name
    }
    
    @Test
    void testLikeEmptyPattern() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE ''";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(0, result.size());
        // Empty pattern matches nothing (no empty names)
    }
    
    @Test
    void testLikePercentOnly() throws Exception {
        String sql = "SELECT * FROM products WHERE name LIKE '%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(10, result.size());
        // % matches all strings
    }
}

