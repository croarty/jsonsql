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
 * JOIN ... ON takes any condition WHERE takes. A pair of rows joins only when the whole
 * condition is true, and LEFT JOIN keeps a left row that no right row passes.
 */
class JoinOnConditionTest {

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
            [{"id": 1, "pid": 1, "region": "EU", "status": "open", "code": "ab", "qty": 2},
             {"id": 2, "pid": 1, "region": "US", "status": "done", "code": "CD", "qty": 5},
             {"id": 3, "pid": 2, "region": "EU", "status": "open", "code": "x", "qty": "many"},
             {"id": 4, "pid": null, "region": "EU", "status": "open"},
             {"id": 5, "region": "US", "status": "open"},
             {"id": 6, "pid": 3, "region": null}]
            """);
        table("products", """
            [{"id": 1, "region": "EU", "name": "Widget EU", "price": 10, "code": "AB"},
             {"id": 1, "region": "US", "name": "Widget US", "price": 12, "code": "cd"},
             {"id": 2, "region": "EU", "name": "Gadget", "price": 25, "code": "X"},
             {"id": 3, "region": null, "name": "Nothing", "price": 40}]
            """);
        table("bands", """
            [{"band": "low", "low": 0, "high": 15}, {"band": "high", "low": 15, "high": 100}]
            """);
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

    @Test
    void twoKeys() throws Exception {
        assertEquals(List.of("1:Widget EU", "2:Widget US", "3:Gadget"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.pid = p.id AND o.region = p.region"));
    }

    @Test
    void theOrderOfTheKeysDoesNotMatter() throws Exception {
        assertEquals(List.of("1:Widget EU", "2:Widget US", "3:Gadget"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON p.region = o.region AND p.id = o.pid"));
    }

    @Test
    void anUnprefixedNameOppositeAnEarlierTableIsReadFromTheJoinedTable() throws Exception {
        List<String> expected = List.of("1:Widget EU", "1:Widget US", "2:Widget EU", "2:Widget US", "3:Gadget", "6:Nothing");
        assertEquals(expected, rows("SELECT o.id, p.name FROM orders o JOIN products p ON id = o.pid"));
        assertEquals(expected, rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.pid = id"));
        assertEquals(expected, rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.pid = p.id"));
    }

    @Test
    void aKeyAndAFilterOnTheJoinedTable() throws Exception {
        assertEquals(List.of("1:Widget US", "2:Widget US", "3:Gadget", "6:Nothing"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.pid = p.id AND p.price > 11"));
    }

    @Test
    void aKeyAndAFilterOnTheLeftTable() throws Exception {
        assertEquals(List.of("1:Widget EU", "1:Widget US", "3:Gadget"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.pid = p.id AND o.status = 'open'"));
    }

    @Test
    void leftJoinWithAConditionOnTheLeftTableKeepsEveryLeftRow() throws Exception {
        assertEquals(List.of("1:Widget EU", "1:Widget US", "2:null", "3:Gadget", "4:null", "5:null", "6:null"),
            rows("SELECT o.id, p.name FROM orders o LEFT JOIN products p ON o.pid = p.id AND o.status = 'open'"));
    }

    @Test
    void leftJoinIsNotTheSameAsFilteringAfterwards() throws Exception {
        assertEquals(List.of("1:Widget EU", "1:Widget US", "3:Gadget"),
            rows("SELECT o.id, p.name FROM orders o LEFT JOIN products p ON o.pid = p.id WHERE o.status = 'open'"
                + " AND p.name IS NOT NULL"));
        assertEquals(List.of("1:Widget US", "2:Widget US", "3:Gadget", "4:null", "5:null", "6:Nothing"),
            rows("SELECT o.id, p.name FROM orders o LEFT JOIN products p ON o.pid = p.id AND p.price > 11"));
    }

    @Test
    void aRangeJoinWithNoKey() throws Exception {
        assertEquals(List.of("Widget EU:low", "Widget US:low", "Gadget:high", "Nothing:high"),
            rows("SELECT p.name, b.band FROM products p JOIN bands b ON p.price >= b.low AND p.price < b.high"));
    }

    @Test
    void betweenInAndLike() throws Exception {
        assertEquals(List.of("Widget EU:low", "Widget US:low"),
            rows("SELECT p.name, b.band FROM products p JOIN bands b"
                + " ON p.price BETWEEN b.low AND b.high AND b.band IN ('low', 'mid') AND p.name LIKE 'W%'"));
    }

    @Test
    void orWithANullKey() throws Exception {
        // Order 4 has a NULL pid: NULL = p.id is unknown, but unknown OR TRUE is TRUE
        assertEquals(List.of("1:Widget EU", "1:Widget US", "1:Nothing", "2:Widget EU", "2:Widget US", "2:Nothing",
                "3:Gadget", "3:Nothing", "4:Nothing", "5:Nothing", "6:Nothing"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.pid = p.id OR p.id = 3"));
    }

    @Test
    void notInOn() throws Exception {
        assertEquals(List.of("1:Widget US", "2:Widget EU"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.pid = p.id AND NOT (o.region = p.region)"));
    }

    @Test
    void aFunctionOnBothSides() throws Exception {
        assertEquals(List.of("1:Widget EU", "2:Widget US", "3:Gadget"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON LOWER(o.code) = LOWER(p.code)"));
    }

    @Test
    void arithmeticAndCase() throws Exception {
        assertEquals(List.of("1:Gadget", "2:Gadget", "3:Nothing"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.pid + 1 = p.id"));
        assertEquals(List.of("1:Widget EU", "2:Widget EU", "3:Gadget"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p"
                + " ON o.pid = p.id AND p.region = CASE WHEN o.pid = 1 THEN 'EU' ELSE p.region END"));
    }

    @Test
    void literalsInTheCondition() throws Exception {
        table("flags", """
            [{"pid": 1, "flag": true, "tag": "yes"}, {"pid": 1, "flag": false, "tag": "no"}, {"pid": 2, "flag": "true", "tag": "text"}]
            """);
        assertEquals(List.of("1:yes", "2:yes", "3:text"),
            rows("SELECT o.id, f.tag FROM orders o JOIN flags f ON o.pid = f.pid AND f.flag = true"));
        assertEquals(List.of("1:no", "2:no"),
            rows("SELECT o.id, f.tag FROM orders o JOIN flags f ON o.pid = f.pid AND false = f.flag"));
        assertEquals(List.of("1:yes", "1:no", "3:text"),
            rows("SELECT o.id, f.tag FROM orders o JOIN flags f ON o.pid = f.pid AND o.status = \"open\" AND o.region = 'EU'"));
    }

    @Test
    void quotedNamesAreFields() throws Exception {
        assertEquals(List.of("1:Widget EU", "2:Widget US", "3:Gadget"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON \"o\".\"pid\" = \"p\".\"id\" AND o.region = p.region"));
    }

    @Test
    void aCastInTheCondition() throws Exception {
        assertEquals(List.of("1:Widget EU", "1:Widget US"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON CAST(o.qty AS TEXT) = '2' AND o.pid = p.id"));
    }

    @Test
    void nullAndMissingKeysNeverJoin() throws Exception {
        // Order 4 has a NULL pid, order 5 has none, and order 6 and product 3 have a NULL region
        assertEquals(List.of("1:Widget EU", "2:Widget US", "3:Gadget"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.region = p.region AND o.pid = p.id"));
        assertEquals(List.of("6:Nothing"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.pid = p.id AND p.id = 3"));
    }

    @Test
    void aConditionOnlyOnTheJoinedTableFiltersIt() throws Exception {
        table("pairs", """
            [{"a": 1, "b": 1, "name": "same"}, {"a": 1, "b": 2, "name": "different"}, {"a": null, "b": null, "name": "nulls"}]
            """);
        assertEquals(List.of("1:same", "2:same"),
            rows("SELECT o.id, x.name FROM orders o JOIN pairs x ON x.a = x.b WHERE o.id <= 2"));
    }

    @Test
    void aConditionOnlyOnTheLeftTableKeepsOrDropsWholeRows() throws Exception {
        assertEquals(List.of("low:0", "high:0"),
            rows("SELECT b.band, x.low FROM bands b JOIN bands x ON b.low = b.low AND x.low = 0"));
    }

    @Test
    void threeTablesWhereTheSecondOnNamesBothEarlierTables() throws Exception {
        assertEquals(List.of("1:Widget EU:low", "2:Widget US:low", "3:Gadget:high"),
            rows("SELECT o.id, p.name, b.band FROM orders o JOIN products p ON o.pid = p.id"
                + " JOIN bands b ON o.region = p.region AND p.price >= b.low AND p.price < b.high"));
    }

    @Test
    void unnestThenJoin() throws Exception {
        table("baskets", """
            [{"basket": "A", "items": [1, 2, 9]}, {"basket": "B", "items": [3]}]
            """);
        assertEquals(List.of("A:Widget EU", "A:Gadget"),
            rows("SELECT b.basket, p.name FROM baskets b, UNNEST(b.items) AS i(item)"
                + " JOIN products p ON item = p.id AND p.region = 'EU'"));
    }

    @Test
    void numbersJoinEqualNumbersButNotText() throws Exception {
        table("left_keys", """
            [{"k1": 1, "k2": 2.0, "name": "numbers"}, {"k1": "1", "k2": 2, "name": "text"}]
            """);
        table("right_keys", """
            [{"k1": 1.0, "k2": 2, "tag": "a"}, {"k1": "1", "k2": 2.00, "tag": "b"}, {"k1": 1, "k2": "2", "tag": "c"}]
            """);
        assertEquals(List.of("numbers:a", "text:b"),
            rows("SELECT l.name, r.tag FROM left_keys l JOIN right_keys r ON l.k1 = r.k1 AND l.k2 = r.k2"));
    }

    @Test
    void textInArithmeticIsAWarning() throws Exception {
        assertEquals(List.of("1:Widget EU", "1:Widget US", "2:Widget EU", "2:Widget US"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.pid = p.id AND o.qty * 2 > 1"));
        assertEquals(List.of("o.qty * 2 left out 1 value that is not a number: \"many\""), warnings);
    }

    @Test
    void textInArithmeticIsAnErrorInStrictMode() {
        executor.setStrict(true);

        Exception error = assertThrows(StrictModeException.class, () -> executor.execute(
            "SELECT o.id, p.name FROM orders o JOIN products p ON o.pid = p.id AND o.qty * 2 > 1"));

        assertEquals("o.qty * 2 found 1 value that is not a number: \"many\"", error.getMessage());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void selfJoinWithAliases() throws Exception {
        table("staff", """
            [{"id": 1, "name": "Ann", "boss": null, "level": 3},
             {"id": 2, "name": "Bob", "boss": 1, "level": 2},
             {"id": 3, "name": "Cy", "boss": 1, "level": 4},
             {"id": 4, "name": "Di", "boss": 2, "level": 1}]
            """);
        assertEquals(List.of("Bob:Ann", "Di:Bob"),
            rows("SELECT e.name, m.name AS boss FROM staff e JOIN staff m ON e.boss = m.id AND m.level > e.level"));
    }

    @Test
    void rowsKeepTheLeftOrderThenTheJoinedFileOrder() throws Exception {
        assertEquals(List.of("6:Nothing", "6:Gadget", "6:Widget US", "6:Widget EU",
                "1:Nothing", "1:Gadget", "1:Widget US", "1:Widget EU"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.id IN (1, 6) AND p.price > 0"
                + " ORDER BY o.id DESC, p.price DESC"));
        assertEquals(List.of("1:Widget EU", "1:Widget US", "1:Gadget", "1:Nothing",
                "6:Widget EU", "6:Widget US", "6:Gadget", "6:Nothing"),
            rows("SELECT o.id, p.name FROM orders o JOIN products p ON o.id IN (1, 6) AND p.price > 0"));
    }

    @Test
    void cteWithAJoinConditionIsChecked() throws Exception {
        assertEquals(List.of("1:Widget US", "2:Widget US", "3:Gadget", "6:Nothing"),
            rows("WITH j AS (SELECT o.id AS id, p.name AS name FROM orders o JOIN products p"
                + " ON o.pid = p.id AND p.price > 11) SELECT id, name FROM j"));
    }
}
