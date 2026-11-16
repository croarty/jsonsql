package com.jsonsql.query;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A JOIN's ON condition, split into join keys, which are looked up through an index, and
 * the rest, which is checked on each pair of rows the keys let through.
 *
 * A key is a {@code field = field} part of the condition, on its own or joined to the rest
 * by AND. Its side that names the joined table is read from the joined table, and the other
 * side from the rows so far. With neither side naming it, a side that names an earlier table
 * is read from the rows so far, so {@code id = o.pid} reads {@code id} from the joined table;
 * otherwise the left side is read from the rows so far. Apart from {@code id = o.pid}, which
 * matched nothing in 3.0.0, that is how 3.0.0 read a join. Two
 * fields of the joined table, or two of earlier tables, aren't a key: they are checked on
 * the combined row, like the rest.
 */
final class JoinCondition {

    /** Join on {@code leftField} of the rows so far equal to {@code rightField} of the joined table. */
    record Key(String leftField, String rightField) {}

    private final List<Key> keys;
    private final Expression residual;

    private JoinCondition(List<Key> keys, Expression residual) {
        this.keys = List.copyOf(keys);
        this.residual = residual;
    }

    /**
     * @param on            the whole ON condition, or null for a cross join
     * @param joined        the table being joined
     * @param earlierTables the names of the FROM table, earlier joins and UNNEST aliases
     */
    static JoinCondition of(Expression on, TableInfo joined, Set<String> earlierTables) {
        if (on == null) {
            // A cross join: no keys and nothing to check
            return new JoinCondition(List.of(), null);
        }
        Expression whole = on instanceof Parenthesis parenthesis ? parenthesis.getExpression() : on;
        List<Expression> parts = new ArrayList<>();
        addParts(whole, parts);

        String joinedName = joined.getEffectiveName();
        List<Key> keys = new ArrayList<>();
        Expression residual = null;
        for (Expression part : parts) {
            Key key = key(part, joinedName, earlierTables);
            if (key != null) {
                keys.add(key);
            } else {
                residual = residual == null ? part : new AndExpression(residual, part);
            }
        }
        return new JoinCondition(keys, residual);
    }

    private static void addParts(Expression expression, List<Expression> parts) {
        if (expression instanceof AndExpression and) {
            addParts(and.getLeftExpression(), parts);
            addParts(and.getRightExpression(), parts);
        } else {
            parts.add(expression);
        }
    }

    /** The key a part stands for, oriented with the joined table on the right, or null if it isn't one. */
    private static Key key(Expression part, String joinedName, Set<String> earlierTables) {
        if (!(part instanceof EqualsTo equals)
                || !(equals.getLeftExpression() instanceof Column left) || !isField(left)
                || !(equals.getRightExpression() instanceof Column right) || !isField(right)) {
            return null;
        }
        String leftField = QueryParser.fieldName(left);
        String rightField = QueryParser.fieldName(right);
        boolean leftJoined = qualifiedBy(leftField, joinedName);
        boolean rightJoined = qualifiedBy(rightField, joinedName);
        // A joined table named like an earlier one is ambiguous; 3.0.0 joined on it, so keep that
        boolean ambiguous = earlierTables.contains(joinedName);
        if (leftJoined && rightJoined && !ambiguous) {
            return null;
        }
        if (qualifiedByAny(leftField, earlierTables, joinedName) && qualifiedByAny(rightField, earlierTables, joinedName)) {
            return null;
        }
        if (leftJoined && !rightJoined) {
            return new Key(rightField, leftField);
        }
        // id = o.pid: the earlier table's side is read from the rows so far, the other from the joined table
        if (!leftJoined && !rightJoined && !qualifiedByAny(leftField, earlierTables, joinedName)
                && qualifiedByAny(rightField, earlierTables, joinedName)) {
            return new Key(rightField, leftField);
        }
        return new Key(leftField, rightField);
    }

    /**
     * A column that names a field. Unqualified true and false are values, and a double-quoted
     * name is text when there is no such field, which only the row can tell.
     */
    private static boolean isField(Column column) {
        String name = QueryParser.fieldName(column);
        boolean bool = column.getTable() == null && (name.equalsIgnoreCase("true") || name.equalsIgnoreCase("false"));
        return !bool && !column.toString().contains("\"");
    }

    private static boolean qualifiedBy(String field, String table) {
        return field.startsWith(table + ".");
    }

    private static boolean qualifiedByAny(String field, Set<String> tables, String joinedName) {
        for (String table : tables) {
            if (!table.equals(joinedName) && qualifiedBy(field, table)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code field = field} parts, in the order written. May be empty. */
    List<Key> keys() {
        return keys;
    }

    /** The other parts joined by AND, or null if there are none. */
    Expression residual() {
        return residual;
    }
}
