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
 * Comprehensive tests for error handling and validation.
 */
class ErrorHandlingTest {

    @TempDir
    Path tempDir;

    private File configFile;
    private File dataDir;
    private MappingManager mappingManager;
    private QueryExecutor queryExecutor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        configFile = tempDir.resolve("test-mappings.json").toFile();
        mappingManager = new MappingManager(configFile);
        dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        queryExecutor = new QueryExecutor(mappingManager, dataDir);
    }
    
    private void setupTestData() throws Exception {
        String json = """
        {"products": [
            {"id": 1, "name": "Widget", "price": 19.99, "category": "Tools"},
            {"id": 2, "name": "Gadget", "price": 29.99, "category": "Electronics"}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("products.json"), json);
        mappingManager.addMapping("products", "products.json:$.products");
    }

    @Test
    void testMalformedJson() throws Exception {
        String badJson = """
        {"items": [{"id": 1, "name": "test"}
        """; // Missing closing brackets
        Files.writeString(dataDir.toPath().resolve("bad.json"), badJson);
        mappingManager.addMapping("bad", "bad.json:$.items");

        assertThrows(Exception.class, () -> 
            queryExecutor.execute("SELECT * FROM bad")
        );
    }

    @Test
    void testInvalidJsonPath() throws Exception {
        String json = """
        {"items": [{"id": 1}]}
        """;
        Files.writeString(dataDir.toPath().resolve("test.json"), json);
        mappingManager.addMapping("invalid_path", "test.json:$.nonexistent.path");

        // Should handle gracefully when path doesn't exist
        String result = queryExecutor.execute("SELECT * FROM invalid_path");
        // May return empty or throw exception - either is acceptable
    }

    @Test
    void testFileNotFound() {
        mappingManager.addMapping("missing", "nonexistent.json:$.items");

        Exception exception = assertThrows(Exception.class, () -> 
            queryExecutor.execute("SELECT * FROM missing")
        );
        assertTrue(exception.getMessage().contains("not found") || 
                   exception.getMessage().contains("nonexistent"));
    }

    @Test
    void testTableNotMapped() {
        Exception exception = assertThrows(Exception.class, () -> 
            queryExecutor.execute("SELECT * FROM unmapped_table")
        );
        assertTrue(exception.getMessage().contains("No mapping found"));
    }

    @Test
    void testInvalidSqlSyntax() {
        assertThrows(Exception.class, () -> 
            queryExecutor.execute("INVALID SQL QUERY")
        );
    }

    @Test
    void testMissingFromClause() {
        assertThrows(Exception.class, () -> 
            queryExecutor.execute("SELECT *")
        );
    }

    @Test
    void testInvalidJoinSyntax() {
        Exception exception = assertThrows(Exception.class, () -> 
            queryExecutor.execute("SELECT * FROM table1 JOIN table2")
        );
        // Should complain about missing ON clause
    }

    @Test
    void testNonExistentFieldInWhere() throws Exception {
        String json = """
        {"items": [{"id": 1, "name": "test"}]}
        """;
        Files.writeString(dataDir.toPath().resolve("test.json"), json);
        mappingManager.addMapping("test", "test.json:$.items");

        // Querying non-existent field should return empty (field doesn't match)
        String result = queryExecutor.execute("SELECT * FROM test WHERE nonexistent = 'value'");
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(0, resultNode.size());
    }

    @Test
    void testNonExistentFieldInSelect() throws Exception {
        String json = """
        {"items": [{"id": 1, "name": "test"}]}
        """;
        Files.writeString(dataDir.toPath().resolve("test.json"), json);
        mappingManager.addMapping("test", "test.json:$.items");

        String result = queryExecutor.execute("SELECT id, nonexistent FROM test");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertTrue(resultNode.get(0).has("id"));
        // nonexistent field should be missing
        assertFalse(resultNode.get(0).has("nonexistent"));
    }

    @Test
    void testJoinOnNonExistentField() throws Exception {
        String left = """
        {"items": [{"id": 1, "name": "A"}]}
        """;
        String right = """
        {"items": [{"id": 1, "value": "Z"}]}
        """;
        
        Files.writeString(dataDir.toPath().resolve("left.json"), left);
        Files.writeString(dataDir.toPath().resolve("right.json"), right);
        
        mappingManager.addMapping("left", "left.json:$.items");
        mappingManager.addMapping("right", "right.json:$.items");

        // Join on non-existent field - should result in no matches
        String result = queryExecutor.execute(
            "SELECT l.name FROM left l JOIN right r ON l.nonexistent = r.id"
        );
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(0, resultNode.size());
    }

    @Test
    void testEmptyDirectory() throws Exception {
        File emptyDir = dataDir.toPath().resolve("empty_dir").toFile();
        emptyDir.mkdirs();
        
        mappingManager.addMapping("empty_dir", "empty_dir:$.items");

        Exception exception = assertThrows(Exception.class, () -> 
            queryExecutor.execute("SELECT * FROM empty_dir")
        );
        assertTrue(exception.getMessage().contains("No JSON files found"));
    }

    @Test
    void testInvalidWhereOperator() throws Exception {
        String json = """
        {"items": [{"id": 1}]}
        """;
        Files.writeString(dataDir.toPath().resolve("test.json"), json);
        mappingManager.addMapping("test", "test.json:$.items");

        // Invalid operator should be handled gracefully (returns empty or error)
        String result = queryExecutor.execute("SELECT * FROM test WHERE id <> 1");
        // <> is valid SQL for != so this should actually work or be rejected by parser
    }

    @Test
    void testVeryLongQuery() throws Exception {
        setupTestData();

        // Build a very long SELECT list
        StringBuilder query = new StringBuilder("SELECT ");
        query.append("id, name, price, category, ");
        for (int i = 0; i < 20; i++) {
            query.append("name AS alias").append(i).append(", ");
        }
        query.append("price FROM products");

        // Should handle long queries
        assertDoesNotThrow(() -> queryExecutor.execute(query.toString()));
    }

    @Test
    void testQueryWithExtraWhitespace() throws Exception {
        setupTestData();

        String result = queryExecutor.execute(
            "SELECT    name   ,   price   FROM     products    WHERE    price   >   10   "
        );
        JsonNode resultNode = objectMapper.readTree(result);

        assertTrue(resultNode.size() > 0);
    }

    @Test
    void testQueryWithNewlines() throws Exception {
        setupTestData();

        String result = queryExecutor.execute("""
            SELECT 
                name,
                price
            FROM 
                products
            WHERE 
                price > 10
            ORDER BY 
                price DESC
            """);
        JsonNode resultNode = objectMapper.readTree(result);

        assertTrue(resultNode.size() > 0);
    }
}

