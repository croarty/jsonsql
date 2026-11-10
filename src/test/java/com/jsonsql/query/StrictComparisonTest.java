package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * WHERE, ORDER BY, MIN and MAX, GROUP BY, DISTINCT and joins all compare values the same
 * way: numbers are exact, text never converts to a number, numbers sort before text, and
 * a boolean compares as the text true or false.
 */
class StrictComparisonTest {

    private static final String ROWS = """
        [
          {"id": 1, "n": 10,       "s": "10",   "b": false, "size": "15.6 inch", "flag": true},
          {"id": 2, "n": 10.00005, "s": "9",    "b": true,  "size": "7 inch",    "flag": "true"},
          {"id": 3, "n": 7,        "s": "abc",  "b": false, "size": "100 inch",  "flag": "false"},
          {"id": 4, "n": 10.0,     "s": "10.0", "b": true,  "size": "n/a",       "flag": false}
        ]
        """;

    @TempDir
    File tempDir;

    private QueryExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), ROWS);
        Files.writeString(new File(tempDir, "u.json").toPath(),
            "[{\"uid\": 1, \"k\": 10}, {\"uid\": 2, \"k\": \"10\"}, {\"uid\": 3, \"k\": true}, {\"uid\": 4, \"k\": \"true\"}]");
        MappingManager mappings = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        mappings.addMapping("t", "t.json:$[*]");
        mappings.addMapping("u", "u.json:$[*]");
        executor = new QueryExecutor(mappings, tempDir);
    }

    private String column(String sql, String column) throws Exception {
        List<String> values = new ArrayList<>();
        for (JsonNode row : mapper.readTree(executor.execute(sql))) {
            values.add(row.get(column).asText());
        }
        return String.join(",", values);
    }

    @ParameterizedTest(name = "WHERE {0} returns ids {1}")
    @CsvSource(delimiter = '|', value = {
        // Numbers are exact, and 10 equals 10.0
        "n = 10              | 1,4",
        "n = 10.00005        | 2",
        "n != 10             | 2,3",
        "n > 10              | 2",
        "n IN (10, 7)        | 1,3,4",
        "n BETWEEN 7 AND 10  | 1,3,4",
        // Text never converts to a number
        "n = '10'            | ''",
        "s = 10              | ''",
        "s = '10'            | 1",
        "n IN ('10')         | ''",
        "s IN (10, 'abc')    | 3",
        "n = s               | ''",
        // Numbers sort before text
        "s > 5               | 1,2,3,4",
        "s < 5               | ''",
        "n > '5'             | ''",
        "n < '5'             | 1,2,3,4",
        "s BETWEEN 9 AND 10  | ''",
        "s > '5'             | 2,3",
        // A boolean compares as the text true or false
        "b = true            | 2,4",
        "b = 'true'          | 2,4",
        "b = false           | 1,3",
        "b = 'false'         | 1,3",
        "b != 'true'         | 1,3",
        "b = 'banana'        | ''",
        "b = 'TRUE'          | ''",
        "b = 1               | ''",
        "b = 0               | ''",
        "b IN ('true', 'x')  | 2,4",
        "flag = true         | 1,2",
        "flag = 'true'       | 1,2",
        "flag = false        | 3,4",
        "flag != true        | 3,4",
    })
    void where(String condition, String ids) throws Exception {
        assertEquals(ids, column("SELECT id FROM t WHERE " + condition + " ORDER BY id", "id"));
    }

    @Test
    void orderByComparesTextAsText() throws Exception {
        assertEquals("10,10.0,9,abc", column("SELECT s FROM t ORDER BY s", "s"));
        assertEquals("100 inch,15.6 inch,7 inch,n/a", column("SELECT size FROM t ORDER BY size", "size"));
    }

    @Test
    void orderByPutsNumbersBeforeText() throws Exception {
        // k is 10, "10", true and "true": the number first, then the text in alphabetical order
        assertEquals("1,2,3,4", column("SELECT uid FROM u ORDER BY k, uid", "uid"));
        assertEquals("2,1", column("SELECT uid FROM u WHERE uid < 3 ORDER BY k DESC", "uid"));
    }

    @Test
    void orderByKeepsExactNumberOrder() throws Exception {
        assertEquals("3,1,4,2", column("SELECT id FROM t ORDER BY n, id", "id"));
    }

    @Test
    void minAndMaxAgreeWithOrderBy() throws Exception {
        for (String field : List.of("n", "s", "size", "flag")) {
            String sorted = column("SELECT " + field + " FROM t ORDER BY " + field, field);
            String first = sorted.substring(0, sorted.indexOf(','));
            String last = sorted.substring(sorted.lastIndexOf(',') + 1);
            assertEquals(first, column("SELECT MIN(" + field + ") AS m FROM t", "m"), "MIN(" + field + ")");
            assertEquals(last, column("SELECT MAX(" + field + ") AS m FROM t", "m"), "MAX(" + field + ")");
        }
    }

    @Test
    void groupByPutsEqualValuesTogether() throws Exception {
        // 10 and 10.0 are one group; true and "true" are one group
        assertEquals("1,1,2", column("SELECT n, COUNT(*) AS c FROM t GROUP BY n ORDER BY c, n", "c"));
        assertEquals("2,2", column("SELECT flag, COUNT(*) AS c FROM t GROUP BY flag", "c"));
        // 10 and "10" are not
        assertEquals("1,1", column("SELECT k, COUNT(*) AS c FROM u WHERE uid < 3 GROUP BY k", "c"));
    }

    @Test
    void distinctUsesTheSameEquality() throws Exception {
        assertEquals(3, mapper.readTree(executor.execute("SELECT DISTINCT n FROM t")).size());
        assertEquals(2, mapper.readTree(executor.execute("SELECT DISTINCT flag FROM t")).size());
        assertEquals(4, mapper.readTree(executor.execute("SELECT DISTINCT s FROM t")).size());
        assertEquals(2, mapper.readTree(executor.execute("SELECT DISTINCT k FROM u WHERE uid < 3")).size());
    }

    @Test
    void countDistinctUsesTheSameEquality() throws Exception {
        assertEquals("3", column("SELECT COUNT(DISTINCT n) AS c FROM t", "c"));
        assertEquals("2", column("SELECT COUNT(DISTINCT flag) AS c FROM t", "c"));
    }

    @Test
    void unionUsesTheSameEquality() throws Exception {
        String sql = "SELECT n FROM t WHERE id = 1 UNION SELECT n FROM t WHERE id = 4";
        assertEquals(1, mapper.readTree(executor.execute(sql)).size());
    }

    @Test
    void joinsUseTheSameEquality() throws Exception {
        // n is 10 or 10.0 in rows 1 and 4; only the number 10 in u matches, not the text "10"
        assertEquals("1:1,4:1", pairs("SELECT t.id, u.uid FROM t JOIN u ON t.n = u.k ORDER BY t.id"));
        // s is the text "10" in row 1; only the text "10" in u matches
        assertEquals("1:2", pairs("SELECT t.id, u.uid FROM t JOIN u ON t.s = u.k"));
        // flag is true or "true" in rows 1 and 2; both forms in u match both
        assertEquals("1:3,1:4,2:3,2:4",
            pairs("SELECT t.id, u.uid FROM t JOIN u ON t.flag = u.k ORDER BY t.id, u.uid"));
    }

    private String pairs(String sql) throws Exception {
        List<String> pairs = new ArrayList<>();
        for (JsonNode row : mapper.readTree(executor.execute(sql))) {
            pairs.add(row.get("id").asText() + ":" + row.get("uid").asText());
        }
        return String.join(",", pairs);
    }
}
