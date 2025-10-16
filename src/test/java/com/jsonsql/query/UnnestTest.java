package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UNNEST functionality.
 */
public class UnnestTest {

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

        // Create test data with arrays
        String testData = """
            {
              "products": [
                {
                  "id": 1,
                  "name": "Smartphone",
                  "tags": ["mobile", "smartphone", "android"],
                  "reviews": [
                    {"user": "Alice", "rating": 5, "comment": "Great!"},
                    {"user": "Bob", "rating": 4, "comment": "Good value"}
                  ]
                },
                {
                  "id": 2,
                  "name": "Laptop",
                  "tags": ["laptop", "computer", "work"],
                  "reviews": [
                    {"user": "Carol", "rating": 5, "comment": "Perfect for work"}
                  ]
                }
              ]
            }
            """;

        File testFile = new File(tempDir, "products.json");
        Files.write(testFile.toPath(), testData.getBytes());

        // Add mapping for the test data
        mappingManager.addMapping("products", "products.json:$.products[*]");
    }

    @Test
    void testParseUnnestQuery() throws Exception {
        String sql = "SELECT name, tag FROM products, UNNEST(tags) AS t(tag)";
        
        ParsedQuery parsedQuery = queryParser.parse(sql);
        
        assertNotNull(parsedQuery);
        assertEquals("products", parsedQuery.getFromTable().getTableName());
        assertTrue(parsedQuery.hasUnnests());
        assertEquals(1, parsedQuery.getUnnests().size());
        
        UnnestInfo unnest = parsedQuery.getUnnests().get(0);
        assertEquals("tags", unnest.getArrayExpression());
        assertEquals("t", unnest.getAlias());
        assertEquals("tag", unnest.getElementColumn());
    }

    @Test
    void testExecuteSimpleUnnest() throws Exception {
        String sql = "SELECT name, tag FROM products, UNNEST(tags) AS t(tag)";
        
        String result = queryExecutor.execute(sql);
        
        JsonNode resultArray = objectMapper.readTree(result);
        assertTrue(resultArray.isArray());
        assertEquals(6, resultArray.size()); // 3 tags * 2 products
        
        // Check that we have the expected structure
        JsonNode firstRow = resultArray.get(0);
        assertTrue(firstRow.has("name"));
        assertTrue(firstRow.has("tag"));
        
        // Verify all combinations exist
        boolean foundSmartphoneMobile = false;
        boolean foundLaptopWork = false;
        
        for (JsonNode row : resultArray) {
            String name = row.get("name").asText();
            String tag = row.get("tag").asText();
            
            if ("Smartphone".equals(name) && "mobile".equals(tag)) {
                foundSmartphoneMobile = true;
            }
            if ("Laptop".equals(name) && "work".equals(tag)) {
                foundLaptopWork = true;
            }
        }
        
        assertTrue(foundSmartphoneMobile, "Should find Smartphone with mobile tag");
        assertTrue(foundLaptopWork, "Should find Laptop with work tag");
    }

    @Test
    void testExecuteUnnestWithWhere() throws Exception {
        String sql = "SELECT name, tag FROM products, UNNEST(tags) AS t(tag) WHERE tag = 'android'";
        
        String result = queryExecutor.execute(sql);
        
        JsonNode resultArray = objectMapper.readTree(result);
        assertTrue(resultArray.isArray());
        assertEquals(1, resultArray.size()); // Only one product has 'android' tag
        
        JsonNode row = resultArray.get(0);
        assertEquals("Smartphone", row.get("name").asText());
        assertEquals("android", row.get("tag").asText());
    }

    @Test
    void testExecuteUnnestWithObjectArrays() throws Exception {
        String sql = "SELECT name, review.user, review.rating FROM products, UNNEST(reviews) AS r(review)";
        
        String result = queryExecutor.execute(sql);
        
        JsonNode resultArray = objectMapper.readTree(result);
        assertTrue(resultArray.isArray());
        assertEquals(3, resultArray.size()); // 2 reviews for Smartphone + 1 for Laptop
        
        // Check that we have the expected structure
        JsonNode firstRow = resultArray.get(0);
        assertTrue(firstRow.has("name"));
        assertTrue(firstRow.has("user"));
        assertTrue(firstRow.has("rating"));
    }

    @Test
    void testExecuteUnnestWithWhereSmartphone() throws Exception {
        String sql = "SELECT name, tag FROM products, UNNEST(tags) AS t(tag) WHERE tag = 'smartphone'";
        
        String result = queryExecutor.execute(sql);
        
        JsonNode resultArray = objectMapper.readTree(result);
        assertTrue(resultArray.isArray());
        assertEquals(1, resultArray.size()); // Only Smartphone has 'smartphone' tag
        
        JsonNode row = resultArray.get(0);
        assertEquals("Smartphone", row.get("name").asText());
        assertEquals("smartphone", row.get("tag").asText());
    }

    @Test
    void testParseInvalidUnnestSyntax() {
        String sql = "SELECT name FROM products, UNNEST(tags)"; // Missing alias
        
        assertThrows(QueryParseException.class, () -> {
            queryParser.parse(sql);
        });
    }

    @Test
    void testParseUnnestWithoutColumnSpec() throws Exception {
        String sql = "SELECT name FROM products, UNNEST(tags) AS t"; // Missing column specification
        
        ParsedQuery parsedQuery = queryParser.parse(sql);
        
        assertNotNull(parsedQuery);
        assertTrue(parsedQuery.hasUnnests());
        assertEquals(1, parsedQuery.getUnnests().size());
        
        UnnestInfo unnest = parsedQuery.getUnnests().get(0);
        assertEquals("tags", unnest.getArrayExpression());
        assertEquals("t", unnest.getAlias());
        assertEquals("value", unnest.getElementColumn()); // Should default to "value"
    }
}
