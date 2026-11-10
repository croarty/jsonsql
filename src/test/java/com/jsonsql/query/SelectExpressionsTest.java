package com.jsonsql.query;

import com.jsonsql.config.CacheManager;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Calculated SELECT columns: arithmetic, literals, text joined with ||, conditions as
 * values, CASE and CAST.
 */
class SelectExpressionsTest {

    private static final String ROWS = """
        [
          {"id": 1, "name": "Widget", "price": 10,     "qty": 3, "cat": "Tools", "size": "15.6 inch", "ok": true,  "info": {"w": 2.5}, "tags": [1, 2]},
          {"id": 2, "name": "Gadget", "price": 20.5,   "qty": 0, "cat": null,    "size": "7 inch",    "ok": false, "info": {"w": 4}},
          {"id": 3, "name": "Thing",  "price": "none", "qty": 7, "cat": "Toys",  "size": "n/a"}
        ]
        """;

    @TempDir
    File tempDir;

    private final List<String> warnings = new ArrayList<>();
    private MappingManager mappings;
    private QueryExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), ROWS);
        Files.writeString(new File(tempDir, "o.json").toPath(), "[{\"oid\": 100, \"tid\": 1, \"n\": 4}, {\"oid\": 101, \"tid\": 2, \"n\": 2}]");
        mappings = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        mappings.addMapping("t", "t.json:$[*]");
        mappings.addMapping("o", "o.json:$[*]");
        executor = new QueryExecutor(mappings, tempDir);
        executor.setWarningSink(warnings::add);
    }

    /** The value of an expression for each of the three rows, in id order. */
    private String each(String expression) throws Exception {
        String json = executor.execute("SELECT " + expression + " AS v FROM t ORDER BY id");
        return json.replace("[{\"v\":", "").replace("},{\"v\":", " ; ").replace("}]", "");
    }

    @ParameterizedTest(name = "{0} gives {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        // Arithmetic; a whole-number result stays whole, except from division
        "price + 1                 | 11 ; 21.5 ; null",
        "price - 0.5               | 9.5 ; 20.0 ; null",
        "price * qty               | 30 ; 0.0 ; null",
        "price * 1.1               | 11.0 ; 22.55 ; null",
        "qty / 2                   | 1.5 ; 0.0 ; 3.5",
        "6 / 2                     | 3.0 ; 3.0 ; 3.0",
        "7 / 2                     | 3.5 ; 3.5 ; 3.5",
        "10 / 3                    | 3.333333333333333 ; 3.333333333333333 ; 3.333333333333333",
        "qty % 2                   | 1 ; 0 ; 1",
        "7.5 % 2                   | 1.5 ; 1.5 ; 1.5",
        "-7 % 3                    | -1 ; -1 ; -1",
        "-price                    | -10 ; -20.5 ; null",
        "+qty                      | 3 ; 0 ; 7",
        "-(qty - 10)               | 7 ; 10 ; 3",
        "(price + 1) * 2           | 22 ; 43.0 ; null",
        "1 + 2 * 3                 | 7 ; 7 ; 7",
        "(1 + 2) * 3               | 9 ; 9 ; 9",
        "10 - 4 - 3                | 3 ; 3 ; 3",
        "0.1 + 0.2                 | 0.3 ; 0.3 ; 0.3",
        "1.10 * 3                  | 3.3 ; 3.3 ; 3.3",
        "9007199254740993 + 1      | 9007199254740994 ; 9007199254740994 ; 9007199254740994",
        "9223372036854775807 + 1   | 9223372036854775808 ; 9223372036854775808 ; 9223372036854775808",
        "9223372036854775807 * 10  | 92233720368547758070 ; 92233720368547758070 ; 92233720368547758070",
        "info.w * 2                | 5.0 ; 8 ; null",
        // Dividing by zero gives NULL, and NULL passes through
        "price / qty               | 3.333333333333333 ; null ; null",
        "qty % 0                   | null ; null ; null",
        "1 / 0.0                   | null ; null ; null",
        "price + NULL              | null ; null ; null",
        "cat + 1                   | null ; null ; null",
        // Literals
        "'x'                       | \"x\" ; \"x\" ; \"x\"",
        "'it''s'                   | \"it's\" ; \"it's\" ; \"it's\"",
        "5                         | 5 ; 5 ; 5",
        "1.50                      | 1.5 ; 1.5 ; 1.5",
        "NULL                      | null ; null ; null",
        "CASE WHEN ok = true THEN 1 ELSE 0 END | 1 ; 0 ; 0",
        // Conditions give true, false or NULL
        "qty = 0                   | false ; true ; false",
        "price > 15                | false ; true ; true",
        "cat = 'Tools'             | true ; null ; false",
        "cat IS NULL               | false ; true ; false",
        "name LIKE 'W%'            | true ; false ; false",
        "qty IN (0, 7)             | false ; true ; true",
        "qty BETWEEN 1 AND 5       | true ; false ; false",
        "NOT (qty = 0)             | true ; false ; true",
        "(qty > 1 AND cat = 'Tools') | true ; false ; false",
        "(qty = 0 OR cat = 'Toys') | false ; true ; true",
        "qty * 2 > price           | false ; false ; false",
        "price * 2 BETWEEN 15 AND 25 | true ; false ; null",
        // CASE
        "CASE WHEN qty = 0 THEN 'out' WHEN qty < 5 THEN 'low' ELSE 'ok' END | \"low\" ; \"out\" ; \"ok\"",
        "CASE WHEN qty = 0 THEN 'out' END                                   | null ; \"out\" ; null",
        "CASE cat WHEN 'Tools' THEN 1 WHEN 'Toys' THEN 2 ELSE 0 END         | 1 ; 0 ; 2",
        "CASE qty WHEN 3 THEN 'three' END                                   | \"three\" ; null ; null",
        "CASE cat WHEN NULL THEN 'n' ELSE 'other' END                      | \"other\" ; \"other\" ; \"other\"",
        "CASE WHEN qty > 1 THEN price * 2 ELSE 0 END                        | 20 ; 0 ; null",
        "CASE WHEN cat IS NULL THEN 'none' ELSE cat END                     | \"Tools\" ; \"none\" ; \"Toys\"",
        "CASE WHEN qty * 2 > 10 THEN 'many' ELSE 'few' END                  | \"few\" ; \"few\" ; \"many\"",
        "CASE WHEN qty > 5 THEN 1 ELSE 0 END + 10                           | 10 ; 10 ; 11",
        // CAST converts on request; text gives its leading number, or NULL without one
        "CAST(size AS REAL)        | 15.6 ; 7.0 ; null",
        "CAST(size AS INTEGER)     | 15 ; 7 ; null",
        "CAST(size AS REAL) * 2    | 31.2 ; 14.0 ; null",
        "CAST(price AS INTEGER)    | 10 ; 20 ; null",
        "CAST(price AS REAL)       | 10.0 ; 20.5 ; null",
        "CAST(price AS DECIMAL)    | 10.0 ; 20.5 ; null",
        "CAST(qty AS TEXT)         | \"3\" ; \"0\" ; \"7\"",
        "CAST(price AS TEXT)       | \"10\" ; \"20.5\" ; \"none\"",
        "CAST(price AS VARCHAR)    | \"10\" ; \"20.5\" ; \"none\"",
        "CAST(ok AS TEXT)          | \"true\" ; \"false\" ; null",
        "CAST(ok AS INTEGER)       | null ; null ; null",
        "CAST(tags AS TEXT)        | \"[1,2]\" ; null ; null",
        "CAST(tags AS REAL)        | null ; null ; null",
        "CAST('  12.5e1x' AS REAL) | 125.0 ; 125.0 ; 125.0",
        "CAST('-3.9' AS INTEGER)   | -3 ; -3 ; -3",
        "CAST(3.9 AS INT)          | 3 ; 3 ; 3",
        "CAST('7.' AS REAL)        | 7.0 ; 7.0 ; 7.0",
        "CAST('.5' AS REAL)        | 0.5 ; 0.5 ; 0.5",
        "CAST('abc' AS INTEGER)    | null ; null ; null",
        "CAST('' AS REAL)          | null ; null ; null",
        "CAST(NULL AS TEXT)        | null ; null ; null",
        "CAST(cat AS TEXT)         | \"Tools\" ; null ; \"Toys\"",
    })
    void expression(String expression, String values) throws Exception {
        assertEquals(values, each(expression));
    }

    @Test
    void textJoinsWithDoubleBar() throws Exception {
        assertEquals("\"Widget-Tools\" ; null ; \"Thing-Toys\"", each("name || '-' || cat"));
        assertEquals("\"Widget3\" ; \"Gadget0\" ; \"Thing7\"", each("name || qty"));
        assertEquals("\"10\" ; \"20.5\" ; \"none\"", each("price || ''"));
        assertEquals("\"true\" ; \"false\" ; null", each("ok || ''"));
        assertEquals("\"[1,2]!\" ; null ; null", each("tags || '!'"));
        assertEquals("\"#11\" ; \"#21.5\" ; null", each("'#' || (price + 1)"));
        assertTrue(warnings.contains("'#' || (price + 1) left out 1 value that is not a number: \"none\""), warnings.toString());
    }

    @Test
    void anUnnamedColumnIsNamedAfterItsExpression() throws Exception {
        assertEquals("[{\"price * 2\":20,\"'x'\":\"x\",\"5\":5}]",
            executor.execute("SELECT price * 2, 'x', 5 FROM t WHERE id = 1"));
        assertEquals("[{\"CAST(size AS REAL)\":15.6}]", executor.execute("SELECT CAST(size AS REAL) FROM t WHERE id = 1"));
    }

    @Test
    void calculatedColumnsSitBesideFieldsInSelectOrder() throws Exception {
        assertEquals("[{\"id\":1,\"total\":30,\"name\":\"Widget\",\"label\":\"Widget (Tools)\"}]",
            executor.execute("SELECT id, price * qty AS total, name, name || ' (' || cat || ')' AS label FROM t WHERE id = 1"));
        assertEquals("[{\"id\":1,\"double id\":2}]", executor.execute("SELECT id, id * 2 AS \"double id\" FROM t WHERE id = 1"));
        assertEquals("[{\"twice\":2}]", executor.execute("SELECT id * 2 twice FROM t WHERE id = 1"));
    }

    @Test
    void orderByCanUseACalculatedColumn() throws Exception {
        assertEquals("[{\"id\":3,\"half\":3.5},{\"id\":1,\"half\":1.5},{\"id\":2,\"half\":0.0}]",
            executor.execute("SELECT id, qty / 2 AS half FROM t ORDER BY half DESC"));
        assertEquals("[{\"id\":2},{\"id\":1},{\"id\":3}]".replace("\"id\"", "\"id\""),
            executor.execute("SELECT id, qty * 10 AS q FROM t ORDER BY 2").replaceAll(",\"q\":\\d+", ""));
        assertEquals("[{\"band\":\"few\",\"id\":1},{\"band\":\"few\",\"id\":2},{\"band\":\"many\",\"id\":3}]",
            executor.execute("SELECT CASE WHEN qty > 5 THEN 'many' ELSE 'few' END AS band, id FROM t ORDER BY band, id"));
        assertEquals("[{\"v\":0.0},{\"v\":null}]", executor.execute("SELECT price * qty AS v FROM t ORDER BY v DESC LIMIT 2 OFFSET 1"));
    }

    @Test
    void distinctWhereAndLimitWorkAsUsual() throws Exception {
        assertEquals("[{\"band\":\"few\"},{\"band\":\"many\"}]",
            executor.execute("SELECT DISTINCT CASE WHEN qty > 5 THEN 'many' ELSE 'few' END AS band FROM t"));
        assertEquals("[{\"v\":14}]", executor.execute("SELECT qty * 2 AS v FROM t WHERE qty > 5"));
        assertEquals("[{\"v\":6}]", executor.execute("SELECT qty * 2 AS v FROM t LIMIT 1"));
        assertEquals("[]", executor.execute("SELECT qty * 2 AS v FROM t WHERE id > 99"));
    }

    @Test
    void worksAcrossJoinsCtesUnionsAndUnnest() throws Exception {
        assertEquals("[{\"oid\":100,\"cost\":40},{\"oid\":101,\"cost\":41.0}]",
            executor.execute("SELECT o.oid, o.n * t.price AS cost FROM o JOIN t ON o.tid = t.id ORDER BY o.oid"));
        assertEquals("[{\"id\":1,\"doubled\":60}]",
            executor.execute("WITH totals AS (SELECT id, price * qty AS total FROM t) SELECT id, total * 2 AS doubled FROM totals WHERE total > 5"));
        assertEquals("[{\"v\":2},{\"v\":4},{\"v\":6},{\"v\":\"x\"}]",
            executor.execute("SELECT id * 2 AS v FROM t UNION SELECT 'x' FROM t ORDER BY v"));
        assertEquals("[{\"v\":10},{\"v\":20}]", executor.execute("SELECT tag * 10 AS v FROM t, UNNEST(tags) AS x(tag)"));
    }

    @Test
    void arithmeticOnTextWarnsOncePerColumn() throws Exception {
        executor.execute("SELECT price * 2 AS a, price + qty, qty * 2 AS c, cat || '!' FROM t");

        assertEquals(List.of(
            "price * 2 left out 1 value that is not a number: \"none\"",
            "price + qty left out 1 value that is not a number: \"none\""), warnings);
    }

    @Test
    void nullsAndCastsGiveNoWarning() throws Exception {
        executor.execute("SELECT cat || 'x', info.w * 2, CAST(size AS REAL) * 2, CAST(price AS REAL) + 1, -qty FROM t");

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void theWarningCountsEveryValueLeftOut() throws Exception {
        executor.execute("SELECT name * 2 AS a, -size AS b, ok + 1 AS c FROM t");

        assertEquals(List.of(
            "name * 2 left out 3 values that are not numbers, such as \"Widget\"",
            "-size left out 3 values that are not numbers, such as \"15.6 inch\"",
            "ok + 1 left out 2 values that are not numbers, such as true"), warnings);
    }

    @Test
    void strictModeFailsInsteadOfWarning() throws Exception {
        executor.setStrict(true);

        Exception error = assertThrows(Exception.class, () -> executor.execute("SELECT id, price * 2 AS v FROM t"));

        assertEquals("price * 2 found 1 value that is not a number: \"none\"", error.getMessage());
        assertTrue(warnings.isEmpty());
        assertEquals("[{\"v\":6},{\"v\":0},{\"v\":14}]", executor.execute("SELECT qty * 2 AS v FROM t"));
    }

    @Test
    void differentCalculationsDoNotShareACachedResult() throws Exception {
        QueryExecutor cached = new QueryExecutor(mappings, tempDir, new CacheManager(tempDir));

        for (int run = 0; run < 2; run++) {
            assertEquals("[{\"v\":6}]", cached.execute("WITH c AS (SELECT qty * 2 AS v FROM t WHERE id = 1) SELECT v FROM c"));
            assertEquals("[{\"v\":9}]", cached.execute("WITH c AS (SELECT qty * 3 AS v FROM t WHERE id = 1) SELECT v FROM c"));
        }
    }

    @Test
    void enormousNumbersAreHandledApproximately() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), "[{\"id\": 1, \"big\": 1e999999999, \"tiny\": 1e-999999999}]");

        String result = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> executor.execute(
            "SELECT big + 1 AS a, tiny + 1 AS b, big * big AS c, CAST(big AS INTEGER) AS d, CAST(big AS REAL) AS e FROM t"));

        assertEquals("[{\"a\":\"Infinity\",\"b\":1.0,\"c\":\"Infinity\",\"d\":null,\"e\":1E+999999999}]", result);
    }

    @Test
    void plainColumnsAreUnchanged() throws Exception {
        assertEquals("[{\"id\":1,\"n\":\"Widget\",\"w\":2.5}]", executor.execute("SELECT id, name AS n, info.w FROM t WHERE id = 1"));
        assertEquals("[{\"true\":null}]", executor.execute("SELECT true FROM t WHERE id = 1"));
    }

    @Test
    void castSortsTextByItsLeadingNumber() throws Exception {
        assertEquals("[{\"size\":\"n/a\"},{\"size\":\"7 inch\"},{\"size\":\"15.6 inch\"}]",
            executor.execute("SELECT size, CAST(size AS REAL) AS inches FROM t ORDER BY inches").replaceAll(",\"inches\":[^}]+", ""));
    }

    @Test
    void castInACteJoinsANumberToText() throws Exception {
        Files.writeString(new File(tempDir, "o.json").toPath(), "[{\"oid\": 100, \"tid\": \"1\"}, {\"oid\": 101, \"tid\": \"2\"}]");

        assertEquals("[]", executor.execute("SELECT o.oid, t.name FROM o JOIN t ON o.tid = t.id"));
        assertEquals("[{\"oid\":100,\"name\":\"Widget\"},{\"oid\":101,\"name\":\"Gadget\"}]", executor.execute(
            "WITH orders AS (SELECT oid, CAST(tid AS INTEGER) AS tid FROM o) "
                + "SELECT orders.oid, t.name FROM orders JOIN t ON orders.tid = t.id ORDER BY orders.oid"));
    }

    @Test
    void castInACteLetsTextBeSummed() throws Exception {
        Files.writeString(new File(tempDir, "o.json").toPath(), "[{\"w\": \"3.5kg\"}, {\"w\": \"2kg\"}, {\"w\": \"none\"}, {\"w\": 1}]");

        assertEquals("[{\"total\":6.5,\"n\":3}]", executor.execute(
            "WITH weights AS (SELECT CAST(w AS REAL) AS kg FROM o) SELECT SUM(kg) AS total, COUNT(kg) AS n FROM weights"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }
}
