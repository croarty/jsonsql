package com.jsonsql.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MappingManagerTest {

    @TempDir
    Path tempDir;

    private File configFile;
    private MappingManager mappingManager;

    @BeforeEach
    void setUp() {
        configFile = tempDir.resolve("test-mappings.json").toFile();
        mappingManager = new MappingManager(configFile);
    }

    @Test
    void testAddMapping() {
        mappingManager.addMapping("products", "$.data.products");
        
        assertTrue(mappingManager.hasMapping("products"));
        assertEquals("$.data.products", mappingManager.getJsonPath("products"));
    }

    @Test
    void testAddMultipleMappings() {
        mappingManager.addMapping("products", "$.data.products");
        mappingManager.addMapping("orders", "$.orders");
        mappingManager.addMapping("customers", "$.users.customers");
        
        Map<String, String> allMappings = mappingManager.getAllMappings();
        assertEquals(3, allMappings.size());
        assertEquals("$.data.products", allMappings.get("products"));
        assertEquals("$.orders", allMappings.get("orders"));
        assertEquals("$.users.customers", allMappings.get("customers"));
    }

    @Test
    void testUpdateMapping() {
        mappingManager.addMapping("products", "$.data.products");
        assertEquals("$.data.products", mappingManager.getJsonPath("products"));
        
        mappingManager.addMapping("products", "$.new.path.products");
        assertEquals("$.new.path.products", mappingManager.getJsonPath("products"));
    }

    @Test
    void testPersistence() {
        mappingManager.addMapping("products", "$.data.products");
        mappingManager.addMapping("orders", "$.orders");
        
        // Create new instance with same file
        MappingManager newManager = new MappingManager(configFile);
        
        assertTrue(newManager.hasMapping("products"));
        assertTrue(newManager.hasMapping("orders"));
        assertEquals("$.data.products", newManager.getJsonPath("products"));
        assertEquals("$.orders", newManager.getJsonPath("orders"));
    }

    @Test
    void testNonExistentMapping() {
        assertFalse(mappingManager.hasMapping("nonexistent"));
        assertNull(mappingManager.getJsonPath("nonexistent"));
    }

    @Test
    void testEmptyMappings() {
        Map<String, String> allMappings = mappingManager.getAllMappings();
        assertTrue(allMappings.isEmpty());
    }

    @Test
    void testAddMappingWithEmptyAlias() {
        assertThrows(IllegalArgumentException.class, () -> 
            mappingManager.addMapping("", "$.path")
        );
    }

    @Test
    void testAddMappingWithNullAlias() {
        assertThrows(IllegalArgumentException.class, () -> 
            mappingManager.addMapping(null, "$.path")
        );
    }

    @Test
    void testAddMappingWithEmptyJsonPath() {
        assertThrows(IllegalArgumentException.class, () -> 
            mappingManager.addMapping("alias", "")
        );
    }

    @Test
    void testAddMappingWithNullJsonPath() {
        assertThrows(IllegalArgumentException.class, () -> 
            mappingManager.addMapping("alias", null)
        );
    }

    @Test
    void testMappingWithFilename() {
        mappingManager.addMapping("products", "ecommerce.json:$.store.data.products");
        
        assertTrue(mappingManager.hasMapping("products"));
        assertEquals("ecommerce.json:$.store.data.products", mappingManager.getJsonPath("products"));
    }

    @Test
    void testGetFileName() {
        mappingManager.addMapping("products", "ecommerce.json:$.store.data.products");
        mappingManager.addMapping("orders", "$.orders");
        
        // Should return filename when format is "filename:jsonpath"
        assertEquals("ecommerce.json", mappingManager.getFileName("products"));
        
        // Should return null when old format (just jsonpath)
        assertNull(mappingManager.getFileName("orders"));
        
        // Should return null for non-existent mapping
        assertNull(mappingManager.getFileName("nonexistent"));
    }

    @Test
    void testGetJsonPathOnly() {
        mappingManager.addMapping("products", "ecommerce.json:$.store.data.products");
        mappingManager.addMapping("orders", "$.orders");
        
        // Should return just the JSONPath part when filename is specified
        assertEquals("$.store.data.products", mappingManager.getJsonPathOnly("products"));
        
        // Should return full string when old format
        assertEquals("$.orders", mappingManager.getJsonPathOnly("orders"));
        
        // Should return null for non-existent mapping
        assertNull(mappingManager.getJsonPathOnly("nonexistent"));
    }

    @Test
    void testMultipleMappingsFromSameFile() {
        mappingManager.addMapping("products", "ecommerce.json:$.store.data.products");
        mappingManager.addMapping("orders", "ecommerce.json:$.store.data.orders");
        
        assertEquals("ecommerce.json", mappingManager.getFileName("products"));
        assertEquals("ecommerce.json", mappingManager.getFileName("orders"));
        
        assertEquals("$.store.data.products", mappingManager.getJsonPathOnly("products"));
        assertEquals("$.store.data.orders", mappingManager.getJsonPathOnly("orders"));
    }

    @Test
    void testMappingWithColonInJsonPath() {
        // Test edge case: JSONPath starting with $ should not be treated as filename format
        mappingManager.addMapping("test", "$.path:with:colons");
        
        // Should treat entire string as JSONPath (no filename)
        assertNull(mappingManager.getFileName("test"));
        assertEquals("$.path:with:colons", mappingManager.getJsonPathOnly("test"));
    }

    @Test
    void testPersistenceWithFilename() {
        mappingManager.addMapping("products", "ecommerce.json:$.store.data.products");
        mappingManager.addMapping("orders", "data.json:$.orders");
        
        // Create new instance with same file
        MappingManager newManager = new MappingManager(configFile);
        
        assertEquals("ecommerce.json", newManager.getFileName("products"));
        assertEquals("data.json", newManager.getFileName("orders"));
        assertEquals("$.store.data.products", newManager.getJsonPathOnly("products"));
        assertEquals("$.orders", newManager.getJsonPathOnly("orders"));
    }
}

