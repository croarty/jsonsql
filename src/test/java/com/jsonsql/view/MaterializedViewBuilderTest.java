package com.jsonsql.view;

import com.jsonsql.config.MappingManager;
import com.jsonsql.index.IndexManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class MaterializedViewBuilderTest {

    @TempDir
    File tempDir;

    private MappingManager mappingManager;
    private MaterializedViewManager viewManager;
    private MaterializedViewStore viewStore;
    private MaterializedViewBuilder builder;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.toPath().resolve("products.json"), """
            {"products":[
              {"id":1,"name":"Cheap","price":5},
              {"id":2,"name":"Mid","price":15},
              {"id":3,"name":"Pricey","price":50}
            ]}""");

        File mappingFile = new File(tempDir, "mappings.json");
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "products.json:$.products[*]");

        viewManager = new MaterializedViewManager(new File(tempDir, ".jsonsql-views.json"));
        viewStore = new MaterializedViewStore(tempDir);
        builder = new MaterializedViewBuilder(mappingManager, tempDir, viewManager, viewStore);
    }

    @Test
    void materializeExtractsCteBodyAndPersistsRows() throws Exception {
        String sql = """
            WITH expensive AS (SELECT id, name, price FROM products WHERE price > 10)
            SELECT * FROM expensive""";
        MaterializedViewDefinition def = builder.materialize("expensive", sql, null);

        assertEquals("SELECT id, name, price FROM products WHERE price > 10", def.getCteSql());
        assertEquals(2, def.getRowCount());
        assertNotNull(def.getSourceFingerprint());
        assertNotNull(def.getDataDirectory());
        assertTrue(viewManager.hasView("expensive"));
        assertNotNull(viewStore.load("expensive"));
    }

    @Test
    void rejectsDuplicateViewName() throws Exception {
        String sql = "WITH v AS (SELECT * FROM products) SELECT * FROM v";
        builder.materialize("v", sql, null);
        assertThrows(IllegalArgumentException.class,
            () -> builder.materialize("v", sql, null));
    }

    @Test
    void rejectsMappingCollision() {
        String sql = "WITH products AS (SELECT * FROM products) SELECT * FROM products";
        assertThrows(IllegalArgumentException.class,
            () -> builder.materialize("products", sql, null));
    }

    @Test
    void rejectsMissingCteInQuery() {
        assertThrows(Exception.class,
            () -> builder.materialize("nope", "SELECT * FROM products", null));
    }

    @Test
    void rebuildRefreshesDataAndFingerprint() throws Exception {
        String sql = "WITH expensive AS (SELECT * FROM products WHERE price > 10) SELECT * FROM expensive";
        builder.materialize("expensive", sql, null);
        String oldFp = viewManager.getView("expensive").getSourceFingerprint();

        Files.writeString(tempDir.toPath().resolve("products.json"), """
            {"products":[
              {"id":1,"name":"Cheap","price":5},
              {"id":2,"name":"Mid","price":15},
              {"id":3,"name":"Pricey","price":50},
              {"id":4,"name":"New","price":99}
            ]}""");

        MaterializedViewDefinition rebuilt = builder.rebuild("expensive", new IndexManager(
            new File(tempDir, ".jsonsql-indexes.json")));
        assertEquals(3, rebuilt.getRowCount());
        assertNotEquals(oldFp, rebuilt.getSourceFingerprint());
    }

    @Test
    void rebuildRemainsFresh() throws Exception {
        String sql = "WITH expensive AS (SELECT * FROM products WHERE price > 10) SELECT * FROM expensive";
        builder.materialize("expensive", sql, null);
        assertTrue(SourceFingerprint.isFresh(viewManager.getView("expensive"), mappingManager, tempDir));

        builder.rebuild("expensive", new IndexManager(new File(tempDir, ".jsonsql-indexes.json")));
        assertTrue(SourceFingerprint.isFresh(viewManager.getView("expensive"), mappingManager, tempDir));
    }
}
