package com.jsonsql.view;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Metadata for a persisted materialized view (CTE body SQL + freshness fingerprint).
 */
public class MaterializedViewDefinition {
    private String name;
    /** Inner CTE SELECT body (not the outer WITH wrapper). */
    private String cteSql;
    private String sourceFingerprint;
    /** Canonical path of --data-dir used when the view was built. */
    private String dataDirectory;
    private String builtAt;
    private int rowCount;

    public MaterializedViewDefinition() {
    }

    @JsonCreator
    public MaterializedViewDefinition(@JsonProperty("name") String name,
                                      @JsonProperty("cteSql") String cteSql,
                                      @JsonProperty("sourceFingerprint") String sourceFingerprint,
                                      @JsonProperty("dataDirectory") String dataDirectory,
                                      @JsonProperty("builtAt") String builtAt,
                                      @JsonProperty("rowCount") int rowCount) {
        this.name = name;
        this.cteSql = cteSql;
        this.sourceFingerprint = sourceFingerprint;
        this.dataDirectory = dataDirectory;
        this.builtAt = builtAt;
        this.rowCount = rowCount;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCteSql() {
        return cteSql;
    }

    public void setCteSql(String cteSql) {
        this.cteSql = cteSql;
    }

    public String getSourceFingerprint() {
        return sourceFingerprint;
    }

    public void setSourceFingerprint(String sourceFingerprint) {
        this.sourceFingerprint = sourceFingerprint;
    }

    public String getDataDirectory() {
        return dataDirectory;
    }

    public void setDataDirectory(String dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public String getBuiltAt() {
        return builtAt;
    }

    public void setBuiltAt(String builtAt) {
        this.builtAt = builtAt;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    /**
     * Compares by name only. Views are uniquely identified by name within the system;
     * two definitions with the same name represent the same materialized view even if
     * other fields (cteSql, fingerprint, etc.) differ.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MaterializedViewDefinition that = (MaterializedViewDefinition) o;
        return Objects.equals(name, that.name);
    }

    /**
     * Hash code based on name only to maintain consistency with equals().
     */
    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
