package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UNNEST combined with JOIN operations.
 * These tests verify that UNNEST operations execute before JOINs,
 * allowing UNNEST columns to be used in JOIN conditions.
 */
public class UnnestJoinTest {

    @TempDir
    File tempDir;

    private QueryParser queryParser;
    private QueryExecutor queryExecutor;
    private MappingManager mappingManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        queryParser = new QueryParser();
        
        // Create a temporary config file for MappingManager
        File configFile = new File(tempDir, "mappings.json");
        mappingManager = new MappingManager(configFile);
        queryExecutor = new QueryExecutor(mappingManager, tempDir);
        objectMapper = new ObjectMapper();

        // Create test data similar to the user's scenario
        // Commercial offers with discount references
        String offersData = """
            {
              "commercialoffers": [
                {
                  "suid": "offer_suid_1",
                  "name": "Summer Sale",
                  "discounts": ["discount_suid_1", "discount_suid_2"]
                },
                {
                  "suid": "offer_suid_2",
                  "name": "Winter Special",
                  "discounts": ["discount_suid_2", "discount_suid_3"]
                },
                {
                  "suid": "offer_suid_3",
                  "name": "Clearance",
                  "discounts": []
                }
              ],
              "discounts": [
                {
                  "suid": "discount_suid_1",
                  "type": "percentage",
                  "value": 15,
                  "description": "15% off"
                },
                {
                  "suid": "discount_suid_2",
                  "type": "fixed",
                  "value": 10,
                  "description": "$10 off"
                },
                {
                  "suid": "discount_suid_3",
                  "type": "percentage",
                  "value": 25,
                  "description": "25% off"
                }
              ]
            }
            """;

        File offersFile = new File(tempDir, "offers.json");
        Files.write(offersFile.toPath(), offersData.getBytes());

        // Add mappings
        mappingManager.addMapping("commercialoffers", "offers.json:$.commercialoffers[*]");
        mappingManager.addMapping("discounts", "offers.json:$.discounts[*]");

        // Create additional test data for nested object joins
        String productsData = """
            {
              "products": [
                {
                  "id": 1,
                  "name": "Product A",
                  "categories": [
                    {"id": 10, "name": "Electronics"},
                    {"id": 20, "name": "Gadgets"}
                  ]
                },
                {
                  "id": 2,
                  "name": "Product B",
                  "categories": [
                    {"id": 20, "name": "Gadgets"}
                  ]
                }
              ],
              "categoryDetails": [
                {"id": 10, "name": "Electronics", "taxRate": 0.08},
                {"id": 20, "name": "Gadgets", "taxRate": 0.10},
                {"id": 30, "name": "Accessories", "taxRate": 0.05}
              ]
            }
            """;

        File productsFile = new File(tempDir, "products.json");
        Files.write(productsFile.toPath(), productsData.getBytes());

