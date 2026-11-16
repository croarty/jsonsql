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
 * FROM (SELECT ...) t and JOIN (SELECT ...) t use the subquery's result as a table named t.
 */
class DerivedTableTest {

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
        table("orders", "[{\"oid\": 100, \"pid\": 1, \"qty\": 2}, {\"oid\": 101, \"pid\": 3, \"qty\": 1}, {\"oid\": 102, \"pid\": 9, \"qty\": \"many\"}]");
        table("a", "[{\"n\": 1}, {\"n\": 2}, {\"n\": 3}]");
        table("b", "[{\"n\": 3}, {\"n\": 4}]");
        table("c", "[{\"n\": 2}, {\"n\": 4}, {\"n\": 5}]");
    }

    private void table(String name, String json) throws Exception {
        Files.writeString(dataDir.toPath().resolve(name + ".json"), json);
        mappingManager.addMapping(name, name + ".json:$[*]");
    }

    /** Each row as its values joined by ":", in result order. */
    private List<String> rows(String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        for (JsonNode row : objectMapper.readTree(executor.execute(sql))) {
            List<String> values = new ArrayList<>();
            row.forEach(value -> values.add(value.isNull() ? "null" : value.asText()));
            rows.add(String.join(":", values));
        }
        return rows;
    }

    // -- in FROM ----------------------------------------------------------------------

    @Test
    void inFrom() throws Exception {
        assertEquals(List.of("Gadget:20", "Doohickey:30"),
            rows("SELECT t.name, t.price FROM (SELECT name, price FROM products WHERE price > 15) t"));
        assertEquals(List.of("Gadget", "Doohickey"),
            rows("SELECT name FROM (SELECT name, price FROM products WHERE price > 15) AS t"));
        assertEquals(List.of("Doohickey"),
            rows("SELECT t.name FROM (SELECT name, price FROM products) t WHERE t.price > 25"));
    }

    @Test
    void selectStarOverADerivedTable() throws Exception {
        assertEquals("[{\"id\":1,\"label\":\"WIDGET\"},{\"id\":2,\"label\":\"GADGET\"}]",
            executor.execute("SELECT * FROM (SELECT id, UPPER(name) AS label FROM products WHERE id < 3) t"));
    }

    @Test
    void anEmptyDerivedTable() throws Exception {
        assertEquals("[]", executor.execute("SELECT name FROM (SELECT name FROM products WHERE price > 1000) t"));
        assertEquals(List.of("100:null", "101:null", "102:null"),
            rows("SELECT o.oid, t.name FROM orders o LEFT JOIN (SELECT id, name FROM products WHERE price > 1000) t ON o.pid = t.id"));
    }

    @Test
    void orderByAndLimitInside() throws Exception {
        assertEquals(List.of("Gadget", "Widget"),
            rows("SELECT name FROM (SELECT name, price FROM products WHERE price < 25 ORDER BY price DESC LIMIT 2) t"));
        assertEquals(List.of("Widget", "Gadget"),
            rows("SELECT name FROM (SELECT name, price FROM products WHERE price < 25 ORDER BY price DESC LIMIT 2) t ORDER BY price"));
    }

    @Test
    void groupingInsideAndOutside() throws Exception {
        assertEquals(List.of("Toys:1"),
            rows("SELECT t.cat, COUNT(*) AS n FROM (SELECT cat FROM products WHERE price > 15) t WHERE t.cat = 'Toys' GROUP BY t.cat"));
        assertEquals(List.of("2"),
            rows("SELECT COUNT(*) AS n FROM (SELECT cat, COUNT(*) AS k FROM products GROUP BY cat) t"));
    }

    @Test
    void nestedTwoDeep() throws Exception {
        assertEquals(List.of("Doohickey"),
            rows("SELECT name FROM (SELECT name, price FROM (SELECT name, price FROM products WHERE cat = 'Tools') x"
                + " WHERE price > 15) y"));
    }

    // -- in joins ---------------------------------------------------------------------

    @Test
    void inEachJoinType() throws Exception {
        String derived = " (SELECT id, name FROM products WHERE price > 15) t";
        assertEquals(List.of("101:Doohickey"), rows("SELECT o.oid, t.name FROM orders o JOIN" + derived + " ON o.pid = t.id"));
        assertEquals(List.of("100:null", "101:Doohickey", "102:null"),
            rows("SELECT o.oid, t.name FROM orders o LEFT JOIN" + derived + " ON o.pid = t.id"));
        assertEquals(List.of("101:Doohickey", "null:Gadget"),
            rows("SELECT o.oid, t.name FROM orders o RIGHT JOIN" + derived + " ON o.pid = t.id"));
        assertEquals(List.of("100:null", "101:Doohickey", "102:null", "null:Gadget"),
            rows("SELECT o.oid, t.name FROM orders o FULL JOIN" + derived + " ON o.pid = t.id"));
        assertEquals(6, rows("SELECT o.oid, t.name FROM orders o CROSS JOIN" + derived).size());
        assertEquals(6, rows("SELECT o.oid, t.name FROM orders o," + derived).size());
    }

    @Test
    void bothSidesDerived() throws Exception {
        assertEquals(List.of("Widget:2", "Doohickey:1"),
            rows("SELECT p.name, q.qty FROM (SELECT id, name FROM products WHERE cat = 'Tools') p"
                + " JOIN (SELECT pid, qty FROM orders) q ON q.pid = p.id"));
    }

    @Test
    void aGroupedSubqueryJoinedBackToItsTable() throws Exception {
        assertEquals(List.of("Widget:10:20.0", "Gadget:20:20.0", "Doohickey:30:20.0"),
            rows("SELECT p.name, p.price, a.avg_price FROM products p"
                + " JOIN (SELECT cat, AVG(price) AS avg_price FROM products WHERE id < 4 GROUP BY cat) a ON p.cat = a.cat"
                + " WHERE p.id < 4"));
    }

    @Test
    void theSameSubqueryTwiceUnderTwoNames() throws Exception {
        assertEquals(List.of("Widget:Doohickey"),
            rows("SELECT x.name, y.name AS other FROM (SELECT id, name, cat FROM products) x"
                + " JOIN (SELECT id, name, cat FROM products) y ON x.cat = y.cat AND x.id < y.id WHERE x.cat = 'Tools'"));
    }

    // -- set operations and CTEs -----------------------------------------------------

    @Test
    void aSetOperationInside() throws Exception {
        assertEquals(List.of("1", "2", "3", "4"), rows("SELECT n FROM (SELECT n FROM a UNION SELECT n FROM b) t"));
        // (A UNION B) INTERSECT C, which parentheses can't express
        assertEquals(List.of("2", "4"),
            rows("SELECT * FROM (SELECT n FROM a UNION SELECT n FROM b) t INTERSECT SELECT n FROM c"));
        assertEquals(List.of("4", "3"),
            rows("SELECT n FROM (SELECT n FROM a UNION SELECT n FROM b ORDER BY n DESC LIMIT 2) t"));
        assertEquals(List.of("3:3"),
            rows("SELECT a.n, t.m FROM a JOIN (SELECT n AS m FROM b EXCEPT SELECT n FROM c) t ON a.n = t.m"));
    }

    @Test
    void readingACte() throws Exception {
        assertEquals(List.of("3"),
            rows("WITH x AS (SELECT n FROM a WHERE n > 1) SELECT t.n FROM (SELECT n FROM x WHERE n > 2) t"));
    }

    @Test
    void insideACteBodyWithAndWithoutTheCache() throws Exception {
        String sql = "WITH x AS (SELECT t.n FROM (SELECT n FROM a WHERE n > 1) t) SELECT n FROM x";
        assertEquals(List.of("2", "3"), rows(sql));

        QueryExecutor cached = new QueryExecutor(mappingManager, dataDir, new CacheManager(dataDir));
        assertEquals(executor.execute(sql), cached.execute(sql));
        assertEquals(executor.execute(sql), cached.execute(sql));
    }

    @Test
    void changingTheFileBehindADerivedTableInvalidatesTheCachedCte() throws Exception {
        QueryExecutor cached = new QueryExecutor(mappingManager, dataDir, new CacheManager(dataDir));
        String sql = "WITH x AS (SELECT t.n FROM (SELECT n FROM b) t) SELECT n FROM x";
        assertEquals("[{\"n\":3},{\"n\":4}]", cached.execute(sql));

        table("b", "[{\"n\": 30}, {\"n\": 40}, {\"n\": 50}]");
        assertEquals("[{\"n\":30},{\"n\":40},{\"n\":50}]", cached.execute(sql));
    }

    @Test
    void changingTheFileBehindAJoinedSetOperationInvalidatesTheCachedCte() throws Exception {
        QueryExecutor cached = new QueryExecutor(mappingManager, dataDir, new CacheManager(dataDir));
        String sql = "WITH x AS (SELECT a.n FROM a JOIN (SELECT n FROM b UNION SELECT n FROM c) t ON a.n = t.n) SELECT n FROM x";
        assertEquals("[{\"n\":2},{\"n\":3}]", cached.execute(sql));

        table("c", "[{\"n\": 1}]");
        assertEquals("[{\"n\":1},{\"n\":3}]", cached.execute(sql));
    }

    // -- what a derived table can't see ----------------------------------------------

    @Test
    void aDerivedTableCantSeeTheOuterQuerysTables() throws Exception {
        // It runs on its own, so p.name inside it names nothing and reads null
        assertEquals(List.of("null", "null", "null", "null"),
            rows("SELECT t.x FROM products p CROSS JOIN (SELECT p.name AS x FROM orders WHERE oid = 100) t"));
    }

    // -- warnings ---------------------------------------------------------------------

    @Test
    void aWarningFromTheSubqueryAppearsOnce() throws Exception {
        assertEquals(List.of("4", "2", "null"), rows("SELECT d FROM (SELECT qty * 2 AS d FROM orders) t"));
        assertEquals(List.of("qty * 2 left out 1 value that is not a number: \"many\""), warnings);
    }

    @Test
    void aWarningFromTheSubqueryIsAnErrorInStrictMode() {
        executor.setStrict(true);
        StrictModeException error = assertThrows(StrictModeException.class,
            () -> executor.execute("SELECT p.name FROM products p JOIN (SELECT pid, qty * 2 AS d FROM orders) t ON p.id = t.pid"));
        assertEquals("qty * 2 found 1 value that is not a number: \"many\"", error.getMessage());
    }
}
