package com.jsonsql.index;

import com.jsonsql.config.MappingManager;
import com.jsonsql.query.ParsedQuery;
import com.jsonsql.query.QueryParser;
import com.jsonsql.query.TableFileResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IndexPlanner} file pruning: predicate extraction, UNNEST mapping,
 * range boundaries, the coverage rule, and safe fallbacks.
 */
class IndexPlannerTest {

    @TempDir
    File tempDir;

    private MappingManager mappingManager;
    private IndexStore store;
    private IndexBuilder builder;
    private IndexManager indexManager;
    private IndexPlanner planner;
    private TableFileResolver resolver;
    private QueryParser parser;

    @BeforeEach
    void setUp() throws IOException {
        mappingManager = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        store = new IndexStore(tempDir);
        builder = new IndexBuilder(mappingManager, tempDir, store);
        indexManager = new IndexManager(new File(tempDir, ".jsonsql-indexes.json"));
        resolver = new TableFileResolver(mappingManager, tempDir);
        planner = new IndexPlanner(indexManager, store, mappingManager, resolver);
        parser = new QueryParser();

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

        declareAndBuild("category");
        declareAndBuild("price");
        declareAndBuild("tags");
        declareAndBuild("reviews.rating");
    }

    private void declareAndBuild(String field) throws IOException {
        indexManager.addIndex("products", field);
        builder.build("products", field);
    }

    private void writeFile(String relPath, String content) throws IOException {
        File f = new File(tempDir, relPath);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    private List<File> prune(String sql) throws Exception {
        ParsedQuery query = parser.parse(sql);
        List<File> files = resolver.resolveFiles("products");
        return planner.prune(query.getFromTable(), query, files);
    }

    private void assertOnly(List<File> files, String fragment) {
        assertEquals(1, files.size(), "expected a single file, got: " + files);
        assertTrue(files.get(0).getPath().replace('\\', '/').contains(fragment),
            "expected file under " + fragment + ", got: " + files.get(0));
    }

    @Test
    void equalityPrunesToMatchingFile() throws Exception {
        assertOnly(prune("SELECT * FROM products WHERE category = 'Furniture'"), "2024");
        assertOnly(prune("SELECT * FROM products WHERE category = 'Tools'"), "2023");
    }

    @Test
    void inListPrunes() throws Exception {
        assertOnly(prune("SELECT * FROM products WHERE category IN ('Office')"), "2024");
    }

    @Test
    void rangePrunesWithBoundaryStrictness() throws Exception {
        // 2023 max price 29.99, 2024 min 50
        assertOnly(prune("SELECT * FROM products WHERE price > 40"), "2024");
        assertOnly(prune("SELECT * FROM products WHERE price < 20"), "2023");
        // >= boundary: 29.99 exactly excludes 2023 (max 29.99 < 30) -> only 2024 has >=30
        assertOnly(prune("SELECT * FROM products WHERE price >= 30"), "2024");
    }

    @Test
    void rangeBoundaryKeepsFileWhenEqualAllowed() throws Exception {
        // price <= 9.99 : 2023 min is 9.99 so it can match; 2024 min 50 cannot
        assertOnly(prune("SELECT * FROM products WHERE price <= 9.99"), "2023");
    }

    @Test
    void unnestScalarArrayPrunes() throws Exception {
        assertOnly(prune("SELECT * FROM products, UNNEST(tags) AS t(tag) WHERE tag = 'a'"), "2023");
        assertOnly(prune("SELECT * FROM products, UNNEST(tags) AS t(tag) WHERE tag = 'd'"), "2024");
    }

    @Test
    void unnestObjectArraySubPropertyPrunes() throws Exception {
        assertOnly(prune("SELECT * FROM products, UNNEST(reviews) AS r(review) WHERE review.rating = 5"), "2024");
        assertOnly(prune("SELECT * FROM products, UNNEST(reviews) AS r(review) WHERE review.rating = 3"), "2023");
    }

    @Test
    void andWithOneIndexedConjunctStillPrunes() throws Exception {
        assertOnly(prune("SELECT * FROM products WHERE category = 'Furniture' AND price > 0"), "2024");
    }

    @Test
    void orPredicateDisablesPruning() throws Exception {
        List<File> files = prune("SELECT * FROM products WHERE category = 'Furniture' OR category = 'Nope'");
        assertEquals(2, files.size(), "OR should fall back to a full scan");
    }

    @Test
    void nonIndexedFieldDoesNotPrune() throws Exception {
        List<File> files = prune("SELECT * FROM products WHERE id = 1");
        assertEquals(2, files.size(), "no index on id -> full scan");
    }

    @Test
    void emptyResultWhenNoFileCanMatch() throws Exception {
        List<File> files = prune("SELECT * FROM products WHERE category = 'DoesNotExist'");
        assertTrue(files.isEmpty(), "no file can match -> prune everything");
    }

    @Test
    void newUncoveredFileIsAlwaysLoaded() throws Exception {
        // Add a file after the index was built; it is not covered, so it must be kept.
        writeFile("products-multi/2025/p.json", """
            { "products": [ {"id": 9, "category": "Furniture", "price": 1.0} ] }""");
        List<File> files = prune("SELECT * FROM products WHERE category = 'Tools'");
        // 2023 matches Tools; 2025 is uncovered so kept; 2024 pruned.
        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(f -> f.getPath().replace('\\','/').contains("2025")),
            "uncovered new file must be loaded");
    }

    @Test
    void modifiedFileFallsBackToLoad() throws Exception {
        // Change a file's contents after indexing so its fingerprint no longer matches.
        writeFile("products-multi/2024/p.json", """
            { "products": [ {"id": 99, "category": "Tools", "price": 5.0} ] }""");
        List<File> files = prune("SELECT * FROM products WHERE category = 'Tools'");
        // 2023 matches; 2024 is now stale (fingerprint mismatch) so it must be loaded too.
        assertEquals(2, files.size());
    }

    @Test
    void mappingChangeDisablesPruning() throws Exception {
        // Repoint the mapping JSONPath; the stored index jsonPath no longer matches.
        mappingManager.addMapping("products", "products-multi:$.products");
        List<File> files = prune("SELECT * FROM products WHERE category = 'Furniture'");
        assertEquals(2, files.size(), "changed JSONPath -> index ignored -> full scan");
    }
}
