package com.jsonsql.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts a JSON array of row objects into CSV with a header row.
 */
public class CsvFormatter {

    private final ObjectMapper objectMapper;

    public CsvFormatter() {
        this.objectMapper = new ObjectMapper();
    }

    CsvFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Format a JSON array result as CSV. The first line is a header of field names.
     * Empty results produce an empty string (no header).
     */
    public String format(String jsonResult) throws IOException {
        JsonNode root = objectMapper.readTree(jsonResult);
        if (!root.isArray()) {
            throw new IOException("CSV output requires a JSON array of row objects");
        }
        if (root.isEmpty()) {
            return "";
        }

        List<String> headers = collectHeaders(root);
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers);
        for (JsonNode row : root) {
            if (!row.isObject()) {
                throw new IOException("CSV output requires each row to be a JSON object");
            }
            appendRow(sb, headers.stream()
                .map(header -> cellValue(row.get(header)))
                .toList());
        }
        return sb.toString();
    }

    private List<String> collectHeaders(JsonNode rows) {
        Set<String> headers = new LinkedHashSet<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) {
                continue;
            }
            Iterator<String> names = row.fieldNames();
            while (names.hasNext()) {
                headers.add(names.next());
            }
        }
        return new ArrayList<>(headers);
    }

    private void appendRow(StringBuilder sb, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeField(values.get(i)));
        }
        sb.append('\n');
    }

    private String cellValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
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

    static String escapeField(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
            || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
