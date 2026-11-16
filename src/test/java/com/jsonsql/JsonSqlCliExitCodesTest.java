package com.jsonsql;

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
 * The exit code says what kind of thing went wrong, so a script can tell them apart:
 * 1 for the query, 2 for the command line, and 3 for the data or another file.
 */
class JsonSqlCliExitCodesTest {

    private static final int OK = 0;
    private static final int QUERY = 1;
    private static final int USAGE = 2;
    private static final int DATA = 3;

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Path mappingFile;
    private Path queriesFile;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("products.json"), """
            {"products": [{"id": 1, "name": "Widget", "price": 10}, {"id": 2, "name": "Gadget", "price": "none"}]}
            """);
        Files.writeString(dataDir.resolve("broken.json"), "{\"rows\": [{\"id\": 1},");
        Files.write(dataDir.resolve("latin1.json"), new byte[] {'[', '{', '"', 'n', '"', ':', '"', (byte) 0xE9, '"', '}', ']'});
        mappingFile = tempDir.resolve("mappings.json");
        Files.writeString(mappingFile, """
            {"products": "products.json:$.products", "missing": "missing.json:$.rows",
             "broken": "broken.json:$.rows", "latin1": "latin1.json:$[*]"}
            """);
        queriesFile = tempDir.resolve("queries.json");
    }

    private record CliResult(int exitCode, String out, String err) {}

    private CliResult run(String... args) {
        String[] base = {
            "-d", dataDir.toString(),
            "-c", mappingFile.toString(),
            "--queries-file", queriesFile.toString(),
            "--indexes-file", tempDir.resolve(".jsonsql-indexes.json").toString(),
            "--views-file", tempDir.resolve(".jsonsql-views.json").toString()
        };
        String[] all = new String[base.length + args.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(args, 0, all, base.length, args.length);
        return runRaw(all);
    }

    private CliResult runRaw(String... args) {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        try {
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new JsonSqlCli()).execute(args);
            return new CliResult(code, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    private void assertExit(int expected, CliResult result) {
        assertEquals(expected, result.exitCode(), "stderr: " + result.err() + " stdout: " + result.out());
        if (expected != OK) {
            assertFalse(result.err().isBlank(), "an error must say what went wrong");
        }
    }

    @Test
    void successIsZero() {
        assertExit(OK, run("-q", "SELECT * FROM products"));
        assertExit(OK, run("-q", "SELECT * FROM products", "--dry-run"));
        assertExit(OK, run("-q", "SELECT SUM(id) AS s FROM products", "--strict"));
        assertExit(OK, run("--list-tables"));
        assertExit(OK, run("--list-queries"));
        assertExit(OK, run("--describe", "products"));
    }

    @Test
    void aWarningIsStillSuccess() {
        CliResult result = run("-q", "SELECT SUM(price) AS s FROM products");

        assertEquals(OK, result.exitCode());
        assertTrue(result.err().contains("Warning: SUM(price) left out 1 value"), result.err());
    }

    @Test
    void aQueryThatIsNotValidSqlIsAQueryError() {
        assertExit(QUERY, run("-q", "SELEC name FROM products"));
        assertExit(QUERY, run("-q", "SELECT name FROM"));
        assertExit(QUERY, run("-q", "DELETE FROM products"));
    }

    @Test
    void sqlThatIsNotSupportedIsAQueryError() {
        assertExit(QUERY, run("-q", "SELECT SOUNDEX(name) FROM products"));
        assertExit(QUERY, run("-q", "SELECT UPPER(name, name) FROM products"));
        assertExit(QUERY, run("-q", "SELECT * FROM products p NATURAL JOIN products q"));
        assertExit(QUERY, run("-q", "SELECT name, COUNT(*) FROM products"));
        assertExit(QUERY, run("-q", "SELECT * FROM products WHERE EXISTS (SELECT 1 FROM products)"));
    }

    @Test
    void aTableThatIsNotMappedIsAQueryError() {
        assertExit(QUERY, run("-q", "SELECT * FROM nope"));
        assertExit(QUERY, run("-q", "SELECT * FROM products p JOIN nope n ON p.id = n.id"));
        assertExit(QUERY, run("--describe", "nope"));
        assertExit(QUERY, run("--add-index", "nope", "id"));
    }

    @Test
    void dryRunReportsAQueryErrorTheSameWay() {
        assertExit(QUERY, run("-q", "SELECT SOUNDEX(name) FROM products", "--dry-run"));
        assertExit(QUERY, run("-q", "SELECT * FROM nope", "--dry-run"));
    }

    @Test
    void dryRunReportsABadJoinCondition() {
        CliResult result = run("-q", "SELECT * FROM products p JOIN products q ON SUM(p.id) = q.id", "--dry-run");

        assertExit(QUERY, result);
        assertTrue(result.err().contains("Function in JOIN ON: SUM(p.id) = q.id is not supported"), result.err());
        assertExit(QUERY, run("-q", "SELECT * FROM products p JOIN products q USING (id)", "--dry-run"));
        assertExit(OK, run("-q", "SELECT * FROM products p JOIN products q ON p.id = q.id AND q.price > 1", "--dry-run"));
    }

    @Test
    void textInJoinArithmeticIsADataErrorInStrictMode() {
        CliResult warned = run("-q", "SELECT p.id FROM products p JOIN products q ON p.id = q.id AND q.price + 1 > 0");
        assertEquals(OK, warned.exitCode());
        assertTrue(warned.err().contains("Warning: q.price + 1 left out 1 value"), warned.err());

        assertExit(DATA, run("-q", "SELECT p.id FROM products p JOIN products q ON p.id = q.id AND q.price + 1 > 0", "--strict"));
    }

    @Test
    void aNameThatDoesNotExistIsAQueryError() {
        assertExit(QUERY, run("--run-query", "nope"));
        assertExit(QUERY, run("--delete-query", "nope"));
        assertExit(QUERY, run("--drop-index", "products", "nope"));
        assertExit(QUERY, run("--rebuild-index", "products", "nope"));
        assertExit(QUERY, run("--show-view", "nope"));
        assertExit(QUERY, run("--drop-materialized-view", "nope"));
        assertExit(QUERY, run("--rebuild-view", "nope"));
    }

    @Test
    void aViewQueryThatCannotBeMaterializedIsAQueryError() {
        assertExit(QUERY, run("--materialize-view", "v", "-q", "SELECT name FROM products"));
        assertExit(QUERY, run("--materialize-view", "v", "-q", "WITH v AS (SELECT name FROM nope) SELECT * FROM v"));
    }

    @Test
    void anOptionThatDoesNotExistIsAUsageError() {
        assertEquals(USAGE, run("--nope").exitCode());
        assertEquals(USAGE, run("-q").exitCode());
        assertEquals(USAGE, run("--add-mapping", "onlyOne").exitCode());
    }

    @Test
    void optionsThatDoNotGoTogetherAreAUsageError() {
        assertExit(USAGE, run("-q", "SELECT * FROM products", "--list-tables"));
        assertExit(USAGE, run("--run-query", "a", "-q", "SELECT * FROM products"));
        assertExit(USAGE, run("--save-query", "a"));
        assertExit(USAGE, run("--materialize-view", "v"));
        assertExit(USAGE, run());
    }

    @Test
    void anOptionValueThatMakesNoSenseIsAUsageError() {
        assertExit(USAGE, run("-q", "SELECT * FROM products", "--format", "xml"));
        assertExit(USAGE, run("-q", "SELECT * FROM products", "--param", "noEqualsSign"));
        assertExit(USAGE, run("-q", "SELECT * FROM products", "--param", "=5"));
        assertExit(USAGE, run("--save-query", " ", "-q", "SELECT * FROM products"));
        assertExit(USAGE, run("--add-mapping", "t", " "));
    }

    @Test
    void aParameterThatIsMissingOrRejectedIsAUsageError() {
        assertExit(USAGE, run("-q", "SELECT * FROM products WHERE id = ${id}"));
        assertExit(USAGE, run("-q", "SELECT * FROM products WHERE id = ${id}", "--param", "id=1 OR 1=1"));
        assertExit(OK, run("-q", "SELECT * FROM products WHERE id = ${id}", "--param", "id=1"));
    }

    @Test
    void anUnknownFormatIsReportedBeforeTheQueryRuns() {
        CliResult result = run("-q", "SELECT SUM(price) AS s FROM products", "--format", "xml");

        assertEquals(USAGE, result.exitCode());
        assertEquals("", result.out());
        assertFalse(result.err().contains("Warning"), "the query should not have run: " + result.err());
        assertTrue(result.err().contains("Unsupported output format: 'xml'"), result.err());
    }

    @Test
    void aDataFileThatCannotBeReadIsADataError() {
        assertExit(DATA, run("-q", "SELECT * FROM missing"));
        assertExit(DATA, run("-q", "SELECT * FROM broken"));
        assertExit(DATA, run("-q", "SELECT * FROM latin1"));
        assertExit(DATA, run("-q", "SELECT * FROM products p JOIN broken b ON p.id = b.id"));
        assertExit(DATA, run("--describe", "broken"));
        assertExit(DATA, run("--add-index", "broken", "id"));
    }

    @Test
    void dryRunReportsAMissingDataFileAsADataError() {
        assertExit(DATA, run("-q", "SELECT * FROM missing", "--dry-run"));
    }

    @Test
    void aDataDirectoryThatDoesNotExistIsADataError() {
        String[] common = {"-c", mappingFile.toString(), "--queries-file", queriesFile.toString()};
        String nowhere = tempDir.resolve("nowhere").toString();

        assertExit(DATA, runRaw(common[0], common[1], common[2], common[3], "-d", nowhere, "-q", "SELECT * FROM products"));
        assertExit(DATA, runRaw(common[0], common[1], common[2], common[3], "-d", nowhere, "--clear-cache"));
        assertExit(DATA, runRaw(common[0], common[1], common[2], common[3], "-d", nowhere, "--list-indexes"));
        assertExit(DATA, runRaw(common[0], common[1], common[2], common[3], "-d", nowhere, "--list-materialized-views"));
        assertExit(DATA, runRaw(common[0], common[1], common[2], common[3], "-d", nowhere, "--describe", "products"));
    }

    @Test
    void strictModeFindingBadValuesIsADataError() {
        CliResult result = run("-q", "SELECT SUM(price) AS s FROM products", "--strict");

        assertExit(DATA, result);
        assertEquals("", result.out());
        assertTrue(result.err().contains("SUM(price) found 1 value that is not a number"), result.err());
        assertExit(DATA, run("-q", "SELECT price * 2 AS v FROM products", "--strict"));
        assertExit(DATA, run("-q", "SELECT id FROM products WHERE ROUND(price) > 1", "--strict"));
    }

    @Test
    void aSettingsFileThatCannotBeReadIsADataError() throws Exception {
        Files.writeString(mappingFile, "{not json");
        assertExit(DATA, run("-q", "SELECT * FROM products"));
        assertExit(DATA, run("--list-tables"));
    }

    @Test
    void aSavedQueriesFileThatCannotBeReadIsADataError() throws Exception {
        Files.writeString(queriesFile, "{not json");
        assertExit(DATA, run("--list-queries"));
    }

    @Test
    void anIndexOrViewFileThatCannotBeReadIsADataError() throws Exception {
        Files.writeString(tempDir.resolve(".jsonsql-indexes.json"), "{not json");
        Files.writeString(tempDir.resolve(".jsonsql-views.json"), "{not json");

        assertExit(DATA, run("--list-indexes"));
        assertExit(DATA, run("--list-materialized-views"));
    }

    @Test
    void outputThatCannotBeWrittenIsADataError() {
        // The output path is a directory, so it can't be written as a file
        CliResult result = run("-q", "SELECT * FROM products", "-o", dataDir.toString());

        assertExit(DATA, result);
        assertTrue(result.err().contains("Error writing output"), result.err());
    }

    @Test
    void rebuildingEverythingReportsTheWorstFailure() throws Exception {
        assertExit(OK, run("--add-index", "products", "id"));
        assertExit(OK, run("--rebuild-indexes"));

        Files.writeString(dataDir.resolve("products.json"), "{\"products\": [");
        assertExit(DATA, run("--rebuild-indexes"));
    }
}
