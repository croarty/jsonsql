package com.jsonsql.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.query.QueryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests that the disk cache is invalidated when the underlying source
 * JSON file changes (freshness), and remains a hit when it does not.
 */
class CacheFreshnessTest {

    @TempDir
    Path tempDir;

    private File dataDir;
    private File sourceFile;
    private MappingManager mappingManager;
    private CacheManager cacheManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        sourceFile = dataDir.toPath().resolve("products.json").toFile();
        Files.writeString(sourceFile.toPath(), """
            {"products": [{"id": 1, "name": "Widget"}]}
            """);
        File mappingFile = tempDir.resolve(".jsonsql-mappings.json").toFile();
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "products.json:$.products");
        cacheManager = new CacheManager(dataDir);
    }

    @Test
    void testQueryReturnsFreshDataAfterSourceChange() throws Exception {
        QueryExecutor executor = new QueryExecutor(mappingManager, dataDir, cacheManager);

        JsonNode first = objectMapper.readTree(executor.execute("SELECT * FROM products"));
        assertEquals(1, first.size());
        assertEquals("Widget", first.get(0).get("name").asText());

        // Modify the source: add a product (changes content and length)
        Files.writeString(sourceFile.toPath(), """
            {"products": [{"id": 1, "name": "Widget"}, {"id": 2, "name": "Gadget"}]}
            """);

        JsonNode second = objectMapper.readTree(executor.execute("SELECT * FROM products"));
        assertEquals(2, second.size(), "Cache should be invalidated after the source file changes");
    }

    @Test
    void testCacheManagerMissAfterFileModification() throws Exception {
        List<JsonNode> data = new ArrayList<>();
        data.add(objectMapper.readTree("{\"id\": 1}"));

        cacheManager.saveToCache(sourceFile, "$.products", data);
        assertNotNull(cacheManager.getCachedData(sourceFile, "$.products"), "Should be a hit before change");

        // Change the file content/length
        Files.writeString(sourceFile.toPath(), """
            {"products": [{"id": 1, "name": "Widget"}, {"id": 2, "name": "Gadget"}]}
            """);

        assertNull(cacheManager.getCachedData(sourceFile, "$.products"),
            "Should be a miss after the source file changes");
    }

    @Test
    void testCacheHitWhenSourceUnchanged() throws Exception {
        List<JsonNode> data = new ArrayList<>();
        data.add(objectMapper.readTree("{\"id\": 42}"));

        cacheManager.saveToCache(sourceFile, "$.products", data);
        List<JsonNode> cached = cacheManager.getCachedData(sourceFile, "$.products");
        assertNotNull(cached);
        assertEquals(1, cached.size());
        assertEquals(42, cached.get(0).get("id").asInt());
    }

    @Test
    void testCorruptCacheTreatedAsMiss() throws Exception {
        List<JsonNode> data = new ArrayList<>();
        data.add(objectMapper.readTree("{\"id\": 1}"));
        cacheManager.saveToCache(sourceFile, "$.products", data);

        // Corrupt every cache file
        File[] cacheFiles = cacheManager.getCacheDirectory().listFiles((d, n) -> n.endsWith(".cache"));
        assertNotNull(cacheFiles);
        for (File f : cacheFiles) {
            Files.writeString(f.toPath(), "{ this is not valid json ]");
        }

        assertNull(cacheManager.getCachedData(sourceFile, "$.products"),
            "Corrupt cache file should be treated as a miss");
    }
}
