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
 * Tests for IS NULL and IS NOT NULL operators.
 * Both missing fields and explicitly null fields should be treated as NULL.
 */
class IsNullOperatorTest {
    
    private File testDir;
    private QueryExecutor executor;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("is-null-test").toFile();
        
        // Create test data
        createTestData();
        
        // Set up mappings
        File mappingFile = new File(testDir, ".jsonsql-mappings.json");
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "$.products");
        mappingManager.addMapping("users", "$.users");
        
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
        // Products with various null scenarios
        String productsJson = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "category": "Electronics", "description": "Gaming laptop"},
                {"id": 2, "name": "Mouse", "category": "Electronics", "description": null},
                {"id": 3, "name": "Desk", "category": null, "description": "Standing desk"},
                {"id": 4, "name": "Chair"},
                {"id": 5, "name": "Monitor", "category": "Electronics"}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), productsJson);
        
        // Users with optional fields
        String usersJson = """
            {
              "users": [
                {"id": 1, "name": "Alice", "email": "alice@example.com", "phone": "555-0001"},
                {"id": 2, "name": "Bob", "email": null, "phone": "555-0002"},
                {"id": 3, "name": "Charlie", "phone": "555-0003"},
                {"id": 4, "name": "Diana"}
              ]
            }
            """;
        Files.writeString(new File(testDir, "users.json").toPath(), usersJson);
    }
    
    @Test
    void testIsNullWithExplicitNull() throws Exception {
        // Find products where description is explicitly null
        String sql = "SELECT * FROM products WHERE description IS NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Should return products 2, 4, and 5 (null, missing, missing)
        assertEquals(3, result.size());
    }
    
    @Test
    void testIsNullWithMissingField() throws Exception {
        // Find products where category is null (explicit or missing)
        String sql = "SELECT * FROM products WHERE category IS NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Should return products 3 and 4 (explicit null and missing)
        assertEquals(2, result.size());
    }
    
    @Test
    void testIsNotNullWithValues() throws Exception {
        // Find products where description is not null
        String sql = "SELECT * FROM products WHERE description IS NOT NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Should return products 1 and 3 (have descriptions)
        assertEquals(2, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
        assertEquals("Desk", result.get(1).get("name").asText());
    }
    
    @Test
    void testIsNotNullExcludesMissingFields() throws Exception {
        // Find products where category is not null
        String sql = "SELECT * FROM products WHERE category IS NOT NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Should return products 1, 2, and 5 (have category values)
        assertEquals(3, result.size());
    }
    
    @Test
    void testIsNullWithAnd() throws Exception {
        // Find products with no description AND in Electronics category
        String sql = "SELECT * FROM products WHERE description IS NULL AND category = 'Electronics'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Should return products 2 and 5
        assertEquals(2, result.size());
    }
    
    @Test
    void testIsNullWithOr() throws Exception {
        // Find products where category OR description is null
        String sql = "SELECT * FROM products WHERE category IS NULL OR description IS NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Products 2 (desc null), 3 (cat null), 4 (both missing), 5 (desc missing)
        assertEquals(4, result.size());
    }
    
    @Test
    void testIsNotNullWithAnd() throws Exception {
        // Find products that have both category AND description
        String sql = "SELECT * FROM products WHERE category IS NOT NULL AND description IS NOT NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Only product 1 has both (Laptop)
        // Product 3 has description but null category
        assertEquals(1, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
    }
    
    @Test
    void testIsNullInComplexExpression() throws Exception {
        // Complex condition with IS NULL
        String sql = "SELECT * FROM products WHERE (category IS NULL OR category = 'Electronics') AND id > 2";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Products 3 (cat null), 4 (cat missing), 5 (Electronics) - all with id > 2
        assertEquals(3, result.size());
    }
    
    @Test
    void testMultipleIsNullChecks() throws Exception {
        // Find products missing both category and description
        String sql = "SELECT * FROM products WHERE category IS NULL AND description IS NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Only product 4 is missing both
        assertEquals(1, result.size());
        assertEquals("Chair", result.get(0).get("name").asText());
    }
    
    @Test
    void testIsNullWithLike() throws Exception {
        // Combine IS NULL with LIKE
        String sql = "SELECT * FROM products WHERE description IS NOT NULL AND name LIKE '%e%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Products with description and 'e' in name: Desk
        // (Laptop has no 'e', Mouse has null description)
        assertEquals(1, result.size());
        assertEquals("Desk", result.get(0).get("name").asText());
    }
    
    @Test
    void testIsNullWithNumericField() throws Exception {
        // Test IS NULL on numeric field (id should never be null in our data)
        String sql = "SELECT * FROM products WHERE id IS NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // No products have null id
        assertEquals(0, result.size());
    }
    
    @Test
    void testIsNotNullWithNumericField() throws Exception {
        // All products should have id
        String sql = "SELECT * FROM products WHERE id IS NOT NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // All 5 products have id
        assertEquals(5, result.size());
    }
    
    @Test
    void testIsNullWithOrderBy() throws Exception {
        // Find null categories, ordered by name
        String sql = "SELECT * FROM products WHERE category IS NULL ORDER BY name";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Should be ordered: Chair, Desk
        assertEquals("Chair", result.get(0).get("name").asText());
        assertEquals("Desk", result.get(1).get("name").asText());
    }
    
    @Test
    void testIsNullWithTopLimit() throws Exception {
        // Get first 2 products with null description
        String sql = "SELECT TOP 2 * FROM products WHERE description IS NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
    }
    
    @Test
    void testIsNullInJoin() throws Exception {
        // Create orders with optional fields
        String ordersJson = """
            {
              "orders": [
                {"id": 1, "productId": 1, "notes": "Rush order"},
                {"id": 2, "productId": 2, "notes": null},
                {"id": 3, "productId": 3}
              ]
            }
            """;
        Files.writeString(new File(testDir, "orders.json").toPath(), ordersJson);
        mappingManager.addMapping("orders", "$.orders");
        
        // Find orders without notes
        String sql = "SELECT o.id, p.name FROM orders o JOIN products p ON o.productId = p.id WHERE o.notes IS NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Orders 2 and 3 have no notes
        assertEquals(2, result.size());
    }
    
    @Test
    void testIsNullWithNot() throws Exception {
        // Using NOT with IS NULL (double negative = IS NOT NULL)
        String sql = "SELECT * FROM products WHERE NOT (description IS NULL)";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Same as IS NOT NULL
        assertEquals(2, result.size());
    }
    
    @Test
    void testIsNotNullWithNot() throws Exception {
        // Using NOT with IS NOT NULL (double negative = IS NULL)
        String sql = "SELECT * FROM products WHERE NOT (description IS NOT NULL)";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Same as IS NULL
        assertEquals(3, result.size());
    }
    
    @Test
    void testIsNullSelectsAllNullTypes() throws Exception {
        // Verify both explicit null and missing fields are selected
        String sql = "SELECT id, name, email FROM users WHERE email IS NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Users 2 (explicit null), 3 (missing), 4 (missing)
        assertEquals(3, result.size());
    }
    
    @Test
    void testIsNotNullPreservesValues() throws Exception {
        // Verify IS NOT NULL returns rows with actual values
        String sql = "SELECT * FROM users WHERE email IS NOT NULL";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Only user 1 has email
        assertEquals(1, result.size());
        assertEquals("alice@example.com", result.get(0).get("email").asText());
    }
    
    @Test
    void testIsNullWithComparisonOperators() throws Exception {
        // Combine IS NULL with other operators
        String sql = "SELECT * FROM products WHERE description IS NOT NULL AND id < 4";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Products 1 and 3 (have description and id < 4)
        assertEquals(2, result.size());
    }
}

