package com.jsonsql.output;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CsvFormatterTest {

    private CsvFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new CsvFormatter();
    }

    @Test
    void formatIncludesHeaderAndRows() throws Exception {
        String json = """
            [{"name":"Widget","price":19.99},{"name":"Gadget","price":29.99}]
            """;

        String csv = formatter.format(json);

        assertEquals("""
            name,price
            Widget,19.99
            Gadget,29.99
            """, csv);
    }

    @Test
    void formatEscapesCommasQuotesAndNewlines() throws Exception {
        String json = "[{\"name\":\"A,B\",\"note\":\"Say \\\"hi\\\"\",\"line\":\"one\\ntwo\"}]";

        String csv = formatter.format(json);

        assertTrue(csv.startsWith("name,note,line\n"));
        assertTrue(csv.contains("\"A,B\""));
        assertTrue(csv.contains("\"Say \"\"hi\"\"\""));
        assertTrue(csv.contains("\"one\ntwo\""));
    }

    @Test
    void formatNullValuesAsEmptyCells() throws Exception {
        String json = """
            [{"name":"Widget","category":null}]
            """;

        String csv = formatter.format(json);

        assertEquals("""
            name,category
            Widget,
            """, csv);
    }

    @Test
    void formatEmptyArrayProducesNoOutput() throws Exception {
        assertEquals("", formatter.format("[]"));
    }

    @Test
    void formatPreservesColumnOrderFromFirstRow() throws Exception {
        String json = """
            [{"z":1,"a":2},{"b":3,"a":4}]
            """;

        String csv = formatter.format(json);

        assertEquals("""
            z,a,b
            1,2,
            ,4,3
            """, csv);
    }

    @Test
    void formatSerializesNestedValuesAsJson() throws Exception {
        String json = """
            [{"name":"Widget","tags":["a","b"]}]
            """;

        String csv = formatter.format(json);

        assertEquals("""
            name,tags
            Widget,"[""a"",""b""]"
            """, csv);
    }

    @Test
    void escapeFieldHandlesNull() {
        assertEquals("", CsvFormatter.escapeField(null));
    }

    @Test
    void formatRejectsNonArrayInput() {
        assertThrows(Exception.class, () -> formatter.format("{\"name\":\"Widget\"}"));
    }
}
