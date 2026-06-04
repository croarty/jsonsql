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
 * Comprehensive tests confirming LIKE/ILIKE behavior after switching to AST-based ILIKE
 * detection and cached compiled patterns.
 */
class LikeIlikeRobustnessTest {

    @TempDir
    Path tempDir;

    private QueryExecutor executor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        File dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        // One value deliberately contains the substring "ILIKE" to guard against the old
        // toString().contains("ILIKE") heuristic.
        String items = """
            {"items": [
                {"id": 1, "name": "Widget"},
                {"id": 2, "name": "WIDGET PRO"},
                {"id": 3, "name": "SIMILIKE_ITEM"},
                {"id": 4, "name": "gadget"}
            ]}
            """;
        Files.writeString(dataDir.toPath().resolve("items.json"), items);
        File mappingFile = tempDir.resolve(".jsonsql-mappings.json").toFile();
        MappingManager mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("items", "items.json:$.items");
        executor = new QueryExecutor(mappingManager, dataDir);
    }

    private JsonNode run(String sql) throws Exception {
        return objectMapper.readTree(executor.execute(sql));
    }

    @Test
    void testCaseSensitiveLike() throws Exception {
        JsonNode result = run("SELECT name FROM items WHERE name LIKE 'Widget'");
        assertEquals(1, result.size());
        assertEquals("Widget", result.get(0).get("name").asText());
    }

    @Test
    void testCaseInsensitiveIlike() throws Exception {
        // ILIKE should match regardless of case
        JsonNode result = run("SELECT name FROM items WHERE name ILIKE 'widget'");
        assertEquals(1, result.size());
        assertEquals("Widget", result.get(0).get("name").asText());
    }

    @Test
    void testIlikeWithWildcard() throws Exception {
        JsonNode result = run("SELECT name FROM items WHERE name ILIKE 'widget%'");
        // "Widget" and "WIDGET PRO"
        assertEquals(2, result.size());
    }

    @Test
    void testValueContainingIlikeMatchedByLike() throws Exception {
        // The literal value contains "ILIKE"; a case-sensitive LIKE with %ILIKE% must match it,
        // and must NOT be treated as case-insensitive.
        JsonNode result = run("SELECT name FROM items WHERE name LIKE '%ILIKE%'");
        assertEquals(1, result.size());
        assertEquals("SIMILIKE_ITEM", result.get(0).get("name").asText());
    }

    @Test
    void testLikeIsCaseSensitiveForValueContainingIlike() throws Exception {
        // Lowercase pattern must NOT match the uppercase SIMILIKE_ITEM under case-sensitive LIKE
        JsonNode result = run("SELECT name FROM items WHERE name LIKE '%ilike%'");
        assertEquals(0, result.size());
    }

    @Test
    void testUnderscoreWildcardSingleChar() throws Exception {
        // _ matches exactly one character
        JsonNode result = run("SELECT name FROM items WHERE name LIKE 'Widge_'");
        assertEquals(1, result.size());
        assertEquals("Widget", result.get(0).get("name").asText());
    }

    @Test
    void testNotLike() throws Exception {
        JsonNode result = run("SELECT name FROM items WHERE name NOT LIKE '%get%'");
        // Excludes "gadget"; "Widget"/"WIDGET PRO" contain "get"? "Widget" -> "get" yes.
        // Names without lowercase "get": "WIDGET PRO", "SIMILIKE_ITEM"
        assertEquals(2, result.size());
    }

    @Test
    void testPatternReusedAcrossManyRowsIsConsistent() throws Exception {
        // Exercises the compiled-pattern cache across multiple rows
        JsonNode result = run("SELECT name FROM items WHERE name LIKE '%LIKE%'");
        // Only SIMILIKE_ITEM contains the uppercase substring "LIKE"
        assertEquals(1, result.size());
        assertEquals("SIMILIKE_ITEM", result.get(0).get("name").asText());
    }
}
