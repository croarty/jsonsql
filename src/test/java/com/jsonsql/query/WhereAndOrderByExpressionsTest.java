package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Calculations in WHERE and ORDER BY: arithmetic, text joined with ||, CASE and CAST, on
 * either side of a condition and as a sort key.
 */
class WhereAndOrderByExpressionsTest {

    private static final String ROWS = """
        [
          {"id": 1, "name": "Widget", "price": 10,     "qty": 3, "cat": "Tools", "size": "15.6 inch", "info": {"w": 2.5}},
          {"id": 2, "name": "Gadget", "price": 20.5,   "qty": 0, "cat": null,    "size": "7 inch",    "info": {"w": 4}},
          {"id": 3, "name": "Thing",  "price": "none", "qty": 7, "cat": "Toys",  "size": "n/a"},
          {"id": 4, "name": "Zed",    "price": 5,      "qty": 2, "cat": "Tools", "size": "100 inch"}
        ]
        """;

    @TempDir
    File tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
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
        Files.writeString(new File(tempDir, "l.json").toPath(), "[{\"lid\": 1, \"list\": [1, 2, 3]}]");
        mappings.addMapping("l", "l.json:$[*]");
        executor = new QueryExecutor(mappings, tempDir);
        executor.setWarningSink(warnings::add);
    }

    private String ids(String sql) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode row : mapper.readTree(executor.execute(sql))) {
            ids.add(row.get("id").asText());
        }
        return String.join(",", ids);
    }

    @ParameterizedTest(name = "WHERE {0} returns ids {1}")
    @CsvSource(delimiterString = "->", quoteCharacter = '`', value = {
        // Arithmetic on either side
        "price * qty > 20                  -> 1",
        "price * 2 = 20                    -> 1",
        "20 = price * 2                    -> 1",
        "price + 1 > 20                    -> 2",
        "qty - 1 >= price / 5              -> 1,4",
        "price * qty = qty * price         -> 1,2,4",
        "qty % 2 = 1                       -> 1,3",
        "-qty < -2                         -> 1,3",
        "(price + qty) * 2 BETWEEN 14 AND 30 -> 1,4",
        "qty BETWEEN price / 5 AND price   -> 1,4",
        "qty * 2 IN (6, 14)                -> 1,3",
        "qty * 2 NOT IN (6, 14)            -> 2,4",
        "qty IN (price - 7, 7)             -> 1,3",
        "info.w * 2 = 5                    -> 1",
        // NULL and text in arithmetic never match
        "price * 2 > 0                     -> 1,2,4",
        "NOT (price * 2 > 0)               -> ``",
        "price / qty > 1                   -> 1,4",
        "price / qty IS NULL               -> 2,3",
        "price + 1 IS NOT NULL             -> 1,2,4",
        "cat || 'x' IS NULL                -> 2",
        // Text
        "name || '-' || cat = 'Widget-Tools' -> 1",
        "name || qty LIKE '%t3'            -> 1",
        "cat || name LIKE 'Tools%'         -> 1,4",
        "name || '' NOT LIKE 'W%'          -> 2,3,4",
        "name || cat IN ('ZedTools', 'x')  -> 4",
        // CASE
        "CASE WHEN qty > 2 THEN 'many' ELSE 'few' END = 'many' -> 1,3",
        "CASE cat WHEN 'Tools' THEN price ELSE 0 END > 6       -> 1",
        "price > CASE WHEN qty > 2 THEN 8 ELSE 100 END         -> 1,3",
        // CAST
        "CAST(size AS REAL) > 10           -> 1,4",
        "CAST(size AS INTEGER) = 15        -> 1",
        "CAST(size AS REAL) IS NULL        -> 3",
        "CAST(price AS REAL) > 6           -> 1,2",
        "CAST(qty AS TEXT) = '3'           -> 1",
        "CAST(qty AS TEXT) LIKE '_'        -> 1,2,3,4",
        "CAST(size AS REAL) BETWEEN 7 AND 16 -> 1,2",
        "CAST(size AS INTEGER) IN (7, 100) -> 2,4",
        // Combined with ordinary conditions
        "cat = 'Tools' AND price * qty > 20  -> 1",
        "cat = 'Toys' OR price * qty = 10    -> 3,4",
        "NOT (qty * 2 > 5) AND price > 1     -> 2,4",
        // A condition as a value. Text sorts after numbers, so "none" > 6 is true
        "(qty > 2) = (price > 6)           -> 1,3,4",
    })
    void where(String condition, String ids) throws Exception {
        assertEquals(ids, ids("SELECT id FROM t WHERE " + condition + " ORDER BY id"));
    }

    @ParameterizedTest(name = "ORDER BY {0} gives ids {1}")
    @CsvSource(delimiterString = "->", quoteCharacter = '`', value = {
        "price * qty, id                   -> 3,2,4,1",
        "price * qty DESC, id              -> 1,4,2,3",
        "qty * -1                          -> 3,1,4,2",
        "qty % 2, id DESC                  -> 4,2,3,1",
        "CAST(size AS REAL)                -> 3,2,1,4",
        "CAST(size AS REAL) DESC           -> 4,1,2,3",
        "CASE WHEN cat = 'Tools' THEN 0 ELSE 1 END, price -> 4,1,2,3",
        "CASE cat WHEN 'Toys' THEN 1 WHEN 'Tools' THEN 2 END, id -> 2,3,1,4",
        "name || cat                       -> 2,3,1,4",
        "cat || name, id                   -> 2,1,4,3",
        "qty > 2, id                       -> 2,4,1,3",
        "info.w * 2, id                    -> 3,4,1,2",
        "(qty + 1) * 2 DESC                -> 3,1,4,2",
        "cat, qty * 2 DESC                 -> 2,1,4,3",
    })
    void orderBy(String orderBy, String ids) throws Exception {
        assertEquals(ids, ids("SELECT id FROM t ORDER BY " + orderBy));
    }

    @Test
    void orderByDoesNotAddTheSortKeyToTheOutput() throws Exception {
        assertEquals("[{\"id\":4},{\"id\":1}]", executor.execute("SELECT id FROM t WHERE cat = 'Tools' ORDER BY price * qty"));
        assertEquals("[{\"id\":4,\"name\":\"Zed\",\"price\":5,\"qty\":2,\"cat\":\"Tools\",\"size\":\"100 inch\"}]",
            executor.execute("SELECT * FROM t WHERE id = 4 ORDER BY price * qty"));
        assertEquals("[{\"oid\":101,\"tid\":2,\"n\":2},{\"oid\":100,\"tid\":1,\"n\":4}]", executor.execute("SELECT * FROM o ORDER BY n * 10"));
    }

    @Test
    void orderByCanUseASelectAliasInsideACalculation() throws Exception {
        assertEquals("3,1,4,2", ids("SELECT id, qty * 2 AS doubled FROM t ORDER BY doubled * -1"));
        assertEquals("3,1,4,2", ids("SELECT id, qty AS q FROM t ORDER BY q * -1"));
        assertEquals("2,4,1,3", ids("SELECT id, qty * 2 AS doubled FROM t ORDER BY DOUBLED + 1"));
        // An alias takes priority over a field of the same name, as it does for a plain ORDER BY
        assertEquals("3,1,4,2", ids("SELECT id, qty * -1 AS price FROM t ORDER BY price + 0"));
    }

    @Test
    void worksWithDistinctLimitJoinsCtesAndUnnest() throws Exception {
        assertEquals("[{\"cat\":\"Toys\"},{\"cat\":null},{\"cat\":\"Tools\"}]",
            executor.execute("SELECT DISTINCT cat FROM t ORDER BY CASE WHEN cat = 'Tools' THEN 1 ELSE 0 END, cat DESC"));
        assertEquals("1,4", ids("SELECT id FROM t ORDER BY price * qty DESC LIMIT 2"));
        assertEquals("4", ids("SELECT id FROM t ORDER BY price * qty DESC LIMIT 1 OFFSET 1"));
        assertEquals("[{\"oid\":101},{\"oid\":100}]",
            executor.execute("SELECT o.oid FROM o JOIN t ON o.tid = t.id WHERE o.n * t.price > 30 ORDER BY o.n * t.price DESC"));
        assertEquals("1", ids("WITH big AS (SELECT id, price FROM t WHERE price * qty > 20) SELECT id FROM big WHERE price + 1 = 11"));
        assertEquals("[{\"v\":3},{\"v\":2}]",
            executor.execute("SELECT v FROM l, UNNEST(list) AS x(v) WHERE v * 2 > 3 ORDER BY v * -1"));
    }

    @Test
    void aSelectAliasIsNotVisibleInWhere() throws Exception {
        // As in SQL, WHERE runs before SELECT, so doubled is looked up as a field
        assertEquals("", ids("SELECT id, qty * 2 AS doubled FROM t WHERE doubled > 1"));
    }

    @Test
    void arithmeticOnTextInWhereWarnsOncePerCalculation() throws Exception {
        assertEquals("1,2", ids("SELECT id FROM t WHERE price * 2 > 15 OR price + qty > 100 ORDER BY id"));

        assertEquals(List.of(
            "price * 2 left out 1 value that is not a number: \"none\"",
            "price + qty left out 1 value that is not a number: \"none\""), warnings);
    }

    @Test
    void arithmeticOnTextInOrderByWarns() throws Exception {
        assertEquals("3,4,1,2", ids("SELECT id FROM t ORDER BY price * 2"));
        assertEquals(List.of("price * 2 left out 1 value that is not a number: \"none\""), warnings);
    }

    @Test
    void theWarningNamesTheCalculationThatMetTheText() throws Exception {
        ids("SELECT id FROM t WHERE (name * 2) + 1 > 0 AND cat = 'Tools'");

        assertEquals(List.of("name * 2 left out 4 values that are not numbers, such as \"Widget\""), warnings);
    }

    @Test
    void castAndNullGiveNoWarning() throws Exception {
        ids("SELECT id FROM t WHERE CAST(size AS REAL) > 1 AND info.w * 2 > 1 AND cat || 'x' = 'Toolsx' ORDER BY CAST(price AS REAL)");

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void strictModeFailsInsteadOfWarning() {
        executor.setStrict(true);

        Exception inWhere = assertThrows(Exception.class, () -> ids("SELECT id FROM t WHERE price * 2 > 15"));
        assertEquals("price * 2 found 1 value that is not a number: \"none\"", inWhere.getMessage());

        Exception inOrderBy = assertThrows(Exception.class, () -> ids("SELECT id FROM t ORDER BY price + 1"));
        assertEquals("price + 1 found 1 value that is not a number: \"none\"", inOrderBy.getMessage());

        assertTrue(warnings.isEmpty());
    }

    @Test
    void strictModeAllowsCleanCalculations() throws Exception {
        executor.setStrict(true);

        assertEquals("1,3", ids("SELECT id FROM t WHERE qty * 2 > 5 ORDER BY qty + 1"));
    }

    @Test
    void differentCalculationsDoNotShareACachedResult() throws Exception {
        QueryExecutor cached = new QueryExecutor(mappings, tempDir, new CacheManager(tempDir));

        for (int run = 0; run < 2; run++) {
            assertEquals("[{\"id\":3}]", cached.execute("WITH c AS (SELECT id FROM t ORDER BY qty * -1 LIMIT 1) SELECT id FROM c"));
            assertEquals("[{\"id\":2}]", cached.execute("WITH c AS (SELECT id FROM t ORDER BY qty * 1 LIMIT 1) SELECT id FROM c"));
            assertEquals("[{\"id\":1}]", cached.execute("WITH c AS (SELECT id FROM t WHERE qty * 2 = 6) SELECT id FROM c"));
            assertEquals("[{\"id\":3}]", cached.execute("WITH c AS (SELECT id FROM t WHERE qty * 2 = 14) SELECT id FROM c"));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiterString = "->", quoteCharacter = '`', value = {
        "SELECT id FROM t WHERE UPPER(name) = 'X'              -> Function in WHERE: UPPER(name) = 'X' is not supported",
        "SELECT id FROM t WHERE price * LENGTH(name) > 1       -> Function in WHERE: price * LENGTH(name) > 1 is not supported",
        "SELECT id FROM t WHERE LOWER(name) LIKE 'w%'          -> Function in WHERE: LOWER(name) LIKE 'w%' is not supported",
        "SELECT id FROM t WHERE COUNT(*) > 1                   -> Function in WHERE: COUNT(*) > 1 is not supported (use HAVING to filter on an aggregate)",
        "SELECT id FROM t WHERE SUM(price) * 2 > 1             -> Function in WHERE: SUM(price) * 2 > 1 is not supported (use HAVING to filter on an aggregate)",
        "SELECT id FROM t WHERE CAST(price AS DATE) > 1        -> CAST to DATE in WHERE: CAST(price AS DATE) > 1 is not supported (use INTEGER, REAL or TEXT)",
        "SELECT id FROM t WHERE price > (SELECT 1)             -> Expression in WHERE: price > (SELECT 1) is not supported",
        "SELECT id FROM t ORDER BY UPPER(name)                 -> Function in ORDER BY: UPPER(name) is not supported",
        "SELECT id FROM t ORDER BY price * LENGTH(name)        -> Function in ORDER BY: price * LENGTH(name) is not supported",
        "SELECT id FROM t ORDER BY CAST(price AS DATE)         -> CAST to DATE in ORDER BY: CAST(price AS DATE) is not supported (use INTEGER, REAL or TEXT)",
        "SELECT id FROM t ORDER BY (SELECT 1)                  -> Expression in ORDER BY: (SELECT 1) is not supported",
        "SELECT cat FROM t GROUP BY cat ORDER BY COUNT(*) + 1  -> Expression in ORDER BY: COUNT(*) + 1 is not supported",
        "SELECT cat FROM t GROUP BY cat ORDER BY cat || 'x'    -> Expression in ORDER BY: cat || 'x' is not supported",
        "SELECT id FROM t UNION SELECT oid FROM o ORDER BY id * 2 -> Expression in ORDER BY: id * 2 is not supported",
    })
    void whatIsStillRejected(String sql, String message) {
        QueryParseException error = assertThrows(QueryParseException.class, () -> new QueryParser().parse(sql));

        assertTrue(error.getMessage().contains(message), error.getMessage());
    }
}
