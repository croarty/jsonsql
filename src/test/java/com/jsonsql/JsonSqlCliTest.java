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
 * Comprehensive tests for the CLI dispatch behavior: mutually-exclusive actions,
 * stdout/stderr separation (pipe-friendliness), error handling and validation.
 */
class JsonSqlCliTest {

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Path mappingFile;
    private Path queriesFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("products.json"), """
            {"products": [{"id": 1, "name": "Widget"}, {"id": 2, "name": "Gadget"}]}
            """);
        mappingFile = tempDir.resolve("mappings.json");
        Files.writeString(mappingFile, "{\"products\": \"products.json:$.products\"}");
        queriesFile = tempDir.resolve("queries.json");
    }

    /** Result of a CLI invocation. */
    private record CliResult(int exitCode, String out, String err) {}

    private CliResult runCli(String... extraArgs) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        try {
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new JsonSqlCli()).execute(extraArgs);
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
            "--queries-file", queriesFile.toString()
        };
        String[] all = new String[base.length + args.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(args, 0, all, base.length, args.length);
        return all;
    }

    @Test
    void testQueryOutputsJsonToStdout() throws Exception {
        CliResult r = runCli(withConfig("-q", "SELECT * FROM products"));
        assertEquals(0, r.exitCode());
        JsonNode parsed = objectMapper.readTree(r.out().trim());
        assertTrue(parsed.isArray());
        assertEquals(2, parsed.size());
    }

    @Test
    void testRunQueryKeepsStdoutClean() throws Exception {
        // Save then run a query; stdout must contain ONLY the JSON result (pipe-friendly),
        // while informational messages go to stderr.
        CliResult save = runCli(withConfig("--save-query", "all", "-q", "SELECT * FROM products"));
        assertEquals(0, save.exitCode());

        CliResult run = runCli(withConfig("--run-query", "all"));
        assertEquals(0, run.exitCode());

        // stdout should parse cleanly as a JSON array
        JsonNode parsed = objectMapper.readTree(run.out().trim());
        assertTrue(parsed.isArray());
        assertEquals(2, parsed.size());

        // Informational messages must be on stderr, not stdout
        assertFalse(run.out().contains("Running saved query"));
        assertTrue(run.err().contains("Running saved query"));
    }

    @Test
    void testMutuallyExclusiveActionsRejected() {
        CliResult r = runCli(withConfig("--list-tables", "-q", "SELECT * FROM products"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("multiple actions"));
    }

    @Test
    void testRunQueryWithInlineQueryRejected() {
        CliResult r = runCli(withConfig("--run-query", "foo", "-q", "SELECT * FROM products"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("cannot combine"));
    }

    @Test
    void testNoActionShowsHelp() {
        CliResult r = runCli(withConfig());
        assertEquals(1, r.exitCode());
        assertTrue(r.err().contains("No action specified"));
        // Help should enumerate the available actions
        assertTrue(r.err().contains("--query"));
        assertTrue(r.err().contains("--list-tables"));
    }

    @Test
    void testInvalidDataDirRejected() {
        Path missing = tempDir.resolve("does-not-exist");
        CliResult r = runCli(
            "-d", missing.toString(),
            "-c", mappingFile.toString(),
            "--queries-file", queriesFile.toString(),
            "-q", "SELECT * FROM products");
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("data directory"));
    }

    @Test
    void testAddMappingValid() throws Exception {
        CliResult r = runCli(withConfig("--add-mapping", "orders", "orders.json:$.orders"));
        assertEquals(0, r.exitCode());
        assertTrue(r.out().contains("Mapping added"));
        assertTrue(Files.readString(mappingFile).contains("orders"));
    }

    @Test
    void testAddMappingInvalidRejected() {
        CliResult r = runCli(withConfig("--add-mapping", "bad", "not-a-jsonpath"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("error adding mapping"));
    }

    @Test
    void testListTables() {
        CliResult r = runCli(withConfig("--list-tables"));
        assertEquals(0, r.exitCode());
        assertTrue(r.out().contains("products"));
    }

    @Test
    void testCorruptMappingConfigSurfacesError() throws Exception {
        Files.writeString(mappingFile, "{ not valid json ]");
        CliResult r = runCli(withConfig("--list-tables"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("error loading configuration"));
    }

    @Test
    void testUnknownTableReportsError() {
        CliResult r = runCli(withConfig("-q", "SELECT * FROM nonexistent"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("error"));
    }

    @Test
    void testNegativeLimitReportsError() {
        CliResult r = runCli(withConfig("-q", "SELECT * FROM products LIMIT -1"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("error"));
    }

    @Test
    void testCsvFormatOutputsHeaderAndRows() {
        CliResult r = runCli(withConfig("-q", "SELECT name, id FROM products", "--format", "csv"));
        assertEquals(0, r.exitCode());
        assertEquals("""
            name,id
            Widget,1
            Gadget,2
            """, r.out());
    }

    @Test
    void testUnsupportedOutputFormatRejected() {
        CliResult r = runCli(withConfig("-q", "SELECT * FROM products", "--format", "xml"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("unsupported output format"));
    }

    @Test
    void testDescribeShowsTableSchema() {
        CliResult r = runCli(withConfig("--describe", "products"));
        assertEquals(0, r.exitCode());
        assertTrue(r.out().contains("Table: products"));
        assertTrue(r.out().contains("Field"));
        assertTrue(r.out().contains("name"));
        assertTrue(r.out().contains("Sample"));
    }

    @Test
    void testDescribeUnknownTableReportsError() {
        CliResult r = runCli(withConfig("--describe", "missing"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("no mapping found"));
    }

    @Test
    void testDescribeCannotCombineWithQuery() {
        CliResult r = runCli(withConfig("--describe", "products", "-q", "SELECT * FROM products"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("multiple actions"));
    }

    @Test
    void testDryRunValidatesWithoutExecuting() {
        CliResult r = runCli(withConfig("-q", "SELECT name FROM products", "--dry-run"));
        assertEquals(0, r.exitCode());
        assertTrue(r.out().contains("Dry run OK"));
        assertTrue(r.out().contains("products"));
        assertFalse(r.out().contains("Widget"));
    }

    @Test
    void testDryRunReportsMissingMapping() {
        CliResult r = runCli(withConfig("-q", "SELECT * FROM missing", "--dry-run"));
        assertEquals(1, r.exitCode());
        assertTrue(r.err().toLowerCase().contains("no mapping found"));
    }

    @Test
    void testDryRunWithSavedQuery() throws Exception {
        runCli(withConfig("--save-query", "names", "-q", "SELECT name FROM products"));
        CliResult r = runCli(withConfig("--run-query", "names", "--dry-run"));
        assertEquals(0, r.exitCode());
        assertTrue(r.out().contains("Dry run OK"));
    }
}
