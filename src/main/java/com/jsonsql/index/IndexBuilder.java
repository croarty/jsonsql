package com.jsonsql.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jsonsql.config.MappingManager;
import com.jsonsql.query.TableFileResolver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds (and persists) per-file value summaries for a declared index.
 *
 * <p>Extraction is array-aware: if the field path crosses an array (an array of scalars
 * at the leaf, or {@code arr.sub} where {@code arr} is an array of objects), every
 * element value is unioned and the index is marked multi-valued.
 */
public class IndexBuilder {
    /** Above this many distinct values per file, drop the value list and keep only min/max. */
    static final int CARDINALITY_LIMIT = 1000;

    private final MappingManager mappingManager;
    private final TableFileResolver resolver;
    private final IndexStore store;
    private final ObjectMapper objectMapper;
    private final Configuration jsonPathConfig;

    public IndexBuilder(MappingManager mappingManager, File dataDirectory, IndexStore store) {
        this.mappingManager = mappingManager;
        this.resolver = new TableFileResolver(mappingManager, dataDirectory);
        this.store = store;
        this.objectMapper = new ObjectMapper();
        this.jsonPathConfig = Configuration.builder()
            .options(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS)
            .build();
    }

    /**
     * Build the index for a (table, field), persist it, and return it.
     */
    public TableIndex build(String table, String field) throws IOException {
        if (!mappingManager.hasMapping(table)) {
            throw new IllegalArgumentException(
                "No mapping found for table: " + table + ". Use --add-mapping to define it.");
        }
        String jsonPath = mappingManager.getJsonPathOnly(table);
        File base = resolver.resolveBase(table);
        List<File> files = resolver.resolveFiles(table);
        String[] parts = field.split("\\.");

        boolean[] crossedArray = {false};
        List<FileSummary> summaries = new ArrayList<>();
        for (File f : files) {
            summaries.add(summarizeFile(f, base, parts, jsonPath, crossedArray));
        }

        TableIndex index = new TableIndex();
        index.setTable(table);
        index.setField(field);
        index.setJsonPath(jsonPath);
        index.setRoot(TableFileResolver.canonicalPath(base));
        index.setKind(crossedArray[0] ? TableIndex.KIND_MULTIVALUED : TableIndex.KIND_SCALAR);
        index.setFiles(summaries);

        store.save(index);
        return index;
    }

    private FileSummary summarizeFile(File file, File base, String[] parts,
                                      String jsonPath, boolean[] crossedArray) throws IOException {
        FileSummary summary = new FileSummary();
        summary.setRelPath(TableFileResolver.relativePath(base, file));
        summary.setCanonicalPath(TableFileResolver.canonicalPath(file));
        summary.setMtime(file.lastModified());
        summary.setSize(file.length());

        List<JsonNode> rows = loadRows(file, jsonPath);
        List<JsonNode> values = new ArrayList<>();
        for (JsonNode row : rows) {
            collectValues(row, parts, 0, values, crossedArray);
        }

        boolean sawNumber = false, sawString = false, sawBoolean = false, sawOther = false;
        Map<String, JsonNode> distinct = new LinkedHashMap<>();
        for (JsonNode v : values) {
            if (v.isNumber()) sawNumber = true;
            else if (v.isTextual()) sawString = true;
            else if (v.isBoolean()) sawBoolean = true;
            else sawOther = true;
            distinct.putIfAbsent(v.toString(), v);
        }

        String valueType = classify(values.isEmpty(), sawNumber, sawString, sawBoolean, sawOther);
        summary.setValueType(valueType);

        if ("number".equals(valueType) || "string".equals(valueType)) {
            JsonNode min = null, max = null;
            for (JsonNode v : distinct.values()) {
                if (min == null || compare(v, min, valueType) < 0) min = v;
                if (max == null || compare(v, max, valueType) > 0) max = v;
            }
            summary.setMin(min);
            summary.setMax(max);
        }

        if (distinct.size() > CARDINALITY_LIMIT) {
            summary.setValuesOmitted(true);
            summary.setDistinctValues(null);
        } else {
            summary.setValuesOmitted(false);
            summary.setDistinctValues(new ArrayList<>(distinct.values()));
        }
        return summary;
    }

    /**
     * Recursively collect scalar leaf values for a dotted path, descending into arrays.
     * Sets {@code crossedArray[0]} when the path passes through (or ends at) an array,
     * which marks the index as multi-valued.
     */
    private void collectValues(JsonNode node, String[] parts, int idx,
                               List<JsonNode> out, boolean[] crossedArray) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (idx == parts.length) {
            if (node.isArray()) {
                crossedArray[0] = true;
                for (JsonNode el : node) {
                    if (el != null && !el.isNull() && el.isValueNode()) {
                        out.add(el);
                    }
                }
            } else if (node.isValueNode()) {
                out.add(node);
            }
            return;
        }
        if (node.isArray()) {
            crossedArray[0] = true;
            for (JsonNode el : node) {
                collectValues(el, parts, idx, out, crossedArray);
            }
            return;
        }
        if (node.isObject()) {
            collectValues(node.get(parts[idx]), parts, idx + 1, out, crossedArray);
        }
    }

    private String classify(boolean empty, boolean num, boolean str, boolean bool, boolean other) {
        if (empty) return "empty";
        int types = (num ? 1 : 0) + (str ? 1 : 0) + (bool ? 1 : 0) + (other ? 1 : 0);
        if (types > 1) return "mixed";
        if (num) return "number";
        if (str) return "string";
        if (bool) return "boolean";
        return "other";
    }

    private int compare(JsonNode a, JsonNode b, String valueType) {
        if ("number".equals(valueType)) {
            return Double.compare(a.asDouble(), b.asDouble());
        }
        return a.asText().compareTo(b.asText());
    }

    private List<JsonNode> loadRows(File file, String jsonPath) throws IOException {
        String content = Files.readString(file.toPath());
        Object result = JsonPath.using(jsonPathConfig).parse(content).read(jsonPath);
        JsonNode resultNode = objectMapper.valueToTree(result);
        List<JsonNode> rows = new ArrayList<>();
        if (resultNode.isArray()) {
            resultNode.forEach(n -> {
                if (n.isObject()) {
                    rows.add(n);
                }
            });
        } else if (resultNode.isObject()) {
            rows.add(resultNode);
        }
        return rows;
    }
}
