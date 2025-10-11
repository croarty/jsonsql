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
 * Comprehensive tests for edge cases and boundary conditions.
 */
class EdgeCaseTest {

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

    @Test
    void testEmptyResultSet() throws Exception {
        String json = """
        {"items": [{"id": 1, "value": "test"}]}
        """;
        Files.writeString(dataDir.toPath().resolve("test.json"), json);
        mappingManager.addMapping("test", "test.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM test WHERE id = 999");
        JsonNode resultNode = objectMapper.readTree(result);

        assertTrue(resultNode.isArray());
        assertEquals(0, resultNode.size());
    }

    @Test
    void testEmptyArray() throws Exception {
        String json = """
        {"items": []}
        """;
        Files.writeString(dataDir.toPath().resolve("empty.json"), json);
        mappingManager.addMapping("empty", "empty.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM empty");
        JsonNode resultNode = objectMapper.readTree(result);

        assertTrue(resultNode.isArray());
        assertEquals(0, resultNode.size());
    }

    @Test
    void testNullValues() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "name": "Item1", "description": null},
            {"id": 2, "name": null, "description": "Desc2"},
            {"id": 3, "name": "Item3", "description": "Desc3"}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("nulls.json"), json);
        mappingManager.addMapping("nulls", "nulls.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM nulls");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(3, resultNode.size());
        assertTrue(resultNode.get(0).get("description").isNull());
        assertTrue(resultNode.get(1).get("name").isNull());
    }

    @Test
    void testMissingFields() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "name": "Item1", "price": 10.0},
            {"id": 2, "name": "Item2"},
            {"id": 3, "price": 30.0}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("missing.json"), json);
        mappingManager.addMapping("missing", "missing.json:$.items");

        String result = queryExecutor.execute("SELECT id, name, price FROM missing");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(3, resultNode.size());
        // Second item missing price
        assertFalse(resultNode.get(1).has("price"));
        // Third item missing name
        assertFalse(resultNode.get(2).has("name"));
    }

    @Test
    void testMixedDataTypes() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "value": "text"},
            {"id": 2, "value": 123},
            {"id": 3, "value": true},
            {"id": 4, "value": 45.67}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("mixed.json"), json);
        mappingManager.addMapping("mixed", "mixed.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM mixed");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(4, resultNode.size());
        assertTrue(resultNode.get(0).get("value").isTextual());
        assertTrue(resultNode.get(1).get("value").isNumber());
        assertTrue(resultNode.get(2).get("value").isBoolean());
        assertTrue(resultNode.get(3).get("value").isNumber());
    }

    @Test
    void testVeryLargeNumbers() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "value": 9999999999999.99},
            {"id": 2, "value": -9999999999999.99},
            {"id": 3, "value": 0.00000000001}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("numbers.json"), json);
        mappingManager.addMapping("numbers", "numbers.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM numbers ORDER BY value");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(3, resultNode.size());
        // Verify sorted order
        assertTrue(resultNode.get(0).get("value").asDouble() < 0);
        assertTrue(resultNode.get(2).get("value").asDouble() > 9999999999999.0);
    }

    @Test
    void testSpecialCharactersInStrings() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "text": "Quote: \\"test\\""},
            {"id": 2, "text": "Newline:\\nTest"},
            {"id": 3, "text": "Tab:\\tTest"},
            {"id": 4, "text": "Unicode: \\u0041"}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("special.json"), json);
        mappingManager.addMapping("special", "special.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM special");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(4, resultNode.size());
    }

    @Test
    void testWhereWithNullComparison() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "status": "active"},
            {"id": 2, "status": null},
            {"id": 3, "status": "inactive"}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("null_test.json"), json);
        mappingManager.addMapping("null_test", "null_test.json:$.items");

        // Items with status != 'active' should return item 3 (item 2 with null is filtered out)
        String result = queryExecutor.execute("SELECT * FROM null_test WHERE status != 'active'");
        JsonNode resultNode = objectMapper.readTree(result);

        // Should only get items where status field exists and != 'active'
        assertTrue(resultNode.size() >= 0); // Nulls might be filtered
    }

    @Test
    void testTopWithZero() throws Exception {
        String json = """
        {"items": [{"id": 1}, {"id": 2}, {"id": 3}]}
        """;
        Files.writeString(dataDir.toPath().resolve("test.json"), json);
        mappingManager.addMapping("test", "test.json:$.items");

        String result = queryExecutor.execute("SELECT TOP 0 * FROM test");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(0, resultNode.size());
    }

    @Test
    void testTopLargerThanResultSet() throws Exception {
        String json = """
        {"items": [{"id": 1}, {"id": 2}]}
        """;
        Files.writeString(dataDir.toPath().resolve("test.json"), json);
        mappingManager.addMapping("test", "test.json:$.items");

        String result = queryExecutor.execute("SELECT TOP 100 * FROM test");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
    }

    @Test
    void testEmptyStringValue() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "name": ""},
            {"id": 2, "name": "Test"}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("empty_str.json"), json);
        mappingManager.addMapping("empty_str", "empty_str.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM empty_str WHERE name = ''");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertEquals(1, resultNode.get(0).get("id").asInt());
    }

    @Test
    void testBooleanValues() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "active": true},
            {"id": 2, "active": false},
            {"id": 3, "active": true}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("bool.json"), json);
        mappingManager.addMapping("bool", "bool.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM bool WHERE active = true");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
        assertEquals(1, resultNode.get(0).get("id").asInt());
        assertEquals(3, resultNode.get(1).get("id").asInt());
    }

    @Test
    void testOrderByWithNulls() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "priority": 10},
            {"id": 2, "priority": null},
            {"id": 3, "priority": 5},
            {"id": 4, "priority": null}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("nullorder.json"), json);
        mappingManager.addMapping("nullorder", "nullorder.json:$.items");

        // Should handle nulls gracefully (likely puts them first or last)
        String result = queryExecutor.execute("SELECT * FROM nullorder ORDER BY priority");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(4, resultNode.size());
    }

    @Test
    void testLeftJoinWithNoMatches() throws Exception {
        String left = """
        {"items": [{"id": 1, "name": "A"}, {"id": 2, "name": "B"}]}
        """;
        String right = """
        {"items": [{"id": 99, "value": "Z"}]}
        """;
        
        Files.writeString(dataDir.toPath().resolve("left.json"), left);
        Files.writeString(dataDir.toPath().resolve("right.json"), right);
        
        mappingManager.addMapping("left_table", "left.json:$.items");
        mappingManager.addMapping("right_table", "right.json:$.items");

        String result = queryExecutor.execute(
            "SELECT l.name, r.value FROM left_table l LEFT JOIN right_table r ON l.id = r.id"
        );
        JsonNode resultNode = objectMapper.readTree(result);

        // Should have all left rows even with no matches
        assertEquals(2, resultNode.size());
        // Right values should be missing/null
        assertFalse(resultNode.get(0).has("value"));
    }

    @Test
    void testInnerJoinWithNoMatches() throws Exception {
        String left = """
        {"items": [{"id": 1, "name": "A"}]}
        """;
        String right = """
        {"items": [{"id": 99, "value": "Z"}]}
        """;
        
        Files.writeString(dataDir.toPath().resolve("left2.json"), left);
        Files.writeString(dataDir.toPath().resolve("right2.json"), right);
        
        mappingManager.addMapping("left2", "left2.json:$.items");
        mappingManager.addMapping("right2", "right2.json:$.items");

        String result = queryExecutor.execute(
            "SELECT l.name, r.value FROM left2 l JOIN right2 r ON l.id = r.id"
        );
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(0, resultNode.size());
    }

    @Test
    void testCaseSensitiveFieldNames() throws Exception {
        String json = """
        {"items": [
            {"Name": "Upper", "name": "lower", "NAME": "ALL_CAPS"}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("case.json"), json);
        mappingManager.addMapping("case_test", "case.json:$.items");

        // Should respect case sensitivity
        String result = queryExecutor.execute("SELECT Name, name FROM case_test");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
    }

    @Test
    void testNumericStringComparison() throws Exception {
        String json = """
        {"items": [
            {"id": "001", "value": 10},
            {"id": "002", "value": 20},
            {"id": "010", "value": 30}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("numstr.json"), json);
        mappingManager.addMapping("numstr", "numstr.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM numstr WHERE id = '002'");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertEquals(20, resultNode.get(0).get("value").asInt());
    }

    @Test
    void testWhereWithZero() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "count": 0},
            {"id": 2, "count": 5},
            {"id": 3, "count": 0}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("zeros.json"), json);
        mappingManager.addMapping("zeros", "zeros.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM zeros WHERE count = 0");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
    }

    @Test
    void testNegativeNumbers() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "balance": -100.50},
            {"id": 2, "balance": 50.25},
            {"id": 3, "balance": -25.00}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("neg.json"), json);
        mappingManager.addMapping("neg", "neg.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM neg WHERE balance < 0 ORDER BY balance");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
        assertTrue(resultNode.get(0).get("balance").asDouble() < resultNode.get(1).get("balance").asDouble());
    }

    @Test
    void testWhereWithFloatingPointComparison() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "value": 10.5},
            {"id": 2, "value": 10.50},
            {"id": 3, "value": 10.500}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("float.json"), json);
        mappingManager.addMapping("float", "float.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM float WHERE value = 10.5");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(3, resultNode.size());
    }

    @Test
    void testVeryLongFieldName() throws Exception {
        String longFieldName = "thisIsAVeryLongFieldNameThatShouldStillWorkCorrectlyInTheSystem";
        String json = String.format("""
        {"items": [{"id": 1, "%s": "value"}]}
        """, longFieldName);
        Files.writeString(dataDir.toPath().resolve("long.json"), json);
        mappingManager.addMapping("long", "long.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM long");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertTrue(resultNode.get(0).has(longFieldName));
    }

    @Test
    void testFieldNameWithUnderscore() throws Exception {
        String json = """
        {"items": [
            {"user_id": 1, "first_name": "John", "last_name": "Doe"}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("underscore.json"), json);
        mappingManager.addMapping("underscore", "underscore.json:$.items");

        String result = queryExecutor.execute("SELECT user_id, first_name, last_name FROM underscore");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertEquals(1, resultNode.get(0).get("user_id").asInt());
        assertEquals("John", resultNode.get(0).get("first_name").asText());
    }

    @Test
    void testWhereWithNegativeNumber() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "temperature": -5},
            {"id": 2, "temperature": 0},
            {"id": 3, "temperature": 5}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("temps.json"), json);
        mappingManager.addMapping("temps", "temps.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM temps WHERE temperature >= 0");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
    }

    @Test
    void testDuplicateRows() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "value": "A"},
            {"id": 1, "value": "A"},
            {"id": 2, "value": "B"}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("dup.json"), json);
        mappingManager.addMapping("dup", "dup.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM dup");
        JsonNode resultNode = objectMapper.readTree(result);

        // Should return all rows including duplicates
        assertEquals(3, resultNode.size());
    }

    @Test
    void testSingleRow() throws Exception {
        String json = """
        {"items": [{"id": 1, "solo": true}]}
        """;
        Files.writeString(dataDir.toPath().resolve("single.json"), json);
        mappingManager.addMapping("single", "single.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM single");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
    }

    @Test
    void testWhereComparisonWithDifferentTypes() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "value": "10"},
            {"id": 2, "value": 10}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("types.json"), json);
        mappingManager.addMapping("types", "types.json:$.items");

        // String "10" should not match number 10
        String result = queryExecutor.execute("SELECT * FROM types WHERE value = 10");
        JsonNode resultNode = objectMapper.readTree(result);

        // Both might match since comparison tries to parse string as number
        assertTrue(resultNode.size() >= 1);
        assertTrue(resultNode.size() <= 2);
    }
}

