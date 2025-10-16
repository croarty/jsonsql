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
 * Tests to reproduce and fix complex nested path issues.
 */
class NestedPathFixTest {

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
        // Test data with null arrays and complex nested structures
        String products = """
        {"products": [
            {
                "id": 1,
                "name": "Smartphone",
                "price": 19.99,
                "category": "Electronics",
                "tags": ["mobile", "smartphone"],
                "specs": {
                    "display": {
                        "size": "6.1 inch",
                        "features": ["OLED", "HDR"]
                    },
                    "camera": {
                        "main": {
                            "megapixels": 48,
                            "features": ["OIS", "Portrait"]
                        }
                    }
                }
            },
            {
                "id": 2,
                "name": "Laptop",
                "price": 29.99,
                "category": "Electronics",
                "tags": null,
                "specs": {
                    "display": {
                        "size": "15.6 inch",
                        "features": ["IPS", "Touch"]
                    }
                }
            },
            {
                "id": 3,
                "name": "Widget",
                "price": 9.99,
                "category": "Tools",
                "tags": ["tool", "metal"],
                "specs": {
                    "display": {
                        "size": "5.0 inch",
                        "features": ["LCD"]
                    }
                }
            }
        ]}
        """;

        java.nio.file.Files.writeString(dataDir.toPath().resolve("products.json"), products);
        mappingManager.addMapping("products", "products.json:$.products[*]");
    }

    @Test
    void testUnnestWithNullArrays() throws Exception {
        // Issue: UNNEST with null arrays should skip the row, not create a row with null
        String sql = "SELECT name, tag FROM products, UNNEST(tags) AS t(tag)";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        // Should be 4 rows: 2 from Smartphone + 2 from Widget (Laptop has null tags)
        assertEquals(4, resultArray.size());
        
        // Verify no null tags
        for (JsonNode row : resultArray) {
            assertFalse(row.get("tag").isNull());
            String name = row.get("name").asText();
            assertTrue(name.equals("Smartphone") || name.equals("Widget"));
        }
    }

    @Test
    void testUnnestWithComplexNestedPath() throws Exception {
        // Issue: UNNEST on deeply nested paths like specs.display.features
        String sql = "SELECT name, feature FROM products, UNNEST(specs.display.features) AS f(feature)";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        // Should be 5 rows: 2 from Smartphone + 2 from Laptop + 1 from Widget
        assertEquals(5, resultArray.size());
        
        // Verify all features are present
        boolean foundOLED = false;
        boolean foundHDR = false;
        boolean foundIPS = false;
        boolean foundTouch = false;
        boolean foundLCD = false;
        
        for (JsonNode row : resultArray) {
            String feature = row.get("feature").asText();
            assertTrue(row.has("name"));
            
            if (feature.equals("OLED")) foundOLED = true;
            if (feature.equals("HDR")) foundHDR = true;
            if (feature.equals("IPS")) foundIPS = true;
            if (feature.equals("Touch")) foundTouch = true;
            if (feature.equals("LCD")) foundLCD = true;
        }
        
        assertTrue(foundOLED);
        assertTrue(foundHDR);
        assertTrue(foundIPS);
        assertTrue(foundTouch);
        assertTrue(foundLCD);
    }

    @Test
    void testUnnestWithVeryDeepNestedPath() throws Exception {
        // Issue: UNNEST on very deeply nested paths like specs.camera.main.features
        String sql = "SELECT name, feature FROM products, UNNEST(specs.camera.main.features) AS f(feature)";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        // Should be 2 rows: only Smartphone has camera.main.features
        assertEquals(2, resultArray.size());
        
        // Verify camera features
        boolean foundOIS = false;
        boolean foundPortrait = false;
        
        for (JsonNode row : resultArray) {
            String feature = row.get("feature").asText();
            assertEquals("Smartphone", row.get("name").asText());
            
            if (feature.equals("OIS")) foundOIS = true;
            if (feature.equals("Portrait")) foundPortrait = true;
        }
        
        assertTrue(foundOIS);
        assertTrue(foundPortrait);
    }

    @Test
    void testOrderByWithNestedFields() throws Exception {
        // Issue: ORDER BY with nested fields causes null pointer exceptions
        String sql = "SELECT name, specs.display.size FROM products ORDER BY specs.display.size";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertTrue(resultArray.isArray());
        assertEquals(3, resultArray.size());
        
        // Verify ordering by display size
        String[] expectedOrder = {"5.0 inch", "6.1 inch", "15.6 inch"};
        for (int i = 0; i < expectedOrder.length; i++) {
            assertEquals(expectedOrder[i], resultArray.get(i).get("size").asText());
        }
    }

    @Test
    void testWhereWithParentheses() throws Exception {
        // Issue: WHERE with parentheses not working correctly
        String sql = "SELECT name FROM products WHERE (category = 'Tools') AND (price < 20)";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        // Should be 1 row: Widget (Tools category AND price < 20)
        assertEquals(1, resultArray.size());
        assertEquals("Widget", resultArray.get(0).get("name").asText());
    }

    @Test
    void testComplexNestedWhere() throws Exception {
        // Test WHERE with nested object access
        String sql = "SELECT name FROM products WHERE specs.display.size = '6.1 inch'";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertEquals(1, resultArray.size());
        assertEquals("Smartphone", resultArray.get(0).get("name").asText());
    }

    @Test
    void testUnnestWithWhereOnNestedField() throws Exception {
        // Test UNNEST with WHERE on nested field
        String sql = "SELECT name, feature FROM products, UNNEST(specs.display.features) AS f(feature) WHERE feature = 'OLED'";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertEquals(1, resultArray.size());
        JsonNode row = resultArray.get(0);
        assertEquals("Smartphone", row.get("name").asText());
        assertEquals("OLED", row.get("feature").asText());
    }

    @Test
    void testUnnestWithOrderByOnNestedField() throws Exception {
        // Test UNNEST with ORDER BY on nested field
        String sql = "SELECT name, feature FROM products, UNNEST(specs.display.features) AS f(feature) ORDER BY feature";
        
        String result = queryExecutor.execute(sql);
        JsonNode resultArray = objectMapper.readTree(result);
        
        assertEquals(5, resultArray.size());
        
        // Verify alphabetical ordering
        String[] expectedOrder = {"HDR", "IPS", "LCD", "OLED", "Touch"};
        for (int i = 0; i < expectedOrder.length; i++) {
            assertEquals(expectedOrder[i], resultArray.get(i).get("feature").asText());
        }
    }
}
