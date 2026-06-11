package com.jsonsql.index;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-disk index for one {@code (table, field)} pair: a per-file value summary
 * that lets the planner skip files that cannot contain a matching row.
 *
 * <p>Serialized to {@code .jsonsql-index/<hash>.idx} (JSON content; the {@code .idx}
 * extension keeps it from being mistaken for a data file by the recursive loader).
 */
public class TableIndex {
    public static final String KIND_SCALAR = "scalar";
    public static final String KIND_MULTIVALUED = "multivalued";

    private String table;
    private String field;
    /** {@link #KIND_SCALAR} (one value per row) or {@link #KIND_MULTIVALUED} (path crosses an array). */
    private String kind;
    /** The JSONPath the table mapping used at build time; used to detect mapping changes. */
    private String jsonPath;
    /** Canonical base directory at build time (informational / diagnostics). */
    private String root;
    private List<FileSummary> files = new ArrayList<>();

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    @JsonIgnore
    public boolean isMultivalued() {
        return KIND_MULTIVALUED.equals(kind);
    }

    public String getJsonPath() {
        return jsonPath;
    }

    public void setJsonPath(String jsonPath) {
        this.jsonPath = jsonPath;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public List<FileSummary> getFiles() {
        return files;
    }

    public void setFiles(List<FileSummary> files) {
        this.files = files;
    }
}
