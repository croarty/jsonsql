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
 * Tests that WHERE follows SQL three-valued logic: a condition on a NULL or missing value
 * is UNKNOWN, and a row is returned only when the whole clause is TRUE.
 */
class ThreeValuedLogicTest {

    private static final String DATA = """
        {"rows": [
          {"id": 1, "price": 10, "cat": "A", "qty": 5},
          {"id": 2, "price": 50, "cat": "B", "qty": 0},
          {"id": 3, "price": null, "cat": "A", "qty": 1},
          {"id": 4, "cat": "B"},
          {"id": 5, "price": 50, "cat": null, "qty": 2},
          {"id": 6, "price": 10, "qty": 3}
        ]}
        """;

    @TempDir
    File tempDir;

    private QueryExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), DATA);
        MappingManager mappings = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        mappings.addMapping("t", "t.json:$.rows[*]");
        executor = new QueryExecutor(mappings, tempDir);
    }

    private String ids(String where) throws Exception {
        JsonNode rows = new ObjectMapper().readTree(executor.execute("SELECT id FROM t WHERE " + where));
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get("id").asText()));
        return String.join(" ", ids);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "price > 20                       | 2 5",
        "NOT (price > 20)                 | 1 6",
        "NOT price > 20                   | 1 6",
        "NOT (price = 10)                 | 2 5",
        "NOT (NOT (price > 20))           | 2 5",
        "price != 10                      | 2 5",
    })
    void notDoesNotTurnUnknownIntoTrue(String where, String expected) throws Exception {
        assertEquals(expected, ids(where));
    }

    @Test
    void notEqualsAndNotOfEqualsAgree() throws Exception {
        assertEquals(ids("price != 10"), ids("NOT (price = 10)"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        // TRUE AND UNKNOWN is UNKNOWN; FALSE AND UNKNOWN is FALSE
        "cat = 'A' AND price > 0            | 1",
        "NOT (cat = 'A' AND price > 0)      | 2 4",
        "NOT (cat = 'B' AND price > 100)    | 1 2 3 5 6",
        // TRUE OR UNKNOWN is TRUE; FALSE OR UNKNOWN is UNKNOWN
        "cat = 'A' OR price > 20            | 1 2 3 5",
        "NOT (cat = 'A' OR price > 20)      | ",
        "NOT (cat = 'B' OR price > 20)      | 1",
    })
    void andOrCombineUnknownAsSqlDoes(String where, String expected) throws Exception {
        assertEquals(expected == null ? "" : expected.trim(), ids(where));
    }

    @Test
    void unknownOnTheLeftDoesNotHideARightSideThatIsFalse() throws Exception {
        // Rows 3 and 4 have no price, so the left side is UNKNOWN, but the right side is FALSE,
        // which makes the AND FALSE and the NOT TRUE. Rows 5 and 6 have no cat, so they stay UNKNOWN.
        assertEquals("1 2 3 4", ids("NOT (price > 0 AND cat = 'Z')"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "cat IN ('A')                     | 1 3",
        "cat NOT IN ('A')                 | 2 4",
        "NOT (cat IN ('A'))               | 2 4",
        "cat IN ('A', NULL)               | 1 3",
        "NOT (cat NOT IN ('A', 'B'))      | 1 2 3 4",
    })
    void inTreatsNullValuesAsUnknown(String where, String expected) throws Exception {
        assertEquals(expected, ids(where));
    }

    @Test
    void notInWithNullInTheListNeverMatches() throws Exception {
        assertEquals("", ids("cat NOT IN ('A', NULL)"));
    }

    @Test
    void notOfInWithNullInTheListNeverMatchesAnUnlistedValue() throws Exception {
        assertEquals("", ids("NOT (cat IN ('A', NULL))"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "cat LIKE 'A%'                    | 1 3",
        "cat NOT LIKE 'A%'                | 2 4",
        "NOT (cat LIKE 'A%')              | 2 4",
        "cat ILIKE 'a%'                   | 1 3",
    })
    void likeTreatsNullValuesAsUnknown(String where, String expected) throws Exception {
        assertEquals(expected, ids(where));
    }

    @Test
    void likeAndNotLikeBothExcludeNumbers() throws Exception {
        assertEquals("", ids("qty LIKE '5'"));
        assertEquals("", ids("qty NOT LIKE '5'"));
        assertEquals("", ids("NOT (qty LIKE '5')"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "price = NULL",
        "price != NULL",
        "price > NULL",
        "NOT (price = NULL)",
        "cat LIKE NULL",
        "cat NOT LIKE NULL",
    })
    void comparingWithLiteralNullNeverMatches(String where) throws Exception {
        assertEquals("", ids(where));
    }

    @Test
    void isNullIsNeverUnknown() throws Exception {
        assertEquals("3 4", ids("price IS NULL"));
        assertEquals("1 2 5 6", ids("NOT (price IS NULL)"));
        assertEquals("1 2 5 6", ids("price IS NOT NULL"));
        assertEquals("3 4", ids("NOT (price IS NOT NULL)"));
    }

    @Test
    void isNullRescuesRowsThatNotExcludes() throws Exception {
        assertEquals("1 3 4 6", ids("NOT (price > 20) OR price IS NULL"));
    }
}
