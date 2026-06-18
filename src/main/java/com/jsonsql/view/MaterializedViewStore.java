package com.jsonsql.view;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Persists materialized view row data under {@code .jsonsql-views/<name>.json}.
 */
public class MaterializedViewStore {
    private final File viewsDirectory;
    private final ObjectMapper objectMapper;

    public MaterializedViewStore(File dataDirectory) {
        this.viewsDirectory = new File(dataDirectory, ".jsonsql-views");
        this.objectMapper = new ObjectMapper();
        if (!viewsDirectory.exists() && !viewsDirectory.mkdirs() && !viewsDirectory.exists()) {
            throw new IllegalStateException(
                "Failed to create views directory: " + viewsDirectory.getAbsolutePath());
        }
    }

    public File getViewsDirectory() {
        return viewsDirectory;
    }

    public File storeFile(String name) {
        return new File(viewsDirectory, sanitize(name) + ".json");
    }

    public List<JsonNode> load(String name) throws IOException {
        File file = storeFile(name);
        if (!file.exists()) {
            return null;
        }
        return objectMapper.readValue(file, new TypeReference<List<JsonNode>>() {});
    }

    public void save(String name, List<JsonNode> rows) throws IOException {
        File file = storeFile(name);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, rows);
    }

    public boolean delete(String name) {
        File file = storeFile(name);
        return file.exists() && file.delete();
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
