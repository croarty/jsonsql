package com.jsonsql.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.query.QueryExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for caching functionality with QueryExecutor.
 */
class CacheIntegrationTest {
    
    private File testDir;
    private QueryExecutor executorWithoutCache;
    private QueryExecutor executorWithCache;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    private CacheManager cacheManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("cache-integration-test").toFile();
        
        // Set up mappings
        File mappingFile = new File(testDir, ".jsonsql-mappings.json");
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "$.products");
        
        // Create test data
        createTestFiles();
        
        // Create executors
        executorWithoutCache = new QueryExecutor(mappingManager, testDir);
        cacheManager = new CacheManager(testDir);
        executorWithCache = new QueryExecutor(mappingManager, testDir, cacheManager);
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
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
    
    private void createTestFiles() throws IOException {
        String productsJson = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "price": 999.99, "category": "Electronics"},
                {"id": 2, "name": "Desk", "price": 299.99, "category": "Furniture"},
                {"id": 3, "name": "Monitor", "price": 199.99, "category": "Electronics"},
                {"id": 4, "name": "Chair", "price": 149.99, "category": "Furniture"}
              ]
            }
            """;
        
        Files.writeString(new File(testDir, "products.json").toPath(), productsJson);
    }
    
    @Test
    void testFirstQueryCreatesCache() throws Exception {
        // First query with cache enabled should create cache
        String query = "SELECT * FROM products WHERE category = 'Electronics'";
        
        String result1 = executorWithCache.execute(query);
        JsonNode resultNode1 = objectMapper.readTree(result1);
        assertEquals(2, resultNode1.size(), "Should return 2 electronics products");
        
        // Verify cache file was created
        File cacheDir = new File(testDir, ".jsonsql-cache");
        assertTrue(cacheDir.exists(), "Cache directory should exist");
        
        File[] cacheFiles = cacheDir.listFiles((dir, name) -> name.endsWith(".cache"));
        assertNotNull(cacheFiles, "Cache files should exist");
        assertTrue(cacheFiles.length > 0, "At least one cache file should be created");
    }
    
    @Test
    void testSecondQueryUsesCache() throws Exception {
        String query = "SELECT * FROM products WHERE price > 200";
        
        // First execution - creates cache
        String result1 = executorWithCache.execute(query);
        JsonNode resultNode1 = objectMapper.readTree(result1);
        assertEquals(2, resultNode1.size(), "First execution should return 2 products");
        
        // Second execution - should use cache
        // We can't directly verify cache usage, but we can verify results are identical
        String result2 = executorWithCache.execute(query);
        JsonNode resultNode2 = objectMapper.readTree(result2);
        
        assertEquals(resultNode1.size(), resultNode2.size(), "Results should be identical");
        assertEquals(
            resultNode1.get(0).get("id").asInt(),
            resultNode2.get(0).get("id").asInt(),
            "First item should be identical"
        );
    }
    
    @Test
    void testCacheWorksWithJoins() throws Exception {
        // Add orders mapping
        mappingManager.addMapping("orders", "$.orders");
        
        String ordersJson = """
            {
              "orders": [
                {"id": 1, "productId": 1, "quantity": 2},
                {"id": 2, "productId": 3, "quantity": 1}
              ]
            }
            """;
        Files.writeString(new File(testDir, "orders.json").toPath(), ordersJson);
        
        String query = "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id";
        
        // First execution - creates caches for both tables
        String result1 = executorWithCache.execute(query);
        JsonNode resultNode1 = objectMapper.readTree(result1);
        assertEquals(2, resultNode1.size(), "Should return 2 joined rows");
        
        // Second execution - should use cached data
        String result2 = executorWithCache.execute(query);
        JsonNode resultNode2 = objectMapper.readTree(result2);
        
        assertEquals(resultNode1.size(), resultNode2.size(), "Results should be identical");
    }
    
    @Test
    void testCacheClearedAfterClearCache() throws Exception {
        String query = "SELECT * FROM products";
        
        // Execute with cache - creates cache
        executorWithCache.execute(query);
        
        // Verify cache exists
        File cacheDir = new File(testDir, ".jsonsql-cache");
        File[] cacheFilesBefore = cacheDir.listFiles((dir, name) -> name.endsWith(".cache"));
        assertNotNull(cacheFilesBefore);
        assertTrue(cacheFilesBefore.length > 0, "Cache should exist");
        
        // Clear cache
        int clearedCount = cacheManager.clearAllCaches(mappingManager, testDir);
        assertTrue(clearedCount > 0, "Should clear at least one cache file");
        
        // Verify cache is gone
        File[] cacheFilesAfter = cacheDir.listFiles((dir, name) -> name.endsWith(".cache"));
        assertTrue(cacheFilesAfter == null || cacheFilesAfter.length == 0, "Cache should be cleared");
        
        // Next query should recreate cache
        executorWithCache.execute(query);
        File[] cacheFilesAfterQuery = cacheDir.listFiles((dir, name) -> name.endsWith(".cache"));
        assertNotNull(cacheFilesAfterQuery);
        assertTrue(cacheFilesAfterQuery.length > 0, "Cache should be recreated");
    }
    
    @Test
    void testNoCacheWhenCacheDisabled() throws Exception {
        // Use a fresh test directory to ensure no cache directory exists
        File freshTestDir = Files.createTempDirectory("cache-test-no-cache").toFile();
        try {
            // Copy test file to fresh directory
            Files.writeString(new File(freshTestDir, "products.json").toPath(), 
                Files.readString(new File(testDir, "products.json").toPath()));
            
            // Create executor without cache
            QueryExecutor executorNoCache = new QueryExecutor(mappingManager, freshTestDir);
            
            String query = "SELECT * FROM products";
            executorNoCache.execute(query);
            
            // Verify no cache directory was created
            File cacheDir = new File(freshTestDir, ".jsonsql-cache");
            assertFalse(cacheDir.exists(), "Cache directory should not exist when caching disabled");
        } finally {
            deleteDirectory(freshTestDir);
        }
    }
    
    @Test
    void testCacheWithComplexQuery() throws Exception {
        String query = """
            SELECT name, price, category 
            FROM products 
            WHERE price > 150 AND category IN ('Electronics', 'Furniture')
            ORDER BY price DESC
            LIMIT 3
            """;
        
        // First execution
        String result1 = executorWithCache.execute(query);
        JsonNode resultNode1 = objectMapper.readTree(result1);
        
        // Second execution - should use cache
        String result2 = executorWithCache.execute(query);
        JsonNode resultNode2 = objectMapper.readTree(result2);
        
        // Results should be identical
        assertEquals(resultNode1.size(), resultNode2.size(), "Results should be identical");
        if (resultNode1.size() > 0) {
            assertEquals(
                resultNode1.get(0).get("name").asText(),
                resultNode2.get(0).get("name").asText(),
                "First item should be identical"
            );
        }
    }
    
    @Test
    void testCacheWithMultipleTables() throws Exception {
        mappingManager.addMapping("orders", "$.orders");
        mappingManager.addMapping("customers", "$.customers");
        
        String ordersJson = "{\"orders\": [{\"id\": 1, \"customerId\": 1}]}";
        String customersJson = "{\"customers\": [{\"id\": 1, \"name\": \"John\"}]}";
        
        Files.writeString(new File(testDir, "orders.json").toPath(), ordersJson);
        Files.writeString(new File(testDir, "customers.json").toPath(), customersJson);
        
        String query = "SELECT * FROM products";
        
        // Execute query - should cache products
        executorWithCache.execute(query);
        
        // Execute query on different table - should cache orders
        String query2 = "SELECT * FROM orders";
        executorWithCache.execute(query2);
        
        // Verify both caches exist
        File cacheDir = new File(testDir, ".jsonsql-cache");
        File[] cacheFiles = cacheDir.listFiles((dir, name) -> name.endsWith(".cache"));
        assertNotNull(cacheFiles);
        assertTrue(cacheFiles.length >= 2, "Should have caches for multiple tables");
    }
}

