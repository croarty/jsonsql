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
 * Comprehensive tests for SELECT projection, especially that explicitly selected columns
 * are always present in output (missing/null source values emitted as JSON null).
 */
class ProjectionTest {

    @TempDir
    Path tempDir;

    private QueryExecutor executor;
    private MappingManager mappingManager;
    private ObjectMapper objectMapper;
    private File dataDir;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        String products = """
            {"products": [
                {"id": 1, "name": "Widget", "category": "Tools", "price": 10},
                {"id": 2, "name": "Gadget"},
                {"id": 3, "name": "Doohickey", "category": null}
            ]}
            """;
        Files.writeString(dataDir.toPath().resolve("products.json"), products);
        File mappingFile = tempDir.resolve(".jsonsql-mappings.json").toFile();
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "products.json:$.products");
        executor = new QueryExecutor(mappingManager, dataDir);
    }

    private JsonNode run(String sql) throws Exception {
        return objectMapper.readTree(executor.execute(sql));
    }

    @Test
    void testMissingColumnEmittedAsNull() throws Exception {
        JsonNode result = run("SELECT id, category FROM products");
        assertEquals(3, result.size());
        // Row 2 is missing category in source -> present as null
        assertTrue(result.get(1).has("category"));
        assertTrue(result.get(1).get("category").isNull());
    }

    @Test
    void testExplicitNullColumnEmittedAsNull() throws Exception {
        JsonNode result = run("SELECT id, category FROM products");
        // Row 3 has explicit null category -> present as null
        assertTrue(result.get(2).has("category"));
        assertTrue(result.get(2).get("category").isNull());
    }

    @Test
    void testPresentColumnEmittedWithValue() throws Exception {
        JsonNode result = run("SELECT id, category FROM products");
        assertEquals("Tools", result.get(0).get("category").asText());
    }

    @Test
    void testCompletelyAbsentColumnAlwaysPresent() throws Exception {
        // A column that exists in no row is still emitted as null for every row
        JsonNode result = run("SELECT id, nonexistent FROM products");
        assertEquals(3, result.size());
        for (JsonNode row : result) {
            assertTrue(row.has("nonexistent"));
            assertTrue(row.get("nonexistent").isNull());
        }
    }

    @Test
    void testAllRowsHaveSameProjectedKeys() throws Exception {
        // Stable schema: every projected row should have the same set of keys
        JsonNode result = run("SELECT id, name, category, price FROM products");
        for (JsonNode row : result) {
            assertTrue(row.has("id"));
            assertTrue(row.has("name"));
            assertTrue(row.has("category"));
            assertTrue(row.has("price"));
        }
    }

    @Test
    void testSelectStarStillOmitsMissingFields() throws Exception {
        // SELECT * flattens whatever fields exist; missing fields remain absent (unchanged behavior)
        JsonNode result = run("SELECT * FROM products");
        assertFalse(result.get(1).has("category")); // Gadget missing category
        assertTrue(result.get(2).has("category"));   // Doohickey explicit null
        assertTrue(result.get(2).get("category").isNull());
    }

    @Test
    void testJoinCollisionUsesQualifiedNames() throws Exception {
        // When two selected columns share a simple name, qualified names are used as output keys
        Files.writeString(dataDir.toPath().resolve("left.json"),
            "{\"items\": [{\"id\": 1, \"name\": \"L\"}]}");
        Files.writeString(dataDir.toPath().resolve("right.json"),
            "{\"items\": [{\"id\": 1, \"name\": \"R\"}]}");
        mappingManager.addMapping("lt", "left.json:$.items");
        mappingManager.addMapping("rt", "right.json:$.items");

        JsonNode result = run(
            "SELECT l.name, r.name FROM lt l JOIN rt r ON l.id = r.id");
        assertEquals(1, result.size());
        JsonNode row = result.get(0);
        assertTrue(row.has("l.name"));
        assertTrue(row.has("r.name"));
        assertEquals("L", row.get("l.name").asText());
        assertEquals("R", row.get("r.name").asText());
    }
}
