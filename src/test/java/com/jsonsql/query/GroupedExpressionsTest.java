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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Calculations with grouping: inside an aggregate, around aggregates, in GROUP BY, in
 * HAVING, and in the ORDER BY of a grouped query.
 */
class GroupedExpressionsTest {

    private static final String ROWS = """
        [
          {"id": 1, "cat": "a",  "price": 10,     "qty": 3, "size": "2 m"},
          {"id": 2, "cat": "a",  "price": 20.5,   "qty": 0, "size": "3 m"},
          {"id": 3, "cat": "b",  "price": "none", "qty": 7, "size": "n/a"},
          {"id": 4, "cat": "b",  "price": 5,      "qty": 2, "size": "10 m"},
          {"id": 5, "cat": null, "price": 8,      "qty": 1}
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

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = "->", quoteCharacter = '`', value = {
        // Inside an aggregate
        "SELECT SUM(price * qty) AS v FROM t -> [{\"v\":48.0}]",
        "SELECT COUNT(CASE WHEN qty > 2 THEN 1 END) AS v FROM t -> [{\"v\":2}]",
        "SELECT SUM(CASE WHEN qty > 2 THEN 1 ELSE 0 END) AS v FROM t -> [{\"v\":2}]",
        "SELECT MIN(CAST(size AS REAL)) AS lo, MAX(CAST(size AS REAL)) AS hi FROM t -> [{\"lo\":2.0,\"hi\":10.0}]",
        "SELECT AVG(qty * 2) AS v FROM t -> [{\"v\":5.2}]",
        "SELECT SUM(CAST(size AS REAL)) AS v, COUNT(CAST(size AS REAL)) AS n FROM t -> [{\"v\":15.0,\"n\":3}]",
        "SELECT SUM(1) AS a, COUNT('x') AS b, SUM(qty + 1) AS c FROM t -> [{\"a\":5,\"b\":5,\"c\":18}]",
        "SELECT COUNT(DISTINCT qty % 2) AS v FROM t -> [{\"v\":2}]",
        "SELECT COUNT(NULL) AS a, COUNT(price / 0) AS b, SUM(NULL) AS c FROM t -> [{\"a\":0,\"b\":0,\"c\":null}]",
        "SELECT cat, SUM(price * qty) AS v FROM t GROUP BY cat ORDER BY cat -> [{\"cat\":null,\"v\":8},{\"cat\":\"a\",\"v\":30.0},{\"cat\":\"b\",\"v\":10}]",
        "SELECT SUM(price * qty) FROM t WHERE id = 1 -> [{\"SUM(price * qty)\":30}]",
        "SELECT sum(price * qty), SUM(price*qty) AS again FROM t WHERE id = 1 -> [{\"sum(price * qty)\":30,\"again\":30}]",
        // Around aggregates
        "SELECT SUM(qty) * 2 AS v FROM t -> [{\"v\":26}]",
        "SELECT SUM(qty) / COUNT(*) AS v FROM t -> [{\"v\":2.6}]",
        "SELECT cat, MAX(qty) - MIN(qty) AS spread FROM t GROUP BY cat ORDER BY cat -> [{\"cat\":null,\"spread\":0},{\"cat\":\"a\",\"spread\":3},{\"cat\":\"b\",\"spread\":5}]",
        "SELECT cat || '!' AS c, COUNT(*) AS n FROM t GROUP BY cat ORDER BY cat -> [{\"c\":null,\"n\":1},{\"c\":\"a!\",\"n\":2},{\"c\":\"b!\",\"n\":2}]",
        "SELECT cat, CASE WHEN COUNT(*) > 1 THEN 'many' ELSE 'one' END AS size FROM t GROUP BY cat ORDER BY cat -> [{\"cat\":null,\"size\":\"one\"},{\"cat\":\"a\",\"size\":\"many\"},{\"cat\":\"b\",\"size\":\"many\"}]",
        "SELECT COUNT(*) + 1 FROM t -> [{\"COUNT(*) + 1\":6}]",
        "SELECT COUNT(*) > 2 AS big, CAST(AVG(qty) AS INTEGER) AS whole FROM t -> [{\"big\":true,\"whole\":2}]",
        "SELECT SUM(price * qty) / SUM(qty) AS v FROM t WHERE cat = 'a' -> [{\"v\":10.0}]",
        "SELECT cat, SUM(qty) AS s, SUM(qty) * 100 / 4 AS pct FROM t GROUP BY cat ORDER BY cat -> [{\"cat\":null,\"s\":1,\"pct\":25.0},{\"cat\":\"a\",\"s\":3,\"pct\":75.0},{\"cat\":\"b\",\"s\":9,\"pct\":225.0}]",
        "SELECT SUM(qty) * 2 AS v FROM t WHERE id > 99 -> [{\"v\":null}]",
        "SELECT COUNT(*) * 2 AS v FROM t WHERE id > 99 -> [{\"v\":0}]",
        "SELECT 'total' AS label, COUNT(*) AS n FROM t -> [{\"label\":\"total\",\"n\":5}]",
        // GROUP BY a calculation
        "SELECT qty > 2 AS big, COUNT(*) AS n FROM t GROUP BY qty > 2 ORDER BY big -> [{\"big\":false,\"n\":3},{\"big\":true,\"n\":2}]",
        "SELECT qty > 2 AS big, COUNT(*) AS n FROM t GROUP BY big ORDER BY big -> [{\"big\":false,\"n\":3},{\"big\":true,\"n\":2}]",
        "SELECT CASE WHEN qty > 2 THEN 'many' ELSE 'few' END AS band, SUM(qty) AS s FROM t GROUP BY 1 ORDER BY band -> [{\"band\":\"few\",\"s\":3},{\"band\":\"many\",\"s\":10}]",
        "SELECT qty % 2, COUNT(*) FROM t GROUP BY qty % 2 ORDER BY 1 -> [{\"qty % 2\":0,\"COUNT(*)\":2},{\"qty % 2\":1,\"COUNT(*)\":3}]",
        "SELECT (qty % 2) + 10 AS v, COUNT(*) AS n FROM t GROUP BY qty % 2 ORDER BY v -> [{\"v\":10,\"n\":2},{\"v\":11,\"n\":3}]",
        "SELECT cat, qty % 2 AS odd, COUNT(*) AS n FROM t GROUP BY cat, qty % 2 ORDER BY cat, odd -> [{\"cat\":null,\"odd\":1,\"n\":1},{\"cat\":\"a\",\"odd\":0,\"n\":1},{\"cat\":\"a\",\"odd\":1,\"n\":1},{\"cat\":\"b\",\"odd\":0,\"n\":1},{\"cat\":\"b\",\"odd\":1,\"n\":1}]",
        "SELECT COUNT(*) AS n FROM t GROUP BY CAST(size AS REAL) > 2.5 ORDER BY n -> [{\"n\":1},{\"n\":2},{\"n\":2}]",
        "SELECT COUNT(*) AS n FROM t GROUP BY qty % 2 ORDER BY qty % 2 DESC -> [{\"n\":3},{\"n\":2}]",
        "SELECT COUNT(*) AS n FROM t GROUP BY qty % 2 HAVING qty % 2 = 1 -> [{\"n\":3}]",
        "SELECT price * 0 AS zero, COUNT(*) AS n FROM t GROUP BY price * 0 ORDER BY n -> [{\"zero\":null,\"n\":1},{\"zero\":0,\"n\":4}]",
        "SELECT cat, COUNT(*) AS n FROM t x GROUP BY cat, x.cat || x.cat ORDER BY cat -> [{\"cat\":null,\"n\":1},{\"cat\":\"a\",\"n\":2},{\"cat\":\"b\",\"n\":2}]",
        // HAVING
        "SELECT cat FROM t GROUP BY cat HAVING SUM(qty) * 2 > 10 -> [{\"cat\":\"b\"}]",
        "SELECT cat FROM t GROUP BY cat HAVING SUM(qty) / COUNT(*) >= 1.5 ORDER BY cat -> [{\"cat\":\"a\"},{\"cat\":\"b\"}]",
        "SELECT cat FROM t GROUP BY cat HAVING COUNT(*) + 1 > 2 ORDER BY cat -> [{\"cat\":\"a\"},{\"cat\":\"b\"}]",
        "SELECT cat FROM t GROUP BY cat HAVING cat || 'x' = 'ax' -> [{\"cat\":\"a\"}]",
        "SELECT cat FROM t GROUP BY cat HAVING MAX(qty) - MIN(qty) BETWEEN 3 AND 4 -> [{\"cat\":\"a\"}]",
        "SELECT cat, COUNT(*) AS n FROM t GROUP BY cat HAVING n * 2 > 2 ORDER BY cat -> [{\"cat\":\"a\",\"n\":2},{\"cat\":\"b\",\"n\":2}]",
        "SELECT cat, COUNT(*) * 10 AS tens FROM t GROUP BY cat HAVING tens > 10 ORDER BY cat -> [{\"cat\":\"a\",\"tens\":20},{\"cat\":\"b\",\"tens\":20}]",
        "SELECT cat FROM t GROUP BY cat HAVING SUM(price * qty) > 20 -> [{\"cat\":\"a\"}]",
        "SELECT cat FROM t GROUP BY cat HAVING CASE WHEN COUNT(*) > 1 THEN 'many' ELSE 'one' END = 'one' -> [{\"cat\":null}]",
        "SELECT COUNT(*) AS n FROM t HAVING COUNT(*) * 2 = 10 -> [{\"n\":5}]",
        "SELECT COUNT(*) AS n FROM t HAVING COUNT(*) * 2 = 11 -> []",
        "SELECT cat FROM t GROUP BY cat HAVING CAST(SUM(qty) AS TEXT) LIKE '9%' -> [{\"cat\":\"b\"}]",
        "SELECT cat FROM t GROUP BY cat HAVING SUM(qty) + 0 IN (3, 1) ORDER BY cat -> [{\"cat\":null},{\"cat\":\"a\"}]",
        "SELECT cat FROM t GROUP BY cat HAVING cat || '' IS NULL -> [{\"cat\":null}]",
        "SELECT cat FROM t GROUP BY cat HAVING NOT (SUM(qty) * 2 > 10) ORDER BY cat -> [{\"cat\":null},{\"cat\":\"a\"}]",
        "SELECT cat FROM t GROUP BY cat HAVING SUM(qty) * 2 > 10 OR cat IS NULL ORDER BY cat -> [{\"cat\":null},{\"cat\":\"b\"}]",
        // ORDER BY in a grouped query
        "SELECT cat FROM t GROUP BY cat ORDER BY SUM(qty) * -1 -> [{\"cat\":\"b\"},{\"cat\":\"a\"},{\"cat\":null}]",
        "SELECT cat FROM t GROUP BY cat ORDER BY COUNT(*) + 1 DESC, cat -> [{\"cat\":\"a\"},{\"cat\":\"b\"},{\"cat\":null}]",
        "SELECT cat, COUNT(*) AS n FROM t GROUP BY cat ORDER BY n * -1, cat -> [{\"cat\":\"a\",\"n\":2},{\"cat\":\"b\",\"n\":2},{\"cat\":null,\"n\":1}]",
        "SELECT cat FROM t GROUP BY cat ORDER BY CASE WHEN cat IS NULL THEN 1 ELSE 0 END, cat -> [{\"cat\":\"a\"},{\"cat\":\"b\"},{\"cat\":null}]",
        "SELECT cat FROM t GROUP BY cat ORDER BY SUM(price * qty) DESC -> [{\"cat\":\"a\"},{\"cat\":\"b\"},{\"cat\":null}]",
        "SELECT cat FROM t GROUP BY cat ORDER BY MAX(qty) - MIN(qty), cat -> [{\"cat\":null},{\"cat\":\"a\"},{\"cat\":\"b\"}]",
        "SELECT cat FROM t GROUP BY cat ORDER BY SUM(qty) * -1 LIMIT 1 -> [{\"cat\":\"b\"}]",
        "SELECT cat FROM t GROUP BY cat ORDER BY SUM(qty) * -1 LIMIT 1 OFFSET 1 -> [{\"cat\":\"a\"}]",
        // Table aliases, joins, CTEs and DISTINCT
        "SELECT x.cat, SUM(x.price * x.qty) AS v FROM t x GROUP BY x.cat HAVING x.cat || '' = 'a' -> [{\"cat\":\"a\",\"v\":30.0}]",
        "SELECT cat FROM t x GROUP BY cat HAVING x.cat || '' = 'b' -> [{\"cat\":\"b\"}]",
        "SELECT x.cat FROM t x GROUP BY x.cat HAVING cat || '' = 'b' -> [{\"cat\":\"b\"}]",
        "SELECT cat FROM t x GROUP BY cat HAVING x.cat = 'b' -> [{\"cat\":\"b\"}]",
        "SELECT x.cat FROM t x GROUP BY x.cat HAVING cat = 'b' ORDER BY cat -> [{\"cat\":\"b\"}]",
        "SELECT cat, COUNT(*) AS n FROM t x GROUP BY cat ORDER BY x.cat DESC -> [{\"cat\":\"b\",\"n\":2},{\"cat\":\"a\",\"n\":2},{\"cat\":null,\"n\":1}]",
        "SELECT t.cat, SUM(o.n * t.price) AS v FROM o JOIN t ON o.tid = t.id GROUP BY t.cat -> [{\"cat\":\"a\",\"v\":81.0}]",
        "WITH s AS (SELECT cat, SUM(price * qty) AS v FROM t GROUP BY cat) SELECT cat, v * 2 AS d FROM s WHERE v > 9 ORDER BY cat -> [{\"cat\":\"a\",\"d\":60.0},{\"cat\":\"b\",\"d\":20}]",
        "SELECT DISTINCT COUNT(*) * 2 AS n FROM t GROUP BY cat ORDER BY n -> [{\"n\":2},{\"n\":4}]",
        "SELECT SUM(qty) * 2 AS v FROM t UNION ALL SELECT COUNT(*) + 1 FROM o -> [{\"v\":26},{\"v\":3}]",
    })
    void query(String sql, String json) throws Exception {
        assertEquals(json, executor.execute(sql));
    }

    @Test
    void arithmeticOnTextInsideAnAggregateWarns() throws Exception {
        executor.execute("SELECT cat, SUM(price * qty) AS v FROM t GROUP BY cat");

        assertEquals(List.of("price * qty left out 1 value that is not a number: \"none\""), warnings);
    }

    @Test
    void arithmeticOnATextResultWarns() throws Exception {
        assertEquals("[{\"v\":null}]", executor.execute("SELECT MAX(size) * 2 AS v FROM t"));
        assertEquals(List.of("MAX(size) * 2 left out 1 value that is not a number: \"n/a\""), warnings);
    }

    @Test
    void arithmeticOnTextInHavingAndOrderByWarns() throws Exception {
        assertEquals("[]", executor.execute("SELECT cat FROM t GROUP BY cat HAVING MAX(size) * 2 > 1"));
        assertEquals(List.of("MAX(size) * 2 left out 2 values that are not numbers, such as \"3 m\""), warnings);

        warnings.clear();
        executor.execute("SELECT cat FROM t GROUP BY cat ORDER BY MIN(size) + 1");
        assertEquals(List.of("MIN(size) + 1 left out 2 values that are not numbers, such as \"2 m\""), warnings);
    }

    @Test
    void sumStillWarnsAboutTextWhenUsedInACalculation() throws Exception {
        assertEquals("[{\"v\":44.5}]", executor.execute("SELECT SUM(price) + 1 AS v FROM t"));
        assertEquals(List.of("SUM(price) left out 1 value that is not a number: \"none\""), warnings);
    }

    @Test
    void cleanCalculationsGiveNoWarning() throws Exception {
        executor.execute("SELECT cat, SUM(qty * 2) AS a, MAX(CAST(size AS REAL)) * 2 AS b, COUNT(*) + 1 AS c FROM t "
            + "GROUP BY cat HAVING SUM(qty) * 2 > 0 ORDER BY SUM(qty) + 1");

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void strictModeFailsInsteadOfWarning() throws Exception {
        executor.setStrict(true);

        Exception inside = assertThrows(Exception.class, () -> executor.execute("SELECT SUM(price * qty) AS v FROM t"));
        assertEquals("price * qty found 1 value that is not a number: \"none\"", inside.getMessage());

        Exception around = assertThrows(Exception.class, () -> executor.execute("SELECT MAX(size) * 2 AS v FROM t"));
        assertEquals("MAX(size) * 2 found 1 value that is not a number: \"n/a\"", around.getMessage());

        Exception having = assertThrows(Exception.class, () -> executor.execute("SELECT cat FROM t GROUP BY cat HAVING MAX(size) * 2 > 1"));
        assertEquals("MAX(size) * 2 found 2 values that are not numbers, such as \"3 m\"", having.getMessage());

        assertTrue(warnings.isEmpty());
        assertEquals("[{\"v\":26}]", executor.execute("SELECT SUM(qty) * 2 AS v FROM t"));
    }

    @Test
    void differentCalculationsDoNotShareACachedResult() throws Exception {
        QueryExecutor cached = new QueryExecutor(mappings, tempDir, new CacheManager(tempDir));

        for (int run = 0; run < 2; run++) {
            assertEquals("[{\"v\":26}]", cached.execute("WITH c AS (SELECT SUM(qty * 2) AS v FROM t) SELECT v FROM c"));
            assertEquals("[{\"v\":39}]", cached.execute("WITH c AS (SELECT SUM(qty * 3) AS v FROM t) SELECT v FROM c"));
            assertEquals("[{\"v\":14}]", cached.execute("WITH c AS (SELECT SUM(qty) + 1 AS v FROM t) SELECT v FROM c"));
            assertEquals("[{\"v\":15}]", cached.execute("WITH c AS (SELECT SUM(qty) + 2 AS v FROM t) SELECT v FROM c"));
            assertEquals("[{\"n\":3}]", cached.execute("WITH c AS (SELECT COUNT(*) AS n FROM t GROUP BY qty % 2 HAVING COUNT(*) + 0 > 2) SELECT n FROM c"));
            assertEquals("[{\"n\":5}]", cached.execute("WITH c AS (SELECT COUNT(*) AS n FROM t GROUP BY qty % 1 HAVING COUNT(*) + 0 > 2) SELECT n FROM c"));
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = "->", quoteCharacter = '`', value = {
        "SELECT cat, price * 2 FROM t GROUP BY cat                      -> Column 'price' must be in GROUP BY or inside an aggregate",
        "SELECT COUNT(*), price + 1 FROM t                              -> Column 'price' must be in GROUP BY or inside an aggregate",
        "SELECT SUM(qty) + id FROM t                                    -> Column 'id' must be in GROUP BY or inside an aggregate",
        "SELECT cat, qty + 1 FROM t GROUP BY cat, qty % 2               -> Column 'qty' must be in GROUP BY or inside an aggregate",
        "SELECT qty FROM t GROUP BY qty % 2                             -> Column 'qty' must be in GROUP BY or inside an aggregate",
        "SELECT price FROM t x GROUP BY x.qty + x.price                 -> Column 'price' must be in GROUP BY or inside an aggregate",
        "SELECT cat, COUNT(*) AS n, n * 2 FROM t GROUP BY cat           -> Column 'n' must be in GROUP BY or inside an aggregate",
        "SELECT SUM(COUNT(id)) FROM t                                   -> Function or aggregate in SELECT list: SUM(COUNT(id)) is not supported (an aggregate can't be used here)",
        "SELECT SUM(qty * COUNT(*)) FROM t                              -> Function or aggregate in SELECT list: SUM(qty * COUNT(*)) is not supported (an aggregate can't be used here)",
        "SELECT SUM(LENGTH(cat)) FROM t                                 -> Function or aggregate in SELECT list: SUM(LENGTH(cat)) is not supported (functions are not available)",
        "SELECT SUM(qty) * LENGTH(cat) FROM t GROUP BY cat              -> Function or aggregate in SELECT list: SUM(qty) * LENGTH(cat) is not supported (functions are not available)",
        "SELECT SUM(*) FROM t                                           -> Aggregate argument is not supported: SUM(*)",
        "SELECT COUNT(id, cat) FROM t                                   -> Aggregate argument is not supported: COUNT(id, cat)",
        "SELECT MEDIAN(price) FROM t                                    -> Function or aggregate in SELECT list: MEDIAN(price) is not supported",
        "SELECT cat FROM t GROUP BY LOWER(cat)                          -> Function in GROUP BY: LOWER(cat) is not supported (functions are not available)",
        "SELECT cat FROM t GROUP BY COUNT(*) + 1                        -> Function in GROUP BY: COUNT(*) + 1 is not supported (an aggregate can't be used here)",
        "SELECT COUNT(*) + 1 AS n FROM t GROUP BY n                     -> Function in GROUP BY: COUNT(*) + 1 is not supported (an aggregate can't be used here)",
        "SELECT COUNT(*) AS n FROM t GROUP BY n                         -> GROUP BY cannot use an aggregate",
        "SELECT cat FROM t GROUP BY CAST(cat AS DATE)                   -> CAST to DATE in GROUP BY",
        "SELECT cat FROM t GROUP BY cat HAVING id > 1                   -> HAVING can only use grouped fields, aliases and aggregates, got: id",
        "SELECT cat FROM t GROUP BY cat HAVING id * 2 > 1               -> HAVING can only use grouped fields, aliases and aggregates, got: id",
        "SELECT cat FROM t GROUP BY cat HAVING LOWER(cat) = 'a'         -> Function in HAVING: LOWER(cat) = 'a' is not supported (functions are not available)",
        "SELECT cat FROM t GROUP BY cat HAVING SUM(COUNT(*)) > 1        -> Function in HAVING: SUM(COUNT(*)) > 1 is not supported (an aggregate can't be used here)",
        "SELECT cat FROM t GROUP BY cat HAVING COUNT(*)                 -> Condition in HAVING: COUNT(*) is not supported",
        "SELECT id FROM t HAVING id > 1                                 -> HAVING",
        "SELECT cat FROM t GROUP BY cat ORDER BY id                     -> ORDER BY can only use grouped fields, aliases and aggregates here, got: id",
        "SELECT cat FROM t GROUP BY cat ORDER BY id * 2                 -> ORDER BY can only use grouped fields, aliases and aggregates here, got: id",
        "SELECT cat FROM t GROUP BY cat ORDER BY LENGTH(cat)            -> Function in ORDER BY: LENGTH(cat) is not supported (functions are not available)",
        "SELECT id FROM t ORDER BY COUNT(*) + 1                         -> Function in ORDER BY: COUNT(*) + 1 is not supported (an aggregate can't be used here)",
        "SELECT id FROM t WHERE SUM(qty) * 2 > 1                        -> Function in WHERE: SUM(qty) * 2 > 1 is not supported (use HAVING to filter on an aggregate)",
    })
    void whatIsStillRejected(String sql, String message) {
        QueryParseException error = assertThrows(QueryParseException.class, () -> new QueryParser().parse(sql));

        assertTrue(error.getMessage().contains(message), error.getMessage());
    }
}
