package com.jsonsql.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for QueryManager class.
 */
class QueryManagerTest {
    
    private File testConfigFile;
    private QueryManager queryManager;
    
    @BeforeEach
    void setUp() throws IOException {
        testConfigFile = Files.createTempFile("test-queries", ".json").toFile();
        queryManager = new QueryManager(testConfigFile);
    }
    
    @AfterEach
    void tearDown() {
        if (testConfigFile.exists()) {
            testConfigFile.delete();
        }
    }
    
    @Test
    void testSaveQuery() throws IOException {
        queryManager.saveQuery("simple", "SELECT * FROM products");
        
        assertTrue(queryManager.hasQuery("simple"));
        assertEquals("SELECT * FROM products", queryManager.getQuery("simple"));
    }
    
    @Test
    void testSaveMultipleQueries() throws IOException {
        queryManager.saveQuery("all_products", "SELECT * FROM products");
        queryManager.saveQuery("expensive", "SELECT * FROM products WHERE price > 100");
        queryManager.saveQuery("electronics", "SELECT * FROM products WHERE category = 'Electronics'");
        
        assertEquals(3, queryManager.getQueryCount());
        assertTrue(queryManager.hasQuery("all_products"));
        assertTrue(queryManager.hasQuery("expensive"));
        assertTrue(queryManager.hasQuery("electronics"));
    }
    
    @Test
    void testSaveComplexQuery() throws IOException {
        String complexSql = "SELECT p.name, o.quantity FROM orders o JOIN products p ON o.productId = p.id WHERE o.status IN ('completed', 'shipped') ORDER BY o.quantity DESC";
        queryManager.saveQuery("completed_orders", complexSql);
        
        assertEquals(complexSql, queryManager.getQuery("completed_orders"));
    }
    
    @Test
    void testOverwriteQuery() throws IOException {
        queryManager.saveQuery("test", "SELECT * FROM products");
        assertEquals("SELECT * FROM products", queryManager.getQuery("test"));
        
        queryManager.saveQuery("test", "SELECT * FROM orders");
        assertEquals("SELECT * FROM orders", queryManager.getQuery("test"));
        assertEquals(1, queryManager.getQueryCount());
    }
    
    @Test
    void testDeleteQuery() throws IOException {
        queryManager.saveQuery("to_delete", "SELECT * FROM products");
        assertTrue(queryManager.hasQuery("to_delete"));
        
        queryManager.deleteQuery("to_delete");
        assertFalse(queryManager.hasQuery("to_delete"));
        assertNull(queryManager.getQuery("to_delete"));
    }
    
    @Test
    void testDeleteNonExistentQuery() {
        assertThrows(IllegalArgumentException.class, () -> {
            queryManager.deleteQuery("non_existent");
        });
    }
    
    @Test
    void testGetNonExistentQuery() {
        assertNull(queryManager.getQuery("non_existent"));
        assertFalse(queryManager.hasQuery("non_existent"));
    }
    
    @Test
    void testGetAllQueries() throws IOException {
        queryManager.saveQuery("q1", "SELECT * FROM products");
        queryManager.saveQuery("q2", "SELECT * FROM orders");
        queryManager.saveQuery("q3", "SELECT * FROM customers");
        
        Map<String, String> allQueries = queryManager.getAllQueries();
        
        assertEquals(3, allQueries.size());
        assertTrue(allQueries.containsKey("q1"));
        assertTrue(allQueries.containsKey("q2"));
        assertTrue(allQueries.containsKey("q3"));
    }
    
    @Test
    void testPersistence() throws IOException {
        queryManager.saveQuery("persistent", "SELECT * FROM products WHERE price > 50");
        
        // Create new instance with same file
        QueryManager newManager = new QueryManager(testConfigFile);
        
        assertTrue(newManager.hasQuery("persistent"));
        assertEquals("SELECT * FROM products WHERE price > 50", newManager.getQuery("persistent"));
    }
    
    @Test
    void testEmptyName() {
        assertThrows(IllegalArgumentException.class, () -> {
            queryManager.saveQuery("", "SELECT * FROM products");
        });
    }
    
    @Test
    void testNullName() {
        assertThrows(IllegalArgumentException.class, () -> {
            queryManager.saveQuery(null, "SELECT * FROM products");
        });
    }
    
