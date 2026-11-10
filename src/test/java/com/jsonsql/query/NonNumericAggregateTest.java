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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SUM and AVG leave out values that aren't numbers, as they leave out NULL, and say so in
 * a warning. In strict mode the query fails instead.
 */
class NonNumericAggregateTest {

    private static final String DATA = """
        [
          {"id": 1, "cat": "a", "price": 10,      "qty": 1},
          {"id": 2, "cat": "a", "price": 20,      "qty": 2},
          {"id": 3, "cat": "a", "price": "none",  "qty": 3},
          {"id": 4, "cat": "b", "price": "3.5kg", "qty": 4},
          {"id": 5, "cat": "b", "price": null,    "qty": null},
          {"id": 6, "cat": "b",                   "qty": 6},
          {"id": 7, "cat": "b", "price": true,    "qty": 7},
          {"id": 8, "cat": "c", "price": "none",  "qty": 8}
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
        Files.writeString(new File(tempDir, "t.json").toPath(), DATA);
        mappings = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        mappings.addMapping("t", "t.json:$[*]");
        executor = new QueryExecutor(mappings, tempDir);
        executor.setWarningSink(warnings::add);
    }

    private JsonNode query(String sql) throws Exception {
        return mapper.readTree(executor.execute(sql));
    }

    @Test
    void sumLeavesOutValuesThatAreNotNumbers() throws Exception {
        assertEquals(30, query("SELECT SUM(price) AS s FROM t").get(0).get("s").asInt());
        assertEquals(List.of("SUM(price) left out 4 values that are not numbers, such as \"none\""), warnings);
    }

    @Test
    void avgDividesByTheNumbersOnly() throws Exception {
        assertEquals(15.0, query("SELECT AVG(price) AS a FROM t").get(0).get("a").asDouble());
        assertEquals(List.of("AVG(price) left out 4 values that are not numbers, such as \"none\""), warnings);
    }

    @Test
    void textIsNeverReadAsANumber() throws Exception {
        JsonNode row = query("SELECT SUM(price) AS s, AVG(price) AS a FROM t WHERE id = 4").get(0);

        assertTrue(row.get("s").isNull());
        assertTrue(row.get("a").isNull());
        assertEquals(List.of(
            "SUM(price) left out 1 value that is not a number: \"3.5kg\"",
            "AVG(price) left out 1 value that is not a number: \"3.5kg\""), warnings);
    }

    @Test
    void aGroupWithNoNumbersGivesNull() throws Exception {
        JsonNode rows = query("SELECT cat, SUM(price) AS s, AVG(price) AS a FROM t GROUP BY cat ORDER BY cat");

        assertEquals(30, rows.get(0).get("s").asInt());
        assertEquals(15.0, rows.get(0).get("a").asDouble());
        assertTrue(rows.get(1).get("s").isNull());
        assertTrue(rows.get(1).get("a").isNull());
        assertTrue(rows.get(2).get("s").isNull());
    }

    @Test
    void oneWarningPerAggregateHoweverManyGroups() throws Exception {
        query("SELECT cat, SUM(price) AS s FROM t GROUP BY cat");

        assertEquals(List.of("SUM(price) left out 4 values that are not numbers, such as \"none\""), warnings);
    }

    @Test
    void countMinAndMaxStillSeeEveryValue() throws Exception {
        JsonNode row = query("SELECT COUNT(price) AS c, MIN(price) AS lo, MAX(price) AS hi, COUNT(*) AS n FROM t").get(0);

        assertEquals(6, row.get("c").asInt());
        assertEquals(10, row.get("lo").asInt());
        assertTrue(row.get("hi").isBoolean());
        assertEquals(8, row.get("n").asInt());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void numbersAndNullsGiveNoWarning() throws Exception {
        JsonNode row = query("SELECT SUM(qty) AS s, AVG(qty) AS a FROM t").get(0);

        assertEquals(31, row.get("s").asInt());
        assertEquals(31.0 / 7, row.get("a").asDouble(), 1e-12);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void distinctCountsEveryRowLeftOut() throws Exception {
        assertEquals(30, query("SELECT SUM(DISTINCT price) AS s FROM t").get(0).get("s").asInt());
        assertEquals(List.of("SUM(DISTINCT price) left out 4 values that are not numbers, such as \"none\""), warnings);
    }

    @Test
    void warnsForAnAggregateUsedOnlyInHavingOrOrderBy() throws Exception {
        query("SELECT cat FROM t GROUP BY cat HAVING SUM(price) > 5");
        assertEquals(1, warnings.size());

        warnings.clear();
        query("SELECT cat FROM t GROUP BY cat ORDER BY AVG(price)");
        assertEquals(1, warnings.size());
    }

    @Test
    void warnsForAnAggregateInsideACte() throws Exception {
        JsonNode rows = query("WITH totals AS (SELECT cat, SUM(price) AS s FROM t GROUP BY cat) SELECT s FROM totals WHERE cat = 'a'");

        assertEquals(30, rows.get(0).get("s").asInt());
        assertEquals(1, warnings.size());
    }

    @Test
    void longExamplesAreCutShort() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), "[{\"price\": \"" + "x".repeat(200) + "\"}]");

        query("SELECT SUM(price) AS s FROM t");

        assertEquals("SUM(price) left out 1 value that is not a number: \"" + "x".repeat(39) + "...", warnings.get(0));
    }

    @Test
    void arraysAndObjectsAreLeftOutToo() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), "[{\"price\": [1, 2]}, {\"price\": {\"a\": 1}}, {\"price\": 5}]");

        assertEquals(5, query("SELECT SUM(price) AS s FROM t").get(0).get("s").asInt());
        assertEquals(List.of("SUM(price) left out 2 values that are not numbers, such as [1,2]"), warnings);
    }

    @Test
    void strictModeFailsInsteadOfWarning() {
        executor.setStrict(true);

        Exception error = assertThrows(Exception.class, () -> query("SELECT cat, SUM(price) AS s FROM t GROUP BY cat"));

        assertEquals("SUM(price) found 4 values that are not numbers, such as \"none\"", error.getMessage());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void strictModeAllowsNumbersAndNulls() throws Exception {
        executor.setStrict(true);

        assertEquals(31, query("SELECT SUM(qty) AS s FROM t").get(0).get("s").asInt());
        assertEquals(6, query("SELECT COUNT(price) AS c FROM t").get(0).get("c").asInt());
    }

    @Test
    void strictModeFailsEvenWhenTheResultWasCached() throws Exception {
        String sql = "WITH totals AS (SELECT cat, SUM(price) AS s FROM t GROUP BY cat) SELECT s FROM totals";
        QueryExecutor cached = new QueryExecutor(mappings, tempDir, new CacheManager(tempDir));
        cached.setWarningSink(warnings::add);
        mapper.readTree(cached.execute(sql));
        assertEquals(1, warnings.size());

        QueryExecutor strict = new QueryExecutor(mappings, tempDir, new CacheManager(tempDir));
        strict.setStrict(true);

        assertThrows(Exception.class, () -> strict.execute(sql));
    }
}
