package com.jsonsql.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.query.QueryExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for parameterized queries with QueryExecutor.
 */
class ParameterizedQueryIntegrationTest {
    
    private File testDir;
    private QueryExecutor executor;
    private ObjectMapper objectMapper;
    private MappingManager mappingManager;
    private QueryManager queryManager;
    
    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        testDir = Files.createTempDirectory("param-query-test").toFile();
        
        // Create test data
        createTestFiles();
        
        // Set up mappings
        File mappingFile = new File(testDir, ".jsonsql-mappings.json");
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "$.products");
        
        executor = new QueryExecutor(mappingManager, testDir);
        
        // Set up query manager
        File queriesFile = new File(testDir, ".jsonsql-queries.json");
        queryManager = new QueryManager(queriesFile);
    }
    
    @AfterEach
    void tearDown() {
        deleteDirectory(testDir);
    }
    
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
    
    private void createTestFiles() throws IOException {
        String productsJson = """
            {
              "products": [
                {"id": 1, "name": "Laptop", "price": 999.99, "category": "Electronics", "inStock": true},
                {"id": 2, "name": "Desk", "price": 299.99, "category": "Furniture", "inStock": true},
                {"id": 3, "name": "Monitor", "price": 199.99, "category": "Electronics", "inStock": false},
                {"id": 4, "name": "Chair", "price": 149.99, "category": "Furniture", "inStock": true},
                {"id": 5, "name": "Keyboard", "price": 79.99, "category": "Electronics", "inStock": true}
              ]
            }
            """;
        
        Files.writeString(new File(testDir, "products.json").toPath(), productsJson);
    }
    
    @Test
    void testParameterizedQueryWithSingleParameter() throws Exception {
        String query = "SELECT * FROM products WHERE price > ${min_price}";
        Map<String, String> params = Map.of("min_price", "100");
        
        String queryWithParams = QueryParameterReplacer.replaceParameters(query, params);
        String result = executor.execute(queryWithParams);
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(4, resultNode.size()); // 4 products with price > 100
    }
    
    @Test
    void testParameterizedQueryWithMultipleParameters() throws Exception {
        String query = "SELECT * FROM products WHERE price > ${min_price} AND category = '${category}'";
        Map<String, String> params = new HashMap<>();
        params.put("min_price", "100");
        params.put("category", "Electronics");
        
        String queryWithParams = QueryParameterReplacer.replaceParameters(query, params);
        String result = executor.execute(queryWithParams);
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(2, resultNode.size()); // Laptop and Monitor
    }
    
    @Test
    void testParameterizedQueryWithDefaultValue() throws Exception {
        String query = "SELECT * FROM products WHERE price > ${min_price:150}";
        Map<String, String> params = new HashMap<>();
        
        String queryWithParams = QueryParameterReplacer.replaceParameters(query, params);
        String result = executor.execute(queryWithParams);
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(3, resultNode.size()); // Products with price > 150
    }
    
    @Test
    void testParameterizedQueryWithDefaultOverridden() throws Exception {
        String query = "SELECT * FROM products WHERE price > ${min_price:150}";
        Map<String, String> params = Map.of("min_price", "200");
        
        String queryWithParams = QueryParameterReplacer.replaceParameters(query, params);
        String result = executor.execute(queryWithParams);
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(2, resultNode.size()); // Products with price > 200
    }
    
    @Test
    void testParameterizedQueryWithStringParameter() throws Exception {
        String query = "SELECT * FROM products WHERE category = '${category}'";
        Map<String, String> params = Map.of("category", "Furniture");
        
        String queryWithParams = QueryParameterReplacer.replaceParameters(query, params);
        String result = executor.execute(queryWithParams);
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(2, resultNode.size()); // Desk and Chair
    }
    
    @Test
    void testParameterizedQueryWithLIKE() throws Exception {
        String query = "SELECT * FROM products WHERE name LIKE '${pattern}'";
        Map<String, String> params = Map.of("pattern", "%top%");
        
        String queryWithParams = QueryParameterReplacer.replaceParameters(query, params);
        String result = executor.execute(queryWithParams);
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(1, resultNode.size()); // Laptop
    }
    
    @Test
    void testParameterizedQueryWithIN() throws Exception {
        String query = "SELECT * FROM products WHERE category IN (${categories})";
        Map<String, String> params = Map.of("categories", "'Electronics', 'Furniture'");
        
        String queryWithParams = QueryParameterReplacer.replaceParameters(query, params);
        String result = executor.execute(queryWithParams);
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(5, resultNode.size()); // All products
    }
    
    @Test
    void testParameterizedQueryWithORDERBYAndLIMIT() throws Exception {
        String query = "SELECT * FROM products WHERE price > ${min_price} ORDER BY price DESC LIMIT ${limit:3}";
        Map<String, String> params = Map.of("min_price", "100");
        
        String queryWithParams = QueryParameterReplacer.replaceParameters(query, params);
        String result = executor.execute(queryWithParams);
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(3, resultNode.size());
        
        // Verify ordering (highest price first)
        double firstPrice = resultNode.get(0).get("price").asDouble();
        double secondPrice = resultNode.get(1).get("price").asDouble();
        assertTrue(firstPrice >= secondPrice);
    }
    
    @Test
    void testSavedParameterizedQuery() throws Exception {
        // Save a parameterized query
        String savedQuery = "SELECT * FROM products WHERE price > ${min_price} AND category = '${category}'";
        queryManager.saveQuery("filtered_products", savedQuery);
        
        // Retrieve and parameterize
        String query = queryManager.getQuery("filtered_products");
        Map<String, String> params = new HashMap<>();
        params.put("min_price", "100");
        params.put("category", "Electronics");
        
        String queryWithParams = QueryParameterReplacer.replaceParameters(query, params);
        String result = executor.execute(queryWithParams);
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertEquals(2, resultNode.size());
    }
    
    @Test
    void testMissingRequiredParameterThrowsException() {
        String query = "SELECT * FROM products WHERE price > ${min_price}";
        Map<String, String> params = new HashMap<>();
        
        assertThrows(IllegalArgumentException.class, () -> {
            QueryParameterReplacer.replaceParameters(query, params);
        });
    }
    
    @Test
    void testParameterizedQueryWithComplexConditions() throws Exception {
        String query = "SELECT * FROM products WHERE (price > ${min_price} OR category = '${category}') AND inStock = ${in_stock}";
        Map<String, String> params = new HashMap<>();
        params.put("min_price", "200");
        params.put("category", "Furniture");
        params.put("in_stock", "true");
        
        String queryWithParams = QueryParameterReplacer.replaceParameters(query, params);
        String result = executor.execute(queryWithParams);
        
        JsonNode resultNode = objectMapper.readTree(result);
        assertTrue(resultNode.size() > 0);
    }
}

