package com.jsonsql.config;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueryParameterReplacer utility class.
 */
class QueryParameterReplacerTest {
    
    @Test
    void testReplaceSingleParameter() {
        String query = "SELECT * FROM products WHERE price > ${min_price}";
        Map<String, String> params = Map.of("min_price", "100");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE price > 100", result);
    }
    
    @Test
    void testReplaceMultipleParameters() {
        String query = "SELECT * FROM products WHERE price > ${min_price} AND category = '${category}'";
        Map<String, String> params = new HashMap<>();
        params.put("min_price", "100");
        params.put("category", "Electronics");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE price > 100 AND category = 'Electronics'", result);
    }
    
    @Test
    void testReplaceParameterWithDefault() {
        String query = "SELECT * FROM products WHERE price > ${min_price:50}";
        Map<String, String> params = new HashMap<>();
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE price > 50", result);
    }
    
    @Test
    void testReplaceParameterWithDefaultOverridden() {
        String query = "SELECT * FROM products WHERE price > ${min_price:50}";
        Map<String, String> params = Map.of("min_price", "100");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE price > 100", result);
    }
    
    @Test
    void testReplaceMultipleParametersWithDefaults() {
        String query = "SELECT * FROM products WHERE price > ${min_price:0} AND category = '${category:All}'";
        Map<String, String> params = Map.of("min_price", "100");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE price > 100 AND category = 'All'", result);
    }
    
    @Test
    void testReplaceParameterWithDefaultContainingSpaces() {
        String query = "SELECT * FROM products WHERE category = '${category:Electronics}'";
        Map<String, String> params = new HashMap<>();
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE category = 'Electronics'", result);
    }
    
    @Test
    void testReplaceParameterWithDefaultContainingSpecialChars() {
        String query = "SELECT * FROM products WHERE name LIKE '${pattern:%test%}'";
        Map<String, String> params = new HashMap<>();
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE name LIKE '%test%'", result);
    }
    
    @Test
    void testReplaceSameParameterMultipleTimes() {
        String query = "SELECT * FROM products WHERE price > ${min_price} AND price < ${max_price} AND min_price = ${min_price}";
        Map<String, String> params = new HashMap<>();
        params.put("min_price", "100");
        params.put("max_price", "500");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE price > 100 AND price < 500 AND min_price = 100", result);
    }
    
    @Test
    void testReplaceParameterInStringLiteral() {
        String query = "SELECT * FROM products WHERE name = '${name}'";
        Map<String, String> params = Map.of("name", "Widget");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE name = 'Widget'", result);
    }
    
    @Test
    void testReplaceParameterWithSpecialRegexCharacters() {
        String query = "SELECT * FROM products WHERE name LIKE '${pattern}'";
        Map<String, String> params = Map.of("pattern", "Test$Value*");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE name LIKE 'Test$Value*'", result);
    }
    
    @Test
    void testMissingRequiredParameter() {
        String query = "SELECT * FROM products WHERE price > ${min_price}";
        Map<String, String> params = new HashMap<>();
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> QueryParameterReplacer.replaceParameters(query, params)
        );
        
        assertTrue(exception.getMessage().contains("Missing required parameters"));
        assertTrue(exception.getMessage().contains("min_price"));
    }
    
    @Test
    void testMissingMultipleRequiredParameters() {
        String query = "SELECT * FROM products WHERE price > ${min_price} AND category = '${category}'";
        Map<String, String> params = new HashMap<>();
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> QueryParameterReplacer.replaceParameters(query, params)
        );
        
        assertTrue(exception.getMessage().contains("Missing required parameters"));
        assertTrue(exception.getMessage().contains("min_price"));
        assertTrue(exception.getMessage().contains("category"));
    }
    
    @Test
    void testNoParametersInQuery() {
        String query = "SELECT * FROM products WHERE price > 100";
        Map<String, String> params = Map.of("min_price", "100");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE price > 100", result);
    }
    
    @Test
    void testEmptyQuery() {
        String query = "";
        Map<String, String> params = Map.of("min_price", "100");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("", result);
    }
    
    @Test
    void testNullQuery() {
        String query = null;
        Map<String, String> params = Map.of("min_price", "100");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertNull(result);
    }
    
    @Test
    void testNullParameters() {
        String query = "SELECT * FROM products WHERE price > ${min_price:50}";
        
        String result = QueryParameterReplacer.replaceParameters(query, null);
        
        assertEquals("SELECT * FROM products WHERE price > 50", result);
    }
    
    @Test
    void testExtractParameterNames() {
        String query = "SELECT * FROM products WHERE price > ${min_price} AND category = '${category}'";
        
        Set<String> paramNames = QueryParameterReplacer.extractParameterNames(query);
        
        assertEquals(2, paramNames.size());
        assertTrue(paramNames.contains("min_price"));
        assertTrue(paramNames.contains("category"));
    }
    
    @Test
    void testExtractParameterNamesWithDefaults() {
        String query = "SELECT * FROM products WHERE price > ${min_price:50} AND category = '${category:All}'";
        
        Set<String> paramNames = QueryParameterReplacer.extractParameterNames(query);
        
        assertEquals(2, paramNames.size());
        assertTrue(paramNames.contains("min_price"));
        assertTrue(paramNames.contains("category"));
    }
    
    @Test
    void testExtractParameterNamesNoParameters() {
        String query = "SELECT * FROM products WHERE price > 100";
        
        Set<String> paramNames = QueryParameterReplacer.extractParameterNames(query);
        
        assertTrue(paramNames.isEmpty());
    }
    
    @Test
    void testHasParameters() {
        String query = "SELECT * FROM products WHERE price > ${min_price}";
        
        assertTrue(QueryParameterReplacer.hasParameters(query));
    }
    
    @Test
    void testHasParametersFalse() {
        String query = "SELECT * FROM products WHERE price > 100";
        
        assertFalse(QueryParameterReplacer.hasParameters(query));
    }
    
    @Test
    void testComplexQueryWithParameters() {
        String query = "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE p.price > ${min_price} AND o.status = '${status}' ORDER BY p.price DESC LIMIT ${limit:10}";
        Map<String, String> params = new HashMap<>();
        params.put("min_price", "50");
        params.put("status", "completed");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE p.price > 50 AND o.status = 'completed' ORDER BY p.price DESC LIMIT 10", result);
    }
    
    @Test
    void testParameterWithUnderscore() {
        String query = "SELECT * FROM products WHERE price > ${min_price}";
        Map<String, String> params = Map.of("min_price", "100");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE price > 100", result);
    }
    
    @Test
    void testParameterNameWithNumbers() {
        String query = "SELECT * FROM products WHERE id = ${product_id}";
        Map<String, String> params = Map.of("product_id", "123");
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE id = 123", result);
    }
    
    @Test
    void testDefaultValueWithColon() {
        // This tests that the regex correctly handles the colon separator
        String query = "SELECT * FROM products WHERE time = '${time:12:00:00}'";
        Map<String, String> params = new HashMap<>();
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE time = '12:00:00'", result);
    }
    
    @Test
    void testEmptyDefaultValue() {
        String query = "SELECT * FROM products WHERE name = '${name:}'";
        Map<String, String> params = new HashMap<>();
        
        String result = QueryParameterReplacer.replaceParameters(query, params);
        
        assertEquals("SELECT * FROM products WHERE name = ''", result);
    }
}

