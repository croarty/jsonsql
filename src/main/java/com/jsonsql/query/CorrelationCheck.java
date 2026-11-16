package com.jsonsql.query;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rejects a subquery or derived table that refers to a table of a query around it. Each
 * runs once on its own, so such a name would find nothing and read null.
 *
 * A name refers to a table when its first part is the table's name or alias, as p in
 * p.category. There is no schema, so an unprefixed name always belongs to the subquery's
 * own tables, and a first part that names no table around it, as profile in profile.level,
 * is a nested field. A table of the subquery's own with the same name as one around it is
 * its own.
 */
final class CorrelationCheck {

    private enum Kind { STATEMENT, VALUE, DERIVED }

    private CorrelationCheck() {
    }

    static void check(Select statement) throws QueryParseException {
        if (statement.getWithItemsList() != null) {
            for (WithItem cte : statement.getWithItemsList()) {
                select(cte.getSelect(), List.of(), Kind.STATEMENT);
            }
        }
        select(statement, List.of(), Kind.STATEMENT);
    }

    /**
     * @param outer the names of the tables of each query around this one, outermost first
     */
    private static void select(Select select, List<Set<String>> outer, Kind kind) throws QueryParseException {
        if (select instanceof ParenthesedSelect parenthesed) {
            select(parenthesed.getSelect(), outer, kind);
        } else if (select instanceof SetOperationList setOperation) {
            for (Select branch : setOperation.getSelects()) {
                select(branch, outer, kind);
            }
        } else if (select instanceof PlainSelect plain) {
            plainSelect(plain, outer, kind);
        }
    }

    private static void plainSelect(PlainSelect plain, List<Set<String>> outer, Kind kind) throws QueryParseException {
        Set<String> own = new HashSet<>();
        List<FromItem> items = new ArrayList<>();
        items.add(plain.getFromItem());
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                items.add(join.getRightItem());
            }
        }
        for (FromItem item : items) {
            addNames(item, own);
        }

        List<Set<String>> inner = new ArrayList<>(outer);
        inner.add(own);

        List<Expression> expressions = new ArrayList<>();
        for (FromItem item : items) {
            if (item instanceof ParenthesedSelect derived) {
                select(derived.getSelect(), inner, Kind.DERIVED);
            } else if (item instanceof TableFunction function && function.getFunction() != null) {
                expressions.add(function.getFunction());
            }
        }
        for (SelectItem<?> selectItem : plain.getSelectItems()) {
            expressions.add(selectItem.getExpression());
        }
        expressions.add(plain.getWhere());
        expressions.add(plain.getHaving());
        if (plain.getGroupBy() != null && plain.getGroupBy().getGroupByExpressionList() != null) {
            for (Object grouped : plain.getGroupBy().getGroupByExpressionList()) {
                expressions.add((Expression) grouped);
            }
        }
        if (plain.getOrderByElements() != null) {
            for (OrderByElement order : plain.getOrderByElements()) {
                expressions.add(order.getExpression());
            }
        }
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                expressions.addAll(join.getOnExpressions());
            }
        }

        Names names = new Names();
        for (Expression expression : expressions) {
            if (expression != null) {
                expression.accept(names);
            }
        }
        for (String name : names.columns) {
            String table = name.contains(".") ? name.substring(0, name.indexOf('.')) : null;
            if (table == null || own.contains(table)) {
                continue;
            }
            for (Set<String> around : outer) {
                if (around.contains(table)) {
                    throw kind == Kind.DERIVED
                        ? new QueryParseException("A derived table that refers to another table of the query (" + name
                            + ") is not supported (a derived table runs on its own; put the condition in the join's ON instead)")
                        : new QueryParseException("A subquery that refers to the enclosing query (" + name
                            + ") is not supported (a subquery runs once on its own; write a CTE with GROUP BY "
                            + "and join it to the table instead)");
                }
            }
        }
        for (Select subquery : names.subqueries) {
            select(subquery, inner, Kind.VALUE);
        }
    }

    /** The names a FROM item gives its rows: its alias or table name, and an UNNEST's element. */
    private static void addNames(FromItem item, Set<String> names) {
        if (item == null) {
            return;
        }
        Alias alias = item.getAlias();
        if (alias != null && alias.getName() != null) {
            names.add(QueryParser.fieldName(new Column(alias.getName())));
            if (alias.getAliasColumns() != null) {
                alias.getAliasColumns().forEach(column -> names.add(QueryParser.fieldName(new Column(column.name))));
            }
        } else if (item instanceof Table table) {
            names.add(QueryParser.fieldName(new Column(table.getName())));
        }
    }

    /** The column names in an expression, and the subqueries in it, which are checked on their own. */
    private static final class Names extends ExpressionVisitorAdapter {
        private final List<String> columns = new ArrayList<>();
        private final List<Select> subqueries = new ArrayList<>();

        @Override
        public void visit(Column column) {
            columns.add(QueryParser.fieldName(column));
        }

        @Override
        public void visit(ParenthesedSelect select) {
            subqueries.add(select);
        }

        // A subquery inside an expression can arrive here instead
        @Override
        public void visit(Select select) {
            subqueries.add(select);
        }
    }
}
