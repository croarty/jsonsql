package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import com.jsonsql.view.MaterializedViewBuilder;
import com.jsonsql.view.MaterializedViewManager;
import com.jsonsql.view.MaterializedViewStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class QueryExecutorMaterializedViewTest {

    @TempDir
    File tempDir;

    private MappingManager mappingManager;
    private MaterializedViewManager viewManager;
    private QueryExecutor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.toPath().resolve("products.json"), """
            {"products":[
              {"id":1,"name":"Cheap","price":5,"category":"Tools"},
              {"id":2,"name":"Mid","price":15,"category":"Tools"},
              {"id":3,"name":"Pricey","price":50,"category":"Electronics"}
            ]}""");
        Files.writeString(tempDir.toPath().resolve("orders.json"), """
            {"orders":[
              {"orderId":10,"productId":2,"qty":1},
              {"orderId":11,"productId":3,"qty":2}
            ]}""");

        File mappingFile = new File(tempDir, "mappings.json");
        mappingManager = new MappingManager(mappingFile);
        mappingManager.addMapping("products", "products.json:$.products[*]");
        mappingManager.addMapping("orders", "orders.json:$.orders[*]");

        viewManager = new MaterializedViewManager(new File(tempDir, ".jsonsql-views.json"));
        MaterializedViewStore viewStore = new MaterializedViewStore(tempDir);
        MaterializedViewBuilder builder = new MaterializedViewBuilder(
            mappingManager, tempDir, viewManager, viewStore);
        builder.materialize("expensive",
            "WITH expensive AS (SELECT id, name, price, category FROM products WHERE price > 10) SELECT * FROM expensive",
            null);

        executor = new QueryExecutor(mappingManager, tempDir, null, null, viewManager);
    }

    @Test
    void queryWithoutWithUsesMaterializedView() throws Exception {
        String json = executor.execute("SELECT id, name FROM expensive WHERE category = 'Tools'");
        JsonNode rows = objectMapper.readTree(json);
        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).get("id").asInt());
        assertEquals("Mid", rows.get(0).get("name").asText());
    }

    @Test
    void joinAgainstMaterializedView() throws Exception {
        String json = executor.execute(
            "SELECT o.orderId, e.name FROM orders o JOIN expensive e ON o.productId = e.id");
        JsonNode rows = objectMapper.readTree(json);
        assertEquals(2, rows.size());
    }

    @Test
    void dryRunResolvesMaterializedView() throws Exception {
        DryRunResult result = executor.dryRunValidate("SELECT * FROM expensive");
        assertTrue(result.getResolvedTables().contains("expensive"));
    }

    @Test
    void staleViewWarnsButStillServesData() throws Exception {
        Files.writeString(tempDir.toPath().resolve("products.json"), """
            {"products":[{"id":99,"name":"Changed","price":999,"category":"X"}]}""");

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        try {
            System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
            String json = executor.execute("SELECT id FROM expensive");
            assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("STALE"));
            JsonNode rows = objectMapper.readTree(json);
            // Still serves stored rows (2), not recomputed from changed source
            assertEquals(2, rows.size());
            assertEquals(2, rows.get(0).get("id").asInt());
        } finally {
            System.setErr(origErr);
        }
    }

    @Test
    void inlineCteTakesPrecedenceOverMaterializedView() throws Exception {
        String json = executor.execute("""
            WITH expensive AS (SELECT id FROM products WHERE id = 1)
            SELECT id FROM expensive""");
        JsonNode rows = objectMapper.readTree(json);
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get("id").asInt());
    }
}
