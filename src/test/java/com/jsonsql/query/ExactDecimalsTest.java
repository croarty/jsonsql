package com.jsonsql.query;

import com.jsonsql.config.CacheManager;
import com.jsonsql.config.MappingManager;
import com.jsonsql.index.IndexBuilder;
import com.jsonsql.index.IndexManager;
import com.jsonsql.index.IndexStore;
import com.jsonsql.output.CsvFormatter;
import com.jsonsql.output.OutputHandler;
import com.jsonsql.view.MaterializedViewBuilder;
import com.jsonsql.view.MaterializedViewManager;
import com.jsonsql.view.MaterializedViewStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decimals are exact from the data file to the output: nothing is rounded to a
 * floating-point number on the way, and arithmetic on them is decimal arithmetic.
 */
class ExactDecimalsTest {

    private static final String LONG = "12345678901234567890.123456789";

    @TempDir
    File tempDir;

    private MappingManager mappings;
    private QueryExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        mappings = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        mappings.addMapping("t", "t.json:$[*]");
        executor = new QueryExecutor(mappings, tempDir);
    }

    private void data(String json) throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), json);
    }

    private void values(String... values) throws Exception {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":").append(i + 1).append(",\"v\":").append(values[i]).append("}");
        }
        data(json.append("]").toString());
    }

    @ParameterizedTest(name = "{0} in a file prints as {1}")
    @CsvSource(delimiter = '|', value = {
        // Ordinary numbers print as they always have
        "5                  | 5",
        "-3                 | -3",
        "20.5               | 20.5",
        "20.50              | 20.5",
        "100.0              | 100.0",
        "1.0                | 1.0",
        "0.1                | 0.1",
        "-0.25              | -0.25",
        "1e2                | 100.0",
        "1.5e2              | 150.0",
        "1.5e-7             | 1.5E-7",
        "0.0                | 0.0",
        "-0.0               | 0.0",
        // No digits are lost
        "12345678901234567890.123456789 | 12345678901234567890.123456789",
        "0.1234567890123456789012345    | 0.1234567890123456789012345",
        "9007199254740993   | 9007199254740993",
        "9007199254740993.0 | 9007199254740993.0",
        // Large and small numbers no longer switch to exponent form as early
        "12345678.5         | 12345678.5",
        "0.0001             | 0.0001",
        "1e19               | 10000000000000000000.0",
        // Very large and very small ones still use it
        "1e25               | 1E+25",
        "1.5e300            | 1.5E+300",
        "1e-10              | 1E-10",
    })
    void numbersPrintExactly(String written, String printed) throws Exception {
        values(written);

        assertEquals("[{\"v\":" + printed + "}]", executor.execute("SELECT v FROM t"));
    }

    @ParameterizedTest(name = "SUM and AVG of {0}")
    @CsvSource(delimiter = '|', value = {
        "0.1, 0.2, 0.3      | 0.6                 | 0.2",
        "0.1, 0.2           | 0.3                 | 0.15",
        "10, 20             | 30                  | 15.0",
        "10, 20, 20         | 50                  | 16.66666666666667",
        "1, 2               | 3                   | 1.5",
        "5, 1.0             | 6.0                 | 3.0",
        "50.0, 120.0        | 170.0               | 85.0",
        "19.99, 0.01        | 20.0                | 10.0",
        "1, 0, 0            | 1                   | 0.3333333333333333",
        "2, 0, 0            | 2                   | 0.6666666666666667",
        "-1, 0, 0           | -1                  | -0.3333333333333333",
        "1e2, 5             | 105.0               | 52.5",
        "12345678901234567890.123456789, 0.000000001 | 12345678901234567890.12345679 | 6172839450617283945.061728395",
        "9007199254740993, 9007199254740993 | 18014398509481986 | 9007199254740993.0",
    })
    void sumAndAverageUseDecimalArithmetic(String numbers, String sum, String average) throws Exception {
        values(numbers.split(",\\s*"));

        assertEquals("[{\"s\":" + sum + ",\"a\":" + average + "}]", executor.execute("SELECT SUM(v) AS s, AVG(v) AS a FROM t"));
    }

    @Test
    void comparisonsSeeEveryDigit() throws Exception {
        values(LONG, "0.1", "0.30000000000000004");

        assertEquals("[{\"id\":1}]", executor.execute("SELECT id FROM t WHERE v > 12345678901234567890.1234"));
        assertEquals("[{\"id\":1}]", executor.execute("SELECT id FROM t WHERE v = " + LONG));
        assertEquals("[]", executor.execute("SELECT id FROM t WHERE v = 12345678901234567890.12345678"));
        assertEquals("[{\"id\":2}]", executor.execute("SELECT id FROM t WHERE v = 0.1"));
        assertEquals("[{\"id\":2}]", executor.execute("SELECT id FROM t WHERE v = 0.10"));
        assertEquals("[{\"id\":2}]", executor.execute("SELECT id FROM t WHERE v = 1e-1"));
        assertEquals("[]", executor.execute("SELECT id FROM t WHERE v = 0.3"));
        assertEquals("[{\"id\":3}]", executor.execute("SELECT id FROM t WHERE v IN (0.30000000000000004, 7)"));
        assertEquals("[{\"id\":1}]", executor.execute("SELECT id FROM t WHERE v BETWEEN 12345678901234567890.123456788 AND 12345678901234567890.12345679"));
    }

    @Test
    void digitsSurviveCtesUnionsDistinctAndGrouping() throws Exception {
        values(LONG, LONG, "20.50");
        String both = "[{\"v\":" + LONG + "},{\"v\":20.5}]";

        assertEquals(both, executor.execute("WITH c AS (SELECT v FROM t) SELECT DISTINCT v FROM c"));
        assertEquals(both, executor.execute("SELECT v FROM t WHERE id = 1 UNION SELECT v FROM t WHERE id > 1"));
        assertEquals(both, executor.execute("SELECT v FROM t GROUP BY v"));
        assertEquals("[{\"lo\":20.5,\"hi\":" + LONG + "}]", executor.execute("SELECT MIN(v) AS lo, MAX(v) AS hi FROM t"));
    }

    @Test
    void digitsSurviveTheCache() throws Exception {
        values(LONG, "20.50");
        String sql = "WITH c AS (SELECT v FROM t) SELECT v FROM c";
        String expected = "[{\"v\":" + LONG + "},{\"v\":20.5}]";

        for (int run = 0; run < 3; run++) {
            QueryExecutor cached = new QueryExecutor(mappings, tempDir, new CacheManager(tempDir));
            assertEquals(expected, cached.execute(sql), "run " + run);
            assertEquals(expected, cached.execute("SELECT v FROM t"), "run " + run);
        }
    }

    @Test
    void digitsSurviveAMaterializedView() throws Exception {
        values(LONG, "20.50");
        MaterializedViewManager viewManager = new MaterializedViewManager(new File(tempDir, ".jsonsql-views.json"));
        new MaterializedViewBuilder(mappings, tempDir, viewManager, new MaterializedViewStore(tempDir))
            .materialize("kept", "WITH kept AS (SELECT id, v FROM t) SELECT * FROM kept", null);

        QueryExecutor withViews = new QueryExecutor(mappings, tempDir, null, null, viewManager);

        assertEquals("[{\"v\":" + LONG + "},{\"v\":20.5}]", withViews.execute("SELECT v FROM kept"));
    }

    @Test
    void indexesPruneOnEveryDigit() throws Exception {
        new File(tempDir, "parts").mkdir();
        Files.writeString(new File(tempDir, "parts/a.json").toPath(), "[{\"id\":1,\"v\":" + LONG + "}]");
        Files.writeString(new File(tempDir, "parts/b.json").toPath(), "[{\"id\":2,\"v\":12345678901234567890.123456788}]");
        mappings.addMapping("parts", "parts:$[*]");
        IndexManager indexManager = new IndexManager(new File(tempDir, ".jsonsql-indexes.json"));
        indexManager.addIndex("parts", "v");
        new IndexBuilder(mappings, tempDir, new IndexStore(tempDir)).build("parts", "v");

        QueryExecutor indexed = new QueryExecutor(mappings, tempDir, null, indexManager);

        assertEquals("[{\"id\":1}]", indexed.execute("SELECT id FROM parts WHERE v = " + LONG));
        assertEquals("[{\"id\":1}]", indexed.execute("SELECT id FROM parts WHERE v > 12345678901234567890.1234567885"));
        assertEquals("[{\"id\":2}]", indexed.execute("SELECT id FROM parts WHERE v < 12345678901234567890.1234567885"));
    }

    @Test
    void csvAndPrettyOutputKeepEveryDigit() throws Exception {
        values(LONG, "20.50", "1e2");
        String json = executor.execute("SELECT v FROM t");

        assertEquals("v\n" + LONG + "\n20.5\n100.0\n", new CsvFormatter().format(json));

        File out = new File(tempDir, "out.json");
        new OutputHandler(true).handleOutput(json, out, false);
        String pretty = Files.readString(out.toPath());
        assertTrue(pretty.contains("\"v\" : " + LONG), pretty);
        assertTrue(pretty.contains("\"v\" : 20.5"), pretty);
        assertTrue(pretty.contains("\"v\" : 100.0"), pretty);
    }

    @Test
    void anEnormousExponentDoesNotStallASum() throws Exception {
        values("1e999999999", "1", "1e-999999999");

        String result = assertTimeoutPreemptively(Duration.ofSeconds(20),
            () -> executor.execute("SELECT SUM(v) AS s, AVG(v) AS a, COUNT(v) AS c, MAX(v) AS hi FROM t"));

        assertTrue(result.contains("\"c\":3"), result);
        assertTrue(result.contains("\"hi\":1E+999999999"), result);
    }

    @Test
    void numbersWrittenInAQueryAreExact() {
        assertEquals("0.1", Json.number("0.10").toString());
        assertEquals("20.0", Json.number("2e1").toString());
        assertEquals("-5.0", Json.number("- 5").toString());
        assertEquals("0.5", Json.number(".5").toString());
        assertEquals(LONG, Json.number(LONG).toString());
    }

    @ParameterizedTest
    @CsvSource({"abc", "1d", "0x10", "NaN", "Infinity", "1.2.3", "''"})
    void textThatIsNotANumberIsRejectedClearly(String text) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Json.number(text));

        assertEquals("Not a number: " + text, error.getMessage());
        assertFalse(error instanceof NumberFormatException);
    }

    @Test
    void numbersInNestedValuesAreExactToo() throws Exception {
        data("[{\"id\":1,\"info\":{\"size\":" + LONG + ",\"sizes\":[1.50,2e1]}}]");

        assertEquals("[{\"size\":" + LONG + "}]", executor.execute("SELECT info.size FROM t"));
        assertEquals("[{\"sizes\":[1.5,20.0]}]", executor.execute("SELECT info.sizes FROM t"));
        assertEquals("[{\"s\":1.5},{\"s\":20.0}]", executor.execute("SELECT s FROM t, UNNEST(info.sizes) AS x(s)"));
    }
}
