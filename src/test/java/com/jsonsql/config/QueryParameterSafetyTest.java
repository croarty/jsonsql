package com.jsonsql.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A value passed with --param must stay a value and never change the query around it.
 */
class QueryParameterSafetyTest {

    private static String replace(String query, String value) {
        return QueryParameterReplacer.replaceParameters(query, Map.of("v", value));
    }

    @Test
    void apostropheInsideQuotesIsEscaped() {
        assertEquals("SELECT id FROM t WHERE name = 'O''Brien'", replace("SELECT id FROM t WHERE name = '${v}'", "O'Brien"));
    }

    @Test
    void aValueCannotCloseTheStringItIsIn() {
        assertEquals("SELECT id FROM t WHERE name = 'x'' OR ''a''=''a'",
            replace("SELECT id FROM t WHERE name = '${v}'", "x' OR 'a'='a"));
    }

    @Test
    void placeholderInsideALargerStringIsEscaped() {
        assertEquals("WHERE name LIKE '%it''s%'", replace("WHERE name LIKE '%${v}%'", "it's"));
    }

    @Test
    void wildcardsInsideQuotesAreKept() {
        assertEquals("WHERE name LIKE '%a_b%'", replace("WHERE name LIKE '${v}'", "%a_b%"));
    }

    @Test
    void aPlaceholderAfterAStringIsOutsideIt() {
        assertEquals("WHERE a = 'x' AND b > 5", replace("WHERE a = 'x' AND b > ${v}", "5"));
    }

    @Test
    void anEscapedQuoteBeforeAPlaceholderIsCountedCorrectly() {
        assertEquals("WHERE a = 'it''s ''x'''", replace("WHERE a = 'it''s ''${v}'''", "x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "-5", "+5", "3.14", "1e3", "1.5E-2", "true", "FALSE", "1,2,3", "1, 2.5, -3"})
    void numbersAndBooleansWorkOutsideQuotes(String value) {
        assertEquals("WHERE a IN (" + value + ")", replace("WHERE a IN (${v})", value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"'x'", "'a', 'b'", "'it''s', 'b'", "1, 'a'", "''"})
    void quotedStringsAndListsWorkOutsideQuotes(String value) {
        assertEquals("WHERE a IN (" + value + ")", replace("WHERE a IN (${v})", value));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SELECT id FROM t ORDER BY ${v}                 | price",
        "SELECT id FROM t ORDER BY name, ${v}           | price",
        "SELECT id FROM t ORDER BY price ${v}           | DESC",
        "SELECT id FROM t ORDER BY ${v}                 | p.price",
        "SELECT id FROM t ORDER BY ${v}                 | name, price",
        "SELECT ${v} FROM t                             | name",
        "SELECT ${v} FROM t                             | name, price",
        "SELECT DISTINCT ${v} FROM t                    | category",
        "SELECT id, ${v} FROM t                         | name",
        "SELECT id FROM ${v}                            | products",
        "SELECT a.id FROM a JOIN ${v} ON a.id = b.id    | b",
        "SELECT id FROM t WHERE a = 1 ORDER BY ${v}     | price",
        "select id from t order   by ${v}               | price",
    })
    void namesWorkWhereTheQueryNamesSomething(String query, String value) {
        assertEquals(query.replace("${v}", value), replace(query, value));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT id FROM t WHERE a > ${v}", "SELECT id FROM t WHERE a = 1 AND b = ${v}", "SELECT id FROM t WHERE a IN (${v})",
        "SELECT id FROM t WHERE ${v} = 1", "SELECT a.id FROM a JOIN b ON a.id = ${v}", "SELECT id FROM t LIMIT ${v}",
        "SELECT id FROM t ORDER BY id LIMIT ${v}",
    })
    void namesAreRejectedInConditionsAndLimits(String query) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> replace(query, "abc"));

        assertTrue(e.getMessage().contains("must be a number, a boolean or a quoted string"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "price; DROP TABLE t", "price DESC", "price, (1)", "price OR 1=1", "a b", "1abc", "price -- x", "price,", "name FROM other",
    })
    void onlyPlainNamesWorkWhereNamesAreAllowed(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> replace("SELECT id FROM t ORDER BY ${v}", value));

        assertTrue(e.getMessage().contains("must be a name"), e.getMessage());
    }

    @Test
    void aKeywordInsideAStringDoesNotChangeThePosition() {
        assertThrows(IllegalArgumentException.class,
            () -> replace("SELECT id FROM t WHERE a = 'ORDER BY' AND b > ${v}", "abc"));
    }

    @Test
    void literalsStillWorkWhereNamesAreAllowed() {
        assertEquals("SELECT TOP 5 id FROM t", replace("SELECT TOP ${v} id FROM t", "5"));
        assertEquals("SELECT id FROM t ORDER BY 2", replace("SELECT id FROM t ORDER BY ${v}", "2"));
    }

    @Test
    void theNearestClauseDecides() {
        assertEquals("SELECT id FROM t WHERE a = 1 ORDER BY b",
            QueryParameterReplacer.replaceParameters("SELECT id FROM t WHERE a = ${x} ORDER BY ${y}", Map.of("x", "1", "y", "b")));
        assertThrows(IllegalArgumentException.class, () ->
            QueryParameterReplacer.replaceParameters("SELECT id FROM t WHERE a = ${x} ORDER BY ${y}", Map.of("x", "b", "y", "b")));
    }

    @Test
    void surroundingSpaceIsTrimmedOutsideQuotes() {
        assertEquals("WHERE a > 5", replace("WHERE a > ${v}", " 5 "));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0 OR 1=1", "1; DROP TABLE t", "x", "1 UNION SELECT id FROM other", "name", "--", "1,", ",1", "0x10", "1 2",
        "'x' OR 'a'='a'", "'x", "'x'y'", "'a', b", "'a' 'b'", "1, 'a' OR 1=1",
    })
    void anythingElseOutsideQuotesIsRejected(String value) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> replace("SELECT id FROM t WHERE a > ${v}", value));

        assertTrue(e.getMessage().contains("must be a number, a boolean or a quoted string"), e.getMessage());
        assertTrue(e.getMessage().contains("'${v}'"), e.getMessage());
    }

    @Test
    void anEmptyValueIsRejectedOutsideQuotes() {
        assertThrows(IllegalArgumentException.class, () -> replace("WHERE a > ${v:5}", ""));
    }

    @Test
    void defaultValuesAreUsedAsWritten() {
        String query = "SELECT id FROM t ORDER BY ${v:price DESC}";

        assertEquals("SELECT id FROM t ORDER BY price DESC",
            QueryParameterReplacer.replaceParameters(query, Map.of()));
    }

    @Test
    void aDefaultCanStillBeOverriddenWithASafeValue() {
        assertEquals("WHERE a > 9", replace("WHERE a > ${v:5}", "9"));
    }

    @Test
    void aDollarSignInAValueIsLiteral() {
        assertEquals("WHERE a = '$5'", replace("WHERE a = '${v}'", "$5"));
    }

    @Test
    void aPlaceholderInAValueIsNotExpandedAgain() {
        assertEquals("WHERE a = '${w}'",
            QueryParameterReplacer.replaceParameters("WHERE a = '${v}'", Map.of("v", "${w}", "w", "x")));
    }
}
