package com.jsonsql.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
                // Typed deserialization so non-string values are rejected cleanly at load time
                Map<String, String> loadedQueries = objectMapper.readValue(
                    configFile, new TypeReference<LinkedHashMap<String, String>>() {});
                queries = loadedQueries;
            } catch (IOException e) {
                // Surface the corruption instead of silently resetting (which would cause the
                // next save to overwrite and permanently lose the user's saved queries).
                throw new RuntimeException(
                    "Failed to load saved queries from " + configFile +
                    " (the file may be corrupted). Fix or remove it and try again.", e);
            }
        }
    }

    /**
     * Save queries to the configuration file using an atomic write (temp file + move)
     * so a crash mid-write cannot leave a truncated/corrupt file.
     */
    private void saveQueries() throws IOException {
        writeAtomically(configFile, objectMapper.writeValueAsBytes(queries));
    }

    /**
     * Write bytes to a target file atomically where supported: write to a temp file in the
     * same directory, then move it into place (falling back to a plain replace move).
     */
    static void writeAtomically(File target, byte[] content) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("Failed to create directory: " + parent);
        }
        File tmp = File.createTempFile(target.getName(), ".tmp", parent);
        try {
            Files.write(tmp.toPath(), content);
            try {
                Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                // Some filesystems don't support atomic moves; fall back to a non-atomic replace
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (tmp.exists()) {
                tmp.delete();
            }
        }
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

