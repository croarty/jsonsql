package com.jsonsql.query;

/**
 * Represents an UNNEST operation in a SQL query.
 * UNNEST flattens arrays into individual rows.
 */
public class UnnestInfo {
    private String arrayExpression;  // The array field to unnest (e.g., "tags", "characteristics")
    private String alias;           // The alias for the unnest result (e.g., "t", "c")
    private String elementColumn;   // The column name for array elements (e.g., "tag", "characteristic")

    public UnnestInfo() {}

    public UnnestInfo(String arrayExpression, String alias, String elementColumn) {
        this.arrayExpression = arrayExpression;
        this.alias = alias;
        this.elementColumn = elementColumn;
    }

    public String getArrayExpression() {
        return arrayExpression;
    }

    public void setArrayExpression(String arrayExpression) {
        this.arrayExpression = arrayExpression;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getElementColumn() {
        return elementColumn;
    }

    public void setElementColumn(String elementColumn) {
        this.elementColumn = elementColumn;
    }

    @Override
    public String toString() {
        return "UNNEST(" + arrayExpression + ") AS " + alias + "(" + elementColumn + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        UnnestInfo that = (UnnestInfo) obj;
        return java.util.Objects.equals(arrayExpression, that.arrayExpression) &&
               java.util.Objects.equals(alias, that.alias) &&
               java.util.Objects.equals(elementColumn, that.elementColumn);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(arrayExpression, alias, elementColumn);
    }
}
