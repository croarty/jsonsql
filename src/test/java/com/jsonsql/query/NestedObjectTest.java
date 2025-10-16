package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for nested object access in SELECT and WHERE clauses.
 * Documents current behavior and identifies areas for improvement.
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
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        configFile = tempDir.resolve("test-mappings.json").toFile();
        mappingManager = new MappingManager(configFile);
        dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        queryExecutor = new QueryExecutor(mappingManager, dataDir);

        setupTestData();
    }

    private void setupTestData() throws Exception {
        // Create complex nested data
        String customers = """
        {"customers": [
            {
                "id": 1,
                "name": "Alice Johnson",
                "profile": {
                    "vip": true,
                    "level": "Gold",
                    "points": 5000,
                    "preferences": {
                        "newsletter": true,
                        "language": "en"
                    }
                },
                "address": {
                    "street": "123 Main St",
                    "city": "New York",
                    "state": "NY",
                    "zip": "10001",
                    "country": "USA",
                    "coordinates": {
                        "lat": 40.7128,
                        "lng": -74.0060
                    }
                }
            },
            {
                "id": 2,
                "name": "Bob Smith",
                "profile": {
                    "vip": false,
                    "level": "Bronze",
                    "points": 500,
                    "preferences": {
                        "newsletter": false,
                        "language": "es"
                    }
                },
                "address": {
                    "street": "456 Oak Ave",
                    "city": "Los Angeles",
                    "state": "CA",
                    "zip": "90210",
                    "country": "USA",
                    "coordinates": {
                        "lat": 34.0522,
                        "lng": -118.2437
                    }
                }
            },
            {
                "id": 3,
                "name": "Carol White",
                "profile": {
                    "vip": true,
                    "level": "Silver",
                    "points": 2000,
                    "preferences": {
                        "newsletter": true,
                        "language": "en"
                    }
                },
                "address": {
                    "street": "789 Pine Rd",
                    "city": "Chicago",
                    "state": "IL",
                    "zip": "60601",
                    "country": "USA",
                    "coordinates": {
                        "lat": 41.8781,
                        "lng": -87.6298
                    }
                }
            }
        ]}
        """;

        // Write test data file
        java.nio.file.Files.writeString(dataDir.toPath().resolve("customers.json"), customers);

        // Set up mapping
        mappingManager.addMapping("customers", "customers.json:$.customers[*]");
    }

    @Test
    void testNestedObjectSelection() throws Exception {
        // Test selecting nested object fields
        String sql = "SELECT name, profile.level, profile.points FROM customers";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(3, resultArray.size());
        
        // Verify nested fields are selected correctly
        JsonNode alice = resultArray.get(0);
        assertEquals("Alice Johnson", alice.get("name").asText());
        assertEquals("Gold", alice.get("level").asText());
        assertEquals(5000, alice.get("points").asInt());
    }

    @Test
    void testDeeplyNestedObjectSelection() throws Exception {
        // Test selecting deeply nested object fields
        String sql = "SELECT name, profile.preferences.language, address.coordinates.lat FROM customers";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(3, resultArray.size());
        
        // Verify deeply nested fields are selected correctly
        JsonNode alice = resultArray.get(0);
        assertEquals("Alice Johnson", alice.get("name").asText());
        assertEquals("en", alice.get("language").asText());
        assertEquals(40.7128, alice.get("lat").asDouble(), 0.0001);
    }

    @Test
    void testNestedObjectWhereClause() throws Exception {
        // Test WHERE clause with nested object access
        String sql = "SELECT name FROM customers WHERE profile.level = 'Gold'";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(1, resultArray.size());
        assertEquals("Alice Johnson", resultArray.get(0).get("name").asText());
    }

    @Test
    void testDeeplyNestedObjectWhereClause() throws Exception {
        // Test WHERE clause with deeply nested object access
        String sql = "SELECT name FROM customers WHERE profile.preferences.newsletter = true";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size());
        
        // Verify we get the correct customers
        String[] expectedNames = {"Alice Johnson", "Carol White"};
        for (int i = 0; i < resultArray.size(); i++) {
            assertTrue(java.util.Arrays.asList(expectedNames).contains(resultArray.get(i).get("name").asText()));
        }
    }

    @Test
    void testNestedObjectWhereWithMultipleConditions() throws Exception {
        // Test WHERE clause with multiple nested conditions
        String sql = "SELECT name FROM customers WHERE profile.vip = true AND address.country = 'USA'";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size());
        
        // Verify we get VIP customers from USA
        String[] expectedNames = {"Alice Johnson", "Carol White"};
        for (int i = 0; i < resultArray.size(); i++) {
            assertTrue(java.util.Arrays.asList(expectedNames).contains(resultArray.get(i).get("name").asText()));
        }
    }

    @Test
    void testNestedObjectWithAliases() throws Exception {
        // Test nested object access with table aliases
        String sql = "SELECT c.name, c.profile.level, c.address.city FROM customers c WHERE c.profile.vip = true";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size());
        
        // Verify nested fields are selected correctly with aliases
        JsonNode alice = resultArray.get(0);
        assertEquals("Alice Johnson", alice.get("name").asText());
        assertTrue(alice.has("level"));
        assertTrue(alice.has("city"));
    }

    @Test
    void testNestedObjectWithOrderBy() throws Exception {
        // Test ORDER BY with nested object fields
        String sql = "SELECT name, profile.points FROM customers ORDER BY profile.points DESC";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(3, resultArray.size());
        
        // Verify ordering by nested field
        assertEquals(5000, resultArray.get(0).get("points").asInt());
        assertEquals(2000, resultArray.get(1).get("points").asInt());
        assertEquals(500, resultArray.get(2).get("points").asInt());
    }

    @Test
    void testNestedObjectWithJoin() throws Exception {
        // Create orders data to test JOIN with nested objects
        String orders = """
        {"orders": [
            {"id": 1, "customerId": 1, "status": "completed", "total": 100.00},
            {"id": 2, "customerId": 2, "status": "pending", "total": 50.00},
            {"id": 3, "customerId": 1, "status": "shipped", "total": 75.00}
        ]}
        """;

        java.nio.file.Files.writeString(dataDir.toPath().resolve("orders.json"), orders);
        mappingManager.addMapping("orders", "orders.json:$.orders[*]");

        // Test JOIN with nested object access
        String sql = "SELECT c.name, c.profile.level, o.total FROM customers c JOIN orders o ON c.id = o.customerId WHERE c.profile.vip = true";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size());
        
        // Verify JOIN results with nested object filtering
        for (JsonNode row : resultArray) {
            assertEquals("Alice Johnson", row.get("name").asText());
            assertEquals("Gold", row.get("level").asText());
            assertTrue(row.has("total"));
        }
    }

    @Test
    void testNestedObjectWithUnnest() throws Exception {
        // Create customers with array data for UNNEST testing
        String customersWithTags = """
        {"customers": [
            {
                "id": 1,
                "name": "Alice",
                "profile": {"level": "Gold", "tags": ["vip", "premium"]},
                "address": {"city": "New York", "state": "NY"}
            },
            {
                "id": 2,
                "name": "Bob",
                "profile": {"level": "Bronze", "tags": ["basic"]},
                "address": {"city": "Los Angeles", "state": "CA"}
            }
        ]}
        """;

        java.nio.file.Files.writeString(dataDir.toPath().resolve("customers-with-tags.json"), customersWithTags);
        mappingManager.addMapping("customers_with_tags", "customers-with-tags.json:$.customers[*]");

        // Test UNNEST with nested object access
        String sql = "SELECT c.name, c.profile.level, tag FROM customers_with_tags c, UNNEST(c.profile.tags) AS t(tag)";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(3, resultArray.size()); // Alice has 2 tags, Bob has 1
        
        // Verify UNNEST results with nested object access
        boolean foundAliceVip = false;
        boolean foundAlicePremium = false;
        boolean foundBobBasic = false;
        
        for (JsonNode row : resultArray) {
            String name = row.get("name").asText();
            String level = row.get("level").asText();
            String tag = row.get("tag").asText();
            
            if (name.equals("Alice")) {
                assertEquals("Gold", level);
                if (tag.equals("vip")) foundAliceVip = true;
                if (tag.equals("premium")) foundAlicePremium = true;
            } else if (name.equals("Bob")) {
                assertEquals("Bronze", level);
                if (tag.equals("basic")) foundBobBasic = true;
            }
        }
        
        assertTrue(foundAliceVip);
        assertTrue(foundAlicePremium);
        assertTrue(foundBobBasic);
    }

    @Test
    void testNestedObjectWithNullValues() throws Exception {
        // Create data with null nested values
        String customersWithNulls = """
        {"customers": [
            {
                "id": 1,
                "name": "Alice",
                "profile": {"level": "Gold", "points": 5000},
                "address": {"city": "New York", "state": "NY"}
            },
            {
                "id": 2,
                "name": "Bob",
                "profile": null,
                "address": {"city": "Los Angeles", "state": "CA"}
            },
            {
                "id": 3,
                "name": "Carol",
                "profile": {"level": "Silver", "points": null},
                "address": {"city": "Chicago", "state": "IL"}
            }
        ]}
        """;

        java.nio.file.Files.writeString(dataDir.toPath().resolve("customers-with-nulls.json"), customersWithNulls);
        mappingManager.addMapping("customers_with_nulls", "customers-with-nulls.json:$.customers[*]");

        // Test handling of null nested objects
        String sql = "SELECT name, profile.level FROM customers_with_nulls WHERE profile.level IS NOT NULL";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size()); // Alice and Carol (Bob has null profile)
        
        // Verify non-null nested values are handled correctly
        for (JsonNode row : resultArray) {
            assertTrue(row.has("name"));
            assertTrue(row.has("level"));
            assertFalse(row.get("level").isNull());
        }
    }

    @Test
    void testNestedObjectEdgeCases() throws Exception {
        // Test edge cases with nested object access
        
        // Test with non-existent nested field
        String sql1 = "SELECT name FROM customers WHERE profile.nonexistent = 'value'";
        String result1 = queryExecutor.execute(sql1);
        JsonNode resultArray1 = objectMapper.readTree(result1);
        assertEquals(0, resultArray1.size()); // Should return no results
        
        // Test selecting non-existent nested field
        String sql2 = "SELECT name, profile.nonexistent FROM customers";
        String result2 = queryExecutor.execute(sql2);
        JsonNode resultArray2 = objectMapper.readTree(result2);
        assertEquals(3, resultArray2.size());
        
        // Test with empty string in nested field
        String customersWithEmpty = """
        {"customers": [
            {"id": 1, "name": "Alice", "profile": {"level": "", "points": 5000}}
        ]}
        """;
        
        java.nio.file.Files.writeString(dataDir.toPath().resolve("customers-empty.json"), customersWithEmpty);
        mappingManager.addMapping("customers_empty", "customers-empty.json:$.customers[*]");
        
        String sql3 = "SELECT name FROM customers_empty WHERE profile.level = ''";
        String result3 = queryExecutor.execute(sql3);
        JsonNode resultArray3 = objectMapper.readTree(result3);
        assertEquals(1, resultArray3.size());
        assertEquals("Alice", resultArray3.get(0).get("name").asText());
    }

    @Test
    void testNestedObjectPerformance() throws Exception {
        // Test performance with many nested fields
        String sql = "SELECT name, profile.level, profile.points, profile.preferences.language, address.city, address.state, address.coordinates.lat, address.coordinates.lng FROM customers";
        
        long startTime = System.currentTimeMillis();
        String result = queryExecutor.execute(sql);
        long endTime = System.currentTimeMillis();
        
        JsonNode resultArray = objectMapper.readTree(result);
        assertTrue(resultArray.isArray());
        assertEquals(3, resultArray.size());
        
        // Verify all nested fields are present
        JsonNode alice = resultArray.get(0);
        assertTrue(alice.has("name"));
        assertTrue(alice.has("level"));
        assertTrue(alice.has("points"));
        assertTrue(alice.has("language"));
        assertTrue(alice.has("city"));
        assertTrue(alice.has("state"));
        assertTrue(alice.has("lat"));
        assertTrue(alice.has("lng"));
        
        // Performance should be reasonable (less than 1 second for small dataset)
        assertTrue(endTime - startTime < 1000);
    }
}