package com.jsonsql.query;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
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
            
            PlainSelect plainSelect = getPlainSelect(select);
            
            // Build the main query (CTEs already stored in query)
            return buildParsedQuery(plainSelect, query);
        } catch (JSQLParserException e) {
            throw new QueryParseException("Invalid SQL syntax: " + e.getMessage(), e);
        }
    }

    private PlainSelect getPlainSelect(Select select) throws QueryParseException {
        if (!(select instanceof PlainSelect)) {
            throw new QueryParseException("Only simple SELECT queries are supported (no UNION, etc.)");
        }
        
        return (PlainSelect) select;
    }

    private ParsedQuery buildParsedQuery(PlainSelect plainSelect) throws QueryParseException {
        return buildParsedQuery(plainSelect, new ParsedQuery());
    }
    
    private ParsedQuery buildParsedQuery(PlainSelect plainSelect, ParsedQuery query) throws QueryParseException {
        // CTEs are already parsed and stored in query, so we don't need to parse them again

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
            query.setWhereExpression(plainSelect.getWhere());
        }

        // Parse ORDER BY clause
        parseOrderBy(plainSelect, query);

        // Parse TOP/LIMIT
        if (plainSelect.getLimit() != null && plainSelect.getLimit().getRowCount() != null) {
            query.setLimit(Long.parseLong(plainSelect.getLimit().getRowCount().toString()));
        }
        if (plainSelect.getTop() != null && plainSelect.getTop().getExpression() != null) {
            query.setTop(Long.parseLong(plainSelect.getTop().getExpression().toString()));
        }

        return query;
    }

    private void parseSelectItems(PlainSelect plainSelect, ParsedQuery query) {
        List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
        List<ColumnInfo> columns = new ArrayList<>();

        for (SelectItem<?> item : selectItems) {
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
            
            columns.add(new ColumnInfo(expression, alias));
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

    private void parseOrderBy(PlainSelect plainSelect, ParsedQuery query) {
        List<net.sf.jsqlparser.statement.select.OrderByElement> orderByElements = plainSelect.getOrderByElements();
        
        if (orderByElements == null || orderByElements.isEmpty()) {
            return;
        }

        List<OrderByInfo> orderByInfos = new ArrayList<>();
        
        for (net.sf.jsqlparser.statement.select.OrderByElement element : orderByElements) {
            String column = element.getExpression().toString();
            boolean ascending = element.isAsc() || !element.isAscDescPresent(); // Default to ASC if not specified
            
            orderByInfos.add(new OrderByInfo(column, ascending));
        }
        
        query.setOrderBy(orderByInfos);
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
     * Parse WITH clause (Common Table Expressions).
     * This method is called from buildParsedQuery but CTEs are actually parsed earlier.
     */
    private void parseWithClause(PlainSelect plainSelect, ParsedQuery query) throws QueryParseException {
        // CTEs are parsed at the Select level, not PlainSelect level
        // This method is a placeholder - actual parsing happens in parseWithClauseFromSelect
    }
}

