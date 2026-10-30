package com.jsonsql.query;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for how data files are parsed. Truncated or concatenated input must fail
 * rather than return partial rows, and common non-standard JSON must keep working.
 */
class JsonPathConfigTest {

    private final Configuration config = JsonPathConfig.create();

    private Object readId(String json) {
        return JsonPath.using(config).parse(json).read("$.items[0].id");
    }

    @Test
    void usesJacksonRatherThanJsonSmart() {
        assertInstanceOf(JacksonJsonProvider.class, config.jsonProvider());
    }

    @Test
    void readsStandardJson() {
        assertEquals(1, readId("{\"items\": [{\"id\": 1}]}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"items\": [{\"id\": 1}",
        "{\"items\": [{\"id\": 1}, {\"id\"",
        "{\"items\": [{\"id\": 1}, {\"id\":",
        "{\"items\": [{\"id\": \"abc",
        "{\"items\": [{\"id\": 1},",
        "{\"items\": [{\"id\": 1}]",
    })
    void rejectsTruncatedJson(String json) {
        assertThrows(RuntimeException.class, () -> readId(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"items\": [{\"id\": 1}]} trailing",
        "{\"items\": [{\"id\": 1}]}{\"items\": [{\"id\": 2}]}",
        "{\"items\": [{\"id\": 1}]}\n{\"items\": [{\"id\": 2}]}\n",
    })
    void rejectsContentAfterTheFirstDocument(String json) {
        assertThrows(RuntimeException.class, () -> readId(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{'items': [{'id': 1}]}",
        "{items: [{id: 1}]}",
        "{\"items\": [{\"id\": 1},]}",
        "{\"items\": [{\"id\": 1,}]}",
        "{\"items\": [{\"id\": 1}]} // comment",
        "{/* comment */ \"items\": [{\"id\": 1}]}",
        "# comment\n{\"items\": [{\"id\": 1}]}",
        "{\"items\": [{\"id\": 001}]}",
    })
    void acceptsCommonNonStandardJson(String json) {
        assertEquals(1, readId(json));
    }

    @Test
    void acceptsTrailingDecimalPoint() {
        assertEquals(5.0, readId("{\"items\": [{\"id\": 5.}]}"));
    }

    @Test
    void acceptsNotANumber() {
        assertEquals(Double.NaN, readId("{\"items\": [{\"id\": NaN}]}"));
    }

    @Test
    void acceptsControlCharactersInStrings() {
        assertEquals("a\tb", readId("{\"items\": [{\"id\": \"a\tb\"}]}"));
    }

    @Test
    void keepsLargeIntegersExact() {
        assertEquals(9007199254740993L, readId("{\"items\": [{\"id\": 9007199254740993}]}"));
    }

    @Test
    void keepsTheLastValueOfADuplicateKey() {
        assertEquals(2, readId("{\"items\": [{\"id\": 1, \"id\": 2}]}"));
    }

    @Test
    void returnsNullForAMissingLeaf() {
        Object value = JsonPath.using(config).parse("{\"items\": [{\"id\": 1}]}").read("$.items[0].missing");
        assertNull(value);
    }

    @Test
    void supportsJsonPathFilters() {
        String json = "{\"items\": [{\"id\": 1, \"p\": 5}, {\"id\": 2, \"p\": 50}]}";
        Object ids = JsonPath.using(config).parse(json).read("$.items[?(@.p > 10)].id");
        assertEquals("[2]", ids.toString());
    }
}
