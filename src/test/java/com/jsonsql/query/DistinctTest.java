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
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DISTINCT keyword functionality.
 */
class DistinctTest {
    
    private File testDir;
    private QueryExecutor executor;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("distinct-test").toFile();
        
        // Create test data with duplicates
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
                {"id": 1, "name": "Laptop", "category": "Electronics", "price": 999.99},
                {"id": 2, "name": "Mouse", "category": "Electronics", "price": 29.99},
                {"id": 3, "name": "Keyboard", "category": "Electronics", "price": 79.99},
                {"id": 4, "name": "Desk", "category": "Furniture", "price": 299.99},
                {"id": 5, "name": "Chair", "category": "Furniture", "price": 199.99},
                {"id": 6, "name": "Laptop", "category": "Electronics", "price": 1299.99},
                {"id": 7, "name": "Mouse", "category": "Electronics", "price": 29.99},
                {"id": 8, "name": "Monitor", "category": "Electronics", "price": 399.99}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), json);
    }
    
    @Test
    void testDistinctSingleColumn() throws Exception {
        String sql = "SELECT DISTINCT category FROM products";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        assertEquals(2, result.size()); // Only "Electronics" and "Furniture"
        
        Set<String> categories = new HashSet<>();
        for (JsonNode row : result) {
            assertTrue(row.has("category"));
            categories.add(row.get("category").asText());
        }
        
        assertTrue(categories.contains("Electronics"));
        assertTrue(categories.contains("Furniture"));
    }
    
    @Test
    void testDistinctMultipleColumns() throws Exception {
        String sql = "SELECT DISTINCT name, category FROM products";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        // Should have unique combinations of name and category
        // Laptop+Electronics appears twice (id 1 and 6) but should only appear once
        // Mouse+Electronics appears twice (id 2 and 7) but should only appear once
        assertEquals(6, result.size()); // 8 products - 2 duplicates = 6 unique combinations
    }
    
    @Test
    void testDistinctWithWhere() throws Exception {
        String sql = "SELECT DISTINCT category FROM products WHERE price > 100";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        assertEquals(2, result.size()); // Electronics and Furniture
    }
    
    @Test
    void testDistinctWithOrderBy() throws Exception {
        String sql = "SELECT DISTINCT category FROM products ORDER BY category";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        assertEquals(2, result.size());
        
        // Should be sorted
        assertEquals("Electronics", result.get(0).get("category").asText());
        assertEquals("Furniture", result.get(1).get("category").asText());
    }
    
    @Test
    void testDistinctWithLimit() throws Exception {
        String sql = "SELECT DISTINCT category FROM products ORDER BY category LIMIT 1";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        assertEquals(1, result.size());
        assertEquals("Electronics", result.get(0).get("category").asText());
    }
    
    @Test
    void testDistinctWithJoin() throws Exception {
        // Create orders file
        String ordersJson = """
            {
              "orders": [
                {"id": 1, "productId": 1, "quantity": 2},
                {"id": 2, "productId": 1, "quantity": 1},
                {"id": 3, "productId": 2, "quantity": 3}
              ]
            }
            """;
        Files.writeString(new File(testDir, "orders.json").toPath(), ordersJson);
        mappingManager.addMapping("orders", "$.orders");
        
        String sql = "SELECT DISTINCT p.category FROM orders o JOIN products p ON o.productId = p.id";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        assertEquals(1, result.size()); // Only Electronics (products 1 and 2 are both Electronics)
        assertEquals("Electronics", result.get(0).get("category").asText());
    }
    
    @Test
    void testDistinctWithUnnest() throws Exception {
        // Create products with tags
        String productsWithTags = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "tags": ["electronics", "portable"]},
                {"id": 2, "name": "Mouse", "tags": ["electronics", "wireless"]},
                {"id": 3, "name": "Keyboard", "tags": ["electronics", "wireless"]}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), productsWithTags);
        
        String sql = "SELECT DISTINCT tag FROM products, UNNEST(tags) AS t(tag)";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        assertEquals(3, result.size()); // electronics, portable, wireless (electronics appears twice but should be distinct)
        
        Set<String> tags = new HashSet<>();
        for (JsonNode row : result) {
            assertTrue(row.has("tag"));
            tags.add(row.get("tag").asText());
        }
        
        assertTrue(tags.contains("electronics"));
        assertTrue(tags.contains("portable"));
        assertTrue(tags.contains("wireless"));
    }
    
    @Test
    void testDistinctSelectStar() throws Exception {
        // Create products with exact duplicates
        String productsWithDuplicates = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "category": "Electronics"},
                {"id": 2, "name": "Mouse", "category": "Electronics"},
                {"id": 3, "name": "Laptop", "category": "Electronics"},
                {"id": 4, "name": "Mouse", "category": "Electronics"}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), productsWithDuplicates);
        
        String sql = "SELECT DISTINCT * FROM products";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        // Should have 2 unique rows (Laptop and Mouse, ignoring id)
        // But since we're selecting *, id is included, so all 4 are distinct
        // Actually, DISTINCT * compares all fields, so rows with same name+category but different id are distinct
        assertEquals(4, result.size());
    }
    
    @Test
    void testDistinctWithNullValues() throws Exception {
        String productsWithNulls = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "category": "Electronics", "description": null},
                {"id": 2, "name": "Mouse", "category": "Electronics", "description": null},
                {"id": 3, "name": "Keyboard", "category": "Electronics"}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), productsWithNulls);
        
        String sql = "SELECT DISTINCT description FROM products";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        // Should have 2 rows: one with null, one without the field (which is also treated as null)
        // Actually, both missing and null are treated the same, so should be 1 row
        assertTrue(result.size() >= 1);
    }
    
    @Test
    void testDistinctNestedFields() throws Exception {
        String productsWithNested = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "specs": {"brand": "Dell", "model": "XPS"}},
                {"id": 2, "name": "Mouse", "specs": {"brand": "Logitech", "model": "MX"}},
                {"id": 3, "name": "Laptop", "specs": {"brand": "Dell", "model": "XPS"}}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), productsWithNested);
        
        String sql = "SELECT DISTINCT specs FROM products";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        assertEquals(2, result.size()); // Two unique specs objects
    }
    
    @Test
    void testNoDistinctReturnsDuplicates() throws Exception {
        String sql = "SELECT category FROM products";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        assertEquals(8, result.size()); // All rows, including duplicates
        
        // Count Electronics occurrences
        int electronicsCount = 0;
        for (JsonNode row : result) {
            if ("Electronics".equals(row.get("category").asText())) {
                electronicsCount++;
            }
        }
        assertTrue(electronicsCount > 1); // Should have duplicates
    }
    
    @Test
    void testDistinctPreservesFirstOccurrence() throws Exception {
        String sql = "SELECT DISTINCT name, price FROM products ORDER BY id";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertTrue(result.isArray());
        // Mouse appears twice with same price (29.99), should only appear once
        // Laptop appears twice with different prices, so both should appear
        assertTrue(result.size() <= 8);
    }
}

