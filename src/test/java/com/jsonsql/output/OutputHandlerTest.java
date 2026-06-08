package com.jsonsql.output;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OutputHandlerTest {

    @TempDir
    Path tempDir;

    private OutputHandler outputHandler;
    private String testJson;

    @BeforeEach
    void setUp() {
        outputHandler = new OutputHandler(false);
        testJson = "[{\"name\":\"Widget\",\"price\":19.99},{\"name\":\"Gadget\",\"price\":29.99}]";
    }

    @Test
    void testOutputToFile() throws Exception {
        File outputFile = tempDir.resolve("output.json").toFile();
        
        outputHandler.handleOutput(testJson, outputFile, false);
        
        assertTrue(outputFile.exists());
        String content = Files.readString(outputFile.toPath());
        assertEquals(testJson, content);
    }

    @Test
    void testPrettyPrintOutput() throws Exception {
        OutputHandler prettyHandler = new OutputHandler(true);
        File outputFile = tempDir.resolve("output.json").toFile();
        
        prettyHandler.handleOutput(testJson, outputFile, false);
        
        String content = Files.readString(outputFile.toPath());
        assertTrue(content.contains("\n")); // Pretty-printed should have newlines
        assertTrue(content.contains("  ")); // Should have indentation
    }

    @Test
    void testOutputWithNonExistentDirectory() throws Exception {
        File outputFile = tempDir.resolve("subdir/output.json").toFile();
        
        // Should handle creation of parent directories
        assertDoesNotThrow(() -> 
            outputHandler.handleOutput(testJson, outputFile, false)
        );
    }

    @Test
    void testFileOutputIsUtf8Encoded() throws Exception {
        // Non-ASCII content must be written as UTF-8 regardless of the platform default charset
        String unicodeJson = "[{\"name\":\"Caf\u00e9 \u00fcber \u2603\"}]";
        File outputFile = tempDir.resolve("unicode.json").toFile();

        outputHandler.handleOutput(unicodeJson, outputFile, false);

        byte[] bytes = Files.readAllBytes(outputFile.toPath());
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(unicodeJson, decoded);
    }

    @Test
    void testNestedParentDirectoriesAreCreated() throws Exception {
        File outputFile = tempDir.resolve("a/b/c/output.json").toFile();

        outputHandler.handleOutput(testJson, outputFile, false);

        assertTrue(outputFile.exists());
        assertEquals(testJson, Files.readString(outputFile.toPath()));
    }

    @Test
    void testCsvOutputIncludesHeaderRow() throws Exception {
        OutputHandler csvHandler = new OutputHandler(false, OutputFormat.CSV);
        File outputFile = tempDir.resolve("output.csv").toFile();

        csvHandler.handleOutput(testJson, outputFile, false);

        assertEquals("""
            name,price
            Widget,19.99
            Gadget,29.99
            """, Files.readString(outputFile.toPath()));
    }
}

