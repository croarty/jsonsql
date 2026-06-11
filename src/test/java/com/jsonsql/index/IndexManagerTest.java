package com.jsonsql.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IndexManager} declared-index definition storage.
 */
class IndexManagerTest {

    @TempDir
    File tempDir;

    private File defsFile() {
        return new File(tempDir, ".jsonsql-indexes.json");
    }

    @Test
    void addsAndPersistsDefinition() {
        IndexManager manager = new IndexManager(defsFile());
        assertTrue(manager.addIndex("products", "category"));
        assertTrue(manager.hasIndex("products", "category"));
        assertTrue(defsFile().exists(), "definitions file should be written");
    }

    @Test
    void addReturnsFalseForDuplicate() {
        IndexManager manager = new IndexManager(defsFile());
        assertTrue(manager.addIndex("products", "category"));
        assertFalse(manager.addIndex("products", "category"), "duplicate add should report not-added");
        assertEquals(1, manager.size());
    }

    @Test
    void persistsAcrossInstances() {
        IndexManager first = new IndexManager(defsFile());
        first.addIndex("products", "category");
        first.addIndex("products", "reviews.rating");

        IndexManager second = new IndexManager(defsFile());
        assertEquals(2, second.size());
        assertTrue(second.hasIndex("products", "category"));
        assertTrue(second.hasIndex("products", "reviews.rating"));
    }

    @Test
    void dottedFieldKeysDoNotCollide() {
        IndexManager manager = new IndexManager(defsFile());
        manager.addIndex("products", "specifications.material");
        manager.addIndex("products", "specifications.weight");
        assertEquals(2, manager.size());
        assertTrue(manager.hasIndex("products", "specifications.material"));
        assertTrue(manager.hasIndex("products", "specifications.weight"));
    }

    @Test
    void dropRemovesDefinition() {
        IndexManager manager = new IndexManager(defsFile());
        manager.addIndex("products", "category");
        assertTrue(manager.dropIndex("products", "category"));
        assertFalse(manager.hasIndex("products", "category"));
        assertFalse(manager.dropIndex("products", "category"), "second drop should report nothing removed");
    }

    @Test
    void getIndexesForTableFiltersByTable() {
        IndexManager manager = new IndexManager(defsFile());
        manager.addIndex("products", "category");
        manager.addIndex("products", "price");
        manager.addIndex("orders", "region");

        List<IndexDefinition> productIndexes = manager.getIndexesForTable("products");
        assertEquals(2, productIndexes.size());
        assertTrue(manager.hasIndexesForTable("products"));
        assertTrue(manager.hasIndexesForTable("orders"));
        assertFalse(manager.hasIndexesForTable("customers"));
    }

    @Test
    void rejectsBlankInput() {
        IndexManager manager = new IndexManager(defsFile());
        assertThrows(IllegalArgumentException.class, () -> manager.addIndex("", "category"));
        assertThrows(IllegalArgumentException.class, () -> manager.addIndex("products", " "));
    }
}
