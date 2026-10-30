package com.jsonsql.query;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and validates SQL queries.
 */
public class QueryParser {

    /**
     * Parse a SQL query string into a ParsedQuery object.
     */
    public ParsedQuery parse(String sql) throws QueryParseException {
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
            
            return query;
        } catch (JSQLParserException e) {
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
     * Parse a UNION query and build the UnionQuery object.
     */
    private UnionQuery parseUnionQuery(net.sf.jsqlparser.statement.select.SetOperationList setOp, ParsedQuery parentQuery) 
            throws QueryParseException {
        UnionQuery unionQuery = new UnionQuery();
        
        for (net.sf.jsqlparser.statement.select.SetOperation op : setOp.getOperations()) {
            if (!(op instanceof net.sf.jsqlparser.statement.select.UnionOp)) {
                throw unsupported(op.toString().trim(), "only UNION and UNION ALL are supported");
            }
            unionQuery.addOperation(((net.sf.jsqlparser.statement.select.UnionOp) op).isAll());
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
            validateCondition(plainSelect.getWhere(), null);
            query.setWhereExpression(plainSelect.getWhere());
        }

        // Parse GROUP BY and HAVING
        parseGroupBy(plainSelect, query);
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
     * Only plain column references and {@code *} can be projected; anything else would
     * otherwise be looked up as a field name and silently produce null.
     */
    private void validateSelectItem(SelectItem<?> item) throws QueryParseException {
        Expression expression = item.getExpression();
        if (expression instanceof AllTableColumns) {
            throw unsupported("SELECT " + expression, "use SELECT * or list the columns");
        }
        if (expression instanceof AllColumns || expression instanceof Column) {
            return;
        }
        String kind = expression instanceof Function ? "Function or aggregate" : "Expression";
        throw unsupported(kind + " in SELECT list: " + expression,
            "only column references and * can be selected");
    }

    /**
     * Each side of a condition must be a field or a literal. Anything else would otherwise be
     * looked up as a field name and silently match nothing.
     */
    private void validateCondition(Expression expression, ParsedQuery having) throws QueryParseException {
        switch (expression) {
            case AndExpression and -> {
                validateCondition(and.getLeftExpression(), having);
                validateCondition(and.getRightExpression(), having);
            }
            case OrExpression or -> {
                validateCondition(or.getLeftExpression(), having);
                validateCondition(or.getRightExpression(), having);
            }
            case NotExpression not -> validateCondition(not.getExpression(), having);
            case Parenthesis parenthesis -> validateCondition(parenthesis.getExpression(), having);
            case ComparisonOperator comparison -> {
                validateOperand(comparison.getLeftExpression(), having);
                validateOperand(comparison.getRightExpression(), having);
            }
            case LikeExpression like -> {
                validateField(like.getLeftExpression(), like, having);
                validateOperand(like.getRightExpression(), having);
                likeEscape(like);
            }
            case IsNullExpression isNull -> validateField(isNull.getLeftExpression(), isNull, having);
            case InExpression in -> validateIn(in, having);
            case Between between -> {
                validateOperand(between.getLeftExpression(), having);
                validateOperand(between.getBetweenExpressionStart(), having);
                validateOperand(between.getBetweenExpressionEnd(), having);
            }
            // Anything else, such as EXISTS, is reported by WhereEvaluator
            default -> {
            }
        }
    }

    private void validateIn(InExpression in, ParsedQuery having) throws QueryParseException {
        validateField(in.getLeftExpression(), in, having);
        if (in.getRightExpression() instanceof ParenthesedExpressionList<?> list) {
            for (Object item : list) {
                validateOperand((Expression) item, having);
            }
        }
    }

    private void validateField(Expression expression, Expression condition, ParsedQuery having) throws QueryParseException {
        if (having != null && AggregateCall.isAggregate(expression)) {
            having.addAggregate(AggregateCall.from((Function) expression));
            return;
        }
        if (having != null && expression instanceof Column) {
            validateHavingColumn((Column) expression, having);
        }
        if (!(expression instanceof Column)) {
            throw unsupported("Expression in WHERE: " + condition, "the left side must be a field");
        }
    }

    private void validateOperand(Expression expression, ParsedQuery having) throws QueryParseException {
        if (expression instanceof Parenthesis) {
            validateOperand(((Parenthesis) expression).getExpression(), having);
            return;
        }
        if (having != null && AggregateCall.isAggregate(expression)) {
            having.addAggregate(AggregateCall.from((Function) expression));
            return;
        }
        if (having != null && expression instanceof Column) {
            validateHavingColumn((Column) expression, having);
        }
        boolean signedNumber = expression instanceof SignedExpression
            && (((SignedExpression) expression).getExpression() instanceof LongValue
                || ((SignedExpression) expression).getExpression() instanceof DoubleValue);
        if (expression instanceof Column || expression instanceof StringValue || expression instanceof LongValue
                || expression instanceof DoubleValue || expression instanceof NullValue || signedNumber) {
            return;
        }
        String kind = expression instanceof Function ? "Function" : "Expression";
        String clause = having == null ? "WHERE" : "HAVING";
        throw unsupported(kind + " in " + clause + ": " + expression, "compare a field with a field or a literal");
    }

    /**
     * HAVING works on groups, so a name must be a group field or a SELECT alias. Booleans
     * and double-quoted text aren't names here.
     */
    private void validateHavingColumn(Column column, ParsedQuery query) throws QueryParseException {
        String name = fieldName(column);
        boolean literal = column.toString().startsWith("\"") || name.equalsIgnoreCase("true") || name.equalsIgnoreCase("false");
        if (literal || matchingGroupField(name, query) != null || selectAlias(name, query) != null) {
            return;
        }
        throw new QueryParseException("HAVING can only use grouped fields, aliases and aggregates, got: " + name);
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

    private String groupField(Expression expression, ParsedQuery query) throws QueryParseException {
        if (expression instanceof LongValue) {
            return selectedColumn((LongValue) expression, query.getSelectColumns()).getExpression();
        }
        if (!(expression instanceof Column)) {
            throw unsupported("Expression in GROUP BY: " + expression, "group by a column, an alias or a position");
        }
        ColumnInfo aliased = selectAlias(fieldName(expression), query);
        return aliased != null ? aliased.getExpression() : fieldName(expression);
    }

    private void parseHaving(PlainSelect plainSelect, ParsedQuery query) throws QueryParseException {
        Expression having = plainSelect.getHaving();
        if (having == null) {
            return;
        }
        validateCondition(having, query);
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
                AggregateCall call = AggregateCall.from((Function) item.getExpression());
                query.addAggregate(call);
                String name = item.getAlias() != null ? item.getAlias().getName() : item.getExpression().toString();
                columns.add(new ColumnInfo(call.key(), unquote(name)));
                continue;
            }
            validateSelectItem(item);
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
                rejectUnsupportedJoinType(join);
                // Regular JOIN
                JoinInfo joinInfo = new JoinInfo();
                joinInfo.setLeftJoin(join.isLeft());
                joinInfo.setTable(extractTableInfo(rightItem));
                
                if (join.getOnExpressions() != null && !join.getOnExpressions().isEmpty()) {
                    joinInfo.setOnCondition(join.getOnExpressions().iterator().next().toString());
                } else {
                    throw new QueryParseException("JOIN requires ON condition");
                }
                
                joinInfos.add(joinInfo);
            }
        }
        
        query.setJoins(joinInfos);
        query.setUnnests(unnestInfos);
    }

    private void rejectUnsupportedJoinType(Join join) throws QueryParseException {
        if (join.isRight()) {
            throw unsupported("RIGHT JOIN");
        }
        if (join.isFull()) {
            throw unsupported("FULL OUTER JOIN");
        }
        if (join.isCross()) {
            throw unsupported("CROSS JOIN");
        }
        if (join.isNatural()) {
            throw unsupported("NATURAL JOIN");
        }
    }

    private TableInfo extractTableInfo(FromItem fromItem) {
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
            String column = query.isAggregate()
                ? resolveGroupedOrderColumn(element.getExpression(), query)
                : resolveOrderColumn(element.getExpression(), query.getSelectColumns());
            boolean ascending = element.isAsc() || !element.isAscDescPresent(); // Default to ASC if not specified

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
        if (!(expression instanceof Column)) {
            throw unsupported("Expression in ORDER BY: " + expression, "order by a column, an alias or a position");
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
            AggregateCall call = AggregateCall.from((Function) expression);
            query.addAggregate(call);
            return call.key();
        }
        if (!(expression instanceof Column)) {
            throw unsupported("Expression in ORDER BY: " + expression, "order by a column, an alias or a position");
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

