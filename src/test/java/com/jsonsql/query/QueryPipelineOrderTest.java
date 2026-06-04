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
 * Comprehensive tests for the correct SQL execution phase ordering:
 * WHERE -> ORDER BY -> projection -> DISTINCT -> LIMIT.
 */
class QueryPipelineOrderTest {

    @TempDir
    Path tempDir;

    private QueryExecutor executor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        File dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();

        // category Electronics has higher prices than Furniture; deliberately interleaved order
        String products = """
            {
              "products": [
                {"id": 1, "name": "Desk", "category": "Furniture", "price": 100.0},
                {"id": 2, "name": "Laptop", "category": "Electronics", "price": 900.0},
                {"id": 3, "name": "Chair", "category": "Furniture", "price": 80.0},
                {"id": 4, "name": "Phone", "category": "Electronics", "price": 700.0},
                {"id": 5, "name": "Monitor", "category": "Electronics", "price": 300.0}
              ]
            }
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
    void testDistinctWithLimitReturnsOneDistinctCategory() throws Exception {
        // Happy path: DISTINCT then LIMIT must return a single DISTINCT category, not the
        // category of the first raw row after limiting.
        JsonNode result = run("SELECT DISTINCT category FROM products ORDER BY category LIMIT 1");
        assertEquals(1, result.size());
        assertEquals("Electronics", result.get(0).get("category").asText());
    }

    @Test
    void testDistinctOrderByLimitPicksOrderedDistinctValue() throws Exception {
        // DISTINCT categories ordered descending, limited to 1 -> "Furniture"
        JsonNode result = run("SELECT DISTINCT category FROM products ORDER BY category DESC LIMIT 1");
        assertEquals(1, result.size());
        assertEquals("Furniture", result.get(0).get("category").asText());
    }

    @Test
    void testLimitAppliesAfterDistinctCountsDistinctRows() throws Exception {
        // There are 2 distinct categories; LIMIT 5 should still yield only 2 rows.
        JsonNode result = run("SELECT DISTINCT category FROM products LIMIT 5");
        assertEquals(2, result.size());
    }

    @Test
    void testOrderByColumnNotInSelectList() throws Exception {
        // Edge case: ORDER BY references a column (price) not present in the SELECT list (name).
        // Ordering must still work because it happens before projection.
        JsonNode result = run("SELECT name FROM products ORDER BY price ASC LIMIT 1");
        assertEquals(1, result.size());
        // Lowest price is Chair (80.0)
        assertEquals("Chair", result.get(0).get("name").asText());
    }

    @Test
    void testWhereDistinctOrderByLimitEndToEnd() throws Exception {
        // Combined: only Electronics, distinct category, ordered, limited.
        JsonNode result = run(
            "SELECT DISTINCT category FROM products WHERE price > 500 ORDER BY category LIMIT 10");
        assertEquals(1, result.size());
        assertEquals("Electronics", result.get(0).get("category").asText());
    }

    @Test
    void testLimitZeroReturnsEmpty() throws Exception {
        JsonNode result = run("SELECT name FROM products LIMIT 0");
        assertEquals(0, result.size());
    }

    @Test
    void testLimitLargerThanResultSet() throws Exception {
        JsonNode result = run("SELECT name FROM products LIMIT 100");
        assertEquals(5, result.size());
    }

    @Test
    void testOrderByThenLimitSelectsLargestRows() throws Exception {
        // ORDER BY price DESC then LIMIT 2 -> the two most expensive products.
        JsonNode result = run("SELECT name, price FROM products ORDER BY price DESC LIMIT 2");
        assertEquals(2, result.size());
        assertEquals("Laptop", result.get(0).get("name").asText());
        assertEquals("Phone", result.get(1).get("name").asText());
    }
}
