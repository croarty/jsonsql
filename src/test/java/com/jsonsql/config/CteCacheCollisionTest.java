package com.jsonsql.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.query.QueryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two CTEs that give different results must never share a cache entry. Each case runs the
 * first query to fill the cache, then checks the second gets its own answer.
 */
class CteCacheCollisionTest {

    @TempDir
    File tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private QueryExecutor cached;
    private QueryExecutor uncached;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(new File(tempDir, "a.json").toPath(),
            "{\"rows\": [{\"id\": 1, \"k\": 1, \"j\": 7, \"n\": \"x\", \"tags\": [\"p\", \"q\"], \"other\": [\"r\"]}, "
                + "{\"id\": 2, \"k\": 2, \"j\": 8, \"n\": \"y\", \"tags\": [\"p\"], \"other\": [\"s\", \"t\"]}, "
                + "{\"id\": 3, \"k\": 9, \"j\": 9, \"n\": \"y\", \"tags\": [], \"other\": []}]}");
        Files.writeString(new File(tempDir, "b.json").toPath(),
            "{\"rows\": [{\"id\": 1, \"k\": 1, \"j\": 8, \"v\": \"one\"}, {\"id\": 2, \"k\": 2, \"j\": 7, \"v\": \"two\"}]}");

        MappingManager mappings = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        mappings.addMapping("a", "a.json:$.rows[*]");
        mappings.addMapping("b", "b.json:$.rows[*]");
        cached = new QueryExecutor(mappings, tempDir, new CacheManager(tempDir));
        uncached = new QueryExecutor(mappings, tempDir);
    }

    private JsonNode run(QueryExecutor executor, String cte) throws Exception {
        return mapper.readTree(executor.execute("WITH c AS (" + cte + ") SELECT * FROM c"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "join type      | SELECT a.id AS x, b.v AS y FROM a JOIN b ON a.k = b.k        | SELECT a.id AS x, b.v AS y FROM a LEFT JOIN b ON a.k = b.k",
        "join type back | SELECT a.id AS x, b.v AS y FROM a LEFT JOIN b ON a.k = b.k   | SELECT a.id AS x, b.v AS y FROM a JOIN b ON a.k = b.k",
        "join condition | SELECT a.id AS x, b.v AS y FROM a JOIN b ON a.k = b.k        | SELECT a.id AS x, b.v AS y FROM a JOIN b ON a.j = b.j",
        "join table     | SELECT a.id AS x FROM a JOIN b ON a.k = b.k                  | SELECT a.id AS x FROM a JOIN a b ON a.k = b.k",
        "where          | SELECT id FROM a WHERE id > 1                                | SELECT id FROM a WHERE id > 2",
        "select list    | SELECT id FROM a                                             | SELECT n FROM a",
        "alias          | SELECT id AS x FROM a                                        | SELECT id AS y FROM a",
        "order          | SELECT id FROM a ORDER BY id LIMIT 1                         | SELECT id FROM a ORDER BY id DESC LIMIT 1",
        "limit          | SELECT id FROM a ORDER BY id LIMIT 1                         | SELECT id FROM a ORDER BY id LIMIT 2",
        "distinct       | SELECT n FROM a                                              | SELECT DISTINCT n FROM a",
        "table          | SELECT id FROM a                                             | SELECT id FROM b",
        "unnest         | SELECT id, x FROM a, UNNEST(tags) AS u(x)                    | SELECT id, x FROM a, UNNEST(other) AS u(x)",
    })
    void differentCtesDoNotShareResults(String label, String first, String second) throws Exception {
        run(cached, first);

        assertEquals(run(uncached, second), run(cached, second), label);
    }

    @Test
    void theSameCteIsServedFromTheCacheWithTheSameResult() throws Exception {
        String cte = "SELECT id FROM a WHERE id > 1";

        assertEquals(run(cached, cte), run(cached, cte));
    }

    @Test
    void anEmptyResultIsNotServedAsAnotherCtesResult() throws Exception {
        run(cached, "SELECT id FROM a WHERE id > 99");

        assertEquals(3, run(cached, "SELECT id FROM a").size());
    }
}
