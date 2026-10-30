package com.jsonsql.query;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.AllColumns;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One aggregate in a query, such as COUNT(*) or SUM(DISTINCT price). Its key identifies it in
 * grouped rows, so count(*) and COUNT(*) share a single result.
 *
 * @param function the upper-case function name
 * @param field    the field to aggregate, or null for COUNT(*)
 * @param distinct whether repeated values are counted once
 */
record AggregateCall(String function, String field, boolean distinct) {

    private static final Set<String> FUNCTIONS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");

    static boolean isAggregate(Expression expression) {
        return expression instanceof Function function
            && FUNCTIONS.contains(function.getName().toUpperCase(Locale.ROOT));
    }

    static AggregateCall from(Function function) throws QueryParseException {
        String name = function.getName().toUpperCase(Locale.ROOT);
        Expression argument = onlyArgument(function);
        boolean allRows = function.isAllColumns() || argument instanceof AllColumns || argument instanceof LongValue;

        if (allRows && name.equals("COUNT") && !function.isDistinct()) {
            return new AggregateCall(name, null, false);
        }
        if (argument instanceof Column) {
            return new AggregateCall(name, QueryParser.fieldName(argument), function.isDistinct());
        }
        throw new QueryParseException(
            "Aggregate argument is not supported: " + function + " (use a single field, or COUNT(*))");
    }

    private static Expression onlyArgument(Function function) {
        List<?> parameters = function.getParameters();
        return parameters != null && parameters.size() == 1 ? (Expression) parameters.get(0) : null;
    }

    /**
     * The key of an aggregate the parser has already accepted.
     */
    static String keyOf(Function function) {
        try {
            return from(function).key();
        } catch (QueryParseException e) {
            return function.toString();
        }
    }

    String key() {
        return function + "(" + (distinct ? "DISTINCT " : "") + (field == null ? "*" : field) + ")";
    }
}
