package com.jsonsql.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which join key values match each other: numbers compare numerically, a number matches
 * its text form, and NULL never matches anything.
 */
class JoinKeyMatchingTest {

    @TempDir
    File tempDir;

    private int matches(String leftKey, String rightKey) throws Exception {
        Files.writeString(new File(tempDir, "l.json").toPath(), "{\"rows\": [{\"id\": 1, \"k\": " + leftKey + "}]}");
        Files.writeString(new File(tempDir, "r.json").toPath(), "{\"rows\": [{\"id\": 2, \"k\": " + rightKey + "}]}");
        MappingManager mappings = new MappingManager(new File(tempDir, ".jsonsql-mappings.json"));
        mappings.addMapping("l", "l.json:$.rows[*]");
        mappings.addMapping("r", "r.json:$.rows[*]");

        String json = new QueryExecutor(mappings, tempDir).execute("SELECT l.id FROM l JOIN r ON l.k = r.k");
        return new ObjectMapper().readTree(json).size();
    }

    @ParameterizedTest(name = "{0} matches {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "1           | 1",
        "1           | 1.0",
        "1.5         | 1.5",
        "-2          | -2",
        "0           | 0.0",
        "1           | \"1\"",
        "\"1\"       | 1",
        "1.5         | \"1.5\"",
        "1           | \"1.0\"",
        "\"abc\"     | \"abc\"",
        "\"\"        | \"\"",
        "true        | true",
        "false       | false",
    })
    void keysThatMatch(String left, String right) throws Exception {
        assertEquals(1, matches(left, right));
    }

    @ParameterizedTest(name = "{0} does not match {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "1           | 2",
        "1           | 1.5",
        "1           | \"2\"",
        "1           | \"abc\"",
        "\"abc\"     | 1",
        "\"abc\"     | \"ABC\"",
        "\"abc\"     | \"abc \"",
        "\"1\"       | \"1.0\"",
        "true        | false",
        "null        | null",
        "null        | 1",
        "1           | null",
        "0           | null",
        "\"\"        | null",
    })
    void keysThatDoNotMatch(String left, String right) throws Exception {
        assertEquals(0, matches(left, right));
    }
}
