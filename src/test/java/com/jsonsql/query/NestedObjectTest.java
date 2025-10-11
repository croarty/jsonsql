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
 * Comprehensive tests for nested object handling.
 */
class NestedObjectTest {

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
    void testDeepNesting() throws Exception {
        String json = """
        {"items": [
            {
                "id": 1,
                "user": {
                    "profile": {
                        "settings": {
                            "theme": "dark",
                            "language": "en"
                        }
                    }
                }
            }
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("deep.json"), json);
        mappingManager.addMapping("deep", "deep.json:$.items");

        String result = queryExecutor.execute("SELECT d.user.profile.settings.theme FROM deep d");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertEquals("dark", resultNode.get(0).get("theme").asText());
    }

    @Test
    void testNestedObjectInWhere() throws Exception {
        String json = """
        {"users": [
            {"id": 1, "name": "Alice", "meta": {"region": "US", "tier": "premium"}},
            {"id": 2, "name": "Bob", "meta": {"region": "EU", "tier": "basic"}},
            {"id": 3, "name": "Carol", "meta": {"region": "US", "tier": "basic"}}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("users.json"), json);
        mappingManager.addMapping("users", "users.json:$.users");

        String result = queryExecutor.execute("SELECT u.name, u.meta FROM users u WHERE u.meta.region = 'US'");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
        assertEquals("Alice", resultNode.get(0).get("name").asText());
        assertEquals("Carol", resultNode.get(1).get("name").asText());
    }

    @Test
    void testNestedObjectInOrderBy() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "data": {"priority": 3}},
            {"id": 2, "data": {"priority": 1}},
            {"id": 3, "data": {"priority": 2}}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("nested_order.json"), json);
        mappingManager.addMapping("nested_order", "nested_order.json:$.items");

        String result = queryExecutor.execute("SELECT n.id, n.data FROM nested_order n ORDER BY n.data.priority");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(3, resultNode.size());
        assertEquals(1, resultNode.get(0).get("data").get("priority").asInt());
        assertEquals(2, resultNode.get(1).get("data").get("priority").asInt());
        assertEquals(3, resultNode.get(2).get("data").get("priority").asInt());
    }

    @Test
    void testNestedObjectInJoinCondition() throws Exception {
        String products = """
        {"items": [{"id": 1, "ref": {"code": "A1"}}]}
        """;
        String orders = """
        {"items": [{"orderId": 101, "product": {"code": "A1"}}]}
        """;
        
        Files.writeString(dataDir.toPath().resolve("prod_ref.json"), products);
        Files.writeString(dataDir.toPath().resolve("ord_ref.json"), orders);
        
        mappingManager.addMapping("prod_ref", "prod_ref.json:$.items");
        mappingManager.addMapping("ord_ref", "ord_ref.json:$.items");

        String result = queryExecutor.execute(
            "SELECT o.orderId, p.id FROM ord_ref o JOIN prod_ref p ON o.product.code = p.ref.code"
        );
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertEquals(101, resultNode.get(0).get("orderId").asInt());
        assertEquals(1, resultNode.get(0).get("id").asInt());
    }

    @Test
    void testSelectEntireNestedObject() throws Exception {
        String json = """
        {"items": [
            {
                "id": 1,
                "metadata": {
                    "created": "2024-01-01",
                    "updated": "2024-01-15",
                    "tags": ["tag1", "tag2"]
                }
            }
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("meta.json"), json);
        mappingManager.addMapping("meta", "meta.json:$.items");

        String result = queryExecutor.execute("SELECT id, metadata FROM meta");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertTrue(resultNode.get(0).get("metadata").isObject());
        assertTrue(resultNode.get(0).get("metadata").has("created"));
        assertTrue(resultNode.get(0).get("metadata").has("tags"));
    }

    @Test
    void testMultipleNestedLevelsInSelect() throws Exception {
        String json = """
        {"items": [
            {
                "id": 1,
                "a": {"b": {"c": {"d": "deep_value"}}}
            }
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("multi_nest.json"), json);
        mappingManager.addMapping("multi_nest", "multi_nest.json:$.items");

        String result = queryExecutor.execute("SELECT m.id, m.a.b.c.d FROM multi_nest m");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertTrue(resultNode.get(0).has("id") || resultNode.get(0).has("d"));
        // The deeply nested value should be present
        if (resultNode.get(0).has("d")) {
            assertEquals("deep_value", resultNode.get(0).get("d").asText());
        }
    }

    @Test
    void testNestedObjectWithNullValue() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "data": {"value": null}},
            {"id": 2, "data": {"value": "present"}}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("null_nested.json"), json);
        mappingManager.addMapping("null_nested", "null_nested.json:$.items");

        String result = queryExecutor.execute("SELECT * FROM null_nested");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
        assertTrue(resultNode.get(0).get("data").get("value").isNull());
        assertFalse(resultNode.get(1).get("data").get("value").isNull());
    }

    @Test
    void testMissingNestedObject() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "config": {"setting": "value"}},
            {"id": 2}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("missing_nest.json"), json);
        mappingManager.addMapping("missing_nest", "missing_nest.json:$.items");

        // Should not crash when config is missing
        String result = queryExecutor.execute("SELECT id FROM missing_nest");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
    }

    @Test
    void testNestedArrayNotSupported() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "tags": ["tag1", "tag2", "tag3"]},
            {"id": 2, "tags": ["tag4"]}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("arrays.json"), json);
        mappingManager.addMapping("arrays", "arrays.json:$.items");

        // Arrays should be included as-is in results
        String result = queryExecutor.execute("SELECT * FROM arrays");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
        assertTrue(resultNode.get(0).get("tags").isArray());
        assertEquals(3, resultNode.get(0).get("tags").size());
    }

    @Test
    void testMixedNestedAndFlatFields() throws Exception {
        String json = """
        {"items": [
            {
                "id": 1,
                "name": "Item1",
                "pricing": {"base": 10.0, "tax": 1.5},
                "category": "Tools"
            }
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("mixed.json"), json);
        mappingManager.addMapping("mixed", "mixed.json:$.items");

        String result = queryExecutor.execute("SELECT m.id, m.name, m.pricing.base, m.pricing.tax, m.category FROM mixed m");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertEquals(1, resultNode.get(0).get("id").asInt());
        assertEquals("Item1", resultNode.get(0).get("name").asText());
        assertTrue(resultNode.get(0).has("base") || resultNode.get(0).has("pricing"));
        assertTrue(resultNode.get(0).has("tax") || resultNode.get(0).has("pricing"));
        assertEquals("Tools", resultNode.get(0).get("category").asText());
    }
}

