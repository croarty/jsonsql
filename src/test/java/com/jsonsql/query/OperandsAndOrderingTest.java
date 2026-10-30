package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the queries that used to return wrong results with no error: reversed and
 * field-to-field comparisons, ORDER BY aliases and positions, LIKE ESCAPE and quoted names.
 */
class OperandsAndOrderingTest {

    private static final String DATA = """
        {"rows": [
          {"id": 1, "name": "Widget", "price": 10, "qty": 5, "on": true},
          {"id": 2, "name": "50%_off", "price": 20, "qty": 20, "on": false},
          {"id": 3, "name": "a_b", "price": 3, "qty": 7, "on": true},
          {"id": 4, "name": "O'Brien", "qty": 1},
          {"id": 5, "name": "axb", "price": null, "qty": 9}
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

    private JsonNode query(String sql) throws Exception {
        return new ObjectMapper().readTree(executor.execute(sql));
    }

    private String ids(String sql) throws Exception {
        List<String> ids = new ArrayList<>();
        query(sql).forEach(row -> ids.add(row.get("id").asText()));
        return String.join(" ", ids);
    }

    private String where(String condition) throws Exception {
        return ids("SELECT id FROM t WHERE " + condition);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "5 < price      | 1 2",
        "5 <= price     | 1 2",
        "10 > price     | 3",
        "10 >= price    | 1 3",
        "10 = price     | 1",
        "10 != price    | 2 3",
        "'Widget' = name | 1",
        "'V' < name     | 1 3 5",
    })
    void literalOnTheLeftWorks(String condition, String expected) throws Exception {
        assertEquals(expected, where(condition));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "price > qty    | 1",
        "price < qty    | 3",
        "price = qty    | 2",
        "price != qty   | 1 3",
        "price >= qty   | 1 2",
        "qty <= price   | 1 2",
    })
    void fieldsCanBeComparedWithEachOther(String condition, String expected) throws Exception {
        assertEquals(expected, where(condition));
    }

    @Test
    void fieldComparisonWithANullSideIsUnknown() throws Exception {
        assertEquals("", where("price = qty AND id = 4"));
        assertEquals("", where("NOT (price = qty) AND id = 4"));
    }

    @Test
    void booleansStillWorkAsLiterals() throws Exception {
        assertEquals("1 3", where("on = true"));
        assertEquals("2", where("on = false"));
        assertEquals("2", where("on != TRUE"));
        assertEquals("1 3", where("on = 'true'"));
    }

    @Test
    void literalsCanBeComparedWithEachOther() throws Exception {
        assertEquals("", where("1 = 2"));
        assertEquals("1 2 3 4 5", where("1 = 1"));
        assertEquals("1 2 3 4 5", where("9 < 10"));
    }

    @Test
    void negativeNumbersAreLiterals() throws Exception {
        assertEquals("1 2 3", where("price > -1.5"));
        assertEquals("1 2 3", where("-1 < price"));
    }

    @Test
    void escapedQuotesInStringsAreUnescaped() throws Exception {
        assertEquals("4", where("name = 'O''Brien'"));
        assertEquals("4", where("name IN ('O''Brien')"));
        assertEquals("4", where("name LIKE 'O''%'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "price + 1 > 20", "price > qty + 1", "LOWER(name) = 'widget'", "name = UPPER('x')",
        "LENGTH(name) > 3", "price * 2 = 20", "-price < 3", "LOWER(name) LIKE 'w%'",
        "LOWER(name) IS NULL", "LOWER(name) IN ('widget')", "name IN (UPPER('x'))",
        "NOT (price + 1 > 20)", "id = 1 AND price + 1 > 20", "id = 1 OR LOWER(name) = 'x'",
    })
    void arithmeticAndFunctionsAreRejected(String condition) {
        Exception e = assertThrows(QueryParseException.class, () -> where(condition));
        assertTrue(e.getMessage().contains("not supported"), e.getMessage());
    }

    @Test
    void unsupportedWhereFailsEvenWithNoMatchingRows() {
        assertThrows(QueryParseException.class, () -> where("id = 999 AND price + 1 > 20"));
    }

    @Test
    void orderByAliasSorts() throws Exception {
        JsonNode rows = query("SELECT id AS a, qty AS b FROM t ORDER BY b DESC");
        assertEquals(2, rows.get(0).get("a").asInt());
        assertEquals(4, rows.get(4).get("a").asInt());
    }

    @Test
    void orderByAliasIgnoresCase() throws Exception {
        assertEquals("2 5 3 1 4", ids("SELECT id, qty AS Stock FROM t ORDER BY STOCK DESC"));
    }

    @Test
    void anAliasTakesPriorityOverAFieldWithTheSameName() throws Exception {
        assertEquals("4 1 3 5 2", ids("SELECT id, qty AS price FROM t ORDER BY price"));
    }

    @Test
    void orderByPositionSorts() throws Exception {
        assertEquals("2 5 3 1 4", ids("SELECT id, qty FROM t ORDER BY 2 DESC"));
        assertEquals("5 4 3 2 1", ids("SELECT id, qty FROM t ORDER BY 1 DESC"));
    }

    @Test
    void orderByStillWorksOnAnUnselectedField() throws Exception {
        assertEquals("4 1 3 5 2", ids("SELECT id FROM t ORDER BY qty"));
    }

    @Test
    void orderByMixesPositionsAliasesAndFields() throws Exception {
        assertEquals("4 1 3 5 2", ids("SELECT id, qty AS q FROM t ORDER BY q, 1, name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT id FROM t ORDER BY 2", "SELECT id FROM t ORDER BY 0", "SELECT * FROM t ORDER BY 1",
        "SELECT id FROM t ORDER BY price * 2", "SELECT id FROM t ORDER BY LOWER(name)",
    })
    void invalidOrderByIsRejected(String sql) {
        assertThrows(QueryParseException.class, () -> query(sql));
    }

    @Test
    void unionOrderByPositionUsesOutputNames() throws Exception {
        JsonNode rows = query("SELECT id FROM t WHERE id < 3 UNION SELECT id FROM t WHERE id > 3 ORDER BY 1 DESC");
        assertEquals(5, rows.get(0).get("id").asInt());
        assertEquals(1, rows.get(3).get("id").asInt());
    }

    @Test
    void unionRejectsExpressionsAndBadPositionsInOrderBy() {
        assertThrows(QueryParseException.class, () -> query("SELECT id FROM t UNION SELECT id FROM t ORDER BY id * 2"));
        assertThrows(QueryParseException.class, () -> query("SELECT id FROM t UNION SELECT id FROM t ORDER BY 2"));
    }

    @Test
    void likeEscapeMatchesLiteralWildcards() throws Exception {
        assertEquals("2", where("name LIKE '50!%!_off' ESCAPE '!'"));
        assertEquals("3", where("name LIKE 'a!_b' ESCAPE '!'"));
        assertEquals("1 2 4 5", where("name NOT LIKE 'a!_b' ESCAPE '!'"));
    }

    @Test
    void likeWithoutEscapeStillTreatsWildcardsAsWildcards() throws Exception {
        assertEquals("3 5", where("name LIKE 'a_b'"));
    }

    @Test
    void ilikeHonoursEscape() throws Exception {
        assertEquals("3", where("name ILIKE 'A!_B' ESCAPE '!'"));
    }

    @Test
    void likeEscapeCanEscapeItself() throws Exception {
        Files.writeString(new File(tempDir, "t.json").toPath(), "{\"rows\": [{\"id\": 1, \"name\": \"a!b\"}]}");
        assertEquals("1", where("name LIKE 'a!!b' ESCAPE '!'"));
    }

    @Test
    void likeEscapeMustBeOneCharacter() {
        assertThrows(UnsupportedExpressionException.class, () -> where("name LIKE 'a' ESCAPE 'xy'"));
    }

    @Test
    void likePatternCanComeFromAField() throws Exception {
        assertEquals("1 2 3 4 5", where("name LIKE name OR name = 'a_b'"));
    }

    @Test
    void doubleQuotedNamesAreFields() throws Exception {
        assertEquals(1, query("SELECT \"id\" FROM t WHERE \"id\" = 1").get(0).get("id").asInt());
        assertEquals("5 4 3 2 1", ids("SELECT \"id\" FROM t ORDER BY \"id\" DESC"));
        assertEquals("1", where("\"name\" LIKE 'W%'"));
        assertEquals("4", where("\"price\" IS NULL AND \"id\" = 4"));
    }

    @Test
    void doubleQuotedValuesStillWorkAsText() throws Exception {
        assertEquals("1", where("name = \"Widget\""));
    }

    @Test
    void anUnquotedWordIsAField() throws Exception {
        assertEquals("", where("name = Widget"));
    }
}
