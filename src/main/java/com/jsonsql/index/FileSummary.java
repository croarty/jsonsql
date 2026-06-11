package com.jsonsql.index;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Per-file value summary used for file-level pruning.
 *
 * <p>A file may be pruned for a query only when this summary is present, its
 * fingerprint ({@link #mtime}/{@link #size}) still matches the file on disk, and the
 * summary proves no row in the file can match the predicate.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FileSummary {
    /** Path relative to the table's base directory, using '/' separators (portable). */
    private String relPath;
    /** Canonical absolute path at build time (used for fast same-run matching). */
    private String canonicalPath;
    /** Source-file last-modified timestamp at build time. */
    private long mtime;
    /** Source-file size in bytes at build time. */
    private long size;
    /** One of: number, string, boolean, mixed, empty. Governs whether range pruning is valid. */
    private String valueType;
    /** True when distinctValues was dropped (high cardinality); min/max still apply. */
    private boolean valuesOmitted;
    /** Distinct values seen for the indexed field in this file (null when omitted). */
    private List<JsonNode> distinctValues;
    /** Minimum value (only meaningful for homogeneous number/string types). */
    private JsonNode min;
    /** Maximum value (only meaningful for homogeneous number/string types). */
    private JsonNode max;

    public String getRelPath() {
        return relPath;
    }

    public void setRelPath(String relPath) {
        this.relPath = relPath;
    }

    public String getCanonicalPath() {
        return canonicalPath;
    }

    public void setCanonicalPath(String canonicalPath) {
        this.canonicalPath = canonicalPath;
    }

    public long getMtime() {
        return mtime;
    }

    public void setMtime(long mtime) {
        this.mtime = mtime;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public boolean isValuesOmitted() {
        return valuesOmitted;
    }

    public void setValuesOmitted(boolean valuesOmitted) {
        this.valuesOmitted = valuesOmitted;
    }

    public List<JsonNode> getDistinctValues() {
        return distinctValues;
    }

    public void setDistinctValues(List<JsonNode> distinctValues) {
        this.distinctValues = distinctValues;
    }

    public JsonNode getMin() {
        return min;
    }

    public void setMin(JsonNode min) {
        this.min = min;
    }

    public JsonNode getMax() {
        return max;
    }

    public void setMax(JsonNode max) {
        this.max = max;
    }
}
