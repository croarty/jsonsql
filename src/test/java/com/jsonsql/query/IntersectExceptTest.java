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
 * INTERSECT keeps the rows of the left side that are also in the right, and EXCEPT those
 * that are not, with duplicates removed. Rows match as they do for UNION and DISTINCT.
 */
class IntersectExceptTest {

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

        table("a", "[{\"n\": 1}, {\"n\": 2}, {\"n\": 3}, {\"n\": 4}, {\"n\": 5}, {\"n\": 6}, {\"n\": 2}]");
        table("b", "[{\"n\": 2}, {\"n\": 3}, {\"n\": 4}, {\"n\": 3}]");
        table("c", "[{\"n\": 3}, {\"n\": 4}, {\"n\": 5}]");
        table("empty", "[]");
        table("p", """
            [{"id": 1, "name": "Widget", "cat": "Tools", "price": 10},
             {"id": 2, "name": "Gadget", "cat": "Toys", "price": 20},
             {"id": 3, "name": "Doohickey", "cat": "Tools", "price": 30}]
            """);
        table("o", "[{\"oid\": 100, \"pid\": 1}, {\"oid\": 101, \"pid\": 3}, {\"oid\": 102, \"pid\": 3}]");
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

    // -- the basics ------------------------------------------------------------------

    @Test
    void intersectKeepsTheLeftRowsThatAreAlsoOnTheRight() throws Exception {
        assertEquals(List.of("2", "3", "4"), rows("SELECT n FROM a INTERSECT SELECT n FROM b"));
    }

    @Test
    void exceptKeepsTheLeftRowsThatAreNotOnTheRight() throws Exception {
        assertEquals(List.of("1", "5", "6"), rows("SELECT n FROM a EXCEPT SELECT n FROM b"));
    }

    @Test
    void duplicatesOnEitherSideAreRemoved() throws Exception {
        assertEquals(List.of("2", "3", "4"), rows("SELECT n FROM b INTERSECT SELECT n FROM b"));
        assertEquals(List.of("2", "3", "4"), rows("SELECT n FROM b EXCEPT SELECT n FROM empty"));
        assertEquals(List.of("1", "2", "6"), rows("SELECT n FROM a EXCEPT SELECT n FROM c"));
    }

    @Test
    void emptySidesNoOverlapAndFullOverlap() throws Exception {
        assertEquals(List.of(), rows("SELECT n FROM empty INTERSECT SELECT n FROM a"));
        assertEquals(List.of(), rows("SELECT n FROM empty EXCEPT SELECT n FROM a"));
        assertEquals(List.of(), rows("SELECT n FROM a INTERSECT SELECT n FROM empty"));
        assertEquals(List.of(), rows("SELECT n FROM a WHERE n < 2 INTERSECT SELECT n FROM c"));
        assertEquals(List.of("1"), rows("SELECT n FROM a WHERE n < 2 EXCEPT SELECT n FROM c"));
        assertEquals(List.of("3", "4", "5"), rows("SELECT n FROM c INTERSECT SELECT n FROM a"));
        assertEquals(List.of(), rows("SELECT n FROM c EXCEPT SELECT n FROM a"));
    }

    // -- which rows are equal --------------------------------------------------------

    @Test
    void rowsMatchAsDistinctMatchesThem() throws Exception {
        table("left_values", """
            [{"v": 10}, {"v": "10"}, {"v": true}, {"v": null}, {}, {"v": {"x": 1}}, {"v": [1, 2]}, {"v": 1.5}]
            """);
        table("right_values", """
            [{"v": 10.0}, {"v": "true"}, {}, {"v": {"x": 1}}, {"v": [1, 2]}]
            """);
        assertEquals("[{\"v\":10},{\"v\":true},{\"v\":null},{\"v\":{\"x\":1}},{\"v\":[1,2]}]",
            executor.execute("SELECT v FROM left_values INTERSECT SELECT v FROM right_values"));
        assertEquals("[{\"v\":\"10\"},{\"v\":1.5}]",
            executor.execute("SELECT v FROM left_values EXCEPT SELECT v FROM right_values"));
    }

    @Test
    void theLeftRowsAreKeptInTheLeftOrder() throws Exception {
        table("l", "[{\"v\": 3}, {\"v\": 1.0}, {\"v\": 2}, {\"v\": 1}]");
        table("r", "[{\"v\": 2}, {\"v\": 1}, {\"v\": 3}]");
        assertEquals("[{\"v\":3},{\"v\":1.0},{\"v\":2}]", executor.execute("SELECT v FROM l INTERSECT SELECT v FROM r"));
    }

    @Test
    void columnsMatchByPositionAndTakeTheFirstBranchsNames() throws Exception {
        table("q", "[{\"pid\": 2, \"label\": \"Gadget\"}, {\"pid\": 3, \"label\": \"Other\"}]");
        assertEquals("[{\"id\":2,\"name\":\"Gadget\"}]",
            executor.execute("SELECT id, name FROM p INTERSECT SELECT pid, label FROM q"));
        assertEquals("[{\"id\":1,\"name\":\"Widget\"},{\"id\":3,\"name\":\"Doohickey\"}]",
            executor.execute("SELECT id, name FROM p EXCEPT SELECT pid, label FROM q"));
        // The same values in a different order are a different row
        assertEquals("[]", executor.execute("SELECT name, id FROM p INTERSECT SELECT pid, label FROM q"));
    }

