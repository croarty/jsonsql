package com.jsonsql.index;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * A declared index on a single {@code (table, field)} pair.
 *
 * <p>The {@code field} may be a dotted path (e.g. {@code specifications.material} or
 * {@code reviews.rating}); whether it is treated as scalar or multi-valued is decided
 * at build time based on the data shape, not stored here.
 */
public class IndexDefinition {
    private String table;
    private String field;

    public IndexDefinition() {
    }

    @JsonCreator
    public IndexDefinition(@JsonProperty("table") String table,
                           @JsonProperty("field") String field) {
        this.table = table;
        this.field = field;
    }

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

    /** Stable identity key for a definition, safe even when the field contains dots. */
    public String key() {
        return table + "\u0000" + field;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IndexDefinition that = (IndexDefinition) o;
        return Objects.equals(table, that.table) && Objects.equals(field, that.field);
    }

    @Override
    public int hashCode() {
        return Objects.hash(table, field);
    }

    @Override
    public String toString() {
        return table + "." + field;
    }
}
