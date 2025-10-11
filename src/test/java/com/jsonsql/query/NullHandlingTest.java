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
 * Comprehensive tests for null value handling in WHERE clauses.
 */
class NullHandlingTest {
    
    private File testDir;
    private QueryExecutor executor;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("null-handling-test").toFile();
        
        // Create test data with nulls
        createTestDataWithNulls();
        
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
    
    private void createTestDataWithNulls() throws IOException {
        String json = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "description": "Gaming laptop", "category": "Electronics", "price": 1200},
                {"id": 2, "name": "Mouse", "description": null, "category": "Electronics", "price": 25},
                {"id": 3, "name": "Desk", "description": "Standing desk", "category": null, "price": 300},
                {"id": 4, "name": null, "description": "Mystery item", "category": "Unknown", "price": 50},
                {"id": 5, "name": "Chair", "description": null, "category": null, "price": null}
              ]
            }
            """;
        Files.writeString(new File(testDir, "products.json").toPath(), json);
    }
    
    @Test
    void testSelectWithNullFields() throws Exception {
        String sql = "SELECT * FROM products";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(5, result.size());
        // Verify nulls are preserved in output
        assertTrue(result.get(1).get("description").isNull());
        assertTrue(result.get(2).get("category").isNull());
        assertTrue(result.get(3).get("name").isNull());
    }
    
    @Test
    void testWhereComparisonWithNullField() throws Exception {
        // WHERE clause with comparison on null field should exclude that row
        String sql = "SELECT * FROM products WHERE description = 'Gaming laptop'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
    }
    
    @Test
    void testWhereNotEqualsWithNullField() throws Exception {
        // Null values don't match != comparison (SQL standard behavior)
        String sql = "SELECT * FROM products WHERE description != 'Gaming laptop'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Should return only items with non-null description that isn't 'Gaming laptop'
        assertEquals(2, result.size()); // "Standing desk" and "Mystery item"
    }
    
    @Test
    void testLikeWithNullField() throws Exception {
        // LIKE on null field should return false (exclude row)
        String sql = "SELECT * FROM products WHERE description LIKE '%laptop%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
    }
    
    @Test
    void testLikeOnNullFieldName() throws Exception {
        // LIKE on null name should exclude that row
        String sql = "SELECT * FROM products WHERE name LIKE 'L%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
        // Product with id=4 (null name) is excluded
    }
    
    @Test
    void testNotLikeWithNullField() throws Exception {
        // NOT LIKE on null field should also exclude that row
        String sql = "SELECT * FROM products WHERE description NOT LIKE '%desk%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Only returns items with non-null description that doesn't contain 'desk'
        assertEquals(2, result.size()); // "Gaming laptop" and "Mystery item"
    }
    
    @Test
    void testComplexWhereWithNulls() throws Exception {
        // Complex WHERE with AND - null values cause row to be excluded
        String sql = "SELECT * FROM products WHERE name LIKE '%e%' AND category = 'Electronics'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertEquals("Mouse", result.get(0).get("name").asText());
        // Desk is excluded because category is null
    }
    
    @Test
    void testOrWithNullValues() throws Exception {
        // OR condition - if one side is null, the other must be true
        String sql = "SELECT * FROM products WHERE category = 'Electronics' OR price > 200";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Laptop (both conditions true), Mouse (category matches), Desk (price > 200)
        // Chair excluded because price is null and category is null
    }
    
    @Test
    void testMultipleNullFields() throws Exception {
        // Product with multiple null fields
        String sql = "SELECT * FROM products WHERE id = 5";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(1, result.size());
        assertTrue(result.get(0).get("description").isNull());
        assertTrue(result.get(0).get("category").isNull());
        assertTrue(result.get(0).get("price").isNull());
    }
    
    @Test
    void testNumericComparisonWithNull() throws Exception {
        // Numeric comparison with null should exclude that row
        String sql = "SELECT * FROM products WHERE price > 100";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(2, result.size());
        // Laptop (1200) and Desk (300), Chair excluded because price is null
    }
    
    @Test
    void testNotConditionWithNull() throws Exception {
        // NOT with null value
        // Note: Full SQL three-valued logic (NULL as UNKNOWN) would exclude nulls,
        // but our implementation treats NOT false as true
        String sql = "SELECT * FROM products WHERE NOT (category = 'Electronics')";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        // Currently returns items where category comparison returns false (including nulls)
        // Future enhancement: implement full three-valued logic where NOT UNKNOWN = UNKNOWN
        assertEquals(3, result.size());
        // Returns: Desk (null category), Chair (missing category), Unknown item
    }
    
    @Test
    void testLikeWithPercentOnNullField() throws Exception {
        // LIKE '%' normally matches all strings, but should not match null
        String sql = "SELECT * FROM products WHERE description LIKE '%'";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(3, result.size());
        // Only returns items with non-null description
    }
    
    @Test
    void testCountWithNulls() throws Exception {
        // Verify all rows are returned, nulls are just preserved
        String sql = "SELECT id, name FROM products";
        String jsonResult = executor.execute(sql);
        JsonNode result = objectMapper.readTree(jsonResult);
        
        assertEquals(5, result.size());
        // Row with null name still appears, just has null value
        assertTrue(result.get(3).get("name").isNull());
    }
}

