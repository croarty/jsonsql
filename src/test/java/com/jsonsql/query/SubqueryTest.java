package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.CacheManager;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A subquery as a value, (SELECT ...), and after IN. Each runs once, before the rows of
 * the query that contains it, and can't refer to that query.
 */
class SubqueryTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> warnings = new ArrayList<>();
    private QueryExecutor executor;
    private MappingManager mappingManager;
    private File dataDir;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        mappingManager = new MappingManager(tempDir.resolve(".jsonsql-mappings.json").toFile());
        executor = new QueryExecutor(mappingManager, dataDir);
        executor.setWarningSink(warnings::add);

        table("products", """
            [{"id": 1, "name": "Widget", "cat": "Tools", "price": 10},
             {"id": 2, "name": "Gadget", "cat": "Toys", "price": 20},
             {"id": 3, "name": "Doohickey", "cat": "Tools", "price": 30},
             {"id": 4, "name": "Thing", "cat": "Toys", "price": null}]
            """);
        table("orders", """
            [{"oid": 100, "pid": 1, "qty": 2}, {"oid": 101, "pid": 3, "qty": 1},
             {"oid": 102, "pid": 9, "qty": "many"}, {"oid": 103, "pid": null, "qty": 4}]
            """);
        table("nums", "[{\"v\": 1}, {\"v\": 2}, {\"v\": null}, {\"v\": \"2\"}]");
        table("empty", "[]");
    }

    private void table(String name, String json) throws Exception {
        Files.writeString(dataDir.toPath().resolve(name + ".json"), json);
        mappingManager.addMapping(name, name + ".json:$[*]");
    }

    /** Each row as its values joined by ":", in result order. */
    private List<String> rows(String sql) throws Exception {
        return rows(executor, sql);
    }

    private List<String> rows(QueryExecutor runner, String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : objectMapper.readTree(runner.execute(sql))) {
            List<String> values = new ArrayList<>();
            row.forEach(value -> values.add(value.isNull() ? "null" : value.asText()));
            rows.add(String.join(":", values));
        }
        return rows;
    }

    // -- a scalar subquery in each clause -----------------------------------------------

    @Test
    void inWhere() throws Exception {
        assertEquals(List.of("Doohickey"), rows("SELECT name FROM products WHERE price > (SELECT AVG(price) FROM products)"));
    }

    @Test
    void inTheSelectList() throws Exception {
        assertEquals(List.of("Widget:30", "Gadget:30"),
            rows("SELECT name, (SELECT MAX(price) FROM products) AS top FROM products WHERE id < 3"));
        assertEquals("[{\"(SELECT MAX(price) FROM products)\":30}]",
            executor.execute("SELECT (SELECT MAX(price) FROM products) FROM products WHERE id = 1"));
    }

    @Test
    void inHaving() throws Exception {
        assertEquals(List.of("Tools:40"),
            rows("SELECT cat, SUM(price) AS s FROM products GROUP BY cat HAVING SUM(price) > (SELECT MIN(price) FROM products WHERE price > 15)"));
    }

    @Test
    void inOrderBy() throws Exception {
        assertEquals(List.of("Gadget", "Doohickey", "Widget"),
            rows("SELECT name FROM products WHERE price IS NOT NULL ORDER BY ABS(price - (SELECT AVG(price) FROM products)), name"));
    }

    @Test
    void inGroupBy() throws Exception {
        assertEquals(List.of("false:2", "true:1", "null:1"),
            rows("SELECT price > (SELECT AVG(price) FROM products) AS above, COUNT(*) AS n FROM products"
                + " GROUP BY price > (SELECT AVG(price) FROM products)"));
    }

    @Test
    void inJoinOn() throws Exception {
        assertEquals(List.of("100:Widget"),
            rows("SELECT o.oid, p.name FROM orders o JOIN products p ON o.pid = p.id AND p.price < (SELECT MAX(price) FROM products)"));
    }

    @Test
    void insideCaseCastFunctionsAndArithmetic() throws Exception {
        assertEquals(List.of("Widget:low", "Gadget:low", "Doohickey:high"),
            rows("SELECT name, CASE WHEN price > (SELECT AVG(price) FROM products) THEN 'high' ELSE 'low' END AS band"
                + " FROM products WHERE id < 4"));
        assertEquals(List.of("30!"), rows("SELECT CAST((SELECT MAX(price) FROM products) AS TEXT) || '!' AS t FROM products WHERE id = 1"));
        assertEquals(List.of("GADGET"), rows("SELECT UPPER((SELECT name FROM products WHERE id = 2)) AS u FROM products WHERE id = 1"));
        assertEquals(List.of("0", "10", "20"),
            rows("SELECT price - (SELECT MIN(price) FROM products) AS d FROM products WHERE id < 4"));
    }

    // -- how many rows it returns --------------------------------------------------------

    @Test
    void noRowsIsNull() throws Exception {
        assertEquals(List.of("Widget:null"), rows("SELECT name, (SELECT name FROM products WHERE id = 99) AS x FROM products WHERE id = 1"));
        assertEquals(List.of(), rows("SELECT name FROM products WHERE price > (SELECT price FROM products WHERE id = 99)"));
    }

    @Test
    void severalRowsIsAnError() {
        Exception error = assertThrows(QueryExecutionException.class,
            () -> executor.execute("SELECT name FROM products WHERE price > (SELECT price FROM products)"));
        assertEquals("Subquery returned 4 rows where one value is expected: (SELECT price FROM products)"
            + " (add LIMIT 1 or use an aggregate such as MAX)", error.getMessage());
    }

    @Test
    void itRunsEvenWhenNoRowNeedsIt() {
        assertThrows(QueryExecutionException.class,
            () -> executor.execute("SELECT v FROM empty WHERE v = (SELECT price FROM products)"));
    }

    // -- IN (SELECT ...) -------------------------------------------------------------

    @Test
    void inAndNotIn() throws Exception {
        assertEquals(List.of("Widget", "Doohickey"), rows("SELECT name FROM products WHERE id IN (SELECT pid FROM orders)"));
        assertEquals(List.of("Gadget", "Thing"),
            rows("SELECT name FROM products WHERE id NOT IN (SELECT pid FROM orders WHERE pid IS NOT NULL)"));
    }

    @Test
    void aNullInTheSubqueryMakesNotInUnknown() throws Exception {
        // Order 103 has a NULL pid, so NOT IN can't be true for any product
        assertEquals(List.of(), rows("SELECT name FROM products WHERE id NOT IN (SELECT pid FROM orders)"));
    }

    @Test
    void aNullOnTheLeftIsUnknown() throws Exception {
        assertEquals(List.of("100", "101"), rows("SELECT oid FROM orders WHERE pid IN (SELECT id FROM products)"));
        assertEquals(List.of("102"), rows("SELECT oid FROM orders WHERE pid NOT IN (SELECT id FROM products)"));
    }

    @Test
    void anEmptySubquery() throws Exception {
        assertEquals(List.of(), rows("SELECT name FROM products WHERE id IN (SELECT v FROM empty)"));
        assertEquals(List.of("Widget", "Gadget", "Doohickey", "Thing"),
            rows("SELECT name FROM products WHERE id NOT IN (SELECT v FROM empty)"));
    }

    @Test
    void numbersDontMatchText() throws Exception {
        assertEquals(List.of("1", "2"), rows("SELECT id FROM products WHERE id IN (SELECT v FROM nums)"));
        assertEquals(List.of("1", "2"), rows("SELECT v FROM nums WHERE v IN (SELECT id FROM products)"));
    }

    @Test
    void aScalarSubqueryInAWrittenList() throws Exception {
        assertEquals(List.of("1", "3"), rows("SELECT id FROM products WHERE id IN (3, (SELECT MIN(id) FROM products))"));
    }

    // -- what a subquery can be --------------------------------------------------------

    @Test
    void nested() throws Exception {
        assertEquals(List.of("Widget"),
            rows("SELECT name FROM products WHERE id IN (SELECT pid FROM orders WHERE qty > (SELECT MIN(qty) FROM orders))"));
    }

    @Test
    void aSetOperationInside() throws Exception {
        assertEquals(List.of("Widget", "Gadget", "Doohickey"),
            rows("SELECT name FROM products WHERE id IN (SELECT pid FROM orders UNION SELECT 2 FROM products)"));
        assertEquals(List.of("Doohickey"),
            rows("SELECT name FROM products WHERE price = (SELECT MAX(price) FROM products UNION SELECT MAX(price) FROM products)"));
    }

    @Test
    void readingACte() throws Exception {
        assertEquals(List.of("Widget", "Doohickey"),
            rows("WITH t AS (SELECT id FROM products WHERE cat = 'Tools') SELECT name FROM products WHERE id IN (SELECT id FROM t)"));
    }

    @Test
    void insideACteBodyWithAndWithoutTheCache() throws Exception {
        String sql = "WITH x AS (SELECT name FROM products WHERE price > (SELECT AVG(price) FROM products)) SELECT name FROM x";
        assertEquals(List.of("Doohickey"), rows(sql));
        QueryExecutor cached = new QueryExecutor(mappingManager, dataDir, new CacheManager(dataDir));
        assertEquals(List.of("Doohickey"), rows(cached, sql));
        assertEquals(List.of("Doohickey"), rows(cached, sql));
    }

    @Test
    void changingTheSubquerysSourceInvalidatesTheCachedCte() throws Exception {
        QueryExecutor cached = new QueryExecutor(mappingManager, dataDir, new CacheManager(dataDir));
        String sql = "WITH x AS (SELECT name FROM products WHERE id IN (SELECT pid FROM orders)) SELECT name FROM x";
        assertEquals(List.of("Widget", "Doohickey"), rows(cached, sql));

        table("orders", "[{\"oid\": 200, \"pid\": 2}, {\"oid\": 201, \"pid\": 4}, {\"oid\": 202, \"pid\": 4}]");
        assertEquals(List.of("Gadget", "Thing"), rows(cached, sql));
    }

    @Test
    void inASetOperationBranch() throws Exception {
        assertEquals(List.of("Widget", "Doohickey"),
            rows("SELECT name FROM products WHERE id IN (SELECT pid FROM orders)"
                + " UNION SELECT name FROM products WHERE price = (SELECT MAX(price) FROM products)"));
    }

    @Test
    void insideADerivedTable() throws Exception {
        assertEquals(List.of("Widget"),
            rows("SELECT t.name FROM (SELECT name FROM products WHERE price < (SELECT AVG(price) FROM products)) t"));
    }

    // -- names -----------------------------------------------------------------------

    @Test
    void namesOfItsOwnTablesAreAllowed() throws Exception {
        assertEquals(List.of("Widget"),
            rows("SELECT name FROM products p WHERE id IN (SELECT o.pid FROM orders o WHERE o.qty > 1)"));
        // The subquery's own p, not the outer one
        assertEquals(List.of("Gadget", "Thing"),
            rows("SELECT p.name FROM products p WHERE p.id IN (SELECT p.id FROM products p WHERE p.cat = 'Toys')"));
        // meta is not a table of the outer query, so meta.flag is a nested field of orders
        assertEquals(List.of("Widget", "Doohickey"),
            rows("SELECT name FROM products p WHERE id IN (SELECT pid FROM orders WHERE meta.flag IS NULL)"));
    }

    @Test
    void anUnprefixedNameThatOnlyTheOuterQueryHasReadsNull() throws Exception {
        // There is no schema: name belongs to orders, which has none, so it reads null and nothing matches
        assertEquals(List.of(), rows("SELECT name FROM products p WHERE id IN (SELECT pid FROM orders WHERE name = 'Widget')"));
    }

    // -- warnings ---------------------------------------------------------------------

    @Test
    void aWarningFromTheSubqueryAppearsOnce() throws Exception {
        // products has four rows; the subquery runs once
        assertEquals(List.of(), rows("SELECT name FROM products WHERE id = (SELECT MAX(qty * 2) FROM orders)"));
        assertEquals(List.of("qty * 2 left out 1 value that is not a number: \"many\""), warnings);
    }

    @Test
    void aWarningEvenWhenNoRowNeedsTheSubquery() throws Exception {
        assertEquals(List.of(), rows("SELECT v FROM empty WHERE v = (SELECT MAX(qty * 2) FROM orders)"));
        assertEquals(1, warnings.size());
    }

    @Test
    void aWarningFromTheSubqueryIsAnErrorInStrictMode() {
        executor.setStrict(true);
        StrictModeException error = assertThrows(StrictModeException.class,
            () -> executor.execute("SELECT name FROM products WHERE id IN (SELECT qty * 2 FROM orders)"));
        assertEquals("qty * 2 found 1 value that is not a number: \"many\"", error.getMessage());
    }
}
