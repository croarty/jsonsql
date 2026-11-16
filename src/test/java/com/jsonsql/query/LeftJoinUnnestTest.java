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
 * LEFT JOIN UNNEST keeps a row whose list has no elements, once, with the element null.
 * The other UNNEST forms leave such a row out, as before.
 */
class LeftJoinUnnestTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private QueryExecutor executor;
    private MappingManager mappingManager;
    private File dataDir;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        mappingManager = new MappingManager(tempDir.resolve(".jsonsql-mappings.json").toFile());
        executor = new QueryExecutor(mappingManager, dataDir);

        // A list, an empty list, no list, a list with one element, a null list, and not a list
        table("u", """
            [{"id": 1, "tags": ["a", "b"], "items": [{"pid": 1, "qty": 2}, {"pid": 2, "qty": 1}], "sets": [[1, 2], []]},
             {"id": 2, "tags": [], "items": [], "sets": []},
             {"id": 3},
             {"id": 4, "tags": ["c"], "items": [{"pid": 9, "qty": 5}], "sets": [[3]]},
             {"id": 5, "tags": null, "items": null, "sets": null},
             {"id": 6, "tags": "x", "items": {"pid": 1}, "sets": "x"}]
            """);
        table("p", "[{\"id\": 1, \"name\": \"A\"}, {\"id\": 2, \"name\": \"B\"}]");
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

    private static final List<String> LEFT_TAGS = List.of("1:a", "1:b", "2:null", "3:null", "4:c", "5:null", "6:null");
    private static final List<String> PLAIN_TAGS = List.of("1:a", "1:b", "4:c");

    @Test
    void leftJoinKeepsEachRowWithNoElementsOnce() throws Exception {
        assertEquals(LEFT_TAGS, rows("SELECT u.id, tag FROM u LEFT JOIN UNNEST(u.tags) AS t(tag)"));
        assertEquals(LEFT_TAGS, rows("SELECT u.id, tag FROM u LEFT OUTER JOIN UNNEST(u.tags) AS t(tag)"));
        assertEquals(LEFT_TAGS, rows("SELECT u.id, tag FROM u LEFT JOIN UNNEST(u.tags) AS t(tag) ON true"));
        assertEquals(LEFT_TAGS, rows("SELECT u.id, tag FROM u LEFT JOIN UNNEST(u.tags) AS t(tag) ON TRUE"));
        assertEquals(LEFT_TAGS, rows("SELECT u.id, tag FROM u LEFT JOIN UNNEST(u.tags) AS t(tag) ON 1 = 1"));
    }

    @Test
    void theOtherFormsAreUnchanged() throws Exception {
        assertEquals(PLAIN_TAGS, rows("SELECT u.id, tag FROM u, UNNEST(u.tags) AS t(tag)"));
        assertEquals(PLAIN_TAGS, rows("SELECT u.id, tag FROM u JOIN UNNEST(u.tags) AS t(tag)"));
        assertEquals(PLAIN_TAGS, rows("SELECT u.id, tag FROM u INNER JOIN UNNEST(u.tags) AS t(tag)"));
        assertEquals(PLAIN_TAGS, rows("SELECT u.id, tag FROM u CROSS JOIN UNNEST(u.tags) AS t(tag)"));
        assertEquals(PLAIN_TAGS, rows("SELECT u.id, tag FROM u JOIN UNNEST(u.tags) AS t(tag) ON true"));
        assertEquals(PLAIN_TAGS, rows("SELECT u.id, tag FROM u JOIN UNNEST(u.tags) AS t(tag) ON 1 = 1"));
    }

    @Test
    void listsOfObjects() throws Exception {
        assertEquals(List.of("1:1:2", "1:2:1", "2:null:null", "3:null:null", "4:9:5", "5:null:null", "6:null:null"),
            rows("SELECT u.id, item.pid, item.qty FROM u LEFT JOIN UNNEST(u.items) AS i(item)"));
    }

    @Test
    void aNestedFieldOfAKeptRowsElementIsNullNotLookedForElsewhere() throws Exception {
        // q also has an item.pid; a kept row's item is null, so item.pid is null rather than q's
        table("q", "[{\"item\": {\"pid\": 7}}]");
        assertEquals(List.of("1:1", "1:2", "2:null", "3:null", "4:9", "5:null", "6:null"),
            rows("SELECT u.id, item.pid FROM u LEFT JOIN UNNEST(u.items) AS i(item) CROSS JOIN q"));
    }

    @Test
    void whereOnTheElement() throws Exception {
        assertEquals(List.of("1:a"), rows("SELECT u.id, tag FROM u LEFT JOIN UNNEST(u.tags) AS t(tag) WHERE tag = 'a'"));
        assertEquals(List.of("2", "3", "5", "6"), rows("SELECT u.id FROM u LEFT JOIN UNNEST(u.tags) AS t(tag) WHERE tag IS NULL"));
    }

    @Test
    void countingTheElementAndTheRows() throws Exception {
        assertEquals(List.of("1:2:2", "2:0:1", "3:0:1", "4:1:1", "5:0:1", "6:0:1"),
            rows("SELECT u.id, COUNT(tag) AS n, COUNT(*) AS r FROM u LEFT JOIN UNNEST(u.tags) AS t(tag) GROUP BY u.id"));
    }

    @Test
    void orderByTheElement() throws Exception {
        assertEquals(List.of("2:null", "3:null", "5:null", "6:null", "1:a", "1:b", "4:c"),
            rows("SELECT u.id, tag FROM u LEFT JOIN UNNEST(u.tags) AS t(tag) ORDER BY tag, u.id"));
    }

    @Test
    void severalUnnestsEachLeftOrNot() throws Exception {
        assertEquals(List.of("1:1", "1:2", "4:3"),
            rows("SELECT u.id, v FROM u LEFT JOIN UNNEST(u.sets) AS s(sset), UNNEST(sset) AS w(v)"));
        assertEquals(List.of("1:1", "1:2", "1:null", "4:3"),
            rows("SELECT u.id, v FROM u, UNNEST(u.sets) AS s(sset) LEFT JOIN UNNEST(sset) AS w(v)"));
        assertEquals(List.of("1:1", "1:2", "1:null", "2:null", "3:null", "4:3", "5:null", "6:null"),
            rows("SELECT u.id, v FROM u LEFT JOIN UNNEST(u.sets) AS s(sset) LEFT JOIN UNNEST(sset) AS w(v)"));
    }

    @Test
    void aJoinOnTheElement() throws Exception {
        assertEquals(List.of("1:A", "1:B", "2:null", "3:null", "4:null", "5:null", "6:null"),
            rows("SELECT u.id, p.name FROM u LEFT JOIN UNNEST(u.items) AS i(item) LEFT JOIN p ON p.id = item.pid"));
        assertEquals(List.of("1:A", "1:B"),
            rows("SELECT u.id, p.name FROM u LEFT JOIN UNNEST(u.items) AS i(item) JOIN p ON p.id = item.pid"));
    }

    @Test
    void insideACteADerivedTableASubqueryAndASetOperation() throws Exception {
        String left = "SELECT u.id AS id, tag FROM u LEFT JOIN UNNEST(u.tags) AS t(tag)";
        assertEquals(List.of("2", "3", "5", "6"), rows("WITH x AS (" + left + ") SELECT id FROM x WHERE tag IS NULL"));
        assertEquals(List.of("7"), rows("SELECT COUNT(*) AS n FROM (" + left + ") d"));
        assertEquals(List.of("2", "3", "5", "6"),
            rows("SELECT id FROM u WHERE id IN (SELECT u.id FROM u LEFT JOIN UNNEST(u.tags) AS t(tag) WHERE tag IS NULL)"));
        assertEquals(List.of("2", "3"),
            rows("SELECT u.id FROM u LEFT JOIN UNNEST(u.tags) AS t(tag) WHERE tag IS NULL EXCEPT SELECT id FROM u WHERE id > 4"));
    }

    @Test
    void selectStar() throws Exception {
        assertEquals("[{\"id\":2,\"tags\":[],\"items\":[],\"sets\":[],\"tag\":null}]",
            executor.execute("SELECT * FROM u LEFT JOIN UNNEST(u.tags) AS t(tag) WHERE u.id = 2"));
    }

    @Test
    void aCachedCteWithPlainUnnestIsNotUsedForLeftJoinUnnest() throws Exception {
        QueryExecutor cached = new QueryExecutor(mappingManager, dataDir, new CacheManager(dataDir));
        assertEquals(List.of("3"),
            rows(cached, "WITH x AS (SELECT u.id, tag FROM u, UNNEST(u.tags) AS t(tag)) SELECT COUNT(*) AS n FROM x"));
        assertEquals(List.of("7"),
            rows(cached, "WITH x AS (SELECT u.id, tag FROM u LEFT JOIN UNNEST(u.tags) AS t(tag)) SELECT COUNT(*) AS n FROM x"));
        assertEquals(List.of("3"),
            rows(cached, "WITH x AS (SELECT u.id, tag FROM u, UNNEST(u.tags) AS t(tag)) SELECT COUNT(*) AS n FROM x"));
    }
}
