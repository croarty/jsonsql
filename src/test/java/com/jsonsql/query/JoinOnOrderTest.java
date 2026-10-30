package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two sides of a JOIN ON condition can be written in either order.
 */
class JoinOnOrderTest {

    @TempDir
    File tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private QueryExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        write("p.json", "{\"rows\": [{\"id\": 1, \"name\": \"Widget\"}, {\"id\": 2, \"name\": \"Gadget\"}]}");
        write("o.json", "{\"rows\": [{\"id\": 100, \"pid\": 1, \"cid\": 7}, {\"id\": 101, \"pid\": 2, \"cid\": 8}, {\"id\": 102, \"pid\": 9, \"cid\": 7}]}");
        write("c.json", "{\"rows\": [{\"id\": 7, \"label\": \"Ann\"}, {\"id\": 8, \"label\": \"Bo\"}]}");

        MappingManager mappings = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        mappings.addMapping("p", "p.json:$.rows[*]");
        mappings.addMapping("o", "o.json:$.rows[*]");
        mappings.addMapping("c", "c.json:$.rows[*]");
        executor = new QueryExecutor(mappings, tempDir);
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(new File(tempDir, name).toPath(), content);
    }

    private JsonNode query(String sql) throws Exception {
        return mapper.readTree(executor.execute(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {"o.pid = p.id", "p.id = o.pid"})
    void innerJoinMatchesWithEitherOrder(String on) throws Exception {
        JsonNode rows = query("SELECT o.id AS oi, p.name AS n FROM o JOIN p ON " + on + " ORDER BY o.id");

        assertEquals(2, rows.size());
        assertEquals(100, rows.get(0).get("oi").asInt());
        assertEquals("Widget", rows.get(0).get("n").asText());
        assertEquals("Gadget", rows.get(1).get("n").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"o.pid = p.id", "p.id = o.pid"})
    void leftJoinKeepsMatchesWithEitherOrder(String on) throws Exception {
        JsonNode rows = query("SELECT o.id AS oi, p.name AS n FROM o LEFT JOIN p ON " + on + " ORDER BY o.id");

        assertEquals(3, rows.size());
        assertEquals("Widget", rows.get(0).get("n").asText());
        assertEquals("Gadget", rows.get(1).get("n").asText());
        assertTrue(rows.get(2).get("n").isNull());
    }

    @Test
    void worksOnEveryJoinInAChain() throws Exception {
        JsonNode rows = query("""
            SELECT o.id AS oi, p.name AS n, c.label AS l
            FROM o JOIN p ON p.id = o.pid JOIN c ON c.id = o.cid
            ORDER BY o.id""");

        assertEquals(2, rows.size());
        assertEquals("Ann", rows.get(0).get("l").asText());
        assertEquals("Bo", rows.get(1).get("l").asText());
    }

    @Test
    void worksWhenTablesHaveNoAlias() throws Exception {
        assertEquals(2, query("SELECT o.id FROM o JOIN p ON p.id = o.pid").size());
    }

    @Test
    void worksWithExplicitAliases() throws Exception {
        assertEquals(2, query("SELECT a.id FROM o a JOIN p b ON b.id = a.pid").size());
    }

    @Test
    void worksForAJoinedCte() throws Exception {
        JsonNode rows = query("""
            WITH x AS (SELECT id, name FROM p)
            SELECT o.id AS oi, x.name AS n FROM o JOIN x ON x.id = o.pid""");

        assertEquals(2, rows.size());
    }

    @Test
    void worksForASelfJoin() throws Exception {
        assertEquals(3, query("SELECT a.id FROM o a JOIN o b ON b.id = a.id").size());
    }

    @Test
    void worksForAnUnnestedKey() throws Exception {
        write("u.json", "{\"rows\": [{\"id\": 1, \"items\": [{\"pid\": 1}, {\"pid\": 2}]}]}");
        MappingManager mappings = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        mappings.addMapping("u", "u.json:$.rows[*]");
        executor = new QueryExecutor(mappings, tempDir);

        JsonNode rows = query("SELECT p.name FROM u, UNNEST(u.items) AS i(item) JOIN p ON p.id = item.pid");

        assertEquals(2, rows.size());
    }
}
