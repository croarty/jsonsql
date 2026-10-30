package com.jsonsql.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A rebuild replaces a view only once the new version is ready, so a failure keeps the old one.
 */
class MaterializedViewRebuildTest {

    private static final String SQL = "WITH v AS (SELECT id FROM t) SELECT * FROM v";

    @TempDir
    File tempDir;

    private File dataFile;
    private MaterializedViewManager viewManager;
    private MaterializedViewStore viewStore;
    private MaterializedViewBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        dataFile = new File(tempDir, "t.json");
        Files.writeString(dataFile.toPath(), "{\"rows\": [{\"id\": 1}, {\"id\": 2}]}");

        MappingManager mappings = new MappingManager(new File(tempDir, "mappings.json"));
        mappings.addMapping("t", "t.json:$.rows[*]");
        viewManager = new MaterializedViewManager(new File(tempDir, ".jsonsql-views.json"));
        viewStore = new MaterializedViewStore(tempDir);
        builder = new MaterializedViewBuilder(mappings, tempDir, viewManager, viewStore);
        builder.materialize("v", SQL, null);
    }

    @Test
    void rebuildPicksUpChangedData() throws Exception {
        Files.writeString(dataFile.toPath(), "{\"rows\": [{\"id\": 9}]}");

        MaterializedViewDefinition rebuilt = builder.rebuild("v", null);

        assertEquals(1, rebuilt.getRowCount());
        assertEquals(1, viewManager.getView("v").getRowCount());
        assertEquals(9, viewStore.load("v").get(0).get("id").asInt());
    }

    @Test
    void failedRebuildKeepsTheDefinition() throws Exception {
        MaterializedViewDefinition before = viewManager.getView("v");
        Files.writeString(dataFile.toPath(), "{\"rows\": [{\"id\": 9}");

        assertThrows(java.io.IOException.class, () -> builder.rebuild("v", null));

        assertTrue(viewManager.hasView("v"));
        assertEquals(before.getCteSql(), viewManager.getView("v").getCteSql());
        assertEquals(before.getBuiltAt(), viewManager.getView("v").getBuiltAt());
    }

    @Test
    void failedRebuildKeepsTheStoredRows() throws Exception {
        Files.writeString(dataFile.toPath(), "{\"rows\": [{\"id\": 9}");

        assertThrows(java.io.IOException.class, () -> builder.rebuild("v", null));

        List<JsonNode> rows = viewStore.load("v");
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).get("id").asInt());
    }

    @Test
    void failedRebuildSurvivesReloadingFromDisk() throws Exception {
        Files.delete(dataFile.toPath());

        assertThrows(java.io.IOException.class, () -> builder.rebuild("v", null));

        MaterializedViewManager reloaded = new MaterializedViewManager(new File(tempDir, ".jsonsql-views.json"));
        assertTrue(reloaded.hasView("v"));
    }

    @Test
    void aFixedSourceCanBeRebuiltAfterAFailure() throws Exception {
        Files.writeString(dataFile.toPath(), "{\"rows\": [{\"id\": 9}");
        assertThrows(java.io.IOException.class, () -> builder.rebuild("v", null));

        Files.writeString(dataFile.toPath(), "{\"rows\": [{\"id\": 3}]}");
        builder.rebuild("v", null);

        assertEquals(3, viewStore.load("v").get(0).get("id").asInt());
    }

    @Test
    void rebuildingAnUnknownViewFails() {
        assertThrows(IllegalArgumentException.class, () -> builder.rebuild("nope", null));
    }

    @Test
    void creatingAViewThatExistsStillFails() {
        assertThrows(IllegalArgumentException.class, () -> builder.materialize("v", SQL, null));
    }
}
