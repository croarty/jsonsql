package com.jsonsql.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                throw new IOException("Failed to create output directory: " + parent.getAbsolutePath());
            }
            // Write explicitly as UTF-8 for portability across platforms
            Files.writeString(outputFile.toPath(), output, StandardCharsets.UTF_8);
            System.err.println("Output written to: " + outputFile.getAbsolutePath());
        }

        // Output to clipboard
        if (clipboard) {
            copyToClipboard(output);
            System.err.println("Output copied to clipboard");
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
     * Throws a clear IOException when no graphical environment / clipboard is available
     * (e.g. headless servers) instead of leaking an AWT HeadlessException.
     */
    private void copyToClipboard(String text) throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IOException(
                "Clipboard is not available in a headless environment. Use --output to write to a file instead.");
        }
        try {
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        } catch (Exception e) {
            throw new IOException("Failed to copy output to clipboard: " + e.getMessage(), e);
        }
    }
}

