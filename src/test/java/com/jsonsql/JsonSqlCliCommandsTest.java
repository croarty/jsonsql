package com.jsonsql;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the view, index and saved query commands, run through the real CLI.
 */
class JsonSqlCliCommandsTest {

    private static final String WITH_VIEW = "WITH v AS (SELECT id, cat FROM t WHERE id > 1) SELECT * FROM v";

    @TempDir
    File tempDir;

    private File dataFile;

    private record Result(int code, String out, String err) {
    }

    @BeforeEach
    void setUp() throws Exception {
        dataFile = new File(tempDir, "t.json");
        writeData("{\"rows\": [{\"id\": 1, \"cat\": \"a\"}, {\"id\": 2, \"cat\": \"b\"}, {\"id\": 3, \"cat\": \"b\"}]}");
        assertEquals(0, run("--add-mapping", "t", "t.json:$.rows[*]").code());
    }

    private void writeData(String json) throws Exception {
        Files.writeString(dataFile.toPath(), json);
    }

    private Result run(String... args) {
        List<String> all = new ArrayList<>(List.of(
            "-c", new File(tempDir, "m.json").getPath(),
            "-d", tempDir.getPath(),
            "--queries-file", new File(tempDir, "q.json").getPath(),
            "--indexes-file", new File(tempDir, "i.json").getPath(),
            "--views-file", new File(tempDir, "v.json").getPath()));
        all.addAll(List.of(args));

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = new CommandLine(new JsonSqlCli()).execute(all.toArray(new String[0]));
            return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private static void assertSucceeds(Result result) {
        assertEquals(0, result.code(), result.err());
    }

    private static void assertFails(Result result, String message) {
        assertEquals(1, result.code(), result.out());
        assertTrue((result.out() + result.err()).contains(message), result.out() + result.err());
    }

    @Test
    void savedQueriesCanBeListedRunAndDeleted() {
        assertSucceeds(run("--save-query", "two", "-q", "SELECT id FROM t WHERE id = 2"));

        Result listed = run("--list-queries");
        assertSucceeds(listed);
        assertTrue(listed.out().contains("two"), listed.out());

        Result ran = run("--run-query", "two");
        assertSucceeds(ran);
        assertTrue(ran.out().contains("\"id\":2"), ran.out());

        assertSucceeds(run("--delete-query", "two"));
        assertFails(run("--run-query", "two"), "Query not found");
    }

    @Test
    void listingQueriesWithNoneSavedSucceeds() {
        assertSucceeds(run("--list-queries"));
    }

    @Test
    void deletingAnUnknownQueryFails() {
        assertFails(run("--delete-query", "nope"), "Query not found");
    }

    @Test
    void parametersAreAppliedToSavedQueries() {
        assertSucceeds(run("--save-query", "by_id", "-q", "SELECT id FROM t WHERE id = ${n}"));

        Result ran = run("--run-query", "by_id", "--param", "n=3");

        assertSucceeds(ran);
        assertTrue(ran.out().contains("\"id\":3"), ran.out());
    }

    @Test
    void aMalformedParameterFails() {
        assertSucceeds(run("--save-query", "by_id", "-q", "SELECT id FROM t WHERE id = ${n}"));

        assertEquals(1, run("--run-query", "by_id", "--param", "n").code());
    }

    @Test
    void aMissingParameterFails() {
        assertSucceeds(run("--save-query", "by_id", "-q", "SELECT id FROM t WHERE id = ${n}"));

        assertFails(run("--run-query", "by_id"), "Missing required parameters");
    }

    @Test
    void indexesCanBeAddedListedRebuiltAndDropped() {
        assertSucceeds(run("--add-index", "t", "cat"));

        Result listed = run("--list-indexes");
        assertSucceeds(listed);
        assertTrue(listed.out().contains("t.cat"), listed.out());
        assertTrue(listed.out().contains("FRESH"), listed.out());

        assertSucceeds(run("--rebuild-index", "t", "cat"));
        assertSucceeds(run("--rebuild-indexes"));
        assertSucceeds(run("--drop-index", "t", "cat"));

        assertFalse(run("--list-indexes").out().contains("t.cat"));
    }

    @Test
    void addingAnIndexOnAnUnknownTableFails() {
        assertEquals(1, run("--add-index", "nope", "cat").code());
    }

    @Test
    void droppingAnUnknownIndexFails() {
        assertFails(run("--drop-index", "t", "cat"), "No such index");
    }

    @Test
    void anIndexedQueryGivesTheSameRowsWithAndWithoutTheIndex() {
        assertSucceeds(run("--add-index", "t", "cat"));

        Result indexed = run("-q", "SELECT id FROM t WHERE cat = 'b'");
        Result unindexed = run("-q", "SELECT id FROM t WHERE cat = 'b'", "--no-index");

        assertSucceeds(indexed);
        assertEquals(unindexed.out(), indexed.out());
    }

    @Test
    void aMissingIndexFileIsTreatedAsNoIndex() throws Exception {
        assertSucceeds(run("--add-index", "t", "cat"));
        File[] indexFiles = new File(tempDir, ".jsonsql-index").listFiles((dir, name) -> name.endsWith(".idx"));
        assertNotNull(indexFiles);
        assertTrue(indexFiles.length > 0);
        for (File file : indexFiles) {
            Files.delete(file.toPath());
        }

        Result result = run("-q", "SELECT id FROM t WHERE cat = 'b'");

        assertSucceeds(result);
        assertTrue(result.out().contains("\"id\":2") && result.out().contains("\"id\":3"), result.out());
    }

    @Test
    void viewsCanBeCreatedListedShownQueriedAndDropped() {
        assertSucceeds(run("--materialize-view", "v", "-q", WITH_VIEW));

        Result listed = run("--list-materialized-views");
        assertSucceeds(listed);
        assertTrue(listed.out().contains("v"), listed.out());

        assertSucceeds(run("--show-view", "v"));

        Result queried = run("-q", "SELECT id FROM v");
        assertSucceeds(queried);
        assertTrue(queried.out().contains("\"id\":3"), queried.out());

        assertSucceeds(run("--drop-materialized-view", "v"));
        assertFails(run("-q", "SELECT id FROM v"), "No mapping or materialized view");
    }

    @Test
    void aStaleViewWarnsAndARebuildClearsIt() throws Exception {
        assertSucceeds(run("--materialize-view", "v", "-q", WITH_VIEW));
        writeData("{\"rows\": [{\"id\": 9, \"cat\": \"z\"}]}");

        Result stale = run("-q", "SELECT id FROM v");
        assertSucceeds(stale);
        assertTrue(stale.err().contains("STALE"), stale.err());
        assertTrue(stale.out().contains("\"id\":2"), stale.out());

        assertSucceeds(run("--rebuild-view", "v"));

        Result fresh = run("-q", "SELECT id FROM v");
        assertSucceeds(fresh);
        assertFalse(fresh.err().contains("STALE"), fresh.err());
        assertTrue(fresh.out().contains("\"id\":9"), fresh.out());
    }

    @Test
    void aFailedRebuildReportsFailureAndKeepsTheView() throws Exception {
        assertSucceeds(run("--materialize-view", "v", "-q", WITH_VIEW));
        writeData("{\"rows\": [{\"id\": 9");

        assertEquals(1, run("--rebuild-views").code());
        assertEquals(1, run("--rebuild-view", "v").code());

        Result stillThere = run("-q", "SELECT id FROM v");
        assertSucceeds(stillThere);
        assertTrue(stillThere.out().contains("\"id\":2"), stillThere.out());
    }

    @Test
    void rebuildingWithNoViewsSucceeds() {
        assertSucceeds(run("--rebuild-views"));
    }

    @Test
    void droppingAnUnknownViewFails() {
        assertFails(run("--drop-materialized-view", "nope"), "No such materialized view");
    }

    @Test
    void showingAnUnknownViewFails() {
        assertFails(run("--show-view", "nope"), "No materialized view");
    }

    @Test
    void aViewCannotReplaceATableMapping() {
        assertFails(run("--materialize-view", "t", "-q", "WITH t AS (SELECT id FROM t) SELECT * FROM t"),
            "a table mapping with that name already exists");
    }

    @Test
    void cacheCanBeClearedAfterACachedQuery() {
        assertSucceeds(run("-q", "SELECT id FROM t", "--enable-cache"));

        Result cleared = run("--clear-cache");

        assertSucceeds(cleared);
    }

    @Test
    void aBadDataDirectoryFails() {
        assertEquals(1, new CommandLine(new JsonSqlCli()).execute(
            "-c", new File(tempDir, "m.json").getPath(), "-d", new File(tempDir, "nope").getPath(), "-q", "SELECT id FROM t"));
    }
}
