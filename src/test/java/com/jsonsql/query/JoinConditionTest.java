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
 * Comprehensive tests for JOIN ON parsing and join-key type coercion.
 */
class JoinConditionTest {

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
        File mappingFile = tempDir.resolve(".jsonsql-mappings.json").toFile();
        mappingManager = new MappingManager(mappingFile);
        executor = new QueryExecutor(mappingManager, dataDir);
    }

    private void writeData(String fileName, String content, String alias, String jsonPath) throws Exception {
        Files.writeString(dataDir.toPath().resolve(fileName), content);
        mappingManager.addMapping(alias, fileName + ":" + jsonPath);
    }

    private JsonNode run(String sql) throws Exception {
        return objectMapper.readTree(executor.execute(sql));
    }

    @Test
    void testEquiJoinHappyPath() throws Exception {
        writeData("orders.json", """
            {"orders": [{"orderId": 1, "productId": 1}, {"orderId": 2, "productId": 2}]}
            """, "orders", "$.orders");
        writeData("products.json", """
            {"products": [{"id": 1, "name": "Widget"}, {"id": 2, "name": "Gadget"}]}
            """, "products", "$.products");

        JsonNode result = run(
            "SELECT o.orderId, p.name FROM orders o JOIN products p ON o.productId = p.id");
        assertEquals(2, result.size());
    }

    @Test
    void testNumberMatchesNumericString() throws Exception {
        // Left key is a number, right key is a numeric string - they should match via coercion
        writeData("orders.json", """
            {"orders": [{"orderId": 1, "productId": 1}]}
            """, "orders", "$.orders");
        writeData("products.json", """
            {"products": [{"id": "1", "name": "Widget"}]}
            """, "products", "$.products");

        JsonNode result = run(
            "SELECT o.orderId, p.name FROM orders o JOIN products p ON o.productId = p.id");
        assertEquals(1, result.size());
        assertEquals("Widget", result.get(0).get("name").asText());
    }

    @Test
    void testStringMatchesNumber() throws Exception {
        // Reverse direction: string key on left, number on right
        writeData("orders.json", """
            {"orders": [{"orderId": 1, "productId": "2"}]}
            """, "orders", "$.orders");
        writeData("products.json", """
            {"products": [{"id": 2, "name": "Gadget"}]}
            """, "products", "$.products");

        JsonNode result = run(
            "SELECT o.orderId, p.name FROM orders o JOIN products p ON o.productId = p.id");
        assertEquals(1, result.size());
        assertEquals("Gadget", result.get(0).get("name").asText());
    }

    @Test
    void testNullKeysDoNotMatch() throws Exception {
        // Explicit null and missing keys must not match (SQL NULL semantics)
        writeData("orders.json", """
            {"orders": [{"orderId": 1, "productId": null}, {"orderId": 2}]}
            """, "orders", "$.orders");
        writeData("products.json", """
            {"products": [{"id": null, "name": "NullProduct"}]}
            """, "products", "$.products");

        JsonNode result = run(
            "SELECT o.orderId, p.name FROM orders o JOIN products p ON o.productId = p.id");
        assertEquals(0, result.size());
    }

    @Test
    void testLeftJoinKeepsUnmatchedLeftRows() throws Exception {
        writeData("orders.json", """
            {"orders": [{"orderId": 1, "productId": 1}, {"orderId": 2, "productId": 99}]}
            """, "orders", "$.orders");
        writeData("products.json", """
            {"products": [{"id": 1, "name": "Widget"}]}
            """, "products", "$.products");

        JsonNode result = run(
            "SELECT o.orderId, p.name FROM orders o LEFT JOIN products p ON o.productId = p.id");
        assertEquals(2, result.size());
    }

    @Test
    void testNonEquiJoinConditionThrows() throws Exception {
        writeData("orders.json", """
            {"orders": [{"orderId": 1, "productId": 1}]}
            """, "orders", "$.orders");
        writeData("products.json", """
            {"products": [{"id": 1, "name": "Widget"}]}
            """, "products", "$.products");

        Exception ex = assertThrows(Exception.class, () -> executor.execute(
            "SELECT o.orderId FROM orders o JOIN products p ON o.productId >= p.id"));
        assertTrue(ex.getMessage() == null || ex.getMessage().toLowerCase().contains("equi")
            || ex.getMessage().toLowerCase().contains("join"));
    }

    @Test
    void testMultipleEqualsInOnConditionThrows() throws Exception {
        writeData("orders.json", """
            {"orders": [{"orderId": 1, "productId": 1}]}
            """, "orders", "$.orders");
        writeData("products.json", """
            {"products": [{"id": 1, "name": "Widget"}]}
            """, "products", "$.products");

        assertThrows(Exception.class, () -> executor.execute(
            "SELECT o.orderId FROM orders o JOIN products p ON o.productId = p.id = 1"));
    }

    @Test
    void testAndInOnConditionThrows() throws Exception {
        writeData("orders.json", """
            {"orders": [{"orderId": 1, "productId": 1, "x": 5}]}
            """, "orders", "$.orders");
        writeData("products.json", """
            {"products": [{"id": 1, "name": "Widget", "y": 5}]}
            """, "products", "$.products");

        assertThrows(Exception.class, () -> executor.execute(
            "SELECT o.orderId FROM orders o JOIN products p ON o.productId = p.id AND o.x = p.y"));
    }
}
