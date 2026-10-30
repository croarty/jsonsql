package com.jsonsql.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
