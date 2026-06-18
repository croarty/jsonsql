package com.jsonsql.query;

import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class DryRunTest {

    @TempDir
    File tempDir;

    private MappingManager mappingManager;
    private QueryExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.toPath().resolve("products.json"), """
            {"products": [{"id": 1, "name": "Widget"}]}
            """);
        Files.writeString(tempDir.toPath().resolve("orders.json"), """
            {"orders": [{"orderId": 1, "productId": 1}]}
            """);

        File configFile = new File(tempDir, "mappings.json");
        mappingManager = new MappingManager(configFile);
        mappingManager.addMapping("products", "products.json:$.products[*]");
        mappingManager.addMapping("orders", "orders.json:$.orders[*]");
        executor = new QueryExecutor(mappingManager, tempDir);
    }

    @Test
    void dryRunValidatesSimpleSelect() throws Exception {
        DryRunResult result = executor.dryRunValidate("SELECT name FROM products");

        assertNotNull(result.getParsedQuery());
        assertEquals(1, result.getResolvedTables().size());
        assertTrue(result.getResolvedTables().contains("products"));
    }

    @Test
    void dryRunValidatesJoin() throws Exception {
        DryRunResult result = executor.dryRunValidate(
            "SELECT p.name, o.orderId FROM orders o JOIN products p ON o.productId = p.id");

        assertEquals(2, result.getResolvedTables().size());
        assertTrue(result.getResolvedTables().contains("orders"));
        assertTrue(result.getResolvedTables().contains("products"));
    }

    @Test
    void dryRunValidatesCteWithoutResolvingCteName() throws Exception {
        DryRunResult result = executor.dryRunValidate("""
            WITH expensive AS (SELECT * FROM products WHERE price > 10)
            SELECT name FROM expensive
            """);

        assertTrue(result.getParsedQuery().hasCTEs());
        assertEquals(1, result.getResolvedTables().size());
        assertTrue(result.getResolvedTables().contains("products"));
    }

    @Test
    void dryRunRejectsInvalidSyntax() {
        assertThrows(QueryParseException.class,
            () -> executor.dryRunValidate("SELECT FROM products"));
    }

    @Test
    void dryRunRejectsMissingMapping() {
        Exception ex = assertThrows(IllegalArgumentException.class,
            () -> executor.dryRunValidate("SELECT * FROM missing"));
        assertTrue(ex.getMessage().toLowerCase().contains("no mapping or materialized view"));
    }

    @Test
    void dryRunRejectsMissingDataFile() throws Exception {
        mappingManager.addMapping("ghost", "ghost.json:$.items[*]");

        Exception ex = assertThrows(Exception.class,
            () -> executor.dryRunValidate("SELECT * FROM ghost"));
        assertTrue(ex.getMessage().contains("JSON file not found"));
    }

    @Test
    void dryRunReporterFormatsResolvedTables() throws Exception {
        DryRunResult result = executor.dryRunValidate(
            "SELECT p.name FROM products p JOIN orders o ON o.productId = p.id");

        String output = DryRunReporter.format(result, mappingManager);

        assertTrue(output.contains("Dry run OK"));
        assertTrue(output.contains("SQL parsed successfully"));
        assertTrue(output.contains("products -> products.json:$.products[*]"));
        assertTrue(output.contains("orders -> orders.json:$.orders[*]"));
    }

    @Test
    void dryRunResolvesMaterializedView() throws Exception {
        com.jsonsql.view.MaterializedViewManager viewManager =
            new com.jsonsql.view.MaterializedViewManager(new File(tempDir, ".jsonsql-views.json"));
        com.jsonsql.view.MaterializedViewStore viewStore =
            new com.jsonsql.view.MaterializedViewStore(tempDir);
        com.jsonsql.view.MaterializedViewBuilder builder = new com.jsonsql.view.MaterializedViewBuilder(
            mappingManager, tempDir, viewManager, viewStore);
        builder.materialize("expensive",
            "WITH expensive AS (SELECT * FROM products WHERE price > 10) SELECT * FROM expensive", null);

        QueryExecutor mvExecutor = new QueryExecutor(mappingManager, tempDir, null, null, viewManager);
        DryRunResult result = mvExecutor.dryRunValidate("SELECT name FROM expensive");
        assertTrue(result.getResolvedTables().contains("expensive"));
    }
}
