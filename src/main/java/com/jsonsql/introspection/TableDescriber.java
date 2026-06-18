package com.jsonsql.introspection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.CacheManager;
import com.jsonsql.config.MappingManager;
import com.jsonsql.query.QueryExecutor;
import com.jsonsql.view.MaterializedViewManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Introspects mapped tables and reports field names, inferred JSON types, and sample values.
 */
public class TableDescriber {

    static final int MAX_SAMPLE_ROWS = 200;
    static final int MAX_SAMPLE_LENGTH = 80;

    private final MappingManager mappingManager;
    private final QueryExecutor queryExecutor;
    private final ObjectMapper objectMapper;

    public TableDescriber(MappingManager mappingManager, File dataDirectory) {
        this(mappingManager, dataDirectory, null);
    }

    public TableDescriber(MappingManager mappingManager, File dataDirectory, CacheManager cacheManager) {
        this(mappingManager, dataDirectory, cacheManager, null);
    }

    public TableDescriber(MappingManager mappingManager, File dataDirectory, CacheManager cacheManager,
                          MaterializedViewManager viewManager) {
        this.mappingManager = mappingManager;
        this.queryExecutor = new QueryExecutor(mappingManager, dataDirectory, cacheManager, null, viewManager);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Build a human-readable description of a mapped table's structure.
     */
    public String describe(String tableName) throws IOException {
        MaterializedViewManager viewManager = queryExecutor.getViewManager();
        boolean isView = viewManager != null && viewManager.hasView(tableName)
            && !mappingManager.hasMapping(tableName);
        if (!mappingManager.hasMapping(tableName) && !isView) {
            throw new IllegalArgumentException(
                "No mapping or materialized view found for table: " + tableName);
        }

        List<JsonNode> rows = queryExecutor.loadMappedTableRows(tableName);
        int sampledRows = Math.min(rows.size(), MAX_SAMPLE_ROWS);

        Map<String, FieldInfo> fields = new LinkedHashMap<>();
        for (int i = 0; i < sampledRows; i++) {
            collectFields(rows.get(i), "", fields);
        }

        String mappingLabel = isView
            ? "materialized view: " + viewManager.getView(tableName).getCteSql()
            : mappingManager.getJsonPath(tableName);
        return formatDescription(tableName, mappingLabel, rows.size(), sampledRows, fields);
    }

    private void collectFields(JsonNode node, String prefix, Map<String, FieldInfo> fields) {
        if (!node.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String fieldPath = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode value = entry.getValue();

            if (value.isObject() && !value.isNull()) {
                collectFields(value, fieldPath, fields);
            } else {
                fields.computeIfAbsent(fieldPath, ignored -> new FieldInfo()).observe(value);
            }
        }
    }

    private String formatDescription(String tableName, String mapping, int totalRows, int sampledRows,
                                     Map<String, FieldInfo> fields) {
        StringBuilder sb = new StringBuilder();

        sb.append("Table: ").append(tableName).append('\n');
        sb.append("Mapping: ").append(mapping).append('\n');
        sb.append("Rows: ").append(totalRows);
        if (sampledRows < totalRows) {
            sb.append(" (schema sampled from first ").append(sampledRows).append(')');
        }
        sb.append('\n');

        if (fields.isEmpty()) {
            sb.append('\n').append("No fields found (table is empty or contains no object rows).");
            return sb.toString();
        }

        int nameWidth = fields.keySet().stream().mapToInt(String::length).max().orElse(5);
        nameWidth = Math.max(nameWidth, "Field".length());
        int typeWidth = fields.values().stream()
            .mapToInt(f -> f.typeLabel().length())
            .max()
            .orElse(4);
        typeWidth = Math.max(typeWidth, "Type".length());

        sb.append('\n');
        sb.append(String.format("  %-" + nameWidth + "s  %-" + typeWidth + "s  %s%n",
            "Field", "Type", "Sample"));

        for (Map.Entry<String, FieldInfo> entry : fields.entrySet()) {
            FieldInfo info = entry.getValue();
            sb.append(String.format("  %-" + nameWidth + "s  %-" + typeWidth + "s  %s%n",
                entry.getKey(),
                info.typeLabel(),
                truncateSample(info.sampleText())));
        }

        return sb.toString().stripTrailing();
    }

    private String truncateSample(String sample) {
        if (sample == null) {
            return "";
        }
        if (sample.length() <= MAX_SAMPLE_LENGTH) {
            return sample;
        }
        return sample.substring(0, MAX_SAMPLE_LENGTH - 3) + "...";
    }

    static String jsonType(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "null";
        }
        if (value.isTextual()) {
            return "string";
        }
        if (value.isNumber()) {
            return "number";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isObject()) {
            return "object";
        }
        return "unknown";
    }

    String formatSampleValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "(null)";
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isValueNode()) {
            return value.asText();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            return value.toString();
        }
    }

    private final class FieldInfo {
        private final Set<String> types = new LinkedHashSet<>();
        private String sampleText;
        private boolean seenNull;

        void observe(JsonNode value) {
            String type = jsonType(value);
            types.add(type);
            if ("null".equals(type)) {
                seenNull = true;
            } else if (sampleText == null) {
                sampleText = formatSampleValue(value);
            }
        }

        String typeLabel() {
            List<String> labels = new ArrayList<>(types);
            labels.remove("null");
            if (labels.isEmpty()) {
                return "null";
            }
            String base = labels.size() == 1 ? labels.get(0) : String.join("|", labels);
            if (seenNull && !"null".equals(base)) {
                return base + " (nullable)";
            }
            return base;
        }

        String sampleText() {
            if (sampleText != null) {
                return sampleText;
            }
            return seenNull ? "(null)" : "";
        }
    }
}
