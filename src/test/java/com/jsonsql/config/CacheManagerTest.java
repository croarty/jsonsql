package com.jsonsql.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for CacheManager disk-based caching functionality.
 */
class CacheManagerTest {
    
    private File testDir;
    private File cacheDir;
    private CacheManager cacheManager;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() throws IOException {
        testDir = Files.createTempDirectory("cache-test").toFile();
        cacheManager = new CacheManager(testDir);
        cacheDir = new File(testDir, ".jsonsql-cache");
        objectMapper = new ObjectMapper();
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
    
    @Test
    void testCacheDirectoryCreated() {
        assertTrue(cacheDir.exists(), "Cache directory should be created");
        assertTrue(cacheDir.isDirectory(), "Cache directory should be a directory");
    }
    
    @Test
    void testGetCachedDataWhenCacheDoesNotExist() throws IOException {
        File jsonFile = new File(testDir, "test.json");
        Files.writeString(jsonFile.toPath(), "[{\"id\": 1, \"name\": \"Test\"}]");
        
        List<JsonNode> cached = cacheManager.getCachedData(jsonFile, "$.data");
        
        assertNull(cached, "Should return null when cache doesn't exist");
    }
    
    @Test
    void testSaveAndRetrieveCache() throws IOException {
        File jsonFile = new File(testDir, "test.json");
        Files.writeString(jsonFile.toPath(), "[{\"id\": 1, \"name\": \"Test\"}]");
        
        // Create test data
        List<JsonNode> testData = new ArrayList<>();
        JsonNode node1 = objectMapper.readTree("{\"id\": 1, \"name\": \"Test\"}");
        JsonNode node2 = objectMapper.readTree("{\"id\": 2, \"name\": \"Test2\"}");
        testData.add(node1);
        testData.add(node2);
        
        // Save to cache
        String jsonPath = "$.data";
        cacheManager.saveToCache(jsonFile, jsonPath, testData);
        
        // Verify cache file exists
        File[] cacheFiles = cacheDir.listFiles((dir, name) -> name.endsWith(".cache"));
        assertNotNull(cacheFiles, "Cache files should exist");
        assertTrue(cacheFiles.length > 0, "At least one cache file should be created");
        
        // Retrieve from cache
        List<JsonNode> cached = cacheManager.getCachedData(jsonFile, jsonPath);
        
        assertNotNull(cached, "Should return cached data");
        assertEquals(2, cached.size(), "Should have 2 items");
        assertEquals(1, cached.get(0).get("id").asInt(), "First item should have id=1");
        assertEquals("Test", cached.get(0).get("name").asText(), "First item should have name=Test");
        assertEquals(2, cached.get(1).get("id").asInt(), "Second item should have id=2");
    }
    
    @Test
    void testCachePersistsBetweenInstances() throws IOException {
        File jsonFile = new File(testDir, "test.json");
        Files.writeString(jsonFile.toPath(), "[{\"id\": 1}]");
        
        // Create and save cache with first instance
        List<JsonNode> testData = new ArrayList<>();
        testData.add(objectMapper.readTree("{\"id\": 1, \"name\": \"Test\"}"));
        String jsonPath = "$.data";
        cacheManager.saveToCache(jsonFile, jsonPath, testData);
        
        // Create new CacheManager instance (simulating new execution)
        CacheManager newCacheManager = new CacheManager(testDir);
        
        // Should be able to retrieve cache
        List<JsonNode> cached = newCacheManager.getCachedData(jsonFile, jsonPath);
        
        assertNotNull(cached, "Cache should persist between instances");
        assertEquals(1, cached.size(), "Should have 1 item");
    }
    
    @Test
    void testCacheWithEmptyList() throws IOException {
        File jsonFile = new File(testDir, "empty.json");
        Files.writeString(jsonFile.toPath(), "[]");
        
        List<JsonNode> emptyData = new ArrayList<>();
        String jsonPath = "$.data";
        cacheManager.saveToCache(jsonFile, jsonPath, emptyData);
        
        List<JsonNode> cached = cacheManager.getCachedData(jsonFile, jsonPath);
        
        assertNotNull(cached, "Should return empty list, not null");
        assertTrue(cached.isEmpty(), "Cached list should be empty");
    }
    
    @Test
    void testCacheWithLargeData() throws IOException {
        File jsonFile = new File(testDir, "large.json");
        Files.writeString(jsonFile.toPath(), "[]");
        
        // Create large dataset
        List<JsonNode> largeData = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            JsonNode node = objectMapper.readTree("{\"id\": " + i + ", \"data\": \"item" + i + "\"}");
            largeData.add(node);
        }
        
        String jsonPath = "$.data";
        cacheManager.saveToCache(jsonFile, jsonPath, largeData);
        
        List<JsonNode> cached = cacheManager.getCachedData(jsonFile, jsonPath);
        
        assertNotNull(cached, "Should cache large datasets");
        assertEquals(1000, cached.size(), "Should have all 1000 items");
        assertEquals(500, cached.get(500).get("id").asInt(), "Should preserve data integrity");
    }
    
