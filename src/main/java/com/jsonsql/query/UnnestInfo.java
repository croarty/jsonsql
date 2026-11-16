package com.jsonsql.query;

/**
 * Represents an UNNEST operation in a SQL query.
 * UNNEST flattens arrays into individual rows.
 */
public class UnnestInfo {
    private String arrayExpression;  // The array field to unnest (e.g., "tags", "characteristics")
    private String alias;           // The alias for the unnest result (e.g., "t", "c")
    private String elementColumn;   // The column name for array elements (e.g., "tag", "characteristic")
    private boolean outer;          // LEFT JOIN UNNEST: keep a row whose list has no elements

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

    /**
     * Whether this is LEFT JOIN UNNEST, which keeps a row whose list is empty, missing, null
     * or not a list, once, with the element null. The other forms leave such a row out.
     */
    public boolean isOuter() {
        return outer;
    }

    public void setOuter(boolean outer) {
        this.outer = outer;
    }

    @Override
    public String toString() {
        return (outer ? "LEFT JOIN " : "") + "UNNEST(" + arrayExpression + ") AS " + alias + "(" + elementColumn + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        UnnestInfo that = (UnnestInfo) obj;
        return java.util.Objects.equals(arrayExpression, that.arrayExpression) &&
               java.util.Objects.equals(alias, that.alias) &&
               java.util.Objects.equals(elementColumn, that.elementColumn) &&
               outer == that.outer;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(arrayExpression, alias, elementColumn, outer);
    }
}
