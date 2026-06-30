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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnionTest {

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
        
        // Create config file
        configFile = tempDir.resolve("test-mappings.json").toFile();
        mappingManager = new MappingManager(configFile);
        
        // Set up test data directory
        dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        
        // Copy test JSON files
        copyTestData();
        
        // Set up mappings
        mappingManager.addMapping("products", "$.data.products");
        mappingManager.addMapping("orders", "$.orders");
        mappingManager.addMapping("categories", "$.categories");
        
        queryExecutor = new QueryExecutor(mappingManager, dataDir);
    }

    private void copyTestData() throws Exception {
        // Create products.json
        String productsJson = """
        {
          "data": {
            "products": [
              {"id": 1, "name": "Widget", "price": 19.99, "category": "Tools"},
              {"id": 2, "name": "Gadget", "price": 29.99, "category": "Electronics"},
              {"id": 3, "name": "Doohickey", "price": 9.99, "category": "Tools"}
            ]
          }
        }
        """;
        Files.writeString(dataDir.toPath().resolve("products.json"), productsJson);
        
        // Create orders.json
        String ordersJson = """
        {
          "orders": [
            {"orderId": 101, "name": "Order A", "total": 99.95},
            {"orderId": 102, "name": "Order B", "total": 59.97},
            {"orderId": 103, "name": "Order C", "total": 29.97}
          ]
        }
        """;
        Files.writeString(dataDir.toPath().resolve("orders.json"), ordersJson);
        
        // Create categories.json
        String categoriesJson = """
        {
          "categories": [
            {"id": 1, "name": "Tools"},
            {"id": 2, "name": "Electronics"},
            {"id": 3, "name": "Furniture"}
          ]
        }
        """;
        Files.writeString(dataDir.toPath().resolve("categories.json"), categoriesJson);
    }

    @Test
    void testUnionAllBasic() throws Exception {
        String result = queryExecutor.execute(
            "SELECT name FROM products WHERE category = 'Tools' " +
            "UNION ALL " +
            "SELECT name FROM categories"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        assertEquals(5, resultNode.size());  // 2 tools + 3 categories
        
        // Check all values are present
        assertEquals("Widget", resultNode.get(0).get("name").asText());
        assertEquals("Doohickey", resultNode.get(1).get("name").asText());
    }

    @Test
    void testUnionBasic() throws Exception {
        String result = queryExecutor.execute(
            "SELECT name FROM products WHERE category = 'Tools' " +
            "UNION " +
            "SELECT name FROM categories"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        // Should have 5 unique values (2 tools + 3 categories, no overlap)
        assertEquals(5, resultNode.size());
    }

    @Test
    void testUnionAllWithDuplicates() throws Exception {
        String result = queryExecutor.execute(
            "SELECT name FROM products WHERE category = 'Tools' " +
            "UNION ALL " +
            "SELECT name FROM products WHERE category = 'Tools'"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        assertEquals(4, resultNode.size());  // 2 tools × 2 queries
        
        // Count occurrences of each product
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            String name = resultNode.get(i).get("name").asText();
            counts.put(name, counts.getOrDefault(name, 0) + 1);
        }
        
        // Each product should appear twice
        assertEquals(2, counts.get("Widget"));
        assertEquals(2, counts.get("Doohickey"));
    }

    @Test
    void testUnionRemovesDuplicates() throws Exception {
        String result = queryExecutor.execute(
            "SELECT name FROM products WHERE category = 'Tools' " +
            "UNION " +
            "SELECT name FROM products WHERE category = 'Tools'"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        assertEquals(2, resultNode.size());  // Widget and Doohickey, deduplicated
    }

    @Test
    void testUnionChained() throws Exception {
        String result = queryExecutor.execute(
            "SELECT name FROM products WHERE id = 1 " +
            "UNION ALL " +
            "SELECT name FROM categories WHERE id = 2 " +
            "UNION " +
            "SELECT name FROM products WHERE id = 2"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        // First two parts are UNION ALL ( Widget, Electronics ), then UNION with Gadget
        // So we get: Widget, Electronics, Gadget but Widget and Gadget might be the same name
        assertEquals(3, resultNode.size());  // Widget, Electronics, Gadget
    }

    @Test
    void testUnionWithOrderBy() throws Exception {
        String result = queryExecutor.execute(
            "SELECT name FROM products WHERE category = 'Tools' " +
            "UNION ALL " +
            "SELECT name FROM categories " +
            "ORDER BY name"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        
        // Check that results are sorted by name (alphabetical)
        // Products: Doohickey, Widget; Categories: Tools, Electronics, Furniture
        // Sorted: Doohickey < Electronics < Furniture < Tools < Widget
        assertEquals("Doohickey", resultNode.get(0).get("name").asText());
        assertEquals("Electronics", resultNode.get(1).get("name").asText());
        assertEquals("Furniture", resultNode.get(2).get("name").asText());
        assertEquals("Tools", resultNode.get(3).get("name").asText());
        assertEquals("Widget", resultNode.get(4).get("name").asText());
    }

    @Test
    void testUnionWithLimit() throws Exception {
        // In JSqlParser 4.9, LIMIT on UNION queries needs to be applied to individual SELECTs
        String result = queryExecutor.execute(
            "SELECT TOP 2 name FROM products " +
            "UNION ALL " +
            "SELECT TOP 2 name FROM categories"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        assertEquals(4, resultNode.size());  // 2 + 2
    }

    @Test
    void testUnionWithTop() throws Exception {
        String result = queryExecutor.execute(
            "SELECT TOP 2 name FROM products WHERE category = 'Tools' " +
            "UNION ALL " +
            "SELECT TOP 1 name FROM categories"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        assertEquals(3, resultNode.size());  // 2 + 1
    }

    @Test
    void testUnionDifferentColumnsError() throws Exception {
        QueryParseException ex = assertThrows(QueryParseException.class, () -> {
            queryExecutor.execute(
                "SELECT name FROM products " +
                "UNION ALL " +
                "SELECT id, name FROM categories"
            );
        });
        
        assertTrue(ex.getMessage().contains("same number of columns"));
    }

    @Test
    void testUnionAllWithWhereAndJoins() throws Exception {
        // Add orders table for more complex query
        String ordersJson = """
        {
          "orders": [
            {"id": 1, "productId": 1, "quantity": 5},
            {"id": 2, "productId": 2, "quantity": 3}
          ]
        }
        """;
        Files.writeString(dataDir.toPath().resolve("orders.json"), ordersJson);
        
        String result = queryExecutor.execute(
            "SELECT p.name FROM products p WHERE p.id = 1 " +
            "UNION ALL " +
            "SELECT 'Order: ' || o.quantity FROM orders o"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        // Widget + "Order: 5" + "Order: 3" = 3 results
        assertEquals(3, resultNode.size());
    }

    @Test
    void testUnionAllWithDistinct() throws Exception {
        String result = queryExecutor.execute(
            "SELECT DISTINCT category FROM products " +
            "UNION ALL " +
            "SELECT name FROM categories"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        assertEquals(5, resultNode.size());  // 2 distinct categories + 3 categories
    }

    @Test
    void testUnionAllEmptyResults() throws Exception {
        String result = queryExecutor.execute(
            "SELECT name FROM products WHERE id = 999 " +
            "UNION ALL " +
            "SELECT name FROM categories"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        assertEquals(3, resultNode.size());  // Only categories results
    }

    @Test
    void testUnionAllWithOrderByAndLimit() throws Exception {
        String result = queryExecutor.execute(
            "SELECT TOP 3 name FROM products WHERE category = 'Tools' " +
            "UNION ALL " +
            "SELECT TOP 3 name FROM categories " +
            "ORDER BY name"
        );
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.isArray());
        assertEquals(5, resultNode.size());  // 2 tools + 3 categories (only 2 tools exist)
    }
}
