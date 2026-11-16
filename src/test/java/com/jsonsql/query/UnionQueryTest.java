package com.jsonsql.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The operators between branches, and the column-count message, which says UNION when
 * every operator is a UNION, as it always has, and otherwise names the operator.
 */
class UnionQueryTest {

    private static UnionQuery withBranches(int... columnCounts) {
        UnionQuery union = new UnionQuery();
        for (int count : columnCounts) {
            ParsedQuery query = new ParsedQuery();
            List<ColumnInfo> columns = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                columns.add(new ColumnInfo("c" + i, null));
            }
            query.setSelectColumns(columns);
            union.addSubQuery(query);
        }
        return union;
    }

    @Test
    void operatorsAreKeptInOrder() {
        UnionQuery union = withBranches(1, 1, 1, 1, 1);
        union.addOperation(UnionQuery.Operator.INTERSECT);
        union.addOperation(UnionQuery.Operator.UNION_ALL);
        union.addOperation(UnionQuery.Operator.EXCEPT);
        union.addOperation(UnionQuery.Operator.UNION);

        assertEquals(UnionQuery.Operator.INTERSECT, union.operatorAt(0));
        assertEquals(UnionQuery.Operator.UNION_ALL, union.operatorAt(1));
        assertEquals(UnionQuery.Operator.EXCEPT, union.operatorAt(2));
        assertEquals(UnionQuery.Operator.UNION, union.operatorAt(3));
    }

    @Test
    void eachOperatorHasItsSqlText() {
        assertEquals(List.of("UNION", "UNION ALL", "INTERSECT", "EXCEPT"),
            List.of(UnionQuery.Operator.values()).stream().map(UnionQuery.Operator::sql).toList());
    }

    @Test
    void anAllUnionStatementKeepsItsMessage() {
        UnionQuery union = withBranches(2, 2, 1);
        union.addOperation(UnionQuery.Operator.UNION_ALL);
        union.addOperation(UnionQuery.Operator.UNION);

        QueryParseException ex = assertThrows(QueryParseException.class, union::validateColumnConsistency);
        assertEquals("UNION queries must have the same number of columns. Query 1 has 2 columns, query 3 has 1 columns.",
            ex.getMessage());
    }

    @Test
    void otherwiseTheMessageNamesTheOperatorBeforeTheBranch() {
        UnionQuery intersect = withBranches(2, 1);
        intersect.addOperation(UnionQuery.Operator.INTERSECT);
        assertEquals("INTERSECT queries must have the same number of columns. Query 1 has 2 columns, query 2 has 1 columns.",
            assertThrows(QueryParseException.class, intersect::validateColumnConsistency).getMessage());

        UnionQuery mixed = withBranches(1, 1, 2);
        mixed.addOperation(UnionQuery.Operator.EXCEPT);
        mixed.addOperation(UnionQuery.Operator.UNION_ALL);
        assertEquals("UNION queries must have the same number of columns. Query 1 has 1 columns, query 3 has 2 columns.",
            assertThrows(QueryParseException.class, mixed::validateColumnConsistency).getMessage());
    }

    @Test
    void matchingColumnCountsPass() throws Exception {
        UnionQuery union = withBranches(3, 3);
        union.addOperation(UnionQuery.Operator.EXCEPT);
        union.validateColumnConsistency();
    }
}
