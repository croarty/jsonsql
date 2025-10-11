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
        assertEquals(List.of("*"), query.getSelectColumns());
        assertEquals("products", query.getFromTable().getTableName());
        assertNull(query.getFromTable().getAlias());
        assertFalse(query.hasJoins());
    }

    @Test
    void testSelectWithColumns() throws QueryParseException {
        ParsedQuery query = parser.parse("SELECT name, price, category FROM products");
        
        assertEquals(3, query.getSelectColumns().size());
        assertTrue(query.getSelectColumns().contains("name"));
        assertTrue(query.getSelectColumns().contains("price"));
        assertTrue(query.getSelectColumns().contains("category"));
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
}

