package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * RIGHT JOIN keeps every row of the joined table, FULL JOIN keeps every row of both
 * sides, and CROSS JOIN (or a comma) pairs every row with every row.
 */
class OuterAndCrossJoinTest {

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

        table("orders", """
            [{"id": 1, "pid": 1, "qty": 2}, {"id": 2, "pid": 2, "qty": "lots"}, {"id": 3, "pid": 9, "qty": 1},
             {"id": 4, "pid": null, "qty": 5}]
            """);
        table("products", """
            [{"id": 1, "name": "A", "cat": "x"}, {"id": 2, "name": "B", "cat": "y"}, {"id": 3, "name": "C", "cat": "x"},
             {"id": null, "name": "D"}]
            """);
        table("stock", """
            [{"pid": 1, "shop": "north"}, {"pid": 3, "shop": "south"}]
            """);
        table("empty", "[]");
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

    // -- RIGHT JOIN ----------------------------------------------------------------

    @Test
    void rightJoinKeepsEveryJoinedRowAfterTheMatches() throws Exception {
        assertEquals(List.of("1:A", "2:B", "null:C", "null:D"),
            rows("SELECT o.id, p.name FROM orders o RIGHT JOIN products p ON o.pid = p.id"));
    }

    @Test
    void rightOuterJoinIsTheSame() throws Exception {
        assertEquals(List.of("1:A", "2:B", "null:C", "null:D"),
            rows("SELECT o.id, p.name FROM orders o RIGHT OUTER JOIN products p ON o.pid = p.id"));
    }

    @Test
    void rightJoinWithAConditionOnTheLeftTable() throws Exception {
        // Product A's only order fails the condition, so A comes out unmatched, in file order
        assertEquals(List.of("2:B", "null:A", "null:C", "null:D"),
            rows("SELECT o.id, p.name FROM orders o RIGHT JOIN products p ON o.pid = p.id AND o.id > 1"));
    }

    @Test
    void rightJoinWithNoKey() throws Exception {
        assertEquals(List.of("3:A", "3:B", "3:C", "null:D"),
            rows("SELECT o.id, p.name FROM orders o RIGHT JOIN products p ON o.pid > p.id + 5"));
    }

    @Test
    void rightJoinFindsTheJoinedRowsWithNoMatch() throws Exception {
        assertEquals(List.of("C", "D"),
            rows("SELECT p.name FROM orders o RIGHT JOIN products p ON o.pid = p.id WHERE o.id IS NULL"));
    }

    @Test
    void anUnmatchedJoinedRowHasOnlyItsOwnFields() throws Exception {
        assertEquals("[{\"id\":3,\"name\":\"C\",\"cat\":\"x\"}]",
            executor.execute("SELECT * FROM orders o RIGHT JOIN products p ON o.pid = p.id WHERE p.id = 3"));
        assertEquals(List.of("C:x"),
            rows("SELECT name, cat FROM orders o RIGHT JOIN products p ON o.pid = p.id WHERE p.id = 3"));
    }

    @Test
    void rightJoinAfterAnInnerJoinKeepsTheThirdTable() throws Exception {
        assertEquals(List.of("1:A:north", "null:null:south"),
            rows("SELECT o.id, p.name, s.shop FROM orders o JOIN products p ON o.pid = p.id"
                + " RIGHT JOIN stock s ON s.pid = p.id"));
    }

    @Test
    void leftJoinAfterARightJoin() throws Exception {
        assertEquals(List.of("1:A:north", "2:B:null", "null:C:south", "null:D:null"),
            rows("SELECT o.id, p.name, s.shop FROM orders o RIGHT JOIN products p ON o.pid = p.id"
                + " LEFT JOIN stock s ON s.pid = p.id"));
    }

    @Test
    void rightJoinToAnEmptyTableIsEmpty() throws Exception {
        assertEquals(List.of(), rows("SELECT o.id FROM orders o RIGHT JOIN empty e ON o.pid = e.id"));
        assertEquals(List.of("null", "null"), rows("SELECT e.id FROM empty e RIGHT JOIN stock s ON e.id = s.pid"));
    }

    @Test
    void textInArithmeticInARightJoinIsAWarning() throws Exception {
        assertEquals(List.of("1:A", "null:B", "null:C", "null:D"),
            rows("SELECT o.id, p.name FROM orders o RIGHT JOIN products p ON o.pid = p.id AND o.qty * 2 > 0"));
        assertEquals(List.of("o.qty * 2 left out 1 value that is not a number: \"lots\""), warnings);
    }

