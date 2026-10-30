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

class BetweenOffsetAndNullOrderTest {

    private static final String DATA = """
        {"rows": [
          {"id": 1, "price": 10, "name": "a", "qty": 5},
          {"id": 2, "price": 20, "name": "b", "qty": 25},
          {"id": 3, "price": 30, "name": "c", "qty": 15},
          {"id": 4, "price": null, "name": "d", "qty": 1},
          {"id": 5, "name": "e", "qty": 2}
        ]}
        """;

    @TempDir
    File tempDir;

    private MappingManager mappings;
    private QueryExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), DATA);
        mappings = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        mappings.addMapping("t", "t.json:$.rows[*]");
        executor = new QueryExecutor(mappings, tempDir);
    }

    private JsonNode query(String sql) throws Exception {
        return new ObjectMapper().readTree(executor.execute(sql));
    }

    private String column(String sql, String name) throws Exception {
        List<String> values = new ArrayList<>();
        query(sql).forEach(row -> values.add(row.get(name).asText()));
        return String.join(" ", values);
    }

    private String ids(String sql) throws Exception {
        return column(sql, "id");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "price BETWEEN 10 AND 20          | 1 2",
        "price BETWEEN 20 AND 20          | 2",
        "price NOT BETWEEN 10 AND 20      | 3",
        "NOT (price BETWEEN 10 AND 20)    | 3",
        "name BETWEEN 'b' AND 'c'         | 2 3",
        "name NOT BETWEEN 'b' AND 'c'     | 1 4 5",
        "price BETWEEN qty AND 30         | 1 3",
        "qty BETWEEN 1 AND price          | 1 3",
        "15 BETWEEN qty AND price         | 3",
        "price BETWEEN NULL AND 100       | ",
        "price NOT BETWEEN NULL AND 100   | ",
        "price NOT BETWEEN NULL AND 15    | 2 3",
        "price NOT BETWEEN 25 AND NULL    | 1 2",
    })
    void betweenFollowsSqlLogic(String condition, String expected) throws Exception {
        assertEquals(expected == null ? "" : expected, ids("SELECT id FROM t WHERE " + condition));
    }

    @Test
    void reversedBoundsMatchNothing() throws Exception {
        assertEquals("", ids("SELECT id FROM t WHERE price BETWEEN 30 AND 10"));
    }

    @Test
    void betweenRejectsArithmetic() {
        assertThrows(QueryParseException.class, () -> query("SELECT id FROM t WHERE price BETWEEN 1 + 1 AND 20"));
        assertThrows(QueryParseException.class, () -> query("SELECT id FROM t WHERE LOWER(name) BETWEEN 'a' AND 'b'"));
    }

    @Test
    void offsetSkipsRowsAfterSorting() throws Exception {
        assertEquals("3 4", ids("SELECT id FROM t ORDER BY id LIMIT 2 OFFSET 2"));
        assertEquals("3 2", ids("SELECT id FROM t ORDER BY id DESC LIMIT 2 OFFSET 2"));
    }

    @Test
    void offsetWorksWithoutLimit() throws Exception {
        assertEquals("4 5", ids("SELECT id FROM t ORDER BY id OFFSET 3"));
    }

    @Test
    void limitCommaFormMeansOffsetThenCount() throws Exception {
        assertEquals("3 4", ids("SELECT id FROM t ORDER BY id LIMIT 2, 2"));
    }

    @Test
    void offsetWorksWithTop() throws Exception {
        assertEquals("2 3", ids("SELECT TOP 2 id FROM t ORDER BY id OFFSET 1"));
    }

    @Test
    void offsetAtOrPastTheEndGivesNoRows() throws Exception {
        assertEquals("", ids("SELECT id FROM t ORDER BY id LIMIT 2 OFFSET 5"));
        assertEquals("", ids("SELECT id FROM t ORDER BY id LIMIT 2 OFFSET 500"));
    }

    @Test
    void offsetPartlyPastTheEndGivesTheRemainingRows() throws Exception {
        assertEquals("5", ids("SELECT id FROM t ORDER BY id LIMIT 5 OFFSET 4"));
    }

    @Test
    void offsetZeroChangesNothing() throws Exception {
        assertEquals("1 2", ids("SELECT id FROM t ORDER BY id LIMIT 2 OFFSET 0"));
    }

    @Test
    void negativeOffsetIsRejected() {
        assertThrows(QueryParseException.class, () -> query("SELECT id FROM t LIMIT 2 OFFSET -1"));
    }

    @Test
    void offsetAppliesAfterDistinct() throws Exception {
        assertEquals("c", column("SELECT DISTINCT name FROM t ORDER BY name LIMIT 1 OFFSET 2", "name"));
    }

    @Test
    void offsetAppliesToAWholeUnion() throws Exception {
        assertEquals("3 4", ids("SELECT id FROM t UNION SELECT id FROM t ORDER BY id LIMIT 2 OFFSET 2"));
        assertEquals("3 4", ids("SELECT id FROM t WHERE id < 3 UNION ALL SELECT id FROM t WHERE id > 2 LIMIT 2 OFFSET 2"));
    }

    @Test
    void fetchIsStillRejected() {
        assertThrows(QueryParseException.class, () -> query("SELECT id FROM t FETCH FIRST 2 ROWS ONLY"));
    }

    @Test
    void cteOffsetIsPartOfTheCacheKey() throws Exception {
        QueryExecutor cached = new QueryExecutor(mappings, tempDir, new CacheManager(tempDir));
        String sql = "WITH c AS (SELECT id FROM t ORDER BY id LIMIT 1 OFFSET %d) SELECT id FROM c";

        cached.execute(sql.formatted(0));
        JsonNode rows = new ObjectMapper().readTree(cached.execute(sql.formatted(2)));

        assertEquals(3, rows.get(0).get("id").asInt());
    }

    @Test
    void nullAndMissingSortTogetherFirstWhenAscending() throws Exception {
        String sorted = ids("SELECT id FROM t ORDER BY price, id");
        assertEquals("4 5 1 2 3", sorted);
    }

    @Test
    void nullAndMissingSortTogetherLastWhenDescending() throws Exception {
        assertEquals("3 2 1 4 5", ids("SELECT id FROM t ORDER BY price DESC, id"));
    }

    @Test
    void textNullIsNotTheSameAsNull() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), "{\"rows\": [{\"id\": 1, \"s\": \"null\"}, {\"id\": 2, \"s\": null}, {\"id\": 3, \"s\": \"a\"}]}");

        assertEquals("2 3 1", ids("SELECT id FROM t ORDER BY s"));
    }

    @Test
    void unionUsesTheFirstBranchNames() throws Exception {
        JsonNode rows = query("SELECT id AS a FROM t WHERE id = 1 UNION ALL SELECT qty FROM t WHERE id = 2");

        assertEquals(1, rows.get(0).get("a").asInt());
        assertEquals(25, rows.get(1).get("a").asInt());
        assertFalse(rows.get(1).has("qty"));
    }

    @Test
    void unionRemovesDuplicatesAcrossDifferentNames() throws Exception {
        JsonNode rows = query("SELECT id AS a FROM t UNION SELECT id FROM t");

        assertEquals(5, rows.size());
    }

    @Test
    void unionMatchesColumnsByPosition() throws Exception {
        JsonNode rows = query("SELECT id AS a, name AS b FROM t WHERE id = 1 UNION ALL SELECT name, id FROM t WHERE id = 2");

        assertEquals("b", rows.get(1).get("a").asText());
        assertEquals(2, rows.get(1).get("b").asInt());
    }

    @Test
    void unionOrderByCanUseANameFromAnyBranch() throws Exception {
        assertEquals("5 4", column("SELECT id AS a FROM t UNION SELECT id FROM t ORDER BY a DESC LIMIT 2", "a"));
        assertEquals("5 4", column("SELECT id AS a FROM t UNION SELECT id FROM t ORDER BY id DESC LIMIT 2", "a"));
    }

    @Test
    void unionNamesAreKeptWhenTheFirstBranchIsEmpty() throws Exception {
        JsonNode rows = query("SELECT id AS a FROM t WHERE id > 99 UNION ALL SELECT qty FROM t WHERE id = 1");

        assertEquals(5, rows.get(0).get("a").asInt());
    }

    @Test
    void unionOfSelectStarIsLeftAlone() throws Exception {
        assertEquals(5, query("SELECT * FROM t UNION SELECT * FROM t").size());
    }
}
