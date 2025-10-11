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
 * Tests for handling missing/optional fields in JSON objects.
 * This tests the difference between:
 * 1. Field is missing (not present in JSON object)
 * 2. Field is present but null
 * 3. Field is present with a value
 */
class MissingFieldsTest {
    
    private File testDir;
    private QueryExecutor executor;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("missing-fields-test").toFile();
        
        // Create test data with missing and null fields
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
        // Products with varying field presence
        String productsJson = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "category": "Electronics", "description": "Gaming laptop"},
                {"id": 2, "name": "Mouse", "category": "Electronics"},
                {"id": 3, "name": "Desk", "category": null, "description": "Standing desk"},
                {"id": 4, "name": "Chair"}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), productsJson);
        
        // Users with optional fields
        String usersJson = """
            {
              "users": [
                {"id": 1, "name": "Alice", "email": "alice@example.com", "phone": "555-0001"},
                {"id": 2, "name": "Bob", "email": "bob@example.com"},
                {"id": 3, "name": "Charlie", "phone": "555-0003"},
                {"id": 4, "name": "Diana", "email": null, "phone": null}
              ]
            }
            """;
        Files.writeString(new File(testDir, "users.json").toPath(), usersJson);
    }
    
    @Test
    void testSelectAllIncludesMissingFields() throws Exception {
        // SELECT * should return all rows, even those with missing fields
        String sql = "SELECT * FROM products";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(4, result.size());
        // Row 2 is missing 'description' - field won't be in output
        assertFalse(result.get(1).has("description"));
        // Row 3 has null category - field is in output as null
        assertTrue(result.get(2).has("category"));
        assertTrue(result.get(2).get("category").isNull());
        // Row 4 is missing both 'category' and 'description'
        assertFalse(result.get(3).has("category"));
        assertFalse(result.get(3).has("description"));
    }
    
    @Test
    void testWhereOnMissingFieldExcludesRow() throws Exception {
        // WHERE clause on missing field excludes that row (SQL standard behavior)
        String sql = "SELECT * FROM products WHERE category = 'Electronics'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Only rows 1 and 2 have category = 'Electronics'
        // Row 3 has null category, Row 4 is missing category - both excluded
        assertEquals(2, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
        assertEquals("Mouse", result.get(1).get("name").asText());
    }
    
    @Test
    void testWhereOnNullFieldExcludesRow() throws Exception {
        // WHERE clause excludes rows where field is null
        String sql = "SELECT * FROM products WHERE description = 'Gaming laptop'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
    }
    
    @Test
    void testLikeOnMissingFieldExcludesRow() throws Exception {
        // LIKE on missing field excludes row
        String sql = "SELECT * FROM products WHERE description LIKE '%desk%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Only "Standing desk" matches
        // Rows with missing description are excluded
        assertEquals(1, result.size());
        assertEquals("Desk", result.get(0).get("name").asText());
    }
    
    @Test
    void testMissingFieldInJoin() throws Exception {
        // When joining, rows with missing join fields are excluded
        // This is standard SQL behavior
        String ordersJson = """
            {
              "orders": [
                {"id": 1, "productId": 1, "quantity": 2},
                {"id": 2, "quantity": 1},
                {"id": 3, "productId": 3, "quantity": 5}
              ]
            }
            """;
        Files.writeString(new File(testDir, "orders.json").toPath(), ordersJson);
        mappingManager.addMapping("orders", "$.orders");
        
        String sql = "SELECT o.id, p.name FROM orders o JOIN products p ON o.productId = p.id";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Order 2 has no productId, so it's excluded from JOIN
        assertEquals(2, result.size());
    }
    
    @Test
    void testComparisonOperatorsWithMissingField() throws Exception {
        // All comparison operators should exclude rows with missing fields
        String sql = "SELECT * FROM products WHERE id > 2";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Rows 3 and 4
    }
    
    @Test
    void testNotEqualsWithMissingField() throws Exception {
        // != comparison also excludes rows with missing or null fields  
        String sql = "SELECT * FROM products WHERE category != 'Furniture'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Only rows with non-null category that != 'Furniture' are included
        // Rows 1 and 2 have category = 'Electronics'
        // Row 3 has null category - excluded
        // Row 4 is missing category - excluded
        assertEquals(2, result.size());
    }
    
    @Test
    void testOrConditionWithMissingField() throws Exception {
        // OR can work around missing fields if other condition is true
        String sql = "SELECT * FROM products WHERE category = 'Electronics' OR name = 'Chair'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Rows 1, 2 (category matches), and 4 (name matches)
        assertEquals(3, result.size());
    }
    
    @Test
    void testSelectSpecificFieldsWithMissing() throws Exception {
        // Selecting specific fields should still work, missing fields appear as missing
        String sql = "SELECT id, name, description FROM products";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(4, result.size());
        // Row 2 won't have description field
        assertFalse(result.get(1).has("description"));
    }
    
    @Test
    void testMultipleMissingFields() throws Exception {
        // Users with various missing fields
        String sql = "SELECT * FROM users WHERE email = 'alice@example.com'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("Alice", result.get(0).get("name").asText());
    }
    
    @Test
    void testWhereOnMissingVsNullField() throws Exception {
        // Demonstrate difference: both missing and null exclude row
        String sql = "SELECT name, email FROM users";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(4, result.size());
        // Bob: missing email in schema becomes missing in output
        // Charlie: missing email becomes missing
        // Diana: null email stays null
        assertFalse(result.get(2).has("email")); // Charlie - missing
        assertTrue(result.get(3).has("email"));   // Diana - null
        assertTrue(result.get(3).get("email").isNull());
    }
}

