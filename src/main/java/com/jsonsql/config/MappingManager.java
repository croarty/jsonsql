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
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, root);
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
        
        mappings.put(alias, jsonPath);
        saveMappings();
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
        // Check if mapping contains filename (format: "filename:jsonpath")
        if (mapping.contains(":") && !mapping.startsWith("$")) {
            return mapping.substring(0, mapping.indexOf(":"));
        }
        return null;
    }
    
    /**
     * Get just the JSONPath part (strips filename if present).
     */
    public String getJsonPathOnly(String alias) {
        String mapping = mappings.get(alias);
        if (mapping == null) {
            return null;
        }
        // Check if mapping contains filename (format: "filename:jsonpath")
        if (mapping.contains(":") && !mapping.startsWith("$")) {
            return mapping.substring(mapping.indexOf(":") + 1);
        }
        return mapping;
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

