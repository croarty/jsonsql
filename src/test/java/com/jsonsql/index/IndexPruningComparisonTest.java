package com.jsonsql.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import com.jsonsql.query.ParsedQuery;
import com.jsonsql.query.QueryExecutor;
import com.jsonsql.query.QueryParser;
import com.jsonsql.query.TableFileResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruning must compare values exactly as WHERE does, or it would skip a file holding a row
 * that matches. Each file here holds one kind of value, so a literal of another kind
 * decides whether the whole file can be skipped.
 */
class IndexPruningComparisonTest {

    @TempDir
    File tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private QueryExecutor indexed;
    private QueryExecutor fullScan;
    private IndexPlanner planner;
    private TableFileResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        MappingManager mappingManager = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        writeFile("t/numbers.json", """
            [{"id": 1, "v": 1, "flag": true}, {"id": 2, "v": 2.5, "flag": true}, {"id": 3, "v": 3, "flag": true}]""");
        writeFile("t/text.json", """
            [{"id": 4, "v": "1", "flag": "true"}, {"id": 5, "v": "2", "flag": "true"}, {"id": 6, "v": "abc", "flag": "yes"}]""");
        writeFile("t/mixed.json", """
            [{"id": 7, "v": 5, "flag": false}, {"id": 8, "v": "5", "flag": "false"}, {"id": 9, "v": null}]""");
        writeFile("t/large.json", """
            [{"id": 10, "v": 9007199254740993, "flag": false}, {"id": 11, "v": 9007199254740995, "flag": false}]""");
        mappingManager.addMapping("t", "t:$[*]");

        IndexStore store = new IndexStore(tempDir);
        IndexBuilder builder = new IndexBuilder(mappingManager, tempDir, store);
        IndexManager indexManager = new IndexManager(new File(tempDir, ".jsonsql-indexes.json"));
        for (String field : List.of("v", "flag")) {
            indexManager.addIndex("t", field);
            builder.build("t", field);
        }
        resolver = new TableFileResolver(mappingManager, tempDir);
        planner = new IndexPlanner(indexManager, store, mappingManager, resolver);
        indexed = new QueryExecutor(mappingManager, tempDir, null, indexManager);
        fullScan = new QueryExecutor(mappingManager, tempDir);
    }

    private void writeFile(String relPath, String content) throws IOException {
        File f = new File(tempDir, relPath);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    private String ids(QueryExecutor executor, String condition) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode row : objectMapper.readTree(executor.execute("SELECT id FROM t WHERE " + condition + " ORDER BY id"))) {
            ids.add(row.get("id").asText());
        }
        return String.join(",", ids);
    }

    private String filesKept(String condition) throws Exception {
        ParsedQuery query = new QueryParser().parse("SELECT id FROM t WHERE " + condition);
        List<String> names = new ArrayList<>();
        for (File file : planner.prune(query.getFromTable(), query, resolver.resolveFiles("t"))) {
            names.add(file.getName().replace(".json", ""));
        }
        names.sort(null);
        return String.join(",", names);
    }

    @ParameterizedTest(name = "WHERE {0} returns ids {1} from {2}")
    @CsvSource(delimiter = '|', value = {
        // A number literal never matches a file of text, and the other way round
        "v = 1                    | 1         | numbers",
        "v = '1'                  | 4         | text",
        "v = 5                    | 7         | mixed",
        "v = '5'                  | 8         | mixed",
        "v IN (1, 'abc')          | 1,6       | numbers,text",
        "v IN ('3', 'x')          | ''        | ''",
        // Exact numbers: no tolerance, and no rounding of large values
        "v = 1.00001              | ''        | ''",
        "v = 2.5                  | 2         | numbers",
        "v = 9007199254740993     | 10        | large",
        "v = 9007199254740992     | ''        | ''",
        "v > 9007199254740994     | 4,5,6,8,11 | large,mixed,text",
        "v >= 9007199254740995    | 4,5,6,8,11 | large,mixed,text",
        "v <= 9007199254740993    | 1,2,3,7,10 | large,mixed,numbers",
        "v < 9007199254740993     | 1,2,3,7   | mixed,numbers",
        // Ranges: every number is below every text value
        "v > 2                    | 2,3,4,5,6,7,8,10,11 | large,mixed,numbers,text",
        "v > 3                    | 4,5,6,7,8,10,11     | large,mixed,text",
        "v <= 3                   | 1,2,3     | mixed,numbers",
        "v < 'a'                  | 1,2,3,4,5,7,8,10,11 | large,mixed,numbers,text",
        "v < '0'                  | 1,2,3,7,10,11       | large,mixed,numbers",
        "v >= '2'                 | 5,6,8     | mixed,text",
        "v > 'abc'                | ''        | mixed",
        "v BETWEEN 1 AND 3        | 1,2,3     | mixed,numbers",
        "v BETWEEN '1' AND '2'    | 4,5       | mixed,text",
        "v BETWEEN 3 AND '1'      | 3,4,7,10,11         | large,mixed,numbers,text",
        // A boolean compares as the text true or false
        "flag = 'true'            | 1,2,3,4,5 | numbers,text",
        "flag = 'false'           | 7,8,10,11 | large,mixed",
        "flag = 'yes'             | 6         | text",
        "flag = 'banana'          | ''        | ''",
        "flag = 1                 | ''        | ''",
        "flag IN ('true', 'yes')  | 1,2,3,4,5,6 | numbers,text",
        // A calculation says nothing about which files to skip, but a plain condition beside it still does
        "v * 2 = 2                | 1         | large,mixed,numbers,text",
        "v = 1 + 1                | ''        | large,mixed,numbers,text",
        "2 < v + 0                | 2,3,7,10,11 | large,mixed,numbers,text",
        "CAST(v AS INTEGER) = 1   | 1,4       | large,mixed,numbers,text",
        "CAST(v AS INTEGER) IN (1, 5) | 1,4,7,8 | large,mixed,numbers,text",
        "v + 0 BETWEEN 1 AND 3    | 1,2,3     | large,mixed,numbers,text",
        "v + 0 > 2 AND flag = 'true' | 2,3    | numbers,text",
        "v = 5 AND v * 2 = 10     | 7         | mixed",
        "v * 2 = 10 OR v = 1      | 1,7       | large,mixed,numbers,text",
    })
    void pruningAgreesWithWhere(String condition, String expectedIds, String expectedFiles) throws Exception {
        assertEquals(expectedIds, ids(fullScan, condition), "full scan");
        assertEquals(expectedIds, ids(indexed, condition), "with indexes");
        assertEquals(expectedFiles, filesKept(condition), "files kept");
    }
}
