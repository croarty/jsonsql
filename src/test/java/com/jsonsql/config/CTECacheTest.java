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
 * Tests for CTE result caching functionality.
 */
class CTECacheTest {
    
    private File testDir;
    private QueryExecutor executorWithCache;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    private CacheManager cacheManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("cte-cache-test").toFile();
        
        // Set up mappings
        File mappingFile = new File(testDir, ".jsonsql-mappings.json");
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "$.products");
        
        // Create test data
        createTestFiles();
        
        // Create executor with cache
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
                {"id": 1, "name": "Laptop", "price": 999.99, "category": "Electronics", "inStock": true},
                {"id": 2, "name": "Desk", "price": 299.99, "category": "Furniture", "inStock": true},
                {"id": 3, "name": "Monitor", "price": 199.99, "category": "Electronics", "inStock": false},
                {"id": 4, "name": "Chair", "price": 149.99, "category": "Furniture", "inStock": true},
                {"id": 5, "name": "Keyboard", "price": 79.99, "category": "Electronics", "inStock": false}
              ]
            }
            """;
        
        Files.writeString(new File(testDir, "products.json").toPath(), productsJson);
    }
    
    @Test
    void testCTECacheCreated() throws Exception {
        String query = """
            WITH inStockProducts AS (
              SELECT * FROM products WHERE inStock = true
            )
            SELECT * FROM inStockProducts
            """;
        
        // First execution - should create CTE cache
        String result1 = executorWithCache.execute(query);
        JsonNode resultNode1 = objectMapper.readTree(result1);
        assertEquals(3, resultNode1.size(), "Should return 3 in-stock products");
        
        // Verify CTE cache file was created
        File cacheDir = new File(testDir, ".jsonsql-cache");
        File[] cteCacheFiles = cacheDir.listFiles((dir, name) -> name.startsWith("cte_") && name.endsWith(".cache"));
        assertNotNull(cteCacheFiles, "CTE cache files should exist");
        assertTrue(cteCacheFiles.length > 0, "At least one CTE cache file should be created");
    }
    
    @Test
    void testCTECacheReused() throws Exception {
        String query = """
            WITH inStockProducts AS (
              SELECT * FROM products WHERE inStock = true
            )
            SELECT * FROM inStockProducts
            """;
        
        // First execution - creates cache
        String result1 = executorWithCache.execute(query);
        JsonNode resultNode1 = objectMapper.readTree(result1);
        assertEquals(3, resultNode1.size(), "First execution should return 3 products");
        
        // Second execution - should use cached CTE
        String result2 = executorWithCache.execute(query);
        JsonNode resultNode2 = objectMapper.readTree(result2);
        
        // Results should be identical
        assertEquals(resultNode1.size(), resultNode2.size(), "Results should be identical");
        assertEquals(
            resultNode1.get(0).get("id").asInt(),
            resultNode2.get(0).get("id").asInt(),
            "First item should be identical"
        );
    }
    
    @Test
    void testDifferentCTEsHaveDifferentCaches() throws Exception {
        String query1 = """
            WITH inStockProducts AS (
              SELECT * FROM products WHERE inStock = true
            )
            SELECT * FROM inStockProducts
            """;
        
        String query2 = """
            WITH electronicsProducts AS (
              SELECT * FROM products WHERE category = 'Electronics'
            )
            SELECT * FROM electronicsProducts
            """;
        
        // Execute both queries
        String result1 = executorWithCache.execute(query1);
        String result2 = executorWithCache.execute(query2);
        
        JsonNode resultNode1 = objectMapper.readTree(result1);
        JsonNode resultNode2 = objectMapper.readTree(result2);
        
        // Should have different results
        assertEquals(3, resultNode1.size(), "inStockProducts should have 3 items");
        assertEquals(3, resultNode2.size(), "electronicsProducts should have 3 items");
        
        // Verify both CTE caches exist
        File cacheDir = new File(testDir, ".jsonsql-cache");
        File[] cteCacheFiles = cacheDir.listFiles((dir, name) -> name.startsWith("cte_") && name.endsWith(".cache"));
        assertNotNull(cteCacheFiles);
        assertTrue(cteCacheFiles.length >= 2, "Should have at least 2 CTE cache files");
    }
    
    @Test
    void testCTECacheClearedWithClearCache() throws Exception {
        String query = """
            WITH inStockProducts AS (
              SELECT * FROM products WHERE inStock = true
            )
            SELECT * FROM inStockProducts
            """;
        
        // Execute with cache - creates CTE cache
        executorWithCache.execute(query);
        
        // Verify CTE cache exists
        File cacheDir = new File(testDir, ".jsonsql-cache");
        File[] cteCacheFilesBefore = cacheDir.listFiles((dir, name) -> name.startsWith("cte_") && name.endsWith(".cache"));
        assertNotNull(cteCacheFilesBefore);
        assertTrue(cteCacheFilesBefore.length > 0, "CTE cache should exist");
        
        // Clear cache
        int clearedCount = cacheManager.clearAllCaches(mappingManager, testDir);
        assertTrue(clearedCount > 0, "Should clear at least one cache file");
        
        // Verify CTE cache is gone
        File[] cteCacheFilesAfter = cacheDir.listFiles((dir, name) -> name.startsWith("cte_") && name.endsWith(".cache"));
        assertTrue(cteCacheFilesAfter == null || cteCacheFilesAfter.length == 0, "CTE cache should be cleared");
        
        // Next query should recreate CTE cache
        executorWithCache.execute(query);
        File[] cteCacheFilesAfterQuery = cacheDir.listFiles((dir, name) -> name.startsWith("cte_") && name.endsWith(".cache"));
        assertNotNull(cteCacheFilesAfterQuery);
        assertTrue(cteCacheFilesAfterQuery.length > 0, "CTE cache should be recreated");
    }
}