    // -- FULL JOIN -----------------------------------------------------------------

    @Test
    void fullJoinKeepsEveryRowOfBothSides() throws Exception {
        assertEquals(List.of("1:A", "2:B", "3:null", "4:null", "null:C", "null:D"),
            rows("SELECT o.id, p.name FROM orders o FULL JOIN products p ON o.pid = p.id"));
        assertEquals(List.of("1:A", "2:B", "3:null", "4:null", "null:C", "null:D"),
            rows("SELECT o.id, p.name FROM orders o FULL OUTER JOIN products p ON o.pid = p.id"));
    }

    @Test
    void fullJoinWithAConditionOnOneSide() throws Exception {
        assertEquals(List.of("1:null", "2:B", "3:null", "4:null", "null:A", "null:C", "null:D"),
            rows("SELECT o.id, p.name FROM orders o FULL JOIN products p ON o.pid = p.id AND p.cat = 'y'"));
    }

    @Test
    void fullJoinCounts() throws Exception {
        assertEquals("[{\"n\":6,\"with_order\":4,\"with_product\":3}]",
            executor.execute("SELECT COUNT(*) AS n, COUNT(o.id) AS with_order, COUNT(p.id) AS with_product"
                + " FROM orders o FULL JOIN products p ON o.pid = p.id"));
    }

    @Test
    void fullJoinWithAnEmptySide() throws Exception {
        assertEquals(List.of("1:null", "3:null"), rows("SELECT s.pid, e.id FROM stock s FULL JOIN empty e ON s.pid = e.id"));
        assertEquals(List.of("null:1", "null:3"), rows("SELECT e.id, s.pid FROM empty e FULL JOIN stock s ON s.pid = e.id"));
    }

    // -- CROSS JOIN and commas -------------------------------------------------------

    @Test
    void crossJoinPairsEveryRowWithEveryRow() throws Exception {
        assertEquals(List.of("1:north", "1:south", "2:north", "2:south", "3:north", "3:south", "4:north", "4:south"),
            rows("SELECT o.id, s.shop FROM orders o CROSS JOIN stock s"));
    }

    @Test
    void aCommaIsACrossJoin() throws Exception {
        assertEquals(rows("SELECT o.id, s.shop FROM orders o CROSS JOIN stock s"),
            rows("SELECT o.id, s.shop FROM orders o, stock s"));
        assertEquals(List.of("1:A", "2:B"),
            rows("SELECT o.id, p.name FROM orders o, products p WHERE o.pid = p.id"));
    }

    @Test
    void threeTablesWithCommas() throws Exception {
        assertEquals(List.of("1:A:north"),
            rows("SELECT o.id, p.name, s.shop FROM orders o, products p, stock s WHERE o.pid = p.id AND s.pid = p.id"));
    }

    @Test
    void crossJoinWithAnEmptyTableIsEmpty() throws Exception {
        assertEquals(List.of(), rows("SELECT o.id FROM orders o CROSS JOIN empty e"));
        assertEquals(List.of(), rows("SELECT o.id FROM empty e, orders o"));
    }

    @Test
    void crossJoinAfterUnnest() throws Exception {
        table("baskets", """
            [{"basket": "X", "items": [1, 3]}]
            """);
        assertEquals(List.of("X:1:north", "X:3:south"),
            rows("SELECT b.basket, item, s.shop FROM baskets b, UNNEST(b.items) AS i(item), stock s WHERE s.pid = item"));
    }

    @Test
    void crossJoinThenAKeyedJoin() throws Exception {
        assertEquals(List.of("north:1:A", "south:1:A"),
            rows("SELECT s.shop, o.id, p.name FROM stock s CROSS JOIN orders o JOIN products p ON o.pid = p.id AND o.id = 1"));
    }

    @Test
    void cteWithEachJoinType() throws Exception {
        assertEquals(List.of("1:A", "2:B", "null:C", "null:D"),
            rows("WITH j AS (SELECT o.id AS oid, p.name AS name FROM orders o RIGHT JOIN products p ON o.pid = p.id)"
                + " SELECT oid, name FROM j"));
        assertEquals(List.of("8"),
            rows("WITH j AS (SELECT o.id FROM orders o, stock s) SELECT COUNT(*) AS n FROM j"));
    }
}
