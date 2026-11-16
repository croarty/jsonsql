package com.jsonsql.query;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.arithmetic.Concat;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses and validates SQL queries.
 */
public class QueryParser {

    /** The queries being built, innermost first. A subquery belongs to the innermost one. */
    private final java.util.Deque<ParsedQuery> building = new java.util.ArrayDeque<>();

    /**
     * Parse a SQL query string into a ParsedQuery object.
     */
    public ParsedQuery parse(String sql) throws QueryParseException {
        building.clear();
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            
            if (!(statement instanceof Select)) {
                throw new QueryParseException("Only SELECT statements are supported");
            }

            Select select = (Select) statement;
            
            // Create ParsedQuery early to store CTEs
            ParsedQuery query = new ParsedQuery();
            
            // Parse WITH clause (CTEs) from Select level before parsing main query
            parseWithClauseFromSelect(select, query);
            
            // Check if this is a UNION query (SetOperationList)
            if (select instanceof net.sf.jsqlparser.statement.select.SetOperationList) {
                net.sf.jsqlparser.statement.select.SetOperationList setOp = 
                    (net.sf.jsqlparser.statement.select.SetOperationList) select;
                UnionQuery unionQuery = parseUnionQuery(setOp, query);
                
                // Validate column consistency across all sub-queries
                unionQuery.validateColumnConsistency();
                
                query.setUnionQuery(unionQuery);
            } else {
                PlainSelect plainSelect = getPlainSelect(select);
                
                if (plainSelect == null) {
                    // This shouldn't happen since we already checked for Union
                    throw new QueryParseException("Failed to extract SELECT statement");
                }
                
                // Build the main query (CTEs already stored in query)
                buildParsedQuery(plainSelect, query);
            }

            CorrelationCheck.check(select);
            return query;
        } catch (JSQLParserException e) {
            QueryParseException modifier = setOperatorModifier(sql);
            if (modifier != null) {
                throw modifier;
            }
            throw new QueryParseException("Invalid SQL syntax: " + syntaxErrorSummary(e), e);
        }
    }

    /**
     * The parser's message without its class name or its long list of expected tokens.
     */
    private static String syntaxErrorSummary(JSQLParserException e) {
        return e.getMessage()
            .replaceFirst("^[\\w.]+Exception:\\s*", "")
            .replaceFirst("(?s)\\s*Was expecting.*$", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private PlainSelect getPlainSelect(Select select) throws QueryParseException {
        // Check for UNION first - if it's a SetOperationList (UNION), we handle it separately
        if (select instanceof net.sf.jsqlparser.statement.select.SetOperationList) {
            return null;  // Signal that this is a UNION query
        }
        
        if (!(select instanceof PlainSelect)) {
            throw new QueryParseException("Only simple SELECT queries are supported");
        }
        
        return (PlainSelect) select;
    }

    /**
     * The operator a set operation stands for. MINUS is Oracle's name for EXCEPT.
     */
    private static UnionQuery.Operator setOperator(net.sf.jsqlparser.statement.select.SetOperation op)
            throws QueryParseException {
        if (op instanceof net.sf.jsqlparser.statement.select.UnionOp union) {
            return union.isAll() ? UnionQuery.Operator.UNION_ALL : UnionQuery.Operator.UNION;
        }
        if (op instanceof net.sf.jsqlparser.statement.select.IntersectOp) {
            return UnionQuery.Operator.INTERSECT;
        }
        if (op instanceof net.sf.jsqlparser.statement.select.ExceptOp) {
            return UnionQuery.Operator.EXCEPT;
        }
        if (op instanceof net.sf.jsqlparser.statement.select.MinusOp) {
            throw unsupported("MINUS", "write EXCEPT");
        }
        throw unsupported(op.toString().trim(), "use UNION, UNION ALL, INTERSECT or EXCEPT");
    }

    /**
     * JSqlParser 4.9 has no INTERSECT ALL, EXCEPT ALL or their DISTINCT forms, and gives a bare
     * syntax error for them. Name the one written, and say what to write instead.
     */
    private static final java.util.regex.Pattern SET_OPERATOR_MODIFIER =
        java.util.regex.Pattern.compile("(?i)\\b(INTERSECT|EXCEPT)\\s+(ALL|DISTINCT)\\b");

    private static QueryParseException setOperatorModifier(String sql) {
        // Text in quotes, such as 'except all', isn't an operator
        java.util.regex.Matcher matcher = SET_OPERATOR_MODIFIER.matcher(sql.replaceAll("'(?:[^']|'')*'", "''"));
        if (!matcher.find()) {
            return null;
        }
        String operator = matcher.group(1).toUpperCase(Locale.ROOT);
        return unsupported(operator + " " + matcher.group(2).toUpperCase(Locale.ROOT),
            "write " + operator + ", which removes duplicates");
    }

    /**
     * Parse a set operation (UNION, UNION ALL, INTERSECT, EXCEPT) and build the UnionQuery object.
     */
    private UnionQuery parseUnionQuery(net.sf.jsqlparser.statement.select.SetOperationList setOp, ParsedQuery parentQuery) 
            throws QueryParseException {
        UnionQuery unionQuery = new UnionQuery();
        
        UnionQuery.Operator firstNotIntersect = null;
        for (net.sf.jsqlparser.statement.select.SetOperation op : setOp.getOperations()) {
            UnionQuery.Operator operator = setOperator(op);
            if (operator == UnionQuery.Operator.INTERSECT && firstNotIntersect != null) {
                // PostgreSQL, MySQL and SQL Server run INTERSECT first; SQLite and Oracle left to right
                throw unsupported("INTERSECT after " + firstNotIntersect.sql(), "databases disagree on which runs first; "
                    + "write the INTERSECT first, as in SELECT ... INTERSECT SELECT ... UNION SELECT ...");
            }
            if (operator != UnionQuery.Operator.INTERSECT && firstNotIntersect == null) {
                firstNotIntersect = operator;
            }
            unionQuery.addOperation(operator);
        }
        
        // Get the list of SELECT statements
        List<net.sf.jsqlparser.statement.select.Select> selectList = setOp.getSelects();
        if (selectList == null || selectList.isEmpty()) {
            throw new QueryParseException("UNION must have at least two queries");
        }

        rejectFetch(setOp.getFetch() != null);
        unionQuery.setOffset(parseOffset(setOp.getOffset(), setOp.getLimit()));
        if (setOp.getLimit() != null && setOp.getLimit().getRowCount() != null) {
            unionQuery.setLimit(parseRowCount(setOp.getLimit().getRowCount().toString(), "LIMIT"));
        }
        
        // Parse each SELECT in the UNION
        for (int i = 0; i < selectList.size(); i++) {
            net.sf.jsqlparser.statement.select.Select select = selectList.get(i);
            PlainSelect plainSelect = extractPlainSelect(select);
            
            if (plainSelect == null) {
                if (select instanceof ParenthesedSelect parenthesed
                        && parenthesed.getSelect() instanceof net.sf.jsqlparser.statement.select.SetOperationList) {
                    throw new QueryParseException("Parentheses around UNION, INTERSECT or EXCEPT are not supported "
                        + "(they run left to right; to group them, use a derived table, as in "
                        + "SELECT * FROM (SELECT ... UNION SELECT ...) t INTERSECT SELECT ...)");
                }
                throw new QueryParseException("Query " + (i + 1) + " in UNION must be a SELECT query");
            }
            
            // Transfer CTEs from parent to this sub-query
            ParsedQuery subQuery = buildParsedQuery(plainSelect, new ParsedQuery());
            if (parentQuery != null && parentQuery.hasCTEs()) {
                for (java.util.Map.Entry<String, ParsedQuery> cte : 
                        parentQuery.getCommonTableExpressions().entrySet()) {
                    subQuery.addCTE(cte.getKey(), cte.getValue());
                }
            }

            // JSqlParser attaches a trailing "... UNION SELECT ... LIMIT n" (without ORDER BY) to
            // the last unparenthesized branch; in SQL it limits the whole union.
            boolean lastBranch = i == selectList.size() - 1;
            if (lastBranch && select instanceof PlainSelect && subQuery.getLimit() != null
                    && unionQuery.getLimit() == null) {
                unionQuery.setLimit(subQuery.getLimit());
                subQuery.setLimit(null);
            }
            if (lastBranch && select instanceof PlainSelect && subQuery.getOffset() != null
                    && unionQuery.getOffset() == null) {
                unionQuery.setOffset(subQuery.getOffset());
                subQuery.setOffset(null);
            }
            
            unionQuery.addSubQuery(subQuery);
        }
        
        // Parse ORDER BY from the SetOperationList (TOP and parenthesized LIMIT stay per-branch)
        if (setOp.getOrderByElements() != null && !setOp.getOrderByElements().isEmpty()) {
            List<net.sf.jsqlparser.statement.select.OrderByElement> orderByElements = 
                setOp.getOrderByElements();
            List<OrderByInfo> orderByInfos = new ArrayList<>();
            
            for (net.sf.jsqlparser.statement.select.OrderByElement element : orderByElements) {
                String column = resolveUnionOrderColumn(element.getExpression(), unionQuery);
                boolean ascending = element.isAsc() || !element.isAscDescPresent();
                orderByInfos.add(new OrderByInfo(column, ascending));
            }
            unionQuery.setOrderBy(orderByInfos);
        }
        
        return unionQuery;
    }

    /**
     * A UNION sorts its combined output, so ORDER BY refers to output names. A position
     * uses the first branch's names.
     */
    private String resolveUnionOrderColumn(Expression expression, UnionQuery unionQuery) throws QueryParseException {
        if (expression instanceof LongValue) {
            return selectedColumn((LongValue) expression, unionQuery.getSubQueries().get(0).getSelectColumns())
                .getOutputName();
        }
        if (!(expression instanceof Column)) {
            throw unsupported("Expression in ORDER BY: " + expression, "order by a column or a position");
        }
        return fieldName(expression);
    }

    private ParsedQuery buildParsedQuery(PlainSelect plainSelect) throws QueryParseException {
        return buildParsedQuery(plainSelect, new ParsedQuery());
    }
    
    private ParsedQuery buildParsedQuery(PlainSelect plainSelect, ParsedQuery query) throws QueryParseException {
        // A subquery written while this query is built belongs to it
        building.push(query);
        try {
            return buildPlainSelect(plainSelect, query);
        } finally {
            building.pop();
        }
    }

    private ParsedQuery buildPlainSelect(PlainSelect plainSelect, ParsedQuery query) throws QueryParseException {
        // CTEs are already parsed and stored in query, so we don't need to parse them again

        rejectUnsupportedClauses(plainSelect);

        // Parse DISTINCT
        if (plainSelect.getDistinct() != null) {
            query.setDistinct(true);
        }

        // Parse SELECT clause
        parseSelectItems(plainSelect, query);

        // Parse FROM clause
        parseFromClause(plainSelect, query);

        // Parse JOINs
        parseJoins(plainSelect, query);

        // Parse WHERE clause
        if (plainSelect.getWhere() != null) {
            validateValueCondition(plainSelect.getWhere(), plainSelect.getWhere(), WHERE, null);
            query.setWhereExpression(plainSelect.getWhere());
        }

        // Parse GROUP BY and HAVING
        parseGroupBy(plainSelect, query);
        validateCalculatedColumns(query);
        parseHaving(plainSelect, query);
        validateGroupedColumns(query);

        // Parse ORDER BY clause
        parseOrderBy(plainSelect, query);

        // Parse TOP/LIMIT/OFFSET
        query.setOffset(parseOffset(plainSelect.getOffset(), plainSelect.getLimit()));
        if (plainSelect.getLimit() != null && plainSelect.getLimit().getRowCount() != null) {
            query.setLimit(parseRowCount(plainSelect.getLimit().getRowCount().toString(), "LIMIT"));
        }
        if (plainSelect.getTop() != null && plainSelect.getTop().getExpression() != null) {
            query.setTop(parseRowCount(plainSelect.getTop().getExpression().toString(), "TOP"));
        }

        return query;
    }

    /**
     * Reject clauses that JSqlParser accepts but the engine does not implement, so they fail
     * loudly instead of being silently ignored.
     */
    private void rejectUnsupportedClauses(PlainSelect plainSelect) throws QueryParseException {
        rejectFetch(plainSelect.getFetch() != null);
    }

    private void rejectFetch(boolean hasFetch) throws QueryParseException {
        if (hasFetch) {
            throw unsupported("FETCH", "use LIMIT and OFFSET");
        }
    }

    /**
     * The row offset from "OFFSET n" or "LIMIT n, m", or null if there is none.
     */
    private Long parseOffset(Offset offset, Limit limit) throws QueryParseException {
        if (offset != null) {
            return parseRowCount(offset.getOffset().toString(), "OFFSET");
        }
        if (limit != null && limit.getOffset() != null) {
            return parseRowCount(limit.getOffset().toString(), "OFFSET");
        }
        return null;
    }

    private static QueryParseException unsupported(String feature) {
        return new QueryParseException(feature + " is not supported");
    }

    private static QueryParseException unsupported(String feature, String hint) {
        return new QueryParseException(feature + " is not supported (" + hint + ")");
    }

    /**
     * Whether a SELECT item is a field or {@code *}, as opposed to something to calculate.
     */
    private boolean isPlainSelectItem(SelectItem<?> item) throws QueryParseException {
        Expression expression = item.getExpression();
        if (expression instanceof AllTableColumns) {
            throw unsupported("SELECT " + expression, "use SELECT * or list the columns");
        }
        return expression instanceof AllColumns || expression instanceof Column;
    }

    private static final String SELECT_LIST = "SELECT list";
    private static final String WHERE = "WHERE";
    private static final String ORDER_BY = "ORDER BY";
    private static final String GROUP_BY = "GROUP BY";
    private static final String HAVING = "HAVING";
    private static final String JOIN_ON = "JOIN ON";

    /**
     * A calculation may only be built from parts ExpressionEvaluator works out. Anything
     * else is rejected here, so it can't silently produce null.
     *
     * A calculation on source rows reads their fields, and can't hold an aggregate. One on
     * groups, such as {@code SUM(price) * 2} in a grouped query, can hold aggregates, which
     * are added to the query, and otherwise reads only what a group has: its GROUP BY keys
     * and, outside the SELECT list, SELECT aliases.
     *
     * @param whole   the SELECT item, condition or sort key this is part of, for the error message
     * @param clause  where it was written, such as SELECT list or WHERE
     * @param grouped the query, when the calculation is on groups; null when it is on source rows
     */
    private void validateValue(Expression expression, Expression whole, String clause, ParsedQuery grouped)
            throws QueryParseException {
        if (grouped != null && grouped.getGroupCalculations().containsKey(expression.toString())) {
            // A GROUP BY key written out again, such as qty % 2, which the group already holds
            return;
        }
        if (grouped != null && AggregateCall.isAggregate(expression)) {
            grouped.addAggregate(aggregateCall((Function) expression, whole, clause));
        } else if (grouped != null && expression instanceof Column column) {
            validateGroupedColumn(column, clause, grouped);
        } else if (expression instanceof Parenthesis parenthesis) {
            validateValue(parenthesis.getExpression(), whole, clause, grouped);
        } else if (expression instanceof SignedExpression signed) {
            validateValue(signed.getExpression(), whole, clause, grouped);
        } else if (expression instanceof Concat || ExpressionEvaluator.isArithmetic(expression)) {
            validateValue(((BinaryExpression) expression).getLeftExpression(), whole, clause, grouped);
            validateValue(((BinaryExpression) expression).getRightExpression(), whole, clause, grouped);
        } else if (expression instanceof CaseExpression caseExpression) {
            validateCase(caseExpression, whole, clause, grouped);
        } else if (expression instanceof CastExpression cast) {
            validateValue(cast.getLeftExpression(), whole, clause, grouped);
            String type = ExpressionEvaluator.castType(cast);
            if (!ExpressionEvaluator.INTEGER_TYPES.contains(type) && !ExpressionEvaluator.DECIMAL_TYPES.contains(type)
                    && !ExpressionEvaluator.TEXT_TYPES.contains(type)) {
                throw unsupported("CAST to " + type + " in " + clause + ": " + whole, "use INTEGER, REAL or TEXT");
            }
        } else if (WhereEvaluator.isCondition(expression)) {
            validateValueCondition(expression, whole, clause, grouped);
        } else if (expression instanceof Function function && !AggregateCall.isAggregate(function)
                && Functions.isFunction(function.getName())) {
            validateFunction(function, whole, clause, grouped);
        } else if (expression instanceof TrimFunction trim) {
            validateValue(trim.getExpression(), whole, clause, grouped);
            if (trim.getFromExpression() != null) {
                validateValue(trim.getFromExpression(), whole, clause, grouped);
            }
        } else if (expression instanceof ParenthesedSelect subquery) {
            // One value, worked out once before the rows; a constant for groups too
            valueSubquery(subquery, false);
        } else if (expression instanceof AnyComparisonExpression) {
            throw new QueryParseException("ANY, SOME and ALL are not supported (use IN (SELECT ...), or MIN or MAX in a subquery)");
        } else if (!(expression instanceof Column || expression instanceof StringValue || expression instanceof LongValue
                || expression instanceof DoubleValue || expression instanceof NullValue)) {
            if (expression instanceof Function) {
                String hint = "available functions: " + Functions.names();
                if (AggregateCall.isAggregate(expression)) {
                    hint = clause.equals(WHERE) ? "use HAVING to filter on an aggregate" : "an aggregate can't be used here";
                }
                String kind = clause.equals(SELECT_LIST) ? "Function or aggregate" : "Function";
                throw unsupported(kind + " in " + clause + ": " + whole, hint);
            }
            throw unsupported("Expression in " + clause + ": " + whole, "use fields, literals, arithmetic, ||, CASE and CAST");
        }
    }

    /**
     * A subquery used as a value or after IN. It is parsed now, so its errors are reported at
     * parse time, and recorded on the query being built, which runs it before its rows.
     *
     * @param list true after IN, where it may return any number of rows
     */
    private void valueSubquery(ParenthesedSelect subquery, boolean list) throws QueryParseException {
        ParsedQuery query = parseNested(subquery);
        List<ColumnInfo> columns = query.isUnionQuery()
            ? query.getUnionQuery().getSubQueries().get(0).getSelectColumns()
            : query.getSelectColumns();
        String count = columns.size() == 1 && "*".equals(columns.get(0).getExpression()) ? "*" : String.valueOf(columns.size());
        if (!count.equals("1")) {
            throw new QueryParseException((list ? "A subquery after IN" : "A subquery used as a value")
                + " must select one column, got " + count + ": " + subquery);
        }
        building.peek().addValueSubquery(subquery, query, list);
    }

    /**
     * A call to one of the functions in {@link Functions}, such as UPPER(name).
     */
    private void validateFunction(Function function, Expression whole, String clause, ParsedQuery grouped)
            throws QueryParseException {
        String name = function.getName().toUpperCase(Locale.ROOT);
        if (function.isDistinct()) {
            throw unsupported("DISTINCT in " + name, "DISTINCT belongs inside an aggregate");
        }
        int count = function.getParameters() == null ? 0 : function.getParameters().size();
        String problem = Functions.arityProblem(name, count);
        if (problem != null) {
            throw new QueryParseException(problem + ": " + function);
        }
        for (Object argument : function.getParameters()) {
            validateValue((Expression) argument, whole, clause, grouped);
        }
    }

    /**
     * The aggregate a function call stands for. Its argument is worked out on source rows,
     * so it can be a calculation but can't hold another aggregate.
     */
    private AggregateCall aggregateCall(Function function, Expression whole, String clause) throws QueryParseException {
        AggregateCall call = AggregateCall.from(function);
        if (call.calculation() != null) {
            validateValue(call.calculation(), whole, clause, null);
        }
        return call;
    }

    /**
     * A name in a calculation on groups must be something a group has: a GROUP BY field or,
     * outside the SELECT list, a SELECT alias. Booleans and double-quoted text aren't names.
     */
    private void validateGroupedColumn(Column column, String clause, ParsedQuery query) throws QueryParseException {
        String name = fieldName(column);
        boolean literal = column.toString().startsWith("\"") || name.equalsIgnoreCase("true") || name.equalsIgnoreCase("false");
        if (literal || matchingGroupField(name, query) != null) {
            return;
        }
        if (clause.equals(SELECT_LIST)) {
            throw new QueryParseException("Column '" + name + "' must be in GROUP BY or inside an aggregate");
        }
        if (selectAlias(name, query) == null) {
            throw new QueryParseException(clause.equals(HAVING)
                ? "HAVING can only use grouped fields, aliases and aggregates, got: " + name
                : "ORDER BY can only use grouped fields, aliases and aggregates here, got: " + name);
        }
    }

    private void validateCase(CaseExpression caseExpression, Expression whole, String clause, ParsedQuery grouped)
            throws QueryParseException {
        Expression subject = caseExpression.getSwitchExpression();
        if (subject != null) {
            validateValue(subject, whole, clause, grouped);
        }
        for (WhenClause when : caseExpression.getWhenClauses()) {
            if (subject == null) {
                validateValueCondition(when.getWhenExpression(), whole, clause, grouped);
            } else {
                validateValue(when.getWhenExpression(), whole, clause, grouped);
            }
            validateValue(when.getThenExpression(), whole, clause, grouped);
        }
        if (caseExpression.getElseExpression() != null) {
            validateValue(caseExpression.getElseExpression(), whole, clause, grouped);
        }
    }

    /**
     * A condition: in WHERE or HAVING, used as a value, or after CASE WHEN. Its operands can
     * be calculations.
     */
    private void validateValueCondition(Expression expression, Expression whole, String clause, ParsedQuery grouped)
            throws QueryParseException {
        switch (expression) {
            case Parenthesis parenthesis -> validateValueCondition(parenthesis.getExpression(), whole, clause, grouped);
            case AndExpression and -> {
                validateValueCondition(and.getLeftExpression(), whole, clause, grouped);
                validateValueCondition(and.getRightExpression(), whole, clause, grouped);
            }
            case OrExpression or -> {
                validateValueCondition(or.getLeftExpression(), whole, clause, grouped);
                validateValueCondition(or.getRightExpression(), whole, clause, grouped);
            }
            case NotExpression not -> validateValueCondition(not.getExpression(), whole, clause, grouped);
            case ComparisonOperator comparison -> {
                validateValue(comparison.getLeftExpression(), whole, clause, grouped);
                validateValue(comparison.getRightExpression(), whole, clause, grouped);
            }
            case Between between -> {
                validateValue(between.getLeftExpression(), whole, clause, grouped);
                validateValue(between.getBetweenExpressionStart(), whole, clause, grouped);
                validateValue(between.getBetweenExpressionEnd(), whole, clause, grouped);
            }
            case LikeExpression like -> {
                validateValue(like.getLeftExpression(), whole, clause, grouped);
                validateValue(like.getRightExpression(), whole, clause, grouped);
                likeEscape(like);
            }
            case IsNullExpression isNull -> validateValue(isNull.getLeftExpression(), whole, clause, grouped);
            case InExpression in when in.getRightExpression() instanceof ParenthesedExpressionList<?> list -> {
                validateInLeft(in);
                validateValue(in.getLeftExpression(), whole, clause, grouped);
                for (Object item : list) {
                    validateValue((Expression) item, whole, clause, grouped);
                }
            }
            case InExpression in when in.getRightExpression() instanceof ParenthesedSelect subquery -> {
                validateInLeft(in);
                validateValue(in.getLeftExpression(), whole, clause, grouped);
                valueSubquery(subquery, true);
            }
            case ExistsExpression _ -> throw unsupported("EXISTS", "use IN (SELECT ...) or a join");
            // Anything else, in every clause, is rejected here rather than when a row reaches it
            default -> throw unsupported("Condition in " + clause + ": " + whole,
                "compare values with =, <, >, BETWEEN, LIKE, IN or IS NULL");
        }
    }

    /** IN compares one value: (a, b) IN (...) compares a row. */
    private static void validateInLeft(InExpression in) throws QueryParseException {
        if (in.getLeftExpression() instanceof ParenthesedExpressionList<?> row) {
            throw unsupported("A row value such as " + row, "compare one value at a time");
        }
    }

    private void parseGroupBy(PlainSelect plainSelect, ParsedQuery query) throws QueryParseException {
        GroupByElement groupBy = plainSelect.getGroupBy();
        if (groupBy == null) {
            return;
        }
        if (groupBy.getGroupingSets() != null && !groupBy.getGroupingSets().isEmpty()) {
            throw unsupported("GROUPING SETS");
        }
        List<String> fields = new ArrayList<>();
        for (Object item : groupBy.getGroupByExpressionList()) {
            String field = groupField((Expression) item, query);
            if (query.hasAggregate(field)) {
                throw new QueryParseException("GROUP BY cannot use an aggregate: " + item);
            }
            fields.add(field);
        }
        query.setGroupBy(fields);
    }

    /**
     * The key of one GROUP BY item: a field, or the text of a calculation. A position or an
     * alias stands for the SELECT column it names.
     */
    private String groupField(Expression expression, ParsedQuery query) throws QueryParseException {
        ColumnInfo selected = null;
        if (expression instanceof LongValue position) {
            selected = selectedColumn(position, query.getSelectColumns());
        } else if (expression instanceof Column) {
            selected = selectAlias(fieldName(expression), query);
        }
        if (selected != null && !selected.isComputed()) {
            return selected.getExpression();
        }
        if (selected == null && expression instanceof Column) {
            return fieldName(expression);
        }
        Expression calculation = selected != null ? selected.getComputed() : expression;
        validateValue(calculation, calculation, GROUP_BY, null);
        return query.addGroupCalculation(calculation);
    }

    /**
     * Check each calculated SELECT column, now that GROUP BY is known. With grouping, or an
     * aggregate anywhere in the SELECT list, a calculation is on groups.
     */
    private void validateCalculatedColumns(ParsedQuery query) throws QueryParseException {
        boolean grouped = query.isAggregate();
        for (ColumnInfo column : query.getSelectColumns()) {
            grouped |= column.isComputed() && containsAggregate(column.getComputed());
        }
        for (ColumnInfo column : query.getSelectColumns()) {
            if (column.isComputed()) {
                validateValue(column.getComputed(), column.getComputed(), SELECT_LIST, grouped ? query : null);
            }
        }
    }

    private static boolean containsAggregate(Expression expression) {
        boolean[] found = {false};
        expression.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Function function) {
                if (AggregateCall.isAggregate(function)) {
                    found[0] = true;
                } else {
                    super.visit(function);
                }
            }
        });
        return found[0];
    }

    private void parseHaving(PlainSelect plainSelect, ParsedQuery query) throws QueryParseException {
        Expression having = plainSelect.getHaving();
        if (having == null) {
            return;
        }
        validateValueCondition(having, having, HAVING, query);
        if (!query.isAggregate()) {
            throw new QueryParseException("HAVING needs GROUP BY or an aggregate");
        }
        query.setHaving(having);
    }

    /**
     * With grouping, a selected field must be grouped. Otherwise its value would be an
     * arbitrary row's, which is a silently wrong answer.
     */
    private void validateGroupedColumns(ParsedQuery query) throws QueryParseException {
        if (!query.isAggregate()) {
            return;
        }
        List<ColumnInfo> columns = new ArrayList<>();
        for (ColumnInfo column : query.getSelectColumns()) {
            String expression = column.getExpression();
            if (column.isComputed()) {
                // Already checked by validateCalculatedColumns
                columns.add(column);
                continue;
            }
            if ("*".equals(expression)) {
                throw unsupported("SELECT * with GROUP BY or aggregates", "list the grouped fields and aggregates");
            }
            if (query.hasAggregate(expression)) {
                columns.add(column);
                continue;
            }
            String field = matchingGroupField(expression, query);
            if (field == null) {
                throw new QueryParseException(
                    "Column '" + expression + "' must be in GROUP BY or inside an aggregate");
            }
            columns.add(new ColumnInfo(field, column.hasAlias() ? column.getAlias() : null));
        }
        query.setSelectColumns(columns);
    }

    /** The GROUP BY field a name refers to, treating p.cat and cat as the same field. */
    private static String matchingGroupField(String name, ParsedQuery query) {
        for (String field : query.getGroupBy()) {
            if (query.getGroupCalculations().containsKey(field)) {
                // A calculation's text, such as t.a + t.price, isn't a field name to match
                continue;
            }
            if (field.equals(name) || field.endsWith("." + name) || name.endsWith("." + field)) {
                return field;
            }
        }
        return null;
    }

    private static ColumnInfo selectAlias(String name, ParsedQuery query) {
        for (ColumnInfo column : query.getSelectColumns()) {
            if (column.hasAlias() && column.getAlias().equalsIgnoreCase(name)) {
                return column;
            }
        }
        return null;
    }

    /**
     * The field path of a column reference, without any double quotes around its name.
     */
    static String fieldName(Expression expression) {
        if (AggregateCall.isAggregate(expression)) {
            return AggregateCall.keyOf((Function) expression);
        }
        return unquote(expression.toString());
    }

    private static String unquote(String name) {
        return name == null ? null : name.replace("\"", "");
    }

    /**
     * The character set by LIKE ... ESCAPE, or null if there is none.
     */
    static Character likeEscape(LikeExpression like) throws UnsupportedExpressionException {
        Expression escape = like.getEscape();
        if (escape == null) {
            return null;
        }
        if (escape instanceof StringValue && ((StringValue) escape).getNotExcapedValue().length() == 1) {
            return ((StringValue) escape).getNotExcapedValue().charAt(0);
        }
        throw new UnsupportedExpressionException("ESCAPE must be a single character, got: " + escape);
    }

    /**
     * Parse and validate a LIMIT/TOP row count: must be a non-negative integer.
     */
    private long parseRowCount(String raw, String clause) throws QueryParseException {
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new QueryParseException(clause + " must be a non-negative integer, got: " + raw);
        }
        if (value < 0) {
            throw new QueryParseException(clause + " must not be negative, got: " + value);
        }
        return value;
    }

    private void parseSelectItems(PlainSelect plainSelect, ParsedQuery query) throws QueryParseException {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
        List<ColumnInfo> columns = new ArrayList<>();

        for (SelectItem<?> item : selectItems) {
            if (AggregateCall.isAggregate(item.getExpression())) {
                AggregateCall call = aggregateCall((Function) item.getExpression(), item.getExpression(), SELECT_LIST);
                query.addAggregate(call);
                String name = item.getAlias() != null ? item.getAlias().getName() : item.getExpression().toString();
                columns.add(new ColumnInfo(call.key(), unquote(name)));
                continue;
            }
            if (!isPlainSelectItem(item)) {
                // Checked by validateCalculatedColumns, once GROUP BY is known
                String name = item.getAlias() == null ? null : unquote(item.getAlias().getName());
                columns.add(ColumnInfo.computed(columns.size(), item.getExpression(), name));
                continue;
            }
            String fullString = item.toString();
            String expression;
            String alias = null;
            
            // Check for AS alias in the string representation
            // Format: "column AS alias" or "column alias"
            String upperString = fullString.toUpperCase();
            int asIndex = upperString.indexOf(" AS ");
            
            if (asIndex > 0) {
                // Has explicit AS alias
                expression = fullString.substring(0, asIndex).trim();
                alias = fullString.substring(asIndex + 4).trim();
            } else if (fullString.contains(" ") && !fullString.equals("*")) {
                // Has implicit alias (space without AS)
                int spaceIndex = fullString.lastIndexOf(' ');
                expression = fullString.substring(0, spaceIndex).trim();
                alias = fullString.substring(spaceIndex + 1).trim();
            } else {
                // No alias
                expression = fullString;
            }
            
            columns.add(new ColumnInfo(unquote(expression), unquote(alias)));
        }

        query.setSelectColumns(columns);
    }

    private void parseFromClause(PlainSelect plainSelect, ParsedQuery query) throws QueryParseException {
        FromItem fromItem = plainSelect.getFromItem();
        
        if (fromItem == null) {
            throw new QueryParseException("FROM clause is required");
        }

        TableInfo tableInfo = extractTableInfo(fromItem);
        query.setFromTable(tableInfo);
    }

    private void parseJoins(PlainSelect plainSelect, ParsedQuery query) throws QueryParseException {
        List<Join> joins = plainSelect.getJoins();
        
        if (joins == null || joins.isEmpty()) {
            return;
        }

        List<JoinInfo> joinInfos = new ArrayList<>();
        List<UnnestInfo> unnestInfos = new ArrayList<>();
        
        for (Join join : joins) {
            FromItem rightItem = join.getRightItem();
            
            // Check if this is an UNNEST operation (TableFunction)
            if (rightItem instanceof net.sf.jsqlparser.statement.select.TableFunction) {
                UnnestInfo unnestInfo = extractUnnestInfo((net.sf.jsqlparser.statement.select.TableFunction) rightItem);
                unnestInfos.add(unnestInfo);
            } else {
                // Regular JOIN
                JoinInfo joinInfo = new JoinInfo();
                joinInfo.setType(joinType(join));
                joinInfo.setTable(extractTableInfo(rightItem));
                joinInfo.setOn(joinCondition(join, joinInfo.getType()));
                joinInfos.add(joinInfo);
            }
        }

        query.setJoins(joinInfos);
        query.setUnnests(unnestInfos);
    }

    private JoinInfo.Type joinType(Join join) throws QueryParseException {
        if (join.isNatural()) {
            throw unsupported("NATURAL JOIN", "write JOIN ... ON with the matching fields");
        }
        if (join.isCross() || join.isSimple()) {
            return JoinInfo.Type.CROSS;
        }
        if (join.isFull()) {
            return JoinInfo.Type.FULL;
        }
        if (join.isRight()) {
            return JoinInfo.Type.RIGHT;
        }
        return join.isLeft() ? JoinInfo.Type.LEFT : JoinInfo.Type.INNER;
    }

    /**
     * A join's ON condition, which can be anything WHERE accepts, or null for a cross join.
     */
    private Expression joinCondition(Join join, JoinInfo.Type type) throws QueryParseException {
        if (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()) {
            throw unsupported("JOIN ... USING", "write ON a.x = b.x instead");
        }
        java.util.Collection<Expression> on = join.getOnExpressions();
        boolean hasOn = on != null && !on.isEmpty();
        if (type == JoinInfo.Type.CROSS) {
            if (hasOn) {
                throw join.isCross()
                    ? unsupported("ON after CROSS JOIN", "use JOIN ... ON, or CROSS JOIN without ON")
                    : unsupported("ON after a comma join", "use JOIN ... ON, or a comma without ON");
            }
            return null;
        }
        if (!hasOn) {
            throw new QueryParseException("JOIN requires ON condition");
        }
        if (on.size() > 1) {
            throw unsupported("More than one ON for JOIN " + join.getRightItem(), "join the conditions with AND");
        }
        Expression condition = on.iterator().next();
        validateValueCondition(condition, condition, JOIN_ON, null);
        return condition;
    }

    /**
     * A table in FROM or a join: a mapped table, CTE or view by name, or a derived table,
     * (SELECT ...) t. Other kinds of FROM item are rejected here, where they used to be taken
     * as a table name and fail as one with no mapping.
     */
    private TableInfo extractTableInfo(FromItem fromItem) throws QueryParseException {
        if (fromItem instanceof LateralSubSelect) {
            throw unsupported("LATERAL", "a derived table can't refer to the outer query");
        }
        if (fromItem instanceof ParenthesedSelect parenthesed) {
            return derivedTable(parenthesed);
        }
        if (fromItem instanceof ParenthesedFromItem) {
            throw unsupported("Parentheses in FROM: " + fromItem,
                "put one pair of parentheses around a subquery, and none around joins");
        }
        if (fromItem instanceof TableFunction function) {
            if (function.getFunction() != null && "UNNEST".equalsIgnoreCase(function.getFunction().getName())) {
                throw unsupported("UNNEST in FROM", "write FROM t, UNNEST(t.items) AS i(item)");
            }
            throw unsupported("Table function in FROM: " + fromItem, "use a mapped table or a derived table");
        }
        if (!(fromItem instanceof net.sf.jsqlparser.schema.Table)) {
            throw unsupported("FROM " + fromItem, "use a mapped table or a derived table");
        }

        TableInfo tableInfo = new TableInfo();
        
        String tableName = fromItem.toString();
        String alias = null;
        
        // Extract alias if present
        if (fromItem.getAlias() != null) {
            alias = fromItem.getAlias().getName();
            // Get the base table name without alias
            if (fromItem instanceof net.sf.jsqlparser.schema.Table) {
                tableName = ((net.sf.jsqlparser.schema.Table) fromItem).getName();
            }
        }
        
        tableInfo.setTableName(tableName);
        tableInfo.setAlias(alias);
        
        return tableInfo;
    }

    /**
     * FROM (SELECT ...) t: the subquery is parsed now, so its errors are reported at parse
     * time, and run once when the query runs, as a table named t.
     */
    private TableInfo derivedTable(ParenthesedSelect parenthesed) throws QueryParseException {
        Alias alias = parenthesed.getAlias();
        if (alias == null || alias.getName() == null || alias.getName().isBlank()) {
            throw new QueryParseException("A derived table needs an alias, as in FROM (SELECT ...) t");
        }
        ParsedQuery subquery = parseNested(parenthesed.getSelect());
        if (alias.getAliasColumns() != null && !alias.getAliasColumns().isEmpty()) {
            throw new QueryParseException("Column names after a derived table's alias are not supported "
                + "(name the columns inside the subquery)");
        }
        TableInfo tableInfo = new TableInfo();
        tableInfo.setSubquery(subquery);
        tableInfo.setTableName("(" + parenthesed.getSelect() + ")");
        tableInfo.setAlias(unquote(alias.getName()));
        return tableInfo;
    }

    /**
     * A subquery: a plain SELECT or a set operation. CTEs belong at the top of the
     * statement, where every part of it can read them.
     */
    private ParsedQuery parseNested(Select select) throws QueryParseException {
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            throw unsupported("WITH inside a subquery", "put the CTEs at the top of the statement");
        }
        if (select instanceof PlainSelect plainSelect) {
            return buildParsedQuery(plainSelect);
        }
        if (select instanceof SetOperationList setOperation) {
            ParsedQuery query = new ParsedQuery();
            UnionQuery unionQuery = parseUnionQuery(setOperation, query);
            unionQuery.validateColumnConsistency();
            query.setUnionQuery(unionQuery);
            return query;
        }
        if (select instanceof ParenthesedSelect parenthesed) {
            return parseNested(parenthesed.getSelect());
        }
        if (select instanceof net.sf.jsqlparser.statement.select.Values) {
            throw unsupported("VALUES", "use a mapped table");
        }
        throw unsupported("Subquery: " + select);
    }

    private UnnestInfo extractUnnestInfo(net.sf.jsqlparser.statement.select.TableFunction tableFunction) throws QueryParseException {
        String functionString = tableFunction.toString();
        
        // Parse UNNEST(expression) AS alias(column) format
        // Example: "UNNEST(tags) AS t(tag)"
        if (!functionString.toUpperCase().startsWith("UNNEST(")) {
            throw new QueryParseException("Invalid UNNEST syntax: " + functionString);
        }
        
        // Find the array expression inside UNNEST(...)
        int startParen = functionString.indexOf('(');
        int endParen = functionString.indexOf(')', startParen);
        if (startParen == -1 || endParen == -1) {
            throw new QueryParseException("Invalid UNNEST syntax: " + functionString);
        }
        
        String arrayExpression = functionString.substring(startParen + 1, endParen).trim();
        
        // Parse the alias part: AS alias(column)
        String aliasPart = functionString.substring(endParen + 1).trim();
        String alias = null;
        String elementColumn = null;
        
        if (aliasPart.toUpperCase().startsWith("AS ")) {
            String aliasExpression = aliasPart.substring(3).trim();
            // Parse alias(column) format
            int aliasStartParen = aliasExpression.indexOf('(');
            int aliasEndParen = aliasExpression.indexOf(')', aliasStartParen);
            
            if (aliasStartParen > 0 && aliasEndParen > aliasStartParen) {
                alias = aliasExpression.substring(0, aliasStartParen).trim();
                elementColumn = aliasExpression.substring(aliasStartParen + 1, aliasEndParen).trim();
            } else {
                // Simple alias without column specification
                alias = aliasExpression;
                elementColumn = "value"; // Default column name
            }
        }
        
        if (alias == null || elementColumn == null) {
            throw new QueryParseException("UNNEST requires alias and column specification: " + functionString);
        }
        
        return new UnnestInfo(arrayExpression, alias, elementColumn);
    }

    private void parseOrderBy(PlainSelect plainSelect, ParsedQuery query) throws QueryParseException {
        List<net.sf.jsqlparser.statement.select.OrderByElement> orderByElements = plainSelect.getOrderByElements();
        
        if (orderByElements == null || orderByElements.isEmpty()) {
            return;
        }

        List<OrderByInfo> orderByInfos = new ArrayList<>();
        
        for (net.sf.jsqlparser.statement.select.OrderByElement element : orderByElements) {
            Expression expression = element.getExpression();
            boolean ascending = element.isAsc() || !element.isAscDescPresent(); // Default to ASC if not specified

            boolean plain = expression instanceof Column || expression instanceof LongValue
                || (query.isAggregate() && AggregateCall.isAggregate(expression));
            if (!plain) {
                // A calculation: its value is added to each row or group under a key of its own
                validateValue(expression, expression, ORDER_BY, query.isAggregate() ? query : null);
                String key = ColumnInfo.calculatedKey(query.getSelectColumns().size() + orderByInfos.size());
                orderByInfos.add(new OrderByInfo(key, ascending, expression));
                continue;
            }
            String column = query.isAggregate()
                ? resolveGroupedOrderColumn(expression, query)
                : resolveOrderColumn(expression, query.getSelectColumns());

            orderByInfos.add(new OrderByInfo(column, ascending));
        }
        
        query.setOrderBy(orderByInfos);
    }

    /**
     * Sorting runs before projection, so an alias or a position has to become the source
     * field it stands for. As in SQL, an alias takes priority over a field of the same name.
     */
    private String resolveOrderColumn(Expression expression, List<ColumnInfo> columns) throws QueryParseException {
        if (expression instanceof LongValue) {
            return selectedColumn((LongValue) expression, columns).getExpression();
        }
        String name = fieldName(expression);
        for (ColumnInfo column : columns) {
            if (column.hasAlias() && column.getAlias().equalsIgnoreCase(name)) {
                return column.getExpression();
            }
        }
        return name;
    }

    /**
     * After grouping, only group fields, aliases, positions and aggregates still exist.
     */
    private String resolveGroupedOrderColumn(Expression expression, ParsedQuery query) throws QueryParseException {
        if (expression instanceof LongValue) {
            return selectedColumn((LongValue) expression, query.getSelectColumns()).getExpression();
        }
        if (AggregateCall.isAggregate(expression)) {
            AggregateCall call = aggregateCall((Function) expression, expression, ORDER_BY);
            query.addAggregate(call);
            return call.key();
        }
        String name = fieldName(expression);
        ColumnInfo aliased = selectAlias(name, query);
        if (aliased != null) {
            return aliased.getExpression();
        }
        String field = matchingGroupField(name, query);
        if (field == null) {
            throw new QueryParseException("ORDER BY can only use grouped fields, aliases and aggregates here, got: " + name);
        }
        return field;
    }

    private ColumnInfo selectedColumn(LongValue position, List<ColumnInfo> columns) throws QueryParseException {
        long index = position.getValue();
        if (columns.stream().anyMatch(column -> "*".equals(column.getExpression()))) {
            throw unsupported("ORDER BY " + index + " with SELECT *", "list the columns or order by a field name");
        }
        if (index < 1 || index > columns.size()) {
            throw new QueryParseException("ORDER BY position " + index + " is not in the SELECT list");
        }
        return columns.get((int) index - 1);
    }

    /**
     * Parse WITH clause (Common Table Expressions) from Select statement.
     * CTEs are defined before the main SELECT and can be referenced in the main query.
     */
    private void parseWithClauseFromSelect(Select select, ParsedQuery query) throws QueryParseException {
        if (select.getWithItemsList() == null || select.getWithItemsList().isEmpty()) {
            return;
        }
        
        for (net.sf.jsqlparser.statement.select.WithItem withItem : select.getWithItemsList()) {
            // Get CTE name from alias or name property
            String cteName;
            if (withItem.getAlias() != null) {
                cteName = withItem.getAlias().getName();
            } else {
                // Try to extract name from string representation
                // Format is typically "name AS (SELECT ...)" or just "name (SELECT ...)"
                String withItemStr = withItem.toString();
                int spaceIndex = withItemStr.indexOf(' ');
                int parenIndex = withItemStr.indexOf('(');
                int nameEnd = Math.min(spaceIndex > 0 ? spaceIndex : withItemStr.length(),
                                      parenIndex > 0 ? parenIndex : withItemStr.length());
                cteName = withItemStr.substring(0, nameEnd).trim();
            }
            
            // Get the SELECT statement for this CTE
            Select cteSelect = withItem.getSelect();
            
            // Handle different SELECT types (PlainSelect, ParenthesedSelect, etc.)
            PlainSelect ctePlainSelect = extractPlainSelect(cteSelect);
            if (ctePlainSelect == null) {
                throw new QueryParseException("CTE must be a simple SELECT query: " + cteName);
            }
            
            // Recursively parse the CTE query
            ParsedQuery cteQuery = buildParsedQuery(ctePlainSelect);
            
            // Add CTE to the query
            query.addCTE(cteName, cteQuery);
        }
    }
    
    /**
     * Extract PlainSelect from various SELECT types (PlainSelect, ParenthesedSelect, etc.).
     */
    private PlainSelect extractPlainSelect(Select select) {
        if (select instanceof PlainSelect) {
            return (PlainSelect) select;
        }
        
        // Try to get PlainSelect from ParenthesedSelect
        if (select instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect) {
            net.sf.jsqlparser.statement.select.ParenthesedSelect parenthesed = 
                (net.sf.jsqlparser.statement.select.ParenthesedSelect) select;
            Select innerSelect = parenthesed.getSelect();
            if (innerSelect instanceof PlainSelect) {
                return (PlainSelect) innerSelect;
            }
        }
        
        return null;
    }

    /**
     * Extract the inner SELECT SQL for a named CTE from a full query that includes WITH.
     */
    public String extractCteSql(String fullSql, String cteName) throws QueryParseException {
        try {
            Statement statement = CCJSqlParserUtil.parse(fullSql);
            if (!(statement instanceof Select)) {
                throw new QueryParseException("Only SELECT statements are supported");
            }
            Select select = (Select) statement;
            if (select.getWithItemsList() == null) {
                throw new QueryParseException("Query has no WITH clause");
            }
            for (net.sf.jsqlparser.statement.select.WithItem withItem : select.getWithItemsList()) {
                String name = withItem.getAlias() != null ? withItem.getAlias().getName() : null;
                if (name != null && name.equalsIgnoreCase(cteName)) {
                    PlainSelect plain = extractPlainSelect(withItem.getSelect());
                    if (plain == null) {
                        throw new QueryParseException("CTE must be a simple SELECT query: " + cteName);
                    }
                    return plain.toString();
                }
            }
            throw new QueryParseException("CTE not found in query: " + cteName);
        } catch (JSQLParserException e) {
            throw new QueryParseException("Failed to parse SQL: " + e.getMessage(), e);
        }
    }
}

