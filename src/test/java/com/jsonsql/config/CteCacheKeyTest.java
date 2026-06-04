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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests that the CTE cache key distinguishes CTEs that differ only by
 * SELECT list, ORDER BY, or LIMIT, and is invalidated when the source changes.
 */
class CteCacheKeyTest {

    @TempDir
    Path tempDir;

    private File dataDir;
    private File sourceFile;
    private MappingManager mappingManager;
    private QueryExecutor executor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        sourceFile = dataDir.toPath().resolve("products.json").toFile();
        Files.writeString(sourceFile.toPath(), """
            {"products": [
                {"id": 1, "name": "Laptop", "price": 900},
                {"id": 2, "name": "Phone", "price": 700},
                {"id": 3, "name": "Monitor", "price": 300},
                {"id": 4, "name": "Cable", "price": 20}
            ]}
            """);
        File mappingFile = tempDir.resolve(".jsonsql-mappings.json").toFile();
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "products.json:$.products");
        CacheManager cacheManager = new CacheManager(dataDir);
        executor = new QueryExecutor(mappingManager, dataDir, cacheManager);
    }

    private JsonNode run(String sql) throws Exception {
        return objectMapper.readTree(executor.execute(sql));
    }

    @Test
    void testCteDifferingByLimitNotConfused() throws Exception {
        // Same CTE name "t", same table, but different LIMIT. The second must not reuse the first.
        JsonNode first = run("WITH t AS (SELECT name FROM products ORDER BY price DESC LIMIT 1) SELECT * FROM t");
        assertEquals(1, first.size());

        JsonNode second = run("WITH t AS (SELECT name FROM products ORDER BY price DESC LIMIT 3) SELECT * FROM t");
        assertEquals(3, second.size(), "CTE cache key must include LIMIT");
    }

    @Test
    void testCteDifferingByOrderByNotConfused() throws Exception {
        JsonNode asc = run("WITH t AS (SELECT name FROM products ORDER BY price ASC LIMIT 1) SELECT * FROM t");
        assertEquals("Cable", asc.get(0).get("name").asText());

        JsonNode desc = run("WITH t AS (SELECT name FROM products ORDER BY price DESC LIMIT 1) SELECT * FROM t");
        assertEquals("Laptop", desc.get(0).get("name").asText(), "CTE cache key must include ORDER BY direction");
    }

    @Test
    void testCteDifferingBySelectListNotConfused() throws Exception {
        JsonNode nameOnly = run("WITH t AS (SELECT name FROM products WHERE id = 1) SELECT * FROM t");
        assertTrue(nameOnly.get(0).has("name"));
        assertFalse(nameOnly.get(0).has("price"));

        JsonNode priceOnly = run("WITH t AS (SELECT price FROM products WHERE id = 1) SELECT * FROM t");
        assertTrue(priceOnly.get(0).has("price"), "CTE cache key must include SELECT list");
        assertFalse(priceOnly.get(0).has("name"));
    }

    @Test
    void testCteCacheInvalidatedOnSourceChange() throws Exception {
        JsonNode first = run("WITH t AS (SELECT name FROM products) SELECT * FROM t");
        assertEquals(4, first.size());

        // Add a product (changes content and length)
        Files.writeString(sourceFile.toPath(), """
            {"products": [
                {"id": 1, "name": "Laptop", "price": 900},
                {"id": 2, "name": "Phone", "price": 700},
                {"id": 3, "name": "Monitor", "price": 300},
                {"id": 4, "name": "Cable", "price": 20},
                {"id": 5, "name": "Mouse", "price": 30}
            ]}
            """);

        JsonNode second = run("WITH t AS (SELECT name FROM products) SELECT * FROM t");
        assertEquals(5, second.size(), "CTE cache must be invalidated when the source file changes");
    }

    @Test
    void testRepeatedIdenticalCteQueryIsStable() throws Exception {
        // Cache hit path: identical query twice returns identical results
        JsonNode first = run("WITH t AS (SELECT name FROM products WHERE price > 100) SELECT * FROM t");
        JsonNode second = run("WITH t AS (SELECT name FROM products WHERE price > 100) SELECT * FROM t");
        assertEquals(first, second);
        assertEquals(3, first.size());
    }
}
