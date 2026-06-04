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
 * Comprehensive tests that unsupported WHERE/IN constructs fail loudly with a clear
 * exception instead of silently filtering out all rows.
 */
class UnsupportedExpressionTest {

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
                {"id": 1, "name": "Widget", "price": 15},
                {"id": 2, "name": "Gadget", "price": 25}
            ]}
            """;
        Files.writeString(dataDir.toPath().resolve("products.json"), products);
        File mappingFile = tempDir.resolve(".jsonsql-mappings.json").toFile();
        MappingManager mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "products.json:$.products");
        executor = new QueryExecutor(mappingManager, dataDir);
    }

    @Test
    void testBetweenThrowsUnsupported() {
        UnsupportedExpressionException ex = assertThrows(
            UnsupportedExpressionException.class,
            () -> executor.execute("SELECT * FROM products WHERE price BETWEEN 10 AND 20"));
        assertTrue(ex.getMessage().toLowerCase().contains("unsupported"));
    }

    @Test
    void testSubqueryInThrowsUnsupported() {
        // IN with a subquery RHS is not a ParenthesedExpressionList -> unsupported, must throw
        assertThrows(UnsupportedExpressionException.class,
            () -> executor.execute("SELECT * FROM products WHERE id IN (SELECT id FROM products)"));
    }

    @Test
    void testSupportedPredicateStillWorks() throws Exception {
        // Adjacent supported predicate must not be over-broadly rejected
        JsonNode result = objectMapper.readTree(
            executor.execute("SELECT * FROM products WHERE price > 20"));
        assertEquals(1, result.size());
        assertEquals("Gadget", result.get(0).get("name").asText());
    }

    @Test
    void testSupportedInListStillWorks() throws Exception {
        JsonNode result = objectMapper.readTree(
            executor.execute("SELECT * FROM products WHERE name IN ('Widget', 'Gadget')"));
        assertEquals(2, result.size());
    }
}
