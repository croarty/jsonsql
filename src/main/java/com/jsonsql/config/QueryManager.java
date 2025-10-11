package com.jsonsql.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages saved SQL queries.
 * Provides functionality to save, load, list, and delete named queries.
 */
public class QueryManager {
    private final File configFile;
    private final ObjectMapper objectMapper;
    private Map<String, String> queries;

    public QueryManager(File configFile) {
        this.configFile = configFile;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.queries = new LinkedHashMap<>();
        loadQueries();
    }

    /**
     * Load queries from the configuration file.
     */
    private void loadQueries() {
        if (configFile.exists() && configFile.length() > 0) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> loadedQueries = objectMapper.readValue(configFile, LinkedHashMap.class);
                queries = loadedQueries;
            } catch (IOException e) {
                // Silently initialize with empty map if file is corrupted or invalid
                queries = new LinkedHashMap<>();
            }
        }
    }

    /**
     * Save queries to the configuration file.
     */
    private void saveQueries() throws IOException {
        objectMapper.writeValue(configFile, queries);
    }

    /**
     * Save a query with a name.
     */
    public void saveQuery(String name, String sql) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Query name cannot be empty");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("Query SQL cannot be empty");
        }
        
        queries.put(name, sql);
        saveQueries();
    }

    /**
     * Get a saved query by name.
     */
    public String getQuery(String name) {
        return queries.get(name);
    }

    /**
     * Check if a query exists.
     */
    public boolean hasQuery(String name) {
        return queries.containsKey(name);
    }

    /**
     * Delete a saved query.
     */
    public void deleteQuery(String name) throws IOException {
        if (!queries.containsKey(name)) {
            throw new IllegalArgumentException("Query not found: " + name);
        }
        queries.remove(name);
        saveQueries();
    }

    /**
     * Get all query names.
     */
    public Map<String, String> getAllQueries() {
        return new LinkedHashMap<>(queries);
    }

    /**
     * Get the number of saved queries.
     */
    public int getQueryCount() {
        return queries.size();
    }
}

