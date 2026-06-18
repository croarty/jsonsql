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

/**
 * CLI tests for the declared-index commands (add/drop/list/rebuild/no-index).
 */
class JsonSqlCliIndexTest {

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Path mappingFile;
    private Path queriesFile;
    private Path indexesFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir.resolve("products-multi/2023"));
        Files.createDirectories(dataDir.resolve("products-multi/2024"));
        Files.writeString(dataDir.resolve("products-multi/2023/p.json"), """
            {"products":[
              {"id":1,"category":"Tools","price":9.99},
              {"id":2,"category":"Electronics","price":29.99}
            ]}""");
        Files.writeString(dataDir.resolve("products-multi/2024/p.json"), """
            {"products":[
              {"id":3,"category":"Furniture","price":50.0},
              {"id":4,"category":"Office","price":120.0}
            ]}""");
        mappingFile = tempDir.resolve("mappings.json");
        Files.writeString(mappingFile, "{\"products\": \"products-multi:$.products[*]\"}");
        queriesFile = tempDir.resolve("queries.json");
        indexesFile = tempDir.resolve("indexes.json");
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
            "--indexes-file", indexesFile.toString()
        };
        String[] all = new String[base.length + args.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(args, 0, all, base.length, args.length);
        return all;
    }

    @Test
    void addIndexBuildsAndPersists() {
        CliResult r = runCli(withConfig("--add-index", "products", "category"));
        assertEquals(0, r.exitCode(), r.err());
        assertTrue(r.out().contains("Index added: products.category"));
        assertTrue(Files.exists(indexesFile), "definitions file should exist");
        assertTrue(Files.exists(dataDir.resolve(".jsonsql-index")), "index data dir should exist");
    }

    @Test
    void addIndexForUnmappedTableFails() {
        CliResult r = runCli(withConfig("--add-index", "nope", "category"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("no mapping or materialized view"));
    }

    @Test
    void listIndexesShowsFreshStatus() {
        runCli(withConfig("--add-index", "products", "category"));
        CliResult r = runCli(withConfig("--list-indexes"));
        assertEquals(0, r.exitCode());
        assertTrue(r.out().contains("products.category"));
        assertTrue(r.out().contains("FRESH"), r.out());
    }

    @Test
    void listIndexesGoesStaleAfterDataChange() throws Exception {
        runCli(withConfig("--add-index", "products", "category"));
        // Mutate a backing file so fingerprints no longer match.
        Files.writeString(dataDir.resolve("products-multi/2024/p.json"), """
            {"products":[{"id":9,"category":"Tools","price":1.0}]}""");
        CliResult r = runCli(withConfig("--list-indexes"));
        assertTrue(r.out().contains("STALE"), r.out());
    }

    @Test
    void dropIndexRemovesDefinition() {
        runCli(withConfig("--add-index", "products", "category"));
        CliResult drop = runCli(withConfig("--drop-index", "products", "category"));
        assertEquals(0, drop.exitCode());
        assertTrue(drop.out().contains("Index dropped"));
        CliResult list = runCli(withConfig("--list-indexes"));
        assertTrue(list.out().contains("No declared indexes"));
    }

    @Test
    void rebuildIndexesRebuildsAll() {
        runCli(withConfig("--add-index", "products", "category"));
        runCli(withConfig("--add-index", "products", "price"));
        CliResult r = runCli(withConfig("--rebuild-indexes"));
        assertEquals(0, r.exitCode());
        assertTrue(r.out().contains("Rebuilt 2 index(es)"), r.out());
    }

    @Test
    void queryUsesIndexAndReturnsCorrectRows() throws Exception {
        runCli(withConfig("--add-index", "products", "category"));
        CliResult r = runCli(withConfig("-q", "SELECT id, category FROM products WHERE category = 'Furniture'"));
        assertEquals(0, r.exitCode(), r.err());
        JsonNode rows = objectMapper.readTree(r.out().trim());
        assertEquals(1, rows.size());
        assertEquals(3, rows.get(0).get("id").asInt());
    }

    @Test
    void noIndexFlagStillReturnsCorrectRows() throws Exception {
        runCli(withConfig("--add-index", "products", "category"));
        CliResult r = runCli(withConfig("--no-index", "-q",
            "SELECT id FROM products WHERE category = 'Furniture'"));
        assertEquals(0, r.exitCode(), r.err());
        JsonNode rows = objectMapper.readTree(r.out().trim());
        assertEquals(1, rows.size());
    }

    @Test
    void addIndexCombinedWithQueryIsRejected() {
        CliResult r = runCli(withConfig("--add-index", "products", "category",
            "-q", "SELECT * FROM products"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("multiple actions"));
    }
}
