package com.jsonsql.view;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MaterializedViewManagerTest {

    @TempDir
    File tempDir;

    private File viewsFile() {
        return new File(tempDir, ".jsonsql-views.json");
    }

    @Test
    void putAndPersistView() {
        MaterializedViewManager manager = new MaterializedViewManager(viewsFile());
        MaterializedViewDefinition def = new MaterializedViewDefinition();
        def.setName("expensive");
        def.setCteSql("SELECT * FROM products WHERE price > 10");
        def.setSourceFingerprint("fp");
        def.setBuiltAt("2026-01-01T00:00:00Z");
        def.setRowCount(3);
        manager.putView(def);

        assertTrue(manager.hasView("expensive"));
        assertEquals(3, manager.getView("expensive").getRowCount());
        assertTrue(viewsFile().exists());
    }

    @Test
    void persistsAcrossInstances() {
        MaterializedViewManager first = new MaterializedViewManager(viewsFile());
        MaterializedViewDefinition def = new MaterializedViewDefinition();
        def.setName("summary");
        def.setCteSql("SELECT category, COUNT(*) AS n FROM products GROUP BY category");
        def.setRowCount(2);
        first.putView(def);

        MaterializedViewManager second = new MaterializedViewManager(viewsFile());
        assertEquals(1, second.size());
        assertEquals("summary", second.getView("summary").getName());
    }

    @Test
    void dropRemovesView() {
        MaterializedViewManager manager = new MaterializedViewManager(viewsFile());
        MaterializedViewDefinition def = new MaterializedViewDefinition();
        def.setName("tmp");
        def.setCteSql("SELECT 1");
        manager.putView(def);

        assertTrue(manager.dropView("tmp"));
        assertFalse(manager.hasView("tmp"));
        assertFalse(manager.dropView("tmp"));
    }

    @Test
    void getAllViewsPreservesOrder() {
        MaterializedViewManager manager = new MaterializedViewManager(viewsFile());
        for (String name : List.of("a", "b", "c")) {
            MaterializedViewDefinition def = new MaterializedViewDefinition();
            def.setName(name);
            def.setCteSql("SELECT * FROM " + name);
            manager.putView(def);
        }
        List<MaterializedViewDefinition> all = manager.getAllViews();
        assertEquals(List.of("a", "b", "c"),
            all.stream().map(MaterializedViewDefinition::getName).toList());
    }

    @Test
    void rejectsEmptyName() {
        MaterializedViewManager manager = new MaterializedViewManager(viewsFile());
        MaterializedViewDefinition def = new MaterializedViewDefinition();
        def.setName("  ");
        assertThrows(IllegalArgumentException.class, () -> manager.putView(def));
    }
}
