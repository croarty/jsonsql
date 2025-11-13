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
 * Tests for ILIKE operator (case-insensitive pattern matching).
 */
class IlikeOperatorTest {
    
    private File testDir;
    private QueryExecutor executor;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("ilike-test").toFile();
        
        // Create test data with mixed case
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
                {"id": 2, "name": "laptop Pro", "category": "electronics", "brand": "Apple"},
                {"id": 3, "name": "LAPTOP STAND", "category": "ELECTRONICS", "brand": "HP"},
                {"id": 4, "name": "Wireless Mouse", "category": "Electronics", "brand": "Logitech"},
                {"id": 5, "name": "wireless keyboard", "category": "Electronics", "brand": "Corsair"},
                {"id": 6, "name": "Office Chair", "category": "Furniture", "brand": "Herman Miller"},
                {"id": 7, "name": "office desk", "category": "furniture", "brand": "Uplift"},
                {"id": 8, "name": "DESK LAMP", "category": "Furniture", "brand": "IKEA"},
                {"id": 9, "name": "USB-C Cable", "category": "Accessories", "brand": "Anker"},
                {"id": 10, "name": "usb-c hub", "category": "Accessories", "brand": "AmazonBasics"}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), json);
    }
    
    @Test
    void testIlikeCaseInsensitiveStartsWith() throws Exception {
        // ILIKE should match regardless of case
        String sql = "SELECT * FROM products WHERE name ILIKE 'laptop%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Should match: "Laptop", "laptop Pro", "LAPTOP STAND"
    }
    
    @Test
    void testIlikeCaseInsensitiveEndsWith() throws Exception {
        String sql = "SELECT * FROM products WHERE name ILIKE '%cable'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("USB-C Cable", result.get(0).get("name").asText());
    }
    
    @Test
    void testIlikeCaseInsensitiveContains() throws Exception {
        String sql = "SELECT * FROM products WHERE name ILIKE '%desk%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Should match: "office desk", "DESK LAMP"
    }
    
    @Test
    void testIlikeMixedCasePattern() throws Exception {
        // Pattern with mixed case should still match
        String sql = "SELECT * FROM products WHERE name ILIKE '%LaPtOp%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Should match all laptop variants regardless of case
    }
    
    @Test
    void testIlikeCategoryCaseInsensitive() throws Exception {
        String sql = "SELECT * FROM products WHERE category ILIKE 'electronics'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(5, result.size());
        // Should match: "Electronics", "electronics", "ELECTRONICS"
    }
    
    @Test
    void testIlikeWithWildcards() throws Exception {
        String sql = "SELECT * FROM products WHERE name ILIKE 'w%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Should match: "Wireless Mouse", "wireless keyboard"
    }
    
    @Test
    void testIlikeSingleCharacterWildcard() throws Exception {
        String sql = "SELECT * FROM products WHERE name ILIKE '%d_sk%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Should match: "office desk", "DESK LAMP" (d_sk matches "desk" and "DESK")
    }
    
    @Test
    void testIlikeExactMatchCaseInsensitive() throws Exception {
        String sql = "SELECT * FROM products WHERE name ILIKE 'laptop'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
    }
    
    @Test
    void testNotIlike() throws Exception {
        String sql = "SELECT * FROM products WHERE name NOT ILIKE '%laptop%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(7, result.size());
        // All products except laptop variants
    }
    
    @Test
    void testIlikeWithAnd() throws Exception {
        String sql = "SELECT * FROM products WHERE name ILIKE '%desk%' AND category ILIKE 'furniture'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // "office desk" and "DESK LAMP" in furniture category
    }
    
    @Test
    void testIlikeWithOr() throws Exception {
        String sql = "SELECT * FROM products WHERE name ILIKE 'laptop%' OR name ILIKE '%mouse%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(4, result.size());
        // Laptop variants + Wireless Mouse
    }
    
    @Test
    void testIlikeInComplexExpression() throws Exception {
        String sql = "SELECT * FROM products WHERE (name ILIKE '%computer%' OR name ILIKE '%laptop%') AND category ILIKE 'electronics'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Laptop variants in electronics
    }
    
    @Test
    void testIlikeVsLikeCaseSensitive() throws Exception {
        // LIKE should be case-sensitive - 'laptop%' should only match lowercase
        String likeSql = "SELECT * FROM products WHERE name LIKE 'laptop%'";
        String likeResult = executor.execute(likeSql);
        JsonNode likeNodes = objectMapper.readTree(likeResult);
        
        // ILIKE should be case-insensitive
        String ilikeSql = "SELECT * FROM products WHERE name ILIKE 'laptop%'";
        String ilikeResult = executor.execute(ilikeSql);
        JsonNode ilikeNodes = objectMapper.readTree(ilikeResult);
        
        // LIKE should find fewer results (case-sensitive)
        assertTrue(likeNodes.size() < ilikeNodes.size(), 
            "LIKE should find fewer results than ILIKE");
        // LIKE finds "laptop Pro" (starts with lowercase 'laptop')
        assertEquals(1, likeNodes.size()); 
        // ILIKE finds all laptop variants
        assertEquals(3, ilikeNodes.size()); 
    }
    
    @Test
    void testIlikeWithJoin() throws Exception {
        // Add orders file for JOIN test
        String ordersJson = """
            {
              "orders": [
                {"id": 1, "productId": 1, "quantity": 2},
                {"id": 2, "productId": 7, "quantity": 1},
                {"id": 3, "productId": 8, "quantity": 1}
              ]
            }
            """;
        Files.writeString(new File(testDir, "orders.json").toPath(), ordersJson);
        mappingManager.addMapping("orders", "$.orders");
        
        String sql = "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE p.name ILIKE '%desk%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Should match "office desk" and "DESK LAMP"
    }
    
    @Test
    void testIlikeWithOrderBy() throws Exception {
        String sql = "SELECT * FROM products WHERE name ILIKE '%e%' ORDER BY name";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.size() >= 3);
        // Results should be ordered by name
    }
    
    @Test
    void testIlikeWithTopLimit() throws Exception {
        String sql = "SELECT TOP 2 * FROM products WHERE name ILIKE '%a%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
    }
    
    @Test
    void testIlikeSpecialCharacters() throws Exception {
        String sql = "SELECT * FROM products WHERE name ILIKE '%usb-c%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Should match "USB-C Cable" and "usb-c hub"
    }
    
    @Test
    void testIlikeEmptyPattern() throws Exception {
        String sql = "SELECT * FROM products WHERE name ILIKE ''";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(0, result.size());
        // Empty pattern matches nothing
    }
    
    @Test
    void testIlikePercentOnly() throws Exception {
        String sql = "SELECT * FROM products WHERE name ILIKE '%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(10, result.size());
        // % matches all strings (case-insensitive)
    }
}

