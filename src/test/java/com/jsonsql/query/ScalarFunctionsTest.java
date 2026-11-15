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
 * The functions that work on one value at a time: UPPER, LOWER, LENGTH, SUBSTR, TRIM,
 * REPLACE, ROUND, ABS, COALESCE, IFNULL and NULLIF.
 */
class ScalarFunctionsTest {

    private static final String ROWS = """
        [
          {"id": 1, "name": "Widget",   "price": 10,     "qty": 3,  "cat": "Tools", "note": "  padded  ", "code": "xxabcxx", "ok": true},
          {"id": 2, "name": "gadget",   "price": 20.567, "qty": -4, "cat": null,    "note": "",           "code": "abc"},
          {"id": 3, "name": "Ünï çødé", "price": "none", "qty": 0,  "cat": "Toys"}
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
        mappings = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        mappings.addMapping("t", "t.json:$[*]");
        executor = new QueryExecutor(mappings, tempDir);
        executor.setWarningSink(warnings::add);
    }

    /** The value of an expression for each of the three rows, in id order. */
    private String each(String expression) throws Exception {
        String json = executor.execute("SELECT " + expression + " AS v FROM t ORDER BY id");
        return json.replace("[{\"v\":", "").replace("},{\"v\":", " ; ").replace("}]", "");
    }

    @ParameterizedTest(name = "{0} gives {1}")
    @CsvSource(delimiterString = "->", quoteCharacter = '`', value = {
        // Case and length. Numbers and booleans are used as they print
        "UPPER(name)                 -> \"WIDGET\" ; \"GADGET\" ; \"ÜNÏ ÇØDÉ\"",
        "LOWER(name)                 -> \"widget\" ; \"gadget\" ; \"ünï çødé\"",
        "upper(name)                 -> \"WIDGET\" ; \"GADGET\" ; \"ÜNÏ ÇØDÉ\"",
        "UPPER(cat)                  -> \"TOOLS\" ; null ; \"TOYS\"",
        "UPPER(qty)                  -> \"3\" ; \"-4\" ; \"0\"",
        "UPPER(ok)                   -> \"TRUE\" ; null ; null",
        "LENGTH(name)                -> 6 ; 6 ; 8",
        "LENGTH(note)                -> 10 ; 0 ; null",
        "LENGTH(price)               -> 2 ; 6 ; 4",
        "LENGTH('😀a')               -> 2 ; 2 ; 2",
        // SUBSTR counts from 1, and from the end when the start is negative
        "SUBSTR(name, 1, 3)          -> \"Wid\" ; \"gad\" ; \"Ünï\"",
        "SUBSTR(name, 4)             -> \"get\" ; \"get\" ; \" çødé\"",
        "SUBSTR(name, -3)            -> \"get\" ; \"get\" ; \"ødé\"",
        "SUBSTR(name, -3, 2)         -> \"ge\" ; \"ge\" ; \"ød\"",
        "SUBSTR(name, 0, 2)          -> \"W\" ; \"g\" ; \"Ü\"",
        "SUBSTR(name, 2, -1)         -> \"W\" ; \"g\" ; \"Ü\"",
        "SUBSTR('Widget', -100, 96)  -> \"Wi\" ; \"Wi\" ; \"Wi\"",
        "SUBSTR('Widget', -100, 102) -> \"Widget\" ; \"Widget\" ; \"Widget\"",
        "SUBSTR(name, 10)            -> \"\" ; \"\" ; \"\"",
        "SUBSTR(name, 5, 100)        -> \"et\" ; \"et\" ; \"çødé\"",
        "SUBSTR(name, 3, 0)          -> \"\" ; \"\" ; \"\"",
        "SUBSTRING(name, 2, 2)       -> \"id\" ; \"ad\" ; \"nï\"",
        "SUBSTR(name, 2.9, 1.9)      -> \"i\" ; \"a\" ; \"n\"",
        "SUBSTR(name, qty)           -> \"dget\" ; \"dget\" ; \"Ünï çødé\"",
        "SUBSTR(cat, 1, 1)           -> \"T\" ; null ; \"T\"",
        "SUBSTR(name, NULL)          -> null ; null ; null",
        "SUBSTR(name, 1, NULL)       -> null ; null ; null",
        "SUBSTR(12345, 2, 3)         -> \"234\" ; \"234\" ; \"234\"",
        "SUBSTR('😀ab', 2, 1)        -> \"a\" ; \"a\" ; \"a\"",
        "SUBSTR(name, 9223372036854775807) -> \"\" ; \"\" ; \"\"",
        "SUBSTR(name, -9223372036854775808, 9223372036854775807) -> \"\" ; \"\" ; \"\"",
        // TRIM removes spaces, or the characters given
        "TRIM(note)                  -> \"padded\" ; \"\" ; null",
        "LTRIM(note)                 -> \"padded  \" ; \"\" ; null",
        "RTRIM(note)                 -> \"  padded\" ; \"\" ; null",
        "TRIM(code, 'x')             -> \"abc\" ; \"abc\" ; null",
        "LTRIM(code, 'x')            -> \"abcxx\" ; \"abc\" ; null",
        "RTRIM(code, 'xc')           -> \"xxab\" ; \"ab\" ; null",
        "TRIM(BOTH 'x' FROM code)    -> \"abc\" ; \"abc\" ; null",
        "TRIM(LEADING 'x' FROM code) -> \"abcxx\" ; \"abc\" ; null",
        "TRIM(TRAILING 'x' FROM code) -> \"xxabc\" ; \"abc\" ; null",
        "TRIM(code, '')              -> \"xxabcxx\" ; \"abc\" ; null",
        "TRIM(code, NULL)            -> null ; null ; null",
        "TRIM('xxx', 'x')            -> \"\" ; \"\" ; \"\"",
        // REPLACE
        "REPLACE(code, 'x', '-')     -> \"--abc--\" ; \"abc\" ; null",
        "REPLACE(code, 'abc', '')    -> \"xxxx\" ; \"\" ; null",
        "REPLACE(code, '', 'z')      -> \"xxabcxx\" ; \"abc\" ; null",
        "REPLACE(name, 'g', 'G')     -> \"WidGet\" ; \"GadGet\" ; \"Ünï çødé\"",
        "REPLACE(name, 'g', NULL)    -> null ; null ; null",
        "REPLACE(qty, '-', 'minus ') -> \"3\" ; \"minus 4\" ; \"0\"",
        // ROUND gives a decimal, and rounds a half away from zero
        "ROUND(price)                -> 10.0 ; 21.0 ; null",
        "ROUND(price, 2)             -> 10.0 ; 20.57 ; null",
        "ROUND(price, 1)             -> 10.0 ; 20.6 ; null",
        "ROUND(price, 50)            -> 10.0 ; 20.567 ; null",
        "ROUND(qty)                  -> 3.0 ; -4.0 ; 0.0",
        "ROUND(2.5)                  -> 3.0 ; 3.0 ; 3.0",
        "ROUND(-2.5)                 -> -3.0 ; -3.0 ; -3.0",
        "ROUND(3.5)                  -> 4.0 ; 4.0 ; 4.0",
        "ROUND(2.675, 2)             -> 2.68 ; 2.68 ; 2.68",
        "ROUND(1234.5678, -2)        -> 1235.0 ; 1235.0 ; 1235.0",
        "ROUND(0.0004, 2)            -> 0.0 ; 0.0 ; 0.0",
        "ROUND(2.567, 1.9)           -> 2.6 ; 2.6 ; 2.6",
        "ROUND(price, NULL)          -> null ; null ; null",
        "ROUND(12345678901234567890.125, 2) -> 12345678901234567890.13 ; 12345678901234567890.13 ; 12345678901234567890.13",
        // ABS keeps a whole number whole
        "ABS(qty)                    -> 3 ; 4 ; 0",
        "ABS(price * -1)             -> 10 ; 20.567 ; null",
        "ABS(-2.5)                   -> 2.5 ; 2.5 ; 2.5",
        "ABS(-9223372036854775808)   -> 9223372036854775808 ; 9223372036854775808 ; 9223372036854775808",
        "ABS(NULL)                   -> null ; null ; null",
        // COALESCE, IFNULL and NULLIF
        "COALESCE(cat, 'none')       -> \"Tools\" ; \"none\" ; \"Toys\"",
        "COALESCE(note, cat, name)   -> \"  padded  \" ; \"\" ; \"Toys\"",
        "COALESCE(NULL, NULL, 5)     -> 5 ; 5 ; 5",
        "COALESCE(cat, note)         -> \"Tools\" ; \"\" ; \"Toys\"",
        "COALESCE(NULL, cat)         -> \"Tools\" ; null ; \"Toys\"",
        "IFNULL(cat, 'x')            -> \"Tools\" ; \"x\" ; \"Toys\"",
        "IFNULL(qty, 99)             -> 3 ; -4 ; 0",
        "NULLIF(qty, 0)              -> 3 ; -4 ; null",
        "NULLIF(cat, 'Tools')        -> null ; null ; \"Toys\"",
        "NULLIF(qty, NULL)           -> 3 ; -4 ; 0",
        "NULLIF(qty, 3.0)            -> null ; -4 ; 0",
        "NULLIF(qty, '3')            -> 3 ; -4 ; 0",
        // Together, and with other calculations
        "UPPER(SUBSTR(name, 1, 1)) || LOWER(SUBSTR(name, 2)) -> \"Widget\" ; \"Gadget\" ; \"Ünï çødé\"",
        "ROUND(price * 1.1, 1)       -> 11.0 ; 22.6 ; null",
        "COALESCE(ROUND(price), 0)   -> 10.0 ; 21.0 ; 0",
        "LENGTH(TRIM(note))          -> 6 ; 0 ; null",
        "CASE WHEN LENGTH(name) > 6 THEN 'long' ELSE 'short' END -> \"short\" ; \"short\" ; \"long\"",
        "CAST(ROUND(price) AS INTEGER) -> 10 ; 21 ; null",
        "LENGTH(name) * 2 + ABS(qty) -> 15 ; 16 ; 16",
        "ROUND(qty / 3, 3)           -> 1.0 ; -1.333 ; 0.0",
        "LENGTH(name) > 6            -> false ; false ; true",
    })
    void function(String expression, String values) throws Exception {
        assertEquals(values, each(expression));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = "->", quoteCharacter = '`', value = {
        // WHERE and ORDER BY
        "SELECT id FROM t WHERE UPPER(name) = 'WIDGET' -> [{\"id\":1}]",
        "SELECT id FROM t WHERE LENGTH(name) > 6 -> [{\"id\":3}]",
        "SELECT id FROM t WHERE COALESCE(cat, 'x') = 'x' -> [{\"id\":2}]",
        "SELECT id FROM t WHERE ROUND(price) = 21 -> [{\"id\":2}]",
        "SELECT id FROM t WHERE LOWER(name) LIKE 'g%' -> [{\"id\":2}]",
        "SELECT id FROM t WHERE SUBSTR(name, 1, 1) IN ('W', 'g') ORDER BY id -> [{\"id\":1},{\"id\":2}]",
        "SELECT id FROM t WHERE NULLIF(qty, 0) IS NULL -> [{\"id\":3}]",
        "SELECT id FROM t WHERE ABS(qty) BETWEEN 3 AND 4 ORDER BY id -> [{\"id\":1},{\"id\":2}]",
        "SELECT id FROM t WHERE TRIM(note) = 'padded' -> [{\"id\":1}]",
        "SELECT id FROM t ORDER BY LOWER(name) -> [{\"id\":2},{\"id\":1},{\"id\":3}]",
        "SELECT id FROM t ORDER BY LENGTH(name) DESC, id -> [{\"id\":3},{\"id\":1},{\"id\":2}]",
        "SELECT id FROM t ORDER BY ABS(qty) -> [{\"id\":3},{\"id\":1},{\"id\":2}]",
        // An unnamed column is named after the call
        "SELECT UPPER(name), LENGTH(name) FROM t WHERE id = 1 -> [{\"UPPER(name)\":\"WIDGET\",\"LENGTH(name)\":6}]",
        "SELECT TRIM(note), LENGTH(TRIM(code, 'x')) + 1, TRIM(LEADING 'x' FROM code), 'Trim( x )', (TRIM(note)) FROM t WHERE id = 1 -> [{\"TRIM(note)\":\"padded\",\"LENGTH(TRIM(code, 'x')) + 1\":4,\"TRIM(LEADING 'x' FROM code)\":\"abcxx\",\"'Trim( x )'\":\"Trim( x )\",\"(TRIM(note))\":\"padded\"}]",
        // With grouping: inside aggregates, around them, and in GROUP BY and HAVING
        "SELECT UPPER(cat) AS c, COUNT(*) AS n FROM t GROUP BY UPPER(cat) ORDER BY c -> [{\"c\":null,\"n\":1},{\"c\":\"TOOLS\",\"n\":1},{\"c\":\"TOYS\",\"n\":1}]",
        "SELECT LENGTH(name) AS len, COUNT(*) AS n FROM t GROUP BY len ORDER BY len -> [{\"len\":6,\"n\":2},{\"len\":8,\"n\":1}]",
        "SELECT ROUND(AVG(qty), 1) AS a FROM t -> [{\"a\":-0.3}]",
        "SELECT SUM(ABS(qty)) AS s, MAX(LENGTH(name)) AS longest FROM t -> [{\"s\":7,\"longest\":8}]",
        "SELECT cat FROM t GROUP BY cat HAVING LENGTH(cat) > 4 -> [{\"cat\":\"Tools\"}]",
        "SELECT cat FROM t GROUP BY cat HAVING ROUND(SUM(qty)) = 3 -> [{\"cat\":\"Tools\"}]",
        "SELECT COALESCE(SUM(qty), 0) AS s FROM t WHERE id > 99 -> [{\"s\":0}]",
        "SELECT COALESCE(cat, 'none') AS c, SUM(qty) AS s FROM t GROUP BY cat ORDER BY ABS(SUM(qty)) -> [{\"c\":\"Toys\",\"s\":0},{\"c\":\"Tools\",\"s\":3},{\"c\":\"none\",\"s\":-4}]",
        "SELECT UPPER(COUNT(*)) AS n FROM t -> [{\"n\":\"3\"}]",
        "WITH c AS (SELECT id, UPPER(name) AS big FROM t) SELECT LOWER(big) AS small FROM c WHERE LENGTH(big) = 8 -> [{\"small\":\"ünï çødé\"}]",
    })
    void query(String sql, String json) throws Exception {
        assertEquals(json, executor.execute(sql));
    }

    @Test
    void aNumberFunctionOnTextWarnsAndGivesNull() throws Exception {
        assertEquals("10.0 ; 21.0 ; null", each("ROUND(price)"));
        assertEquals(List.of("ROUND(price) left out 1 value that is not a number: \"none\""), warnings);

        warnings.clear();
        assertEquals("null ; null ; null", each("ABS(name)"));
        assertEquals(List.of("ABS(name) left out 3 values that are not numbers, such as \"Widget\""), warnings);
    }

    @Test
    void aTextStartOrLengthWarnsAndGivesNull() throws Exception {
        assertEquals("null ; null ; null", each("SUBSTR(name, cat)"));
        assertEquals(List.of("SUBSTR(name, cat) left out 2 values that are not numbers, such as \"Tools\""), warnings);

        warnings.clear();
        assertEquals("null ; null ; null", each("ROUND(qty, 'two')"));
        assertEquals(List.of("ROUND(qty, 'two') left out 3 values that are not numbers, such as \"two\""), warnings);
    }

    @Test
    void textFunctionsAndNullsGiveNoWarning() throws Exception {
        executor.execute("SELECT UPPER(qty), LENGTH(price), TRIM(cat), REPLACE(qty, '3', 'x'), ROUND(NULL), ABS(cat || NULL), "
            + "COALESCE(price, 0), NULLIF(price, 'none'), ROUND(CAST(price AS REAL)) FROM t");

        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void warningsWorkInEveryClause() throws Exception {
        executor.execute("SELECT id FROM t WHERE ROUND(price) > 1");
        executor.execute("SELECT id FROM t ORDER BY ABS(price)");
        executor.execute("SELECT SUM(ROUND(price)) AS s FROM t");
        executor.execute("SELECT cat FROM t GROUP BY cat HAVING ABS(MAX(name)) > 1");

        assertEquals(List.of(
            "ROUND(price) left out 1 value that is not a number: \"none\"",
            "ABS(price) left out 1 value that is not a number: \"none\"",
            "ROUND(price) left out 1 value that is not a number: \"none\"",
            "ABS(MAX(name)) left out 3 values that are not numbers, such as \"Widget\""), warnings);
    }

    @Test
    void strictModeFailsInsteadOfWarning() throws Exception {
        executor.setStrict(true);

        Exception error = assertThrows(Exception.class, () -> each("ROUND(price)"));

        assertEquals("ROUND(price) found 1 value that is not a number: \"none\"", error.getMessage());
        assertTrue(warnings.isEmpty());
        assertEquals("\"WIDGET\" ; \"GADGET\" ; \"ÜNÏ ÇØDÉ\"", each("UPPER(name)"));
        assertEquals("3.0 ; -4.0 ; 0.0", each("ROUND(qty)"));
    }

    @Test
    void differentCallsDoNotShareACachedResult() throws Exception {
        QueryExecutor cached = new QueryExecutor(mappings, tempDir, new CacheManager(tempDir));

        for (int run = 0; run < 2; run++) {
            assertEquals("[{\"v\":\"WIDGET\"}]", cached.execute("WITH c AS (SELECT UPPER(name) AS v FROM t WHERE id = 1) SELECT v FROM c"));
            assertEquals("[{\"v\":\"widget\"}]", cached.execute("WITH c AS (SELECT LOWER(name) AS v FROM t WHERE id = 1) SELECT v FROM c"));
            assertEquals("[{\"v\":\"Wi\"}]", cached.execute("WITH c AS (SELECT SUBSTR(name, 1, 2) AS v FROM t WHERE id = 1) SELECT v FROM c"));
            assertEquals("[{\"v\":\"Wid\"}]", cached.execute("WITH c AS (SELECT SUBSTR(name, 1, 3) AS v FROM t WHERE id = 1) SELECT v FROM c"));
        }
    }

    @Test
    void enormousNumbersDoNotStallRoundOrAbs() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), "[{\"id\": 1, \"big\": 1e999999999, \"tiny\": -1e-999999999}]");

        String result = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> executor.execute(
            "SELECT ROUND(big, 2) AS a, ROUND(tiny, 2) AS b, ABS(tiny) AS c, ABS(big) AS d, SUBSTR('abc', big) AS e FROM t"));

        assertEquals("[{\"a\":1E+999999999,\"b\":0.0,\"c\":1E-999999999,\"d\":1E+999999999,\"e\":\"\"}]", result);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiterString = "->", quoteCharacter = '`', value = {
        "SELECT SOUNDEX(name) FROM t                   -> Function or aggregate in SELECT list: SOUNDEX(name) is not supported (available functions: ABS, COALESCE, IFNULL, LENGTH, LOWER, LTRIM, NULLIF, REPLACE, ROUND, RTRIM, SUBSTR, SUBSTRING, TRIM, UPPER)",
        "SELECT id FROM t WHERE CONCAT(name, cat) = 'x' -> Function in WHERE: CONCAT(name, cat) = 'x' is not supported (available functions: ABS,",
        "SELECT id FROM t ORDER BY LEFT(name, 2)       -> Function in ORDER BY: LEFT(name, 2) is not supported (available functions: ABS,",
        "SELECT x.UPPER(name) FROM t                   -> Function or aggregate in SELECT list: x.UPPER(name) is not supported",
        "SELECT UPPER() FROM t                         -> UPPER takes 1 argument, got 0: UPPER()",
        "SELECT UPPER(name, cat) FROM t                -> UPPER takes 1 argument, got 2: UPPER(name, cat)",
        "SELECT SUBSTR(name) FROM t                    -> SUBSTR takes 2 or 3 arguments, got 1: SUBSTR(name)",
        "SELECT SUBSTRING(name FROM 1 FOR 3) FROM t    -> SUBSTRING takes 2 or 3 arguments, got 0",
        "SELECT COALESCE(name) FROM t                  -> COALESCE takes 2 or more arguments, got 1: COALESCE(name)",
        "SELECT ROUND(price, 1, 2) FROM t              -> ROUND takes 1 or 2 arguments, got 3: ROUND(price, 1, 2)",
        "SELECT REPLACE(name, 'a') FROM t              -> REPLACE takes 3 arguments, got 2: REPLACE(name, 'a')",
        "SELECT id FROM t WHERE LENGTH(name, 1) > 2    -> LENGTH takes 1 argument, got 2: LENGTH(name, 1)",
        "SELECT UPPER(DISTINCT name) FROM t            -> DISTINCT in UPPER is not supported (DISTINCT belongs inside an aggregate)",
        "SELECT UPPER(*) FROM t                        -> Expression in SELECT list: UPPER(*) is not supported",
        "SELECT UPPER(SOUNDEX(name)) FROM t            -> Function or aggregate in SELECT list: UPPER(SOUNDEX(name)) is not supported (available functions:",
        "SELECT UPPER(name) FROM t GROUP BY cat        -> Column 'name' must be in GROUP BY or inside an aggregate",
        "SELECT cat FROM t GROUP BY cat HAVING LENGTH(name) > 1 -> HAVING can only use grouped fields, aliases and aggregates, got: name",
        "SELECT SUM(UPPER(COUNT(*))) FROM t            -> Function or aggregate in SELECT list: SUM(UPPER(COUNT(*))) is not supported (an aggregate can't be used here)",
        "SELECT id FROM t WHERE UPPER(MAX(name)) = 'X' -> Function in WHERE: UPPER(MAX(name)) = 'X' is not supported (use HAVING to filter on an aggregate)",
        "SELECT id FROM t UNION SELECT id FROM t ORDER BY ABS(id) -> Expression in ORDER BY: ABS(id) is not supported",
    })
    void whatIsRejected(String sql, String message) {
        QueryParseException error = assertThrows(QueryParseException.class, () -> new QueryParser().parse(sql));

        assertTrue(error.getMessage().contains(message), error.getMessage());
    }
}
