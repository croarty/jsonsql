package com.jsonsql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JsonSqlCliMaterializedViewTest {

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Path mappingFile;
    private Path queriesFile;
    private Path viewsFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("products.json"), """
            {"products":[
              {"id":1,"category":"Tools","price":9.99},
              {"id":2,"category":"Electronics","price":29.99},
              {"id":3,"category":"Furniture","price":120.0}
            ]}""");
        mappingFile = tempDir.resolve("mappings.json");
        Files.writeString(mappingFile, "{\"products\": \"products.json:$.products[*]\"}");
        queriesFile = tempDir.resolve("queries.json");
        viewsFile = tempDir.resolve(".jsonsql-views.json");
    }

    private record CliResult(int exitCode, String out, String err) {}

    private CliResult runCli(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        try {
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new JsonSqlCli()).execute(args);
            return new CliResult(code,
                outBuf.toString(StandardCharsets.UTF_8),
                errBuf.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    private String[] withConfig(String... args) {
        String[] base = {
            "-d", dataDir.toString(),
            "-c", mappingFile.toString(),
            "--queries-file", queriesFile.toString(),
            "--views-file", viewsFile.toString()
        };
        String[] all = new String[base.length + args.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(args, 0, all, base.length, args.length);
        return all;
    }

    @Test
    void materializeViewCreatesDefinitionAndData() {
        CliResult r = runCli(withConfig(
            "--materialize-view", "expensive",
            "--query", "WITH expensive AS (SELECT * FROM products WHERE price > 10) SELECT * FROM expensive"));
        assertEquals(0, r.exitCode(), r.err());
        assertTrue(r.out().contains("Materialized view created: expensive"));
        assertTrue(Files.exists(viewsFile));
        assertTrue(Files.exists(dataDir.resolve(".jsonsql-views/expensive.json")));
    }

    @Test
    void queryUsesMaterializedViewWithoutWith() throws Exception {
        runCli(withConfig(
            "--materialize-view", "expensive",
            "--query", "WITH expensive AS (SELECT id, category FROM products WHERE price > 10) SELECT * FROM expensive"));
        CliResult r = runCli(withConfig("-q", "SELECT id, category FROM expensive"));
        assertEquals(0, r.exitCode(), r.err());
        JsonNode rows = objectMapper.readTree(r.out().trim());
        assertEquals(2, rows.size());
    }

    @Test
    void listAndShowViews() {
        runCli(withConfig(
            "--materialize-view", "expensive",
            "--query", "WITH expensive AS (SELECT * FROM products WHERE price > 10) SELECT * FROM expensive"));
        CliResult list = runCli(withConfig("--list-materialized-views"));
        assertEquals(0, list.exitCode());
        assertTrue(list.out().contains("expensive"));
        assertTrue(list.out().contains("FRESH"));
        assertTrue(list.out().contains("price > 10"));

        CliResult show = runCli(withConfig("--show-view", "expensive"));
        assertEquals(0, show.exitCode());
        assertTrue(show.out().contains("SQL:"));
        assertTrue(show.out().contains("rows=2") || show.out().contains("Rows: 2"));
    }

    @Test
    void dropViewRemovesDefinitionAndData() {
        runCli(withConfig(
            "--materialize-view", "expensive",
            "--query", "WITH expensive AS (SELECT * FROM products) SELECT * FROM expensive"));
        CliResult drop = runCli(withConfig("--drop-materialized-view", "expensive"));
        assertEquals(0, drop.exitCode());
        assertTrue(drop.out().contains("dropped"));
        assertFalse(Files.exists(dataDir.resolve(".jsonsql-views/expensive.json")));
    }

    @Test
    void rebuildViewRefreshesAfterDataChange() throws Exception {
        runCli(withConfig(
            "--materialize-view", "expensive",
            "--query", "WITH expensive AS (SELECT id FROM products WHERE price > 10) SELECT * FROM expensive"));
        Files.writeString(dataDir.resolve("products.json"), """
            {"products":[
              {"id":1,"category":"Tools","price":9.99},
              {"id":2,"category":"Electronics","price":29.99},
              {"id":3,"category":"Furniture","price":120.0},
              {"id":4,"category":"Office","price":200.0}
            ]}""");
        CliResult rebuild = runCli(withConfig("--rebuild-view", "expensive"));
        assertEquals(0, rebuild.exitCode(), rebuild.err());
        CliResult q = runCli(withConfig("-q", "SELECT id FROM expensive"));
        JsonNode rows = objectMapper.readTree(q.out().trim());
        assertEquals(3, rows.size());
    }

    @Test
    void materializeRejectsMappingCollision() {
        CliResult r = runCli(withConfig(
            "--materialize-view", "products",
            "--query", "WITH products AS (SELECT * FROM products) SELECT * FROM products"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().contains("mapping"));
    }

    @Test
    void freshnessIgnoresMismatchedCliDataDir() {
        runCli(withConfig(
            "--materialize-view", "expensive",
            "--query", "WITH expensive AS (SELECT * FROM products WHERE price > 10) SELECT * FROM expensive"));
        // List with parent tempDir as --data-dir, not the subdir used at build time
        CliResult list = runCli(
            "-d", tempDir.toString(),
            "-c", mappingFile.toString(),
            "--queries-file", queriesFile.toString(),
            "--views-file", viewsFile.toString(),
            "--list-materialized-views");
        assertEquals(0, list.exitCode(), list.err());
        assertTrue(list.out().contains("FRESH"), list.out());
        assertTrue(list.err().contains("Warning: --data-dir differs"));
    }

    @Test
    void addIndexOnMaterializedView() {
        runCli(withConfig(
            "--materialize-view", "expensive",
            "--query", "WITH expensive AS (SELECT id, category FROM products) SELECT * FROM expensive"));
        CliResult r = runCli(withConfig("--add-index", "expensive", "category"));
        assertEquals(0, r.exitCode(), r.err());
        assertTrue(r.out().contains("Index added: expensive.category"));
    }
}
