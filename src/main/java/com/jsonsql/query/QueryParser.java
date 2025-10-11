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
            PlainSelect plainSelect = getPlainSelect(select);

            return buildParsedQuery(plainSelect);
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
        ParsedQuery query = new ParsedQuery();

        // Parse SELECT clause
        parseSelectItems(plainSelect, query);

        // Parse FROM clause
        parseFromClause(plainSelect, query);

        // Parse JOINs
        parseJoins(plainSelect, query);

        // Parse WHERE clause
        if (plainSelect.getWhere() != null) {
            query.setWhereClause(plainSelect.getWhere().toString());
        }

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
        List<String> columns = new ArrayList<>();

        for (SelectItem<?> item : selectItems) {
            columns.add(item.toString());
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
        
        for (Join join : joins) {
            JoinInfo joinInfo = new JoinInfo();
            joinInfo.setLeftJoin(join.isLeft());
            joinInfo.setTable(extractTableInfo(join.getRightItem()));
            
            if (join.getOnExpressions() != null && !join.getOnExpressions().isEmpty()) {
                joinInfo.setOnCondition(join.getOnExpressions().iterator().next().toString());
            } else {
                throw new QueryParseException("JOIN requires ON condition");
            }
            
            joinInfos.add(joinInfo);
        }
        
        query.setJoins(joinInfos);
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
}

