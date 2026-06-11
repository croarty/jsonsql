package com.jsonsql.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jsonsql.config.QueryManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages declared index definitions stored in a JSON file (default
 * {@code .jsonsql-indexes.json}). Only the definitions live here; the per-file value
 * summaries are built and stored separately by {@link IndexBuilder}/{@link IndexStore}.
 */
public class IndexManager {
    private final File configFile;
    private final ObjectMapper objectMapper;
    // Keyed by IndexDefinition.key() to preserve insertion order and reject duplicates.
    private final Map<String, IndexDefinition> definitions = new LinkedHashMap<>();

    public IndexManager(File configFile) {
        this.configFile = configFile;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    private void load() {
        if (!configFile.exists() || configFile.length() == 0) {
            return;
        }
        try {
            List<IndexDefinition> loaded = objectMapper.readValue(
                configFile, new TypeReference<List<IndexDefinition>>() {});
            for (IndexDefinition def : loaded) {
                if (def != null && def.getTable() != null && def.getField() != null) {
                    definitions.put(def.key(), def);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(
                "Failed to load index definitions from " + configFile +
                " (the file may be corrupted). Fix or remove it and try again.", e);
        }
    }

    private void save() {
        try {
            byte[] content = objectMapper.writeValueAsBytes(new ArrayList<>(definitions.values()));
            QueryManager.writeAtomically(configFile, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save index definitions to " + configFile, e);
        }
    }

    /**
     * Add an index definition. Returns true if newly added, false if it already existed.
     */
    public boolean addIndex(String table, String field) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("Index table cannot be empty");
        }
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Index field cannot be empty");
        }
        IndexDefinition def = new IndexDefinition(table.trim(), field.trim());
        if (definitions.containsKey(def.key())) {
            return false;
        }
        definitions.put(def.key(), def);
        save();
        return true;
    }

    /**
     * Remove an index definition. Returns true if it existed and was removed.
     */
    public boolean dropIndex(String table, String field) {
        IndexDefinition def = new IndexDefinition(table, field);
        if (definitions.remove(def.key()) != null) {
            save();
            return true;
        }
        return false;
    }

    public boolean hasIndex(String table, String field) {
        return definitions.containsKey(new IndexDefinition(table, field).key());
    }

    public boolean hasIndexesForTable(String table) {
        for (IndexDefinition def : definitions.values()) {
            if (def.getTable().equals(table)) {
                return true;
            }
        }
        return false;
    }

    public List<IndexDefinition> getIndexesForTable(String table) {
        List<IndexDefinition> result = new ArrayList<>();
        for (IndexDefinition def : definitions.values()) {
            if (def.getTable().equals(table)) {
                result.add(def);
            }
        }
        return result;
    }

    public List<IndexDefinition> getAllIndexes() {
        return new ArrayList<>(definitions.values());
    }

    public int size() {
        return definitions.size();
    }
}
