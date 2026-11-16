package com.jsonsql.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsonsql.config.MappingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Constructs that JSqlParser accepts but the engine does not implement must be rejected at
 * parse time with a clear error, instead of silently producing wrong results.
 */
class UnsupportedSyntaxTest {

    @TempDir
    Path tempDir;

    private final QueryParser parser = new QueryParser();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private QueryExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        File dataDir = tempDir.resolve("data").toFile();
        dataDir.mkdirs();
        Files.writeString(dataDir.toPath().resolve("products.json"), """
            {"products": [
              {"id": 1, "name": "Widget", "price": 19.99, "category": "Tools", "tags": ["a", "b"]},
              {"id": 2, "name": "Gadget", "price": 29.99, "category": "Electronics", "tags": ["b"]}
            ]}
            """);
        Files.writeString(dataDir.toPath().resolve("orders.json"), """
            {"orders": [{"orderId": 10, "productId": 1}, {"orderId": 11, "productId": 3}]}
            """);
        MappingManager mappingManager = new MappingManager(tempDir.resolve("mappings.json").toFile());
        mappingManager.addMapping("products", "products.json:$.products");
        mappingManager.addMapping("orders", "orders.json:$.orders");
        executor = new QueryExecutor(mappingManager, dataDir);
    }

    private QueryParseException rejected(String sql) {
        return assertThrows(QueryParseException.class, () -> parser.parse(sql), sql);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "SELECT name FROM products OFFSET 5 ROWS FETCH NEXT 2 ROWS ONLY         | FETCH is not supported",
        "SELECT o.orderId FROM orders o NATURAL JOIN products p                 | NATURAL JOIN is not supported (write JOIN ... ON with the matching fields)",
        "SELECT o.orderId FROM orders o CROSS JOIN products p ON o.productId = p.id | ON after CROSS JOIN is not supported (use JOIN ... ON, or CROSS JOIN without ON)",
        "SELECT o.orderId FROM orders o, products p ON o.productId = p.id       | ON after a comma join is not supported (use JOIN ... ON, or a comma without ON)",
        "SELECT o.orderId FROM orders o RIGHT JOIN products p USING (id)        | JOIN ... USING is not supported (write ON a.x = b.x instead)",
        "SELECT o.orderId FROM orders o FULL JOIN products p ON SUM(p.id) = 1   | Function in JOIN ON: SUM(p.id) = 1 is not supported",
        "SELECT o.orderId FROM orders o JOIN products p                         | JOIN requires ON condition",
        "SELECT p.* FROM products p                                             | SELECT p.* is not supported",
        "SELECT SOUNDEX(name) FROM products                                       | Function or aggregate in SELECT list: SOUNDEX(name) is not supported",
        "SELECT price * HEX(name) AS x FROM products                         | Function or aggregate in SELECT list: price * HEX(name) is not supported",
        "SELECT SUM(SOUNDEX(name)) FROM products                                  | Function or aggregate in SELECT list: SUM(SOUNDEX(name)) is not supported",
        "SELECT (SELECT 1) FROM products                                        | Expression in SELECT list: (SELECT 1) is not supported",
        "SELECT CAST(price AS DATE) FROM products                               | CAST to DATE in SELECT list: CAST(price AS DATE) is not supported (use INTEGER, REAL or TEXT)",
        "SELECT category, price * 2 FROM products GROUP BY category             | Column 'price' must be in GROUP BY or inside an aggregate",
        "SELECT COUNT(*), price + 1 FROM products                               | Column 'price' must be in GROUP BY or inside an aggregate",
        "SELECT CASE WHEN price THEN 1 END FROM products                        | Condition in SELECT list",
        "SELECT price IN (SELECT 1) FROM products                               | Condition in SELECT list",
        "SELECT NOT name FROM products                                          | Condition in SELECT list",
        "SELECT name FROM products ORDER BY SOUNDEX(name)                         | Function in ORDER BY: SOUNDEX(name) is not supported",
        "SELECT name FROM products MINUS SELECT name FROM products              | MINUS is not supported (write EXCEPT)",
        "SELECT name FROM products INTERSECT ALL SELECT name FROM products      | INTERSECT ALL is not supported (write INTERSECT, which removes duplicates)",
        "SELECT name FROM products EXCEPT ALL SELECT name FROM products         | EXCEPT ALL is not supported (write EXCEPT, which removes duplicates)",
        "SELECT name FROM products except distinct SELECT name FROM products    | EXCEPT DISTINCT is not supported (write EXCEPT, which removes duplicates)",
        "SELECT name FROM products UNION SELECT name FROM products INTERSECT SELECT name FROM products | INTERSECT after UNION is not supported (databases disagree on which runs first; write the INTERSECT first, as in SELECT ... INTERSECT SELECT ... UNION SELECT ...)",
        "SELECT name FROM products UNION ALL SELECT name FROM products INTERSECT SELECT name FROM products | INTERSECT after UNION ALL is not supported",
        "SELECT name FROM products EXCEPT SELECT name FROM products INTERSECT SELECT name FROM products | INTERSECT after EXCEPT is not supported",
        "SELECT name FROM products INTERSECT SELECT name FROM products UNION SELECT name FROM products INTERSECT SELECT name FROM products | INTERSECT after UNION is not supported",
        "(SELECT name FROM products UNION SELECT name FROM products) INTERSECT SELECT name FROM products | Parentheses around UNION, INTERSECT or EXCEPT are not supported (they run left to right, so write them in the order they should run)",
        "SELECT name FROM products EXCEPT (SELECT name FROM products INTERSECT SELECT name FROM products) | Parentheses around UNION, INTERSECT or EXCEPT are not supported",
        "SELECT name, price FROM products INTERSECT SELECT name FROM products   | INTERSECT queries must have the same number of columns. Query 1 has 2 columns, query 2 has 1 columns.",
        "SELECT name FROM products UNION SELECT name FROM products EXCEPT SELECT name, price FROM products | EXCEPT queries must have the same number of columns. Query 1 has 1 columns, query 3 has 2 columns.",
        "SELECT name, price FROM products UNION SELECT name FROM products       | UNION queries must have the same number of columns. Query 1 has 2 columns, query 2 has 1 columns.",
        "SELECT o.orderId FROM orders o JOIN products p USING (id)              | JOIN ... USING is not supported (write ON a.x = b.x instead)",
        "SELECT o.orderId FROM orders o JOIN products p ON o.productId = p.id ON o.orderId = p.id | More than one ON for JOIN products p is not supported (join the conditions with AND)",
        "SELECT o.orderId FROM orders o JOIN products p ON SUM(p.id) = o.productId | Function in JOIN ON: SUM(p.id) = o.productId is not supported (an aggregate can't be used here)",
        "SELECT o.orderId FROM orders o JOIN products p ON o.productId = p.id AND COUNT(*) > 1 | Function in JOIN ON: o.productId = p.id AND COUNT(*) > 1 is not supported (an aggregate can't be used here)",
        "SELECT o.orderId FROM orders o JOIN products p ON SOUNDEX(p.name) = o.productId | Function in JOIN ON: SOUNDEX(p.name) = o.productId is not supported (available functions:",
        "SELECT o.orderId FROM orders o JOIN products p ON UPPER(p.name, p.id) = o.productId | UPPER takes 1 argument",
        "SELECT o.orderId FROM orders o JOIN products p ON o.productId IN (SELECT id FROM products) | Condition in JOIN ON: o.productId IN (SELECT id FROM products) is not supported",
        "SELECT o.orderId FROM orders o JOIN products p ON o.productId = (SELECT 1) | Expression in JOIN ON: o.productId = (SELECT 1) is not supported",
        "SELECT o.orderId FROM orders o JOIN products p ON EXISTS (SELECT 1 FROM products) | Condition in JOIN ON: EXISTS (SELECT 1 FROM products) is not supported",
        "SELECT o.orderId FROM orders o JOIN products p ON p.id                 | Condition in JOIN ON: p.id is not supported (compare values with",
        "SELECT o.orderId FROM orders o JOIN products p ON CAST(p.id AS DATE) = o.productId | CAST to DATE in JOIN ON",
    })
    void unsupportedConstructIsRejectedWithClearMessage(String sql, String expectedMessage) {
        QueryParseException ex = rejected(sql);
        assertTrue(ex.getMessage().contains(expectedMessage),
            "Expected message containing '" + expectedMessage + "' but got: " + ex.getMessage());
    }

    @Test
    void functionJoinedToTextIsRejected() {
        assertTrue(rejected("SELECT SOUNDEX(name) || 'x' FROM products").getMessage()
            .contains("Function or aggregate in SELECT list: SOUNDEX(name) || 'x' is not supported"));
    }

    @Test
    void hintTellsUserHowToProjectAllColumns() {
        assertTrue(rejected("SELECT p.* FROM products p").getMessage().contains("use SELECT *"));
    }

    @Test
    void intersectAfterIntersectIsAllowed() throws Exception {
        assertEquals("[{\"name\":\"Widget\"}]", executor.execute("SELECT name FROM products INTERSECT SELECT name FROM products"
            + " INTERSECT SELECT name FROM products WHERE id = 1"));
    }

    @Test
    void aRejectedOrderingIsReportedByDryRun() {
        QueryParseException ex = assertThrows(QueryParseException.class, () -> executor.dryRunValidate(
            "SELECT name FROM products EXCEPT SELECT name FROM orders INTERSECT SELECT name FROM products"));
        assertTrue(ex.getMessage().startsWith("INTERSECT after EXCEPT"), ex.getMessage());
    }

    @Test
    void rejectedInsideCteBody() {
        QueryParseException ex = rejected(
            "WITH c AS (SELECT SOUNDEX(name) FROM products) SELECT * FROM c");
        assertTrue(ex.getMessage().contains("SOUNDEX(name)"));
    }

    @Test
    void rejectedInsideUnionBranch() {
        QueryParseException ex = rejected(
            "SELECT name FROM products UNION ALL SELECT SOUNDEX(name) FROM products");
        assertTrue(ex.getMessage().contains("SOUNDEX(name)"));
    }

    @Test
    void rejectedInJoinedQueryEvenWhenLaterJoinsAreValid() {
        QueryParseException ex = rejected("SELECT o.orderId FROM orders o "
            + "JOIN products p ON o.productId = p.id NATURAL JOIN products q");
        assertTrue(ex.getMessage().contains("NATURAL JOIN"));
    }

    @Test
    void executorSurfacesParseErrorInsteadOfReturningRows() {
        assertThrows(QueryParseException.class,
            () -> executor.execute("SELECT SOUNDEX(category) FROM products"));
        assertThrows(QueryParseException.class,
            () -> executor.execute("SELECT o.orderId FROM orders o NATURAL JOIN products p"));
    }

    @Test
    void dryRunAlsoRejects() {
        assertThrows(QueryParseException.class,
            () -> executor.dryRunValidate("SELECT SOUNDEX(name) FROM products"));
        assertThrows(QueryParseException.class,
            () -> executor.dryRunValidate("SELECT o.orderId FROM orders o JOIN products p ON SUM(p.id) = o.productId"));
    }

    @Test
    void aBadJoinConditionIsRejectedInsideACteAndAUnionBranch() {
        assertTrue(rejected("WITH c AS (SELECT o.orderId FROM orders o JOIN products p ON SOUNDEX(p.name) = o.productId) "
            + "SELECT * FROM c").getMessage().contains("Function in JOIN ON"));
        assertTrue(rejected("SELECT name FROM products UNION SELECT p.name FROM orders o JOIN products p ON p.id")
            .getMessage().contains("Condition in JOIN ON"));
    }

    // -- forms that must remain accepted ----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM products",
        "SELECT DISTINCT * FROM products",
        "SELECT TOP 1 * FROM products",
        "SELECT name, price AS cost, category c FROM products",
        "SELECT p.name, p.price FROM products p",
        "SELECT o.orderId, p.name FROM orders o JOIN products p ON o.productId = p.id",
        "SELECT o.orderId, p.name FROM orders o INNER JOIN products p ON o.productId = p.id",
        "SELECT o.orderId, p.name FROM orders o LEFT JOIN products p ON o.productId = p.id",
        "SELECT o.orderId, p.name FROM orders o LEFT OUTER JOIN products p ON o.productId = p.id",
        "SELECT o.orderId, p.name FROM orders o JOIN products p ON o.productId = p.id AND p.price > 1",
        "SELECT o.orderId, p.name FROM orders o JOIN products p ON o.productId <= p.id OR p.name LIKE 'W%'",
        "SELECT o.orderId, p.name FROM orders o JOIN products p ON UPPER(p.category) = 'TOOLS'",
        "SELECT o.orderId, p.name FROM orders o RIGHT JOIN products p ON o.productId = p.id",
        "SELECT o.orderId, p.name FROM orders o RIGHT OUTER JOIN products p ON o.productId = p.id",
        "SELECT o.orderId, p.name FROM orders o FULL JOIN products p ON o.productId = p.id",
        "SELECT o.orderId, p.name FROM orders o FULL OUTER JOIN products p ON o.productId = p.id",
        "SELECT o.orderId, p.name FROM orders o CROSS JOIN products p",
        "SELECT o.orderId, p.name FROM orders o, products p",
        "SELECT name, tag FROM products, UNNEST(tags) AS t(tag)",
        "SELECT name, tag FROM products JOIN UNNEST(tags) AS t(tag)",
        "SELECT name, tag FROM products CROSS JOIN UNNEST(tags) AS t(tag)",
        "SELECT name FROM products ORDER BY price DESC LIMIT 1",
        "SELECT name FROM products UNION SELECT name FROM products",
        "SELECT name FROM products UNION ALL SELECT name FROM products",
        "SELECT name FROM products INTERSECT SELECT name FROM products",
        "SELECT name FROM products EXCEPT SELECT name FROM orders",
        "SELECT name FROM products INTERSECT SELECT name FROM products UNION SELECT name FROM orders",
        "SELECT name FROM products EXCEPT SELECT name FROM orders UNION ALL SELECT name FROM products",
        "(SELECT name FROM products) INTERSECT (SELECT name FROM products)",
    })
    void supportedFormsStillExecute(String sql) throws Exception {
        JsonNode rows = objectMapper.readTree(executor.execute(sql));
        assertTrue(rows.isArray(), sql);
        assertFalse(rows.isEmpty(), sql);
    }

    @Test
    void leftOuterJoinKeepsUnmatchedRows() throws Exception {
        JsonNode rows = objectMapper.readTree(executor.execute(
            "SELECT o.orderId, p.name FROM orders o LEFT OUTER JOIN products p ON o.productId = p.id"));
        assertEquals(2, rows.size());
        assertTrue(rows.get(1).get("name").isNull());
    }

    @Test
    void crossJoinUnnestFlattensArray() throws Exception {
        JsonNode rows = objectMapper.readTree(executor.execute(
            "SELECT name, tag FROM products CROSS JOIN UNNEST(tags) AS t(tag)"));
        assertEquals(3, rows.size());
    }
}
