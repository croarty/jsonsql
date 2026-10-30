package com.jsonsql.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages disk-based caching of parsed JSON data.
 * Caches are stored in a .jsonsql-cache directory and persist between executions.
 */
public class CacheManager {
    private final File cacheDirectory;
    private final ObjectMapper objectMapper;
    private final TypeFactory typeFactory;
    
    public CacheManager(File dataDirectory) {
        // Create cache directory in the data directory
        this.cacheDirectory = new File(dataDirectory, ".jsonsql-cache");
        this.objectMapper = new ObjectMapper();
        this.typeFactory = TypeFactory.defaultInstance();
        
        // Ensure cache directory exists
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs() && !cacheDirectory.exists()) {
            throw new IllegalStateException(
                "Failed to create cache directory: " + cacheDirectory.getAbsolutePath());
        }
    }
    
    /**
     * Get cached data for a file and JSONPath combination, if it exists.
     * 
     * @param jsonFile The JSON file that was cached
     * @param jsonPathExpression The JSONPath expression used to extract data
     * @return Cached data, or null if cache doesn't exist
     */
    public List<JsonNode> getCachedData(File jsonFile, String jsonPathExpression) {
        File cacheFile = getCacheFile(jsonFile, jsonPathExpression);
        
        if (!cacheFile.exists()) {
            return null;
        }
        
        try {
            // Read cached JSON array
            JsonNode cacheArray = objectMapper.readTree(cacheFile);
            
            // Convert to List<JsonNode>
            CollectionType listType = typeFactory.constructCollectionType(List.class, JsonNode.class);
            List<JsonNode> cachedData = objectMapper.readValue(cacheArray.toString(), listType);
            
            return cachedData;
        } catch (IOException e) {
            // If cache is corrupted, return null to force reload
            return null;
        }
    }
    
    /**
     * Save parsed data to cache.
     * 
     * @param jsonFile The source JSON file
     * @param jsonPathExpression The JSONPath expression used to extract data
     * @param data The parsed data to cache
     * @throws IOException If cache write fails
     */
    public void saveToCache(File jsonFile, String jsonPathExpression, List<JsonNode> data) throws IOException {
        File cacheFile = getCacheFile(jsonFile, jsonPathExpression);
        
        // Ensure parent directory exists
        File parentDir = cacheFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs() && !parentDir.exists()) {
            throw new IOException("Failed to create cache directory: " + parentDir.getAbsolutePath());
        }
        
        // Write data as JSON array
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile, data);
    }
    
    /**
     * Clear all cached files, including CTE caches.
     *
     * @return Number of cache files cleared
     */
    public int clearAllCaches() {
        int clearedCount = 0;
        
        // Clear all cache files (handles all JSONPath variations for all files, plus CTE caches)
        // This is the correct behavior for --clear-cache: clear everything
        try {
            File[] cacheFiles = cacheDirectory.listFiles((dir, name) -> name.endsWith(".cache"));
            if (cacheFiles != null) {
                for (File cacheFile : cacheFiles) {
                    if (cacheFile.delete()) {
                        clearedCount++;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors when cleaning cache files
        }
        
        return clearedCount;
    }
    
    /**
     * Get the cache file path for a given JSON file and JSONPath combination.
     * Uses a hash of the absolute path + JSONPath to create a unique cache filename.
     * This ensures different JSONPaths on the same file get different cache entries.
     */
    private File getCacheFile(File jsonFile, String jsonPathExpression) {
        // Fold the source file's last-modified time and size into the cache key so that
        // editing the source JSON automatically invalidates the cache (a changed file
        // produces a different cache filename, i.e. a cache miss).
        long lastModified = jsonFile.lastModified();
        long length = jsonFile.length();
        try {
            String absolutePath = jsonFile.getAbsolutePath();
            // Combine file path, JSONPath, mtime and size for a freshness-sensitive cache key
            String cacheKey = absolutePath + "|" + jsonPathExpression + "|" + lastModified + "|" + length;
            String hash = hashPath(cacheKey);
            String cacheFileName = hash + ".cache";
            return new File(cacheDirectory, cacheFileName);
        } catch (Exception e) {
            // Fallback to simple filename-based cache (still freshness-sensitive)
            String fileName = jsonFile.getName();
            String jsonPathHash = jsonPathExpression != null ? 
                String.valueOf(jsonPathExpression.hashCode()) : "default";
            String cacheFileName = fileName.replace(".json", "") + "_" + jsonPathHash
                + "_" + lastModified + "_" + length + ".cache";
            return new File(cacheDirectory, cacheFileName);
        }
    }
    
    /**
     * Create a hash of the cache key (file path + JSONPath) for cache naming.
     * This ensures unique cache files for different JSONPath expressions on the same file.
     */
    private String hashPath(String cacheKey) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = md.digest(cacheKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Get the cache directory path.
     */
    public File getCacheDirectory() {
        return cacheDirectory;
    }
    
    /**
     * Get cached CTE result, if it exists.
     * 
     * @param cteCacheKey A unique key identifying the CTE query
     * @return Cached CTE data, or null if cache doesn't exist
     */
    public List<JsonNode> getCachedCTE(String cteCacheKey) {
        File cacheFile = getCTECacheFile(cteCacheKey);
        
        if (!cacheFile.exists()) {
            return null;
        }
        
        try {
            // Read cached JSON array
            JsonNode cacheArray = objectMapper.readTree(cacheFile);
            
            // Convert to List<JsonNode>
            CollectionType listType = typeFactory.constructCollectionType(List.class, JsonNode.class);
            List<JsonNode> cachedData = objectMapper.readValue(cacheArray.toString(), listType);
            
            return cachedData;
        } catch (IOException e) {
            // If cache is corrupted, return null to force reload
            return null;
        }
    }
    
    /**
     * Save CTE result to cache.
     * 
     * @param cteCacheKey A unique key identifying the CTE query
     * @param data The CTE result data to cache
     * @throws IOException If cache write fails
     */
    public void saveCTEToCache(String cteCacheKey, List<JsonNode> data) throws IOException {
        File cacheFile = getCTECacheFile(cteCacheKey);
        
        // Ensure parent directory exists
        File parentDir = cacheFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs() && !parentDir.exists()) {
            throw new IOException("Failed to create cache directory: " + parentDir.getAbsolutePath());
        }
        
        // Write data as JSON array
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile, data);
    }
    
    /**
     * Get the cache file path for a CTE result.
     * Uses a hash of the cache key to create a unique filename.
     */
    private File getCTECacheFile(String cteCacheKey) {
        try {
            String hash = hashPath(cteCacheKey);
            String cacheFileName = "cte_" + hash + ".cache";
            return new File(cacheDirectory, cacheFileName);
        } catch (Exception e) {
            // Fallback to simple hash-based cache
            String cacheFileName = "cte_" + String.valueOf(cteCacheKey.hashCode()) + ".cache";
            return new File(cacheDirectory, cacheFileName);
        }
    }
}

