package com.jsonsql.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages JSONPath mappings (aliases) stored in a configuration file.
 */
public class MappingManager {
    private final File configFile;
    private final ObjectMapper objectMapper;
    private Map<String, String> mappings;

    public MappingManager(File configFile) {
        this.configFile = configFile;
        this.objectMapper = new ObjectMapper();
        this.mappings = new HashMap<>();
        loadMappings();
    }

    /**
     * Load mappings from the configuration file.
     */
    private void loadMappings() {
        if (!configFile.exists()) {
            // Create empty mappings file if it doesn't exist
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(configFile);
            root.fields().forEachRemaining(entry -> 
                mappings.put(entry.getKey(), entry.getValue().asText())
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to load mappings from " + configFile, e);
        }
    }

    /**
     * Save mappings to the configuration file.
     */
    private void saveMappings() {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            mappings.forEach(root::put);
            // Atomic write (temp file + move) so a crash mid-write cannot corrupt the config
            byte[] content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
            QueryManager.writeAtomically(configFile, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save mappings to " + configFile, e);
        }
    }

    /**
     * Add or update a mapping.
     */
    public void addMapping(String alias, String jsonPath) {
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("Alias cannot be empty");
        }
        if (jsonPath == null || jsonPath.isBlank()) {
            throw new IllegalArgumentException("JSONPath cannot be empty");
        }

        // Validate the resulting shape: the JSONPath portion (after any "filename:" prefix)
        // must start with '$'. This catches malformed mappings like "products" or
        // "file.json:items" at add time rather than at query time.
        String pathPart = jsonPathPartOf(jsonPath);
        if (pathPart == null || !pathPart.startsWith("$")) {
            throw new IllegalArgumentException(
                "Invalid mapping '" + jsonPath + "'. Expected a JSONPath starting with '$', " +
                "optionally prefixed by a file or directory path as \"path:$.json.path\".");
        }
        
        mappings.put(alias, jsonPath);
        saveMappings();
    }

    /**
     * Index of the "filename:JSONPath" delimiter (the ':' immediately preceding the '$'
     * that begins the JSONPath). Returns -1 when the mapping is a bare JSONPath (starts
     * with '$') or has no such delimiter. Using ":$" as the delimiter keeps Windows
     * drive-letter paths (e.g. "C:\\data\\orders.json:$.orders") intact.
     */
    private int filenameDelimiterIndex(String mapping) {
        if (mapping == null || mapping.startsWith("$")) {
            return -1;
        }
        int idx = mapping.indexOf(":$");
        return idx > 0 ? idx : -1;
    }

    /**
     * Extract the JSONPath portion of a raw mapping value.
     */
    private String jsonPathPartOf(String mapping) {
        if (mapping == null) {
            return null;
        }
        int idx = filenameDelimiterIndex(mapping);
        return idx > 0 ? mapping.substring(idx + 1) : mapping;
    }

    /**
     * Get the JSONPath for a given alias.
     */
    public String getJsonPath(String alias) {
        return mappings.get(alias);
    }
    
    /**
     * Get the filename from mapping (if specified).
     * Returns null if mapping uses old format (just JSONPath).
     */
    public String getFileName(String alias) {
        String mapping = mappings.get(alias);
        if (mapping == null) {
            return null;
        }
        // Format: "filename:$.jsonpath" - filename precedes the ":$" delimiter
        int idx = filenameDelimiterIndex(mapping);
        return idx > 0 ? mapping.substring(0, idx) : null;
    }
    
    /**
     * Get just the JSONPath part (strips filename if present).
     */
    public String getJsonPathOnly(String alias) {
        String mapping = mappings.get(alias);
        if (mapping == null) {
            return null;
        }
        // Format: "filename:$.jsonpath" - JSONPath begins at the '$' after the ":$" delimiter
        int idx = filenameDelimiterIndex(mapping);
        return idx > 0 ? mapping.substring(idx + 1) : mapping;
    }

    /**
     * Check if an alias exists.
     */
    public boolean hasMapping(String alias) {
        return mappings.containsKey(alias);
    }

    /**
     * Get all mappings.
     */
    public Map<String, String> getAllMappings() {
        return new HashMap<>(mappings);
    }

    /**
     * List all mappings to stdout.
     */
    public void listMappings() {
        if (mappings.isEmpty()) {
            System.out.println("No mappings configured.");
            System.out.println("Add a mapping using: jsonsql --add-mapping <alias> <jsonpath>");
            return;
        }

        System.out.println("Configured JSONPath Mappings:");
        System.out.println("─".repeat(80));
        
        int maxAliasLength = mappings.keySet().stream()
            .mapToInt(String::length)
            .max()
            .orElse(10);
        
        String format = "  %-" + maxAliasLength + "s  ->  %s%n";
        
        mappings.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> 
                System.out.printf(format, entry.getKey(), entry.getValue())
            );
        
        System.out.println("─".repeat(80));
        System.out.println("Total: " + mappings.size() + " mapping(s)");
    }
}

