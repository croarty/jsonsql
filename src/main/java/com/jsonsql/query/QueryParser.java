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
            throw new QueryParseException("Invalid SQL syntax: " + e.getMessage(), e);
        }
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

        rejectOffset(setOp.getOffset() != null || setOp.getFetch() != null
            || (setOp.getLimit() != null && setOp.getLimit().getOffset() != null));
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
            validateWhere(plainSelect.getWhere());
            query.setWhereExpression(plainSelect.getWhere());
        }

        // Parse ORDER BY clause
        parseOrderBy(plainSelect, query);

        // Parse TOP/LIMIT
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
        if (plainSelect.getGroupBy() != null) {
            throw unsupported("GROUP BY");
        }
        if (plainSelect.getHaving() != null) {
            throw unsupported("HAVING");
        }
        rejectOffset(plainSelect.getOffset() != null || plainSelect.getFetch() != null
            || (plainSelect.getLimit() != null && plainSelect.getLimit().getOffset() != null));
    }

    private void rejectOffset(boolean hasOffset) throws QueryParseException {
        if (hasOffset) {
            throw unsupported("OFFSET / FETCH");
        }
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
    private void validateWhere(Expression expression) throws QueryParseException {
        switch (expression) {
            case AndExpression and -> {
                validateWhere(and.getLeftExpression());
                validateWhere(and.getRightExpression());
            }
            case OrExpression or -> {
                validateWhere(or.getLeftExpression());
                validateWhere(or.getRightExpression());
            }
            case NotExpression not -> validateWhere(not.getExpression());
            case Parenthesis parenthesis -> validateWhere(parenthesis.getExpression());
            case ComparisonOperator comparison -> {
                validateOperand(comparison.getLeftExpression());
                validateOperand(comparison.getRightExpression());
            }
            case LikeExpression like -> {
                validateField(like.getLeftExpression(), like);
                validateOperand(like.getRightExpression());
                likeEscape(like);
            }
            case IsNullExpression isNull -> validateField(isNull.getLeftExpression(), isNull);
            case InExpression in -> validateIn(in);
            // Anything else, such as BETWEEN, is reported by WhereEvaluator
            default -> {
            }
        }
    }

    private void validateIn(InExpression in) throws QueryParseException {
        validateField(in.getLeftExpression(), in);
        if (in.getRightExpression() instanceof ParenthesedExpressionList<?> list) {
            for (Object item : list) {
                validateOperand((Expression) item);
            }
        }
    }

    private void validateField(Expression expression, Expression condition) throws QueryParseException {
        if (!(expression instanceof Column)) {
            throw unsupported("Expression in WHERE: " + condition, "the left side must be a field");
        }
    }

    private void validateOperand(Expression expression) throws QueryParseException {
        if (expression instanceof Parenthesis) {
            validateOperand(((Parenthesis) expression).getExpression());
            return;
        }
        boolean signedNumber = expression instanceof SignedExpression
            && (((SignedExpression) expression).getExpression() instanceof LongValue
                || ((SignedExpression) expression).getExpression() instanceof DoubleValue);
        if (expression instanceof Column || expression instanceof StringValue || expression instanceof LongValue
                || expression instanceof DoubleValue || expression instanceof NullValue || signedNumber) {
            return;
        }
        String kind = expression instanceof Function ? "Function" : "Expression";
        throw unsupported(kind + " in WHERE: " + expression, "compare a field with a field or a literal");
    }

    /**
     * The field path of a column reference, without any double quotes around its name.
     */
    static String fieldName(Expression expression) {
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
            String column = resolveOrderColumn(element.getExpression(), query.getSelectColumns());
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

