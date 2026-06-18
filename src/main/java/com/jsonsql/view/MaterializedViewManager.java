package com.jsonsql.view;

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
 * Manages materialized view definitions in {@code .jsonsql-views.json}.
 */
public class MaterializedViewManager {
    private final File configFile;
    private final ObjectMapper objectMapper;
    private final Map<String, MaterializedViewDefinition> views = new LinkedHashMap<>();

    public MaterializedViewManager(File configFile) {
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
            List<MaterializedViewDefinition> loaded = objectMapper.readValue(
                configFile, new TypeReference<List<MaterializedViewDefinition>>() {});
            for (MaterializedViewDefinition def : loaded) {
                if (def != null && def.getName() != null) {
                    views.put(def.getName(), def);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(
                "Failed to load materialized views from " + configFile +
                " (the file may be corrupted). Fix or remove it and try again.", e);
        }
    }

    private void save() {
        try {
            byte[] content = objectMapper.writeValueAsBytes(new ArrayList<>(views.values()));
            QueryManager.writeAtomically(configFile, content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save materialized views to " + configFile, e);
        }
    }

    public boolean hasView(String name) {
        return views.containsKey(name);
    }

    public MaterializedViewDefinition getView(String name) {
        return views.get(name);
    }

    public List<MaterializedViewDefinition> getAllViews() {
        return new ArrayList<>(views.values());
    }

    public void putView(MaterializedViewDefinition definition) {
        if (definition.getName() == null || definition.getName().isBlank()) {
            throw new IllegalArgumentException("View name cannot be empty");
        }
        views.put(definition.getName(), definition);
        save();
    }

    public boolean dropView(String name) {
        if (views.remove(name) != null) {
            save();
            return true;
        }
        return false;
    }

    public int size() {
        return views.size();
    }
}