    @Test
    void selectStarBranchesMatchByFieldName() throws Exception {
        table("s1", "[{\"x\": 1, \"y\": 2}, {\"x\": 3, \"y\": 4}]");
        table("s2", "[{\"y\": 2, \"x\": 1}, {\"x\": 5, \"y\": 6}]");
        assertEquals("[{\"x\":1,\"y\":2}]", executor.execute("SELECT * FROM s1 INTERSECT SELECT * FROM s2"));
        assertEquals("[{\"x\":3,\"y\":4}]", executor.execute("SELECT * FROM s1 EXCEPT SELECT * FROM s2"));
    }

    // -- chains, left to right ---------------------------------------------------------

    @Test
    void chainsRunLeftToRight() throws Exception {
        assertEquals(List.of("1", "6"), rows("SELECT n FROM a EXCEPT SELECT n FROM b EXCEPT SELECT n FROM c"));
        assertEquals(List.of("3", "4"), rows("SELECT n FROM a INTERSECT SELECT n FROM b INTERSECT SELECT n FROM c"));
        assertEquals(List.of("2", "3", "4", "5"), rows("SELECT n FROM a INTERSECT SELECT n FROM b UNION SELECT n FROM c"));
        assertEquals(List.of("1", "5", "6", "3", "4", "5"),
            rows("SELECT n FROM a EXCEPT SELECT n FROM b UNION ALL SELECT n FROM c"));
        assertEquals(List.of("1", "2", "6"), rows("SELECT n FROM a UNION SELECT n FROM b EXCEPT SELECT n FROM c"));
        assertEquals(List.of("2"), rows("SELECT n FROM b UNION ALL SELECT n FROM b EXCEPT SELECT n FROM c"));
        assertEquals(List.of("5", "3", "4", "5"), rows("SELECT n FROM c EXCEPT SELECT n FROM b UNION ALL SELECT n FROM c"));
    }

    // -- ORDER BY, LIMIT, OFFSET and TOP ----------------------------------------------

    @Test
    void orderByLimitAndOffsetApplyToTheWholeResult() throws Exception {
        assertEquals(List.of("4", "3", "2"), rows("SELECT n FROM a INTERSECT SELECT n FROM b ORDER BY n DESC"));
        assertEquals(List.of("6", "5"), rows("SELECT n AS x FROM a EXCEPT SELECT n FROM b ORDER BY x DESC LIMIT 2"));
        assertEquals(List.of("5", "1"), rows("SELECT n FROM a EXCEPT SELECT n FROM b ORDER BY 1 DESC LIMIT 2 OFFSET 1"));
        assertEquals(List.of("1", "5"), rows("SELECT n FROM a EXCEPT SELECT n FROM b LIMIT 2"));
        assertEquals(List.of("3", "4"), rows("SELECT n FROM a INTERSECT SELECT n FROM b LIMIT 2 OFFSET 1"));
    }

    @Test
    void topInABranchLimitsThatBranch() throws Exception {
        assertEquals(List.of("1"), rows("SELECT TOP 3 n FROM a EXCEPT SELECT n FROM b"));
        assertEquals(List.of("2", "3"), rows("SELECT n FROM a INTERSECT SELECT TOP 2 n FROM b"));
    }

    // -- what a branch can be ---------------------------------------------------------

    @Test
    void branchesWithJoinsGroupingCalculationsAndFunctions() throws Exception {
        assertEquals(List.of("Tools"),
            rows("SELECT p.cat FROM p JOIN o ON o.pid = p.id INTERSECT SELECT cat FROM p WHERE price > 10"));
        assertEquals(List.of("Tools:2"),
            rows("SELECT cat, COUNT(*) AS n FROM p GROUP BY cat EXCEPT SELECT cat, 1 FROM p"));
        assertEquals(List.of("40"), rows("SELECT price * 2 AS d FROM p INTERSECT SELECT oid - 61 FROM o"));
        assertEquals(List.of("GADGET"),
            rows("SELECT UPPER(name) AS u FROM p EXCEPT SELECT UPPER(name) FROM p JOIN o ON o.pid = p.id"));
    }

    @Test
    void branchesReadingCtes() throws Exception {
        String sql = "WITH x AS (SELECT n FROM a WHERE n > 2) SELECT n FROM a EXCEPT SELECT n FROM x";
        assertEquals(List.of("1", "2"), rows(sql));

        QueryExecutor cached = new QueryExecutor(mappingManager, dataDir, new CacheManager(dataDir));
        assertEquals(executor.execute(sql), cached.execute(sql));
        assertEquals(executor.execute(sql), cached.execute(sql));
        String intersect = "WITH x AS (SELECT n FROM a WHERE n > 2) SELECT n FROM b INTERSECT SELECT n FROM x";
        assertEquals("[{\"n\":3},{\"n\":4}]", cached.execute(intersect));
    }

    @Test
    void textInArithmeticInABranchIsAWarning() throws Exception {
        table("mixed", "[{\"n\": 1}, {\"n\": \"two\"}, {\"n\": 3}]");
        assertEquals(List.of("2"), rows("SELECT n * 2 AS d FROM mixed INTERSECT SELECT n FROM b"));
        assertEquals(List.of("n * 2 left out 1 value that is not a number: \"two\""), warnings);
    }

    @Test
    void textInArithmeticInABranchIsAnErrorInStrictMode() throws Exception {
        table("mixed", "[{\"n\": 1}, {\"n\": \"two\"}, {\"n\": 3}]");
        executor.setStrict(true);
        assertThrows(StrictModeException.class,
            () -> executor.execute("SELECT n FROM b EXCEPT SELECT n * 2 FROM mixed"));
    }
}