        mappingManager.addMapping("products", "products.json:$.products[*]");
        mappingManager.addMapping("categoryDetails", "products.json:$.categoryDetails[*]");
    }

    @Test
    void testUnnestJoinOnStringValue() throws Exception {
        // Test joining on an unnested string value (like discount suid)
        String sql = """
            SELECT co.suid AS offer_id, co.name AS offer_name, 
                   discs.suid AS discount_id, discs.type, discs.value
            FROM commercialoffers co, UNNEST(co.discounts) AS d(disc)
            JOIN discounts discs ON disc = discs.suid
            """;
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(4, resultArray.size()); // 2 discounts for offer 1 + 2 discounts for offer 2
        
        // Verify structure
        JsonNode firstRow = resultArray.get(0);
        assertTrue(firstRow.has("offer_id"));
        assertTrue(firstRow.has("offer_name"));
        assertTrue(firstRow.has("discount_id"));
        assertTrue(firstRow.has("type"));
        assertTrue(firstRow.has("value"));
        
        // Verify we get correct combinations
        Set<String> combinations = new HashSet<>();
        for (JsonNode row : resultArray) {
            String offerId = row.get("offer_id").asText();
            String discountId = row.get("discount_id").asText();
            combinations.add(offerId + ":" + discountId);
        }
        
        assertTrue(combinations.contains("offer_suid_1:discount_suid_1"));
        assertTrue(combinations.contains("offer_suid_1:discount_suid_2"));
        assertTrue(combinations.contains("offer_suid_2:discount_suid_2"));
        assertTrue(combinations.contains("offer_suid_2:discount_suid_3"));
    }

    @Test
    void testUnnestJoinOnNestedObjectProperty() throws Exception {
        // Test joining on a property of an unnested object
        String sql = """
            SELECT p.name AS product_name, 
                   cat.name AS category_name,
                   cd.taxRate
            FROM products p, UNNEST(p.categories) AS c(cat)
            JOIN categoryDetails cd ON cat.id = cd.id
            """;
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(3, resultArray.size()); // 2 categories for Product A + 1 for Product B
        
        // Verify structure
        JsonNode firstRow = resultArray.get(0);
        assertTrue(firstRow.has("product_name"));
        assertTrue(firstRow.has("category_name"));
        assertTrue(firstRow.has("taxRate"));
        
        // Verify we get correct tax rates
        boolean foundElectronics = false;
        boolean foundGadgets = false;
        for (JsonNode row : resultArray) {
            String category = row.get("category_name").asText();
            if ("Electronics".equals(category)) {
                assertEquals(0.08, row.get("taxRate").asDouble(), 0.001);
                foundElectronics = true;
            }
            if ("Gadgets".equals(category)) {
                assertEquals(0.10, row.get("taxRate").asDouble(), 0.001);
                foundGadgets = true;
            }
        }
        
        assertTrue(foundElectronics, "Should find Electronics category");
        assertTrue(foundGadgets, "Should find Gadgets category");
    }

    @Test
    void testUnnestJoinWithWhereClause() throws Exception {
        // Test UNNEST + JOIN with WHERE filtering
        String sql = """
            SELECT co.name AS offer_name, discs.type, discs.value
            FROM commercialoffers co, UNNEST(co.discounts) AS d(disc)
            JOIN discounts discs ON disc = discs.suid
            WHERE discs.type = 'percentage'
            """;
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size()); // Only percentage discounts
        
        // Verify all results are percentage type
        for (JsonNode row : resultArray) {
            assertEquals("percentage", row.get("type").asText());
        }
    }

    @Test
    void testUnnestLeftJoin() throws Exception {
        // Test LEFT JOIN with UNNEST - should include offers even with no discounts
        String sql = """
            SELECT co.suid AS offer_id, co.name AS offer_name, 
                   discs.suid AS discount_id, discs.type
            FROM commercialoffers co, UNNEST(co.discounts) AS d(disc)
            LEFT JOIN discounts discs ON disc = discs.suid
            """;
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        // Should have 4 rows (2 discounts for offer 1 + 2 discounts for offer 2)
        // Note: offer 3 has empty discounts array, so UNNEST produces no rows for it
        assertEquals(4, resultArray.size());
        
        // All rows should have offer info
        for (JsonNode row : resultArray) {
            assertTrue(row.has("offer_id"));
            assertTrue(row.has("offer_name"));
        }
    }

    @Test
    void testUnnestJoinWithOrderBy() throws Exception {
        // Test UNNEST + JOIN with ORDER BY
        String sql = """
            SELECT co.name AS offer_name, discs.value
            FROM commercialoffers co, UNNEST(co.discounts) AS d(disc)
            JOIN discounts discs ON disc = discs.suid
            ORDER BY discs.value DESC
            """;
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertTrue(resultArray.size() > 1);
        
        // Verify descending order
        for (int i = 0; i < resultArray.size() - 1; i++) {
            double current = resultArray.get(i).get("value").asDouble();
            double next = resultArray.get(i + 1).get("value").asDouble();
            assertTrue(current >= next, "Values should be in descending order");
        }
    }

    @Test
    void testUnnestJoinWithMultipleConditions() throws Exception {
        // Test UNNEST + JOIN with complex WHERE conditions
        String sql = """
            SELECT co.name AS offer_name, discs.type, discs.value
            FROM commercialoffers co, UNNEST(co.discounts) AS d(disc)
            JOIN discounts discs ON disc = discs.suid
            WHERE discs.type = 'percentage' AND discs.value > 15
            """;
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(1, resultArray.size()); // Only discount_suid_3 (25%)
        
        JsonNode row = resultArray.get(0);
        assertEquals("percentage", row.get("type").asText());
        assertEquals(25, row.get("value").asInt());
    }

    @Test
    void testUnnestJoinOnNestedPropertyWithAlias() throws Exception {
        // Test joining on nested property using table aliases
        String sql = """
            SELECT p.name AS product, cat.id AS category_id, cd.name AS category_name
            FROM products p, UNNEST(p.categories) AS c(cat)
            JOIN categoryDetails cd ON cat.id = cd.id
            WHERE p.id = 1
            """;
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size()); // Product A has 2 categories
        
        // Verify both categories are present
        Set<String> categoryNames = new HashSet<>();
        for (JsonNode row : resultArray) {
            categoryNames.add(row.get("category_name").asText());
        }
        
        assertTrue(categoryNames.contains("Electronics"));
        assertTrue(categoryNames.contains("Gadgets"));
    }

    @Test
    void testUnnestJoinWithSelectStar() throws Exception {
        // Test UNNEST + JOIN with SELECT *
        String sql = """
            SELECT *
            FROM commercialoffers co, UNNEST(co.discounts) AS d(disc)
            JOIN discounts discs ON disc = discs.suid
            LIMIT 2
            """;
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size());
        
        // Verify all fields are present
        JsonNode firstRow = resultArray.get(0);
        assertTrue(firstRow.has("suid") || firstRow.has("co.suid"));
        assertTrue(firstRow.has("name") || firstRow.has("co.name"));
    }

    @Test
    void testUnnestJoinWithEmptyArray() throws Exception {
        // Test that UNNEST with empty array produces no rows (offer_suid_3 has empty discounts)
        String sql = """
            SELECT co.suid, discs.suid
            FROM commercialoffers co, UNNEST(co.discounts) AS d(disc)
            JOIN discounts discs ON disc = discs.suid
            WHERE co.suid = 'offer_suid_3'
            """;
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(0, resultArray.size()); // Empty array means no rows from UNNEST
    }

    @Test
    void testUnnestJoinMultipleUnnests() throws Exception {
        // Test multiple UNNESTs with JOIN (if supported)
        // This tests the scenario where we might have multiple arrays to unnest
        String sql = """
            SELECT co.name AS offer_name, discs.value
            FROM commercialoffers co, UNNEST(co.discounts) AS d(disc)
            JOIN discounts discs ON disc = discs.suid
            WHERE co.suid = 'offer_suid_1'
            """;
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(2, resultArray.size()); // offer_suid_1 has 2 discounts
    }
}