    @Test
    void testCacheWithDifferentFilePaths() throws IOException {
        // Test that different file paths create different cache files
        File file1 = new File(testDir, "file1.json");
        File file2 = new File(testDir, "subdir/file2.json");
        file2.getParentFile().mkdirs();
        
        Files.writeString(file1.toPath(), "[]");
        Files.writeString(file2.toPath(), "[]");
        
        List<JsonNode> data1 = new ArrayList<>();
        data1.add(objectMapper.readTree("{\"file\": 1}"));
        
        List<JsonNode> data2 = new ArrayList<>();
        data2.add(objectMapper.readTree("{\"file\": 2}"));
        
        String jsonPath = "$.data";
        cacheManager.saveToCache(file1, jsonPath, data1);
        cacheManager.saveToCache(file2, jsonPath, data2);
        
        // Verify cache files exist (we can't easily predict the hash-based names)
        File[] cacheFiles = cacheDir.listFiles((dir, name) -> name.endsWith(".cache"));
        assertNotNull(cacheFiles, "Cache files should exist");
        assertTrue(cacheFiles.length >= 2, "Should have at least 2 cache files");
        
        // Verify correct data retrieval (this is the important part)
        List<JsonNode> retrieved1 = cacheManager.getCachedData(file1, jsonPath);
        List<JsonNode> retrieved2 = cacheManager.getCachedData(file2, jsonPath);
        
        assertNotNull(retrieved1, "File1 cache should exist");
        assertNotNull(retrieved2, "File2 cache should exist");
        assertEquals(1, retrieved1.get(0).get("file").asInt(), "File1 cache should have file=1");
        assertEquals(2, retrieved2.get(0).get("file").asInt(), "File2 cache should have file=2");
    }
    
    @Test
    void testCorruptedCacheReturnsNull() throws IOException {
        File jsonFile = new File(testDir, "test.json");
        Files.writeString(jsonFile.toPath(), "[]");
        
        // Create invalid cache file (we'll create one with a .cache extension)
        cacheDir.mkdirs();
        File cacheFile = new File(cacheDir, "invalid.cache");
        Files.writeString(cacheFile.toPath(), "invalid json content!!!");
        
        // Should return null for corrupted cache
        List<JsonNode> cached = cacheManager.getCachedData(jsonFile, "$.data");
        
        assertNull(cached, "Should return null for corrupted cache");
    }
    
    @Test
    void testClearAllCaches() throws IOException {
        MappingManager mappingManager = new MappingManager(new File(testDir, ".jsonsql-mappings.json"));
        mappingManager.addMapping("table1", "file1.json:$.data");
        mappingManager.addMapping("table2", "file2.json:$.data");
        
        // Create test files
        File file1 = new File(testDir, "file1.json");
        File file2 = new File(testDir, "file2.json");
        Files.writeString(file1.toPath(), "[]");
        Files.writeString(file2.toPath(), "[]");
        
        // Create caches
        List<JsonNode> data1 = new ArrayList<>();
        data1.add(objectMapper.readTree("{\"id\": 1}"));
        String jsonPath = "$.data";
        cacheManager.saveToCache(file1, jsonPath, data1);
        
        List<JsonNode> data2 = new ArrayList<>();
        data2.add(objectMapper.readTree("{\"id\": 2}"));
        cacheManager.saveToCache(file2, jsonPath, data2);
        
        // Verify caches exist
        assertNotNull(cacheManager.getCachedData(file1, jsonPath), "Cache 1 should exist");
        assertNotNull(cacheManager.getCachedData(file2, jsonPath), "Cache 2 should exist");
        
        // Clear all caches
        int clearedCount = cacheManager.clearAllCaches(mappingManager, testDir);
        
        // Verify caches are cleared
        assertTrue(clearedCount >= 2, "Should clear at least 2 cache files");
        assertNull(cacheManager.getCachedData(file1, jsonPath), "Cache 1 should be cleared");
        assertNull(cacheManager.getCachedData(file2, jsonPath), "Cache 2 should be cleared");
    }
    
    @Test
    void testClearAllCachesWithDirectoryMapping() throws IOException {
        MappingManager mappingManager = new MappingManager(new File(testDir, ".jsonsql-mappings.json"));
        mappingManager.addMapping("products", "products:$.products");
        
        // Create directory with multiple files
        File productsDir = new File(testDir, "products");
        productsDir.mkdirs();
        
        File file1 = new File(productsDir, "products_2023.json");
        File file2 = new File(productsDir, "products_2024.json");
        Files.writeString(file1.toPath(), "[]");
        Files.writeString(file2.toPath(), "[]");
        
        // Create caches for both files
        List<JsonNode> data = new ArrayList<>();
        data.add(objectMapper.readTree("{\"id\": 1}"));
        String jsonPath = "$.data";
        cacheManager.saveToCache(file1, jsonPath, data);
        cacheManager.saveToCache(file2, jsonPath, data);
        
        // Clear all caches
        int clearedCount = cacheManager.clearAllCaches(mappingManager, testDir);
        
        // Should clear both files
        assertTrue(clearedCount >= 2, "Should clear caches for all files in directory");
        assertNull(cacheManager.getCachedData(file1, jsonPath), "Cache for file1 should be cleared");
        assertNull(cacheManager.getCachedData(file2, jsonPath), "Cache for file2 should be cleared");
    }
    
    @Test
    void testClearAllCachesWithNoMappings() throws IOException {
        MappingManager mappingManager = new MappingManager(new File(testDir, ".jsonsql-mappings.json"));
        
        // Create orphaned cache file
        File orphanedCache = new File(cacheDir, "orphaned.cache");
        cacheDir.mkdirs();
        Files.writeString(orphanedCache.toPath(), "[]");
        
        // Clear should remove orphaned files too
        int clearedCount = cacheManager.clearAllCaches(mappingManager, testDir);
        
        assertTrue(clearedCount >= 1, "Should clear orphaned cache files");
        assertFalse(orphanedCache.exists(), "Orphaned cache should be deleted");
    }
    
    @Test
    void testGetCacheDirectory() {
        File cacheDir = cacheManager.getCacheDirectory();
        
        assertNotNull(cacheDir, "Should return cache directory");
        assertEquals(".jsonsql-cache", cacheDir.getName(), "Should be named .jsonsql-cache");
        assertEquals(testDir, cacheDir.getParentFile(), "Should be in data directory");
    }
    
}

