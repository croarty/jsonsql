package com.jsonsql.query;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How an ON condition splits into join keys, looked up through an index, and the rest,
 * checked on each pair of rows.
 */
class JoinConditionKeysTest {

    private static JoinCondition split(String on, String joined, String... earlier) throws Exception {
        TableInfo table = new TableInfo();
        table.setTableName(joined);
        return JoinCondition.of(CCJSqlParserUtil.parseCondExpression(on), table, Set.of(earlier));
    }

    private static List<String> keys(JoinCondition condition) {
        return condition.keys().stream().map(key -> key.leftField() + "=" + key.rightField()).toList();
    }

    private static String residual(JoinCondition condition) {
        return condition.residual() == null ? null : condition.residual().toString();
    }

    @Test
    void aSingleEqualityIsAKey() throws Exception {
        JoinCondition condition = split("o.pid = p.id", "p", "o");

        assertEquals(List.of("o.pid=p.id"), keys(condition));
        assertNull(condition.residual());
    }

    @Test
    void theJoinedTableGoesOnTheRight() throws Exception {
        assertEquals(List.of("o.pid=p.id"), keys(split("p.id = o.pid", "p", "o")));
    }

    @Test
    void equalitiesJoinedByAndAreAllKeysAndTheRestIsChecked() throws Exception {
        JoinCondition condition = split("o.pid = p.id AND p.price > 5 AND p.region = o.region AND o.st = 'open'", "p", "o");

        assertEquals(List.of("o.pid=p.id", "o.region=p.region"), keys(condition));
        assertEquals("p.price > 5 AND o.st = 'open'", residual(condition));
    }

    @Test
    void withNoTableNamesTheWrittenOrderIsKept() throws Exception {
        assertEquals(List.of("pid=id"), keys(split("pid = id", "p", "o")));
        assertEquals(List.of("id=pid"), keys(split("id = pid", "p", "o")));
    }

    @Test
    void aNameWithoutATableIsReadFromTheRowsSoFar() throws Exception {
        assertEquals(List.of("pid=p.id"), keys(split("p.id = pid", "p", "o")));
        assertEquals(List.of("o.pid=id"), keys(split("o.pid = id", "p", "o")));
    }

    @Test
    void twoFieldsOfTheJoinedTableAreAFilterNotAKey() throws Exception {
        JoinCondition condition = split("p.a = p.b", "p", "o");

        assertTrue(condition.keys().isEmpty());
        assertEquals("p.a = p.b", residual(condition));
    }

    @Test
    void twoFieldsOfEarlierTablesAreAFilterNotAKey() throws Exception {
        assertEquals("o.a = o.b", residual(split("o.a = o.b", "p", "o")));
        assertEquals("o.region = c.region", residual(split("o.region = c.region", "p", "o", "c")));
        assertTrue(split("o.region = c.region", "p", "o", "c").keys().isEmpty());
    }

    @Test
    void aJoinedTableWithTheSameNameAsAnEarlierOneKeepsTheWrittenOrder() throws Exception {
        // FROM emp JOIN emp ON emp.boss = emp.id: ambiguous, so it stays a key, as in 3.0.0
        JoinCondition condition = split("emp.boss = emp.id", "emp", "emp");

        assertEquals(List.of("emp.boss=emp.id"), keys(condition));
        assertNull(condition.residual());
    }

    @Test
    void aNestedPathIsNotATableName() throws Exception {
        assertEquals(List.of("profile.level=p.level"), keys(split("p.level = profile.level", "p", "o")));
        assertEquals(List.of("o.profile.level=p.level"), keys(split("p.level = o.profile.level", "p", "o")));
    }

    @Test
    void anAliasIsTheJoinedTablesName() throws Exception {
        TableInfo table = new TableInfo();
        table.setTableName("products");
        table.setAlias("p");
        JoinCondition condition = JoinCondition.of(
            CCJSqlParserUtil.parseCondExpression("p.id = products.id"), table, Set.of("o"));

        assertEquals(List.of("products.id=p.id"), keys(condition));
    }

    @Test
    void trueAndFalseAreValuesNotFields() throws Exception {
        JoinCondition condition = split("o.pid = p.id AND p.inStock = true AND FALSE = o.gift", "p", "o");

        assertEquals(List.of("o.pid=p.id"), keys(condition));
        assertEquals("p.inStock = true AND FALSE = o.gift", residual(condition));
    }

    @Test
    void aDoubleQuotedNameIsAFilterBecauseItMayBeText() throws Exception {
        // As in SQLite, "x" is text when there is no field x, which only the whole row can tell
        JoinCondition condition = split("\"p\".\"id\" = o.pid AND p.cat = \"Tools\"", "p", "o");

        assertTrue(condition.keys().isEmpty());
        assertEquals("\"p\".\"id\" = o.pid AND p.cat = \"Tools\"", residual(condition));
    }

    @Test
    void orAtTheTopHasNoKeys() throws Exception {
        JoinCondition condition = split("o.pid = p.id OR o.code = p.code", "p", "o");

        assertTrue(condition.keys().isEmpty());
        assertEquals("o.pid = p.id OR o.code = p.code", residual(condition));
    }

    @Test
    void parenthesesAroundTheWholeConditionAreIgnored() throws Exception {
        JoinCondition condition = split("(o.pid = p.id AND o.region = p.region)", "p", "o");

        assertEquals(List.of("o.pid=p.id", "o.region=p.region"), keys(condition));
        assertNull(condition.residual());
    }

    @Test
    void parenthesesAroundAPartMakeItAFilter() throws Exception {
        JoinCondition condition = split("o.pid = p.id AND (o.region = p.region)", "p", "o");

        assertEquals(List.of("o.pid=p.id"), keys(condition));
        assertEquals("(o.region = p.region)", residual(condition));
    }

    @Test
    void calculationsAndLiteralsAreFilters() throws Exception {
        JoinCondition condition = split("LOWER(o.code) = LOWER(p.code) AND o.pid = 3 AND o.n + 1 = p.n", "p", "o");

        assertTrue(condition.keys().isEmpty());
        assertEquals("LOWER(o.code) = LOWER(p.code) AND o.pid = 3 AND o.n + 1 = p.n", residual(condition));
    }

    @Test
    void otherComparisonsAreFilters() throws Exception {
        JoinCondition condition = split("p.price >= b.low AND p.price < b.high", "b", "p");

        assertTrue(condition.keys().isEmpty());
        assertEquals("p.price >= b.low AND p.price < b.high", residual(condition));
    }
}
