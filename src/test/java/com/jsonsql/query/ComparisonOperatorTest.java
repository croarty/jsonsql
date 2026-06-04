package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for >, <, >=, <= comparisons, including lexicographic string fallback.
 */
class ComparisonOperatorTest {

    @TempDir
    Path tempDir;

    private QueryExecutor executor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        File dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        String products = """
            {"products": [
                {"id": 1, "name": "Apple", "price": 10},
                {"id": 2, "name": "Mango", "price": 25},
                {"id": 3, "name": "Zucchini", "price": 5}
            ]}
            """;
        Files.writeString(dataDir.toPath().resolve("products.json"), products);
        File mappingFile = tempDir.resolve(".jsonsql-mappings.json").toFile();
        MappingManager mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "products.json:$.products");
        executor = new QueryExecutor(mappingManager, dataDir);
    }

    private JsonNode run(String sql) throws Exception {
        return objectMapper.readTree(executor.execute(sql));
    }

    @Test
    void testStringGreaterThan() throws Exception {
        // Lexicographic: names greater than "M" -> Mango, Zucchini
        JsonNode result = run("SELECT name FROM products WHERE name > 'M'");
        assertEquals(2, result.size());
    }

    @Test
    void testStringLessThan() throws Exception {
        // Lexicographic: names less than "M" -> Apple
        JsonNode result = run("SELECT name FROM products WHERE name < 'M'");
        assertEquals(1, result.size());
        assertEquals("Apple", result.get(0).get("name").asText());
    }

    @Test
    void testStringGreaterThanOrEqual() throws Exception {
        // >= with an exact match
        JsonNode result = run("SELECT name FROM products WHERE name >= 'Mango'");
        assertEquals(2, result.size()); // Mango, Zucchini
    }

    @Test
    void testNumericGreaterThanStillWorks() throws Exception {
        JsonNode result = run("SELECT name FROM products WHERE price > 8");
        assertEquals(2, result.size()); // 10 and 25
    }

    @Test
    void testNumericLessThanStillWorks() throws Exception {
        JsonNode result = run("SELECT name FROM products WHERE price < 10");
        assertEquals(1, result.size());
        assertEquals("Zucchini", result.get(0).get("name").asText());
    }

    @Test
    void testStringComparisonIsCaseSensitiveLexicographic() throws Exception {
        // Uppercase letters sort before lowercase in lexicographic order; all names start
        // with uppercase, so > 'a' (lowercase) yields nothing.
        JsonNode result = run("SELECT name FROM products WHERE name > 'a'");
        assertEquals(0, result.size());
    }
}
