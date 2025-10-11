package com.jsonsql.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Handles output of query results to various destinations.
 */
public class OutputHandler {
    private final ObjectMapper objectMapper;
    private final boolean prettyPrint;

    public OutputHandler(boolean prettyPrint) {
        this.objectMapper = new ObjectMapper();
        this.prettyPrint = prettyPrint;
    }

    /**
     * Handle output to the specified destination(s).
     */
    public void handleOutput(String jsonResult, File outputFile, boolean clipboard) throws IOException {
        String output = formatOutput(jsonResult);

        // Output to file
        if (outputFile != null) {
            // Create parent directories if they don't exist
            if (outputFile.getParentFile() != null) {
                outputFile.getParentFile().mkdirs();
            }
            Files.writeString(outputFile.toPath(), output);
            System.out.println("Output written to: " + outputFile.getAbsolutePath());
        }

        // Output to clipboard
        if (clipboard) {
            copyToClipboard(output);
            System.out.println("Output copied to clipboard");
        }

        // Output to stdout if no file specified
        if (outputFile == null && !clipboard) {
            System.out.println(output);
        }
    }

    /**
     * Format the JSON output based on pretty-print setting.
     */
    private String formatOutput(String jsonResult) throws IOException {
        if (!prettyPrint) {
            return jsonResult;
        }

        // Parse and re-format with pretty printing
        JsonNode node = objectMapper.readTree(jsonResult);
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    /**
     * Copy text to system clipboard.
     */
    private void copyToClipboard(String text) {
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
    }
}