    @Test
    void testEmptyQuery() {
        assertThrows(IllegalArgumentException.class, () -> {
            queryManager.saveQuery("test", "");
        });
    }
    
    @Test
    void testNullQuery() {
        assertThrows(IllegalArgumentException.class, () -> {
            queryManager.saveQuery("test", null);
        });
    }
    
    @Test
    void testWhitespaceName() {
        assertThrows(IllegalArgumentException.class, () -> {
            queryManager.saveQuery("   ", "SELECT * FROM products");
        });
    }
    
    @Test
    void testWhitespaceQuery() {
        assertThrows(IllegalArgumentException.class, () -> {
            queryManager.saveQuery("test", "   ");
        });
    }
    
    @Test
    void testQueryCountWithEmpty() {
        assertEquals(0, queryManager.getQueryCount());
    }
    
    @Test
    void testGetAllQueriesWhenEmpty() {
        Map<String, String> queries = queryManager.getAllQueries();
        
        assertNotNull(queries);
        assertEquals(0, queries.size());
    }
    
    @Test
    void testSpecialCharactersInName() throws IOException {
        queryManager.saveQuery("my-query_v2", "SELECT * FROM products");
        assertTrue(queryManager.hasQuery("my-query_v2"));
    }
    
    @Test
    void testQueryWithSpecialCharacters() throws IOException {
        String sql = "SELECT * FROM products WHERE name LIKE '%L''Oreal%'";
        queryManager.saveQuery("special", sql);
        
        assertEquals(sql, queryManager.getQuery("special"));
    }

    @Test
    void testCorruptStoreSurfacesError() throws IOException {
        // A corrupt (non-JSON) store must surface an error rather than silently resetting,
        // which would otherwise let the next save permanently overwrite user data.
        Files.writeString(testConfigFile.toPath(), "{ this is not valid json ]");

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> new QueryManager(testConfigFile));
        assertTrue(ex.getMessage().toLowerCase().contains("saved queries")
            || ex.getMessage().toLowerCase().contains("corrupt"));
    }

    @Test
    void testStructurallyInvalidStoreSurfacesError() throws IOException {
        // A JSON array is not a String->String map and must be rejected at load time.
        Files.writeString(testConfigFile.toPath(), "[1, 2, 3]");

        assertThrows(RuntimeException.class, () -> new QueryManager(testConfigFile));
    }

    @Test
    void testSaveLeavesNoTempFiles() throws IOException {
        queryManager.saveQuery("q1", "SELECT * FROM products");
        queryManager.saveQuery("q2", "SELECT * FROM orders");

        File parent = testConfigFile.getAbsoluteFile().getParentFile();
        File[] tmpFiles = parent.listFiles((d, n) ->
            n.startsWith(testConfigFile.getName()) && n.endsWith(".tmp"));
        assertTrue(tmpFiles == null || tmpFiles.length == 0,
            "Atomic write should not leave temp files behind");
    }

    @Test
    void testAtomicWritePreservesValidFileOnReload() throws IOException {
        queryManager.saveQuery("q1", "SELECT 1");
        queryManager.saveQuery("q2", "SELECT 2");

        QueryManager reloaded = new QueryManager(testConfigFile);
        assertEquals("SELECT 1", reloaded.getQuery("q1"));
        assertEquals("SELECT 2", reloaded.getQuery("q2"));
    }
    
    @Test
    void testMultipleDeletesAndAdds() throws IOException {
        queryManager.saveQuery("q1", "SELECT * FROM t1");
        queryManager.saveQuery("q2", "SELECT * FROM t2");
        queryManager.saveQuery("q3", "SELECT * FROM t3");
        
        queryManager.deleteQuery("q2");
        assertEquals(2, queryManager.getQueryCount());
        
        queryManager.saveQuery("q4", "SELECT * FROM t4");
        assertEquals(3, queryManager.getQueryCount());
        
        assertTrue(queryManager.hasQuery("q1"));
        assertFalse(queryManager.hasQuery("q2"));
        assertTrue(queryManager.hasQuery("q3"));
        assertTrue(queryManager.hasQuery("q4"));
    }
}

