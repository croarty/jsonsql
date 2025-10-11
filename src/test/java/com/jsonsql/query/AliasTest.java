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
 * Comprehensive tests for AS alias functionality.
 */
class AliasTest {

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
    void testSimpleAlias() throws Exception {
        setupTestData();

        String result = queryExecutor.execute("SELECT name AS productName FROM products");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
        assertTrue(resultNode.get(0).has("productName"));
        assertFalse(resultNode.get(0).has("name"));
        assertEquals("Widget", resultNode.get(0).get("productName").asText());
    }

    @Test
    void testMultipleAliases() throws Exception {
        setupTestData();

        String result = queryExecutor.execute("SELECT id AS productId, name AS productName, price AS cost FROM products");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
        assertTrue(resultNode.get(0).has("productId"));
        assertTrue(resultNode.get(0).has("productName"));
        assertTrue(resultNode.get(0).has("cost"));
        assertFalse(resultNode.get(0).has("id"));
        assertFalse(resultNode.get(0).has("name"));
        assertFalse(resultNode.get(0).has("price"));
    }

    @Test
    void testMixedAliasedAndNonAliased() throws Exception {
        setupTestData();

        String result = queryExecutor.execute("SELECT id, name AS productName, price FROM products");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
        assertTrue(resultNode.get(0).has("id"));
        assertTrue(resultNode.get(0).has("productName"));
        assertTrue(resultNode.get(0).has("price"));
        assertFalse(resultNode.get(0).has("name"));
    }

    @Test
    void testAliasWithQualifiedName() throws Exception {
        setupTestData();

        String result = queryExecutor.execute("SELECT p.id AS productId, p.name AS productName FROM products p");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
        assertTrue(resultNode.get(0).has("productId"));
        assertTrue(resultNode.get(0).has("productName"));
    }

    @Test
    void testAliasWithNestedField() throws Exception {
        String json = """
        {"items": [
            {"id": 1, "pricing": {"base": 10.0, "discount": 2.0}}
        ]}
        """;
        Files.writeString(dataDir.toPath().resolve("pricing.json"), json);
        mappingManager.addMapping("pricing", "pricing.json:$.items");

        String result = queryExecutor.execute("SELECT p.pricing.base AS basePrice, p.pricing.discount AS discountAmount FROM pricing p");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertTrue(resultNode.get(0).has("basePrice"));
        assertTrue(resultNode.get(0).has("discountAmount"));
        assertEquals(10.0, resultNode.get(0).get("basePrice").asDouble());
        assertEquals(2.0, resultNode.get(0).get("discountAmount").asDouble());
    }

    @Test
    void testAliasInJoinQuery() throws Exception {
        String products = """
        {"items": [{"id": 1, "name": "Widget"}]}
        """;
        String orders = """
        {"items": [{"orderId": 101, "productId": 1, "qty": 5}]}
        """;
        
        Files.writeString(dataDir.toPath().resolve("p.json"), products);
        Files.writeString(dataDir.toPath().resolve("o.json"), orders);
        
        mappingManager.addMapping("p_table", "p.json:$.items");
        mappingManager.addMapping("o_table", "o.json:$.items");

        String result = queryExecutor.execute(
            "SELECT p.id AS productId, p.name AS productName, o.qty AS quantity FROM o_table o JOIN p_table p ON o.productId = p.id"
        );
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertTrue(resultNode.get(0).has("productId"));
        assertTrue(resultNode.get(0).has("productName"));
        assertTrue(resultNode.get(0).has("quantity"));
    }

    @Test
    void testAliasWithWhere() throws Exception {
        setupTestData();

        String result = queryExecutor.execute("SELECT name AS productName, price AS cost FROM products WHERE price > 20");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertTrue(resultNode.get(0).has("productName"));
        assertTrue(resultNode.get(0).has("cost"));
        assertEquals("Gadget", resultNode.get(0).get("productName").asText());
    }

    @Test
    void testAliasWithOrderBy() throws Exception {
        setupTestData();

        String result = queryExecutor.execute("SELECT name AS productName, price AS cost FROM products ORDER BY price DESC");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
        assertEquals("Gadget", resultNode.get(0).get("productName").asText());
        assertEquals("Widget", resultNode.get(1).get("productName").asText());
    }

    @Test
    void testAliasWithTop() throws Exception {
        setupTestData();

        String result = queryExecutor.execute("SELECT TOP 1 name AS productName, price AS cost FROM products ORDER BY price DESC");
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertTrue(resultNode.get(0).has("productName"));
        assertTrue(resultNode.get(0).has("cost"));
    }

    @Test
    void testAliasPreventsCollision() throws Exception {
        String products = """
        {"items": [{"id": 1, "name": "Product"}]}
        """;
        String customers = """
        {"items": [{"id": 1, "name": "Customer"}]}
        """;
        
        Files.writeString(dataDir.toPath().resolve("prod2.json"), products);
        Files.writeString(dataDir.toPath().resolve("cust2.json"), customers);
        
        mappingManager.addMapping("prod2", "prod2.json:$.items");
        mappingManager.addMapping("cust2", "cust2.json:$.items");

        // Use aliases to avoid collision
        String result = queryExecutor.execute(
            "SELECT p.name AS productName, c.name AS customerName FROM prod2 p JOIN cust2 c ON p.id = c.id"
        );
        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertTrue(resultNode.get(0).has("productName"));
        assertTrue(resultNode.get(0).has("customerName"));
        assertEquals("Product", resultNode.get(0).get("productName").asText());
        assertEquals("Customer", resultNode.get(0).get("customerName").asText());
    }

    @Test
    void testNumericAlias() throws Exception {
        setupTestData();

        String result = queryExecutor.execute("SELECT id AS id1, price AS price1 FROM products");
        JsonNode resultNode = objectMapper.readTree(result);

        assertTrue(resultNode.get(0).has("id1"));
        assertTrue(resultNode.get(0).has("price1"));
    }

    @Test
    void testAliasWithUnderscore() throws Exception {
        setupTestData();

        String result = queryExecutor.execute("SELECT name AS product_name, price AS product_price FROM products");
        JsonNode resultNode = objectMapper.readTree(result);

        assertTrue(resultNode.get(0).has("product_name"));
        assertTrue(resultNode.get(0).has("product_price"));
    }

    @Test
    void testCaseSensitiveAlias() throws Exception {
        setupTestData();

        String result = queryExecutor.execute("SELECT name AS ProductName, price AS PRICE FROM products");
        JsonNode resultNode = objectMapper.readTree(result);

        assertTrue(resultNode.get(0).has("ProductName"));
        assertTrue(resultNode.get(0).has("PRICE"));
        assertFalse(resultNode.get(0).has("productname"));
    }
}

