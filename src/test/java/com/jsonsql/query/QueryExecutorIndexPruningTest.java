package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import com.jsonsql.index.IndexBuilder;
import com.jsonsql.index.IndexManager;
import com.jsonsql.index.IndexStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end checks that index-based file pruning never changes query results:
 * an indexed executor must return exactly what a full-scan executor returns.
 */
class QueryExecutorIndexPruningTest {

    @TempDir
    File tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MappingManager mappingManager;
    private QueryExecutor indexed;
    private QueryExecutor fullScan;

    @BeforeEach
    void setUp() throws IOException {
        mappingManager = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));

        writeFile("products-multi/2023/p.json", """
            { "products": [
              {"id": 1, "category": "Tools", "price": 9.99,  "tags": ["a","b"],
               "reviews": [{"user":"x","rating":3}]},
              {"id": 2, "category": "Electronics", "price": 29.99, "tags": ["b","c"],
               "reviews": [{"user":"y","rating":4}]}
            ]}""");
        writeFile("products-multi/2024/p.json", """
            { "products": [
              {"id": 3, "category": "Furniture", "price": 50.0, "tags": ["d"],
               "reviews": [{"user":"q","rating":5}]},
              {"id": 4, "category": "Office", "price": 120.0, "tags": ["d","e"],
               "reviews": [{"user":"r","rating":5}]}
            ]}""");
        mappingManager.addMapping("products", "products-multi:$.products[*]");

        IndexStore store = new IndexStore(tempDir);
        IndexBuilder builder = new IndexBuilder(mappingManager, tempDir, store);
        IndexManager indexManager = new IndexManager(new File(tempDir, ".jsonsql-indexes.json"));
        for (String field : new String[]{"category", "price", "tags", "reviews.rating"}) {
            indexManager.addIndex("products", field);
            builder.build("products", field);
        }

        indexed = new QueryExecutor(mappingManager, tempDir, null, indexManager);
        fullScan = new QueryExecutor(mappingManager, tempDir);
    }

    private void writeFile(String relPath, String content) throws IOException {
        File f = new File(tempDir, relPath);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    private void assertSameResult(String sql) throws Exception {
        JsonNode withIndex = objectMapper.readTree(indexed.execute(sql));
        JsonNode withoutIndex = objectMapper.readTree(fullScan.execute(sql));
        assertEquals(withoutIndex, withIndex, "indexed result must match full-scan result for: " + sql);
    }

    @Test
    void equalityResultsMatchFullScan() throws Exception {
        assertSameResult("SELECT id, category FROM products WHERE category = 'Furniture'");
        assertSameResult("SELECT id FROM products WHERE category = 'Tools'");
    }

    @Test
    void rangeResultsMatchFullScan() throws Exception {
        assertSameResult("SELECT id, price FROM products WHERE price > 40");
        assertSameResult("SELECT id, price FROM products WHERE price <= 29.99");
    }

    @Test
    void inResultsMatchFullScan() throws Exception {
        assertSameResult("SELECT id FROM products WHERE category IN ('Tools', 'Office')");
    }

    @Test
    void unnestScalarResultsMatchFullScan() throws Exception {
        assertSameResult("SELECT id, tag FROM products, UNNEST(tags) AS t(tag) WHERE tag = 'a'");
        assertSameResult("SELECT id, tag FROM products, UNNEST(tags) AS t(tag) WHERE tag = 'd'");
    }

    @Test
    void unnestObjectResultsMatchFullScan() throws Exception {
        assertSameResult(
            "SELECT id FROM products, UNNEST(reviews) AS r(review) WHERE review.rating = 5");
    }

    @Test
    void orResultsMatchFullScan() throws Exception {
        assertSameResult(
            "SELECT id FROM products WHERE category = 'Tools' OR category = 'Office'");
    }

    @Test
    void noMatchReturnsEmptyJustLikeFullScan() throws Exception {
        JsonNode result = objectMapper.readTree(
            indexed.execute("SELECT id FROM products WHERE category = 'Nope'"));
        assertTrue(result.isArray());
        assertEquals(0, result.size());
        assertSameResult("SELECT id FROM products WHERE category = 'Nope'");
    }

    @Test
    void resultsMatchAfterFileModification() throws Exception {
        // Change data after building the index; pruning must fall back and stay correct.
        writeFile("products-multi/2024/p.json", """
            { "products": [ {"id": 7, "category": "Tools", "price": 5.0, "tags": ["a"]} ] }""");
        assertSameResult("SELECT id, category FROM products WHERE category = 'Tools'");
    }
}
