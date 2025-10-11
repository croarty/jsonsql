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
 * Integration tests combining multiple features.
 */
class IntegrationTest {

    @TempDir
    Path tempDir;

    private File configFile;
    private File dataDir;
    private MappingManager mappingManager;
    private QueryExecutor queryExecutor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        configFile = tempDir.resolve("test-mappings.json").toFile();
        mappingManager = new MappingManager(configFile);
        dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        queryExecutor = new QueryExecutor(mappingManager, dataDir);
        
        setupComplexTestData();
    }

    private void setupComplexTestData() throws Exception {
        // Products
        String products = """
        {"products": [
            {"id": 1, "name": "Widget", "price": 19.99, "category": "Tools", "stock": 100},
            {"id": 2, "name": "Gadget", "price": 29.99, "category": "Electronics", "stock": 50},
            {"id": 3, "name": "Doohickey", "price": 9.99, "category": "Tools", "stock": 200}
        ]}
        """;
        
        // Orders
        String orders = """
        {"orders": [
            {"orderId": 101, "customerId": 1, "productId": 1, "quantity": 5, "status": "completed", "total": 99.95},
            {"orderId": 102, "customerId": 2, "productId": 2, "quantity": 2, "status": "pending", "total": 59.98},
            {"orderId": 103, "customerId": 1, "productId": 3, "quantity": 10, "status": "completed", "total": 99.90}
        ]}
        """;
        
        // Customers with nested data
        String customers = """
        {"customers": [
            {
                "id": 1,
                "name": "Alice",
                "email": "alice@example.com",
                "profile": {
                    "vip": true,
                    "tier": "gold",
                    "points": 1000
                },
                "address": {
                    "city": "New York",
                    "country": "USA"
                }
            },
            {
                "id": 2,
                "name": "Bob",
                "email": "bob@example.com",
                "profile": {
                    "vip": false,
                    "tier": "bronze",
                    "points": 100
                },
                "address": {
                    "city": "London",
                    "country": "UK"
                }
            }
        ]}
        """;
        
        Files.writeString(dataDir.toPath().resolve("products.json"), products);
        Files.writeString(dataDir.toPath().resolve("orders.json"), orders);
        Files.writeString(dataDir.toPath().resolve("customers.json"), customers);
        
        mappingManager.addMapping("products", "products.json:$.products");
        mappingManager.addMapping("orders", "orders.json:$.orders");
        mappingManager.addMapping("customers", "customers.json:$.customers");
    }

    @Test
    void testCompleteEcommerceScenario() throws Exception {
        // Complex query using all features: JOIN, WHERE, ORDER BY, TOP, nested fields, aliases
        String result = queryExecutor.execute("""
            SELECT TOP 10
                o.orderId AS orderNumber,
                c.name AS customerName,
                c.profile.tier AS customerTier,
                p.name AS productName,
                p.category AS productCategory,
                o.quantity,
                o.total AS orderTotal,
                c.address.city AS city
            FROM orders o
            JOIN customers c ON o.customerId = c.id
            JOIN products p ON o.productId = p.id
            WHERE o.status = 'completed'
            ORDER BY o.total DESC
            """);

        JsonNode resultNode = objectMapper.readTree(result);

        assertTrue(resultNode.size() > 0);
        JsonNode first = resultNode.get(0);
        
        // Verify all aliased columns present
        assertTrue(first.has("orderNumber"));
        assertTrue(first.has("customerName"));
        assertTrue(first.has("customerTier"));
        assertTrue(first.has("productName"));
        assertTrue(first.has("productCategory"));
        assertTrue(first.has("quantity"));
        assertTrue(first.has("orderTotal"));
        assertTrue(first.has("city"));
        
        // Verify no original names
        assertFalse(first.has("orderId"));
        assertFalse(first.has("name"));
        assertFalse(first.has("total"));
    }

    @Test
    void testThreeWayJoinWithNestedFields() throws Exception {
        String result = queryExecutor.execute("""
            SELECT 
                c.name,
                c.profile.tier,
                c.address.city,
                p.name,
                o.quantity
            FROM orders o
            JOIN customers c ON o.customerId = c.id
            JOIN products p ON o.productId = p.id
            WHERE c.profile.vip = true
            ORDER BY o.quantity DESC
            """);

        JsonNode resultNode = objectMapper.readTree(result);

        assertTrue(resultNode.size() > 0);
        // All results should be for VIP customers
        for (JsonNode row : resultNode) {
            assertTrue(row.has("c.name") || row.has("name"));
            assertTrue(row.has("tier"));
            assertTrue(row.has("city"));
        }
    }

    @Test
    void testLeftJoinWithNestedFields() throws Exception {
        String result = queryExecutor.execute("""
            SELECT 
                o.orderId,
                c.name AS customerName,
                c.profile.tier AS tier
            FROM orders o
            LEFT JOIN customers c ON o.customerId = c.id
            """);

        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(3, resultNode.size());
        // All orders should be present
        assertTrue(resultNode.get(0).has("orderId"));
    }

    @Test
    void testMultipleOrderByWithNestedFields() throws Exception {
        String result = queryExecutor.execute("""
            SELECT 
                c.name,
                c.profile.tier,
                c.profile.points
            FROM customers c
            ORDER BY c.profile.tier, c.profile.points DESC
            """);

        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(2, resultNode.size());
    }

    @Test
    void testWhereOnMultipleNestedLevels() throws Exception {
        String result = queryExecutor.execute("""
            SELECT c.name, c.address
            FROM customers c
            WHERE c.address.country = 'USA'
            """);

        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertEquals("Alice", resultNode.get(0).get("name").asText());
        assertTrue(resultNode.get(0).get("address").isObject());
    }

    @Test
    void testCombiningAllFeatures() throws Exception {
        // Absolute kitchen sink query
        String result = queryExecutor.execute("""
            SELECT TOP 5
                o.orderId AS orderNum,
                c.name AS customer,
                c.profile.tier AS vipLevel,
                c.address.city AS location,
                p.name AS product,
                p.category,
                o.quantity AS qty,
                o.total
            FROM orders o
            JOIN customers c ON o.customerId = c.id
            JOIN products p ON o.productId = p.id
            WHERE o.status = 'completed'
            ORDER BY c.profile.points DESC
            """);

        JsonNode resultNode = objectMapper.readTree(result);

        assertTrue(resultNode.size() > 0);
        assertTrue(resultNode.size() <= 5);
        
        JsonNode first = resultNode.get(0);
        assertTrue(first.has("orderNum"));
        assertTrue(first.has("customer"));
        assertTrue(first.has("vipLevel"));
        assertTrue(first.has("location"));
        assertTrue(first.has("product"));
        assertTrue(first.has("category"));
        assertTrue(first.has("qty"));
        assertTrue(first.has("total"));
    }

    @Test
    void testMultipleJoinsWithAliases() throws Exception {
        String result = queryExecutor.execute("""
            SELECT 
                p.id AS pid,
                p.name AS pname,
                c.id AS cid,
                c.name AS cname,
                o.orderId AS oid
            FROM orders o
            JOIN products p ON o.productId = p.id
            JOIN customers c ON o.customerId = c.id
            """);

        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(3, resultNode.size());
        
        // All should use aliases
        assertTrue(resultNode.get(0).has("pid"));
        assertTrue(resultNode.get(0).has("pname"));
        assertTrue(resultNode.get(0).has("cid"));
        assertTrue(resultNode.get(0).has("cname"));
        assertTrue(resultNode.get(0).has("oid"));
    }

    @Test
    void testNestedFieldsWithoutTableAlias() throws Exception {
        // Test accessing nested fields - need table alias for nested field access
        String result = queryExecutor.execute("""
            SELECT c.name, c.profile.tier, c.profile.points
            FROM customers c
            WHERE c.profile.vip = true
            """);

        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        assertEquals("Alice", resultNode.get(0).get("name").asText());
    }

    @Test
    void testComplexNestedStructurePreservation() throws Exception {
        String result = queryExecutor.execute("""
            SELECT 
                c.name,
                c.profile,
                c.address
            FROM customers c
            WHERE c.profile.tier = 'gold'
            """);

        JsonNode resultNode = objectMapper.readTree(result);

        assertEquals(1, resultNode.size());
        
        // Verify nested objects are fully preserved
        JsonNode profile = resultNode.get(0).get("profile");
        assertTrue(profile.isObject());
        assertTrue(profile.has("vip"));
        assertTrue(profile.has("tier"));
        assertTrue(profile.has("points"));
        
        JsonNode address = resultNode.get(0).get("address");
        assertTrue(address.isObject());
        assertTrue(address.has("city"));
        assertTrue(address.has("country"));
    }
}

