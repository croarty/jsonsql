package com.jsonsql.output;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
}

