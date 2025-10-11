package com.jsonsql.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryParserTest {

    private QueryParser parser;

    @BeforeEach
    void setUp() {
        parser = new QueryParser();
    }

    @Test
    void testSimpleSelect() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT * FROM products");
        
        assertNotNull(query);
        assertEquals(1, query.getSelectColumns().size());
        assertEquals("*", query.getSelectColumns().get(0).getExpression());
        assertEquals("products", query.getFromTable().getTableName());
        assertNull(query.getFromTable().getAlias());
        assertFalse(query.hasJoins());
    }

    @Test
    void testSelectWithColumns() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT name, price, category FROM products");
        
        assertEquals(3, query.getSelectColumns().size());
        assertEquals("name", query.getSelectColumns().get(0).getExpression());
        assertEquals("price", query.getSelectColumns().get(1).getExpression());
        assertEquals("category", query.getSelectColumns().get(2).getExpression());
        assertFalse(query.getSelectColumns().get(0).hasAlias());
    }

    @Test
    void testSelectWithTableAlias() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT p.name, p.price FROM products p");
        
        assertEquals("products", query.getFromTable().getTableName());
        assertEquals("p", query.getFromTable().getAlias());
        assertEquals("p", query.getFromTable().getEffectiveName());
    }

    @Test
    void testSelectWithWhere() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT * FROM products WHERE category = 'Tools'");
        
        assertNotNull(query.getWhereClause());
        assertTrue(query.getWhereClause().contains("category"));
    }

    @Test
    void testSelectWithJoin() throws QueryParseException {
        ParsedQuery query = parser.parse(
            "SELECT * FROM orders o JOIN products p ON o.productId = p.id"
        );
        
        assertTrue(query.hasJoins());
        assertEquals(1, query.getJoins().size());
        
        JoinInfo join = query.getJoins().get(0);
        assertFalse(join.isLeftJoin());
        assertEquals("products", join.getTable().getTableName());
        assertEquals("p", join.getTable().getAlias());
        assertNotNull(join.getOnCondition());
    }

    @Test
    void testSelectWithLeftJoin() throws QueryParseException {
        ParsedQuery query = parser.parse(
            "SELECT * FROM orders o LEFT JOIN products p ON o.productId = p.id"
        );
        
        assertTrue(query.hasJoins());
        JoinInfo join = query.getJoins().get(0);
        assertTrue(join.isLeftJoin());
    }

    @Test
    void testSelectWithMultipleJoins() throws QueryParseException {
        ParsedQuery query = parser.parse(
            "SELECT * FROM orders o " +
            "JOIN products p ON o.productId = p.id " +
            "LEFT JOIN customers c ON o.customerId = c.id"
        );
        
        assertEquals(2, query.getJoins().size());
        
        JoinInfo firstJoin = query.getJoins().get(0);
        assertFalse(firstJoin.isLeftJoin());
        assertEquals("products", firstJoin.getTable().getTableName());
        
        JoinInfo secondJoin = query.getJoins().get(1);
        assertTrue(secondJoin.isLeftJoin());
        assertEquals("customers", secondJoin.getTable().getTableName());
    }

    @Test
    void testSelectWithTop() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT TOP 10 * FROM products");
        
        assertNotNull(query.getTop());
        assertEquals(10L, query.getTop());
        assertEquals(10L, query.getEffectiveLimit());
    }

    @Test
    void testSelectWithLimit() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT * FROM products LIMIT 5");
        
        assertNotNull(query.getLimit());
        assertEquals(5L, query.getLimit());
        assertEquals(5L, query.getEffectiveLimit());
    }

    @Test
    void testComplexQuery() throws QueryParseException {
        ParsedQuery query = parser.parse(
            "SELECT TOP 10 p.name, o.quantity, o.orderDate " +
            "FROM orders o " +
            "JOIN products p ON o.productId = p.id " +
            "WHERE o.status = 'active'"
        );
        
        assertEquals(3, query.getSelectColumns().size());
        assertEquals("p.name", query.getSelectColumns().get(0).getExpression());
        assertEquals("o.quantity", query.getSelectColumns().get(1).getExpression());
        assertEquals("o.orderDate", query.getSelectColumns().get(2).getExpression());
        assertTrue(query.hasJoins());
        assertNotNull(query.getWhereClause());
        assertEquals(10L, query.getTop());
    }

    @Test
    void testInvalidSyntax() {
        assertThrows(QueryParseException.class, () -> 
            parser.parse("INVALID SQL QUERY")
        );
    }

    @Test
    void testNonSelectStatement() {
        assertThrows(QueryParseException.class, () -> 
            parser.parse("INSERT INTO products VALUES (1, 'test')")
        );
    }

    @Test
    void testMissingFromClause() {
        assertThrows(QueryParseException.class, () -> 
            parser.parse("SELECT * ")
        );
    }

    @Test
    void testJoinWithoutOnCondition() {
        assertThrows(QueryParseException.class, () -> 
            parser.parse("SELECT * FROM orders o JOIN products p")
        );
    }

    @Test
    void testSelectWithOrderBy() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT * FROM products ORDER BY price");
        
        assertTrue(query.hasOrderBy());
        assertEquals(1, query.getOrderBy().size());
        assertEquals("price", query.getOrderBy().get(0).getColumn());
        assertTrue(query.getOrderBy().get(0).isAscending());
    }

    @Test
    void testSelectWithOrderByDesc() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT * FROM products ORDER BY price DESC");
        
        assertTrue(query.hasOrderBy());
        assertEquals(1, query.getOrderBy().size());
        assertEquals("price", query.getOrderBy().get(0).getColumn());
        assertFalse(query.getOrderBy().get(0).isAscending());
    }

    @Test
    void testSelectWithOrderByAsc() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT * FROM products ORDER BY name ASC");
        
        assertTrue(query.hasOrderBy());
        assertEquals(1, query.getOrderBy().size());
        assertEquals("name", query.getOrderBy().get(0).getColumn());
        assertTrue(query.getOrderBy().get(0).isAscending());
    }

    @Test
    void testSelectWithMultipleOrderBy() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT * FROM products ORDER BY category ASC, price DESC");
        
        assertTrue(query.hasOrderBy());
        assertEquals(2, query.getOrderBy().size());
        
        assertEquals("category", query.getOrderBy().get(0).getColumn());
        assertTrue(query.getOrderBy().get(0).isAscending());
        
        assertEquals("price", query.getOrderBy().get(1).getColumn());
        assertFalse(query.getOrderBy().get(1).isAscending());
    }

    @Test
    void testComplexQueryWithOrderBy() throws QueryParseException {
        ParsedQuery query = parser.parse(
            "SELECT TOP 10 p.name, o.quantity " +
            "FROM orders o " +
            "JOIN products p ON o.productId = p.id " +
            "WHERE o.status = 'active' " +
            "ORDER BY o.quantity DESC"
        );
        
        assertTrue(query.hasOrderBy());
        assertEquals(1, query.getOrderBy().size());
        assertEquals("o.quantity", query.getOrderBy().get(0).getColumn());
        assertFalse(query.getOrderBy().get(0).isAscending());
    }

    @Test
    void testSelectWithAlias() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT name AS productName, price AS productPrice FROM products");
        
        assertEquals(2, query.getSelectColumns().size());
        
        // First column
        assertEquals("name", query.getSelectColumns().get(0).getExpression());
        assertEquals("productName", query.getSelectColumns().get(0).getAlias());
        assertTrue(query.getSelectColumns().get(0).hasAlias());
        
        // Second column
        assertEquals("price", query.getSelectColumns().get(1).getExpression());
        assertEquals("productPrice", query.getSelectColumns().get(1).getAlias());
        assertTrue(query.getSelectColumns().get(1).hasAlias());
    }

    @Test
    void testSelectWithMixedAliases() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT p.id AS productId, p.name, p.price AS cost FROM products p");
        
        assertEquals(3, query.getSelectColumns().size());
        
        // With alias
        assertTrue(query.getSelectColumns().get(0).hasAlias());
        assertEquals("productId", query.getSelectColumns().get(0).getAlias());
        
        // Without alias
        assertFalse(query.getSelectColumns().get(1).hasAlias());
        assertEquals("p.name", query.getSelectColumns().get(1).getExpression());
        
        // With alias
        assertTrue(query.getSelectColumns().get(2).hasAlias());
        assertEquals("cost", query.getSelectColumns().get(2).getAlias());
    }
}

