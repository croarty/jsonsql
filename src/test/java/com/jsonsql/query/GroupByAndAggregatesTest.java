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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour SqliteComparisonTest can't cover: queries JsonSQL deliberately rejects, output
 * names, JSON-only values, and how grouping works with the cache and with nested fields.
 */
class GroupByAndAggregatesTest {

    private static final String DATA = """
        {"rows": [
          {"id": 1, "cat": "a", "price": 10, "ok": true, "info": {"size": 1}, "tags": ["x"]},
          {"id": 2, "cat": "a", "price": 20, "ok": false, "info": {"size": 1}, "tags": ["x"]},
          {"id": 3, "cat": "b", "price": 30, "ok": true, "info": {"size": 2}, "tags": ["y"]}
        ]}
        """;

    @TempDir
    File tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
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
        return mapper.readTree(executor.execute(sql));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "SELECT cat, id FROM t GROUP BY cat                 | Column 'id' must be in GROUP BY",
        "SELECT cat, COUNT(*) FROM t                        | Column 'cat' must be in GROUP BY",
        "SELECT * FROM t GROUP BY cat                       | SELECT * with GROUP BY",
        "SELECT *, COUNT(*) FROM t                          | not supported",
        "SELECT cat FROM t GROUP BY LOWER(cat)              | Expression in GROUP BY",
        "SELECT cat FROM t GROUP BY price + 1               | Expression in GROUP BY",
        "SELECT COUNT(*) AS n FROM t GROUP BY n             | GROUP BY cannot use an aggregate",
        "SELECT COUNT(*) FROM t GROUP BY 1                  | GROUP BY cannot use an aggregate",
        "SELECT cat FROM t GROUP BY 2                       | not in the SELECT list",
        "SELECT SUM(price + 1) FROM t                       | Aggregate argument is not supported",
        "SELECT SUM(*) FROM t                               | Aggregate argument is not supported",
        "SELECT COUNT(DISTINCT *) FROM t                    | Invalid SQL syntax",
        "SELECT SUM(1) FROM t                               | Aggregate argument is not supported",
        "SELECT COUNT(id, cat) FROM t                       | Aggregate argument is not supported",
        "SELECT SUM(COUNT(id)) FROM t                       | Aggregate argument is not supported",
        "SELECT MEDIAN(price) FROM t                        | not supported",
        "SELECT COUNT(*) + 1 FROM t                         | not supported",
        "SELECT id FROM t WHERE COUNT(*) > 1                | Function in WHERE",
        "SELECT id FROM t HAVING id > 1                     | HAVING",
        "SELECT cat FROM t GROUP BY cat HAVING id > 1       | HAVING can only use",
        "SELECT cat FROM t GROUP BY cat HAVING LOWER(cat) = 'a' | Function in HAVING",
        "SELECT cat FROM t GROUP BY cat HAVING COUNT(*) + 1 > 1 | Expression in HAVING",
        "SELECT cat FROM t GROUP BY cat ORDER BY id         | ORDER BY can only use",
        "SELECT cat FROM t GROUP BY cat ORDER BY COUNT(*) + 1 | Expression in ORDER BY",
    })
    void rejectedWithAClearMessage(String sql, String message) {
        QueryParseException e = assertThrows(QueryParseException.class, () -> query(sql));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    @Test
    void anUnaliasedAggregateIsNamedAsWritten() throws Exception {
        JsonNode row = query("SELECT COUNT(*), count(id), SUM(price) FROM t").get(0);

        assertEquals(3, row.get("COUNT(*)").asInt());
        assertEquals(3, row.get("count(id)").asInt());
        assertEquals(60, row.get("SUM(price)").asInt());
    }

    @Test
    void anAliasNamesTheColumn() throws Exception {
        JsonNode row = query("SELECT COUNT(*) AS total FROM t").get(0);

        assertEquals(1, row.size());
        assertEquals(3, row.get("total").asInt());
    }

    @Test
    void aSumOfIntegersIsAnInteger() throws Exception {
        assertTrue(query("SELECT SUM(price) AS s FROM t").get(0).get("s").isIntegralNumber());
    }

    @Test
    void anAverageIsADecimal() throws Exception {
        assertEquals(20.0, query("SELECT AVG(price) AS a FROM t").get(0).get("a").asDouble());
        assertTrue(query("SELECT AVG(price) AS a FROM t").get(0).get("a").isFloatingPointNumber());
    }

    @Test
    void largeIntegerSumsStayExact() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(),
            "{\"rows\": [{\"n\": 9007199254740993}, {\"n\": 9007199254740993}]}");

        assertEquals("18014398509481986", query("SELECT SUM(n) AS s FROM t").get(0).get("s").asText());
    }

    @Test
    void numericTextInSumUsesItsLeadingNumber() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(),
            "{\"rows\": [{\"n\": \"12\"}, {\"n\": \"3.5kg\"}, {\"n\": \"none\"}, {\"n\": \".5\"}, {\"n\": \"1e2\"}, {\"n\": \"7.\"}]}");

        assertEquals(123.0, query("SELECT SUM(n) AS s FROM t").get(0).get("s").asDouble());
    }

    @Test
    void groupsOnNestedFields() throws Exception {
        JsonNode rows = query("SELECT info.size, COUNT(*) AS n FROM t GROUP BY info.size ORDER BY info.size");

        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).get("size").asInt());
        assertEquals(2, rows.get(0).get("n").asInt());
    }

    @Test
    void aggregatesNestedFields() throws Exception {
        assertEquals(4, query("SELECT SUM(info.size) AS s FROM t").get(0).get("s").asInt());
    }

    @Test
    void groupsOnBooleans() throws Exception {
        assertEquals(2, query("SELECT ok, COUNT(*) AS n FROM t GROUP BY ok").size());
    }

    @Test
    void groupsOnArraysByValue() throws Exception {
        assertEquals(2, query("SELECT tags, COUNT(*) AS n FROM t GROUP BY tags").size());
    }

    @Test
    void aGroupedNumberAndItsTextFormAreDifferentGroups() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), "{\"rows\": [{\"k\": 1}, {\"k\": \"1\"}, {\"k\": 1.0}]}");

        assertEquals(2, query("SELECT k, COUNT(*) AS n FROM t GROUP BY k").size());
    }

    @Test
    void groupsKeepTheirFirstSeenOrderWithoutOrderBy() throws Exception {
        JsonNode rows = query("SELECT cat FROM t GROUP BY cat");

        assertEquals("a", rows.get(0).get("cat").asText());
        assertEquals("b", rows.get(1).get("cat").asText());
    }

    @Test
    void groupByWorksAfterUnnest() throws Exception {
        JsonNode rows = query("SELECT tag, COUNT(*) AS n FROM t, UNNEST(tags) AS u(tag) GROUP BY tag ORDER BY tag");

        assertEquals("x", rows.get(0).get("tag").asText());
        assertEquals(2, rows.get(0).get("n").asInt());
    }

    @Test
    void sameColumnNameFromTwoTablesFallsBackToTheFullName() throws Exception {
        JsonNode rows = query("SELECT a.cat, b.cat, COUNT(*) AS n FROM t a JOIN t b ON a.id = b.id GROUP BY a.cat, b.cat");

        assertTrue(rows.get(0).has("a.cat"), rows.toString());
        assertTrue(rows.get(0).has("b.cat"), rows.toString());
    }

    @Test
    void dryRunAcceptsAndRejectsTheSameAsExecution() throws Exception {
        assertNotNull(executor.dryRunValidate("SELECT cat, COUNT(*) FROM t GROUP BY cat"));
        assertThrows(QueryParseException.class, () -> executor.dryRunValidate("SELECT cat, id FROM t GROUP BY cat"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT cat, COUNT(*) AS n FROM t GROUP BY cat",
        "SELECT cat, COUNT(*) AS n FROM t GROUP BY cat HAVING COUNT(*) > 1",
        "SELECT cat, COUNT(*) AS n FROM t GROUP BY id, cat",
        "SELECT cat, SUM(price) AS n FROM t GROUP BY cat",
        "SELECT cat, SUM(DISTINCT price) AS n FROM t GROUP BY cat",
    })
    void cteResultsDoNotShareACacheEntry(String other) throws Exception {
        QueryExecutor cached = new QueryExecutor(mappings, tempDir, new CacheManager(tempDir));
        String first = "WITH c AS (SELECT cat, COUNT(*) AS n FROM t WHERE id > 0 GROUP BY cat) SELECT * FROM c";
        String second = "WITH c AS (" + other + ") SELECT * FROM c";

        cached.execute(first);

        assertEquals(executor.execute(second), cached.execute(second));
    }
}
